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
        buildInsertInto(table.getName(), columns);
    }

    @Override
    protected void insertValue(ClickHouseColumn column) {
        if (signConstrained && column.getType().getType() == ClickHouseDataType.Int8) {
            sb.append(Randomly.getBoolean() ? "1" : "-1");
            return;
        }
        String s = ClickHouseToStringVisitor.asString(gen.generateConstant(column.getType()));
        sb.append(s);
    }

}
