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

        select.setFetchColumns(List.of(columns.get(0)));
        select.setWhereClause(predicate);

        String baseline = ClickHouseToStringVisitor.asString(select);

        String noPruneBody = MaterializedColumnVisitor.asString(select);
        String noPrune = noPruneBody + " SETTINGS use_skip_indexes = 0, force_primary_key = 0,"
                + " use_query_condition_cache = 0";

        List<String> baseRows;
        try {
            baseRows = ComparatorHelper.getResultSetFirstColumnAsString(baseline, errors, state);
        } catch (IgnoreMeException e) {

            throw e;
        }
        List<String> noPruneRows = ComparatorHelper.getResultSetFirstColumnAsString(noPrune, errors, state);
        ComparatorHelper.assumeResultSetsAreEqual(baseRows, noPruneRows, baseline, List.of(noPrune), state);
    }

    static final class MaterializedColumnVisitor extends ClickHouseToStringVisitor {

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
