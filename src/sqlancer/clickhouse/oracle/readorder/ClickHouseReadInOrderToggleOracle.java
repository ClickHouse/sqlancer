package sqlancer.clickhouse.oracle.readorder;

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
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.ExpectedErrors;

public class ClickHouseReadInOrderToggleOracle implements TestOracle<ClickHouseGlobalState> {

    static final String ARM_ON = " SETTINGS optimize_read_in_order = 1, optimize_aggregation_in_order = 1, read_in_order_use_buffering = 1";
    static final String ARM_OFF = " SETTINGS optimize_read_in_order = 0, optimize_aggregation_in_order = 0, read_in_order_use_buffering = 0";

    private final ClickHouseGlobalState state;
    private final ExpectedErrors errors = new ExpectedErrors();

    public ClickHouseReadInOrderToggleOracle(ClickHouseGlobalState state) {
        this.state = state;
        ClickHouseErrors.addExpectedExpressionErrors(errors);
        ClickHouseErrors.addSessionSettingsErrors(errors);
    }

    @Override
    public void check() throws SQLException {
        if (!state.getClickHouseOptions().readInOrderToggleOracle) {
            throw new IgnoreMeException();
        }

        ClickHouseSchema schema = state.getSchema();
        List<ClickHouseTable> tables = schema.getRandomTableNonEmptyTables().getTables();
        List<ClickHouseTable> eligible = tables.stream().filter(ClickHouseReadInOrderToggleOracle::isEligibleTable)
                .collect(Collectors.toList());
        if (eligible.isEmpty()) {
            throw new IgnoreMeException();
        }
        ClickHouseTable table = eligible.get((int) Randomly.getNotCachedInteger(0, eligible.size()));

        if (Randomly.getBoolean()) {
            checkOrderArm(table);
        } else {
            checkGroupByArm(table);
        }
    }

    private void checkOrderArm(ClickHouseTable table) throws SQLException {
        List<ClickHouseColumn> orderableCols = table.getColumns().stream()
                .filter(c -> isOrderableScalar(c.getType().getType())).collect(Collectors.toList());
        if (orderableCols.isEmpty()) {
            throw new IgnoreMeException();
        }

        String projection = "toString(tuple("
                + orderableCols.stream().map(c -> ref(c.getName())).collect(Collectors.joining(", ")) + "))";
        String orderBy = orderableCols.stream().map(c -> ref(c.getName()) + " ASC").collect(Collectors.joining(", "));
        int limit = Randomly.fromOptions(1, 5, 20);

        String base = "SELECT " + projection + " FROM " + table.getName() + " ORDER BY " + orderBy + " LIMIT " + limit;
        String on = base + ARM_ON;
        String off = base + ARM_OFF;

        List<String> onRows = ComparatorHelper.getResultSetFirstColumnAsString(on, errors, state);
        List<String> offRows = ComparatorHelper.getResultSetFirstColumnAsString(off, errors, state);

        if (!onRows.equals(offRows)) {
            throw new AssertionError(String.format(
                    "read-in-order ORDER mismatch (positional): %d rows in-order-on vs %d rows in-order-off.%n"
                            + "on:  %s%noff: %s%non rows:  %s%noff rows: %s",
                    onRows.size(), offRows.size(), on, off, onRows, offRows));
        }
    }

    private void checkGroupByArm(ClickHouseTable table) throws SQLException {
        List<ClickHouseColumn> keyCols = table.getColumns().stream().filter(c -> isScalarGroupKey(c.getType().getType()))
                .collect(Collectors.toList());
        if (keyCols.isEmpty()) {
            throw new IgnoreMeException();
        }
        ClickHouseColumn key = keyCols.get((int) Randomly.getNotCachedInteger(0, keyCols.size()));

        List<ClickHouseColumn> intCols = table.getColumns().stream()
                .filter(c -> isExactInteger(c.getType().getType())).collect(Collectors.toList());
        String sumArg = intCols.isEmpty() ? "0"
                : ref(intCols.get((int) Randomly.getNotCachedInteger(0, intCols.size())).getName());

        String keyRef = ref(key.getName());
        String projection = "toString(tuple(" + keyRef + ", count(), sum(" + sumArg + ")))";
        String base = "SELECT " + projection + " FROM " + table.getName() + " GROUP BY " + keyRef;
        String on = base + ARM_ON;
        String off = base + ARM_OFF;

        List<String> onRows = ComparatorHelper.getResultSetFirstColumnAsString(on, errors, state);
        List<String> offRows = ComparatorHelper.getResultSetFirstColumnAsString(off, errors, state);
        ComparatorHelper.assumeResultSetsAreEqual(onRows, offRows, on, List.of(off), state,
                ComparatorHelper.ComparisonMode.MULTISET);
    }

    static boolean isEligibleTable(ClickHouseTable table) {
        return !table.isView() && table.getEngine() != null && table.getEngine().endsWith("MergeTree");
    }

    static String ref(String columnName) {
        return "`" + columnName + "`";
    }

    static boolean isOrderableScalar(ClickHouseDataType t) {
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
        case Decimal:
        case Bool:
        case String:
        case FixedString:
        case Date:
        case Date32:
        case DateTime:
        case DateTime32:
        case DateTime64:
        case UUID:
        case IPv4:
        case IPv6:
        case Enum8:
        case Enum16:
            return true;
        default:
            return false;
        }
    }

    static boolean isScalarGroupKey(ClickHouseDataType t) {
        return isExactInteger(t) || t == ClickHouseDataType.String || t == ClickHouseDataType.FixedString
                || t == ClickHouseDataType.Date || t == ClickHouseDataType.Date32 || t == ClickHouseDataType.DateTime
                || t == ClickHouseDataType.DateTime32 || t == ClickHouseDataType.DateTime64
                || t == ClickHouseDataType.UUID || t == ClickHouseDataType.IPv4 || t == ClickHouseDataType.IPv6
                || t == ClickHouseDataType.Enum8 || t == ClickHouseDataType.Enum16 || t == ClickHouseDataType.Bool;
    }

    static boolean isExactInteger(ClickHouseDataType t) {
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
