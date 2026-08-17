package sqlancer.clickhouse.gen;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import com.clickhouse.data.ClickHouseDataType;

import sqlancer.Randomly;
import sqlancer.clickhouse.ClickHouseErrors;
import sqlancer.clickhouse.ClickHouseProvider.ClickHouseGlobalState;
import sqlancer.clickhouse.ClickHouseSchema.ClickHouseColumn;
import sqlancer.clickhouse.ClickHouseSchema.ClickHouseTable;
import sqlancer.clickhouse.ClickHouseToStringVisitor;
import sqlancer.common.gen.AbstractInsertGenerator;
import sqlancer.common.query.SQLQueryAdapter;

public class ClickHouseInsertGenerator extends AbstractInsertGenerator<ClickHouseColumn> {

    private final ClickHouseGlobalState globalState;
    private final ClickHouseExpressionGenerator gen;

    private boolean signConstrained;
    private java.util.Set<String> defaultHeavyColumns = java.util.Collections.emptySet();

    public ClickHouseInsertGenerator(ClickHouseGlobalState globalState) {
        this.globalState = globalState;
        gen = new ClickHouseExpressionGenerator(globalState);
        errors.add("Cannot insert NULL value into a column of type");
        errors.add("Memory limit");
        errors.add("Cannot parse string");
        errors.add("Cannot parse Int32 from String, because value is too short");
        errors.add("does not return a value of type UInt8");
        ClickHouseErrors.addExpectedExpressionErrors(errors);
    }

    public static SQLQueryAdapter getQuery(ClickHouseGlobalState globalState) {
        return new ClickHouseInsertGenerator(globalState).getStatement();
    }

    @Override
    public void buildStatement() {
        ClickHouseTable table = globalState.getSchema().getRandomTable(t -> !t.isView());
        String engine = table.getEngine();
        signConstrained = "CollapsingMergeTree".equals(engine) || "VersionedCollapsingMergeTree".equals(engine);
        List<ClickHouseColumn> columns = Collections.emptyList();
        while (columns.isEmpty()) {
            columns = table.getRandomNonEmptyColumnSubset().stream().filter(c -> !c.isAlias() && !c.isMaterialized())
                    .collect(Collectors.toList());
        }
        if (signConstrained) {

            List<ClickHouseColumn> withSign = new java.util.ArrayList<>(columns);
            for (ClickHouseColumn c : table.getColumns()) {
                if (c.getType().getType() == ClickHouseDataType.Int8 && !c.isAlias() && !c.isMaterialized()
                        && withSign.stream().noneMatch(x -> x.getName().equals(c.getName()))) {
                    withSign.add(c);
                }
            }
            columns = withSign;
        }
        defaultHeavyColumns = pickDefaultHeavyColumns(table, columns);
        buildInsertInto(table.getName(), columns);
    }

    private java.util.Set<String> pickDefaultHeavyColumns(ClickHouseTable table, List<ClickHouseColumn> columns) {
        if (!globalState.getClickHouseOptions().sparseColumnEmission || !"MergeTree".equals(table.getEngine())
                || Randomly.getBoolean()) {
            return java.util.Collections.emptySet();
        }
        java.util.Set<String> picked = new java.util.HashSet<>();
        for (ClickHouseColumn c : columns) {
            if (defaultLiteral(c) != null && Randomly.getBoolean()) {
                picked.add(c.getName());
            }
        }
        return picked;
    }

    private static String defaultLiteral(ClickHouseColumn column) {
        sqlancer.clickhouse.ClickHouseType term = column.getType().getTypeTerm();
        if (term instanceof sqlancer.clickhouse.ClickHouseType.Nullable) {
            return "NULL";
        }
        sqlancer.clickhouse.ClickHouseType unwrapped = term.unwrap();
        if (unwrapped instanceof sqlancer.clickhouse.ClickHouseType.Array) {
            return "[]";
        }
        if (unwrapped instanceof sqlancer.clickhouse.ClickHouseType.FixedString) {
            return "''";
        }
        switch (column.getType().getType()) {
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
        case Float32:
        case Float64:
        case Bool:
            return "0";
        case String:
            return "''";
        case Date:
        case Date32:
            return "'1970-01-01'";
        case DateTime:
        case DateTime32:
            return "'1970-01-01 00:00:00'";
        default:
            return null;
        }
    }

    @Override
    protected void insertValue(ClickHouseColumn column) {
        if (signConstrained && column.getType().getType() == ClickHouseDataType.Int8) {
            sb.append(Randomly.getBoolean() ? "1" : "-1");
            return;
        }
        if (defaultHeavyColumns.contains(column.getName()) && !Randomly.getBooleanWithSmallProbability()) {
            String literal = defaultLiteral(column);
            if (literal != null) {
                sb.append(literal);
                return;
            }
        }
        String s = ClickHouseToStringVisitor.asString(gen.generateConstant(column.getType()));
        sb.append(s);
    }

}
