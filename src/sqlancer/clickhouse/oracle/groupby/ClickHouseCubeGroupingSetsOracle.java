package sqlancer.clickhouse.oracle.groupby;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

import sqlancer.ComparatorHelper;
import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.clickhouse.ClickHouseErrors;
import sqlancer.clickhouse.ClickHouseProvider.ClickHouseGlobalState;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;

public class ClickHouseCubeGroupingSetsOracle implements TestOracle<ClickHouseGlobalState> {

    private static final AtomicLong CTR = new AtomicLong();
    private static final int DIFF_LIMIT = 20;
    private static final int A_DOMAIN = 4;
    private static final int B_DOMAIN = 3;

    private final ClickHouseGlobalState state;

    private final ExpectedErrors ddlErrors = new ExpectedErrors();
    private final ExpectedErrors readErrors = new ExpectedErrors();

    public ClickHouseCubeGroupingSetsOracle(ClickHouseGlobalState state) {
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
            e.add("ILLEGAL_AGGREGATION");
            e.add("NOT_AN_AGGREGATE");
            e.add("Unknown function GROUPING");
        }
    }

    @Override
    public void check() throws SQLException {
        if (!state.getClickHouseOptions().cubeGroupingSetsOracle) {
            throw new IgnoreMeException();
        }
        long id = CTR.incrementAndGet();
        String table = state.getDatabaseName() + ".cube_" + id;
        Randomly r = state.getRandomly();
        try {
            if (!execute("CREATE TABLE " + table + " (a Int32, b Int32, v Int64) ENGINE = MergeTree ORDER BY tuple()",
                    ddlErrors)) {
                throw new IgnoreMeException();
            }
            String values = renderValues(r);
            if (!execute("INSERT INTO " + table + " (a, b, v) VALUES " + values, readErrors)) {
                throw new IgnoreMeException();
            }

            checkCubeEqualsGroupingSets(table, values);
            checkSubtotalDecomposition(table, values);
        } finally {
            dropQuietly(table);
        }
    }

    private void checkCubeEqualsGroupingSets(String table, String values) throws SQLException {
        String projection = "toString(tuple(GROUPING(a), GROUPING(b), a, b, sum(v), count()))";
        String cube = "SELECT " + projection + " FROM " + table + " GROUP BY CUBE(a, b)";
        String groupingSets = "SELECT " + projection + " FROM " + table
                + " GROUP BY GROUPING SETS ((a, b), (a), (b), ())";

        List<String> cubeRows = ComparatorHelper.getResultSetFirstColumnAsString(cube, readErrors, state);
        List<String> setsRows = ComparatorHelper.getResultSetFirstColumnAsString(groupingSets, readErrors, state);
        List<String> diff = multisetDiff(cubeRows, setsRows);
        if (!diff.isEmpty()) {
            throw new AssertionError(String.format(
                    "CUBE vs GROUPING SETS multiset mismatch:%n  cube (%d rows): %s%n  grouping-sets (%d rows): %s%n  "
                            + "first differing entries: %s%n  values: %s",
                    cubeRows.size(), cube, setsRows.size(), groupingSets, diff, values));
        }
    }

    private void checkSubtotalDecomposition(String table, String values) throws SQLException {
        String grandSum = "SELECT toString(sum(v)) FROM " + table;
        String emptySetSum = "SELECT toString(sum(v)) FROM " + table + " GROUP BY GROUPING SETS (())";
        String grandSumValue = readSingleValue(grandSum);
        String emptySetSumValue = readSingleValue(emptySetSum);
        if (!grandSumValue.equals(emptySetSumValue)) {
            throw new AssertionError(String.format(
                    "CUBE subtotal sum mismatch:%n  grand: %s -> %s%n  empty-set: %s -> %s%n  values: %s",
                    grandSum, grandSumValue, emptySetSum, emptySetSumValue, values));
        }

        String grandCount = "SELECT toString(count()) FROM " + table;
        String emptySetCount = "SELECT toString(count()) FROM " + table + " GROUP BY GROUPING SETS (())";
        String grandCountValue = readSingleValue(grandCount);
        String emptySetCountValue = readSingleValue(emptySetCount);
        if (!grandCountValue.equals(emptySetCountValue)) {
            throw new AssertionError(String.format(
                    "CUBE subtotal count mismatch:%n  grand: %s -> %s%n  empty-set: %s -> %s%n  values: %s",
                    grandCount, grandCountValue, emptySetCount, emptySetCountValue, values));
        }
    }

    static String renderValues(Randomly r) {
        int rows = 25 + (int) r.getInteger(0, 26);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rows; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            int a = (int) r.getInteger(0, A_DOMAIN);
            int b = (int) r.getInteger(0, B_DOMAIN);
            long v = r.getInteger(0, 100);
            sb.append('(').append(a).append(", ").append(b).append(", ").append(v).append(')');
        }
        return sb.toString();
    }

    static List<String> multisetDiff(List<String> left, List<String> right) {
        Map<String, Long> counts = new TreeMap<>();
        for (String s : left) {
            counts.merge(s == null ? "\\N" : s, 1L, Long::sum);
        }
        for (String s : right) {
            counts.merge(s == null ? "\\N" : s, -1L, Long::sum);
        }
        List<String> diff = new ArrayList<>();
        for (Map.Entry<String, Long> e : counts.entrySet()) {
            if (e.getValue() == 0) {
                continue;
            }
            if (diff.size() >= DIFF_LIMIT) {
                break;
            }
            long c = e.getValue();
            diff.add(e.getKey() + " (+" + Math.abs(c) + " " + (c > 0 ? "cube" : "grouping-sets") + ")");
        }
        return diff;
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
