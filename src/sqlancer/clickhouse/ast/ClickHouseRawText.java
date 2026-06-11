package sqlancer.clickhouse.ast;

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
