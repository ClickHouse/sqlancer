package sqlancer.clickhouse.oracle.join;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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

public class ClickHouseJoinReorderOracle implements TestOracle<ClickHouseGlobalState> {

    private static final AtomicLong JREORD_COUNTER = new AtomicLong();
    private static final int DIFF_LIMIT = 20;

    static final String ARM_REORDER_ON = "SETTINGS query_plan_optimize_join_order_limit = 10";
    static final String ARM_REORDER_OFF = "SETTINGS query_plan_optimize_join_order_limit = 0";
    static final String ARM_REORDER_RANDOMIZE = "SETTINGS query_plan_optimize_join_order_limit = 10, "
            + "query_plan_optimize_join_order_randomize = 1";

    enum JoinKind {
        INNER("INNER JOIN"), LEFT("LEFT JOIN"), FULL("FULL JOIN"), LEFT_SEMI("LEFT SEMI JOIN"),
        LEFT_ANTI("LEFT ANTI JOIN"), RIGHT_SEMI("RIGHT SEMI JOIN"), RIGHT_ANTI("RIGHT ANTI JOIN");

        private final String sql;

        JoinKind(String sql) {
            this.sql = sql;
        }

        String getSql() {
            return sql;
        }

        boolean isSemiOrAnti() {
            return this == LEFT_SEMI || this == LEFT_ANTI || this == RIGHT_SEMI || this == RIGHT_ANTI;
        }

        boolean isAnti() {
            return this == LEFT_ANTI || this == RIGHT_ANTI;
        }

        boolean isSemi() {
            return this == LEFT_SEMI || this == RIGHT_SEMI;
        }
    }

    static List<Integer> liveAliasesBeforeJoin(List<JoinKind> precedingKinds) {
        TreeSet<Integer> live = new TreeSet<>();
        live.add(0);
        for (int j = 0; j < precedingKinds.size(); j++) {
            switch (precedingKinds.get(j)) {
            case LEFT_SEMI:
            case LEFT_ANTI:

                break;
            case RIGHT_SEMI:
            case RIGHT_ANTI:
                live.clear();
                live.add(j + 1);
                break;
            default:
                live.add(j + 1);
                break;
            }
        }
        return new ArrayList<>(live);
    }

    static boolean referencesDroppedAlias(List<JoinKind> kinds, List<Integer> onLeft) {
        for (int i = 0; i < onLeft.size(); i++) {
            if (!liveAliasesBeforeJoin(kinds.subList(0, i)).contains(onLeft.get(i))) {
                return true;
            }
        }
        return false;
    }

    private final ClickHouseGlobalState state;

    private final ExpectedErrors readErrors = new ExpectedErrors();
    private final ExpectedErrors selectErrors = new ExpectedErrors();
    private final ExpectedErrors statsErrors = new ExpectedErrors();

    public ClickHouseJoinReorderOracle(ClickHouseGlobalState state) {
        this.state = state;

        for (ExpectedErrors e : List.of(readErrors, selectErrors, statsErrors)) {

            ClickHouseErrors.addSessionSettingsErrors(e);

            e.add("UNKNOWN_TABLE");
            e.add("Unknown table expression identifier");

            e.add("(MEMORY_LIMIT_EXCEEDED)");
            e.add("memory limit exceeded");

            e.add("TIMEOUT_EXCEEDED");
            e.add("Timeout exceeded");
        }

        selectErrors.add("Join restriction violated");

        ClickHouseErrors.addStatisticsErrors(statsErrors);
        statsErrors.add("already contains statistics");

        statsErrors.add("Exception happened during execution of mutation");
        statsErrors.add("UNFINISHED");
        statsErrors.add("contains a duplicate expression");
    }

    @Override
    public void check() throws SQLException {
        long id = JREORD_COUNTER.incrementAndGet();
        String db = state.getDatabaseName();

        int numJoins = Randomly.getBoolean() ? 2 : 3;
        int numTables = numJoins + 1;
        List<String> tables = new ArrayList<>();
        for (int i = 0; i < numTables; i++) {
            tables.add(db + ".jreord_" + id + "_t" + i);
        }

        boolean[] nullableKey = new boolean[numTables];
        boolean anyNullable = false;
        for (int i = 0; i < numTables; i++) {
            nullableKey[i] = Randomly.getBoolean();
            anyNullable |= nullableKey[i];
        }
        if (!anyNullable) {
            nullableKey[1] = true;
        }

        try {
            for (int i = 0; i < numTables; i++) {
                String keyType = nullableKey[i] ? "Nullable(Int32)" : "Int32";
                String create = "CREATE TABLE " + tables.get(i) + " (k " + keyType
                        + ", v Int32, s String) ENGINE = MergeTree ORDER BY tuple()";
                logStmt(create);
                if (!new SQLQueryAdapter(create, readErrors, true).execute(state)) {
                    throw new IgnoreMeException();
                }
            }
            seedTables(tables, nullableKey);

            if (Randomly.getNotCachedInteger(0, 100) < 25) {
                materializeStatsBestEffort(tables.get(0));
            }

            List<JoinKind> kinds = new ArrayList<>();
            for (int i = 0; i < numJoins; i++) {
                kinds.add(Randomly.fromOptions(JoinKind.values()));
            }
            boolean allowDroppedKeyRef = state.getClickHouseOptions().joinReorderAllowDroppedKeyRef;
            List<Integer> onLeft = new ArrayList<>();
            for (int i = 0; i < numJoins; i++) {

                List<Integer> candidates = allowDroppedKeyRef
                        ? java.util.stream.IntStream.rangeClosed(0, i).boxed().collect(java.util.stream.Collectors.toList())
                        : liveAliasesBeforeJoin(kinds.subList(0, i));
                onLeft.add(Randomly.fromList(candidates));
            }
            List<Integer> det = deterministicTables(kinds);
            String where = null;
            if (det.size() >= 2 && Randomly.getNotCachedInteger(0, 100) < 30) {

                int aIdx = det.get((int) Randomly.getNotCachedInteger(0, det.size()));
                int bIdx = aIdx;
                while (bIdx == aIdx) {
                    bIdx = det.get((int) Randomly.getNotCachedInteger(0, det.size()));
                }
                where = "a" + aIdx + ".v " + Randomly.fromOptions("<", "<=", "!=") + " a" + bIdx + ".v";
            }

            String qOn = renderQuery(kinds, tables, onLeft, where, ARM_REORDER_ON);
            String qOff = renderQuery(kinds, tables, onLeft, where, ARM_REORDER_OFF);
            String qRandomized = renderQuery(kinds, tables, onLeft, where, ARM_REORDER_RANDOMIZE);

            logStmt(qOn);
            List<String> rowsOn = ComparatorHelper.getResultSetFirstColumnAsString(qOn, selectErrors, state);
            logStmt(qOff);
            List<String> rowsOff = ComparatorHelper.getResultSetFirstColumnAsString(qOff, selectErrors, state);
            logStmt(qRandomized);
            List<String> rowsRandomized = ComparatorHelper.getResultSetFirstColumnAsString(qRandomized, selectErrors,
                    state);

            assertMultisetsEqual(kinds, qOn, rowsOn, qOff, rowsOff);
            assertMultisetsEqual(kinds, qOn, rowsOn, qRandomized, rowsRandomized);
            assertMultisetsEqual(kinds, qOff, rowsOff, qRandomized, rowsRandomized);
        } finally {
            for (String t : tables) {
                try {
                    new SQLQueryAdapter("DROP TABLE IF EXISTS " + t, readErrors, true).execute(state);
                } catch (Exception | AssertionError ignored) {

                }
            }
        }
    }

    private void seedTables(List<String> tables, boolean[] nullableKey) throws SQLException {

        long bigRows = 200 + Randomly.getNotCachedInteger(0, 201);
        String keyExpr = nullableKey[0] ? "if(number % 11 = 0, NULL, toInt32(number % 10))" : "toInt32(number % 10)";
        String seedBig = "INSERT INTO " + tables.get(0) + " SELECT " + keyExpr
                + ", toInt32(number % 17), toString(number % 10) FROM numbers(" + bigRows + ")";
        logStmt(seedBig);
        if (!new SQLQueryAdapter(seedBig, readErrors, true).execute(state)) {
            throw new IgnoreMeException();
        }

        for (int i = 1; i < tables.size(); i++) {
            if (Randomly.getNotCachedInteger(0, 100) < 10) {
                continue;
            }
            int rows = 1 + (int) Randomly.getNotCachedInteger(0, 5);
            StringBuilder values = new StringBuilder();
            for (int r = 0; r < rows; r++) {
                if (r > 0) {
                    values.append(", ");
                }
                String k;
                if (nullableKey[i] && Randomly.getNotCachedInteger(0, 100) < 25) {
                    k = "NULL";
                } else if (Randomly.getNotCachedInteger(0, 100) < 10) {
                    k = Long.toString(40 + Randomly.getNotCachedInteger(0, 10));
                } else {
                    k = Long.toString(Randomly.getNotCachedInteger(0, 10));
                }
                values.append("(").append(k).append(", ").append(Randomly.getNotCachedInteger(0, 21)).append(", 'r")
                        .append(Randomly.getNotCachedInteger(0, 10)).append("')");
            }
            String seed = "INSERT INTO " + tables.get(i) + " VALUES " + values;
            logStmt(seed);
            if (!new SQLQueryAdapter(seed, readErrors, true).execute(state)) {
                throw new IgnoreMeException();
            }
        }
    }

    private void materializeStatsBestEffort(String table) throws SQLException {

        String add = "ALTER TABLE " + table + " ADD STATISTICS IF NOT EXISTS v TYPE minmax";
        logStmt(add);
        if (!new SQLQueryAdapter(add, statsErrors, false).execute(state)) {
            return;
        }
        String materialize = "ALTER TABLE " + table + " MATERIALIZE STATISTICS v SETTINGS mutations_sync = 1";
        logStmt(materialize);
        new SQLQueryAdapter(materialize, statsErrors, false).execute(state);
    }

    private void assertMultisetsEqual(List<JoinKind> kinds, String firstQuery, List<String> firstRows,
            String secondQuery, List<String> secondRows) {
        List<String> diff = multisetDiff(firstRows, secondRows, DIFF_LIMIT);
        if (diff.isEmpty()) {
            return;
        }
        List<String> chain = new ArrayList<>();
        for (JoinKind k : kinds) {
            chain.add(k.name());
        }
        throw new AssertionError(String.format(
                "join-reorder multiset mismatch (chain %s): %d rows vs %d rows.%nfirst:  %s%nsecond: %s%n"
                        + "first %d differing entries (value (+count side)): %s",
                chain, firstRows.size(), secondRows.size(), firstQuery, secondQuery, diff.size(), diff));
    }

    static List<Integer> deterministicTables(List<JoinKind> kinds) {
        TreeSet<Integer> allowed = new TreeSet<>();
        for (int i = 0; i <= kinds.size(); i++) {
            allowed.add(i);
        }
        for (int i = 0; i < kinds.size(); i++) {
            switch (kinds.get(i)) {
            case LEFT_SEMI:
            case LEFT_ANTI:
                allowed.retainAll(List.of(0));
                break;
            case RIGHT_SEMI:
            case RIGHT_ANTI:
                allowed.retainAll(List.of(i + 1));
                break;
            default:
                break;
            }
        }
        return new ArrayList<>(allowed);
    }

    static String renderProjection(List<Integer> deterministicAliases) {
        if (deterministicAliases.isEmpty()) {
            return "toString(count())";
        }
        StringBuilder sb = new StringBuilder("toString(tuple(");
        boolean first = true;
        for (int idx : deterministicAliases) {
            for (String col : List.of("k", "v", "s")) {
                if (!first) {
                    sb.append(", ");
                }
                sb.append("a").append(idx).append(".").append(col);
                first = false;
            }
        }
        return sb.append("))").toString();
    }

    static String renderQuery(List<JoinKind> kinds, List<String> tableNames, List<Integer> onLeftAliases,
            String whereOrNull, String settingsSuffix) {
        StringBuilder sb = new StringBuilder("SELECT ").append(renderProjection(deterministicTables(kinds)));
        sb.append(" FROM ").append(tableNames.get(0)).append(" AS a0");
        for (int i = 0; i < kinds.size(); i++) {
            int right = i + 1;
            sb.append(" ").append(kinds.get(i).getSql()).append(" ").append(tableNames.get(right)).append(" AS a")
                    .append(right).append(" ON a").append(onLeftAliases.get(i)).append(".k = a").append(right)
                    .append(".k");
        }
        if (whereOrNull != null) {
            sb.append(" WHERE ").append(whereOrNull);
        }
        return sb.append(" ").append(settingsSuffix).toString();
    }

    static List<String> multisetDiff(List<String> first, List<String> second, int limit) {
        Map<String, Long> counts = new TreeMap<>();
        for (String s : first) {
            counts.merge(s == null ? "\\N" : s, 1L, Long::sum);
        }
        for (String s : second) {
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
            diff.add(e.getKey() + " (+" + Math.abs(c) + " " + (c > 0 ? "first" : "second") + ")");
        }
        return diff;
    }

    private void logStmt(String stmt) {
        if (state.getOptions().logEachSelect()) {
            state.getLogger().writeCurrent(stmt);
            state.getState().logStatement(stmt);
        }
    }
}
