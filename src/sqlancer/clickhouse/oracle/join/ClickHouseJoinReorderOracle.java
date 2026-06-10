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

/**
 * Join-reorder differential oracle: targets the 26.3 extension of join-order swapping to ANTI/SEMI/FULL joins
 * (PR #97498). The wrong-result class is proven live by the 26.5 fix PR #101504 (reordering produced wrong results),
 * and the crash class by the still-open #106426 ({@code LOGICAL_ERROR "Join restriction violated"} in
 * {@code JoinOrderOptimizer::solveGreedy}).
 *
 * <p>
 * Self-contained (Shape C, MutationAnalyzer lifecycle): per {@code check()} it creates 3-4 private AtomicLong-suffixed
 * plain-MergeTree tables {@code <db>.jreord_<id>_t{0..3}} ({@code k} Int32 or Nullable(Int32), {@code v} Int32,
 * {@code s} String), seeds them with a small key domain (0-9, occasional out-of-domain outlier for
 * disjoint-range FULL coverage), deliberate NULL keys on the Nullable tables, duplicate keys, and skewed
 * cardinalities -- t0 large (200-400 rows), t1..t3 tiny (0-5 rows). The cardinality asymmetry is what drives the
 * optimizer's reordering decisions (and is the exact #106426 trigger shape).
 *
 * <p>
 * One query per iteration: a chain of 2-3 joins over distinct aliases with kinds drawn from {INNER, LEFT, FULL,
 * LEFT SEMI, LEFT ANTI, RIGHT SEMI, RIGHT ANTI}, equality ON clauses ({@code a<x>.k = a<i>.k}, {@code x < i}), and an
 * occasional (~30%) cross-relation WHERE over two different tables' {@code v} columns. The same query text runs under
 * three SETTINGS arms and the three result multisets are compared pairwise:
 * <ol>
 * <li>{@code query_plan_optimize_join_order_limit = 10} -- reordering on (the default);</li>
 * <li>{@code query_plan_optimize_join_order_limit = 0} -- reordering off (the reference plan);</li>
 * <li>{@code query_plan_optimize_join_order_limit = 10, query_plan_optimize_join_order_randomize = 1} -- the 26.4
 * test knob that shuffles the chosen order, forcing orders the cost model would never pick.</li>
 * </ol>
 *
 * <p>
 * <b>Projection determinism rules.</b> SEMI/ANTI join outputs are only deterministic as a set of rows of one side:
 * <ul>
 * <li>LEFT SEMI keeps left rows that have a match, but the right-side non-key columns come from "some" match --
 * non-deterministic under duplicate right keys. LEFT ANTI keeps left rows without a match (right side
 * default-filled). For both, the deterministic projection surface is the leftmost (driving) table.</li>
 * <li>RIGHT SEMI/ANTI mirror this: they keep right-table rows, so only the right table of that join is
 * deterministic.</li>
 * <li>INNER/LEFT/FULL impose no restriction.</li>
 * </ul>
 * The projected table set is the intersection of every join's allowed set ({@link #deterministicTables}); if the
 * chain mixes constraints so that no table is deterministic for every join, the query projects {@code count()} only.
 * The projection is rendered as a single {@code toString(tuple(<qualified k,v,s columns>))} column so the multiset
 * compare runs over plain strings (tuple-rendered strings are never SQL NULL, so sorting is safe).
 *
 * <p>
 * ON clauses and the cross-relation WHERE stay sound through SEMI/ANTI because they only touch columns that remain
 * deterministic: ON clauses use only {@code k} equality -- through a SEMI join the matched {@code k} is pinned by the
 * equality itself, and through an ANTI join the other side's {@code k} is default-filled (0 / NULL) deterministically
 * -- and the WHERE only references {@code v} columns of tables in the deterministic set.
 *
 * <p>
 * ~25% of iterations run {@code ADD STATISTICS IF NOT EXISTS ... TYPE minmax} + {@code MATERIALIZE STATISTICS} on the
 * big table after seeding (reordering is stats-driven; since 26.4 auto-stats exist, materializing makes the cost
 * model see the skew). The stats step is best-effort: a tolerated failure skips the step, never the iteration.
 *
 * <p>
 * Error tolerance mirrors MutationAnalyzer's layering: a narrow shared baseline (session-settings family -- the
 * randomize knob is 26.4+ and unknown on older images -- UNKNOWN_TABLE, MEMORY_LIMIT, TIMEOUT) plus, on the SELECT
 * arms ONLY, the known-open #106426 pin {@code "Join restriction violated"} routed to {@link IgnoreMeException}.
 * <b>Remove the pin when #106426 is fixed on head</b> (check:
 * {@code gh issue view 106426 --repo ClickHouse/ClickHouse --json state -q .state}).
 */
public class ClickHouseJoinReorderOracle implements TestOracle<ClickHouseGlobalState> {

    private static final AtomicLong JREORD_COUNTER = new AtomicLong();
    private static final int DIFF_LIMIT = 20;

    // The three arms. Limit=10 is the server default, spelled explicitly so the arm survives a
    // future default change; limit=0 disables reordering entirely (the reference plan);
    // randomize=1 (26.4 test knob) shuffles the chosen order so cost-model-implausible orders get
    // traffic too.
    static final String ARM_REORDER_ON = "SETTINGS query_plan_optimize_join_order_limit = 10";
    static final String ARM_REORDER_OFF = "SETTINGS query_plan_optimize_join_order_limit = 0";
    static final String ARM_REORDER_RANDOMIZE = "SETTINGS query_plan_optimize_join_order_limit = 10, "
            + "query_plan_optimize_join_order_randomize = 1";

    /**
     * Join kinds under test. {@code sql} is the exact join clause keyword sequence.
     */
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
    }

    private final ClickHouseGlobalState state;
    // Three tolerance sets, deliberately scoped (MutationAnalyzer layering):
    //   readErrors   -- CREATE / INSERT / DROP. No #106426 pin here: a "Join restriction violated"
    //                   on a setup statement would be a NEW finding and must surface.
    //   selectErrors -- the three SELECT arms only. Baseline + the #106426 pin.
    //   statsErrors  -- the two stats statements only. Baseline + the stats-DDL rejection family.
    private final ExpectedErrors readErrors = new ExpectedErrors();
    private final ExpectedErrors selectErrors = new ExpectedErrors();
    private final ExpectedErrors statsErrors = new ExpectedErrors();

    public ClickHouseJoinReorderOracle(ClickHouseGlobalState state) {
        this.state = state;
        // Narrow shared baseline (no global expression list -- every statement is hand-built
        // static SQL, so analyzer/JOIN-shaped messages can only mean a bug and must surface):
        for (ExpectedErrors e : List.of(readErrors, selectErrors, statsErrors)) {
            // query_plan_optimize_join_order_randomize is 26.4+; on older images the arm degrades
            // to IgnoreMe instead of a fake finding.
            ClickHouseErrors.addSessionSettingsErrors(e);
            // Per-thread database drop/recreate race (same as the MV / PatchPart / MutationAnalyzer
            // oracles): reads hitting a dropped namespace are not reorder bugs.
            e.add("UNKNOWN_TABLE");
            e.add("Unknown table expression identifier");
            // Code 241 load-shedding under the squeezed dev-vm container cap (-m=6g): environment
            // artifact, not a reorder bug. A tolerated arm failure aborts the iteration via
            // IgnoreMe, so it cannot fake a multiset match.
            e.add("(MEMORY_LIMIT_EXCEEDED)");
            e.add("memory limit exceeded");
            // A load-shed timeout on any statement is an environment artifact here (no statement
            // in this oracle has a deadlock-shaped finding the way MutationAnalyzer's shape (b)
            // does).
            e.add("TIMEOUT_EXCEEDED");
            e.add("Timeout exceeded");
        }
        // SELECT-arms-only known-open pin: #106426, LOGICAL_ERROR "Join restriction violated" in
        // JoinOrderOptimizer::solveGreedy -- filed, still open, and this oracle's skewed-cardinality
        // chains are exactly its trigger shape. Routed to IgnoreMe so already-filed noise does not
        // kill workers. Deliberately a narrow message substring, never the bare LOGICAL_ERROR token,
        // and kept OFF readErrors/statsErrors. REMOVE when #106426 is fixed on head.
        selectErrors.add("Join restriction violated");
        // Stats-statement-only: ADD/MATERIALIZE STATISTICS rejections (experimental flag off on
        // older images, unsupported kind/type) just skip the stats step.
        ClickHouseErrors.addStatisticsErrors(statsErrors);
        statsErrors.add("already contains statistics");
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
        // Nullable(Int32) key on some tables; force at least one so the NULL-key join semantics
        // (NULL never matches -- the rows ANTI keeps, the rows FULL leaves unmatched) get traffic
        // every iteration.
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

            // Stats interplay (~25%): reordering is cost-model-driven; materializing minmax stats
            // on the big table makes the model see the cardinality skew. Best-effort -- a tolerated
            // failure skips the step, never the iteration.
            if (Randomly.getNotCachedInteger(0, 100) < 25) {
                materializeStatsBestEffort(tables.get(0));
            }

            List<JoinKind> kinds = new ArrayList<>();
            for (int i = 0; i < numJoins; i++) {
                kinds.add(Randomly.fromOptions(JoinKind.values()));
            }
            List<Integer> onLeft = new ArrayList<>();
            for (int i = 0; i < numJoins; i++) {
                // ON for join i (alias a<i+1>) references the k of a random earlier alias. k-only
                // equality keeps the ON deterministic through SEMI/ANTI (see class javadoc).
                onLeft.add((int) Randomly.getNotCachedInteger(0, i + 1));
            }
            List<Integer> det = deterministicTables(kinds);
            String where = null;
            if (det.size() >= 2 && Randomly.getNotCachedInteger(0, 100) < 30) {
                // Cross-relation WHERE over two different tables' v columns (the #101504/#106426
                // shape). Restricted to the deterministic set so the filter outcome cannot differ
                // across arms; v is Int32, so no float noise.
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
                    // Best effort; AssertionError too, because SQLQueryAdapter.execute() throws an
                    // AssertionError (not an exception) on an untolerated error, and a DROP hitting
                    // e.g. a transport failure must not write a misleading reproducer or abort
                    // cleanup of the sibling tables.
                }
            }
        }
    }

    private void seedTables(List<String> tables, boolean[] nullableKey) throws SQLException {
        // t0 big: 200-400 rows over key domain 0-9 (heavy duplication), ~1/11 NULL keys when the
        // key is Nullable. Single INSERT = one part.
        long bigRows = 200 + Randomly.getNotCachedInteger(0, 201);
        String keyExpr = nullableKey[0] ? "if(number % 11 = 0, NULL, toInt32(number % 10))" : "toInt32(number % 10)";
        String seedBig = "INSERT INTO " + tables.get(0) + " SELECT " + keyExpr
                + ", toInt32(number % 17), toString(number % 10) FROM numbers(" + bigRows + ")";
        logStmt(seedBig);
        if (!new SQLQueryAdapter(seedBig, readErrors, true).execute(state)) {
            throw new IgnoreMeException();
        }
        // t1..t3 tiny: 1-5 VALUES rows (occasionally 0 -- the empty-table-in-the-chain edge case).
        // Keys mostly in-domain with duplicates, ~10% out-of-domain outlier (disjoint-range FULL
        // coverage), ~25% NULL on Nullable tables.
        for (int i = 1; i < tables.size(); i++) {
            if (Randomly.getNotCachedInteger(0, 100) < 10) {
                continue; // empty table in the chain
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
        // Mirrors ClickHouseStatisticsGenerator's ALTER forms; ADD first because MATERIALIZE
        // needs the statistics object to exist on images without 26.4 auto-stats.
        String add = "ALTER TABLE " + table + " ADD STATISTICS IF NOT EXISTS v TYPE minmax";
        logStmt(add);
        if (!new SQLQueryAdapter(add, statsErrors, false).execute(state)) {
            return; // tolerated rejection: skip the stats step, keep the iteration
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

    /**
     * Alias indices (0-based; alias {@code a<i>} reads table i) whose columns are deterministic under every join of
     * the chain. INNER/LEFT/FULL allow every table; LEFT SEMI/ANTI restrict to the leftmost (driving) table; RIGHT
     * SEMI/ANTI restrict to the right table of that join. The result is the intersection; empty means no table is
     * deterministic for every join and the query must project {@code count()} only.
     *
     * @param kinds
     *            the join-kind chain; join i attaches alias {@code a<i+1>}
     *
     * @return sorted alias indices whose columns may be projected/filtered without arm-dependent values
     */
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

    /**
     * Single-column projection over the deterministic alias set: {@code toString(tuple(...))} over the qualified
     * k/v/s columns, or {@code toString(count())} when no table is deterministic. The tuple-rendered string is never
     * SQL NULL, so the multiset compare can sort plain Java strings.
     *
     * @param deterministicAliases
     *            output of {@link #deterministicTables}
     *
     * @return the single projection expression
     */
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

    /**
     * Renders the full query for one arm.
     *
     * @param kinds
     *            the join-kind chain; join i attaches alias {@code a<i+1>}
     * @param tableNames
     *            fully qualified table names, one per alias ({@code kinds.size() + 1} entries)
     * @param onLeftAliases
     *            per join i, the alias index referenced on the left side of its {@code ON} equality (must be
     *            {@code <= i})
     * @param whereOrNull
     *            cross-relation WHERE condition, or null for none
     * @param settingsSuffix
     *            one of the ARM_* SETTINGS strings
     *
     * @return the complete SELECT text
     */
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

    /**
     * Multiset difference of two string lists: empty iff the lists are equal as multisets. Each returned entry is
     * {@code "<value> (+<n> first|second)"} for a value over-represented on one side, capped at {@code limit} entries
     * in sorted value order. Null entries (cannot occur for tuple-rendered strings, but defensive) sort as the
     * literal {@code "\\N"}.
     *
     * @param first
     *            rows of the first arm
     * @param second
     *            rows of the second arm
     * @param limit
     *            maximum number of differing entries to report
     *
     * @return empty list iff the multisets are equal; otherwise the bounded diff
     */
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
