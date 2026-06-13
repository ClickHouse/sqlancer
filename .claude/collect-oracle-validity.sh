#!/usr/bin/env bash
# Per-oracle query-validity collection.
# One fresh CH HEAD container; for each oracle: truncate system.query_log, run
# sqlancer briefly, then dump the error-code distribution + sample failing-query
# TEXTS from query_log. query_log captures EVERY failed query (code + text)
# regardless of whether sqlancer tolerated it -- the true "did the generator emit
# valid SQL" signal. Output: val/<Oracle>.txt (one self-contained file per oracle).
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
unset JAVA_TOOL_OPTIONS ASAN_OPTIONS || true

NAME="clickhouse-val"
PORT="18125"
JAR="$ROOT/target/sqlancer-2.0.0.jar"
CFG="$ROOT/.claude/clickhouse-config"
DUR="${DUR:-50}"
THREADS="${THREADS:-6}"
OUTDIR="$ROOT/val"

# Full current oracle set (from run-sqlancer.sh ALL_ORACLES).
IFS=',' read -r -a ORACLES <<< "TLPWhere,TLPDistinct,TLPGroupBy,TLPAggregate,TLPHaving,NoREC,PQS,CERT,CODDTest,SEMR,SEMRMulti,EET,SetOpTLP,CombinatorTLP,QccCache,SortedUnionLimitBy,SchemaRoundtrip,JoinAlgorithm,Cast,Parallelism,PartitionMirror,KeyCondition,TableFunctionIN,ViewEquivalence,AggregateStateRoundtrip,MaterializedViewConsistency,FinalMerge,ProjectionToggle,PatchPartConsistency,DictGetVsJoin,WindowEquivalence,DynamicSubcolumn,SubqueryMaterialize,MutationAnalyzer,TextIndexLike,TopK,JoinReorder,NaturalJoin,JsonSkipIndex,MaterializedCte,StatsToggle,ExtendedDatetime,JoinUseNulls,QueryCache,TextIndexDirectRead,TextIndexContainer,TextIndexLifecycle"

rm -rf "$OUTDIR"; mkdir -p "$OUTDIR"

echo "==> docker pull clickhouse/clickhouse-server:head"
docker pull -q clickhouse/clickhouse-server:head
DIGEST=$(docker inspect --format '{{index .RepoDigests 0}}' clickhouse/clickhouse-server:head 2>/dev/null || echo unknown)
docker rm -f "$NAME" >/dev/null 2>&1 || true

echo "==> starting $NAME on :$PORT"
docker run --ulimit nofile=262144:262144 --name "$NAME" -p "$PORT":8123 -d \
  --cpus=10 -m=10g \
  -e CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT=1 -e CLICKHOUSE_SKIP_USER_SETUP=1 \
  -v "$CFG/log_level.xml:/etc/clickhouse-server/config.d/sf_log_level.xml:ro" \
  -v "$CFG/trace_log_disabled.xml:/etc/clickhouse-server/config.d/sf_trace_log_disabled.xml:ro" \
  -v "$CFG/system_log_ttl.xml:/etc/clickhouse-server/config.d/sf_system_log_ttl.xml:ro" \
  -v "$CFG/async_insert_off.xml:/etc/clickhouse-server/config.d/sf_async_insert_off.xml:ro" \
  -v "$CFG/alter_mutation_sync.xml:/etc/clickhouse-server/users.d/sf_alter_mutation_sync.xml:ro" \
  clickhouse/clickhouse-server:head >/dev/null

until curl -sf "http://127.0.0.1:$PORT/ping" >/dev/null; do sleep 1; done
VERSION=$(docker exec "$NAME" clickhouse-client -q "SELECT version()")
echo "version=$VERSION  digest=$DIGEST  duration=${DUR}s  threads=$THREADS  oracles=${#ORACLES[@]}" | tee "$OUTDIR/_meta.txt"

CH() { docker exec -i "$NAME" clickhouse-client "$@"; }

i=0
for O in "${ORACLES[@]}"; do
  i=$((i+1))
  echo "[$i/${#ORACLES[@]}] $O ..."
  CH -q "TRUNCATE TABLE system.query_log" 2>/dev/null || true
  rm -f logs/clickhouse/database*.log 2>/dev/null || true

  set +e
  timeout $((DUR+40)) java -Xmx8g -jar "$JAR" \
    --num-threads "$THREADS" --num-tries 999999 --timeout-seconds "$DUR" \
    --use-connection-test false --print-progress-summary true \
    --host 127.0.0.1 --port "$PORT" --username default --password "" \
    clickhouse --oracle "$O" > "$OUTDIR/$O.run" 2>&1
  RC=$?
  set -e

  CH -q "SYSTEM FLUSH LOGS" 2>/dev/null || true
  REPROS=$(find logs/clickhouse -maxdepth 1 -name 'database*.log' ! -name '*-cur.log' 2>/dev/null | wc -l)

  {
    echo "=== ORACLE: $O ==="
    echo "version: $VERSION   duration: ${DUR}s   threads: $THREADS   exit: $RC   reproducers: $REPROS"
    echo "progress: $(grep -E 'Threads shut down' "$OUTDIR/$O.run" | tail -1 | tr -s ' ')"
    if grep -qiE 'Exception in thread .main.|ParameterException|Unknown option|Was passed' "$OUTDIR/$O.run"; then
      echo "STARTUP_ERROR: yes (oracle may have failed to launch -- see .run)"
    fi
    echo
    echo "--- query_log totals ---"
    CH -q "SELECT 'queries='||toString(count())||'  failures='||toString(countIf(exception_code!=0))||'  fail_pct='||toString(round(100*countIf(exception_code!=0)/greatest(count(),1),1)) FROM system.query_log WHERE type IN ('QueryFinish','ExceptionBeforeStart','ExceptionWhileProcessing')" 2>/dev/null
    echo
    echo "--- error-code distribution (code  name  count) ---"
    CH -q "SELECT exception_code, errorCodeToName(exception_code), count() AS c FROM system.query_log WHERE exception_code!=0 GROUP BY exception_code ORDER BY c DESC LIMIT 30 FORMAT TSV" 2>/dev/null
    echo
    echo "--- sample failing queries (code | name | up-to-3 distinct query texts, 400 chars) ---"
    CH -q "SELECT exception_code, errorCodeToName(exception_code), replaceRegexpAll(substring(any(query),1,400),'[\n\t]+',' ') FROM (SELECT exception_code, query, row_number() OVER (PARTITION BY exception_code ORDER BY cityHash64(query)) AS rn FROM system.query_log WHERE exception_code!=0 AND type IN ('ExceptionBeforeStart','ExceptionWhileProcessing')) WHERE rn<=3 GROUP BY exception_code, normalizeQuery(query) ORDER BY exception_code LIMIT 60 FORMAT TSV" 2>/dev/null
  } > "$OUTDIR/$O.txt"
  echo "    exit=$RC repros=$REPROS"
done

docker rm -f "$NAME" >/dev/null 2>&1 || true
echo "==> ALL DONE -> $OUTDIR ($(ls "$OUTDIR"/*.txt 2>/dev/null | wc -l) oracle files)"
