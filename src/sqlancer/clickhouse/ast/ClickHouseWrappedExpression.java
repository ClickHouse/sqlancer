package sqlancer.clickhouse.ast;

public class ClickHouseWrappedExpression extends ClickHouseExpression {

    private final String prefix;
    private final ClickHouseExpression expression;
    private final String suffix;

    public ClickHouseWrappedExpression(String prefix, ClickHouseExpression expression, String suffix) {
        this.prefix = prefix;
        this.expression = expression;
        this.suffix = suffix;
    }

    public String getPrefix() {
        return prefix;
    }

    public ClickHouseExpression getExpression() {
        return expression;
    }

    public String getSuffix() {
        return suffix;
    }
}
