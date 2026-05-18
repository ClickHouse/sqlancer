package sqlancer.clickhouse.oracle.view;

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
import sqlancer.clickhouse.ast.ClickHouseBinaryLogicalOperation;
import sqlancer.clickhouse.ast.ClickHouseBinaryLogicalOperation.ClickHouseBinaryLogicalOperator;
import sqlancer.clickhouse.ast.ClickHouseColumnReference;
import sqlancer.clickhouse.ast.ClickHouseExpression;
import sqlancer.clickhouse.ast.ClickHouseSelect;
import sqlancer.clickhouse.ast.ClickHouseTableReference;
import sqlancer.clickhouse.gen.ClickHouseExpressionGenerator;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;

/**
 * Differential oracle for normal views.
 *
 * <p>
 * A normal view {@code v AS SELECT * FROM t WHERE p_view} composes with a query-level predicate exactly the same as the
 * inlined form. The bug class lives in the analyzer's view-expansion + filter-pushdown interaction -- see #100390 "view
 * returning wrong results after DETACH/ATTACH with UNION and INTERSECT" -- and the only way to reach that code path is
 * to actually emit a CREATE VIEW and read through it.
 *
 * <p>
 * Per check():
 *
 * <ol>
 * <li>Pick a base table with at least one column.</li>
 * <li>Generate a "view predicate" {@code p_view} and a "query predicate" {@code p_q}.</li>
 * <li>Compute the inlined baseline: {@code SELECT col FROM t WHERE (p_view) AND (p_q)}. This is the row set the view
 * read *should* produce.</li>
 * <li>Create {@code v AS SELECT * FROM t WHERE p_view}.</li>
 * <li>Read {@code SELECT col FROM v WHERE p_q}.</li>
 * <li>Drop the view.</li>
 * <li>Assert the two row multisets are equal.</li>
 * </ol>
 *
 * <p>
 * The view is dropped in a {@code finally} so a comparison failure does not strand it. Each view name embeds a
 * process-wide monotonic counter so concurrent invocations from different worker threads do not collide.
 *
 * <p>
 * Aggregates in {@code fetchColumns} would collapse the row set in a way that hides whether the view's filter
 * propagation was correct, so we project a single bare column. Joins are also out of scope -- the bug class is about
 * single-table view expansion, and joining introduces an attribution surface this oracle is not positioned to cover.
 */
public class ClickHouseViewEquivalenceOracle implements TestOracle<ClickHouseGlobalState> {

    private static final AtomicLong VIEW_COUNTER = new AtomicLong();

    private final ClickHouseGlobalState state;
    private final ExpectedErrors errors = new ExpectedErrors();

    public ClickHouseViewEquivalenceOracle(ClickHouseGlobalState state) {
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
        // Skip views -- a view-over-view would multiply our attribution surface and is best
        // covered by a dedicated nested-view oracle (out of scope here). isView is set by
        // ClickHouseSchema.fromConnection from a naming convention.
        if (table.isView()) {
            throw new IgnoreMeException();
        }
        ClickHouseTableReference tableRef = new ClickHouseTableReference(table, null);
        List<ClickHouseColumnReference> columns = tableRef.getColumnReferences();
        if (columns.isEmpty()) {
            throw new IgnoreMeException();
        }

        ClickHouseExpressionGenerator gen = new ClickHouseExpressionGenerator(state).allowAggregates(false);
        gen.addColumns(columns);
        ClickHouseExpression viewPredicate = gen.generatePredicate();
        ClickHouseExpression queryPredicate = gen.generatePredicate();
        ClickHouseColumnReference projectionCol = columns.get(0);

        // Inlined baseline: WHERE (p_view) AND (p_q). The AND is constructed via the AST so the
        // visitor handles parenthesisation; doing it as raw string concatenation risks operator-
        // precedence surprises around NOT/IS NULL postfix operators.
        ClickHouseExpression combinedPredicate = new ClickHouseBinaryLogicalOperation(viewPredicate, queryPredicate,
                ClickHouseBinaryLogicalOperator.AND);
        ClickHouseSelect baseline = new ClickHouseSelect();
        baseline.setFromClause(tableRef);
        baseline.setFetchColumns(List.of((ClickHouseExpression) projectionCol));
        baseline.setWhereClause(combinedPredicate);
        String baselineQuery = ClickHouseVisitor.asString(baseline);
        List<String> baselineRows = ComparatorHelper.getResultSetFirstColumnAsString(baselineQuery, errors, state);

        String viewName = "v_" + table.getName() + "_" + VIEW_COUNTER.incrementAndGet();
        String fqView = state.getDatabaseName() + "." + viewName;
        // CREATE VIEW IF NOT EXISTS shields against the (rare) name collision with a stale view
        // from a previous run that the database drop didn't pick up. The IF NOT EXISTS branch is
        // semantically safe because viewName embeds the monotonic counter -- collision would imply
        // a previous identical-numbered view that should be functionally identical.
        String viewSelect = "SELECT * FROM " + table.getName() + " WHERE " + ClickHouseVisitor.asString(viewPredicate);
        String createView = "CREATE VIEW IF NOT EXISTS " + fqView + " AS " + viewSelect;
        String dropView = "DROP VIEW IF EXISTS " + fqView;

        try {
            new SQLQueryAdapter(createView, errors, true).execute(state);
        } catch (SQLException e) {
            String msg = String.valueOf(e.getMessage());
            // Permissioning rejections or analyzer-specific view restrictions surface here.
            // Treat them as oracle-inapplicable rather than oracle failures.
            if (msg.contains("ACCESS_DENIED") || msg.contains("Not enough privileges")
                    || msg.contains("UNSUPPORTED_METHOD")) {
                throw new IgnoreMeException();
            }
            throw e;
        }

        try {
            // The expression generator renders column references qualified by the *base* table
            // name (e.g. `t1.c0`). Inside a `SELECT ... FROM view`, that qualifier is invalid --
            // the view is not aliased as the base table. Strip the qualifier so references
            // resolve to the view's projected columns (which inherit names from `SELECT *`).
            // The substitution is safe because `t1.` only appears as a column qualifier in our
            // generated predicates -- there is no user data with that prefix in the rendered SQL.
            String qualifierPrefix = table.getName() + ".";
            String queryPredicateForView = ClickHouseVisitor.asString(queryPredicate).replace(qualifierPrefix, "");
            String viewReadQuery = "SELECT " + projectionCol.getColumn().getName() + " FROM " + viewName + " WHERE "
                    + queryPredicateForView;

            List<String> viewRows = ComparatorHelper.getResultSetFirstColumnAsString(viewReadQuery, errors, state);
            ComparatorHelper.assumeResultSetsAreEqual(baselineRows, viewRows, baselineQuery, List.of(viewReadQuery),
                    state);
        } finally {
            try {
                new SQLQueryAdapter(dropView, errors, true).execute(state);
            } catch (SQLException ignored) {
                // Leak handling: the database is dropped between top-level runs by the provider,
                // so an undropped view does not persist across SQLancer invocations. Best-effort
                // is appropriate here.
            }
        }
    }

}
