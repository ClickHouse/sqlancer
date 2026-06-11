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

        ClickHouseSelect baseline = new ClickHouseSelect();
        baseline.setFromClause(tableRef);
        baseline.setFetchColumns(List.of((ClickHouseExpression) projectionCol));
        baseline.setWhereClause(predicate);
        String baselineQuery = ClickHouseVisitor.asString(baseline);
        List<String> baselineRows;
        try {
            baselineRows = ComparatorHelper.getResultSetFirstColumnAsString(baselineQuery, errors, state);
        } catch (IgnoreMeException e) {

            throw e;
        }

        String policyName = "rp_" + table.getName() + "_" + POLICY_COUNTER.incrementAndGet();
        String fqTable = state.getDatabaseName() + "." + table.getName();
        String predicateSql = ClickHouseVisitor.asString(predicate);
        String createPolicy = "CREATE ROW POLICY " + policyName + " ON " + fqTable + " USING (" + predicateSql
                + ") TO CURRENT_USER";
        String dropPolicy = "DROP ROW POLICY IF EXISTS " + policyName + " ON " + fqTable;

        try {
            new SQLQueryAdapter(createPolicy, errors, false).execute(state);
        } catch (SQLException e) {

            String msg = String.valueOf(e.getMessage());
            if (msg.contains("ACCESS_DENIED") || msg.contains("Not enough privileges")
                    || msg.contains("CREATE_ROW_POLICY")) {
                throw new IgnoreMeException();
            }
            throw e;
        }

        try {

            ClickHouseSelect filtered = new ClickHouseSelect();
            filtered.setFromClause(tableRef);
            filtered.setFetchColumns(List.of((ClickHouseExpression) projectionCol));
            filtered.setWhereClause(null);
            String filteredQuery = ClickHouseVisitor.asString(filtered);
            List<String> filteredRows = ComparatorHelper.getResultSetFirstColumnAsString(filteredQuery, errors, state);

            ComparatorHelper.assumeResultSetsAreEqual(baselineRows, filteredRows, baselineQuery, List.of(filteredQuery),
                    state);
        } finally {

            try {
                new SQLQueryAdapter(dropPolicy, errors, false).execute(state);
            } catch (SQLException ignored) {

            }
        }
    }

}
