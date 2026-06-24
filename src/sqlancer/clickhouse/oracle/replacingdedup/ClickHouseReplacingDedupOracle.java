package sqlancer.clickhouse.oracle.replacingdedup;

import java.sql.SQLException;
import java.util.ArrayList;
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

public class ClickHouseReplacingDedupOracle implements TestOracle<ClickHouseGlobalState> {

    private static final AtomicLong TABLE_COUNTER = new AtomicLong();
    private static final AtomicLong VERSION_COUNTER = new AtomicLong();

    private final ClickHouseGlobalState state;

    private final ExpectedErrors createErrors = new ExpectedErrors();
    private final ExpectedErrors readErrors = new ExpectedErrors();

    public ClickHouseReplacingDedupOracle(ClickHouseGlobalState state) {
        this.state = state;
        for (ExpectedErrors e : List.of(createErrors, readErrors)) {
            ClickHouseErrors.addExpectedExpressionErrors(e);
            ClickHouseErrors.addSessionSettingsErrors(e);

            e.add("UNKNOWN_TABLE");
            e.add("Unknown table expression identifier");

            e.add("(MEMORY_LIMIT_EXCEEDED)");
            e.add("memory limit exceeded");

            e.add("TIMEOUT_EXCEEDED");
            e.add("Timeout exceeded");
        }
    }

    @Override
    public void check() throws SQLException {
        if (!state.getClickHouseOptions().replacingDedupOracle) {
            throw new IgnoreMeException();
        }

        long id = TABLE_COUNTER.incrementAndGet();
        String table = state.getDatabaseName() + ".repl_" + id + "_t";
        Randomly r = state.getRandomly();

        String create = "CREATE TABLE " + table
                + " (k Int32, val Int64, ver UInt64) ENGINE = ReplacingMergeTree(ver) ORDER BY k";

        try {
            logStmt(create);
            if (!new SQLQueryAdapter(create, createErrors, true).execute(state)) {
                throw new IgnoreMeException();
            }

            int distinctKeys = 5 + r.getInteger(0, 26);
            int blocks = 3 + r.getInteger(0, 4);
            for (int b = 0; b < blocks; b++) {
                StringBuilder sb = new StringBuilder("INSERT INTO ").append(table).append(" (k, val, ver) VALUES ");
                boolean first = true;
                for (int key = 0; key < distinctKeys; key++) {
                    int rowsForKey = 1 + r.getInteger(0, 3);
                    for (int i = 0; i < rowsForKey; i++) {
                        long ver = VERSION_COUNTER.incrementAndGet();
                        long val = r.getInteger(-1000000, 1000000);
                        if (!first) {
                            sb.append(", ");
                        }
                        first = false;
                        sb.append('(').append(key).append(", ").append(val).append(", ").append(ver).append(')');
                    }
                }
                logStmt(sb.toString());
                if (!new SQLQueryAdapter(sb.toString(), readErrors, false).execute(state)) {
                    throw new IgnoreMeException();
                }
            }

            String optimize = "OPTIMIZE TABLE " + table + " FINAL";
            logStmt(optimize);
            if (!new SQLQueryAdapter(optimize, readErrors, false).execute(state)) {
                throw new IgnoreMeException();
            }

            String finalSql = "SELECT toString(tuple(k, val)) FROM " + table + " FINAL ORDER BY k";
            String argMaxSql = "SELECT toString(tuple(k, argMax(val, ver))) FROM " + table + " GROUP BY k ORDER BY k";

            List<String> finalRows = ComparatorHelper.getResultSetFirstColumnAsString(finalSql, readErrors, state);
            List<String> argMaxRows = ComparatorHelper.getResultSetFirstColumnAsString(argMaxSql, readErrors, state);

            if (!finalRows.equals(argMaxRows)) {
                throw new AssertionError(String.format(
                        "ReplacingMergeTree FINAL dedup mismatch vs argMax(val, ver) ground truth:%n"
                                + "  FINAL:  %s%n  argMax: %s%n  FINAL rows (%d): %s%n  argMax rows (%d): %s%n  DDL: %s",
                        finalSql, argMaxSql, finalRows.size(), truncate(finalRows), argMaxRows.size(),
                        truncate(argMaxRows), create));
            }
        } finally {
            dropQuietly(table);
        }
    }

    private static String truncate(List<String> rows) {
        int limit = 50;
        if (rows.size() <= limit) {
            return rows.toString();
        }
        return new ArrayList<>(rows.subList(0, limit)) + "... (" + rows.size() + " total)";
    }

    private void dropQuietly(String table) {
        try {
            new SQLQueryAdapter("DROP TABLE IF EXISTS " + table, readErrors, true).execute(state);
        } catch (Exception | AssertionError ignored) {

        }
    }

    private void logStmt(String stmt) {
        if (state.getOptions().logEachSelect()) {
            state.getLogger().writeCurrent(stmt);
            state.getState().logStatement(stmt);
        }
    }
}
