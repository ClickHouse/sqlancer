package sqlancer.clickhouse.oracle.cast;

import java.sql.SQLException;
import java.util.List;

import sqlancer.ComparatorHelper;
import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.clickhouse.ClickHouseErrors;
import sqlancer.clickhouse.ClickHouseProvider.ClickHouseGlobalState;
import sqlancer.clickhouse.ClickHouseSchema;
import sqlancer.clickhouse.ClickHouseSchema.ClickHouseColumn;
import sqlancer.clickhouse.ClickHouseSchema.ClickHouseTable;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.ExpectedErrors;

public class ClickHouseCastOracle implements TestOracle<ClickHouseGlobalState> {

    private static final List<String> TARGETS = List.of("Int8", "Int16", "Int32", "Int64", "UInt8", "UInt16", "UInt32",
            "UInt64", "Float32", "Float64", "Date", "DateTime", "Decimal(9, 2)", "Decimal(18, 4)");

    private final ClickHouseGlobalState state;
    private final ExpectedErrors errors = new ExpectedErrors();

    public ClickHouseCastOracle(ClickHouseGlobalState state) {
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
        List<ClickHouseColumn> columns = table.getColumns();
        if (columns.isEmpty()) {
            throw new IgnoreMeException();
        }
        ClickHouseColumn col = columns.get((int) Randomly.getNotCachedInteger(0, columns.size()));
        String columnName = col.getName();
        String target = TARGETS.get((int) Randomly.getNotCachedInteger(0, TARGETS.size()));
        String fqTable = state.getDatabaseName() + "." + table.getName();

        String orNullQuery = "SELECT toString(accurateCastOrNull(" + columnName + ", '" + target + "')) FROM " + fqTable
                + " ORDER BY " + columnName + " NULLS FIRST";
        String guardedThrowQuery = "SELECT IF(accurateCastOrNull(" + columnName + ", '" + target
                + "') IS NULL, NULL, toString(accurateCast(" + columnName + ", '" + target + "'))) FROM " + fqTable
                + " ORDER BY " + columnName + " NULLS FIRST";

        List<String> orNullRows;
        try {
            orNullRows = ComparatorHelper.getResultSetFirstColumnAsString(orNullQuery, errors, state);
        } catch (IgnoreMeException e) {

            throw e;
        }
        List<String> guardedRows;
        try {
            guardedRows = ComparatorHelper.getResultSetFirstColumnAsString(guardedThrowQuery, errors, state);
        } catch (IgnoreMeException e) {

            throw e;
        }
        ComparatorHelper.assumeResultSetsAreEqual(orNullRows, guardedRows, orNullQuery, List.of(guardedThrowQuery),
                state);
    }
}
