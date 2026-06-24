package sqlancer.clickhouse.oracle.materialize;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import sqlancer.ComparatorHelper;
import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.clickhouse.ClickHouseErrors;
import sqlancer.clickhouse.ClickHouseProvider.ClickHouseGlobalState;
import sqlancer.clickhouse.ClickHouseSchema;
import sqlancer.clickhouse.ClickHouseSchema.ClickHouseColumn;
import sqlancer.clickhouse.ClickHouseSchema.ClickHouseTable;
import sqlancer.clickhouse.ClickHouseToStringVisitor;
import sqlancer.clickhouse.ast.ClickHouseColumnReference;
import sqlancer.clickhouse.ast.ClickHouseExpression;
import sqlancer.clickhouse.gen.ClickHouseExpressionGenerator;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;

public class ClickHouseSubqueryMaterializeOracle implements TestOracle<ClickHouseGlobalState> {

    private static final AtomicLong TMP_COUNTER = new AtomicLong();

    private final ClickHouseGlobalState state;
    private final ExpectedErrors errors = new ExpectedErrors();

    public ClickHouseSubqueryMaterializeOracle(ClickHouseGlobalState state) {
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

        List<ClickHouseColumn> tableColumns = table.getColumns();
        if (tableColumns.isEmpty()) {
            throw new IgnoreMeException();
        }

        List<ClickHouseColumnReference> bareColumns = new ArrayList<>(tableColumns.size());
        for (ClickHouseColumn c : tableColumns) {
            ClickHouseColumn bare = new ClickHouseColumn(c.getName(), c.getType(), false, false, null);
            bareColumns.add(new ClickHouseColumnReference(bare, null, null));
        }

        ClickHouseExpressionGenerator gen = new ClickHouseExpressionGenerator(state).allowAggregates(false);
        gen.addColumns(bareColumns);
        ClickHouseExpression pInner = gen.generatePredicate();
        ClickHouseExpression pOuter = gen.generatePredicate();

        String col0 = bareColumns.get(0).getColumn().getName();

        String projection = renderColumnList(bareColumns);
        String pInnerSql = ClickHouseToStringVisitor.asString(pInner);
        String pOuterSql = ClickHouseToStringVisitor.asString(pOuter);

        String fqSource = state.getDatabaseName() + "." + table.getName();

        String innerSelect = "SELECT " + projection + " FROM " + fqSource + " WHERE " + pInnerSql;
        String inlineQuery = "SELECT " + col0 + " FROM (" + innerSelect + ") AS sub WHERE " + pOuterSql;

        String engine = Randomly.getBoolean() ? "Memory" : "Log";
        String tmpName = tmpName(table.getName());
        String fqTmp = state.getDatabaseName() + "." + tmpName;
        String dropTmp = "DROP TABLE IF EXISTS " + fqTmp + " SYNC";
        String createTmp = "CREATE TABLE " + fqTmp + " ENGINE = " + engine + " AS " + innerSelect;
        String tmpSelect = "SELECT " + col0 + " FROM " + fqTmp + " WHERE " + pOuterSql;

        try {

            if (state.getOptions().logEachSelect()) {
                state.getLogger().writeCurrent(dropTmp);
                state.getLogger().writeCurrent(createTmp);
                state.getLogger().writeCurrent(tmpSelect);
                state.getLogger().writeCurrent(dropTmp);
                state.getState().logStatement(dropTmp);
                state.getState().logStatement(createTmp);
                state.getState().logStatement(tmpSelect);
                state.getState().logStatement(dropTmp);
            }

            new SQLQueryAdapter(dropTmp, errors, true).execute(state, false);
            boolean created = new SQLQueryAdapter(createTmp, errors, true).execute(state, false);
            if (!created) {

                throw new IgnoreMeException();
            }
        } catch (SQLException e) {

            safeDrop(dropTmp);
            throw new IgnoreMeException();
        }

        try {
            List<String> inlineRows;
            try {
                inlineRows = ComparatorHelper.getResultSetFirstColumnAsString(inlineQuery, errors, state);
            } catch (IgnoreMeException e) {

                throw e;
            }
            List<String> tmpRows = ComparatorHelper.getResultSetFirstColumnAsString(tmpSelect, errors, state);
            ComparatorHelper.assumeResultSetsAreEqual(inlineRows, tmpRows, inlineQuery, List.of(tmpSelect), state);
        } finally {
            safeDrop(dropTmp);
        }
    }

    private String renderColumnList(List<ClickHouseColumnReference> columns) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(ClickHouseToStringVisitor.asString(columns.get(i)));
        }
        return sb.toString();
    }

    private void safeDrop(String dropTmp) {
        try {
            new SQLQueryAdapter(dropTmp, errors, true).execute(state, false);
        } catch (SQLException ignored) {

        }
    }

    private String tmpName(String source) {
        return "smat_" + source + "_" + TMP_COUNTER.incrementAndGet();
    }
}
