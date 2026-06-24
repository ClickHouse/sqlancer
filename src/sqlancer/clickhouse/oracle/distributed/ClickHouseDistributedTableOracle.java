package sqlancer.clickhouse.oracle.distributed;

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

public class ClickHouseDistributedTableOracle implements TestOracle<ClickHouseGlobalState> {

    private static final int DIFF_LIMIT = 20;
    private static final AtomicLong DIST_COUNTER = new AtomicLong();

    private final ClickHouseGlobalState state;
    private final ExpectedErrors errors = new ExpectedErrors();

    public ClickHouseDistributedTableOracle(ClickHouseGlobalState state) {
        this.state = state;
        ClickHouseErrors.addExpectedExpressionErrors(errors);
        ClickHouseErrors.addSessionSettingsErrors(errors);

        errors.add("UNKNOWN_TABLE");
        errors.add("Unknown table expression identifier");
        errors.add("UNKNOWN_STORAGE");
        errors.add("Unknown table engine");
        errors.add("SUPPORT_IS_DISABLED");
        errors.add("NOT_IMPLEMENTED");

        errors.add("(MEMORY_LIMIT_EXCEEDED)");
        errors.add("memory limit exceeded");
        errors.add("TIMEOUT_EXCEEDED");
        errors.add("Timeout exceeded");
        errors.add("Limit for result exceeded");
        errors.add("TOO_MANY_ROWS_OR_BYTES");

        errors.add("Requested cluster");
        errors.add("CLUSTER_DOESNT_EXIST");
        errors.add("There is no Cluster");
        errors.add("NETWORK_ERROR");
        errors.add("Connection refused");
        errors.add("All connection tries failed");
        errors.add("ACCESS_DENIED");
        errors.add("Not enough privileges");
    }

    @Override
    public void check() throws SQLException {
        if (!state.getClickHouseOptions().distributedTableOracle) {
            throw new IgnoreMeException();
        }

        long id = DIST_COUNTER.incrementAndGet();
        String db = state.getDatabaseName();
        String localBare = "dist_local_" + id;
        String local = db + "." + localBare;
        String dist = db + ".dist_" + id;

        int groups = 2 + (int) Randomly.getNotCachedInteger(0, 8);
        int rows = 100 + (int) Randomly.getNotCachedInteger(0, 900);

        String createLocal = "CREATE TABLE " + local
                + " (id UInt32, g UInt32, v Int64) ENGINE = MergeTree ORDER BY id";
        String insertLocal = "INSERT INTO " + local + " SELECT number AS id, toUInt32(number % " + groups
                + ") AS g, toInt64(number * 7 % 100) AS v FROM numbers(" + rows + ")";
        String createDist = "CREATE TABLE " + dist + " AS " + local + " ENGINE = Distributed('default', "
                + "currentDatabase(), '" + localBare + "')";

        logStatements(createLocal, insertLocal, createDist);
        try {
            if (!new SQLQueryAdapter(createLocal, errors, true).execute(state)) {
                throw new IgnoreMeException();
            }
            if (!new SQLQueryAdapter(insertLocal, errors, true).execute(state)) {
                throw new IgnoreMeException();
            }
            if (!new SQLQueryAdapter(createDist, errors, true).execute(state)) {
                throw new IgnoreMeException();
            }

            checkReadEquivalence(local, dist);
            checkAggregationEquivalence(local, dist);
            checkInsertRouting(local, dist);
        } finally {
            dropQuietly(dist);
            dropQuietly(local);
        }
    }

    private void checkReadEquivalence(String local, String dist) throws SQLException {
        String localSql = "SELECT toString(tuple(*)) FROM " + local;
        String distSql = "SELECT toString(tuple(*)) FROM " + dist;
        List<String> localRows = read(localSql);
        List<String> distRows = read(distSql);
        assertMultisetsEqual(localRows, distRows, localSql, distSql, "distributed read");
    }

    private void checkAggregationEquivalence(String local, String dist) throws SQLException {
        String proj = "SELECT toString(tuple(g, count(), sum(v), min(v), max(v))) FROM ";
        String localSql = proj + local + " GROUP BY g ORDER BY g";
        String distSql = proj + dist + " GROUP BY g ORDER BY g";
        List<String> localRows = read(localSql);
        List<String> distRows = read(distSql);
        assertMultisetsEqual(localRows, distRows, localSql, distSql, "distributed aggregation");
    }

    private void checkInsertRouting(String local, String dist) throws SQLException {
        List<String> before = read("SELECT toString(count()) FROM " + local);
        if (before.size() != 1) {
            throw new IgnoreMeException();
        }
        long countBefore;
        try {
            countBefore = Long.parseLong(before.get(0));
        } catch (NumberFormatException e) {
            throw new IgnoreMeException();
        }

        int added = 1 + (int) Randomly.getNotCachedInteger(0, 4);
        StringBuilder values = new StringBuilder();
        for (int i = 0; i < added; i++) {
            if (i > 0) {
                values.append(", ");
            }
            long uid = 1_000_000_000L + i;
            values.append("(").append(uid).append(", 0, ").append(i).append(")");
        }
        String insertDist = "INSERT INTO " + dist + " (id, g, v) SETTINGS distributed_foreground_insert = 1 VALUES "
                + values;
        logStatements(insertDist);
        if (!new SQLQueryAdapter(insertDist, errors, true).execute(state)) {
            return;
        }
        try {
            new SQLQueryAdapter("SYSTEM FLUSH DISTRIBUTED " + dist, errors, true).execute(state);
        } catch (SQLException ignored) {
        }

        List<String> after = read("SELECT toString(count()) FROM " + local);
        if (after.size() != 1) {
            throw new IgnoreMeException();
        }
        long countAfter;
        try {
            countAfter = Long.parseLong(after.get(0));
        } catch (NumberFormatException e) {
            throw new IgnoreMeException();
        }
        if (countAfter != countBefore + added) {
            throw new AssertionError(String.format(
                    "distributed INSERT routing mismatch: inserted %d rows into %s, local count went %d -> %d "
                            + "(expected %d)",
                    added, dist, countBefore, countAfter, countBefore + added));
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
                "%s mismatch: %d local rows vs %d distributed rows.%nlocal: %s%ndistributed: %s%nfirst %d differing: %s",
                label, expected.size(), actual.size(), expectedSql, actualSql, diff.size(), diff));
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
            diff.add(e.getKey() + " (+" + Math.abs(c) + " " + (c > 0 ? "local" : "distributed") + ")");
        }
        return diff;
    }
}
