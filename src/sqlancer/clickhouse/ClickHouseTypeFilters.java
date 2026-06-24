package sqlancer.clickhouse;

import com.clickhouse.data.ClickHouseDataType;

import sqlancer.clickhouse.ClickHouseSchema.ClickHouseColumn;

public final class ClickHouseTypeFilters {

    private ClickHouseTypeFilters() {
    }

    public static boolean isExactIntegerFamily(ClickHouseColumn column) {
        if (!column.getType().getTypeTerm().unwrap().isNumeric()) {
            return false;
        }
        ClickHouseDataType t = column.getType().getType();
        return t != ClickHouseDataType.Float32 && t != ClickHouseDataType.Float64 && t != ClickHouseDataType.Decimal;
    }
}
