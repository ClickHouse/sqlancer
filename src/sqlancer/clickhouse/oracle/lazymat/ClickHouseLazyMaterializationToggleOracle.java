package sqlancer.clickhouse.oracle.lazymat;

import java.sql.SQLException;
import java.util.ArrayList;
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
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.ExpectedErrors;

public class ClickHouseLazyMaterializationToggleOracle implements TestOracle<ClickHouseGlobalState> {

    static final String SETTING = "query_plan_optimize_lazy_materialization";

    private static final int[] LIMITS = { 1, 5, 20 };

    private final ClickHouseGlobalState state;
    private final ExpectedErrors errors = new ExpectedErrors();

    public ClickHouseLazyMaterializationToggleOracle(ClickHouseGlobalState state) {
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
        if (!state.getClickHouseOptions().lazyMaterializationToggleOracle) {
            throw new IgnoreMeException();
        }

        List<ClickHouseTable> tables = state.getSchema().getRandomTableNonEmptyTables().getTables();
        List<ClickHouseTable> eligible = tables.stream().filter(ClickHouseLazyMaterializationToggleOracle::isEligible)
                .collect(Collectors.toList());
        if (eligible.isEmpty()) {
            throw new IgnoreMeException();
        }
        ClickHouseTable table = eligible.get((int) Randomly.getNotCachedInteger(0, eligible.size()));

        List<ClickHouseColumn> physicalColumns = table.getColumns().stream()
                .filter(c -> !c.isAlias() && !c.isMaterialized()).filter(c -> isRenderable(c.getType().getType()))
                .collect(Collectors.toList());
        if (physicalColumns.isEmpty()) {
            throw new IgnoreMeException();
        }

        List<String> orderByRefs = physicalColumns.stream().map(c -> ref(c.getName())).collect(Collectors.toList());

        List<String> projectionParts = new ArrayList<>(orderByRefs);
        for (String scalarExpr : scalarExpressions(physicalColumns)) {
            projectionParts.add(scalarExpr);
        }

        String projection = "toString(tuple(" + String.join(", ", projectionParts) + "))";
        String orderBy = orderByRefs.stream().map(r -> r + " ASC").collect(Collectors.joining(", "));
        int limit = LIMITS[(int) Randomly.getNotCachedInteger(0, LIMITS.length)];

        String base = "SELECT " + projection + " FROM " + state.getDatabaseName() + "." + ref(table.getName())
                + " ORDER BY " + orderBy + " LIMIT " + limit;
        String lazyOn = base + " SETTINGS " + SETTING + " = 1";
        String lazyOff = base + " SETTINGS " + SETTING + " = 0";

        List<String> onRows = ComparatorHelper.getResultSetFirstColumnAsString(lazyOn, errors, state);
        List<String> offRows = ComparatorHelper.getResultSetFirstColumnAsString(lazyOff, errors, state);

        if (!onRows.equals(offRows)) {
            throw new AssertionError(String.format(
                    "lazy-materialization toggle mismatch (positional, total ORDER BY):%n  on:  %s%n  off: %s%n"
                            + "  on rows (%d): %s%n  off rows (%d): %s",
                    lazyOn, lazyOff, onRows.size(), onRows, offRows.size(), offRows));
        }
    }

    private static List<String> scalarExpressions(List<ClickHouseColumn> physicalColumns) {
        List<ClickHouseColumn> intCols = physicalColumns.stream().filter(c -> isExactInteger(c.getType().getType()))
                .collect(Collectors.toList());
        if (intCols.isEmpty()) {
            return List.of();
        }
        int count = 1 + (int) Randomly.getNotCachedInteger(0, 2);
        List<String> exprs = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ClickHouseColumn col = intCols.get((int) Randomly.getNotCachedInteger(0, intCols.size()));
            String colRef = ref(col.getName());
            String inner;
            switch ((int) Randomly.getNotCachedInteger(0, 3)) {
            case 0:
                inner = colRef + " + " + Randomly.getNotCachedInteger(-1000, 1000);
                break;
            case 1:
                inner = colRef + " % " + (2 + Randomly.getNotCachedInteger(0, 9));
                break;
            default:
                inner = "abs(toInt64(" + colRef + "))";
                break;
            }
            exprs.add("CAST((" + inner + ") AS Nullable(Float64))");
        }
        return exprs;
    }

    static boolean isEligible(ClickHouseTable table) {
        return !table.isView() && table.getEngine() != null && table.getEngine().endsWith("MergeTree");
    }

    private static boolean isRenderable(ClickHouseDataType t) {
        switch (t) {
        case Variant:
        case Dynamic:
        case JSON:
        case Nothing:
        case AggregateFunction:
        case Float32:
        case Float64:
            return false;
        default:
            return true;
        }
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

    private static String ref(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }
}
