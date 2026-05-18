package sqlancer.clickhouse.oracle.rowpolicy;

import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import sqlancer.ComparatorHelper;
import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.clickhouse.ClickHouseErrors;
import sqlancer.clickhouse.ClickHouseProvider.ClickHouseGlobalState;
import sqlancer.clickhouse.ClickHouseSchema;
import sqlancer.clickhouse.ClickHouseSchema.ClickHouseTable;
import sqlancer.clickhouse.ClickHouseVisitor;
import sqlancer.clickhouse.ast.ClickHouseColumnReference;
import sqlancer.clickhouse.ast.ClickHouseExpression;
import sqlancer.clickhouse.ast.ClickHouseSelect;
import sqlancer.clickhouse.ast.ClickHouseTableReference;
import sqlancer.clickhouse.gen.ClickHouseExpressionGenerator;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;

/**
 * Differential oracle for ClickHouse row policies.
 *
 * <p>
 * A permissive row policy {@code CREATE ROW POLICY rp ON t USING p TO user} must filter the result of
 * {@code SELECT cols FROM t} identically to manually appending {@code WHERE p}. The bug class we are after lives in the
 * interaction between row policies and other filter-rewriting code paths (PREWHERE, FINAL, skip indexes,
 * partition-pruning) -- see ClickHouse#97076 "Fix partition pruning (indexes) for row policy/PREWHERE with FINAL".
 * Without exercising row policies explicitly, no oracle reaches that surface.
 *
 * <p>
 * Per check():
 *
 * <ol>
 * <li>Pick a non-empty table and generate a predicate {@code p}.</li>
 * <li>Compute the baseline by running {@code SELECT col FROM t WHERE p} -- this is the row set that the policy *should*
 * produce, computed using the well-tested WHERE-filter code path.</li>
 * <li>Create a row policy {@code rp ON t USING p TO CURRENT_USER}.</li>
 * <li>Run {@code SELECT col FROM t} (no explicit WHERE) -- the row policy applies transparently.</li>
 * <li>Drop the row policy.</li>
 * <li>Assert the two row multisets are equal.</li>
 * </ol>
 *
 * <p>
 * The policy is dropped in a {@code finally} block so a comparison failure does not strand a policy on the table for
 * the rest of the run. Each policy name embeds a process-wide monotonic counter so concurrent invocations from
 * different worker threads on isolated databases cannot collide.
 *
 * <p>
 * The oracle absorbs {@code CREATE ROW POLICY} setup errors (e.g., {@code ACCESS_DENIED} on locked-down clusters) as
 * {@link IgnoreMeException} -- a permissioning gap is not a correctness bug.
 */
public class ClickHouseRowPolicyOracle implements TestOracle<ClickHouseGlobalState> {

    private static final AtomicLong POLICY_COUNTER = new AtomicLong();

    private final ClickHouseGlobalState state;
    private final ExpectedErrors errors = new ExpectedErrors();

    public ClickHouseRowPolicyOracle(ClickHouseGlobalState state) {
        this.state = state;
        ClickHouseErrors.addExpectedExpressionErrors(errors);
        ClickHouseErrors.addSessionSettingsErrors(errors);
    }

    @Override
    public void check() throws SQLException {
        ClickHouseSchema schema = state.getSchema();
        List<ClickHouseTable> tables = schema.getRandomTableNonEmptyTables().getTables();
        if (tables.isEmpty()) {
            throw new IgnoreMeException();
        }
        ClickHouseTable table = tables.get((int) Randomly.getNotCachedInteger(0, tables.size()));
        ClickHouseTableReference tableRef = new ClickHouseTableReference(table, null);
        List<ClickHouseColumnReference> columns = tableRef.getColumnReferences();
        if (columns.isEmpty()) {
            throw new IgnoreMeException();
        }

        ClickHouseExpressionGenerator gen = new ClickHouseExpressionGenerator(state).allowAggregates(false);
        gen.addColumns(columns);
        ClickHouseExpression predicate = gen.generatePredicate();
        ClickHouseColumnReference projectionCol = columns.get(0);

        // Step 1: baseline via WHERE p (no policy yet).
        ClickHouseSelect baseline = new ClickHouseSelect();
        baseline.setFromClause(tableRef);
        baseline.setFetchColumns(List.of((ClickHouseExpression) projectionCol));
        baseline.setWhereClause(predicate);
        String baselineQuery = ClickHouseVisitor.asString(baseline);
        List<String> baselineRows;
        try {
            baselineRows = ComparatorHelper.getResultSetFirstColumnAsString(baselineQuery, errors, state);
        } catch (IgnoreMeException e) {
            // If the predicate itself blows up (type mismatch, malformed function) the bug isn't
            // here -- skip and let another iteration try a fresher predicate.
            throw e;
        }

        // Step 2: build the policy DDL.
        String policyName = "rp_" + table.getName() + "_" + POLICY_COUNTER.incrementAndGet();
        String fqTable = state.getDatabaseName() + "." + table.getName();
        String predicateSql = ClickHouseVisitor.asString(predicate);
        String createPolicy = "CREATE ROW POLICY " + policyName + " ON " + fqTable + " USING (" + predicateSql
                + ") TO CURRENT_USER";
        String dropPolicy = "DROP ROW POLICY IF EXISTS " + policyName + " ON " + fqTable;

        // Step 3: install policy. On servers where the connecting user lacks ACCESS_MANAGEMENT,
        // CREATE ROW POLICY fails -- treat that as "oracle inapplicable for this deployment",
        // not as a bug.
        try {
            new SQLQueryAdapter(createPolicy, errors, false).execute(state);
        } catch (SQLException e) {
            // Most ACCESS_DENIED variants stringify with one of these substrings; skip the
            // iteration rather than failing the oracle.
            String msg = String.valueOf(e.getMessage());
            if (msg.contains("ACCESS_DENIED") || msg.contains("Not enough privileges")
                    || msg.contains("CREATE_ROW_POLICY")) {
                throw new IgnoreMeException();
            }
            throw e;
        }

        try {
            // Step 4: read through the policy.
            ClickHouseSelect filtered = new ClickHouseSelect();
            filtered.setFromClause(tableRef);
            filtered.setFetchColumns(List.of((ClickHouseExpression) projectionCol));
            filtered.setWhereClause(null);
            String filteredQuery = ClickHouseVisitor.asString(filtered);
            List<String> filteredRows = ComparatorHelper.getResultSetFirstColumnAsString(filteredQuery, errors, state);

            // Step 5 (assertion): the policy USING p must produce the same row multiset as
            // explicit WHERE p. Result-set ordering is irrelevant -- ComparatorHelper compares as
            // multisets (sorted) by default. Use the canonical helper for failure reporting parity
            // with the rest of the suite.
            ComparatorHelper.assumeResultSetsAreEqual(baselineRows, filteredRows, baselineQuery, List.of(filteredQuery),
                    state);
        } finally {
            // Always attempt drop. A failed drop is logged but not promoted to an assertion -- the
            // database is dropped at end-of-iteration anyway (ClickHouseProvider.createDatabase
            // recreates from scratch), so a leaked policy can only affect later iterations within
            // the same database instance. Best-effort is appropriate.
            try {
                new SQLQueryAdapter(dropPolicy, errors, false).execute(state);
            } catch (SQLException ignored) {
                // Leave the policy in place -- the database lifecycle will clean it up.
            }
        }
    }

}
