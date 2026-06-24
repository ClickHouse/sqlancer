package sqlancer.clickhouse.oracle;

import sqlancer.Reproducer;
import sqlancer.clickhouse.ClickHouseProvider.ClickHouseGlobalState;
import sqlancer.clickhouse.ClickHouseSchema.ClickHouseTable;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;

public final class ClickHouseOptimizingOracle implements TestOracle<ClickHouseGlobalState> {

    private final ClickHouseGlobalState state;
    private final TestOracle<ClickHouseGlobalState> inner;

    private static final ExpectedErrors OPTIMIZE_EXPECTED_ERRORS = ExpectedErrors.from("TIMEOUT_EXCEEDED",
            "TOO_MANY_PARTS", "PART_IS_TEMPORARILY_LOCKED", "UNKNOWN_TABLE", "ABORTED");

    public ClickHouseOptimizingOracle(ClickHouseGlobalState state, TestOracle<ClickHouseGlobalState> inner) {
        this.state = state;
        this.inner = inner;
    }

    @Override
    public void check() throws Exception {
        optimizeDedupeTables();
        inner.check();
    }

    @Override
    public Reproducer<ClickHouseGlobalState> getLastReproducer() {
        return inner.getLastReproducer();
    }

    @Override
    public String getLastQueryString() {
        return inner.getLastQueryString();
    }

    private void optimizeDedupeTables() {
        try {
            for (ClickHouseTable t : state.getSchema().getDatabaseTables()) {
                if (!t.supportsFinal()) {
                    continue;
                }
                SQLQueryAdapter q = new SQLQueryAdapter("OPTIMIZE TABLE " + t.getName() + " FINAL",
                        OPTIMIZE_EXPECTED_ERRORS);
                try {
                    state.executeStatement(q);
                } catch (Throwable ignored) {

                }
            }
        } catch (Throwable ignored) {

        }
    }
}
