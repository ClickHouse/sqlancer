package sqlancer.clickhouse.oracle.patch;

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
 * Lightweight-UPDATE patch-part consistency oracle.
 *
 * <p>
 * A lightweight {@code UPDATE ... SET} against a MergeTree table that carries
 * {@code enable_block_number_column}/{@code enable_block_offset_column} writes an <em>unmerged patch part</em> instead
 * of rewriting the data part. Reads then apply the patch on the fly ({@code apply_patch_parts = 1}, default). This is
 * the surface behind the ClickHouse support escalation #7912 and its upstream root cause #98227 / fix-history #99023 /
 * #102904 / #103910: a read-in-order + {@code LIMIT} query projecting a non-sort-key ("wide") column, executed under
 * {@code query_plan_optimize_lazy_materialization}, passed an empty {@code read_sample_block} to {@code readPatches} and
 * crashed with {@code NOT_FOUND_COLUMN_IN_BLOCK} ({@code _block_number}) / a sibling {@code _part_offset}
 * {@code LOGICAL_ERROR}, while patch parts were still unmerged.
 *
 * <p>
 * The oracle is fully self-contained and deterministic (same construction style as
 * {@link sqlancer.clickhouse.oracle.view.ClickHouseMaterializedViewConsistencyOracle}): it creates a fresh
 * patch-eligible table, fills it across several single-INSERT parts, fires a few lightweight {@code UPDATE}s to leave
 * live patch parts, then asserts two invariants that both held on CH HEAD 26.6.1.399 (verified) and fail exactly when
 * this regresses:
 *
 * <ol>
 * <li><b>Lazy-materialization invariant / crash detector.</b> The read-in-order + {@code LIMIT} query projecting the
 * wide column returns the identical result whether {@code query_plan_optimize_lazy_materialization} is 1 or 0. A
 * regression of the escalation bug surfaces as an <em>untolerated</em> {@code NOT_FOUND_COLUMN_IN_BLOCK} /
 * {@code _part_offset} exception (worker dies, reproducer written) before the equality check even runs; a silent
 * wrong-result surfaces as a divergence.</li>
 * <li><b>Patch-apply invariant.</b> The same query returns the identical result with patches applied on the fly
 * ({@code apply_patch_parts = 1} over live patch parts) and after {@code OPTIMIZE TABLE ... FINAL} has physically merged
 * the patches in. A wrong on-the-fly patch apply (the eventual-consistency / staleness compromise the customer
 * flagged) diverges here.</li>
 * </ol>
 *
 * Invariant 1 runs before invariant 2 because the {@code OPTIMIZE FINAL} in (2) destroys the patch parts that (1)
 * depends on. The table is dropped in a {@code finally}; each name embeds a process-wide counter so concurrent workers
 * never collide. Only deterministic, exact-typed projections are compared (the wide column is a {@code String}, the
 * key/numeric columns are integers), so neither invariant can false-positive on float rounding or NaN grouping.
 */
public class ClickHousePatchPartConsistencyOracle implements TestOracle<ClickHouseGlobalState> {

    private static final AtomicLong PATCH_COUNTER = new AtomicLong();

    private final ClickHouseGlobalState state;
    private final ExpectedErrors errors = new ExpectedErrors();

    public ClickHousePatchPartConsistencyOracle(ClickHouseGlobalState state) {
        this.state = state;
        // Deliberately NARROW tolerance. We do NOT call addExpectedExpressionErrors() here: that
        // global list tolerates "(NOT_FOUND_COLUMN_IN_BLOCK)" / "in block. There are only columns:"
        // (for generator-induced JOIN/alias column misses) -- which is EXACTLY the escalation crash
        // signature (#7912: "Not found column _block_number in block ... (NOT_FOUND_COLUMN_IN_BLOCK)").
        // This oracle's CREATE/INSERT/UPDATE/SELECT are all hand-built static SQL with no generated
        // expressions, so it has no legitimate need for that list -- and omitting it is what lets a
        // regression of the patch-part crash actually surface (untolerated -> worker dies ->
        // reproducer) instead of being swallowed. Only the genuinely-benign failure modes of these
        // fixed statements are tolerated below.
        ClickHouseErrors.addSessionSettingsErrors(errors); // unknown setting on an older build
        ClickHouseErrors.addMutationErrors(errors); // lightweight UPDATE restrictions / OPTIMIZE timeout
        // Same per-thread database drop/recreate race tolerated by the MV oracle: if the database
        // is recreated under us after the table was created, the reads hit UNKNOWN_TABLE -- not a
        // patch-part consistency bug. Abandon the iteration rather than report it.
        errors.add("UNKNOWN_TABLE");
        errors.add("Unknown table expression identifier");
    }

    @Override
    public void check() throws SQLException {
        long id = PATCH_COUNTER.incrementAndGet();
        String db = state.getDatabaseName();
        String t = db + ".patchpart_" + id;

        // Layout: k is the sole sort key, w is the wide non-sort-key String column the read-in-order
        // + LIMIT query projects (the one that drives lazy materialization), v a numeric column also
        // touched by an update. Small index_granularity keeps several granules per part so the
        // read-in-order path actually exercises granule boundaries.
        int granularity = Randomly.fromOptions(8, 16, 128, 8192);
        String createTable = "CREATE TABLE " + t + " (k Int32, v Int64, w String) ENGINE = MergeTree ORDER BY k "
                + "SETTINGS enable_block_number_column = 1, enable_block_offset_column = 1, index_granularity = "
                + granularity;
        String dropTable = "DROP TABLE IF EXISTS " + t;

        // A few single-INSERT parts so patch application spans multiple data parts.
        int parts = 2 + (int) Randomly.getNotCachedInteger(0, 3);
        int rowsPerPart = 200 + (int) Randomly.getNotCachedInteger(0, 800);

        if (state.getOptions().logEachSelect()) {
            for (String stmt : List.of(dropTable, createTable)) {
                state.getLogger().writeCurrent(stmt);
                state.getState().logStatement(stmt);
            }
        }

        try {
            if (!new SQLQueryAdapter(createTable, errors, true).execute(state)) {
                throw new IgnoreMeException();
            }
            for (int p = 0; p < parts; p++) {
                long offset = (long) p * rowsPerPart;
                String insert = "INSERT INTO " + t + " SELECT toInt32(number) AS k, toInt64(number) AS v, "
                        + "toString(number) AS w FROM numbers(" + offset + ", " + rowsPerPart + ")";
                logStmt(insert);
                if (!new SQLQueryAdapter(insert, errors, true).execute(state)) {
                    throw new IgnoreMeException();
                }
            }

            // Fire a few lightweight UPDATEs WITHOUT optimizing -- each leaves an unmerged patch
            // part. Mixed targets (the wide String column and the numeric column) and disjoint-ish
            // predicates so the patches overlap different rows. enable_lightweight_update=1 is the
            // explicit gate (no-op where lightweight UPDATE is on by default on HEAD).
            int divA = 3 + (int) Randomly.getNotCachedInteger(0, 5);
            int divB = 4 + (int) Randomly.getNotCachedInteger(0, 5);
            List<String> updates = List.of(
                    "UPDATE " + t + " SET w = concat('p', toString(k)) WHERE k % " + divA
                            + " = 0 SETTINGS enable_lightweight_update = 1",
                    "UPDATE " + t + " SET v = v + 1000 WHERE k % " + divB
                            + " = 0 SETTINGS enable_lightweight_update = 1");
            for (String upd : updates) {
                logStmt(upd);
                // A lightweight UPDATE may be rejected / rewritten as a heavy mutation on builds or
                // engines that don't support patch parts -- tolerated. execute()==false means a
                // tolerated error fired; we keep going (the precondition below decides whether the
                // run is meaningful).
                new SQLQueryAdapter(upd, errors, false).execute(state);
            }

            // Precondition: at least one live patch part must exist, otherwise this iteration never
            // engaged the surface (the update was rewritten or the build doesn't produce patches).
            // The invariants would pass vacuously; skip to avoid false confidence and wasted compares.
            String patchCount = "SELECT toString(countIf(startsWith(name, 'patch'))) FROM system.parts WHERE database = '"
                    + db + "' AND table = 'patchpart_" + id + "' AND active";
            List<String> pc = ComparatorHelper.getResultSetFirstColumnAsString(patchCount, errors, state);
            if (pc.size() != 1 || "0".equals(pc.get(0))) {
                throw new IgnoreMeException();
            }

            // Whole row collapsed into one String column so the first-column comparator can diff it;
            // the concat flows through every variant identically, so only a genuine divergence trips.
            String rowExpr = "concat(toString(k), '#', toString(v), '#', w)";
            String dir = Randomly.getBoolean() ? " DESC" : " ASC";
            int limit = 10 + (int) Randomly.getNotCachedInteger(0, 200);
            // Read-in-order (ORDER BY the sort key) + LIMIT projecting the wide non-key column -- the
            // exact shape that triggers lazy materialization over patch parts.
            String readBase = "SELECT " + rowExpr + " FROM " + t + " ORDER BY k" + dir + " LIMIT " + limit;

            // --- Invariant 1: lazy-materialization on vs off (also the crash detector) ---
            String lazyOn = readBase + " SETTINGS query_plan_optimize_lazy_materialization = 1, apply_patch_parts = 1";
            String lazyOff = readBase + " SETTINGS query_plan_optimize_lazy_materialization = 0, apply_patch_parts = 1";
            logStmt(lazyOn);
            logStmt(lazyOff);
            List<String> onRows = ComparatorHelper.getResultSetFirstColumnAsString(lazyOn, errors, state);
            List<String> offRows = ComparatorHelper.getResultSetFirstColumnAsString(lazyOff, errors, state);
            ComparatorHelper.assumeResultSetsAreEqual(onRows, offRows, lazyOn, List.of(lazyOff), state);

            // --- Invariant 2: patches applied on the fly vs after OPTIMIZE FINAL materialization ---
            // Capture the on-the-fly result over LIVE patch parts first; FINAL then physically merges
            // them in, and the re-read must match. (FINAL destroys the patch parts, so this must run
            // after invariant 1.)
            String applyRead = readBase + " SETTINGS apply_patch_parts = 1";
            logStmt(applyRead);
            List<String> preRows = ComparatorHelper.getResultSetFirstColumnAsString(applyRead, errors, state);
            String optimize = "OPTIMIZE TABLE " + t + " FINAL";
            logStmt(optimize);
            if (!new SQLQueryAdapter(optimize, errors, false).execute(state)) {
                throw new IgnoreMeException();
            }
            List<String> postRows = ComparatorHelper.getResultSetFirstColumnAsString(applyRead, errors, state);
            ComparatorHelper.assumeResultSetsAreEqual(preRows, postRows, applyRead, List.of(optimize), state);
        } finally {
            try {
                new SQLQueryAdapter(dropTable, errors, true).execute(state);
            } catch (SQLException ignored) {
                // Best effort -- the per-thread database may be dropped between top-level runs.
            }
        }
    }

    private void logStmt(String stmt) {
        if (state.getOptions().logEachSelect()) {
            state.getLogger().writeCurrent(stmt);
            state.getState().logStatement(stmt);
        }
    }
}
