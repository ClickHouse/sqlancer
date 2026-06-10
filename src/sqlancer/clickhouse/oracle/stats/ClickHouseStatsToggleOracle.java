package sqlancer.clickhouse.oracle.stats;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import com.clickhouse.data.ClickHouseDataType;

import sqlancer.ComparatorHelper;
import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.clickhouse.ClickHouseErrors;
import sqlancer.clickhouse.ClickHouseProvider.ClickHouseGlobalState;
import sqlancer.clickhouse.ClickHouseSchema.ClickHouseColumn;
import sqlancer.clickhouse.ClickHouseSchema.ClickHouseTable;
import sqlancer.clickhouse.ast.ClickHouseAlterStatistics;
import sqlancer.clickhouse.gen.ClickHouseStatisticsGenerator;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;

/**
 * Statistics on/off differential oracle (Unit 9 of the 2026-06-10 26.x coverage plan): targets stats-driven planning
 * wrong results -- column statistics (tdigest / uniq / countmin / minmax) feed PREWHERE reordering, index selection
 * and join cost decisions, and a bug in that path drops or duplicates rows rather than crashing. The surface is young:
 * 26.2 moved statistics to a single-file part format, 26.4 made `use_statistics` / `allow_statistics_optimize`
 * non-experimental (both default true) and started auto-creating `minmax, uniq` statistics on every new table
 * ({@code auto_statistics_types}) -- so the stats-ON arm is meaningful on every fleet table even without explicit
 * statistics DDL.
 *
 * <p>
 * The invariant: the same deterministic SELECT must return the identical result multiset under
 * {@code use_statistics = 1, allow_statistics_optimize = 1} and under {@code = 0, = 0}. Statistics may only change the
 * plan, never the rows. Two arms per {@code check()}, picked ~70/30:
 * <ul>
 * <li><b>Arm A -- fleet differential (Shape A, ProjectionToggle template).</b> A random non-empty <b>plain
 * MergeTree</b> fleet table (dedupe engines change visible rows when a background merge lands between the two arms --
 * the documented 2026-05-20 false-positive class -- so they are excluded; {@code mutations_sync} covers mutations, not
 * merges). ~40% of iterations first run one statistics ALTER from {@link ClickHouseStatisticsGenerator} (this oracle
 * is the generator's sole consumer until it joins the fleet Action pool post-convergence). The DDL is best-effort and
 * <b>safe against fleet tables under the plan's read-only constraint</b>: MODIFY/DROP STATISTICS are metadata-only and
 * MATERIALIZE STATISTICS is a stats-rebuild mutation that never changes row data -- this is how the plan's
 * "fleet-table oracles are read-only" rule and its bulk-DELETE staleness wish are reconciled (the destructive shape
 * lives in arm B on private tables). The differential SELECT projects {@code toString(tuple(<int cols>))}, filters on
 * 1-3 integer predicates, and optionally INNER JOINs a second plain-MergeTree fleet table on an integer column pair;
 * join queries pin {@code query_plan_optimize_join_order_limit = 0} on <b>both</b> arms so a mismatch attributes to
 * statistics, not to Unit 3's reorder surface (and vice versa: the JoinReorder oracle pins its stats state).</li>
 * <li><b>Arm B -- staleness differential (Shape C, MutationAnalyzer lifecycle).</b> A private AtomicLong-suffixed
 * table {@code <db>.stats_<id>_t (k Int32, v Int64)} seeded with skewed data (k heavily duplicated over 0-2 plus a
 * tail of distinct values) from {@code numbers(500-2000)}; ADD-IF-NOT-EXISTS + MODIFY STATISTICS on k and v with
 * rotating kinds (so every kind gets traffic deterministically; static per-kind counters record each successful
 * MATERIALIZE so a run summary can confirm all four kinds materialized -- see {@link #materializedCount}); then
 * {@code MATERIALIZE STATISTICS k, v SETTINGS mutations_sync = 1}; then the stats are made <b>stale</b> -- a sync
 * {@code ALTER ... DELETE WHERE k % 2 = 0} or a second INSERT shifting the k domain by 1,000,000 -- and the
 * differential pair runs against data the materialized statistics no longer describe. Stale statistics must change
 * the plan only, never the result. ~30% of iterations additionally DROP STATISTICS and re-run the same pair. DROP
 * TABLE in {@code finally}.</li>
 * </ul>
 *
 * <p>
 * Tolerance is narrow (MutationAnalyzer layering): a shared baseline (session-settings family -- {@code
 * use_statistics} is unknown on pre-26.x images and the pair degrades to IgnoreMe, never a fake finding -- plus
 * UNKNOWN_TABLE, MEMORY_LIMIT, TIMEOUT, the universal max_result_rows cap, and fleet schema-cache staleness) on every
 * statement, and the statistics-DDL rejection family ({@link ClickHouseErrors#addStatisticsErrors},
 * {@code ILLEGAL_STATISTICS}) on the statistics statements ONLY. A tolerated failure on either side of the
 * differential pair raises {@link IgnoreMeException} inside the result fetch, so a partial pair is never compared.
 *
 * <p>
 * Note on the experimental gate: {@code ClickHouseColumnBuilder}'s inline {@code STATISTICS(...)} comment still claims
 * {@code allow_experimental_statistics = 1} is required; per the plan's correction #9 that gate is gone on head (the
 * settings are default-true and non-experimental). This oracle relies on the errors catalogue to absorb the gate on
 * older images instead of setting it, and deliberately does not touch the ColumnBuilder comment.
 */
public class ClickHouseStatsToggleOracle implements TestOracle<ClickHouseGlobalState> {

    private static final AtomicLong STATS_COUNTER = new AtomicLong();
    private static final int DIFF_LIMIT = 20;

    /** Statistics kinds under rotation in arm B; mirrors {@code ClickHouseStatisticsGenerator}'s kind pool. */
    static final List<String> STATISTICS_KINDS = List.of("tdigest", "uniq", "countmin", "minmax");

    // Per-kind success counters for arm B's MATERIALIZE STATISTICS: a run summary (or a post-run
    // debugger/test hook) can confirm every kind materialized at least once across a run. Static
    // because oracle instances are per-thread while the coverage question is per-run.
    private static final Map<String, AtomicLong> MATERIALIZED_BY_KIND = Map.of("tdigest", new AtomicLong(), "uniq",
            new AtomicLong(), "countmin", new AtomicLong(), "minmax", new AtomicLong());

    // The two differential arms. Both settings are toggled together: use_statistics gates loading
    // the stats objects at all, allow_statistics_optimize gates the optimizer consuming them --
    // flipping only one leaves a half-on state that is not the bug-shaped contrast.
    static final String ARM_STATS_ON = "SETTINGS use_statistics = 1, allow_statistics_optimize = 1";
    static final String ARM_STATS_OFF = "SETTINGS use_statistics = 0, allow_statistics_optimize = 0";
    // Appended to BOTH arms of join-shaped differentials: stats act through join-order decisions,
    // so pinning reorder off attributes any mismatch to the statistics path (Unit 3's JoinReorder
    // oracle owns the reorder axis and conversely pins its stats state).
    static final String PIN_JOIN_ORDER = ", query_plan_optimize_join_order_limit = 0";

    private final ClickHouseGlobalState state;
    // Two tolerance sets, deliberately scoped (MutationAnalyzer layering):
    //   readErrors     -- CREATE / INSERT / mutations / the differential SELECTs / DROP. No
    //                     statistics-DDL family here: a statistics-shaped rejection on a SELECT
    //                     would be a NEW finding and must surface.
    //   statsDdlErrors -- the statistics ALTER statements only. Baseline + the stats-DDL
    //                     rejection family (unsupported kind/type, gate off on older images).
    private final ExpectedErrors readErrors = new ExpectedErrors();
    private final ExpectedErrors statsDdlErrors = new ExpectedErrors();

    public ClickHouseStatsToggleOracle(ClickHouseGlobalState state) {
        this.state = state;
        // Narrow shared baseline (no global expression list -- every statement is hand-built SQL
        // over integer columns, so analyzer/type-shaped messages can only mean a bug):
        for (ExpectedErrors e : List.of(readErrors, statsDdlErrors)) {
            // use_statistics / allow_statistics_optimize / query_plan_optimize_join_order_limit
            // are young settings: on older images the pair degrades to IgnoreMe, never a finding.
            ClickHouseErrors.addSessionSettingsErrors(e);
            // Per-thread database drop/recreate race (same as the MutationAnalyzer / JoinReorder
            // oracles): reads hitting a dropped namespace are not statistics bugs.
            e.add("UNKNOWN_TABLE");
            e.add("Unknown table expression identifier");
            // Arm A reads fleet tables whose columns another generator action may have just
            // dropped/renamed; a stale schema snapshot is a harness artifact, not a stats bug
            // (statistics bugs manifest as wrong results, never as unknown identifiers).
            e.add("UNKNOWN_IDENTIFIER");
            e.add("Missing columns");
            // Code 241 load-shedding under the squeezed dev-vm container cap (-m=6g): environment
            // artifact. A tolerated arm failure aborts the iteration via IgnoreMe, so it cannot
            // fake a multiset match.
            e.add("(MEMORY_LIMIT_EXCEEDED)");
            e.add("memory limit exceeded");
            // A load-shed timeout is an environment artifact here (no statement in this oracle has
            // a deadlock-shaped finding).
            e.add("TIMEOUT_EXCEEDED");
            e.add("Timeout exceeded");
            // The provider pins max_result_rows=1M + result_overflow_mode='throw' on every
            // connection; an arm-A INNER JOIN over dup-heavy CERT-filler fleet tables can
            // legitimately exceed the cap on both arms.
            e.add("Limit for result exceeded");
            e.add("TOO_MANY_ROWS_OR_BYTES");
            // Arm-A join keys are random integer column pairs; UInt256-vs-signed has no common
            // supertype and the JOIN ON is rejected at analysis time (Code 386). A type-system
            // rejection, not a stats bug (getSetOpErrors tolerates the same family).
            e.add("There is no supertype");
            e.add("NO_COMMON_TYPE");
        }
        // Statistics-statements-only: ADD/MODIFY/MATERIALIZE/DROP STATISTICS rejections
        // (experimental gate on older images, kind-vs-column-type mismatches from the generator's
        // unconstrained column pick) skip the DDL step or the arm-B iteration, never fake a pass.
        ClickHouseErrors.addStatisticsErrors(statsDdlErrors);
        // Code 707 covers add-existing / modify-missing / drop-missing / unsupported-type; the
        // catalogue above carries only the older message forms.
        statsDdlErrors.add("ILLEGAL_STATISTICS");
        statsDdlErrors.add("already contains statistics");
    }

    @Override
    public void check() throws SQLException {
        if (Randomly.getNotCachedInteger(0, 100) < 70) {
            List<ClickHouseTable> eligible = state.getSchema().getDatabaseTables().stream()
                    .filter(ClickHouseStatsToggleOracle::isEligibleFleetTable).collect(Collectors.toList());
            if (!eligible.isEmpty()) {
                checkFleetArm(eligible);
                return;
            }
            // No eligible fleet table yet (early in a database's life every table may still be a
            // dedupe engine or integer-free): fall through to the self-contained arm so the
            // iteration still produces coverage instead of IgnoreMe.
        }
        checkStalenessArm();
    }

    // --- Arm A: fleet differential -------------------------------------------------------------

    private void checkFleetArm(List<ClickHouseTable> eligible) throws SQLException {
        ClickHouseTable table = eligible.get((int) Randomly.getNotCachedInteger(0, eligible.size()));
        List<ClickHouseColumn> intCols = integerColumns(table);

        // Non-empty gate: an empty table makes the pair trivially equal -- spend the iteration
        // elsewhere. (getResultSetFirstColumnAsString logs the query and IgnoreMes on a tolerated
        // failure.)
        List<String> countRows = ComparatorHelper
                .getResultSetFirstColumnAsString("SELECT toString(count()) FROM " + table.getName(), readErrors, state);
        if (countRows.size() != 1 || "0".equals(countRows.get(0))) {
            throw new IgnoreMeException();
        }

        // ~40%: vary the statistics state first via the generator (kinds beyond the 26.4
        // auto-stats pair, DROP/MATERIALIZE churn). Metadata-only / row-data-preserving, so safe
        // on fleet tables; best-effort -- a tolerated rejection skips the step, never the
        // differential. The generator's MATERIALIZE carries no mutations_sync, but a stats
        // rebuild landing between the two reads cannot move rows, so the pair stays sound.
        if (Randomly.getNotCachedInteger(0, 100) < 40) {
            ClickHouseAlterStatistics ddl = ClickHouseStatisticsGenerator.buildStatement(state);
            logStmt(ddl.getSql());
            new SQLQueryAdapter(ddl.getSql(), statsDdlErrors, false).execute(state);
        }

        List<ClickHouseTable> partners = eligible.stream().filter(t -> !t.getName().equals(table.getName()))
                .collect(Collectors.toList());
        ClickHouseTable partner = null;
        if (!partners.isEmpty() && Randomly.getBoolean()) {
            partner = partners.get((int) Randomly.getNotCachedInteger(0, partners.size()));
        }

        String select;
        boolean pinJoinOrder = partner != null;
        if (partner == null) {
            List<String> refs = intCols.stream().map(c -> ref(null, c.getName())).collect(Collectors.toList());
            select = "SELECT " + renderTupleProjection(pickRefs(refs, 3)) + " FROM " + table.getName() + " WHERE "
                    + buildWhere(refs);
        } else {
            List<ClickHouseColumn> partnerInts = integerColumns(partner);
            List<String> leftRefs = intCols.stream().map(c -> ref("a0", c.getName())).collect(Collectors.toList());
            List<String> projection = new ArrayList<>(pickRefs(leftRefs, 2));
            projection.add(ref("a1", partnerInts.get((int) Randomly.getNotCachedInteger(0, partnerInts.size()))
                    .getName()));
            String onLeft = leftRefs.get((int) Randomly.getNotCachedInteger(0, leftRefs.size()));
            String onRight = ref("a1",
                    partnerInts.get((int) Randomly.getNotCachedInteger(0, partnerInts.size())).getName());
            select = "SELECT " + renderTupleProjection(projection) + " FROM " + table.getName() + " AS a0 INNER JOIN "
                    + partner.getName() + " AS a1 ON " + onLeft + " = " + onRight + " WHERE " + buildWhere(leftRefs);
        }
        runDifferential(select, pinJoinOrder);
    }

    // --- Arm B: staleness differential on a private table ---------------------------------------

    private void checkStalenessArm() throws SQLException {
        long id = STATS_COUNTER.incrementAndGet();
        String table = state.getDatabaseName() + ".stats_" + id + "_t";
        long rows = 500 + Randomly.getNotCachedInteger(0, 1501);
        String kindK = kindForIteration(id, 0);
        String kindV = kindForIteration(id, 1);

        try {
            List<String> setup = renderStalenessSetup(table, rows, kindK, kindV);
            for (int i = 0; i < setup.size(); i++) {
                String stmt = setup.get(i);
                boolean isStatsDdl = i >= 2;
                logStmt(stmt);
                if (!new SQLQueryAdapter(stmt, isStatsDdl ? statsDdlErrors : readErrors, !isStatsDdl)
                        .execute(state)) {
                    // Without materialized statistics the staleness contrast does not exist;
                    // abandon (e.g. the whole stats subsystem gated off on an older image).
                    throw new IgnoreMeException();
                }
            }
            MATERIALIZED_BY_KIND.get(kindK).incrementAndGet();
            MATERIALIZED_BY_KIND.get(kindV).incrementAndGet();

            // Make the materialized stats stale: drop ~2/3 of the rows (sync mutation), or shift
            // the k domain a million away from everything minmax/tdigest recorded.
            String stale = Randomly.getBoolean() ? renderStaleDelete(table) : renderStaleInsert(table, rows);
            logStmt(stale);
            if (!new SQLQueryAdapter(stale, readErrors, false).execute(state)) {
                throw new IgnoreMeException();
            }

            String select = "SELECT toString(tuple(k, v)) FROM " + table + " WHERE " + buildWhere(List.of("k", "v"));
            runDifferential(select, false);

            // ~30%: DROP STATISTICS mid-sequence and re-run the SAME pair -- the planner must
            // degrade to statistics-free planning without changing the rows.
            if (Randomly.getNotCachedInteger(0, 100) < 30) {
                String drop = renderDropStatistics(table);
                logStmt(drop);
                if (new SQLQueryAdapter(drop, statsDdlErrors, false).execute(state)) {
                    runDifferential(select, false);
                }
            }
        } finally {
            try {
                new SQLQueryAdapter("DROP TABLE IF EXISTS " + table, readErrors, true).execute(state);
            } catch (Exception | AssertionError ignored) {
                // Best effort; AssertionError too, because SQLQueryAdapter.execute() throws an
                // AssertionError (not an exception) on an untolerated error, and a DROP hitting
                // e.g. a transport failure must not write a misleading reproducer. The
                // disk-cleanup script reaps any orphans.
            }
        }
    }

    // --- Shared differential machinery -----------------------------------------------------------

    private void runDifferential(String select, boolean pinJoinOrder) throws SQLException {
        String[] pair = renderDifferentialPair(select, pinJoinOrder);
        // A tolerated failure on either side raises IgnoreMe inside the fetch, so a partial pair
        // is never compared.
        List<String> statsOnRows = ComparatorHelper.getResultSetFirstColumnAsString(pair[0], readErrors, state);
        List<String> statsOffRows = ComparatorHelper.getResultSetFirstColumnAsString(pair[1], readErrors, state);
        assertMultisetsEqual(statsOnRows, statsOffRows, pair[0], pair[1]);
    }

    /**
     * Renders the two arms of the differential: index 0 carries the statistics-ON settings suffix, index 1 the
     * statistics-OFF suffix. Join-shaped selects additionally pin join reordering off on both arms.
     */
    static String[] renderDifferentialPair(String select, boolean pinJoinOrder) {
        String pin = pinJoinOrder ? PIN_JOIN_ORDER : "";
        return new String[] { select + " " + ARM_STATS_ON + pin, select + " " + ARM_STATS_OFF + pin };
    }

    static void assertMultisetsEqual(List<String> statsOnRows, List<String> statsOffRows, String statsOnSql,
            String statsOffSql) {
        List<String> diff = multisetDiff(statsOnRows, statsOffRows, DIFF_LIMIT);
        if (diff.isEmpty()) {
            return;
        }
        throw new AssertionError(String.format(
                "stats-toggle multiset mismatch: %d rows with statistics on vs %d rows with statistics off.%n"
                        + "on:  %s%noff: %s%nfirst %d differing entries (value (+count side)): %s",
                statsOnRows.size(), statsOffRows.size(), statsOnSql, statsOffSql, diff.size(), diff));
    }

    /**
     * Multiset difference of two string lists: empty iff the lists are equal as multisets. Each entry is
     * {@code "<value> (+<n> on|off)"} for a value over-represented on one side, capped at {@code limit} entries in
     * sorted value order. Null entries (cannot occur for tuple-rendered strings, but defensive) sort as the literal
     * {@code "\\N"}.
     */
    static List<String> multisetDiff(List<String> statsOnRows, List<String> statsOffRows, int limit) {
        Map<String, Long> counts = new TreeMap<>();
        for (String s : statsOnRows) {
            counts.merge(s == null ? "\\N" : s, 1L, Long::sum);
        }
        for (String s : statsOffRows) {
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
            diff.add(e.getKey() + " (+" + Math.abs(c) + " " + (c > 0 ? "on" : "off") + ")");
        }
        return diff;
    }

    // --- Statement rendering (static for DB-free tests) ------------------------------------------

    /**
     * Arm B setup, in execution order: CREATE, skewed INSERT (k mostly duplicated over 0-2 with a ~25% tail of
     * distinct values), then per column ADD STATISTICS IF NOT EXISTS + MODIFY STATISTICS (ADD covers images without
     * 26.4 auto-stats where MODIFY-on-nothing rejects; MODIFY forces the rotated kind where auto-stats already
     * exist), then a sync MATERIALIZE over both columns.
     */
    static List<String> renderStalenessSetup(String table, long rows, String kindK, String kindV) {
        return List.of("CREATE TABLE " + table + " (k Int32, v Int64) ENGINE = MergeTree ORDER BY k",
                "INSERT INTO " + table + " SELECT toInt32(if(number % 4 = 3, number, number % 3)), "
                        + "toInt64(number % 11) FROM numbers(" + rows + ")",
                "ALTER TABLE " + table + " ADD STATISTICS IF NOT EXISTS k TYPE " + kindK,
                "ALTER TABLE " + table + " MODIFY STATISTICS k TYPE " + kindK,
                "ALTER TABLE " + table + " ADD STATISTICS IF NOT EXISTS v TYPE " + kindV,
                "ALTER TABLE " + table + " MODIFY STATISTICS v TYPE " + kindV,
                "ALTER TABLE " + table + " MATERIALIZE STATISTICS k, v SETTINGS mutations_sync = 1");
    }

    static String renderStaleDelete(String table) {
        return "ALTER TABLE " + table + " DELETE WHERE k % 2 = 0 SETTINGS mutations_sync = 1";
    }

    static String renderStaleInsert(String table, long rows) {
        return "INSERT INTO " + table + " SELECT toInt32(1000000 + number), toInt64(number % 5) FROM numbers(" + rows
                + ")";
    }

    static String renderDropStatistics(String table) {
        return "ALTER TABLE " + table + " DROP STATISTICS k, v";
    }

    static String renderTupleProjection(List<String> columnRefs) {
        return "toString(tuple(" + String.join(", ", columnRefs) + "))";
    }

    // 1-3 integer predicates over the given column refs, AND/OR-combined; comparisons against
    // small constants plus an occasional modulo-bucket predicate. Integer-only by construction
    // (the float false-positive families never enter).
    static String buildWhere(List<String> columnRefs) {
        int predicates = 1 + (int) Randomly.getNotCachedInteger(0, 3);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < predicates; i++) {
            if (i > 0) {
                sb.append(Randomly.getBoolean() ? " AND " : " OR ");
            }
            String col = columnRefs.get((int) Randomly.getNotCachedInteger(0, columnRefs.size()));
            if (Randomly.getBooleanWithRatherLowProbability()) {
                int modulus = 2 + (int) Randomly.getNotCachedInteger(0, 9); // 2..10, never 0
                sb.append("(").append(col).append(" % ").append(modulus).append(" = ")
                        .append(Randomly.getNotCachedInteger(0, modulus)).append(")");
            } else {
                String op = Randomly.fromOptions("<", "<=", ">", ">=", "=", "!=");
                sb.append("(").append(col).append(" ").append(op).append(" ")
                        .append(Randomly.getNotCachedInteger(-1000, 1000)).append(")");
            }
        }
        return sb.toString();
    }

    // --- Eligibility & kind rotation --------------------------------------------------------------

    /**
     * Arm-A table gate: a non-view, <b>plain MergeTree</b> table with at least one exact-integer column. Dedupe
     * engines (Replacing/Summing/Collapsing/Aggregating) are excluded -- a background merge landing between the two
     * arms changes their visible rows -- and an empty engine string (undiscovered) fails closed.
     */
    static boolean isEligibleFleetTable(ClickHouseTable table) {
        return !table.isView() && "MergeTree".equals(table.getEngine())
                && table.getColumns().stream().anyMatch(c -> isExactInteger(c.getType().getType()));
    }

    // Integer types only: comparisons and modulo over these are exact, so the two arms must agree
    // byte-for-byte. getType() already unwraps Nullable / LowCardinality.
    static boolean isExactInteger(ClickHouseDataType t) {
        switch (t) {
        case Int8:
        case Int16:
        case Int32:
        case Int64:
        case Int128:
        case Int256:
        case UInt8:
        case UInt16:
        case UInt32:
        case UInt64:
        case UInt128:
        case UInt256:
            return true;
        default:
            return false;
        }
    }

    /**
     * Deterministic kind rotation for arm B: iteration {@code id} assigns {@code KINDS[(id + offset) mod 4]}, so
     * four consecutive iterations cover every kind on each column position.
     */
    static String kindForIteration(long id, int offset) {
        return STATISTICS_KINDS.get((int) Math.floorMod(id + offset, STATISTICS_KINDS.size()));
    }

    /** Package-private run-summary hook: successful MATERIALIZE count for one statistics kind. */
    static long materializedCount(String kind) {
        return MATERIALIZED_BY_KIND.get(kind).get();
    }

    // --- Small helpers ----------------------------------------------------------------------------

    private static List<ClickHouseColumn> integerColumns(ClickHouseTable table) {
        return table.getColumns().stream().filter(c -> isExactInteger(c.getType().getType()))
                .collect(Collectors.toList());
    }

    private static String ref(String aliasOrNull, String columnName) {
        return (aliasOrNull == null ? "" : aliasOrNull + ".") + "`" + columnName + "`";
    }

    // 1..min(max, size) distinct refs in random order (Randomly-driven, like every other
    // generator choice in this oracle).
    private static List<String> pickRefs(List<String> refs, int max) {
        List<String> pool = new ArrayList<>(refs);
        int n = 1 + (int) Randomly.getNotCachedInteger(0, Math.min(max, pool.size()));
        List<String> picked = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            picked.add(pool.remove((int) Randomly.getNotCachedInteger(0, pool.size())));
        }
        return picked;
    }

    private void logStmt(String stmt) {
        if (state.getOptions().logEachSelect()) {
            state.getLogger().writeCurrent(stmt);
            state.getState().logStatement(stmt);
        }
    }
}
