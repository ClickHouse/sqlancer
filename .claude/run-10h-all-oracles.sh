#!/usr/bin/env bash
# 10-hour run of all 30 oracles. Single sqlancer process round-robins
# across all oracles. Used after the triage fixes from the 6h and 1h smokes
# to validate the noise reduction at scale.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

ALL_ORACLES="TLPWhere,TLPDistinct,TLPGroupBy,TLPAggregate,TLPHaving,NoREC,PQS,CERT,CODDTest,SEMR,SEMRMulti,EET,SetOpTLP,CombinatorTLP,QccCache,SortedUnionLimitBy,RowPolicy,SchemaRoundtrip,JoinAlgorithm,Cast,Parallelism,PartitionMirror,KeyCondition,TableFunctionIN,ViewEquivalence,FinalMerge,AggregateStateRoundtrip,DictGetVsJoin,WindowEquivalence,DynamicSubcolumn"

DURATION=36000  # 10 hours
OUT_BASE="logs/run-10h-$(date -u +%Y%m%d_%H%M%S)"
mkdir -p "$OUT_BASE"

rm -f logs/clickhouse/database*.log 2>/dev/null || true
rm -f logs/runs/*.log 2>/dev/null || true

echo "Starting 10h run at $(date -u). Oracles: 30. Duration: ${DURATION}s."
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

echo "==> 10h run complete at $(date -u)"
echo "    exit code: $RC"
echo "    reproducers: $REPRO_COUNT"
echo "    archive: $OUT_BASE"

# Categorize reproducers
{
    echo "=== Reproducer categorization ==="
    echo "Total: $REPRO_COUNT"
    echo
    echo "-- by CH error code --"
    for f in $OUT_BASE/clickhouse/database*.log; do
        grep -oE "Code: [0-9]+" "$f" 2>/dev/null | head -1 || echo "NO_CODE"
    done | sort | uniq -c | sort -rn | head -20
    echo
    echo "-- by oracle --"
    for f in $OUT_BASE/clickhouse/database*.log; do
        grep -oE "ClickHouse[A-Z][a-zA-Z]*Oracle" "$f" 2>/dev/null | head -1 || echo "(other)"
    done | sort | uniq -c | sort -rn | head -20
} > "$OUT_BASE/categorization.txt" 2>/dev/null
cat "$OUT_BASE/categorization.txt"
