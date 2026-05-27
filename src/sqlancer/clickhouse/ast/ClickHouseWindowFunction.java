package sqlancer.clickhouse.ast;

import java.util.Collections;
import java.util.List;

import sqlancer.clickhouse.ClickHouseToStringVisitor;

/**
 * Window function call: {@code func(args) OVER (PARTITION BY ... ORDER BY ... frame?)}.
 *
 * <p>Workstream 19 of the 2026-05-27 coverage expansion plan. Minimal viable: name + optional
 * argument expression + partition/order lists. Frame clauses (ROWS/RANGE/GROUPS with
 * UNBOUNDED/CURRENT_ROW/n_PRECEDING/n_FOLLOWING and EXCLUDE NO_OTHERS/CURRENT_ROW/GROUP/TIES)
 * are deferred -- the bug surface they reach (RANGE off-by-one on Decimal/DateTime64 boundaries
 * per the plan) needs a more careful generator.
 */
public class ClickHouseWindowFunction extends ClickHouseExpression {

    public enum Kind {
        ROW_NUMBER, RANK, DENSE_RANK, PERCENT_RANK, CUME_DIST,
        FIRST_VALUE, LAST_VALUE, NTH_VALUE,
        LAG, LEAD,
        SUM, COUNT, MIN, MAX, AVG
    }

    private final Kind kind;
    private final ClickHouseExpression argument; // may be null for ROW_NUMBER / RANK / etc.
    private final List<ClickHouseExpression> partitionBy;
    private final List<ClickHouseExpression> orderBy;

    public ClickHouseWindowFunction(Kind kind, ClickHouseExpression argument,
            List<ClickHouseExpression> partitionBy, List<ClickHouseExpression> orderBy) {
        this.kind = kind;
        this.argument = argument;
        this.partitionBy = partitionBy == null ? Collections.emptyList() : List.copyOf(partitionBy);
        this.orderBy = orderBy == null ? Collections.emptyList() : List.copyOf(orderBy);
    }

    public Kind getKind() {
        return kind;
    }

    public ClickHouseExpression getArgument() {
        return argument;
    }

    public List<ClickHouseExpression> getPartitionBy() {
        return partitionBy;
    }

    public List<ClickHouseExpression> getOrderBy() {
        return orderBy;
    }

    public String renderName() {
        switch (kind) {
        case ROW_NUMBER: return "row_number";
        case RANK: return "rank";
        case DENSE_RANK: return "dense_rank";
        case PERCENT_RANK: return "percent_rank";
        case CUME_DIST: return "cume_dist";
        case FIRST_VALUE: return "first_value";
        case LAST_VALUE: return "last_value";
        case NTH_VALUE: return "nth_value";
        case LAG: return "lag";
        case LEAD: return "lead";
        case SUM: return "sum";
        case COUNT: return "count";
        case MIN: return "min";
        case MAX: return "max";
        case AVG: return "avg";
        default: throw new AssertionError(kind);
        }
    }

    @Override
    public String toString() {
        return ClickHouseToStringVisitor.asString(this);
    }
}
