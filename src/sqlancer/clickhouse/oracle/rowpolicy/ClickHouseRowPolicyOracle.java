package sqlancer.clickhouse.oracle.rowpolicy;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import sqlancer.ComparatorHelper;
import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.clickhouse.ClickHouseErrors;
import sqlancer.clickhouse.ClickHouseFailureDiagnostics;
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
        errors.add("TOO_DEEP_SUBQUERIES");
        errors.add("Too deep subqueries");
        errors.add("TOO_DEEP_RECURSION");
        errors.add("Maximum parse depth");
        errors.add("Stack size too large");
        errors.add("TOO_DEEP_AST");
        errors.add("TOO_BIG_AST");
    }

    @Override
    public void check() throws SQLException {
        ClickHouseSchema schema = state.getSchema();
        List<ClickHouseTable> tables = schema.getRandomTableNonEmptyTables().getTables().stream()
                .filter(t -> !t.isView()).collect(java.util.stream.Collectors.toList());
        if (tables.isEmpty()) {
            throw new IgnoreMeException();
        }
        ClickHouseTable table = tables.get((int) Randomly.getNotCachedInteger(0, tables.size()));
        ClickHouseTableReference tableRef = new ClickHouseTableReference(table, null);
        List<ClickHouseColumnReference> columns = tableRef.getColumnReferences();
        if (columns.isEmpty()) {
            throw new IgnoreMeException();
        }

        String fqTable = state.getDatabaseName() + "." + table.getName();
        List<String> existingPolicies = rowPoliciesOn(table.getName());
        if (!existingPolicies.isEmpty()) {
            for (String name : existingPolicies) {
                try {
                    new SQLQueryAdapter("DROP ROW POLICY IF EXISTS " + name + " ON " + fqTable, errors, false)
                            .execute(state);
                } catch (SQLException ignored) {

                }
            }
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
        String predicateSql = ClickHouseVisitor.asString(predicate);
        String createPolicy = "CREATE ROW POLICY " + policyName + " ON " + fqTable + " USING (" + predicateSql
                + ") TO CURRENT_USER";
        String dropPolicy = "DROP ROW POLICY IF EXISTS " + policyName + " ON " + fqTable;

        boolean policyCreated;
        try {
            policyCreated = new SQLQueryAdapter(createPolicy, errors, false).execute(state);
        } catch (SQLException e) {

            String msg = String.valueOf(e.getMessage());
            if (msg.contains("ACCESS_DENIED") || msg.contains("Not enough privileges")
                    || msg.contains("CREATE_ROW_POLICY")) {
                throw new IgnoreMeException();
            }
            throw e;
        }
        if (!policyCreated) {
            new SQLQueryAdapter(dropPolicy, errors, false).execute(state);
            throw new IgnoreMeException();
        }

        try {

            ClickHouseSelect filtered = new ClickHouseSelect();
            filtered.setFromClause(tableRef);
            filtered.setFetchColumns(List.of((ClickHouseExpression) projectionCol));
            filtered.setWhereClause(null);
            String filteredQuery = ClickHouseVisitor.asString(filtered);
            List<String> filteredRows = ComparatorHelper.getResultSetFirstColumnAsString(filteredQuery, errors, state);

            try {
                ComparatorHelper.assumeResultSetsAreEqual(baselineRows, filteredRows, baselineQuery,
                        List.of(filteredQuery), state);
            } catch (AssertionError e) {
                logMismatchDiagnostics(table.getName(), filteredQuery);
                throw e;
            }
        } finally {

            try {
                new SQLQueryAdapter(dropPolicy, errors, false).execute(state);
            } catch (SQLException ignored) {

            }
        }
    }

    private List<String> rowPoliciesOn(String tableName) {
        List<String> names = new ArrayList<>();
        String query = "SELECT short_name FROM system.row_policies WHERE database = "
                + ClickHouseFailureDiagnostics.asStringLiteral(state.getDatabaseName()) + " AND table = "
                + ClickHouseFailureDiagnostics.asStringLiteral(tableName);
        try (Statement s = state.getConnection().createStatement(); ResultSet rs = s.executeQuery(query)) {
            while (rs.next()) {
                names.add(rs.getString(1));
            }
        } catch (SQLException e) {
            throw new IgnoreMeException();
        }
        return names;
    }

    private void logMismatchDiagnostics(String tableName, String filteredQuery) {
        ClickHouseFailureDiagnostics.logRowPolicies(state, tableName);
        ClickHouseFailureDiagnostics.logTableState(state, tableName);
        String reread;
        try {
            reread = ComparatorHelper.getResultSetFirstColumnAsString(filteredQuery, errors, state).size() + " rows";
        } catch (IgnoreMeException | SQLException | AssertionError e) {
            reread = "unavailable";
        }
        state.getState().getLocalState().log("-- diagnostics: filtered query re-read: " + reread);
    }

}
