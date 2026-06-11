package sqlancer.transformations;

import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.ItemsList;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.SelectBody;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.values.ValuesStatement;

public class RemoveRowsOfInsert extends JSQLParserBasedTransformation {
    public RemoveRowsOfInsert() {
        super("remove rows of an insert statement");
    }

    @Override
    public void apply() {
        super.apply();
        if (!(statement instanceof Insert)) {
            return;
        }
        SelectBody selectBody = ((Insert) statement).getSelect().getSelectBody();
        if (!(selectBody instanceof SetOperationList)) {
            return;
        }
        SetOperationList insertingList = (SetOperationList) selectBody;
        for (SelectBody selBody : insertingList.getSelects()) {
            if (!(selBody instanceof ValuesStatement)) {
                continue;
            }
            ValuesStatement valuesStatement = (ValuesStatement) selBody;
            ItemsList itemsList = valuesStatement.getExpressions();
            if (!(itemsList instanceof ExpressionList)) {
                continue;
            }
            tryRemoveElms((ExpressionList) itemsList, ((ExpressionList) itemsList).getExpressions(),
                    ExpressionList::setExpressions);
        }
    }
}
