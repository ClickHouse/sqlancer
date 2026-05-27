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

        List<String> resultFinal = ComparatorHelper.getResultSetFirstColumnAsString(baseSelect + " FINAL", errors,
                state);

        // OPTIMIZE TABLE t FINAL forces a synchronous merge of every active part on the table. The
        // server returns when the merge is done, so the subsequent SELECT sees the post-merge
        // state. Use the underlying SQLConnection directly because OPTIMIZE has no result set.
        try (Statement s = state.getConnection().createStatement()) {
            s.execute("OPTIMIZE TABLE " + fqTable + " FINAL");
        } catch (SQLException e) {
            // OPTIMIZE can fail with TOO_MANY_PARTS, MEMORY_LIMIT_EXCEEDED, or transient merge
            // errors. The matching tolerances are already on the ExpectedErrors set; if the
            // exception message matches one of them, the iteration is uninformative.
            if (errors.errorIsExpected(e.getMessage())) {
                throw new IgnoreMeException();
            }
            throw e;
        }

        List<String> resultAfter = ComparatorHelper.getResultSetFirstColumnAsString(baseSelect, errors, state);

        ComparatorHelper.assumeResultSetsAreEqual(resultFinal, resultAfter,
                baseSelect + " FINAL  -- vs OPTIMIZE+post-merge\n-- result_before=" + resultBefore,
                java.util.Collections.singletonList(baseSelect + " (after OPTIMIZE FINAL)"), state,
                ComparatorHelper.ComparisonMode.MULTISET);
    }
}
