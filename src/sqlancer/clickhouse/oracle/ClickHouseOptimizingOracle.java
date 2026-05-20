package sqlancer.clickhouse.oracle;

import sqlancer.Reproducer;
import sqlancer.clickhouse.ClickHouseProvider.ClickHouseGlobalState;
import sqlancer.clickhouse.ClickHouseSchema.ClickHouseTable;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;

// Forces Replacing/Summing/Aggregating/Collapsing/VersionedCollapsing tables to a fully-merged
// state before each oracle iteration. Those engines collapse same-ORDER-BY-key rows only at
// merge time, so two consecutive SELECTs against the same table can return different counts if
// a background merge fires between them -- producing TLP-WHERE false positives where the
// partition union sees the converged count while the unpartitioned scan saw the pre-merge
// count. Repros from the 2026-05-20 25-oracle run: db13 (240 vs 208 on `SELECT * FROM t1, t0`
// against a `SummingMergeTree(c0 String) ORDER BY c0` t0 with 13 distinct values).
//
// `OPTIMIZE TABLE ... FINAL` is gated on supportsFinal(): plain MergeTree raises ILLEGAL_FINAL
// and is skipped. After the first iteration there are typically no pending merges so subsequent
// calls are server-side no-ops -- the cost is one round-trip per dedupe table per iteration.
// Optimize errors (TIMEOUT_EXCEEDED on slow merges, UNKNOWN_TABLE on a just-dropped table) are
// swallowed: the inner oracle proceeds and either hits the same condition cleanly or passes.
public final class ClickHouseOptimizingOracle implements TestOracle<ClickHouseGlobalState> {

    private final ClickHouseGlobalState state;
    private final TestOracle<ClickHouseGlobalState> inner;

    private static final ExpectedErrors OPTIMIZE_EXPECTED_ERRORS = ExpectedErrors.from("TIMEOUT_EXCEEDED",
            "TOO_MANY_PARTS", "PART_IS_TEMPORARILY_LOCKED", "UNKNOWN_TABLE", "ABORTED");

    public ClickHouseOptimizingOracle(ClickHouseGlobalState state, TestOracle<ClickHouseGlobalState> inner) {
        this.state = state;
        this.inner = inner;
    }

    @Override
    public void check() throws Exception {
        optimizeDedupeTables();
        inner.check();
    }

    @Override
    public Reproducer<ClickHouseGlobalState> getLastReproducer() {
        return inner.getLastReproducer();
    }

    @Override
    public String getLastQueryString() {
        return inner.getLastQueryString();
    }

    private void optimizeDedupeTables() {
        try {
            for (ClickHouseTable t : state.getSchema().getDatabaseTables()) {
                if (!t.supportsFinal()) {
                    continue;
                }
                SQLQueryAdapter q = new SQLQueryAdapter("OPTIMIZE TABLE " + t.getName() + " FINAL",
                        OPTIMIZE_EXPECTED_ERRORS);
                try {
                    state.executeStatement(q);
                } catch (Throwable ignored) {
                    // Optimize failure does not affect oracle correctness, only the determinism
                    // guarantee. Continue to the next table; the iteration may still pass.
                }
            }
        } catch (Throwable ignored) {
            // Schema fetch failure is also non-fatal -- the inner oracle hits the same condition
            // and IgnoreMes in the normal path.
        }
    }
}
