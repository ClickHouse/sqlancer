package sqlancer.clickhouse.oracle.settingflip;

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

public class ClickHouseSettingFlipOracle implements TestOracle<ClickHouseGlobalState> {

    private static final String[][] NEUTRAL_SETTINGS = { { "optimize_read_in_order", "1", "0" },
            { "optimize_aggregation_in_order", "1", "0" }, { "read_in_order_use_buffering", "1", "0" },
            { "optimize_move_to_prewhere", "1", "0" }, { "enable_multiple_prewhere_read_steps", "1", "0" },
            { "query_plan_optimize_lazy_materialization", "1", "0" }, { "query_plan_enable_optimizations", "1", "0" },
            { "query_plan_filter_push_down", "1", "0" }, { "compile_expressions", "1", "0" },
            { "compile_aggregate_expressions", "1", "0" }, { "compile_sort_description", "1", "0" },
            { "min_count_to_compile_expression", "0", "3" }, { "optimize_functions_to_subcolumns", "1", "0" },
            { "optimize_aggregators_of_group_by_keys", "1", "0" }, { "optimize_distinct_in_order", "1", "0" },
            { "optimize_trivial_count_query", "1", "0" }, { "optimize_use_projections", "1", "0" },
            { "group_by_two_level_threshold", "1", "100000" },
            { "aggregation_in_order_max_block_bytes", "1", "50000000" },
            { "max_bytes_before_external_group_by", "0", "1" }, { "max_threads", "1", "8" },
            { "max_block_size", "1024", "65536" }, { "prefer_localhost_replica", "1", "0" } };

    private final ClickHouseGlobalState state;
    private final ExpectedErrors errors = new ExpectedErrors();

    public ClickHouseSettingFlipOracle(ClickHouseGlobalState state) {
        this.state = state;
        ClickHouseErrors.addExpectedExpressionErrors(errors);
        ClickHouseErrors.addSessionSettingsErrors(errors);
    }

    @Override
    public void check() throws SQLException {
        if (!state.getClickHouseOptions().settingFlipOracle) {
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
        String[] flip = NEUTRAL_SETTINGS[(int) Randomly.getNotCachedInteger(0, NEUTRAL_SETTINGS.length)];
        String queryA = base + " SETTINGS " + flip[0] + " = " + flip[1];
        String queryB = base + " SETTINGS " + flip[0] + " = " + flip[2];

        List<String> rowsA = ComparatorHelper.getResultSetFirstColumnAsString(queryA, errors, state);
        List<String> rowsB = ComparatorHelper.getResultSetFirstColumnAsString(queryB, errors, state);
        ComparatorHelper.assumeResultSetsAreEqual(rowsA, rowsB, queryA, List.of(queryB), state);
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
