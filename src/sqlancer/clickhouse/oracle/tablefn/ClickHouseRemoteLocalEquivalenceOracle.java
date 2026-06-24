package sqlancer.clickhouse.oracle.tablefn;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import sqlancer.ComparatorHelper;
import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.clickhouse.ClickHouseErrors;
import sqlancer.clickhouse.ClickHouseProvider.ClickHouseGlobalState;
import sqlancer.clickhouse.ClickHouseSchema.ClickHouseTable;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.ExpectedErrors;

public class ClickHouseRemoteLocalEquivalenceOracle implements TestOracle<ClickHouseGlobalState> {

    private static final int DIFF_LIMIT = 20;
    private static final int MAX_NUMBERS = 50;

    private final ClickHouseGlobalState state;
    private final ExpectedErrors readErrors = new ExpectedErrors();

    public ClickHouseRemoteLocalEquivalenceOracle(ClickHouseGlobalState state) {
        this.state = state;
        ClickHouseErrors.addExpectedExpressionErrors(readErrors);
        ClickHouseErrors.addSessionSettingsErrors(readErrors);

        readErrors.add("UNKNOWN_TABLE");
        readErrors.add("Unknown table expression identifier");
        readErrors.add("UNKNOWN_STORAGE");
        readErrors.add("Unknown table engine");
        readErrors.add("SUPPORT_IS_DISABLED");
        readErrors.add("NOT_IMPLEMENTED");
        readErrors.add("experimental");

        readErrors.add("(MEMORY_LIMIT_EXCEEDED)");
        readErrors.add("memory limit exceeded");
        readErrors.add("TIMEOUT_EXCEEDED");
        readErrors.add("Timeout exceeded");

        readErrors.add("Limit for result exceeded");
        readErrors.add("TOO_MANY_ROWS_OR_BYTES");

        readErrors.add("Authentication failed");
        readErrors.add("AUTHENTICATION_FAILED");
        readErrors.add("password is incorrect");
        readErrors.add("Connection refused");
        readErrors.add("Connection reset");
        readErrors.add("NETWORK_ERROR");
        readErrors.add("Timeout: connect timed out");
        readErrors.add("All connection tries failed");
        readErrors.add("Cannot resolve host");
        readErrors.add("Requested cluster");
        readErrors.add("CLUSTER_DOESNT_EXIST");
        readErrors.add("There is no Cluster");
        readErrors.add("ACCESS_DENIED");
        readErrors.add("Not enough privileges");
    }

    @Override
    public void check() throws SQLException {
        if (!state.getClickHouseOptions().remoteLocalEquivalenceOracle) {
            throw new IgnoreMeException();
        }

        boolean checkedAnything = false;
        if (Randomly.getBoolean()) {
            checkedAnything |= checkRemoteEquivalence();
        } else {
            checkNumbersGroundTruth();
            checkedAnything = true;
        }

        if (!checkedAnything) {
            checkNumbersGroundTruth();
        }
    }

    private boolean checkRemoteEquivalence() throws SQLException {
        List<ClickHouseTable> tables = new ArrayList<>(state.getSchema().getDatabaseTablesWithoutViews());
        if (tables.isEmpty()) {
            return false;
        }
        ClickHouseTable table = tables.get((int) Randomly.getNotCachedInteger(0, tables.size()));
        String tableName = table.getName();
        String qualified = state.getDatabaseName() + "." + tableName;

        List<String> localRows = ComparatorHelper
                .getResultSetFirstColumnAsString("SELECT toString(count()) FROM " + qualified, readErrors, state);
        if (localRows.size() != 1 || "0".equals(localRows.get(0))) {
            throw new IgnoreMeException();
        }

        String local = "SELECT toString(tuple(*)) FROM " + qualified;
        String remote = "SELECT toString(tuple(*)) FROM remote('127.0.0.1', currentDatabase(), '"
                + esc(tableName) + "')";

        List<String> localResult = ComparatorHelper.getResultSetFirstColumnAsString(local, readErrors, state);
        List<String> remoteResult = ComparatorHelper.getResultSetFirstColumnAsString(remote, readErrors, state);
        assertMultisetsEqual(localResult, remoteResult, local, remote, "remote");

        if (Randomly.getBoolean()) {
            String cluster = "SELECT toString(tuple(*)) FROM cluster('default', currentDatabase(), '"
                    + esc(tableName) + "')";
            List<String> clusterResult = ComparatorHelper.getResultSetFirstColumnAsString(cluster, readErrors, state);
            assertMultisetsEqual(localResult, clusterResult, local, cluster, "cluster");
        }
        return true;
    }

    private void checkNumbersGroundTruth() throws SQLException {
        int n = 1 + (int) Randomly.getNotCachedInteger(0, MAX_NUMBERS);

        String countQuery = "SELECT toString(count()) FROM numbers(" + n + ")";
        List<String> countRows = ComparatorHelper.getResultSetFirstColumnAsString(countQuery, readErrors, state);
        if (countRows.size() != 1) {
            throw new IgnoreMeException();
        }
        if (!String.valueOf(n).equals(countRows.get(0))) {
            throw new AssertionError(String.format(
                    "numbers() count ground-truth mismatch: %s expected %d but got %s", countQuery, n,
                    countRows.get(0)));
        }

        String setQuery = "SELECT toString(arraySort(groupArray(number))) FROM numbers(" + n + ")";
        List<String> setRows = ComparatorHelper.getResultSetFirstColumnAsString(setQuery, readErrors, state);
        if (setRows.size() != 1) {
            throw new IgnoreMeException();
        }
        String expected = buildSortedRangeText(n);
        if (!expected.equals(setRows.get(0))) {
            throw new AssertionError(String.format(
                    "numbers() value-set ground-truth mismatch: %s expected %s but got %s", setQuery, expected,
                    setRows.get(0)));
        }
    }

    private static String buildSortedRangeText(int n) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(i);
        }
        return sb.append(']').toString();
    }

    private static void assertMultisetsEqual(List<String> localRows, List<String> otherRows, String localSql,
            String otherSql, String label) {
        List<String> diff = multisetDiff(localRows, otherRows, DIFF_LIMIT);
        if (diff.isEmpty()) {
            return;
        }
        throw new AssertionError(String.format(
                "%s-vs-local multiset mismatch: %d local rows vs %d %s rows.%nlocal: %s%n%s: %s%n"
                        + "first %d differing entries (value (+count side)): %s",
                label, localRows.size(), otherRows.size(), label, localSql, label, otherSql, diff.size(), diff));
    }

    private static List<String> multisetDiff(List<String> localRows, List<String> otherRows, int limit) {
        Map<String, Long> counts = new TreeMap<>();
        for (String s : localRows) {
            counts.merge(s == null ? "\\N" : s, 1L, Long::sum);
        }
        for (String s : otherRows) {
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
            diff.add(e.getKey() + " (+" + Math.abs(c) + " " + (c > 0 ? "local" : "other") + ")");
        }
        return diff;
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("'", "\\'");
    }
}
