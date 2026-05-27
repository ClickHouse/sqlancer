package sqlancer.clickhouse.ast;

import java.util.List;

import sqlancer.clickhouse.ClickHouseToStringVisitor;

/**
 * Lambda expression for higher-order functions. ClickHouse renders as {@code (p1, p2) -> body}
 * (parentheses required for multi-arity; single-parameter form {@code p -> body} also accepted).
 *
 * <p>Workstream 22 of the 2026-05-27 coverage expansion plan. Used by arrayMap / arrayFilter /
 * arrayCount / arrayExists / arrayAll / arraySort / arrayFirst / arrayLast / arrayFold and
 * the comparator-lambda overloads.
 */
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
