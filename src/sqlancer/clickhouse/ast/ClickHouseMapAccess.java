package sqlancer.clickhouse.ast;

import sqlancer.clickhouse.ClickHouseToStringVisitor;

/**
 * Map key access. ClickHouse renders as {@code m['key']} (the key is itself an expression that the visitor renders via
 * the standard expression path; the brackets are the access operator).
 *
 * <p>
 * Workstream 2 of the 2026-05-27 coverage expansion plan.
 */
public class ClickHouseMapAccess extends ClickHouseExpression {

    private final ClickHouseExpression map;
    private final ClickHouseExpression key;

    public ClickHouseMapAccess(ClickHouseExpression map, ClickHouseExpression key) {
        this.map = map;
        this.key = key;
    }

    public ClickHouseExpression getMap() {
        return map;
    }

    public ClickHouseExpression getKey() {
        return key;
    }

    @Override
    public String toString() {
        return ClickHouseToStringVisitor.asString(this);
    }
}
