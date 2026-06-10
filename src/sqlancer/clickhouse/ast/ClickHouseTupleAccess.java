package sqlancer.clickhouse.ast;

import sqlancer.clickhouse.ClickHouseToStringVisitor;

/**
 * Positional tuple field access. ClickHouse renders as {@code tup.1}, {@code tup.2}, ... Indices are 1-based per the CH
 * grammar.
 *
 * <p>
 * Workstream 2 of the 2026-05-27 coverage expansion plan.
 */
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
