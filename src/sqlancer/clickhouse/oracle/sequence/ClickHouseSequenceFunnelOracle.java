package sqlancer.clickhouse.oracle.sequence;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import sqlancer.ComparatorHelper;
import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.clickhouse.ClickHouseErrors;
import sqlancer.clickhouse.ClickHouseProvider.ClickHouseGlobalState;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;

public class ClickHouseSequenceFunnelOracle implements TestOracle<ClickHouseGlobalState> {

    private static final AtomicLong CTR = new AtomicLong();

    private static final int WINDOW = 1000;

    private static final int SMALL_GAP = 5;

    private static final int HUGE_GAP = 100000;

    private final ClickHouseGlobalState state;

    private final ExpectedErrors createErrors = new ExpectedErrors();
    private final ExpectedErrors readErrors = new ExpectedErrors();

    public ClickHouseSequenceFunnelOracle(ClickHouseGlobalState state) {
        this.state = state;
        for (ExpectedErrors e : List.of(createErrors, readErrors)) {
            ClickHouseErrors.addSessionSettingsErrors(e);

            e.add("UNKNOWN_STORAGE");
            e.add("Unknown table engine");
            e.add("UNKNOWN_FUNCTION");
            e.add("Unknown function");
            e.add("SUPPORT_IS_DISABLED");
            e.add("NOT_IMPLEMENTED");
            e.add("ILLEGAL_TYPE_OF_ARGUMENT");
            e.add("Unknown setting");
            e.add("BAD_ARGUMENTS");
            e.add("experimental");
            e.add("allow_experimental");
            e.add("SYNTAX_ERROR");
            e.add("NUMBER_OF_ARGUMENTS_DOESNT_MATCH");
            e.add("Aggregate function");

            e.add("UNKNOWN_TABLE");
            e.add("Unknown table expression identifier");

            e.add("(MEMORY_LIMIT_EXCEEDED)");
            e.add("memory limit exceeded");

            e.add("TIMEOUT_EXCEEDED");
            e.add("Timeout exceeded");
        }
    }

    static final class Event {
        final long ts;
        final int ev;

        Event(long ts, int ev) {
            this.ts = ts;
            this.ev = ev;
        }
    }

    @Override
    public void check() throws SQLException {
        if (!state.getClickHouseOptions().sequenceFunnelOracle) {
            throw new IgnoreMeException();
        }
        long id = CTR.incrementAndGet();
        String table = state.getDatabaseName() + ".seq_" + id;
        Randomly r = state.getRandomly();

        List<List<Event>> perUid = buildFixture(r);
        String create = "CREATE TABLE " + table
                + " (uid UInt32, ts UInt32, ev UInt8) ENGINE = MergeTree ORDER BY (uid, ts)";

        try {
            logStmt(create);
            if (!new SQLQueryAdapter(create, createErrors, true).execute(state)) {
                throw new IgnoreMeException();
            }

            StringBuilder insert = new StringBuilder("INSERT INTO ").append(table).append(" (uid, ts, ev) VALUES ");
            boolean firstRow = true;
            for (int uid = 0; uid < perUid.size(); uid++) {
                for (Event e : perUid.get(uid)) {
                    if (!firstRow) {
                        insert.append(", ");
                    }
                    firstRow = false;
                    insert.append('(').append(uid).append(", ").append(e.ts).append(", ").append(e.ev).append(')');
                }
            }
            if (firstRow) {
                throw new IgnoreMeException();
            }
            logStmt(insert.toString());
            if (!new SQLQueryAdapter(insert.toString(), readErrors, true).execute(state)) {
                throw new IgnoreMeException();
            }

            checkWindowFunnel(table, perUid, create);
            checkSequenceCount(table, perUid, create);
            checkSequenceMatch(table, perUid, create);
            checkRetention(table, perUid, create);
        } finally {
            dropQuietly(table);
        }
    }

    private static List<List<Event>> buildFixture(Randomly r) {
        List<List<Event>> perUid = new ArrayList<>();

        List<Event> u0 = new ArrayList<>();
        u0.add(new Event(10, 1));
        u0.add(new Event(10 + SMALL_GAP, 2));
        u0.add(new Event(10 + 2 * SMALL_GAP, 3));
        perUid.add(u0);

        List<Event> u1 = new ArrayList<>();
        u1.add(new Event(20, 1));
        u1.add(new Event(20 + SMALL_GAP, 2));
        u1.add(new Event(20 + SMALL_GAP + HUGE_GAP, 3));
        perUid.add(u1);

        List<Event> u2 = new ArrayList<>();
        u2.add(new Event(30, 1));
        u2.add(new Event(30 + SMALL_GAP, 3));
        u2.add(new Event(30 + 2 * SMALL_GAP, 2));
        perUid.add(u2);

        List<Event> u3 = new ArrayList<>();
        u3.add(new Event(40, 2));
        u3.add(new Event(40 + SMALL_GAP, 3));
        perUid.add(u3);

        List<Event> u4 = new ArrayList<>();
        u4.add(new Event(50, 1));
        u4.add(new Event(50 + SMALL_GAP, 2));
        u4.add(new Event(50 + 2 * SMALL_GAP, 1));
        u4.add(new Event(50 + 3 * SMALL_GAP, 2));
        u4.add(new Event(50 + 4 * SMALL_GAP, 3));
        perUid.add(u4);

        if (Randomly.getBoolean()) {
            List<Event> u5 = new ArrayList<>();
            u5.add(new Event(60, 1));
            perUid.add(u5);
        }
        if (Randomly.getBoolean()) {
            List<Event> u6 = new ArrayList<>();
            u6.add(new Event(70, 1));
            u6.add(new Event(70 + SMALL_GAP, 1));
            u6.add(new Event(70 + 2 * SMALL_GAP, 2));
            u6.add(new Event(70 + 3 * SMALL_GAP, 2));
            perUid.add(u6);
        }
        return perUid;
    }

    static int windowFunnel(List<Event> events, int steps, int window) {
        long[] reached = new long[steps];
        boolean[] set = new boolean[steps];
        for (Event e : events) {
            for (int level = steps - 1; level >= 0; level--) {
                int cond = level + 1;
                if (e.ev != cond) {
                    continue;
                }
                if (level == 0) {
                    set[0] = true;
                    reached[0] = e.ts;
                } else if (set[level - 1] && e.ts <= reached[0] + window) {
                    set[level] = true;
                    reached[level] = e.ts;
                }
            }
        }
        int best = 0;
        for (int level = 0; level < steps; level++) {
            if (set[level]) {
                best = level + 1;
            } else {
                break;
            }
        }
        return best;
    }

    static long sequenceCount(List<Event> events, int first, int second) {
        long count = 0;
        boolean armed = false;
        for (Event e : events) {
            if (!armed) {
                if (e.ev == first) {
                    armed = true;
                }
            } else if (e.ev == second) {
                count++;
                armed = false;
            }
        }
        return count;
    }

    static boolean sequenceMatch(List<Event> events, int first, int second) {
        return sequenceCount(events, first, second) > 0;
    }

    static int[] retention(List<Event> events, int[] conds) {
        boolean[] anyMatch = new boolean[conds.length];
        for (Event e : events) {
            for (int i = 0; i < conds.length; i++) {
                if (e.ev == conds[i]) {
                    anyMatch[i] = true;
                }
            }
        }
        int[] out = new int[conds.length];
        out[0] = anyMatch[0] ? 1 : 0;
        for (int i = 1; i < conds.length; i++) {
            out[i] = anyMatch[0] && anyMatch[i] ? 1 : 0;
        }
        return out;
    }

    private void checkWindowFunnel(String table, List<List<Event>> perUid, String create) throws SQLException {
        for (int steps = 1; steps <= 3; steps++) {
            String predicates = funnelPredicates(steps);
            String query = "SELECT toString(windowFunnel(" + WINDOW + ")(ts, " + predicates + ")) FROM " + table
                    + " GROUP BY uid ORDER BY uid";
            List<String> observed = readColumn(query);
            List<Integer> expected = new ArrayList<>();
            for (List<Event> events : perUid) {
                expected.add(windowFunnel(events, steps, WINDOW));
            }
            assertPerUid("windowFunnel(" + WINDOW + ")(" + steps + " steps)", expected, observed, query, create);
        }

        String q1 = "SELECT toString(windowFunnel(" + WINDOW + ")(ts, " + funnelPredicates(1) + ")) FROM " + table
                + " GROUP BY uid ORDER BY uid";
        String q2 = "SELECT toString(windowFunnel(" + WINDOW + ")(ts, " + funnelPredicates(2) + ")) FROM " + table
                + " GROUP BY uid ORDER BY uid";
        String q3 = "SELECT toString(windowFunnel(" + WINDOW + ")(ts, " + funnelPredicates(3) + ")) FROM " + table
                + " GROUP BY uid ORDER BY uid";
        List<String> l1 = readColumn(q1);
        List<String> l2 = readColumn(q2);
        List<String> l3 = readColumn(q3);
        if (l1.size() != l2.size() || l2.size() != l3.size()) {
            throw new IgnoreMeException();
        }
        for (int i = 0; i < l1.size(); i++) {
            long v1 = Long.parseLong(l1.get(i));
            long v2 = Long.parseLong(l2.get(i));
            long v3 = Long.parseLong(l3.get(i));
            if (!(v3 <= v2 && v2 <= v1)) {
                throw new AssertionError(String.format(
                        "windowFunnel monotonicity violated for uid %d: 1-step=%d, 2-step=%d, 3-step=%d "
                                + "(expected 3 <= 2 <= 1). DDL: %s",
                        i, v1, v2, v3, create));
            }
        }
    }

    private static String funnelPredicates(int steps) {
        List<String> parts = new ArrayList<>();
        for (int i = 1; i <= steps; i++) {
            parts.add("ev = " + i);
        }
        return String.join(", ", parts);
    }

    private void checkSequenceCount(String table, List<List<Event>> perUid, String create) throws SQLException {
        String query = "SELECT toString(sequenceCount('(?1)(?2)')(toDateTime(ts), ev = 1, ev = 2)) FROM " + table
                + " GROUP BY uid ORDER BY uid";
        List<String> observed = readColumn(query);
        List<Integer> expected = new ArrayList<>();
        for (List<Event> events : perUid) {
            expected.add((int) sequenceCount(events, 1, 2));
        }
        assertPerUid("sequenceCount('(?1)(?2)')(ev=1,ev=2)", expected, observed, query, create);
    }

    private void checkSequenceMatch(String table, List<List<Event>> perUid, String create) throws SQLException {
        String query = "SELECT toString(sequenceMatch('(?1)(?2)')(toDateTime(ts), ev = 1, ev = 2)) FROM " + table
                + " GROUP BY uid ORDER BY uid";
        List<String> observed = readColumn(query);
        List<Integer> expected = new ArrayList<>();
        for (List<Event> events : perUid) {
            expected.add(sequenceMatch(events, 1, 2) ? 1 : 0);
        }
        assertPerUid("sequenceMatch('(?1)(?2)')(ev=1,ev=2)", expected, observed, query, create);
    }

    private void checkRetention(String table, List<List<Event>> perUid, String create) throws SQLException {
        int[] conds = { 1, 2, 3 };
        String query = "SELECT toString(retention(ev = 1, ev = 2, ev = 3)) FROM " + table
                + " GROUP BY uid ORDER BY uid";
        List<String> observed = readColumn(query);
        if (observed.size() != perUid.size()) {
            throw new IgnoreMeException();
        }
        for (int uid = 0; uid < perUid.size(); uid++) {
            int[] expected = retention(perUid.get(uid), conds);
            String expectedStr = renderIntArray(expected);
            if (!expectedStr.equals(observed.get(uid))) {
                throw new AssertionError(String.format(
                        "retention(ev=1,ev=2,ev=3) ground-truth mismatch for uid %d: Java expects %s but ClickHouse "
                                + "returned %s. Query: %s. DDL: %s",
                        uid, expectedStr, observed.get(uid), query, create));
            }
            int head = expected[0];
            for (int i = 1; i < expected.length; i++) {
                if (head < expected[i]) {
                    throw new AssertionError(String.format(
                            "retention monotonicity violated for uid %d: retention[0]=%d < retention[%d]=%d. DDL: %s",
                            uid, head, i, expected[i], create));
                }
            }
        }
    }

    private void assertPerUid(String label, List<Integer> expected, List<String> observed, String query,
            String create) {
        if (expected.size() != observed.size()) {
            throw new IgnoreMeException();
        }
        for (int i = 0; i < expected.size(); i++) {
            if (!String.valueOf(expected.get(i)).equals(observed.get(i))) {
                throw new AssertionError(String.format(
                        "%s ground-truth mismatch for uid %d: Java expects %d but ClickHouse returned %s. "
                                + "Query: %s. DDL: %s",
                        label, i, expected.get(i), observed.get(i), query, create));
            }
        }
    }

    private static String renderIntArray(int[] values) {
        List<String> parts = new ArrayList<>();
        for (int v : values) {
            parts.add(String.valueOf(v));
        }
        return "[" + parts.stream().collect(Collectors.joining(",")) + "]";
    }

    private List<String> readColumn(String query) throws SQLException {
        return ComparatorHelper.getResultSetFirstColumnAsString(query, readErrors, state);
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
