package sqlancer.clickhouse.ast;

/**
 * Holds a pre-rendered SQL fragment for direct emission. Used by generators that build SQL as a string (geo function
 * calls, map() literals, INTERVAL fragments, dictGet calls, higher-order function calls, date+interval arithmetic,
 * scalar subqueries).
 *
 * <p>
 * Why this exists separately from {@link sqlancer.clickhouse.ast.ClickHouseExpression.ClickHousePostfixText}:
 * PostfixText {@code implements UnaryOperation}, which routes it through the base ToStringVisitor's UnaryOperation
 * dispatch that recurses into a (possibly null) inner expression. ClickHouseRawText extends ClickHouseExpression
 * directly so the visitor dispatch lands cleanly on the ClickHouseVisitor#visit(ClickHouseRawText) override.
 */
public class ClickHouseRawText extends ClickHouseExpression {

    private final String sql;

    public ClickHouseRawText(String sql) {
        this.sql = sql;
    }

    public String getSql() {
        return sql;
    }

    @Override
    public String toString() {
        return sql;
    }
}
