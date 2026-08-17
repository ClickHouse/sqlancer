package sqlancer.clickhouse.oracle.join;

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

public class ClickHouseIEJoinOracle implements TestOracle<ClickHouseGlobalState> {

    private static final AtomicLong IE_COUNTER = new AtomicLong();
    private static final int DIFF_LIMIT = 20;
    private static final String CAPS = "max_result_rows = 1000000, result_overflow_mode = 'throw', "
            + "max_bytes_in_join = 268435456, max_memory_usage = 1073741824";

    private final ClickHouseGlobalState state;
    private final ExpectedErrors errors = new ExpectedErrors();

    public ClickHouseIEJoinOracle(ClickHouseGlobalState state) {
        this.state = state;
        ClickHouseErrors.addExpectedExpressionErrors(errors);
        ClickHouseErrors.addSessionSettingsErrors(errors);
        errors.add("UNKNOWN_TABLE");
        errors.add("(MEMORY_LIMIT_EXCEEDED)");
        errors.add("memory limit exceeded");
        errors.add("TIMEOUT_EXCEEDED");
        errors.add("Timeout exceeded");
        errors.add("Limit for result exceeded");
        errors.add("TOO_MANY_ROWS_OR_BYTES");
        errors.add("Limit for JOIN exceeded");
        errors.add("Can't execute any of specified algorithms");
        errors.add("INVALID_JOIN_ON_EXPRESSION");
        errors.add("Cannot determine join keys");
        errors.add("UNKNOWN_JOIN");
    }

    @Override
    public void check() throws SQLException {
        if (!state.getClickHouseOptions().ieJoinOracle) {
            throw new IgnoreMeException();
        }
        long id = IE_COUNTER.incrementAndGet();
        String left = state.getDatabaseName() + ".iejoin_" + id + "_l";
        String right = state.getDatabaseName() + ".iejoin_" + id + "_r";
        try {
            createAndSeed(left);
            createAndSeed(right);

            String leftOp = Randomly.fromOptions("<", "<=");
            String rightOp = Randomly.fromOptions(">", ">=");
            String on = "l.x " + leftOp + " r.x AND l.y " + rightOp + " r.y";
            String projection = "toString(tuple(l.k, r.k))";

            String ieJoin = "SELECT " + projection + " FROM " + left + " AS l INNER JOIN " + right + " AS r ON " + on
                    + " SETTINGS join_algorithm = 'ie_join', " + CAPS;
            String crossJoin = "SELECT " + projection + " FROM " + left + " AS l CROSS JOIN " + right + " AS r WHERE "
                    + on + " SETTINGS " + CAPS;

            log(ieJoin);
            List<String> ieRows = ComparatorHelper.getResultSetFirstColumnAsString(ieJoin, errors, state);
            log(crossJoin);
            List<String> crossRows = ComparatorHelper.getResultSetFirstColumnAsString(crossJoin, errors, state);

            List<String> diff = multisetDiff(crossRows, ieRows, DIFF_LIMIT);
            if (!diff.isEmpty()) {
                throw new AssertionError(String.format(
                        "IEJoin mismatch: a join whose ON carries two inequality comparisons returned %d rows under "
                                + "join_algorithm = 'ie_join' but the equivalent CROSS JOIN with the same two "
                                + "comparisons in WHERE returned %d.%n  ie_join:    %s%n  cross join: %s%n"
                                + "  first %d differing (left key, right key) pairs: %s",
                        ieRows.size(), crossRows.size(), ieJoin, crossJoin, diff.size(), diff));
            }

            checkLeftPreservation(left, right, on);
        } finally {
            dropQuietly(left);
            dropQuietly(right);
        }
    }

    private void checkLeftPreservation(String left, String right, String on) throws SQLException {
        String outer = "SELECT toString(count(DISTINCT l.k)) FROM " + left + " AS l LEFT JOIN " + right + " AS r ON "
                + on + " SETTINGS join_algorithm = 'ie_join', " + CAPS;
        String total = "SELECT toString(count()) FROM " + left;
        log(outer);
        long joined = scalar(outer);
        long rows = scalar(total);
        if (joined != rows) {
            throw new AssertionError(String.format(
                    "IEJoin LEFT-preservation violation: a LEFT JOIN must emit at least one row for every left row, "
                            + "so the number of distinct left keys in its output (%d) must equal the left table's row "
                            + "count (%d).%n  Q: %s",
                    joined, rows, outer));
        }
    }

    private void createAndSeed(String table) throws SQLException {
        String create = "CREATE TABLE " + table + " (k Int64, x Int64, y Int64) ENGINE = MergeTree ORDER BY k";
        log(create);
        if (!new SQLQueryAdapter(create, errors, true).execute(state)) {
            throw new IgnoreMeException();
        }
        int rows = 8 + (int) Randomly.getNotCachedInteger(0, 25);
        StringBuilder sb = new StringBuilder("INSERT INTO ").append(table).append(" (k, x, y) VALUES ");
        for (int i = 0; i < rows; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append('(').append(i).append(", ").append(Randomly.getNotCachedInteger(-30, 30)).append(", ")
                    .append(Randomly.getNotCachedInteger(-30, 30)).append(')');
        }
        log(sb.toString());
        if (!new SQLQueryAdapter(sb.toString(), errors, true).execute(state)) {
            throw new IgnoreMeException();
        }
    }

    private long scalar(String query) throws SQLException {
        List<String> rows = ComparatorHelper.getResultSetFirstColumnAsString(query, errors, state);
        if (rows.size() != 1 || rows.get(0) == null) {
            throw new IgnoreMeException();
        }
        try {
            return Long.parseLong(rows.get(0).trim());
        } catch (NumberFormatException e) {
            throw new IgnoreMeException();
        }
    }

    private static List<String> multisetDiff(List<String> a, List<String> b, int limit) {
        Map<String, Long> counts = new TreeMap<>();
        for (String s : a) {
            counts.merge(s == null ? "\\N" : s, 1L, Long::sum);
        }
        for (String s : b) {
            counts.merge(s == null ? "\\N" : s, -1L, Long::sum);
        }
        List<String> diff = new ArrayList<>();
        for (Map.Entry<String, Long> e : counts.entrySet()) {
            if (e.getValue() == 0) {
                continue;
            }
            if (diff.size() >= limit) {
                break;
            }
            long c = e.getValue();
            diff.add(e.getKey() + " (+" + Math.abs(c) + " " + (c > 0 ? "cross join" : "ie_join") + ")");
        }
        return diff;
    }

    private void log(String sql) {
        if (state.getOptions().logEachSelect()) {
            state.getLogger().writeCurrent(sql);
            state.getState().logStatement(sql);
        }
    }

    private void dropQuietly(String table) {
        try {
            new SQLQueryAdapter("DROP TABLE IF EXISTS " + table, errors, true).execute(state);
        } catch (Exception | AssertionError ignored) {
        }
    }
}
