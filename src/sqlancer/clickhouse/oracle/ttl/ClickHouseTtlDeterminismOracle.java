package sqlancer.clickhouse.oracle.ttl;

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

public class ClickHouseTtlDeterminismOracle implements TestOracle<ClickHouseGlobalState> {

    private static final AtomicLong CTR = new AtomicLong();

    private static final String EXPIRED_BOUNDARY = "2000-01-01";

    private static final String[] EXPIRED_DATES = { "1990-01-01", "1991-06-15", "1992-12-31", "1993-03-07",
            "1994-09-30", "1995-11-11" };

    private static final String[] SURVIVING_DATES = { "2190-01-01", "2191-06-15", "2192-12-31", "2193-03-07",
            "2194-09-30", "2195-07-04", "2197-02-28", "2200-12-31" };

    private final ClickHouseGlobalState state;

    private final ExpectedErrors ddlErrors = new ExpectedErrors();
    private final ExpectedErrors readErrors = new ExpectedErrors();

    public ClickHouseTtlDeterminismOracle(ClickHouseGlobalState state) {
        this.state = state;
        for (ExpectedErrors e : List.of(ddlErrors, readErrors)) {
            ClickHouseErrors.addExpectedExpressionErrors(e);
            ClickHouseErrors.addSessionSettingsErrors(e);
            ClickHouseErrors.addAlterErrors(e);

            e.add("UNKNOWN_STORAGE");
            e.add("Unknown table engine");
            e.add("SUPPORT_IS_DISABLED");
            e.add("NOT_IMPLEMENTED");
            e.add("ILLEGAL_TYPE_OF_ARGUMENT");
            e.add("UNKNOWN_FUNCTION");
            e.add("Unknown setting");
            e.add("BAD_ARGUMENTS");
            e.add("experimental");
            e.add("allow_experimental");
            e.add("SYNTAX_ERROR");

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
        if (!state.getClickHouseOptions().ttlDeterminismOracle) {
            throw new IgnoreMeException();
        }
        long id = CTR.incrementAndGet();
        String ttlTable = state.getDatabaseName() + ".ttl_" + id + "_t";
        String mirrorTable = state.getDatabaseName() + ".ttl_" + id + "_m";
        Randomly r = state.getRandomly();

        String createTtl = "CREATE TABLE " + ttlTable + " (d Date32, k UInt32, v Int64) ENGINE = MergeTree ORDER BY k "
                + "TTL d + INTERVAL 1 DAY DELETE";
        String createMirror = "CREATE TABLE " + mirrorTable
                + " (d Date32, k UInt32, v Int64) ENGINE = MergeTree ORDER BY k";

        try {
            logStmt(createTtl);
            if (!new SQLQueryAdapter(createTtl, ddlErrors, true).execute(state)) {
                throw new IgnoreMeException();
            }
            logStmt(createMirror);
            if (!new SQLQueryAdapter(createMirror, ddlErrors, true).execute(state)) {
                throw new IgnoreMeException();
            }

            long survivorCount = 0;
            int nextKey = 0;

            int blocks = 2 + r.getInteger(0, 3);
            for (int b = 0; b < blocks; b++) {
                int rows = 8 + r.getInteger(0, 25);
                StringBuilder values = new StringBuilder();
                for (int i = 0; i < rows; i++) {
                    boolean survivor = Randomly.getBoolean();
                    String date = survivor ? SURVIVING_DATES[r.getInteger(0, SURVIVING_DATES.length)]
                            : EXPIRED_DATES[r.getInteger(0, EXPIRED_DATES.length)];
                    int key = nextKey++;
                    long val = r.getInteger();
                    if (i > 0) {
                        values.append(", ");
                    }
                    values.append("(toDate32('").append(date).append("'), ").append(key).append(", ").append(val)
                            .append(')');
                    if (survivor) {
                        survivorCount++;
                    }
                }
                String insertTtl = "INSERT INTO " + ttlTable + " (d, k, v) VALUES " + values;
                String insertMirror = "INSERT INTO " + mirrorTable + " (d, k, v) VALUES " + values;
                logStmt(insertTtl);
                if (!new SQLQueryAdapter(insertTtl, readErrors, true).execute(state)) {
                    throw new IgnoreMeException();
                }
                logStmt(insertMirror);
                if (!new SQLQueryAdapter(insertMirror, readErrors, true).execute(state)) {
                    throw new IgnoreMeException();
                }
            }

            String optimize = "OPTIMIZE TABLE " + ttlTable + " FINAL SETTINGS materialize_ttl_after_modify = 1";
            logStmt(optimize);
            if (!new SQLQueryAdapter(optimize, ddlErrors, true).execute(state)) {
                throw new IgnoreMeException();
            }

            String materialize = "ALTER TABLE " + ttlTable + " MATERIALIZE TTL SETTINGS mutations_sync = 2";
            logStmt(materialize);
            new SQLQueryAdapter(materialize, ddlErrors, true).execute(state);

            String expiredCount = readSingleValue(
                    "SELECT toString(count()) FROM " + ttlTable + " WHERE d < toDate32('" + EXPIRED_BOUNDARY + "')");
            if (!"0".equals(expiredCount)) {
                throw new AssertionError(String.format(
                        "TTL determinism: %s expired rows (d < %s) survived after OPTIMIZE FINAL + MATERIALIZE TTL. "
                                + "All expired-bucket dates are in {1990..1995}, decades past now(), so "
                                + "TTL d + INTERVAL 1 DAY DELETE must remove them. DDL: %s",
                        expiredCount, EXPIRED_BOUNDARY, createTtl));
            }

            String totalCount = readSingleValue("SELECT toString(count()) FROM " + ttlTable);
            if (!String.valueOf(survivorCount).equals(totalCount)) {
                throw new AssertionError(String.format(
                        "TTL determinism: survivor count mismatch: %d future-bucket rows (d in {2190..2200}) were "
                                + "inserted but the table holds %s rows after expiry. DDL: %s",
                        survivorCount, totalCount, createTtl));
            }

            List<String> actualSurvivors = ComparatorHelper.getResultSetFirstColumnAsString(
                    "SELECT toString(tuple(d, k, v)) FROM " + ttlTable + " ORDER BY k", readErrors, state);
            List<String> expectedSurvivors = ComparatorHelper.getResultSetFirstColumnAsString(
                    "SELECT toString(tuple(d, k, v)) FROM " + mirrorTable + " WHERE d >= toDate32('" + EXPIRED_BOUNDARY
                            + "') ORDER BY k",
                    readErrors, state);
            if (!expectedSurvivors.equals(actualSurvivors)) {
                throw new AssertionError(String.format(
                        "TTL determinism: survivor set mismatch: TTL-table survivors %s vs no-TTL mirror rows with "
                                + "d >= %s %s. Both sides are now()-independent (future bucket). DDL: %s",
                        truncate(actualSurvivors), EXPIRED_BOUNDARY, truncate(expectedSurvivors), createTtl));
            }
        } finally {
            dropQuietly(ttlTable);
            dropQuietly(mirrorTable);
        }
    }

    private static String truncate(List<String> rows) {
        int limit = 50;
        if (rows.size() <= limit) {
            return rows.toString();
        }
        return rows.subList(0, limit) + "... (" + rows.size() + " total)";
    }

    private String readSingleValue(String query) throws SQLException {
        List<String> rows = ComparatorHelper.getResultSetFirstColumnAsString(query, readErrors, state);
        if (rows.size() != 1) {
            throw new IgnoreMeException();
        }
        return rows.get(0);
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
