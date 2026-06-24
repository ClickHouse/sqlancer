package sqlancer.clickhouse.oracle.aggfamily;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.clickhouse.ClickHouseErrors;
import sqlancer.clickhouse.ClickHouseProvider.ClickHouseGlobalState;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;

public class ClickHouseArgExtremumOracle implements TestOracle<ClickHouseGlobalState> {

    private static final AtomicLong CTR = new AtomicLong();
    private static final int TOP_N = 3;

    private final ClickHouseGlobalState state;

    private final ExpectedErrors createErrors = new ExpectedErrors();
    private final ExpectedErrors readErrors = new ExpectedErrors();

    public ClickHouseArgExtremumOracle(ClickHouseGlobalState state) {
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
        if (!state.getClickHouseOptions().argExtremumOracle) {
            throw new IgnoreMeException();
        }
        long id = CTR.incrementAndGet();
        String table = state.getDatabaseName() + ".argx_" + id;
        Randomly r = state.getRandomly();
        String create = "CREATE TABLE " + table + " (k Int64, v Int64) ENGINE = MergeTree ORDER BY tuple()";

        try {
            logStmt(create);
            if (!new SQLQueryAdapter(create, createErrors, true).execute(state)) {
                throw new IgnoreMeException();
            }

            List<Long> values = new ArrayList<>();
            long nextK = r.getInteger(-1000, 1000);
            long vAtMaxK = 0;
            int blocks = 1 + r.getInteger(0, 3);
            for (int b = 0; b < blocks; b++) {
                int rows = 20 + r.getInteger(0, 41);
                StringBuilder sb = new StringBuilder("INSERT INTO ").append(table).append(" (k, v) VALUES ");
                for (int i = 0; i < rows; i++) {
                    long v = r.getInteger(-100000, 100000);
                    values.add(v);
                    vAtMaxK = v;
                    if (i > 0) {
                        sb.append(", ");
                    }
                    sb.append('(').append(nextK).append(", ").append(v).append(')');
                    nextK += 1 + r.getInteger(0, 7);
                }
                logStmt(sb.toString());
                if (!new SQLQueryAdapter(sb.toString(), readErrors, true).execute(state)) {
                    throw new IgnoreMeException();
                }
            }

            if (values.isEmpty()) {
                throw new IgnoreMeException();
            }

            List<Long> sorted = new ArrayList<>(values);
            sorted.sort(Long::compareTo);
            String expectedSortedArray = renderLongArray(sorted);
            String expectedArgMax = Long.toString(vAtMaxK);
            String expectedTopN = renderLongArray(sorted.subList(0, Math.min(TOP_N, sorted.size())));

            String query = "SELECT toString(arraySort(groupArray(v))) AS a, toString(argMax(v, k)) AS b, "
                    + "toString(groupArraySorted(" + TOP_N + ")(v)) AS c FROM " + table;
            logStmt(query);

            String[] observed = readThreeStrings(query);
            assertEquals("arraySort(groupArray(v))", expectedSortedArray, observed[0], query, create);
            assertEquals("argMax(v, k)", expectedArgMax, observed[1], query, create);
            assertEquals("groupArraySorted(" + TOP_N + ")(v)", expectedTopN, observed[2], query, create);
        } finally {
            dropQuietly(table);
        }
    }

    private String[] readThreeStrings(String query) throws SQLException {
        try (Statement s = state.getConnection().createStatement(); ResultSet rs = s.executeQuery(query)) {
            if (!rs.next()) {
                throw new IgnoreMeException();
            }
            String a = rs.getString(1);
            String b = rs.getString(2);
            String c = rs.getString(3);
            if (rs.next()) {
                throw new IgnoreMeException();
            }
            return new String[] { a, b, c };
        } catch (SQLException ex) {
            if (ex.getMessage() != null && readErrors.errorIsExpected(ex.getMessage())) {
                throw new IgnoreMeException();
            }
            throw ex;
        }
    }

    private static void assertEquals(String label, String expected, String observed, String query, String create) {
        if (!expected.equals(observed)) {
            throw new AssertionError(String.format(
                    "argMin/argMax/groupArray ground-truth mismatch on %s: Java expects %s but ClickHouse returned %s. "
                            + "Query: %s. DDL: %s",
                    label, expected, observed, query, create));
        }
    }

    static String renderLongArray(List<Long> values) {
        return "[" + values.stream().map(String::valueOf).collect(Collectors.joining(",")) + "]";
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
