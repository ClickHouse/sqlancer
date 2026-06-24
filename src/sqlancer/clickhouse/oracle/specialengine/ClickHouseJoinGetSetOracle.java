package sqlancer.clickhouse.oracle.specialengine;

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

public class ClickHouseJoinGetSetOracle implements TestOracle<ClickHouseGlobalState> {

    private static final AtomicLong CTR = new AtomicLong();
    private static final int DIFF_LIMIT = 20;
    private static final int PROBE_SPACE = 20;

    enum Mode {
        SET_MEMBERSHIP,
        JOIN_GET
    }

    private final ClickHouseGlobalState state;

    private final ExpectedErrors ddlErrors = new ExpectedErrors();
    private final ExpectedErrors readErrors = new ExpectedErrors();

    public ClickHouseJoinGetSetOracle(ClickHouseGlobalState state) {
        this.state = state;
        for (ExpectedErrors e : List.of(ddlErrors, readErrors)) {
            ClickHouseErrors.addSessionSettingsErrors(e);

            e.add("UNKNOWN_TABLE");
            e.add("Unknown table expression identifier");

            e.add("UNKNOWN_STORAGE");
            e.add("Unknown table engine");
            e.add("SUPPORT_IS_DISABLED");
            e.add("NOT_IMPLEMENTED");
            e.add("experimental");
            e.add("Method joinGet");

            e.add("(MEMORY_LIMIT_EXCEEDED)");
            e.add("memory limit exceeded");

            e.add("TIMEOUT_EXCEEDED");
            e.add("Timeout exceeded");

            e.add("Limit for result exceeded");
            e.add("TOO_MANY_ROWS_OR_BYTES");
        }
    }

    @Override
    public void check() throws SQLException {
        if (!state.getClickHouseOptions().joinGetSetOracle) {
            throw new IgnoreMeException();
        }
        long id = CTR.incrementAndGet();
        Mode mode = Randomly.fromOptions(Mode.values());
        if (mode == Mode.SET_MEMBERSHIP) {
            checkSetMembership(id);
        } else {
            checkJoinGet(id);
        }
    }

    private void checkSetMembership(long id) throws SQLException {
        String src = state.getDatabaseName() + ".jgs_src_" + id;
        String setTable = state.getDatabaseName() + ".jgs_set_" + id;
        Randomly r = state.getRandomly();
        try {
            if (!execute("CREATE TABLE " + src + " (k Int32, v Int64) ENGINE = MergeTree ORDER BY tuple()", ddlErrors)) {
                throw new IgnoreMeException();
            }
            String values = renderSourceValues(r, PROBE_SPACE);
            if (!execute("INSERT INTO " + src + " (k, v) VALUES " + values, readErrors)) {
                throw new IgnoreMeException();
            }
            if (!execute("CREATE TABLE " + setTable + " (k Int32) ENGINE = Set", ddlErrors)) {
                throw new IgnoreMeException();
            }
            if (!execute("INSERT INTO " + setTable + " SELECT k FROM " + src, readErrors)) {
                throw new IgnoreMeException();
            }

            String viaSet = readSingleValue("SELECT toString(arraySort(groupArray(x))) FROM (SELECT number AS x FROM numbers("
                    + PROBE_SPACE + ") WHERE x IN " + setTable + ")");
            String viaSubquery = readSingleValue(
                    "SELECT toString(arraySort(groupArray(x))) FROM (SELECT number AS x FROM numbers(" + PROBE_SPACE
                            + ") WHERE x IN (SELECT k FROM " + src + "))");

            if (!viaSet.equals(viaSubquery)) {
                throw new AssertionError(String.format(
                        "Set-engine membership mismatch: x IN %s gave %s but x IN (SELECT k FROM %s) gave %s. "
                                + "Source values: %s",
                        setTable, viaSet, src, viaSubquery, values));
            }
        } finally {
            dropQuietly(setTable);
            dropQuietly(src);
        }
    }

    private void checkJoinGet(long id) throws SQLException {
        String join = state.getDatabaseName() + ".jgs_join_" + id;
        Randomly r = state.getRandomly();
        try {
            if (!execute("CREATE TABLE " + join + " (k Int32, v Int64) ENGINE = Join(ANY, LEFT, k)", ddlErrors)) {
                throw new IgnoreMeException();
            }
            Map<Integer, Long> present = new TreeMap<>();
            int rows = 5 + (int) r.getInteger(0, 11);
            StringBuilder sb = new StringBuilder("INSERT INTO " + join + " (k, v) VALUES ");
            int emitted = 0;
            while (present.size() < rows && emitted < rows * 4) {
                emitted++;
                int k = (int) r.getInteger(0, PROBE_SPACE);
                if (present.containsKey(k)) {
                    continue;
                }
                long v = r.getInteger(-1000, 1000);
                present.put(k, v);
                if (present.size() > 1) {
                    sb.append(", ");
                }
                sb.append('(').append(k).append(", ").append(v).append(')');
            }
            if (present.isEmpty()) {
                throw new IgnoreMeException();
            }
            if (!execute(sb.toString(), readErrors)) {
                throw new IgnoreMeException();
            }

            List<Integer> probeKeys = new ArrayList<>(present.keySet());

            List<String> viaJoinGet = new ArrayList<>(probeKeys.size());
            List<String> viaLookup = new ArrayList<>(probeKeys.size());
            for (int k : probeKeys) {
                viaJoinGet.add(readSingleValue(
                        "SELECT toString(joinGet('" + join + "', 'v', toInt32(" + k + ")))"));
                viaLookup.add(readSingleValue(
                        "SELECT toString(any(v)) FROM " + join + " WHERE k = toInt32(" + k + ")"));
            }

            List<String> diff = orderedDiff(viaJoinGet, viaLookup, probeKeys, DIFF_LIMIT);
            if (!diff.isEmpty()) {
                throw new AssertionError(String.format(
                        "joinGet vs ANY LEFT JOIN lookup mismatch on %s: probe keys %s, joinGet %s, lookup %s. "
                                + "first differing entries: %s",
                        join, probeKeys, viaJoinGet, viaLookup, diff));
            }
        } finally {
            dropQuietly(join);
        }
    }

    static String renderSourceValues(Randomly r, int space) {
        int rows = 8 + (int) r.getInteger(0, 13);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rows; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            int k = (int) r.getInteger(0, space);
            long v = r.getInteger(-1000, 1000);
            sb.append('(').append(k).append(", ").append(v).append(')');
        }
        return sb.toString();
    }

    static List<String> orderedDiff(List<String> left, List<String> right, List<Integer> keys, int limit) {
        List<String> diff = new ArrayList<>();
        for (int i = 0; i < left.size() && diff.size() < limit; i++) {
            String a = left.get(i);
            String b = right.get(i);
            boolean equal = a == null ? b == null : a.equals(b);
            if (!equal) {
                diff.add("k=" + keys.get(i) + " joinGet=" + (a == null ? "\\N" : a) + " lookup="
                        + (b == null ? "\\N" : b));
            }
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
