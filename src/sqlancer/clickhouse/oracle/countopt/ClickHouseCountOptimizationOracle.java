package sqlancer.clickhouse.oracle.countopt;

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

public class ClickHouseCountOptimizationOracle implements TestOracle<ClickHouseGlobalState> {

    private static final String COUNT_OPT_ON = " SETTINGS optimize_trivial_count_query = 1, "
            + "optimize_use_implicit_projections = 1, optimize_use_projections = 1";
    private static final String COUNT_OPT_OFF = " SETTINGS optimize_trivial_count_query = 0, "
            + "optimize_use_implicit_projections = 0, optimize_use_projections = 0";

    private final ClickHouseGlobalState state;
    private final ExpectedErrors errors = new ExpectedErrors();

    public ClickHouseCountOptimizationOracle(ClickHouseGlobalState state) {
        this.state = state;
        ClickHouseErrors.addExpectedExpressionErrors(errors);
        ClickHouseErrors.addSessionSettingsErrors(errors);
    }

    @Override
    public void check() throws SQLException {
        if (!state.getClickHouseOptions().countOptimizationOracle) {
            throw new IgnoreMeException();
        }
        ClickHouseSchema schema = state.getSchema();
        List<ClickHouseTable> tables = schema.getRandomTableNonEmptyTables().getTables();
        if (tables.isEmpty()) {
            throw new IgnoreMeException();
        }
        ClickHouseTable table = tables.get((int) Randomly.getNotCachedInteger(0, tables.size()));
        if (table.isView() || table.getColumns().isEmpty()) {
            throw new IgnoreMeException();
        }

        List<ClickHouseColumn> physicalColumns = table.getColumns().stream()
                .filter(c -> !c.isAlias() && !c.isMaterialized()).collect(Collectors.toList());
        if (physicalColumns.isEmpty()) {
            throw new IgnoreMeException();
        }

        List<ClickHouseColumnReference> columnRefs = physicalColumns.stream()
                .map(c -> c.asColumnReference(table.getName())).collect(Collectors.toList());

        ClickHouseExpressionGenerator gen = new ClickHouseExpressionGenerator(state).allowAggregates(false);
        gen.addColumns(columnRefs);
        ClickHouseExpression predicate = gen.generatePredicate();
        String pred = ClickHouseToStringVisitor.asString(predicate);

        String from = " FROM " + table.getName();

        String fullCountSql = "SELECT toString(count())" + from;
        String onFullCount = readSingleValue(fullCountSql + COUNT_OPT_ON);
        String offFullCount = readSingleValue(fullCountSql + COUNT_OPT_OFF);
        if (!onFullCount.equals(offFullCount)) {
            throw mismatch("count() toggle", fullCountSql, onFullCount, offFullCount);
        }

        String filteredCountSql = "SELECT toString(count())" + from + " WHERE " + pred;
        String onFilteredCount = readSingleValue(filteredCountSql + COUNT_OPT_ON);
        String offFilteredCount = readSingleValue(filteredCountSql + COUNT_OPT_OFF);
        if (!onFilteredCount.equals(offFilteredCount)) {
            throw mismatch("count()+WHERE toggle", filteredCountSql, onFilteredCount, offFilteredCount);
        }

        String countWhereSql = "SELECT toString(count())" + from + " WHERE " + pred;
        String countIfSql = "SELECT toString(countIf(" + pred + "))" + from;
        String countWhere = readSingleValue(countWhereSql);
        String countIf = readSingleValue(countIfSql);
        if (!countWhere.equals(countIf)) {
            throw new AssertionError(String.format(
                    "count-optimization row-drop cross-check mismatch: predicate %s%n  count() WHERE pred = %s%n"
                            + "  countIf(pred)      = %s%n  table: %s%n  filter query: %s%n  ground truth: %s",
                    pred, countWhere, countIf, table.getName(), countWhereSql, countIfSql));
        }

        List<ClickHouseColumn> groupKeys = physicalColumns.stream()
                .filter(c -> isScalarGroupKey(c.getType().getType())).collect(Collectors.toList());
        if (!groupKeys.isEmpty()) {
            ClickHouseColumn key = Randomly.fromList(groupKeys);
            String keyName = "`" + key.getName() + "`";
            String groupSql = "SELECT toString(tuple(" + keyName + ", count()))" + from + " GROUP BY " + keyName;
            logStmt(groupSql + COUNT_OPT_ON);
            List<String> onRows = ComparatorHelper.getResultSetFirstColumnAsString(groupSql + COUNT_OPT_ON, errors,
                    state);
            logStmt(groupSql + COUNT_OPT_OFF);
            List<String> offRows = ComparatorHelper.getResultSetFirstColumnAsString(groupSql + COUNT_OPT_OFF, errors,
                    state);
            ComparatorHelper.assumeResultSetsAreEqual(onRows, offRows, groupSql + COUNT_OPT_ON,
                    List.of(groupSql + COUNT_OPT_OFF), state, ComparatorHelper.ComparisonMode.MULTISET);
        }
    }

    private AssertionError mismatch(String label, String sql, String onValue, String offValue) {
        return new AssertionError(String.format(
                "count-optimization %s mismatch: settings-on saw %s but settings-off saw %s.%n  base: %s", label,
                onValue, offValue, sql));
    }

    private String readSingleValue(String query) throws SQLException {
        logStmt(query);
        List<String> rows = ComparatorHelper.getResultSetFirstColumnAsString(query, errors, state);
        if (rows.size() != 1) {
            throw new IgnoreMeException();
        }
        return rows.get(0);
    }

    private void logStmt(String stmt) {
        if (state.getOptions().logEachSelect()) {
            state.getState().logStatement(stmt);
        }
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
