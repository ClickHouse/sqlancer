package sqlancer.common.gen;

public abstract class AbstractDeleteGenerator extends AbstractGenerator {

    protected AbstractDeleteGenerator() {
    }

    protected void appendDeleteFromTable(String tableName) {
        appendDeleteFromTable(tableName, false);
    }

    protected void appendDeleteFromTable(String tableName, boolean only) {
        sb.append("DELETE FROM ");
        if (only) {
            sb.append("ONLY ");
        }
        sb.append(tableName);
    }

    protected void appendLimitClause(Object value) {
        sb.append(" LIMIT ");
        sb.append(value);
    }

    protected void appendReturningClause(String expression) {
        sb.append(" RETURNING ");
        sb.append(expression);
    }

}
