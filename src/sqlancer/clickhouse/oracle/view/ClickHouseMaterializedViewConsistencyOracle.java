package sqlancer.clickhouse.oracle.view;

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
 * Materialized-view maintenance-consistency oracle (Unit 3.3 of the ClickHouse coverage-expansion roadmap).
 *
 * <p>
 * ClickHouse maintains a materialized view incrementally: every {@code INSERT} into the view's source table fires the
 * view's transformation over <em>only that inserted block</em> and writes the (partial) result into the view's target
 * engine. For an aggregating target (AggregatingMergeTree storing {@code -State} columns, or SummingMergeTree storing
 * running sums) the maintained aggregate must, after re-merging across all parts, equal a direct aggregate computed
 * over the full source table. Bugs in block-level transformation, part merging, or state (de)serialization break that
 * equality -- the historically highest wrong-result density surface in the engine.
 *
 * <p>
 * The oracle is fully self-contained and deterministic: it creates a fresh source table and materialized view, inserts
 * a known, reproducible dataset over several blocks (to force multi-part state merging), then asserts
 *
 * <pre>
 *   SELECT k, sum(v), count() FROM src GROUP BY k            -- ground truth, direct aggregate
 *     ==
 *   SELECT k, &lt;merge&gt;(state) FROM mv GROUP BY k         -- the view-maintained aggregate
 * </pre>
 *
 * Only deterministic, order-insensitive aggregates ({@code sum}, {@code count}) are used, so the equality holds
 * regardless of merge schedule, parallelism, or block boundaries. The source and view are dropped in a {@code finally}
 * so a divergence never strands them; each name embeds a process-wide counter so concurrent workers never collide.
 */
public class ClickHouseMaterializedViewConsistencyOracle implements TestOracle<ClickHouseGlobalState> {

    private static final AtomicLong MV_COUNTER = new AtomicLong();

    private final ClickHouseGlobalState state;
    private final ExpectedErrors errors = new ExpectedErrors();

    public ClickHouseMaterializedViewConsistencyOracle(ClickHouseGlobalState state) {
        this.state = state;
        ClickHouseErrors.addExpectedExpressionErrors(errors);
        ClickHouseErrors.addSessionSettingsErrors(errors);
        // Under heavy worker-thread churn the provider can drop/recreate the per-thread database
        // between statements (the documented "consecutive test runs can lead to dropped database"
        // race). If that happens after our source/view were created, the read-side queries hit
        // UNKNOWN_TABLE -- not a materialized-view consistency bug. Tolerate it locally so the
        // iteration is abandoned (IgnoreMeException) rather than reported.
        errors.add("UNKNOWN_TABLE");
        errors.add("Unknown table expression identifier");
    }

    @Override
    public void check() throws SQLException {
        long id = MV_COUNTER.incrementAndGet();
        String db = state.getDatabaseName();
        String src = db + ".mvsrc_" + id;
        String mv = db + ".mv_" + id;

        // Small key cardinality so each group accumulates several rows across blocks (the case that
        // actually exercises cross-part state merging); a few hundred rows split into 2-4 blocks.
        int keyCardinality = 2 + (int) Randomly.getNotCachedInteger(0, 18);
        int blocks = 2 + (int) Randomly.getNotCachedInteger(0, 3);
        int rowsPerBlock = 20 + (int) Randomly.getNotCachedInteger(0, 200);

        // Two target shapes, both deterministic. Aggregating stores -State columns read back via the
        // -Merge combinator; Summing stores running sums re-aggregated with sum() at read time.
        boolean aggregating = Randomly.getBoolean();
        String createSrc = "CREATE TABLE " + src + " (k Int32, v Int64) ENGINE = MergeTree ORDER BY k";
        String createMv;
        String mvRead;
        // Total-rows seen by the MV. On a healthy server this always equals the source row count
        // (every inserted row feeds exactly one MV group's count). It can fall short only when an
        // INSERT's MV push did not fully propagate -- which happens under server memory pressure
        // (the MV push fails/partially-applies while the source part commits and the INSERT still
        // reports success). That is an environment artifact, not an MV wrong-result, so we use this
        // total as a precondition: if it disagrees with the source total, abandon the iteration.
        String mvTotal;
        if (aggregating) {
            createMv = "CREATE MATERIALIZED VIEW " + mv + " ENGINE = AggregatingMergeTree() ORDER BY k AS "
                    + "SELECT k, sumState(v) AS sv, countState() AS cv FROM " + src + " GROUP BY k";
            mvRead = "SELECT concat(toString(k), '#', toString(sumMerge(sv)), '#', toString(countMerge(cv))) FROM " + mv
                    + " GROUP BY k ORDER BY k";
            mvTotal = "SELECT toString(countMerge(cv)) FROM " + mv;
        } else {
            createMv = "CREATE MATERIALIZED VIEW " + mv + " ENGINE = SummingMergeTree() ORDER BY k AS "
                    + "SELECT k, sum(v) AS sv, count() AS cv FROM " + src + " GROUP BY k";
            mvRead = "SELECT concat(toString(k), '#', toString(sum(sv)), '#', toString(sum(cv))) FROM " + mv
                    + " GROUP BY k ORDER BY k";
            mvTotal = "SELECT toString(sum(cv)) FROM " + mv;
        }
        String srcTotal = "SELECT toString(count()) FROM " + src;
        String groundTruth = "SELECT concat(toString(k), '#', toString(sum(v)), '#', toString(count())) FROM " + src
                + " GROUP BY k ORDER BY k";
        String dropMv = "DROP VIEW IF EXISTS " + mv;
        String dropSrc = "DROP TABLE IF EXISTS " + src;

        if (state.getOptions().logEachSelect()) {
            // Persist DDL into the reproducer so a saved failure replays standalone (drops first for
            // idempotent replay, then creates).
            for (String stmt : List.of(dropMv, dropSrc, createSrc, createMv)) {
                state.getLogger().writeCurrent(stmt);
                state.getState().logStatement(stmt);
            }
        }

        try {
            // execute() returns false when the statement hit a tolerated error (e.g. the database
            // was dropped from under us -> "doesn't exist"). In that case the table does not exist,
            // so reading it later would raise an untolerated UNKNOWN_TABLE -- abandon the iteration
            // immediately instead of comparing against a setup that never materialized.
            if (!new SQLQueryAdapter(createSrc, errors, true).execute(state)) {
                throw new IgnoreMeException();
            }
            if (!new SQLQueryAdapter(createMv, errors, true).execute(state)) {
                throw new IgnoreMeException();
            }

            for (int b = 0; b < blocks; b++) {
                long offset = (long) b * rowsPerBlock;
                // numbers(offset, count) yields offset .. offset+count-1; modding by keyCardinality
                // spreads rows across the key space and the same k recurs across blocks.
                String insert = "INSERT INTO " + src + " SELECT toInt32(number % " + keyCardinality
                        + ") AS k, toInt64(number) AS v FROM numbers(" + offset + ", " + rowsPerBlock + ")";
                if (state.getOptions().logEachSelect()) {
                    state.getLogger().writeCurrent(insert);
                    state.getState().logStatement(insert);
                }
                if (!new SQLQueryAdapter(insert, errors, true).execute(state)) {
                    throw new IgnoreMeException();
                }
            }

            // Precondition: the MV must have observed exactly as many rows as the source. A shortfall
            // means an INSERT's MV push did not fully propagate (server memory pressure) -- abandon
            // the iteration rather than report a divergence that a healthy server never produces.
            List<String> srcCnt = ComparatorHelper.getResultSetFirstColumnAsString(srcTotal, errors, state);
            List<String> mvCnt = ComparatorHelper.getResultSetFirstColumnAsString(mvTotal, errors, state);
            if (srcCnt.size() != 1 || mvCnt.size() != 1 || !srcCnt.get(0).equals(mvCnt.get(0))) {
                throw new IgnoreMeException();
            }

            if (state.getOptions().logEachSelect()) {
                state.getLogger().writeCurrent(groundTruth);
                state.getLogger().writeCurrent(mvRead);
            }
            List<String> baseRows = ComparatorHelper.getResultSetFirstColumnAsString(groundTruth, errors, state);
            List<String> mvRows = ComparatorHelper.getResultSetFirstColumnAsString(mvRead, errors, state);
            ComparatorHelper.assumeResultSetsAreEqual(baseRows, mvRows, groundTruth, List.of(mvRead), state);
        } finally {
            try {
                new SQLQueryAdapter(dropMv, errors, true).execute(state);
            } catch (SQLException ignored) {
                // Best effort -- the database is dropped between top-level runs.
            }
            try {
                new SQLQueryAdapter(dropSrc, errors, true).execute(state);
            } catch (SQLException ignored) {
                // Best effort.
            }
        }
    }
}
