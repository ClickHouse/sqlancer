package sqlancer.clickhouse.oracle.coddtest;

import sqlancer.clickhouse.ClickHouseType;

/**
 * Thin public bridge over {@link ClickHouseCODDTestOracle}'s package-private filter predicates so unit tests can
 * exercise the capability dispatch without standing up a live ClickHouse instance.
 */
public final class ClickHouseCODDTestFilters {

    private ClickHouseCODDTestFilters() {
    }

    // true iff a column of this type can be constant-folded by the CODDTest oracle.
    public static boolean isFoldable(ClickHouseType term) {
        return ClickHouseCODDTestOracle.isFoldableColumnTerm(term);
    }
}
