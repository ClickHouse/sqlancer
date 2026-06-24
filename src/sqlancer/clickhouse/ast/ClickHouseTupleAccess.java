package sqlancer.clickhouse.ast;

import sqlancer.clickhouse.ClickHouseToStringVisitor;

public class ClickHouseTupleAccess extends ClickHouseExpression {

    private final ClickHouseExpression tuple;
    private final int index;

    public ClickHouseTupleAccess(ClickHouseExpression tuple, int index) {
        if (index < 1) {
            throw new IllegalArgumentException("Tuple index must be >= 1; got " + index);
        }
        this.tuple = tuple;
        this.index = index;
    }

    public ClickHouseExpression getTuple() {
        return tuple;
    }

    public int getIndex() {
        return index;
    }

    @Override
    public String toString() {
        return ClickHouseToStringVisitor.asString(this);
    }
}
