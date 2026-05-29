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
 * A normal view {@code v AS SELECT <cols> FROM t WHERE p_view} composes with a query-level predicate exactly the same
 * as the inlined form. The bug class lives in the analyzer's view-expansion + filter-pushdown interaction -- see #100390
 * "view returning wrong results after DETACH/ATTACH with UNION and INTERSECT" -- and the only way to reach that code
 * path is to actually emit a CREATE VIEW and read through it.
 *
 * <p>
 * The view projects an explicit (possibly proper) subset of the base columns, not {@code SELECT *}. The query-level
 * predicate {@code p_q} and the read projection are therefore generated over ONLY the view's projected column set --
 * referencing a base column the view does not expose would make CH reject the read with
 * {@code UNKNOWN_IDENTIFIER ("Maybe you meant: ['c2']")}, a generator artefact rather than a real divergence. The view
 * body's own {@code WHERE p_view}, by contrast, runs against the base table and may reference any base column.
 *
 * <p>
 * Per check():
 *
 * <ol>
 * <li>Pick a base table with at least one column, and pick the subset {@code projectedCols} the view will project.</li>
 * <li>Generate a "view predicate" {@code p_view} over all base columns, and a "query predicate" {@code p_q} over
 * {@code projectedCols} only.</li>
 * <li>Compute the inlined baseline: {@code SELECT col FROM t WHERE (p_view) AND (p_q)}. This is the row set the view
 * read *should* produce.</li>
 * <li>Create {@code v AS SELECT <projectedCols> FROM t WHERE p_view} (logged into the reproducer so saved failures
 * replay standalone).</li>
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

        // The view body is `SELECT <projectedCols> FROM t WHERE p_view`. Two distinct column
        // scopes are at play and conflating them is exactly the bug this oracle hit (3 spurious
        // reproducers database7/11/43, e.g. "Unknown expression identifier 'c0' in scope
        // SELECT c0 FROM v_t2_399 WHERE ((c1)+(c1)). Maybe you meant: ['c2']"):
        //
        //   * p_view runs INSIDE the view body against the BASE table, so it may reference ANY
        //     base column (the full `columns` set).
        //   * the view's OUTPUT schema is only `projectedCols`. The query-level predicate p_q and
        //     the read-side projection both run against the VIEW, so they must reference ONLY
        //     columns in `projectedCols` -- otherwise CH raises UNKNOWN_IDENTIFIER and the failing
        //     query is a generator artefact, not a real view-expansion divergence.
        //
        // To keep the output schema deterministic and to actually exercise subset-projecting
        // views (the prior `SELECT *` body masked this scoping hazard by exposing every column),
        // we pick a random non-empty prefix-sized subset of the base columns as the view output.
        int projectedCount = (int) Randomly.getNotCachedInteger(1, columns.size() + 1);
        java.util.List<ClickHouseColumnReference> projectedCols = new java.util.ArrayList<>(
                columns.subList(0, projectedCount));

        // p_view: generated over the FULL base column set -- it lives inside the view body where
        // every base column is in scope.
        ClickHouseExpressionGenerator viewGen = new ClickHouseExpressionGenerator(state).allowAggregates(false);
        viewGen.addColumns(columns);
        ClickHouseExpression viewPredicate = viewGen.generatePredicate();

        // p_q + projection: generated over ONLY the view's projected columns -- this is the scope
        // restriction that fixes the UNKNOWN_IDENTIFIER reproducers. Every column reference the
        // view-side query can emit is guaranteed to exist in the view's output schema.
        ClickHouseExpressionGenerator queryGen = new ClickHouseExpressionGenerator(state).allowAggregates(false);
        queryGen.addColumns(projectedCols);
        ClickHouseExpression queryPredicate = queryGen.generatePredicate();
        ClickHouseColumnReference projectionCol = projectedCols.get(0);

        // Inlined baseline: WHERE (p_view) AND (p_q). The AND is constructed via the AST so the
        // visitor handles parenthesisation; doing it as raw string concatenation risks operator-
        // precedence surprises around NOT/IS NULL postfix operators. Both predicates resolve
        // against the base table here, and projectionCol is one of the projected (base) columns,
        // so the baseline is well-scoped by construction.
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
        // The view body projects exactly `projectedCols` (an explicit column list, not `SELECT *`),
        // which defines the view's output schema that the query-side predicate is scoped to above.
        StringBuilder projectionList = new StringBuilder();
        for (int i = 0; i < projectedCols.size(); i++) {
            if (i > 0) {
                projectionList.append(", ");
            }
            projectionList.append(projectedCols.get(i).getColumn().getName());
        }
        // CREATE VIEW IF NOT EXISTS shields against the (rare) name collision with a stale view
        // from a previous run that the database drop didn't pick up. The IF NOT EXISTS branch is
        // semantically safe because viewName embeds the monotonic counter -- collision would imply
        // a previous identical-numbered view that should be functionally identical.
        String viewSelect = "SELECT " + projectionList + " FROM " + table.getName() + " WHERE "
                + ClickHouseVisitor.asString(viewPredicate);
        String createView = "CREATE VIEW IF NOT EXISTS " + fqView + " AS " + viewSelect;
        String dropView = "DROP VIEW IF EXISTS " + fqView;

        if (state.getOptions().logEachSelect()) {
            // Persist the view DDL into the reproducer. writeCurrent → live -cur.log;
            // logStatement → state.getStatements(), which is what gets dumped to the persistent
            // database<N>.log on AssertionError. Without the second call the CREATE/DROP VIEW are
            // invisible in saved reproducers and the failing `SELECT ... FROM v_...` references a
            // view that doesn't exist on replay ("SHOW CREATE VIEW v_... doesn't exist"). We log
            // the DROP first so a replay that re-runs the file is idempotent, then the CREATE.
            state.getLogger().writeCurrent(dropView);
            state.getLogger().writeCurrent(createView);
            state.getState().logStatement(dropView);
            state.getState().logStatement(createView);
        }

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
            // resolve to the view's projected columns (which inherit their names from the explicit
            // column list in the view body). p_q was generated over `projectedCols` only, so every
            // stripped identifier is guaranteed to exist in the view's output schema -- this is the
            // scope restriction that eliminates the UNKNOWN_IDENTIFIER reproducers.
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
