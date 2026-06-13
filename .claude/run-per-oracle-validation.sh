#!/usr/bin/env bash
# Per-oracle sequential validation, one sqlancer run per oracle.
# Each run is 5 minutes; results stored in logs/per-oracle/<OracleName>/.
# Run with nohup ./run-per-oracle-validation.sh > validation.out 2>&1 &
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

ORACLES=(
  TLPWhere TLPDistinct TLPGroupBy TLPAggregate TLPHaving
  NoREC PQS CERT CODDTest
  SEMR SEMRMulti EET
  SetOpTLP CombinatorTLP QccCache SortedUnionLimitBy
  RowPolicy SchemaRoundtrip
  JoinAlgorithm Cast Parallelism PartitionMirror
  KeyCondition TableFunctionIN ViewEquivalence
)

DURATION="${DURATION:-300}"
OUT_BASE="${OUT_BASE:-logs/per-oracle-$(date -u +%Y%m%d_%H%M%S)}"
mkdir -p "$OUT_BASE"

# Warm the image up front; run-sqlancer.sh ALWAYS re-pulls HEAD per sub-run anyway
# (the 'head' tag is mutable and per-build tags are unpullable once it advances).
echo "==> initial pull of clickhouse/clickhouse-server:head"
docker pull -q clickhouse/clickhouse-server:head

SUMMARY="$OUT_BASE/summary.tsv"
{ printf "oracle\tduration_s\treproducers\tlast_progress\texit_code\n"; } > "$SUMMARY"

for ORACLE in "${ORACLES[@]}"; do
  echo
  echo "###########################################################"
  echo "### Oracle: $ORACLE  (duration ${DURATION}s)"
  echo "###########################################################"
  ORACLE_DIR="$OUT_BASE/$ORACLE"
  mkdir -p "$ORACLE_DIR"

  # Clear any leftover reproducers/logs from the previous oracle so attribution stays clean.
  rm -f logs/clickhouse/database*.log 2>/dev/null || true
  rm -f logs/runs/*.log 2>/dev/null || true

  set +e
  ./.claude/run-sqlancer.sh \
    --oracles "$ORACLE" \
    --duration "$DURATION" \
    --threads 8 --heap 16g --ch-cpus 8 --ch-mem 6g \
    > "$ORACLE_DIR/runner.out" 2>&1
  RC=$?
  set -e

  # Archive runtime artifacts.
  if compgen -G "logs/runs/sqlancer-*.log" > /dev/null; then
    mv logs/runs/sqlancer-*.log "$ORACLE_DIR/"
  fi
  REPRO_COUNT=0
  for f in logs/clickhouse/database*.log; do
    [[ -e "$f" ]] || continue
    case "$f" in *-cur.log) ;; *)
      mv "$f" "$ORACLE_DIR/"
      REPRO_COUNT=$((REPRO_COUNT+1)) ;;
    esac
  done

  RUN_LOG=$(ls "$ORACLE_DIR"/sqlancer-*.log 2>/dev/null | head -1 || true)
  LAST_PROG=""
  if [[ -n "$RUN_LOG" ]]; then
    LAST_PROG=$(grep -E "Threads shut down" "$RUN_LOG" | tail -1 | tr '\t' ' ' | head -c 120 || true)
  fi
  printf "%s\t%s\t%s\t%s\t%s\n" "$ORACLE" "$DURATION" "$REPRO_COUNT" "${LAST_PROG:-}" "$RC" >> "$SUMMARY"
  echo "    reproducers: $REPRO_COUNT   exit: $RC   $LAST_PROG"
done

echo
echo "==> All oracles complete. Summary at $SUMMARY"
column -t -s $'\t' < "$SUMMARY" || cat "$SUMMARY"
