package sqlancer.hive.gen;

import java.util.List;

import sqlancer.common.gen.AbstractInsertGenerator;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.hive.HiveErrors;
import sqlancer.hive.HiveGlobalState;
import sqlancer.hive.HiveSchema.HiveColumn;
import sqlancer.hive.HiveSchema.HiveTable;
import sqlancer.hive.HiveToStringVisitor;

public class HiveInsertGenerator extends AbstractInsertGenerator<HiveColumn> {

    private final HiveGlobalState globalState;
    private final HiveExpressionGenerator gen;

    public HiveInsertGenerator(HiveGlobalState globalState) {
        this.globalState = globalState;
        this.gen = new HiveExpressionGenerator(globalState);
        this.canonicalizeString = false;
    }

    public static SQLQueryAdapter getQuery(HiveGlobalState globalState) {
        return new HiveInsertGenerator(globalState).getStatement();
    }

    @Override
    protected void insertValue(HiveColumn column) {
        sb.append(HiveToStringVisitor.asString(gen.generateConstant()));
    }

    @Override
    public void buildStatement() {

        sb.append("INSERT INTO ");
        HiveTable table = globalState.getSchema().getRandomTable(t -> !t.isView());
        sb.append(table.getName());

        sb.append(" VALUES ");

        List<HiveColumn> columns = table.getColumns();
        insertColumns(columns);

        HiveErrors.addInsertErrors(errors);
    }
}
