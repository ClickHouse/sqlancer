package sqlancer.clickhouse.ast;

import java.util.List;

import sqlancer.clickhouse.ClickHouseToStringVisitor;

public class ClickHouseLambda extends ClickHouseExpression {

    private final List<String> params;
    private final ClickHouseExpression body;

    public ClickHouseLambda(List<String> params, ClickHouseExpression body) {
        if (params == null || params.isEmpty()) {
            throw new IllegalArgumentException("Lambda requires at least one parameter");
        }
        this.params = List.copyOf(params);
        this.body = body;
    }

    public List<String> getParams() {
        return params;
    }

    public ClickHouseExpression getBody() {
        return body;
    }

    @Override
    public String toString() {
        return ClickHouseToStringVisitor.asString(this);
    }
}
