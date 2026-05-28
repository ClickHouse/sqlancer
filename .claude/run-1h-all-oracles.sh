#!/usr/bin/env bash
# 1-hour sanity-check run of all 30 oracles. Same shape as the 6h script
# but compressed; used after triage fixes to verify reduction in noise
# before committing to another full 6h run.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

ALL_ORACLES="TLPWhere,TLPDistinct,TLPGroupBy,TLPAggregate,TLPHaving,NoREC,PQS,CERT,CODDTest,SEMR,SEMRMulti,EET,SetOpTLP,CombinatorTLP,QccCache,SortedUnionLimitBy,RowPolicy,SchemaRoundtrip,JoinAlgorithm,Cast,Parallelism,PartitionMirror,KeyCondition,TableFunctionIN,ViewEquivalence,FinalMerge,AggregateStateRoundtrip,DictGetVsJoin,WindowEquivalence,DynamicSubcolumn"

DURATION=3600  # 1 hour
OUT_BASE="logs/run-1h-$(date -u +%Y%m%d_%H%M%S)"
mkdir -p "$OUT_BASE"

rm -f logs/clickhouse/database*.log 2>/dev/null || true
rm -f logs/runs/*.log 2>/dev/null || true

echo "Starting 1h run at $(date -u)"
./.claude/run-sqlancer.sh \
  --oracles "$ALL_ORACLES" --duration "$DURATION" \
  --threads 8 --heap 16g --ch-cpus 8 --ch-mem 12g \
  --no-pull \
  2>&1 | tee "$OUT_BASE/runner.out"
RC=$?

if compgen -G "logs/runs/sqlancer-*.log" > /dev/null; then
    cp logs/runs/sqlancer-*.log "$OUT_BASE/"
fi
mkdir -p "$OUT_BASE/clickhouse"
REPRO_COUNT=0
for f in logs/clickhouse/database*.log; do
    [[ -e "$f" ]] || continue
    case "$f" in *-cur.log) ;; *)
      cp "$f" "$OUT_BASE/clickhouse/"
      REPRO_COUNT=$((REPRO_COUNT+1)) ;;
    esac
done

echo "==> 1h run complete at $(date -u)"
echo "    exit code: $RC"
echo "    reproducers: $REPRO_COUNT"
echo "    archive: $OUT_BASE"

{
    echo "=== Reproducer categorization ==="
    for f in $OUT_BASE/clickhouse/database*.log; do
        grep -oE "Code: [0-9]+" "$f" 2>/dev/null | head -1 || \
        head -1 "$f" | grep -oE "AssertionError" || echo "(unknown)"
    done | sort | uniq -c | sort -rn | head -20
} > "$OUT_BASE/categorization.txt" 2>/dev/null
cat "$OUT_BASE/categorization.txt"
