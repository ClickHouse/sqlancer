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
        } finally {
            dropQuietly(table);
        }
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
