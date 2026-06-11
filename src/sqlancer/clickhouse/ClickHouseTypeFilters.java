package sqlancer.clickhouse;

import com.clickhouse.data.ClickHouseDataType;

import sqlancer.clickhouse.ClickHouseSchema.ClickHouseColumn;

/**
 * Shared column-type predicates for oracle authoring. The exact-integer-family filter encodes the project's float
 * noise rule (CLAUDE.md, TLPGroupBy section): differential / multiset oracles must restrict value comparisons to
 * exact-integer columns, because Float32/Float64 arithmetic is order-sensitive (parallel partial aggregates vs full
 * rescans round differently -- the ClickHouse#99109 class) and Decimal rendering varies with scale handling. One
 * shared predicate instead of per-oracle copies, so a future type addition (e.g. BFloat16) is classified once.
 */
public final class ClickHouseTypeFilters {

    private ClickHouseTypeFilters() {
    }

    /**
     * True iff the column is numeric (after unwrapping Nullable/LowCardinality) and not a Float32/Float64/Decimal --
     * i.e. safe for exact value comparison in a multiset oracle.
     */
    public static boolean isExactIntegerFamily(ClickHouseColumn column) {
        if (!column.getType().getTypeTerm().unwrap().isNumeric()) {
            return false;
        }
        ClickHouseDataType t = column.getType().getType();
        return t != ClickHouseDataType.Float32 && t != ClickHouseDataType.Float64 && t != ClickHouseDataType.Decimal;
    }
}
