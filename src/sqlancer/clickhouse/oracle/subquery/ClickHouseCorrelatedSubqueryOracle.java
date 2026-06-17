package sqlancer.clickhouse.oracle.subquery;

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

public class ClickHouseCorrelatedSubqueryOracle implements TestOracle<ClickHouseGlobalState> {

    private static final AtomicLong CTR = new AtomicLong();
    private static final int KEY_SPACE = 16;
    private static final String EXPERIMENTAL_SETTING = " SETTINGS allow_experimental_correlated_subqueries = 1";

    enum Mode {
        EXISTS_IN,
        NOT_EXISTS_NOT_IN,
        EXISTS_EXTRA_PREDICATE
    }

    private final ClickHouseGlobalState state;

    private final ExpectedErrors ddlErrors = new ExpectedErrors();
    private final ExpectedErrors readErrors = new ExpectedErrors();

    public ClickHouseCorrelatedSubqueryOracle(ClickHouseGlobalState state) {
        this.state = state;
        for (ExpectedErrors e : List.of(ddlErrors, readErrors)) {
            ClickHouseErrors.addSessionSettingsErrors(e);
            ClickHouseErrors.addExpectedExpressionErrors(e);

            e.add("UNKNOWN_TABLE");
            e.add("(MEMORY_LIMIT_EXCEEDED)");
            e.add("memory limit exceeded");
            e.add("TIMEOUT_EXCEEDED");
            e.add("Timeout exceeded");
            e.add("Limit for result exceeded");
            e.add("TOO_MANY_ROWS_OR_BYTES");
            e.add("NOT_IMPLEMENTED");
            e.add("experimental");
            e.add("allow_experimental_correlated_subqueries");
            e.add("UNKNOWN_SETTING");
            e.add("UNSUPPORTED_METHOD");
            e.add("correlated");
            e.add("CORRELATED");
            e.add("NOT_AN_AGGREGATE");
        }
    }

    @Override
    public void check() throws SQLException {
        if (!state.getClickHouseOptions().correlatedSubqueryOracle) {
            throw new IgnoreMeException();
        }
        long id = CTR.incrementAndGet();
        String t = state.getDatabaseName() + ".corr_t_" + id;
        String s = state.getDatabaseName() + ".corr_s_" + id;
        Randomly r = state.getRandomly();
        try {
            if (!execute("CREATE TABLE " + t + " (k Int32, v Int64) ENGINE = MergeTree ORDER BY tuple()", ddlErrors)) {
                throw new IgnoreMeException();
            }
            if (!execute("CREATE TABLE " + s + " (k Int32, w Int64) ENGINE = MergeTree ORDER BY tuple()", ddlErrors)) {
                throw new IgnoreMeException();
            }
            String tValues = renderRows(r, 15, 30);
            String sValues = renderRows(r, 10, 25);
            if (!execute("INSERT INTO " + t + " (k, v) VALUES " + tValues, readErrors)) {
                throw new IgnoreMeException();
            }
            if (!execute("INSERT INTO " + s + " (k, w) VALUES " + sValues, readErrors)) {
                throw new IgnoreMeException();
            }

            Mode mode = Randomly.fromOptions(Mode.values());
            String correlated;
            String rewrite;
            if (mode == Mode.EXISTS_IN) {
                correlated = preservedKeyMultiset("SELECT k FROM " + t + " AS t WHERE EXISTS (SELECT 1 FROM " + s
                        + " AS s WHERE s.k = t.k)" + EXPERIMENTAL_SETTING);
                rewrite = preservedKeyMultiset("SELECT k FROM " + t + " WHERE k IN (SELECT k FROM " + s + ")");
            } else if (mode == Mode.NOT_EXISTS_NOT_IN) {
                correlated = preservedKeyMultiset("SELECT k FROM " + t + " AS t WHERE NOT EXISTS (SELECT 1 FROM " + s
                        + " AS s WHERE s.k = t.k)" + EXPERIMENTAL_SETTING);
                rewrite = preservedKeyMultiset("SELECT k FROM " + t + " WHERE k NOT IN (SELECT k FROM " + s + ")");
            } else {
                correlated = preservedKeyMultiset("SELECT k FROM " + t + " AS t WHERE EXISTS (SELECT 1 FROM " + s
                        + " AS s WHERE s.k = t.k AND s.w > 0)" + EXPERIMENTAL_SETTING);
                rewrite = preservedKeyMultiset(
                        "SELECT k FROM " + t + " WHERE k IN (SELECT k FROM " + s + " WHERE w > 0)");
            }

            if (!correlated.equals(rewrite)) {
                throw new AssertionError(String.format(
                        "Correlated subquery rewrite mismatch (%s): correlated form gave %s but IN-rewrite gave %s. "
                                + "T values: %s ; S values: %s",
                        mode, correlated, rewrite, tValues, sValues));
            }
        } finally {
            dropQuietly(s);
            dropQuietly(t);
        }
    }

    private String preservedKeyMultiset(String innerQuery) throws SQLException {
        return readSingleValue("SELECT toString(arraySort(groupArray(k))) FROM (" + innerQuery + ")");
    }

    static String renderRows(Randomly r, int minRows, int maxRows) {
        int rows = minRows + (int) r.getInteger(0, maxRows - minRows + 1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rows; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            int k = (int) r.getInteger(0, KEY_SPACE);
            long second = r.getInteger(-1000, 1000);
            sb.append('(').append(k).append(", ").append(second).append(')');
        }
        return sb.toString();
    }

    private boolean execute(String stmt, ExpectedErrors errors) throws SQLException {
        logStmt(stmt);
        return new SQLQueryAdapter(stmt, errors, true).execute(state);
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
