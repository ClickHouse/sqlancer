package sqlancer.clickhouse.oracle.final_;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import sqlancer.ComparatorHelper;
import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.clickhouse.ClickHouseErrors;
import sqlancer.clickhouse.ClickHouseProvider.ClickHouseGlobalState;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;

public class ClickHouseTupleFinalAggregationOracle implements TestOracle<ClickHouseGlobalState> {

    private static final AtomicLong TUPLE_COUNTER = new AtomicLong();
    private static final int KEYS = 6;

    private final ClickHouseGlobalState state;
    private final ExpectedErrors errors = new ExpectedErrors();

    public ClickHouseTupleFinalAggregationOracle(ClickHouseGlobalState state) {
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
        errors.add("allow_tuple_element_aggregation");
        errors.add("Unknown setting");
        errors.add("UNKNOWN_SETTING");
        errors.add("UNKNOWN_STORAGE");
        errors.add("Unknown table engine");
    }

    @Override
    public void check() throws SQLException {
        if (!state.getClickHouseOptions().tupleFinalAggregationOracle) {
            throw new IgnoreMeException();
        }
        boolean summing = Randomly.getBoolean();
        long id = TUPLE_COUNTER.incrementAndGet();
        String table = state.getDatabaseName() + ".tuplefin_" + id;
        try {
            if (summing) {
                checkSumming(table);
            } else {
                checkCoalescing(table);
            }
        } finally {
            dropQuietly(table);
        }
    }

    private void checkSumming(String table) throws SQLException {
        String tupleType = Randomly.getBoolean() ? "Tuple(Int64, Int64)" : "Tuple(a Int64, b Int64)";
        String create = "CREATE TABLE " + table + " (k UInt32, t " + tupleType
                + ", v Int64) ENGINE = SummingMergeTree ORDER BY k SETTINGS allow_tuple_element_aggregation = 1";
        create(create);

        Map<Long, long[]> model = new LinkedHashMap<>();
        int blocks = 2 + (int) Randomly.getNotCachedInteger(0, 3);
        for (int b = 0; b < blocks; b++) {
            StringBuilder sb = new StringBuilder("INSERT INTO ").append(table).append(" (k, t, v) VALUES ");
            for (int i = 0; i < KEYS; i++) {
                long k = i;
                long t1 = Randomly.getNotCachedInteger(-50, 51);
                long t2 = Randomly.getNotCachedInteger(-50, 51);
                long v = 1 + Randomly.getNotCachedInteger(0, 20);
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append('(').append(k).append(", (").append(t1).append(", ").append(t2).append("), ").append(v)
                        .append(')');
                long[] acc = model.computeIfAbsent(k, x -> new long[3]);
                acc[0] += t1;
                acc[1] += t2;
                acc[2] += v;
            }
            insert(sb.toString());
        }

        List<String> expected = new ArrayList<>();
        for (Map.Entry<Long, long[]> e : model.entrySet()) {
            long[] acc = e.getValue();
            expected.add("(" + e.getKey() + "," + acc[0] + "," + acc[1] + "," + acc[2] + ")");
        }

        String projection = "toString(tuple(k, t.1, t.2, v))";
        assertGroundTruth(table, projection, expected, "SummingMergeTree per-element Tuple summation");
        assertQueryTimeMatchesPhysicalFinal(table, projection);
        if (state.getClickHouseOptions().summingSubsetProjectionArm) {
            assertSubsetProjectionMatchesPhysicalFinal(table);
        }
    }

    private void checkCoalescing(String table) throws SQLException {
        String create = "CREATE TABLE " + table
                + " (k UInt32, t Tuple(Nullable(Int64), Nullable(Int64)), v Nullable(Int64)) "
                + "ENGINE = CoalescingMergeTree ORDER BY k SETTINGS allow_tuple_element_aggregation = 1";
        create(create);

        Map<Long, long[]> model = new LinkedHashMap<>();
        for (int slot = 0; slot < 3; slot++) {
            StringBuilder sb = new StringBuilder("INSERT INTO ").append(table).append(" (k, t, v) VALUES ");
            for (int i = 0; i < KEYS; i++) {
                long k = i;
                long value = 1 + Randomly.getNotCachedInteger(0, 100);
                String t1 = slot == 0 ? String.valueOf(value) : "NULL";
                String t2 = slot == 1 ? String.valueOf(value) : "NULL";
                String v = slot == 2 ? String.valueOf(value) : "NULL";
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append('(').append(k).append(", (").append(t1).append(", ").append(t2).append("), ").append(v)
                        .append(')');
                long[] acc = model.computeIfAbsent(k, x -> new long[3]);
                acc[slot] = value;
            }
            insert(sb.toString());
        }

        List<String> expected = new ArrayList<>();
        for (Map.Entry<Long, long[]> e : model.entrySet()) {
            long[] acc = e.getValue();
            expected.add("(" + e.getKey() + "," + acc[0] + "," + acc[1] + "," + acc[2] + ")");
        }

        String projection = "toString(tuple(k, t.1, t.2, v))";
        assertGroundTruth(table, projection, expected,
                "CoalescingMergeTree per-element Tuple coalescing (each element is non-NULL in exactly one part, "
                        + "so the outcome does not depend on merge order)");
        assertQueryTimeMatchesPhysicalFinal(table, projection);
    }

    private void assertGroundTruth(String table, String projection, List<String> expected, String what)
            throws SQLException {
        String query = "SELECT " + projection + " FROM " + table + " FINAL ORDER BY k";
        log(query);
        List<String> actual = ComparatorHelper.getResultSetFirstColumnAsString(query, errors, state);
        if (!expected.equals(actual)) {
            throw new AssertionError(String.format(
                    "%s disagrees with the Java ground truth over the inserted rows.%n  Q: %s%n  expected (%d): %s%n"
                            + "  actual   (%d): %s",
                    what, query, expected.size(), expected, actual.size(), actual));
        }
    }

    private void assertQueryTimeMatchesPhysicalFinal(String table, String projection) throws SQLException {
        String queryTime = "SELECT " + projection + " FROM " + table + " FINAL ORDER BY k";
        log(queryTime);
        List<String> before = ComparatorHelper.getResultSetFirstColumnAsString(queryTime, errors, state);

        String optimize = "OPTIMIZE TABLE " + table + " FINAL";
        log(optimize);
        if (!new SQLQueryAdapter(optimize, errors, false).execute(state)) {
            throw new IgnoreMeException();
        }

        String physical = "SELECT " + projection + " FROM " + table + " ORDER BY k";
        log(physical);
        List<String> after = ComparatorHelper.getResultSetFirstColumnAsString(physical, errors, state);
        if (!before.equals(after)) {
            throw new AssertionError(String.format(
                    "query-time FINAL and a physical OPTIMIZE ... FINAL disagree.%n  query-time: %s%n  physical:   %s%n"
                            + "  query-time rows (%d): %s%n  physical rows   (%d): %s",
                    queryTime, physical, before.size(), before, after.size(), after));
        }
    }

    private void assertSubsetProjectionMatchesPhysicalFinal(String table) throws SQLException {
        String subset = Randomly.fromOptions("toString(tuple(k, v))", "toString(tuple(k, t.1))", "toString(k)");
        String queryTime = "SELECT " + subset + " FROM " + table + " FINAL ORDER BY k";
        log(queryTime);
        List<String> before = ComparatorHelper.getResultSetFirstColumnAsString(queryTime, errors, state);
        String physical = "SELECT " + subset + " FROM " + table + " ORDER BY k";
        log(physical);
        List<String> after = ComparatorHelper.getResultSetFirstColumnAsString(physical, errors, state);
        if (!before.equals(after)) {
            throw new AssertionError(String.format(
                    "query-time FINAL over a subset of the summed columns dropped or kept rows differently from a "
                            + "physical OPTIMIZE ... FINAL (ClickHouse #106125 shape).%n  query-time: %s%n"
                            + "  physical:   %s%n  query-time rows (%d): %s%n  physical rows   (%d): %s",
                    queryTime, physical, before.size(), before, after.size(), after));
        }
    }

    private void create(String ddl) throws SQLException {
        log(ddl);
        if (!new SQLQueryAdapter(ddl, errors, true).execute(state)) {
            throw new IgnoreMeException();
        }
    }

    private void insert(String stmt) throws SQLException {
        log(stmt);
        if (!new SQLQueryAdapter(stmt, errors, true).execute(state)) {
            throw new IgnoreMeException();
        }
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
