package sqlancer.clickhouse.oracle.mutate;

import java.sql.SQLException;
import java.util.List;
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
 * Mutation-analyzer oracle: deterministically exercises the surface that ClickHouse PR #98884 routed through the new
 * analyzer in 26.6 -- {@code ALTER TABLE ... UPDATE/DELETE}, lightweight {@code UPDATE ... SET} / {@code DELETE FROM},
 * and {@code MATERIALIZE COLUMN} -- one randomized matrix cell per {@code check()}.
 *
 * <p>
 * The first fallout of that routing is #106649: {@code LOGICAL_ERROR "Column identifier ... is already registered"}
 * when a mutation's WHERE contains an {@code IN (subquery)} whose inner SELECT joins two subquery-wrapped derived
 * tables that each project the same column name. The general fleet can now stumble into that shape organically
 * (mutation-generator U1/U2), but this oracle fires the full matrix every iteration with <b>narrow</b> error tolerance,
 * so analyzer bugs that surface under messages the global expression list tolerates ("Missing columns", "Ambiguous
 * column", "Cannot find column", ...) are caught here.
 *
 * <p>
 * Matrix dimensions per iteration:
 * <ul>
 * <li><b>Mutation kind:</b> ALTER UPDATE / ALTER DELETE / lightweight UPDATE / lightweight DELETE / MATERIALIZE COLUMN
 * (the last over a MATERIALIZED-expression column or a constant-DEFAULT column -- the PR's
 * {@code getTableExpressionDataOrNull} fix).</li>
 * <li><b>WHERE shape</b> (UPDATE/DELETE kinds only): (a) the exact #106649 joined-derived-tables IN-subquery with
 * colliding projected names; (b) IN-subquery whose inner FROM references <em>the mutated table itself</em> -- the PR's
 * {@code currently_processing_in_background_mutex} deadlock-avoidance path, where a deadlock would surface as a
 * {@code max_execution_time} timeout, which this oracle deliberately does NOT tolerate; (c) plain IN-subquery over
 * another private table; (d) predicate over an ALIAS column (alias columns in mutations are newly supported by the
 * PR); (e) predicate over a virtual column ({@code _part_offset}, or {@code _block_number} on a patch-enabled
 * variant) -- crash coverage only, excluded from the consistency assertion because it is part-layout-dependent.</li>
 * <li><b>Settings:</b> {@code mutations_sync=1} in-statement for ALTER kinds; {@code lightweight_deletes_sync=2} for
 * lightweight DELETE ({@code mutations_sync} does not govern {@code DELETE FROM}); lightweight UPDATE is synchronous
 * by design behind {@code enable_lightweight_update=1}. {@code validate_mutation_query} randomized 0/1 -- the PR gates
 * validation behind it and adds an {@code ignore_in_subqueries} analyzer path for 0, so both arms get traffic.</li>
 * <li><b>Engine (stretch):</b> small-probability Memory-engine variant (ALTER kinds only -- PR #98884 routes Memory
 * mutations through the analyzer too); otherwise plain MergeTree, 50% patch-enabled
 * ({@code enable_block_number_column/enable_block_offset_column}) so shape (e) can reach {@code _block_number}.</li>
 * </ul>
 *
 * <p>
 * Assertions: (1) any untolerated exception is a bug (the crash class -- the narrow tolerance is the catch mechanism);
 * (2) affected-rows consistency: {@code SELECT count() WHERE <pred>} pre-read vs rows actually mutated -- sentinel
 * {@code countIf(marker = 424242)} for UPDATE kinds, count-delta for DELETE kinds. Integer counts only (float noise
 * rule), private single-writer tables, single-INSERT seeding (one part, so background merges cannot move rows between
 * the pre-count and the mutation), plain-MergeTree/Memory engines only (no dedupe-family row collapse). The
 * MATERIALIZE COLUMN arm instead asserts the materialized values equal the column expression recomputed in a SELECT.
 *
 * <p>
 * Construction style follows {@link sqlancer.clickhouse.oracle.patch.ClickHousePatchPartConsistencyOracle}: fresh
 * AtomicLong-suffixed tables per check, DROP in {@code finally}, {@code IgnoreMeException} on tolerated setup failure,
 * hand-built SQL strings throughout (the join AST cannot express derived tables in FROM).
 */
public class ClickHouseMutationAnalyzerOracle implements TestOracle<ClickHouseGlobalState> {

    private static final AtomicLong MUTAN_COUNTER = new AtomicLong();
    private static final String SENTINEL = "424242";

    private enum Kind {
        ALTER_UPDATE, ALTER_DELETE, LIGHTWEIGHT_UPDATE, LIGHTWEIGHT_DELETE, MATERIALIZE_COLUMN
    }

    private enum WhereShape {
        JOINED_DERIVED, // (a) the #106649 trigger
        SELF_REFERENCE, // (b) inner FROM is the mutated table (deadlock-avoidance path)
        PLAIN_IN, // (c) IN over another private table
        ALIAS_COLUMN, // (d) predicate over an ALIAS column
        VIRTUAL_COLUMN // (e) predicate over _part_offset / _block_number; crash coverage only
    }

    private final ClickHouseGlobalState state;
    // Two narrow tolerance sets, deliberately scoped so the catch mechanism stays sharp:
    //   readErrors    -- for CREATE / INSERT / SELECT / DROP (the non-mutation statements).
    //   mutationErrors -- for the mutation execute() ONLY (ALTER/lightweight UPDATE/DELETE,
    //                     MATERIALIZE COLUMN).
    // They differ on exactly two axes (see the constructor): the #106649 pin and the timeout
    // tolerance. Keeping the pin off the read path means a future "is already registered" on a
    // CREATE/SELECT still surfaces; keeping TIMEOUT_EXCEEDED off the mutation path means shape (b)'s
    // deadlock stays catchable, while tolerating it on reads stops a benign load-shed COUNT timeout
    // from writing a misleading SELECT reproducer.
    private final ExpectedErrors readErrors = new ExpectedErrors();
    private final ExpectedErrors mutationErrors = new ExpectedErrors();

    public ClickHouseMutationAnalyzerOracle(ClickHouseGlobalState state) {
        this.state = state;
        // Deliberately NARROW tolerance (PatchPartConsistency precedent): no global expression list
        // -- every statement here is hand-built static SQL, so "Missing columns" / "Ambiguous
        // column" / "Cannot find column"-style messages can only mean an analyzer bug, and they must
        // surface. The shared getMutationErrors() bucket is NOT adopted wholesale either:
        //   - "TIMEOUT_EXCEEDED" is kept OFF the mutation path on purpose: shape (b)'s deadlock class
        //     manifests as a max_execution_time timeout, and tolerating it on the mutation would make
        //     that finding uncatchable. It IS tolerated on the read path (a benign load-shed COUNT
        //     timeout on a squeezed server must not write a misleading SELECT reproducer).
        //   - "Cannot find column" / "Cannot read from" / "Cannot UPDATE key column" / "Cannot
        //     DELETE" / "_row_exists" / "Mutation cannot be executed" / "UNFINISHED_MUTATION" /
        //     "Background mutation" / "ATTEMPT_TO_READ_AFTER_EOF" / projection-mode rejections are
        //     excluded everywhere: none can legitimately fire against this oracle's fixed statements
        //     (the marker target is never a key column, the tables carry no projections), and several
        //     are exactly the analyzer-bug-shaped blind spots this oracle exists to remove.
        // Shared baseline tolerated on BOTH sets, each with a reason:
        for (ExpectedErrors e : List.of(readErrors, mutationErrors)) {
            ClickHouseErrors.addSessionSettingsErrors(e); // unknown setting names on older builds
            // Memory-engine arm on builds where Memory mutations are not (yet) routed/supported.
            e.add("Mutations are not supported by");
            // Lightweight-UPDATE version/engine gating family -- documented limits, not bugs: the LW
            // arm must degrade to IgnoreMe where the feature is gated (same set the generator path
            // tolerates, minus everything analyzer-shaped).
            e.add("Lightweight update");
            e.add("lightweight update");
            e.add("allow_experimental_lightweight_update");
            e.add("SUPPORT_IS_DISABLED");
            e.add("is not supported for lightweight");
            e.add("Lightweight updates are not supported");
            // Per-thread database drop/recreate race (same as the MV / PatchPart oracles): reads
            // hitting a dropped namespace are not analyzer bugs.
            e.add("UNKNOWN_TABLE");
            e.add("Unknown table expression identifier");
            // Code 241 load-shedding under the squeezed dev-vm container cap (-m=6g): any statement
            // -- including the finally-DROP -- can be rejected when CH is at its cgroup limit.
            // Environment artifact, not an analyzer bug (first all-oracles convergence run died 13/15
            // on exactly this). A mutation aborted by it fails sync -> tolerated -> IgnoreMe, so it
            // cannot fake a consistency pass.
            e.add("(MEMORY_LIMIT_EXCEEDED)");
            e.add("memory limit exceeded");
        }
        // Read-path-only: a benign timeout on a setup/verification query under load is not a bug.
        // Kept OFF mutationErrors so shape (b)'s deadlock surfaces as a finding.
        readErrors.add("TIMEOUT_EXCEEDED");
        readErrors.add("Timeout exceeded");
        // Mutation-path-only: known-open filed bugs this matrix reproduces every iteration (#106649:
        // "Column identifier ... is already registered", verified reproducing on head 26.6.1.399 on
        // 2026-06-10). Without the pin, every JOINED_DERIVED iteration kills its worker on the
        // already-filed bug. Kept OFF readErrors so a future "is already registered" on a
        // CREATE/SELECT still surfaces. Same removal condition as the generator-side pin -- see
        // ClickHouseErrors.getKnownOpenMutationAnalyzerBugs.
        for (String pin : ClickHouseErrors.getKnownOpenMutationAnalyzerBugs()) {
            mutationErrors.add(pin);
        }
    }

    @Override
    public void check() throws SQLException {
        long id = MUTAN_COUNTER.incrementAndGet();
        String db = state.getDatabaseName();
        String tA = db + ".mutan_" + id + "_a";
        String tB = db + ".mutan_" + id + "_b";
        String tEdges = db + ".mutan_" + id + "_edges";

        Kind kind = Randomly.fromOptions(Kind.values());
        // Memory-engine stretch arm: ALTER kinds only (lightweight kinds and MATERIALIZE COLUMN are
        // MergeTree mechanisms). Small probability; drop entirely if it churns (plan deferred item).
        boolean memoryEngine = (kind == Kind.ALTER_UPDATE || kind == Kind.ALTER_DELETE)
                && Randomly.getBooleanWithSmallProbability();
        // Patch-enabled MergeTree variant gives shape (e) the _block_number surface.
        boolean patchEnabled = !memoryEngine && Randomly.getBoolean();
        boolean validateMutationQuery = Randomly.getBoolean();

        // MATERIALIZE COLUMN arm: mz is a MATERIALIZED expression column, df a constant-DEFAULT
        // column (the PR's getTableExpressionDataOrNull fix covers the constant case). Both exist on
        // every table; the arm picks which one to materialize.
        String engineClause = memoryEngine ? " ENGINE = Memory"
                : " ENGINE = MergeTree ORDER BY k"
                        + (patchEnabled ? " SETTINGS enable_block_number_column = 1, enable_block_offset_column = 1"
                                : "");
        String createA = "CREATE TABLE " + tA + " (k Int32, v Int64, marker Int64, al Int64 ALIAS (v + 7), "
                + "mz Int64 MATERIALIZED (v * 3 + 1), df Int64 DEFAULT 42)" + engineClause;
        String createB = "CREATE TABLE " + tB + " (k Int32, v Int64) ENGINE = MergeTree ORDER BY k";
        String createEdges = "CREATE TABLE " + tEdges + " (k Int32, v Int64) ENGINE = MergeTree ORDER BY k";

        // Single INSERT per table = one part: background merges cannot reshuffle rows between the
        // pre-count and the mutation, and the consistency arm needs exactly that stability.
        int rowsA = 40 + (int) Randomly.getNotCachedInteger(0, 40);
        String seedA = "INSERT INTO " + tA + " (k, v, marker) SELECT toInt32(number), toInt64(number % 13), "
                + "toInt64(-1) FROM numbers(" + rowsA + ")";
        // k domains overlap partially with tA so IN sets are non-trivial subsets.
        String seedB = "INSERT INTO " + tB + " SELECT toInt32(number * 2), toInt64(number % 7) FROM numbers(25)";
        String seedEdges = "INSERT INTO " + tEdges + " SELECT toInt32(number * 3), toInt64(number % 5) FROM numbers(20)";

        try {
            for (String stmt : List.of(createA, createB, createEdges)) {
                logStmt(stmt);
                if (!new SQLQueryAdapter(stmt, readErrors, true).execute(state)) {
                    throw new IgnoreMeException();
                }
            }
            for (String stmt : List.of(seedA, seedB, seedEdges)) {
                logStmt(stmt);
                if (!new SQLQueryAdapter(stmt, readErrors, true).execute(state)) {
                    throw new IgnoreMeException();
                }
            }

            if (kind == Kind.MATERIALIZE_COLUMN) {
                checkMaterializeColumn(tA, validateMutationQuery);
                return;
            }

            WhereShape shape = pickShape(memoryEngine);
            String pred = renderPredicate(shape, tA, tB, tEdges, patchEnabled);

            // Shape (e) predicates depend on part layout (a mutation rewrites parts and renumbers
            // offsets), so the pre-count vs mutated-rows identity does not hold structurally. Crash
            // coverage only.
            boolean assertConsistency = shape != WhereShape.VIRTUAL_COLUMN;

            String expected = null;
            String countBefore = null;
            if (assertConsistency) {
                String preCount = "SELECT toString(count()) FROM " + tA + " WHERE " + pred;
                logStmt(preCount);
                expected = readSingleValue(preCount);
                if (kind == Kind.ALTER_DELETE || kind == Kind.LIGHTWEIGHT_DELETE) {
                    String total = "SELECT toString(count()) FROM " + tA;
                    logStmt(total);
                    countBefore = readSingleValue(total);
                }
            }

            String mutation = renderMutation(kind, tA, pred, validateMutationQuery);
            logStmt(mutation);
            if (!new SQLQueryAdapter(mutation, mutationErrors, false).execute(state)) {
                // Tolerated rejection (feature gating, Memory-arm support, session setting):
                // abandon the iteration without asserting. With validate_mutation_query=0 more
                // server-side late failures are legitimate, so no assertion there either.
                throw new IgnoreMeException();
            }

            if (!assertConsistency) {
                return;
            }
            if (kind == Kind.ALTER_UPDATE || kind == Kind.LIGHTWEIGHT_UPDATE) {
                String actualQuery = "SELECT toString(countIf(marker = " + SENTINEL + ")) FROM " + tA;
                logStmt(actualQuery);
                String actual = readSingleValue(actualQuery);
                if (!expected.equals(actual)) {
                    throw new AssertionError(String.format(
                            "mutation-analyzer affected-rows mismatch: predicate matched %s rows pre-mutation but "
                                    + "%s rows carry the sentinel after. mutation: %s -- pre-count WHERE: %s",
                            expected, actual, mutation, pred));
                }
            } else {
                String total = "SELECT toString(count()) FROM " + tA;
                logStmt(total);
                String countAfter = readSingleValue(total);
                long deleted = Long.parseLong(countBefore) - Long.parseLong(countAfter);
                if (deleted != Long.parseLong(expected)) {
                    throw new AssertionError(String.format(
                            "mutation-analyzer affected-rows mismatch: predicate matched %s rows pre-mutation but "
                                    + "the DELETE removed %d (count %s -> %s). mutation: %s -- pre-count WHERE: %s",
                            expected, deleted, countBefore, countAfter, mutation, pred));
                }
            }
        } finally {
            for (String t : List.of(tA, tB, tEdges)) {
                try {
                    new SQLQueryAdapter("DROP TABLE IF EXISTS " + t, readErrors, true).execute(state);
                } catch (Exception | AssertionError ignored) {
                    // Best effort. Catch AssertionError too, not just SQLException:
                    // SQLQueryAdapter.execute() throws an AssertionError (not an exception) on an
                    // untolerated error, so a DROP that hits something outside readErrors (e.g. a
                    // transport failure) would otherwise escape this finally and write a misleading
                    // reproducer pointing at a DROP statement, or abort cleanup of the sibling
                    // tables. The disk-cleanup script reaps any orphans the per-thread database may
                    // leave when it is recreated between top-level runs.
                }
            }
        }
    }

    private static WhereShape pickShape(boolean memoryEngine) {
        if (memoryEngine) {
            // Memory has no parts (no virtual part columns -> no VIRTUAL_COLUMN). It is also
            // deliberately restricted to the two shapes that read OTHER tables (JOINED_DERIVED reads
            // the MergeTree tB/tEdges, PLAIN_IN reads tB): SELF_REFERENCE on a Memory table is not
            // guaranteed to evaluate its IN-set against a pre-mutation snapshot (Memory mutations
            // apply synchronously in place), which would break the count-delta identity; and
            // ALIAS_COLUMN resolution inside a Memory mutation WHERE can be rejected with a message
            // outside this oracle's narrow tolerance. Keeping Memory to the cross-table IN shapes
            // preserves the analyzer-routing coverage without those false-positive surfaces.
            return Randomly.fromOptions(WhereShape.JOINED_DERIVED, WhereShape.PLAIN_IN);
        }
        return Randomly.fromOptions(WhereShape.values());
    }

    private static String renderPredicate(WhereShape shape, String tA, String tB, String tEdges,
            boolean patchEnabled) {
        String in = Randomly.getBoolean() ? " IN " : " NOT IN ";
        switch (shape) {
        case JOINED_DERIVED:
            // The exact #106649 form: both derived tables project the same column name (k), two
            // joins, the middle source a plain table -- all numeric, non-correlated.
            return "k" + in + "(SELECT a.k FROM (SELECT k FROM " + tB + ") AS a JOIN " + tEdges
                    + " AS e ON e.k = a.k JOIN (SELECT k FROM " + tEdges + ") AS b ON b.k = e.k)";
        case SELF_REFERENCE:
            // Inner FROM is the mutated table itself: PR #98884's deadlock-avoidance path. The
            // subquery is evaluated against the pre-mutation snapshot, so the consistency identity
            // holds; a deadlock would surface as an (untolerated) max_execution_time timeout.
            return "k" + in + "(SELECT k FROM " + tA + " WHERE v >= " + Randomly.getNotCachedInteger(0, 13) + ")";
        case PLAIN_IN:
            return "k" + in + "(SELECT k FROM " + tB + " WHERE v % 3 = " + Randomly.getNotCachedInteger(0, 3) + ")";
        case ALIAS_COLUMN: {
            // al ALIAS (v + 7); v = number % 13 in [0,12] -> al in [7,19]. Choose the bound per
            // operator so the predicate is never vacuous: "< 7" would match 0 rows on every seed
            // (a coverage hole -- the consistency check passes 0==0 without ever exercising a
            // non-empty UPDATE/DELETE). For "<" the bound is in [8,19] (matches at least al=7);
            // for ">=" / "!=" any bound in [7,19] is non-trivial.
            String op = Randomly.fromOptions(">=", "<", "!=");
            int bound = "<".equals(op) ? 8 + (int) Randomly.getNotCachedInteger(0, 12)
                    : 7 + (int) Randomly.getNotCachedInteger(0, 13);
            return "al " + op + " " + bound;
        }
        case VIRTUAL_COLUMN:
            if (patchEnabled && Randomly.getBoolean()) {
                return "_block_number % 2 = 0";
            }
            return "_part_offset % 2 = " + Randomly.getNotCachedInteger(0, 2);
        default:
            throw new AssertionError(shape);
        }
    }

    private static String renderMutation(Kind kind, String tA, String pred, boolean validate) {
        String validateSetting = "validate_mutation_query = " + (validate ? 1 : 0);
        switch (kind) {
        case ALTER_UPDATE:
            return "ALTER TABLE " + tA + " UPDATE marker = " + SENTINEL + " WHERE " + pred
                    + " SETTINGS mutations_sync = 1, " + validateSetting;
        case ALTER_DELETE:
            return "ALTER TABLE " + tA + " DELETE WHERE " + pred + " SETTINGS mutations_sync = 1, " + validateSetting;
        case LIGHTWEIGHT_UPDATE:
            // Synchronous by design; enable_lightweight_update is the explicit gate.
            return "UPDATE " + tA + " SET marker = " + SENTINEL + " WHERE " + pred
                    + " SETTINGS enable_lightweight_update = 1, " + validateSetting;
        case LIGHTWEIGHT_DELETE:
            // mutations_sync does NOT govern DELETE FROM; lightweight_deletes_sync=2 states the
            // synchronicity explicitly so a future default change can't reintroduce the
            // before/after race.
            return "DELETE FROM " + tA + " WHERE " + pred + " SETTINGS lightweight_deletes_sync = 2, "
                    + validateSetting;
        default:
            throw new AssertionError(kind);
        }
    }

    private void checkMaterializeColumn(String tA, boolean validate) throws SQLException {
        // mz MATERIALIZED (v * 3 + 1) or the constant-DEFAULT column df (DEFAULT 42). MATERIALIZE
        // COLUMN is itself a mutation -- sync + validate settings apply the same way. The arm has no
        // WHERE; its analyzer surface is the column's stored expression.
        boolean constantDefault = Randomly.getBoolean();
        String col = constantDefault ? "df" : "mz";
        String mutation = "ALTER TABLE " + tA + " MATERIALIZE COLUMN " + col + " SETTINGS mutations_sync = 1, "
                + "validate_mutation_query = " + (validate ? 1 : 0);
        logStmt(mutation);
        if (!new SQLQueryAdapter(mutation, mutationErrors, false).execute(state)) {
            throw new IgnoreMeException();
        }
        // Value assertion: the materialized (now stored) values must equal the expression
        // recomputed at read time. Integer-only, so no float noise.
        String mismatchQuery = constantDefault ? "SELECT toString(countIf(df != 42)) FROM " + tA
                : "SELECT toString(countIf(mz != (v * 3 + 1))) FROM " + tA;
        logStmt(mismatchQuery);
        String mismatches = readSingleValue(mismatchQuery);
        if (!"0".equals(mismatches)) {
            throw new AssertionError(String.format(
                    "mutation-analyzer MATERIALIZE COLUMN mismatch: %s rows diverge from the column expression "
                            + "after %s (checked via %s)",
                    mismatches, mutation, mismatchQuery));
        }
    }

    private String readSingleValue(String query) throws SQLException {
        List<String> rows = ComparatorHelper.getResultSetFirstColumnAsString(query, readErrors, state);
        if (rows.size() != 1) {
            throw new IgnoreMeException();
        }
        return rows.get(0);
    }

    private void logStmt(String stmt) {
        if (state.getOptions().logEachSelect()) {
            state.getLogger().writeCurrent(stmt);
            state.getState().logStatement(stmt);
        }
    }
}
