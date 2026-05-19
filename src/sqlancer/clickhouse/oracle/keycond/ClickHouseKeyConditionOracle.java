package sqlancer.clickhouse.oracle.keycond;

import java.sql.SQLException;
import java.util.List;

import sqlancer.ComparatorHelper;
import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.clickhouse.ClickHouseErrors;
import sqlancer.clickhouse.ClickHouseProvider.ClickHouseGlobalState;
import sqlancer.clickhouse.ClickHouseSchema;
import sqlancer.clickhouse.ClickHouseSchema.ClickHouseTable;
import sqlancer.clickhouse.ClickHouseToStringVisitor;
import sqlancer.clickhouse.ast.ClickHouseColumnReference;
import sqlancer.clickhouse.ast.ClickHouseExpression;
import sqlancer.clickhouse.ast.ClickHouseSelect;
import sqlancer.clickhouse.ast.ClickHouseTableReference;
import sqlancer.clickhouse.gen.ClickHouseExpressionGenerator;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.ExpectedErrors;

/**
 * KeyCondition / skip-index pruning oracle.
 *
 * <p>
 * ClickHouse uses the primary key and secondary skip-indices to prune granules at query time -- the {@code KeyCondition}
 * subsystem decides which parts and granules to read by analysing the predicate. Bugs in that subsystem produce wrong
 * results that look identical to an unindexed scan would, so they are invisible to oracles that compare two indexed
 * paths against each other (TLP, SEMR, ...).
 *
 * <p>
 * ClickHouse#92492 is the canonical recent example: {@code KeyCondition} mis-evaluated a regex with {@code ?} and {@code
 * not} operators, dropping granules that should have matched. The fix landed in 25.x but the same shape can recur in
 * any monotonicity or function-tracking change to {@code KeyCondition.cpp}.
 *
 * <p>
 * The differential here is between
 *
 * <ol>
 * <li>the baseline query (KeyCondition can prune), and</li>
 * <li>the same query with every base-column reference wrapped in {@code materialize(col)} -- KeyCondition cannot
 * recognise {@code materialize(col)} as the underlying column and so falls back to a full scan, plus
 * {@code use_skip_indexes=0} and {@code force_primary_key=0} as a belt-and-braces defence.</li>
 * </ol>
 *
 * <p>
 * If the two row multisets disagree, KeyCondition pruned a granule it should have kept (or vice versa). The single-table
 * shape and the absence of GROUP BY / ORDER BY in the generated SELECT is intentional: it keeps the failure
 * attribution focused on the predicate <-> KeyCondition path. JOINs add their own row-cardinality variance which would
 * dilute the signal.
 */
public class ClickHouseKeyConditionOracle implements TestOracle<ClickHouseGlobalState> {

    private final ClickHouseGlobalState state;
    private final ExpectedErrors errors = new ExpectedErrors();

    public ClickHouseKeyConditionOracle(ClickHouseGlobalState state) {
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

        ClickHouseSelect select = new ClickHouseSelect();
        select.setFromClause(tableRef);
        // Project the first column only -- the oracle's contract is "the same multiset of values is
        // visible to a granule-pruned and an unpruned scan", and one column makes both ends of the
        // diff cheap.
        select.setFetchColumns(List.of(columns.get(0)));
        select.setWhereClause(predicate);

        // Baseline: KeyCondition is free to prune.
        String baseline = ClickHouseToStringVisitor.asString(select);

        // No-prune variant: render with column references wrapped in materialize(); attach
        // belt-and-braces settings to suppress skip-index + primary-key forcing on top of the
        // materialize wrap. force_primary_key=0 means "do not require PK use" rather than "do not
        // use PK", which is what we want -- KeyCondition is already neutralised by materialize().
        String noPruneBody = MaterializedColumnVisitor.asString(select);
        String noPrune = noPruneBody + " SETTINGS use_skip_indexes = 0, force_primary_key = 0,"
                + " use_query_condition_cache = 0";

        List<String> baseRows;
        try {
            baseRows = ComparatorHelper.getResultSetFirstColumnAsString(baseline, errors, state);
        } catch (IgnoreMeException e) {
            // A predicate that the server rejects (type mismatch, malformed function) is a generator
            // slip, not a KeyCondition bug. Drop the iteration.
            throw e;
        }
        List<String> noPruneRows = ComparatorHelper.getResultSetFirstColumnAsString(noPrune, errors, state);
        ComparatorHelper.assumeResultSetsAreEqual(baseRows, noPruneRows, baseline, List.of(noPrune), state);
    }

    // Render a ClickHouseSelect (or any expression) with every ClickHouseColumnReference wrapped
    // in materialize(...). materialize() is an identity function on values but is opaque to
    // KeyCondition's analysis, which is the entire point. Wrapping is only applied to base column
    // references inside PREWHERE/WHERE/HAVING; project / GROUP BY / ORDER BY pass through
    // unchanged so the column shape and group keys are preserved.
    static final class MaterializedColumnVisitor extends ClickHouseToStringVisitor {

        // Depth counter rather than a bare boolean: when a subquery's PREWHERE/WHERE nests inside
        // an outer PREWHERE/WHERE, exiting the inner predicate must NOT turn off wrapping for the
        // outer one. Save-and-restore stack semantics via a counter is the simplest correct fix.
        private int predicateDepth;

        @Override
        public void visit(ClickHouseColumnReference c) {
            if (predicateDepth == 0) {
                super.visit(c);
                return;
            }
            sb.append("materialize(");
            super.visit(c);
            sb.append(")");
        }

        @Override
        public void visit(ClickHouseSelect select, boolean inner) {
            if (inner) {
                sb.append("(");
            }
            sb.append("SELECT ");
            switch (select.getFromOptions()) {
            case DISTINCT:
                sb.append("DISTINCT ");
                break;
            case ALL:
                break;
            default:
                throw new AssertionError(select.getFromOptions());
            }
            visit(select.getFetchColumns());
            List<ClickHouseExpression> fromList = select.getFromList();
            if (fromList != null) {
                sb.append(" FROM ");
                visit(fromList);
            }
            if (select.isFinal()) {
                sb.append(" FINAL");
            }
            if (select.getPrewhereClause() != null) {
                sb.append(" PREWHERE ");
                predicateDepth++;
                try {
                    visit(select.getPrewhereClause());
                } finally {
                    predicateDepth--;
                }
            }
            if (select.getWhereClause() != null) {
                sb.append(" WHERE ");
                predicateDepth++;
                try {
                    visit(select.getWhereClause());
                } finally {
                    predicateDepth--;
                }
            }
            if (!select.getGroupByClause().isEmpty()) {
                sb.append(" GROUP BY ");
                visit(select.getGroupByClause());
            }
            if (select.getHavingClause() != null) {
                sb.append(" HAVING ");
                predicateDepth++;
                try {
                    visit(select.getHavingClause());
                } finally {
                    predicateDepth--;
                }
            }
            if (!select.getOrderByClauses().isEmpty()) {
                sb.append(" ORDER BY ");
                visit(select.getOrderByClauses());
            }
            if (inner) {
                sb.append(")");
            }
        }

        public static String asString(ClickHouseExpression expr) {
            MaterializedColumnVisitor v = new MaterializedColumnVisitor();
            if (expr instanceof ClickHouseSelect) {
                v.visit((ClickHouseSelect) expr, false);
            } else {
                v.visit(expr);
            }
            return v.get();
        }
    }
}
