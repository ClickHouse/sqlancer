package sqlancer.clickhouse.oracle.projection;

import java.sql.SQLException;
import java.util.List;
import java.util.stream.Collectors;

import com.clickhouse.data.ClickHouseDataType;

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

public class ClickHouseProjectionToggleOracle implements TestOracle<ClickHouseGlobalState> {

    private final ClickHouseGlobalState state;
    private final ExpectedErrors errors = new ExpectedErrors();

    public ClickHouseProjectionToggleOracle(ClickHouseGlobalState state) {
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
        if (table.getColumns().isEmpty()) {
            throw new IgnoreMeException();
        }
        List<ClickHouseColumnReference> columns = table.getColumns().stream()
                .map(c -> c.asColumnReference(table.getName())).collect(Collectors.toList());

        ClickHouseExpressionGenerator gen = new ClickHouseExpressionGenerator(state).allowAggregates(false);
        gen.addColumns(columns);

        String whereClause = "";
        if (Randomly.getBoolean()) {
            ClickHouseExpression predicate = gen.generatePredicate();
            whereClause = " WHERE " + ClickHouseToStringVisitor.asString(predicate);
        }

        List<ClickHouseColumn> scalarKeys = table.getColumns().stream()
                .filter(c -> isScalarGroupKey(c.getType().getType())).collect(Collectors.toList());
        List<ClickHouseColumn> intCols = table.getColumns().stream().filter(c -> isExactInteger(c.getType().getType()))
                .collect(Collectors.toList());

        String projection;
        String groupBy = "";
        if (scalarKeys.isEmpty() || Randomly.getBooleanWithRatherLowProbability()) {
            projection = "toString(count())";
            if (!intCols.isEmpty()) {
                ClickHouseColumn agg = Randomly.fromList(intCols);
                String fn = Randomly.fromOptions("sum", "min", "max", "count");
                projection = "concat(toString(count()), '#', toString(" + fn + "(`" + agg.getName() + "`)))";
            }
        } else {
            ClickHouseColumn key = Randomly.fromList(scalarKeys);
            String keyName = "`" + key.getName() + "`";
            String aggExpr;
            if (intCols.isEmpty()) {
                aggExpr = "toString(count())";
            } else {
                ClickHouseColumn agg = Randomly.fromList(intCols);
                String fn = Randomly.fromOptions("sum", "min", "max", "count");
                aggExpr = "concat(toString(count()), '#', toString(" + fn + "(`" + agg.getName() + "`)))";
            }
            projection = "concat(toString(" + keyName + "), '@', " + aggExpr + ")";
            groupBy = " GROUP BY " + keyName;
        }

        String base = "SELECT " + projection + " FROM " + table.getName() + whereClause + groupBy;
        String withProjections = base + " SETTINGS optimize_use_projections = 1";
        String withoutProjections = base + " SETTINGS optimize_use_projections = 0";

        List<String> onRows = ComparatorHelper.getResultSetFirstColumnAsString(withProjections, errors, state);
        List<String> offRows = ComparatorHelper.getResultSetFirstColumnAsString(withoutProjections, errors, state);
        ComparatorHelper.assumeResultSetsAreEqual(onRows, offRows, withProjections, List.of(withoutProjections), state);
    }

    private static boolean isScalarGroupKey(ClickHouseDataType t) {
        return isExactInteger(t) || t == ClickHouseDataType.String || t == ClickHouseDataType.FixedString
                || t == ClickHouseDataType.Date || t == ClickHouseDataType.Date32 || t == ClickHouseDataType.DateTime
                || t == ClickHouseDataType.DateTime64 || t == ClickHouseDataType.UUID;
    }

    private static boolean isExactInteger(ClickHouseDataType t) {
        switch (t) {
        case Int8:
        case Int16:
        case Int32:
        case Int64:
        case Int128:
        case Int256:
        case UInt8:
        case UInt16:
        case UInt32:
        case UInt64:
        case UInt128:
        case UInt256:
            return true;
        default:
            return false;
        }
    }

}
