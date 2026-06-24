package sqlancer.clickhouse.oracle.sample;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import sqlancer.ComparatorHelper;
import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.clickhouse.ClickHouseErrors;
import sqlancer.clickhouse.ClickHouseProvider.ClickHouseGlobalState;
import sqlancer.clickhouse.ClickHouseSchema.ClickHouseTable;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;

public class ClickHouseSampleClauseOracle implements TestOracle<ClickHouseGlobalState> {

    private static final int DIFF_LIMIT = 20;
    private static final AtomicLong SAMPLE_COUNTER = new AtomicLong();

    private final ClickHouseGlobalState state;
    private final ExpectedErrors errors = new ExpectedErrors();

    public ClickHouseSampleClauseOracle(ClickHouseGlobalState state) {
        this.state = state;
        ClickHouseErrors.addExpectedExpressionErrors(errors);
        ClickHouseErrors.addSessionSettingsErrors(errors);

        errors.add("UNKNOWN_TABLE");
        errors.add("Unknown table expression identifier");
        errors.add("SAMPLING_NOT_SUPPORTED");
        errors.add("Illegal SAMPLE");
        errors.add("(MEMORY_LIMIT_EXCEEDED)");
        errors.add("memory limit exceeded");
        errors.add("TIMEOUT_EXCEEDED");
        errors.add("Timeout exceeded");
        errors.add("Limit for result exceeded");
        errors.add("TOO_MANY_ROWS_OR_BYTES");
    }

    @Override
    public void check() throws SQLException {
        if (!state.getClickHouseOptions().sampleClauseOracle) {
            throw new IgnoreMeException();
        }

        String table = pickSampleableTable();
        boolean selfCreated = table == null;
        if (selfCreated) {
            table = createFixture();
        }
        try {
            runInvariants(table);
        } finally {
            if (selfCreated) {
                dropQuietly(table);
            }
        }
    }

    private String pickSampleableTable() {
        List<ClickHouseTable> candidates = state.getSchema().getDatabaseTablesWithoutViews().stream()
                .filter(t -> !t.isView()).filter(ClickHouseTable::hasSamplingKey)
                .filter(t -> "MergeTree".equals(t.getEngine())).collect(Collectors.toList());
        if (candidates.isEmpty()) {
            return null;
        }
        ClickHouseTable t = candidates.get((int) Randomly.getNotCachedInteger(0, candidates.size()));
        return state.getDatabaseName() + "." + t.getName();
    }

    private String createFixture() throws SQLException {
        long id = SAMPLE_COUNTER.incrementAndGet();
        String name = state.getDatabaseName() + ".samp_" + id;
        int rows = 100 + (int) Randomly.getNotCachedInteger(0, 1900);
        String create = "CREATE TABLE " + name
                + " (id UInt32, v Int64) ENGINE = MergeTree ORDER BY id SAMPLE BY id";
        String insert = "INSERT INTO " + name + " SELECT number AS id, toInt64(number * 7 % 100) AS v FROM numbers("
                + rows + ")";
        logStatements(create, insert);
        if (!new SQLQueryAdapter(create, errors, true).execute(state)) {
            throw new IgnoreMeException();
        }
        if (!new SQLQueryAdapter(insert, errors, true).execute(state)) {
            dropQuietly(name);
            throw new IgnoreMeException();
        }
        return name;
    }

    private void runInvariants(String table) throws SQLException {
        String fullSql = "SELECT toString(tuple(*)) FROM " + table;
        List<String> full = read(fullSql);
        if (full.isEmpty()) {
            throw new IgnoreMeException();
        }

        String identitySql = "SELECT toString(tuple(*)) FROM " + table + " SAMPLE 1";
        List<String> identity = read(identitySql);
        if (identity.size() != full.size()) {
            throw new IgnoreMeException();
        }
        assertMultisetsEqual(full, identity, fullSql, identitySql, "SAMPLE 1 identity");

        String ratio = Randomly.fromOptions("0.5", "0.25", "0.1");
        String subsetSql = "SELECT toString(tuple(*)) FROM " + table + " SAMPLE " + ratio;
        List<String> subset = read(subsetSql);
        assertSubset(subset, full, subsetSql, fullSql, "SAMPLE " + ratio + " subset");

        int k = 2 + (int) Randomly.getNotCachedInteger(0, 3);
        for (int i = 0; i < k; i++) {
            String tileSql = "SELECT toString(tuple(*)) FROM " + table + " SAMPLE 1/" + k + " OFFSET " + i + "/" + k;
            List<String> tile = read(tileSql);
            assertSubset(tile, full, tileSql, fullSql, "SAMPLE 1/" + k + " OFFSET " + i + "/" + k + " subset");
        }

        if (state.getClickHouseOptions().sampleFactorArm) {
            checkSampleFactor(table, full.size());
        }
    }

    private void checkSampleFactor(String table, int fullCount) throws SQLException {
        String sql = "SELECT toString(toUInt64(round(sum(_sample_factor)))) FROM " + table + " SAMPLE 0.5";
        List<String> rows = read(sql);
        if (rows.size() != 1) {
            throw new IgnoreMeException();
        }
        long reconstructed;
        try {
            reconstructed = Long.parseLong(rows.get(0));
        } catch (NumberFormatException e) {
            throw new IgnoreMeException();
        }
        if (reconstructed < fullCount / 5L || reconstructed > fullCount * 5L) {
            throw new AssertionError(String.format(
                    "_sample_factor reconstruction far off: %s gave %d but full count is %d", sql, reconstructed,
                    fullCount));
        }
    }

    private List<String> read(String sql) throws SQLException {
        if (state.getOptions().logEachSelect()) {
            state.getLogger().writeCurrent(sql);
        }
        return ComparatorHelper.getResultSetFirstColumnAsString(sql, errors, state);
    }

    private void logStatements(String... stmts) {
        if (!state.getOptions().logEachSelect()) {
            return;
        }
        for (String stmt : stmts) {
            state.getLogger().writeCurrent(stmt);
            state.getState().logStatement(stmt);
        }
    }

    private void dropQuietly(String table) {
        try {
            new SQLQueryAdapter("DROP TABLE IF EXISTS " + table, errors, true).execute(state);
        } catch (SQLException ignored) {
        }
    }

    private static void assertMultisetsEqual(List<String> expected, List<String> actual, String expectedSql,
            String actualSql, String label) {
        List<String> diff = multisetDiff(expected, actual, DIFF_LIMIT);
        if (diff.isEmpty()) {
            return;
        }
        throw new AssertionError(String.format(
                "%s mismatch: %d expected rows vs %d actual rows.%nexpected: %s%nactual: %s%nfirst %d differing: %s",
                label, expected.size(), actual.size(), expectedSql, actualSql, diff.size(), diff));
    }

    private static void assertSubset(List<String> subset, List<String> superset, String subsetSql, String supersetSql,
            String label) {
        Map<String, Long> counts = new TreeMap<>();
        for (String s : superset) {
            counts.merge(s == null ? "\\N" : s, 1L, Long::sum);
        }
        List<String> violations = new ArrayList<>();
        for (String s : subset) {
            String key = s == null ? "\\N" : s;
            long remaining = counts.merge(key, -1L, Long::sum);
            if (remaining < 0 && violations.size() < DIFF_LIMIT) {
                violations.add(key);
            }
        }
        if (violations.isEmpty()) {
            return;
        }
        throw new AssertionError(String.format(
                "%s: sampled rows are NOT a subset of the full read (%d sampled vs %d full).%nsample: %s%nfull: %s%n"
                        + "first %d rows present more often in sample than in full: %s",
                label, subset.size(), superset.size(), subsetSql, supersetSql, violations.size(), violations));
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
            diff.add(e.getKey() + " (+" + Math.abs(c) + " " + (c > 0 ? "expected" : "actual") + ")");
        }
        return diff;
    }
}
