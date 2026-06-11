package sqlancer.clickhouse.ast;

import sqlancer.clickhouse.ClickHouseToStringVisitor;

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
