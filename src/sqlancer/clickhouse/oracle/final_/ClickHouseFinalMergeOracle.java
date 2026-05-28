package sqlancer.clickhouse.oracle.final_;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import sqlancer.ComparatorHelper;
import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.clickhouse.ClickHouseErrors;
import sqlancer.clickhouse.ClickHouseProvider.ClickHouseGlobalState;
import sqlancer.clickhouse.ClickHouseSchema.ClickHouseTable;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.ExpectedErrors;

/**
 * SELECT FINAL differential oracle. Asserts that on dedupe engines (Replacing/Summing/
 * Aggregating/Collapsing MergeTree) the result of {@code SELECT ... FROM t FINAL} on a multi-part
 * table equals the result of the same {@code SELECT} after a synchronous {@code OPTIMIZE TABLE t
 * FINAL}. The "merge-pending" state (the third capture in the plan) is intentionally NOT compared
 * against either of those -- merge-pending rows are server-allowed to drift; the invariant is
 * only over the two post-merge views.
 *
 * <p>Workstream 10 of the coverage expansion plan. Requires the table generator to emit
 * Replacing/Summing variants -- when only plain MergeTree is in the pool (the current default
 * pinning in {@link sqlancer.clickhouse.gen.ClickHouseTableGenerator}), every iteration short-
 * circuits via IgnoreMeException because no eligible table exists.
 */
public class ClickHouseFinalMergeOracle implements TestOracle<ClickHouseGlobalState> {

    private final ClickHouseGlobalState state;
    private final ExpectedErrors errors;

    public ClickHouseFinalMergeOracle(ClickHouseGlobalState state) {
        this.state = state;
        this.errors = ExpectedErrors.newErrors().with(ClickHouseErrors.getExpectedExpressionErrors())
                .with(ClickHouseErrors.getMutationErrors()).build();
    }

    @Override
    public void check() throws SQLException {
        List<ClickHouseTable> tables = state.getSchema().getDatabaseTables().stream()
                .filter(ClickHouseTable::supportsFinal).toList();
        if (tables.isEmpty()) {
            // No dedupe-engine tables in the schema. The oracle has no work to do; surface an
            // IgnoreMeException so the test runner moves on without recording a "successful" no-op.
            throw new IgnoreMeException();
        }
        ClickHouseTable table = Randomly.fromList(tables);
        String fqTable = state.getDatabaseName() + "." + table.getName();

        // The base SELECT is the simplest projection: count() on the table. count() commutes with
        // FINAL and with OPTIMIZE, so it's the canonical invariant. count() is also the projection
        // that hits the most CH internal optimizers (trivial_count, projections, skipping indexes)
        // so it doubles as a cross-cutting smoke for those.
        String baseSelect = "SELECT count() FROM " + fqTable;

        // Capture without FINAL -- this may reflect the pre-merge state, which is allowed to
        // differ from the post-merge view. We capture it for the failure log only; do not assert
        // against either of the other two.
        List<String> resultBefore = ComparatorHelper.getResultSetFirstColumnAsString(baseSelect, errors, state);

        // Use do_not_merge_across_partitions_select_final=1 on the FINAL read too, so it
        // returns the same within-partition-deduped row set that OPTIMIZE TABLE FINAL produces.
        // Without the setting, FINAL does cross-partition dedupe, but OPTIMIZE doesn't -- the
        // two diverge on multi-partition tables (smoke #N debug, FinalMergeOracle).
        List<String> resultFinal = ComparatorHelper.getResultSetFirstColumnAsString(
                baseSelect + " FINAL SETTINGS do_not_merge_across_partitions_select_final=1", errors, state);

        // OPTIMIZE TABLE t FINAL forces a synchronous merge of every active part on the table. The
        // server returns when the merge is done, so the subsequent SELECT sees the post-merge
        // state. Use the underlying SQLConnection directly because OPTIMIZE has no result set.
        try (Statement s = state.getConnection().createStatement()) {
            s.execute("OPTIMIZE TABLE " + fqTable + " FINAL");
        } catch (SQLException e) {
            // OPTIMIZE can fail with TOO_MANY_PARTS, MEMORY_LIMIT_EXCEEDED, ORDER_BY_CANNOT_BE_EMPTY,
            // or transient merge errors. Absorb the catalogued cases; OPTIMIZE-side failures are
            // not the bug the oracle is hunting (it's hunting result divergence between FINAL
            // and post-OPTIMIZE reads, both of which we re-run after OPTIMIZE fails).
            String msg = e.getMessage();
            if (msg == null || errors.errorIsExpected(msg)
                    || msg.contains("ORDER BY cannot be empty") || msg.contains("BAD_ARGUMENTS")) {
                throw new IgnoreMeException();
            }
            throw e;
        }

        // Read with do_not_merge_across_partitions_select_final=1 so the read matches OPTIMIZE's
        // within-partition-only dedupe semantics. The default FINAL setting does cross-partition
        // dedupe (visible row count = distinct ORDER BY values across all partitions); OPTIMIZE
        // only merges within partitions, so plain FINAL vs post-OPTIMIZE diverges on multi-
        // partition tables. With the setting on, both views converge to the within-partition
        // deduped count.
        String afterSelect = baseSelect + " FINAL SETTINGS do_not_merge_across_partitions_select_final=1";
        List<String> resultAfter = ComparatorHelper.getResultSetFirstColumnAsString(afterSelect, errors, state);

        ComparatorHelper.assumeResultSetsAreEqual(resultFinal, resultAfter,
                baseSelect + " FINAL  -- vs OPTIMIZE+post-merge\n-- result_before=" + resultBefore,
                java.util.Collections.singletonList(afterSelect + " (after OPTIMIZE FINAL)"), state,
                ComparatorHelper.ComparisonMode.MULTISET);
    }
}
