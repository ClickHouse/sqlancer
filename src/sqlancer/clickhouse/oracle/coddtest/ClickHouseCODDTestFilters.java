package sqlancer.clickhouse.oracle.coddtest;

import sqlancer.clickhouse.ClickHouseType;

public final class ClickHouseCODDTestFilters {

    private ClickHouseCODDTestFilters() {
    }

    public static boolean isFoldable(ClickHouseType term) {
        return ClickHouseCODDTestOracle.isFoldableColumnTerm(term);
    }
}
