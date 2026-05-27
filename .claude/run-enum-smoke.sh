#!/usr/bin/env bash
# Smoke-test the Enum8/Enum16 column emission added in workstream 2 of the
# coverage expansion plan. Runs TLPWhere + JoinAlgorithm + Cast + NoREC (the
# 4 oracles most likely to exercise the new column shape under expression-
# generator pressure) for 5 minutes each. Compare reproducer counts against
# the 0-reproducer baseline from the prior 25-oracle run.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

ORACLES=(TLPWhere JoinAlgorithm Cast NoREC)
DURATION="${DURATION:-300}"
OUT_BASE="${OUT_BASE:-logs/enum-smoke-$(date -u +%Y%m%d_%H%M%S)}"
mkdir -p "$OUT_BASE"

SUMMARY="$OUT_BASE/summary.tsv"
{ printf "oracle\tduration_s\treproducers\tlast_progress\texit_code\n"; } > "$SUMMARY"

for ORACLE in "${ORACLES[@]}"; do
  echo "### Enum smoke: $ORACLE (${DURATION}s)"
  ORACLE_DIR="$OUT_BASE/$ORACLE"
  mkdir -p "$ORACLE_DIR"
  rm -f logs/clickhouse/database*.log 2>/dev/null || true
  rm -f logs/runs/*.log 2>/dev/null || true

  set +e
  ./.claude/run-sqlancer.sh \
    --oracles "$ORACLE" --duration "$DURATION" \
    --threads 8 --heap 16g --ch-cpus 8 --ch-mem 6g \
    --no-pull \
    > "$ORACLE_DIR/runner.out" 2>&1
  RC=$?
  set -e

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
  echo "    reproducers: $REPRO_COUNT   exit: $RC"
done

echo
column -t -s $'\t' < "$SUMMARY" || cat "$SUMMARY"
