#!/usr/bin/env bash
# Set up ClickHouse HEAD + sqlancer per .claude/CLAUDE.md, run the fuzzer,
# then tear down the container on exit.
#
# For long runs, wrap with nohup so an SSH disconnect doesn't kill it:
#   nohup ./.claude/run-sqlancer.sh --duration 28800 --oracles all \
#     --ch-mem 20g --heap 24g --threads 8 > run.out 2>&1 &

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# --- defaults (overridable via flags) -----------------------------------------
NAME="clickhouse-server-perf"
PORT="18124"
CH_CPUS="6"
CH_MEM="28g"
HEAP="12g"
THREADS="6"
DURATION="1800"
ORACLES="TLPWhere"
REBUILD=0
KEEP=0
# Extra args appended to the `clickhouse` subcommand (DBMS-specific JCommander flags,
# e.g. --extra-ch-args "--eet-26x-modes true --variant-where-emission true").
EXTRA_CH_ARGS=""

# RowPolicy temporarily removed (2026-05-31): it dominates all-oracle run noise (Code 49/162/306)
# and is commented out in ClickHouseOracleFactory, so passing it would fail enum parsing.
# DictGetVsJoin/WindowEquivalence/DynamicSubcolumn/SubqueryMaterialize were registered in the
# factory but had drifted out of this list (never ran under --oracles all); re-added 2026-06-10.
# ExtendedDatetime/JoinUseNulls/QueryCache appended 2026-06-11 (settings-coverage plan section 3/5
# targeted oracles; tmp/ch-settings-to-test-in-sqlancer.md).
# 26.x coverage oracles (TextIndexLike..StatsToggle) appended 2026-06-10 after their convergence
# run: 3h x 41 oracles x 1.09M queries with --eet-26x-modes/--variant-where-emission on produced
# 0 false positives and 1 genuine CH wrong-result (JoinReorder, ANTI/SEMI/INNER chain).
ALL_ORACLES="TLPWhere,TLPDistinct,TLPGroupBy,TLPAggregate,TLPHaving,NoREC,PQS,CERT,CODDTest,SEMR,SEMRMulti,EET,SetOpTLP,CombinatorTLP,QccCache,SortedUnionLimitBy,SchemaRoundtrip,JoinAlgorithm,Cast,Parallelism,PartitionMirror,KeyCondition,TableFunctionIN,ViewEquivalence,AggregateStateRoundtrip,MaterializedViewConsistency,FinalMerge,ProjectionToggle,PatchPartConsistency,DictGetVsJoin,WindowEquivalence,DynamicSubcolumn,SubqueryMaterialize,MutationAnalyzer,TextIndexLike,TopK,JoinReorder,NaturalJoin,JsonSkipIndex,MaterializedCte,StatsToggle,ExtendedDatetime,JoinUseNulls,QueryCache,TextIndexDirectRead,TextIndexContainer,TextIndexLifecycle,PrewhereEquivalence,ReadInOrderToggle,CountOptimization,LazyMaterializationToggle,ReplacingDedup,QuantileConsistency,UniqExactness,ArgExtremum,MaterializedColumn,GroupingDecomposition,LimitRanking,WindowFrame,SemiJoinRewrite,ColumnTransformer,EngineEquivalence,CoalescingFinal,JoinGetSet,RemoteLocalEquivalence,MapTupleContainer,GeoMetamorphic,VariantSubcolumn,AggregateStateExpansion,SequenceFunnel,PartitionLifecycle,AlterModifyConsistency,TtlDeterminism,InsertDedup,TokenBf,VectorIndexRecall"

usage() {
  cat <<EOF
Usage: $(basename "$0") [options]
  --duration SEC      sqlancer total wall-clock cap (default $DURATION; -1 = no cap)
  --threads N         sqlancer worker threads (default $THREADS)
  --heap SIZE         JVM heap, e.g. 8g, 12g (default $HEAP)
  --ch-cpus N         CH container CPU cap (default $CH_CPUS)
  --ch-mem SIZE       CH container memory cap, e.g. 8g, 28g (default $CH_MEM)
  --port PORT         CH HTTP port on host (default $PORT)
  --name NAME         CH container name (default $NAME)
  --oracles LIST      comma-separated oracle list (default $ORACLES); "all" = 25 oracles
  --extra-ch-args S   extra DBMS-specific flags appended after 'clickhouse --oracle ...'
  --no-pull           DEPRECATED no-op: the image is ALWAYS pulled fresh (see below)
  --rebuild           force-rebuild the jar
  --keep-container    don't tear down the CH container at the end
  -h, --help
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --duration)       DURATION="$2"; shift 2 ;;
    --threads)        THREADS="$2"; shift 2 ;;
    --heap)           HEAP="$2"; shift 2 ;;
    --ch-cpus)        CH_CPUS="$2"; shift 2 ;;
    --ch-mem)         CH_MEM="$2"; shift 2 ;;
    --port)           PORT="$2"; shift 2 ;;
    --name)           NAME="$2"; shift 2 ;;
    --oracles)        ORACLES="$2"; shift 2 ;;
    --extra-ch-args)  EXTRA_CH_ARGS="$2"; shift 2 ;;
    --no-pull)        echo "WARNING: --no-pull is deprecated and ignored; HEAD is always pulled fresh" >&2; shift ;;
    --rebuild)        REBUILD=1; shift ;;
    --keep-container) KEEP=1; shift ;;
    -h|--help)        usage; exit 0 ;;
    *) echo "unknown option: $1" >&2; usage; exit 2 ;;
  esac
done

[[ "$ORACLES" == "all" ]] && ORACLES="$ALL_ORACLES"

CFG="$ROOT/.claude/clickhouse-config"
for f in log_level.xml trace_log_disabled.xml system_logs_disabled.xml async_insert_off.xml alter_mutation_sync.xml; do
  [[ -f "$CFG/$f" ]] || { echo "missing $CFG/$f" >&2; exit 1; }
done

MVN="$ROOT/tmp/apache-maven-3.9.9/bin/mvn"
JAR="$ROOT/target/sqlancer-2.0.0.jar"
[[ -x "$MVN" ]] || { echo "vendored maven not found at $MVN" >&2; exit 1; }
command -v docker >/dev/null || { echo "docker not on PATH" >&2; exit 1; }
command -v java   >/dev/null || { echo "java not on PATH" >&2; exit 1; }

cleanup() {
  if [[ $KEEP -eq 0 ]]; then
    echo "==> cleanup: removing container $NAME"
    docker rm -f "$NAME" >/dev/null 2>&1 || true
  else
    echo "==> --keep-container: $NAME left running"
  fi
}
trap cleanup EXIT

# JAVA_TOOL_OPTIONS / ASAN_OPTIONS are poisoned in some user shells (see CLAUDE.md)
unset JAVA_TOOL_OPTIONS ASAN_OPTIONS || true

# --- build jar ----------------------------------------------------------------
if [[ $REBUILD -eq 1 || ! -f "$JAR" ]]; then
  echo "==> building sqlancer jar"
  # jacoco 0.8.12 chokes on Java 25 class files (major version 69) -> skip it
  "$MVN" -B package -DskipTests=true -Djacoco.skip=true -q
fi
ls -lh "$JAR"

# --- pull image + start container --------------------------------------------
# ALWAYS pull HEAD fresh: 'head' is a mutable tag that advances ~daily, and CH
# does NOT retain per-build version tags (e.g. 26.6.1.658 is unpullable once head
# moves on). Running a stale local image silently fuzzes an old build and makes a
# finding impossible to re-confirm later. There is intentionally no opt-out.
echo "==> docker pull clickhouse/clickhouse-server:head (always)"
docker pull -q clickhouse/clickhouse-server:head

# Record the EXACT resolved build so reproducers stay attributable after head moves.
IMAGE_DIGEST=$(docker inspect --format '{{index .RepoDigests 0}}' clickhouse/clickhouse-server:head 2>/dev/null || echo "unknown")

# Always start from a clean slate so config mounts + env vars match exactly
docker rm -f "$NAME" >/dev/null 2>&1 || true

echo "==> starting $NAME (cpus=$CH_CPUS, mem=$CH_MEM, port=$PORT)"
docker run --ulimit nofile=262144:262144 --name "$NAME" -p "$PORT":8123 -d \
  --cpus="$CH_CPUS" -m="$CH_MEM" \
  -e CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT=1 \
  -e CLICKHOUSE_SKIP_USER_SETUP=1 \
  -v "$CFG/log_level.xml:/etc/clickhouse-server/config.d/sf_log_level.xml:ro" \
  -v "$CFG/trace_log_disabled.xml:/etc/clickhouse-server/config.d/sf_trace_log_disabled.xml:ro" \
  -v "$CFG/system_logs_disabled.xml:/etc/clickhouse-server/config.d/sf_system_logs_disabled.xml:ro" \
  -v "$CFG/async_insert_off.xml:/etc/clickhouse-server/config.d/sf_async_insert_off.xml:ro" \
  -v "$CFG/alter_mutation_sync.xml:/etc/clickhouse-server/users.d/sf_alter_mutation_sync.xml:ro" \
  clickhouse/clickhouse-server:head >/dev/null

echo "==> waiting for /ping"
for _ in $(seq 1 60); do
  curl -sf "http://127.0.0.1:$PORT/ping" >/dev/null && break
  sleep 1
done
curl -sf "http://127.0.0.1:$PORT/ping" >/dev/null || {
  echo "CH did not become ready in 60s; last container logs:" >&2
  docker logs "$NAME" 2>&1 | tail -30 >&2
  exit 1
}
CH_VERSION="$(curl -s "http://127.0.0.1:$PORT/?query=SELECT%20version()")"
echo "    CH version: $CH_VERSION"
echo "    CH image:   $IMAGE_DIGEST"

# --- run sqlancer -------------------------------------------------------------
mkdir -p logs/runs logs/clickhouse
TS=$(date -u +%Y%m%d_%H%M%S)
LOG="logs/runs/sqlancer-${TS}.log"

# Stamp the exact build at the TOP of the run log so every reproducer in it stays
# attributable to a specific HEAD build even after the 'head' tag advances.
{
  echo "# sqlancer run ${TS}Z"
  echo "# CH version: $CH_VERSION"
  echo "# CH image:   $IMAGE_DIGEST"
  echo "# oracles:    $ORACLES"
} > "$LOG"

echo "==> launching sqlancer for ${DURATION}s ($THREADS threads, heap $HEAP)"
echo "    oracles: $ORACLES"
echo "    log:     $LOG"

# --password "" is mandatory: MainOptions.password defaults to the literal
# string "sqlancer", which CH HEAD's default user rejects (REQUIRED_PASSWORD).
set +e
java "-Xmx${HEAP}" -jar "$JAR" \
  --num-threads "$THREADS" \
  --num-tries 999999 \
  --timeout-seconds "$DURATION" \
  --use-connection-test false \
  --print-progress-summary true \
  --host 127.0.0.1 --port "$PORT" \
  --username default --password "" \
  clickhouse --oracle "$ORACLES" $EXTRA_CH_ARGS \
  2>&1 | tee -a "$LOG"
RC=${PIPESTATUS[0]}
set -e

# --- summary ------------------------------------------------------------------
LAST_PROG=$(grep -E "Threads shut down" "$LOG" | tail -1 || true)
REPROS=$(find logs/clickhouse -maxdepth 1 -name 'database*.log' ! -name '*-cur.log' 2>/dev/null | wc -l)
SIZE=$(du -h "$LOG" 2>/dev/null | cut -f1)
echo
echo "==> Summary"
echo "    exit code:       $RC"
echo "    CH version:      $CH_VERSION"
echo "    CH image:        $IMAGE_DIGEST"
echo "    log:             $LOG ($SIZE)"
echo "    reproducers:     $REPROS database*.log file(s) in logs/clickhouse/"
echo "    last progress:   $LAST_PROG"

# EXIT trap tears the container down unless --keep-container was given.
exit "$RC"
