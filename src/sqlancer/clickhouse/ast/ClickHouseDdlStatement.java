package sqlancer.clickhouse.ast;

public abstract class ClickHouseDdlStatement extends ClickHouseExpression {

    private final String sql;

    protected ClickHouseDdlStatement(String sql) {
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
