package sqlancer.clickhouse.oracle.window;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;

import sqlancer.ComparatorHelper;
import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.clickhouse.ClickHouseErrors;
import sqlancer.clickhouse.ClickHouseProvider.ClickHouseGlobalState;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;

public class ClickHouseWindowFrameGroundTruthOracle implements TestOracle<ClickHouseGlobalState> {

    private static final AtomicLong CTR = new AtomicLong();
    private static final String NULL_TOKEN = "\\N";

    private final ClickHouseGlobalState state;
    private final ExpectedErrors errors = new ExpectedErrors();

    public ClickHouseWindowFrameGroundTruthOracle(ClickHouseGlobalState state) {
        this.state = state;
        ClickHouseErrors.addSessionSettingsErrors(errors);
        ClickHouseErrors.addExpectedExpressionErrors(errors);
        errors.add("UNKNOWN_TABLE");
        errors.add("(MEMORY_LIMIT_EXCEEDED)");
        errors.add("memory limit exceeded");
        errors.add("TIMEOUT_EXCEEDED");
        errors.add("Timeout exceeded");
        errors.add("Limit for result exceeded");
        errors.add("TOO_MANY_ROWS_OR_BYTES");
        errors.add("NOT_IMPLEMENTED");
        errors.add("ILLEGAL_TYPE_OF_ARGUMENT");
    }

    private enum Probe {
        PREFIX_SUM("sum(v) OVER (PARTITION BY p ORDER BY ord ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW)"),
        NEIGHBOR_SUM("sum(v) OVER (PARTITION BY p ORDER BY ord ROWS BETWEEN 1 PRECEDING AND 1 FOLLOWING)"),
        TRAILING_COUNT("count() OVER (PARTITION BY p ORDER BY ord ROWS BETWEEN 2 PRECEDING AND CURRENT ROW)"),
        NEIGHBOR_MIN("min(v) OVER (PARTITION BY p ORDER BY ord ROWS BETWEEN 1 PRECEDING AND 1 FOLLOWING)"),
        NEIGHBOR_MAX("max(v) OVER (PARTITION BY p ORDER BY ord ROWS BETWEEN 1 PRECEDING AND 1 FOLLOWING)"),
        FIRST_VALUE("first_value(v) OVER (PARTITION BY p ORDER BY ord ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW)"),
        LAST_VALUE("last_value(v) OVER (PARTITION BY p ORDER BY ord ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW)"),
        SUFFIX_SUM("sum(v) OVER (PARTITION BY p ORDER BY ord ROWS BETWEEN CURRENT ROW AND UNBOUNDED FOLLOWING)"),
        LAG("lagInFrame(v, 1) OVER (PARTITION BY p ORDER BY ord ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW)"),
        LEAD("leadInFrame(v, 1) OVER (PARTITION BY p ORDER BY ord ROWS BETWEEN CURRENT ROW AND UNBOUNDED FOLLOWING)");

        private final String windowExpr;

        Probe(String windowExpr) {
            this.windowExpr = windowExpr;
        }
    }

    @Override
    public void check() throws SQLException {
        if (!state.getClickHouseOptions().windowFrameGroundTruthOracle) {
            throw new IgnoreMeException();
        }
        long id = CTR.incrementAndGet();
        Randomly r = state.getRandomly();
        String table = state.getDatabaseName() + ".winf_" + id;
        String create = "CREATE TABLE " + table + " (p UInt32, ord Int64, v Int64) ENGINE = MergeTree ORDER BY (p, ord)";

        int partitions = 2 + r.getInteger(0, 2);
        List<List<Long>> model = new ArrayList<>();

        try {
            logStmt(create);
            if (!new SQLQueryAdapter(create, errors, true).execute(state)) {
                throw new IgnoreMeException();
            }

            StringBuilder sb = new StringBuilder("INSERT INTO ").append(table).append(" (p, ord, v) VALUES ");
            boolean firstRow = true;
            for (int p = 0; p < partitions; p++) {
                int rows = 10 + r.getInteger(0, 11);
                TreeSet<Long> ords = new TreeSet<>();
                while (ords.size() < rows) {
                    ords.add((long) r.getInteger(0, 10000));
                }
                List<Long> partitionValues = new ArrayList<>();
                for (Long ord : ords) {
                    long v = r.getInteger(-50, 51);
                    partitionValues.add(v);
                    if (!firstRow) {
                        sb.append(", ");
                    }
                    firstRow = false;
                    sb.append('(').append(p).append(", ").append(ord).append(", ").append(v).append(')');
                }
                model.add(partitionValues);
            }
            if (firstRow) {
                throw new IgnoreMeException();
            }
            logStmt(sb.toString());
            if (!new SQLQueryAdapter(sb.toString(), errors, true).execute(state)) {
                throw new IgnoreMeException();
            }

            List<Probe> probes = new ArrayList<>(List.of(Probe.values()));
            java.util.Collections.shuffle(probes, new java.util.Random(r.getInteger(0, Integer.MAX_VALUE)));
            int probeCount = 3 + r.getInteger(0, 3);
            for (int i = 0; i < probeCount && i < probes.size(); i++) {
                Probe probe = probes.get(i);
                assertProbe(table, probe, expected(probe, model));
            }
            if (state.getClickHouseOptions().groupsWindowFrameEmission) {
                assertGroupsEqualsRowsOnUniqueKey(table);
            }
        } finally {
            dropQuietly(table);
        }
        if (state.getClickHouseOptions().groupsWindowFrameEmission) {
            checkGroupsFrame();
        }
    }

    private void assertGroupsEqualsRowsOnUniqueKey(String table) throws SQLException {
        long offset = 1 + state.getRandomly().getInteger(0, 3);
        String query = "SELECT toString(tuple(g, w)) FROM (SELECT sum(v) OVER (PARTITION BY p ORDER BY ord GROUPS "
                + "BETWEEN " + offset + " PRECEDING AND CURRENT ROW) AS g, sum(v) OVER (PARTITION BY p ORDER BY ord "
                + "ROWS BETWEEN " + offset + " PRECEDING AND CURRENT ROW) AS w FROM " + table + ") WHERE g != w";
        logStmt(query);
        List<String> violations = ComparatorHelper.getResultSetFirstColumnAsString(query, errors, state);
        if (!violations.isEmpty()) {
            throw new AssertionError(String.format(
                    "GROUPS frame differs from the equivalent ROWS frame on a fixture whose window ORDER BY key is "
                            + "unique per partition, so every peer group holds exactly one row and the two frames must "
                            + "coincide. %d rows disagree (GROUPS, ROWS): %s%n  Q: %s",
                    violations.size(), truncate(violations), query));
        }
    }

    private void checkGroupsFrame() throws SQLException {
        long id = CTR.incrementAndGet();
        Randomly r = state.getRandomly();
        String table = state.getDatabaseName() + ".wing_" + id;
        String create = "CREATE TABLE " + table
                + " (p UInt32, ord Int64, rid Int64, v Int64) ENGINE = MergeTree ORDER BY (p, ord, rid)";

        int partitions = 2 + r.getInteger(0, 2);
        List<List<List<Long>>> model = new ArrayList<>();

        try {
            logStmt(create);
            if (!new SQLQueryAdapter(create, errors, true).execute(state)) {
                throw new IgnoreMeException();
            }

            StringBuilder sb = new StringBuilder("INSERT INTO ").append(table).append(" (p, ord, rid, v) VALUES ");
            boolean firstRow = true;
            long rid = 0;
            for (int p = 0; p < partitions; p++) {
                int groups = 3 + r.getInteger(0, 5);
                List<List<Long>> partitionGroups = new ArrayList<>();
                long ord = 0;
                for (int g = 0; g < groups; g++) {
                    ord += 1 + r.getInteger(0, 5);
                    int peers = 1 + r.getInteger(0, 3);
                    List<Long> groupValues = new ArrayList<>();
                    for (int i = 0; i < peers; i++) {
                        long v = r.getInteger(-50, 51);
                        groupValues.add(v);
                        if (!firstRow) {
                            sb.append(", ");
                        }
                        firstRow = false;
                        sb.append('(').append(p).append(", ").append(ord).append(", ").append(rid++).append(", ")
                                .append(v).append(')');
                    }
                    partitionGroups.add(groupValues);
                }
                model.add(partitionGroups);
            }
            if (firstRow) {
                throw new IgnoreMeException();
            }
            logStmt(sb.toString());
            if (!new SQLQueryAdapter(sb.toString(), errors, true).execute(state)) {
                throw new IgnoreMeException();
            }

            for (GroupsProbe probe : GroupsProbe.values()) {
                assertGroupsProbe(table, probe, expectedGroups(probe, model));
            }
            assertConstantKeyWholePartition();
        } finally {
            dropQuietly(table);
        }
    }

    private void assertConstantKeyWholePartition() throws SQLException {
        String query = "SELECT toString(tuple(g, whole)) FROM (SELECT sum(v) OVER (PARTITION BY p ORDER BY ord GROUPS "
                + "BETWEEN 0 PRECEDING AND 0 FOLLOWING) AS g, sum(v) OVER (PARTITION BY p) AS whole FROM "
                + "(SELECT number % 3 AS p, 7 AS ord, toInt64(number) AS v FROM numbers(30))) WHERE g != whole";
        logStmt(query);
        List<String> violations = ComparatorHelper.getResultSetFirstColumnAsString(query, errors, state);
        if (!violations.isEmpty()) {
            throw new AssertionError(String.format(
                    "GROUPS BETWEEN 0 PRECEDING AND 0 FOLLOWING over a constant window ORDER BY key must cover the "
                            + "whole partition (every row is one peer group), but %d rows disagree with the "
                            + "whole-partition aggregate (GROUPS, whole): %s%n  Q: %s",
                    violations.size(), truncate(violations), query));
        }
    }

    private enum GroupsProbe {
        PREFIX("sum(v) OVER (PARTITION BY p ORDER BY ord GROUPS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW)"),
        NEIGHBOR("sum(v) OVER (PARTITION BY p ORDER BY ord GROUPS BETWEEN 1 PRECEDING AND 1 FOLLOWING)"),
        TRAILING_COUNT("count() OVER (PARTITION BY p ORDER BY ord GROUPS BETWEEN 2 PRECEDING AND CURRENT ROW)"),
        CURRENT_GROUP_MIN("min(v) OVER (PARTITION BY p ORDER BY ord GROUPS BETWEEN CURRENT ROW AND CURRENT ROW)"),
        SUFFIX_MAX("max(v) OVER (PARTITION BY p ORDER BY ord GROUPS BETWEEN CURRENT ROW AND UNBOUNDED FOLLOWING)");

        private final String windowExpr;

        GroupsProbe(String windowExpr) {
            this.windowExpr = windowExpr;
        }
    }

    private static List<String> expectedGroups(GroupsProbe probe, List<List<List<Long>>> model) {
        List<String> result = new ArrayList<>();
        for (List<List<Long>> partition : model) {
            int groups = partition.size();
            for (int g = 0; g < groups; g++) {
                int lo;
                int hi;
                switch (probe) {
                case PREFIX:
                    lo = 0;
                    hi = g;
                    break;
                case NEIGHBOR:
                    lo = Math.max(0, g - 1);
                    hi = Math.min(groups - 1, g + 1);
                    break;
                case TRAILING_COUNT:
                    lo = Math.max(0, g - 2);
                    hi = g;
                    break;
                case CURRENT_GROUP_MIN:
                    lo = g;
                    hi = g;
                    break;
                case SUFFIX_MAX:
                    lo = g;
                    hi = groups - 1;
                    break;
                default:
                    throw new AssertionError(probe.name());
                }
                String value = aggregateOverGroups(probe, partition, lo, hi);
                for (int i = 0; i < partition.get(g).size(); i++) {
                    result.add(value);
                }
            }
        }
        return result;
    }

    private static String aggregateOverGroups(GroupsProbe probe, List<List<Long>> partition, int lo, int hi) {
        long sum = 0;
        long count = 0;
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        for (int g = lo; g <= hi; g++) {
            for (Long v : partition.get(g)) {
                sum += v;
                count++;
                min = Math.min(min, v);
                max = Math.max(max, v);
            }
        }
        switch (probe) {
        case PREFIX:
        case NEIGHBOR:
            return String.valueOf(sum);
        case TRAILING_COUNT:
            return String.valueOf(count);
        case CURRENT_GROUP_MIN:
            return String.valueOf(min);
        case SUFFIX_MAX:
            return String.valueOf(max);
        default:
            throw new AssertionError(probe.name());
        }
    }

    private void assertGroupsProbe(String table, GroupsProbe probe, List<String> expected) throws SQLException {
        String query = "SELECT toString(" + probe.windowExpr + ") FROM " + table + " ORDER BY p, ord, rid";
        logStmt(query);
        List<String> actual = ComparatorHelper.getResultSetFirstColumnAsString(query, errors, state);
        if (actual.size() != expected.size()) {
            throw new AssertionError(String.format(
                    "GROUPS-frame ground-truth row-count mismatch (%s): Java expects %d rows but query returned %d. "
                            + "Q: %s",
                    probe.name(), expected.size(), actual.size(), query));
        }
        for (int i = 0; i < expected.size(); i++) {
            if (!nullSafeEquals(expected.get(i), actual.get(i))) {
                throw new AssertionError(String.format(
                        "GROUPS-frame ground-truth mismatch (%s) at global row %d: the frame spans peer groups (rows "
                                + "tied on the window ORDER BY key), Java expects %s but query returned %s. Q: %s",
                        probe.name(), i, expected.get(i), actual.get(i), query));
            }
        }
    }

    private static String truncate(List<String> rows) {
        int limit = 20;
        if (rows.size() <= limit) {
            return rows.toString();
        }
        return rows.subList(0, limit) + "... (" + rows.size() + " total)";
    }

    private static List<String> expected(Probe probe, List<List<Long>> model) {
        List<String> result = new ArrayList<>();
        for (List<Long> values : model) {
            int n = values.size();
            for (int i = 0; i < n; i++) {
                result.add(expectedAt(probe, values, i, n));
            }
        }
        return result;
    }

    private static String expectedAt(Probe probe, List<Long> values, int i, int n) {
        switch (probe) {
        case PREFIX_SUM: {
            long sum = 0;
            for (int j = 0; j <= i; j++) {
                sum += values.get(j);
            }
            return String.valueOf(sum);
        }
        case SUFFIX_SUM: {
            long sum = 0;
            for (int j = i; j < n; j++) {
                sum += values.get(j);
            }
            return String.valueOf(sum);
        }
        case NEIGHBOR_SUM: {
            long sum = 0;
            for (int j = Math.max(0, i - 1); j <= Math.min(n - 1, i + 1); j++) {
                sum += values.get(j);
            }
            return String.valueOf(sum);
        }
        case TRAILING_COUNT:
            return String.valueOf(Math.min(3, i + 1));
        case NEIGHBOR_MIN: {
            long min = values.get(i);
            for (int j = Math.max(0, i - 1); j <= Math.min(n - 1, i + 1); j++) {
                min = Math.min(min, values.get(j));
            }
            return String.valueOf(min);
        }
        case NEIGHBOR_MAX: {
            long max = values.get(i);
            for (int j = Math.max(0, i - 1); j <= Math.min(n - 1, i + 1); j++) {
                max = Math.max(max, values.get(j));
            }
            return String.valueOf(max);
        }
        case FIRST_VALUE:
            return String.valueOf(values.get(0));
        case LAST_VALUE:
            return String.valueOf(values.get(i));
        case LAG:
            return i == 0 ? "0" : String.valueOf(values.get(i - 1));
        case LEAD:
            return i == n - 1 ? "0" : String.valueOf(values.get(i + 1));
        default:
            throw new AssertionError(probe.name());
        }
    }

    private void assertProbe(String table, Probe probe, List<String> expected) throws SQLException {
        String query = "SELECT toString(" + probe.windowExpr + ") FROM " + table + " ORDER BY p, ord";
        logStmt(query);
        List<String> actual = ComparatorHelper.getResultSetFirstColumnAsString(query, errors, state);
        if (actual.size() != expected.size()) {
            throw new AssertionError(String.format(
                    "window-frame ground-truth row-count mismatch (%s): Java expects %d rows but query returned %d. Q: %s",
                    probe.name(), expected.size(), actual.size(), query));
        }
        for (int i = 0; i < expected.size(); i++) {
            if (!nullSafeEquals(expected.get(i), actual.get(i))) {
                throw new AssertionError(String.format(
                        "window-frame ground-truth mismatch (%s) at global row %d: Java expects %s but query returned %s. "
                                + "Q: %s",
                        probe.name(), i, expected.get(i), actual.get(i), query));
            }
        }
    }

    private static boolean nullSafeEquals(String expected, String actual) {
        if (NULL_TOKEN.equals(expected)) {
            return actual == null || NULL_TOKEN.equals(actual);
        }
        if (actual == null) {
            return false;
        }
        return expected.equals(actual);
    }

    private void dropQuietly(String table) {
        try {
            new SQLQueryAdapter("DROP TABLE IF EXISTS " + table, errors, true).execute(state);
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
