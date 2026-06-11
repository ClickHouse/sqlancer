package sqlancer.questdb;

import sqlancer.common.ast.newast.NewToStringVisitor;
import sqlancer.questdb.ast.QuestDBConstant;
import sqlancer.questdb.ast.QuestDBExpression;
import sqlancer.questdb.ast.QuestDBSelect;

public class QuestDBToStringVisitor extends NewToStringVisitor<QuestDBExpression> {

    @Override
    public void visitSpecific(QuestDBExpression expr) {
        if (expr instanceof QuestDBConstant) {
            visit((QuestDBConstant) expr);
        } else if (expr instanceof QuestDBSelect) {
            visit((QuestDBSelect) expr);
        } else {
            throw new AssertionError("Unknown class: " + expr.getClass());
        }
    }

    private void visit(QuestDBConstant constant) {
        sb.append(constant.toString());
    }

    private void visit(QuestDBSelect select) {
        sb.append("SELECT ");
        if (select.isDistinct()) {
            sb.append("DISTINCT ");
        }
        visit(select.getFetchColumns());
        sb.append(" FROM ");
        visit(select.getFromList());
        if (!select.getFromList().isEmpty() && !select.getJoinList().isEmpty()) {
            sb.append(", ");
        }
        if (!select.getJoinList().isEmpty()) {
            visit(select.getJoinList());
        }
        if (select.getWhereClause() != null) {
            sb.append(" WHERE ");
            visit(select.getWhereClause());
        }

        if (select.getLimitClause() != null) {
            sb.append(" LIMIT ");
            visit(select.getLimitClause());
        }
    }

    public static String asString(QuestDBExpression expr) {
        QuestDBToStringVisitor visitor = new QuestDBToStringVisitor();
        visitor.visit(expr);
        return visitor.get();
    }
}
