#!/usr/bin/env bash
# 6-hour run of all 31 oracles concurrently. Single sqlancer process round-robins
# across all oracles. Same shape as the historical baseline runs.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# All registered oracles in the factory.
ALL_ORACLES="TLPWhere,TLPDistinct,TLPGroupBy,TLPAggregate,TLPHaving,NoREC,PQS,CERT,CODDTest,SEMR,SEMRMulti,EET,SetOpTLP,CombinatorTLP,QccCache,SortedUnionLimitBy,RowPolicy,SchemaRoundtrip,JoinAlgorithm,Cast,Parallelism,PartitionMirror,KeyCondition,TableFunctionIN,ViewEquivalence,FinalMerge,AggregateStateRoundtrip,DictGetVsJoin,WindowEquivalence,DynamicSubcolumn,SubqueryMaterialize"

DURATION=21600  # 6 hours
OUT_BASE="logs/run-6h-$(date -u +%Y%m%d_%H%M%S)"
mkdir -p "$OUT_BASE"

rm -f logs/clickhouse/database*.log 2>/dev/null || true
rm -f logs/runs/*.log 2>/dev/null || true

echo "Starting 6h run at $(date -u). Oracles: $(echo $ALL_ORACLES | tr ',' '\n' | wc -l) oracles, duration ${DURATION}s"
./.claude/run-sqlancer.sh \
  --oracles "$ALL_ORACLES" --duration "$DURATION" \
  --threads 8 --heap 16g --ch-cpus 8 --ch-mem 12g \
  --no-pull \
  2>&1 | tee "$OUT_BASE/runner.out"
RC=$?

# Archive
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

echo "==> 6h run complete at $(date -u)"
echo "    exit code: $RC"
echo "    reproducers: $REPRO_COUNT"
echo "    archive: $OUT_BASE"

# Categorize reproducers by Caused by code
{
    echo "=== Reproducer categorization ==="
    for f in $OUT_BASE/clickhouse/database*.log; do
        grep -m1 "Caused by.*Code:" "$f" 2>/dev/null | grep -oE "Code: [0-9]+.*\([A-Z_]+\)" || \
        head -1 "$f" | grep -oE "AssertionError" || echo "(unknown)"
    done | sort | uniq -c | sort -rn | head -20
} >> "$OUT_BASE/categorization.txt" 2>/dev/null
