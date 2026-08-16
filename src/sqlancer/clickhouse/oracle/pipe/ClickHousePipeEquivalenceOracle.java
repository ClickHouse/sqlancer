package sqlancer.clickhouse.oracle.pipe;

import java.sql.SQLException;
import java.util.List;
import java.util.stream.Collectors;

import com.clickhouse.data.ClickHouseDataType;

import sqlancer.ComparatorHelper;
import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.clickhouse.ClickHouseErrors;
import sqlancer.clickhouse.ClickHouseProvider.ClickHouseGlobalState;
import sqlancer.clickhouse.ClickHouseSchema.ClickHouseColumn;
import sqlancer.clickhouse.ClickHouseSchema.ClickHouseTable;
import sqlancer.clickhouse.ClickHouseToStringVisitor;
import sqlancer.clickhouse.ast.ClickHouseColumnReference;
import sqlancer.clickhouse.gen.ClickHouseExpressionGenerator;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.ExpectedErrors;

public class ClickHousePipeEquivalenceOracle implements TestOracle<ClickHouseGlobalState> {

    private enum Arm {
        PROJECTION, AGGREGATE, ORDER_LIMIT
    }

    private final ClickHouseGlobalState state;
    private final ExpectedErrors errors = new ExpectedErrors();

    public ClickHousePipeEquivalenceOracle(ClickHouseGlobalState state) {
        this.state = state;
        ClickHouseErrors.addExpectedExpressionErrors(errors);
        ClickHouseErrors.addSessionSettingsErrors(errors);
        errors.add("UNKNOWN_TABLE");
        errors.add("Unknown table expression identifier");
        errors.add("(MEMORY_LIMIT_EXCEEDED)");
        errors.add("memory limit exceeded");
        errors.add("TIMEOUT_EXCEEDED");
        errors.add("Timeout exceeded");
        errors.add("Limit for result exceeded");
        errors.add("TOO_MANY_ROWS_OR_BYTES");
    }

    @Override
    public void check() throws SQLException {
        if (!state.getClickHouseOptions().pipeEquivalenceOracle) {
            throw new IgnoreMeException();
        }
        List<ClickHouseTable> tables = state.getSchema().getRandomTableNonEmptyTables().getTables().stream()
                .filter(t -> !t.isView()).collect(Collectors.toList());
        if (tables.isEmpty()) {
            throw new IgnoreMeException();
        }
        ClickHouseTable table = Randomly.fromList(tables);
        List<ClickHouseColumnReference> columns = table.getColumns().stream().filter(ClickHousePipeEquivalenceOracle::isReadableByStar)
                .map(c -> new ClickHouseColumnReference(c, null, "")).collect(Collectors.toList());
        if (columns.isEmpty()) {
            throw new IgnoreMeException();
        }

        ClickHouseExpressionGenerator gen = new ClickHouseExpressionGenerator(state).allowAggregates(false);
        gen.addColumns(columns);
        String predicate = ClickHouseToStringVisitor.asString(gen.generatePredicate());
        String from = state.getDatabaseName() + "." + table.getName();

        switch (Randomly.fromOptions(Arm.values())) {
        case PROJECTION:
            checkProjection(from, predicate, table);
            break;
        case AGGREGATE:
            checkAggregate(from, predicate, table);
            break;
        case ORDER_LIMIT:
            checkOrderLimit(from, predicate, table);
            break;
        default:
            throw new AssertionError();
        }
    }

    private void checkProjection(String from, String predicate, ClickHouseTable table) throws SQLException {
        String projection = rowProjection(table);
        String classic = "SELECT " + projection + " FROM " + from + " WHERE " + predicate;
        String pipe = "FROM " + from + " |> WHERE " + predicate + " |> SELECT " + projection;
        compareMultisets(classic, pipe);
    }

    private void checkAggregate(String from, String predicate, ClickHouseTable table) throws SQLException {
        List<ClickHouseColumn> keys = table.getColumns().stream()
                .filter(c -> isScalarGroupKey(c.getType().getType()) && isReadableByStar(c))
                .collect(Collectors.toList());
        if (keys.isEmpty()) {
            throw new IgnoreMeException();
        }
        String key = quote(Randomly.fromList(keys).getName());
        List<ClickHouseColumn> ints = table.getColumns().stream()
                .filter(c -> isExactInteger(c.getType().getType()) && isReadableByStar(c))
                .collect(Collectors.toList());
        String sumArg = ints.isEmpty() ? "0" : quote(Randomly.fromList(ints).getName());

        String classic = "SELECT toString(tuple(" + key + ", count(), sum(" + sumArg + "))) FROM " + from + " WHERE "
                + predicate + " GROUP BY " + key;
        String pipe = "FROM " + from + " |> WHERE " + predicate + " |> AGGREGATE count() AS pipe_c, sum(" + sumArg
                + ") AS pipe_s GROUP BY " + key + " |> SELECT toString(tuple(" + key + ", pipe_c, pipe_s))";
        compareMultisets(classic, pipe);
    }

    private void checkOrderLimit(String from, String predicate, ClickHouseTable table) throws SQLException {
        String projection = rowProjection(table);
        long limit = 1 + Randomly.getNotCachedInteger(0, 20);
        String classic = "SELECT " + projection + " AS pipe_o FROM " + from + " WHERE " + predicate
                + " ORDER BY pipe_o ASC LIMIT " + limit;
        String pipe = "FROM " + from + " |> WHERE " + predicate + " |> SELECT " + projection
                + " AS pipe_o |> ORDER BY pipe_o ASC |> LIMIT " + limit;

        log(classic);
        List<String> classicRows = ComparatorHelper.getResultSetFirstColumnAsString(classic, errors, state);
        log(pipe);
        List<String> pipeRows = ComparatorHelper.getResultSetFirstColumnAsString(pipe, errors, state);
        if (!classicRows.equals(pipeRows)) {
            throw new AssertionError(String.format(
                    "pipe-operator ORDER BY / LIMIT mismatch: the classic query returned %d rows and its pipe-syntax "
                            + "rendering returned %d under an identical total order.%n  classic: %s%n  pipe:    %s%n"
                            + "  classic rows: %s%n  pipe rows:    %s",
                    classicRows.size(), pipeRows.size(), classic, pipe, classicRows, pipeRows));
        }
    }

    private void compareMultisets(String classic, String pipe) throws SQLException {
        log(classic);
        List<String> classicRows = ComparatorHelper.getResultSetFirstColumnAsString(classic, errors, state);
        log(pipe);
        List<String> pipeRows = ComparatorHelper.getResultSetFirstColumnAsString(pipe, errors, state);
        ComparatorHelper.assumeResultSetsAreEqual(classicRows, pipeRows, classic, List.of(pipe), state,
                ComparatorHelper.ComparisonMode.MULTISET);
    }

    private static String rowProjection(ClickHouseTable table) {
        List<ClickHouseColumn> projectable = table.getColumns().stream()
                .filter(ClickHousePipeEquivalenceOracle::isReadableByStar).collect(Collectors.toList());
        if (projectable.isEmpty()) {
            throw new IgnoreMeException();
        }
        return "toString(tuple("
                + projectable.stream().map(c -> quote(c.getName())).collect(Collectors.joining(", ")) + "))";
    }

    private static boolean isReadableByStar(ClickHouseColumn column) {
        return !column.isAlias() && !column.isMaterialized();
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

    private static String quote(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }

    private void log(String sql) {
        if (state.getOptions().logEachSelect()) {
            state.getLogger().writeCurrent(sql);
            state.getState().logStatement(sql);
        }
    }
}
