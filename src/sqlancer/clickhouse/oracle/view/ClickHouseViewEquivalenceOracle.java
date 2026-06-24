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

        if (table.isView()) {
            throw new IgnoreMeException();
        }
        ClickHouseTableReference tableRef = new ClickHouseTableReference(table, null);
        List<ClickHouseColumnReference> columns = tableRef.getColumnReferences();
        if (columns.isEmpty()) {
            throw new IgnoreMeException();
        }

        int projectedCount = (int) Randomly.getNotCachedInteger(1, columns.size() + 1);
        java.util.List<ClickHouseColumnReference> projectedCols = new java.util.ArrayList<>(
                columns.subList(0, projectedCount));

        ClickHouseExpressionGenerator viewGen = new ClickHouseExpressionGenerator(state).allowAggregates(false);
        viewGen.addColumns(columns);
        ClickHouseExpression viewPredicate = viewGen.generatePredicate();

        ClickHouseExpressionGenerator queryGen = new ClickHouseExpressionGenerator(state).allowAggregates(false);
        queryGen.addColumns(projectedCols);
        ClickHouseExpression queryPredicate = queryGen.generatePredicate();
        ClickHouseColumnReference projectionCol = projectedCols.get(0);

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

        StringBuilder projectionList = new StringBuilder();
        for (int i = 0; i < projectedCols.size(); i++) {
            if (i > 0) {
                projectionList.append(", ");
            }
            projectionList.append(projectedCols.get(i).getColumn().getName());
        }

        String viewSelect = "SELECT " + projectionList + " FROM " + table.getName() + " WHERE "
                + ClickHouseVisitor.asString(viewPredicate);
        String createView = "CREATE VIEW IF NOT EXISTS " + fqView + " AS " + viewSelect;
        String dropView = "DROP VIEW IF EXISTS " + fqView;

        if (state.getOptions().logEachSelect()) {

            state.getLogger().writeCurrent(dropView);
            state.getLogger().writeCurrent(createView);
            state.getState().logStatement(dropView);
            state.getState().logStatement(createView);
        }

        try {

            boolean created = new SQLQueryAdapter(createView, errors, true).execute(state);
            if (!created) {
                throw new IgnoreMeException();
            }
        } catch (SQLException e) {
            String msg = String.valueOf(e.getMessage());

            if (msg.contains("ACCESS_DENIED") || msg.contains("Not enough privileges")
                    || msg.contains("UNSUPPORTED_METHOD")) {
                throw new IgnoreMeException();
            }
            throw e;
        }

        try {

            String qualifierPrefix = table.getName() + ".";
            String queryPredicateForView = ClickHouseVisitor.asString(queryPredicate).replace(qualifierPrefix, "");

            String viewReadQuery = "SELECT " + projectionCol.getColumn().getName() + " FROM " + fqView + " WHERE "
                    + queryPredicateForView;

            List<String> viewRows = ComparatorHelper.getResultSetFirstColumnAsString(viewReadQuery, errors, state);
            ComparatorHelper.assumeResultSetsAreEqual(baselineRows, viewRows, baselineQuery, List.of(viewReadQuery),
                    state);
        } finally {
            try {
                new SQLQueryAdapter(dropView, errors, true).execute(state);
            } catch (SQLException ignored) {

            }
        }
    }

}
