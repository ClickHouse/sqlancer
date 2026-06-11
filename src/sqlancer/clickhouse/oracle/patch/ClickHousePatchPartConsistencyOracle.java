package sqlancer.clickhouse.oracle.patch;

import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import sqlancer.ComparatorHelper;
import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.clickhouse.ClickHouseErrors;
import sqlancer.clickhouse.ClickHouseProvider.ClickHouseGlobalState;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;

public class ClickHousePatchPartConsistencyOracle implements TestOracle<ClickHouseGlobalState> {

    private static final AtomicLong PATCH_COUNTER = new AtomicLong();

    private final ClickHouseGlobalState state;
    private final ExpectedErrors errors = new ExpectedErrors();

    public ClickHousePatchPartConsistencyOracle(ClickHouseGlobalState state) {
        this.state = state;

        ClickHouseErrors.addSessionSettingsErrors(errors);
        ClickHouseErrors.addMutationErrors(errors);

        errors.add("UNKNOWN_TABLE");
        errors.add("Unknown table expression identifier");

        errors.add("(MEMORY_LIMIT_EXCEEDED)");
        errors.add("memory limit exceeded");
    }

    @Override
    public void check() throws SQLException {
        long id = PATCH_COUNTER.incrementAndGet();
        String db = state.getDatabaseName();
        String t = db + ".patchpart_" + id;

        int granularity = Randomly.fromOptions(8, 16, 128, 8192);
        String createTable = "CREATE TABLE " + t + " (k Int32, v Int64, w String) ENGINE = MergeTree ORDER BY k "
                + "SETTINGS enable_block_number_column = 1, enable_block_offset_column = 1, index_granularity = "
                + granularity;
        String dropTable = "DROP TABLE IF EXISTS " + t;

        int parts = 2 + (int) Randomly.getNotCachedInteger(0, 3);
        int rowsPerPart = 200 + (int) Randomly.getNotCachedInteger(0, 800);

        if (state.getOptions().logEachSelect()) {
            for (String stmt : List.of(dropTable, createTable)) {
                state.getLogger().writeCurrent(stmt);
                state.getState().logStatement(stmt);
            }
        }

        try {
            if (!new SQLQueryAdapter(createTable, errors, true).execute(state)) {
                throw new IgnoreMeException();
            }
            for (int p = 0; p < parts; p++) {
                long offset = (long) p * rowsPerPart;
                String insert = "INSERT INTO " + t + " SELECT toInt32(number) AS k, toInt64(number) AS v, "
                        + "toString(number) AS w FROM numbers(" + offset + ", " + rowsPerPart + ")";
                logStmt(insert);
                if (!new SQLQueryAdapter(insert, errors, true).execute(state)) {
                    throw new IgnoreMeException();
                }
            }

            int divA = 3 + (int) Randomly.getNotCachedInteger(0, 5);
            int divB = 4 + (int) Randomly.getNotCachedInteger(0, 5);
            List<String> updates = List.of(
                    "UPDATE " + t + " SET w = concat('p', toString(k)) WHERE k % " + divA
                            + " = 0 SETTINGS enable_lightweight_update = 1",
                    "UPDATE " + t + " SET v = v + 1000 WHERE k % " + divB
                            + " = 0 SETTINGS enable_lightweight_update = 1");
            for (String upd : updates) {
                logStmt(upd);

                new SQLQueryAdapter(upd, errors, false).execute(state);
            }

            String patchCount = "SELECT toString(countIf(startsWith(name, 'patch'))) FROM system.parts WHERE database = '"
                    + db + "' AND table = 'patchpart_" + id + "' AND active";
            List<String> pc = ComparatorHelper.getResultSetFirstColumnAsString(patchCount, errors, state);
            if (pc.size() != 1 || "0".equals(pc.get(0))) {
                throw new IgnoreMeException();
            }

            String rowExpr = "concat(toString(k), '#', toString(v), '#', w)";
            String dir = Randomly.getBoolean() ? " DESC" : " ASC";
            int limit = 10 + (int) Randomly.getNotCachedInteger(0, 200);

            String readBase = "SELECT " + rowExpr + " FROM " + t + " ORDER BY k" + dir + " LIMIT " + limit;

            String lazyOn = readBase + " SETTINGS query_plan_optimize_lazy_materialization = 1, apply_patch_parts = 1";
            String lazyOff = readBase + " SETTINGS query_plan_optimize_lazy_materialization = 0, apply_patch_parts = 1";
            logStmt(lazyOn);
            logStmt(lazyOff);
            List<String> onRows = ComparatorHelper.getResultSetFirstColumnAsString(lazyOn, errors, state);
            List<String> offRows = ComparatorHelper.getResultSetFirstColumnAsString(lazyOff, errors, state);
            ComparatorHelper.assumeResultSetsAreEqual(onRows, offRows, lazyOn, List.of(lazyOff), state);

            String applyRead = readBase + " SETTINGS apply_patch_parts = 1";
            logStmt(applyRead);
            List<String> preRows = ComparatorHelper.getResultSetFirstColumnAsString(applyRead, errors, state);
            String optimize = "OPTIMIZE TABLE " + t + " FINAL";
            logStmt(optimize);
            if (!new SQLQueryAdapter(optimize, errors, false).execute(state)) {
                throw new IgnoreMeException();
            }
            List<String> postRows = ComparatorHelper.getResultSetFirstColumnAsString(applyRead, errors, state);
            ComparatorHelper.assumeResultSetsAreEqual(preRows, postRows, applyRead, List.of(optimize), state);
        } finally {
            try {
                new SQLQueryAdapter(dropTable, errors, true).execute(state);
            } catch (SQLException ignored) {

            }
        }
    }

    private void logStmt(String stmt) {
        if (state.getOptions().logEachSelect()) {
            state.getLogger().writeCurrent(stmt);
            state.getState().logStatement(stmt);
        }
    }
}
