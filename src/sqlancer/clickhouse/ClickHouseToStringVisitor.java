package sqlancer.clickhouse;

import java.util.List;

import sqlancer.clickhouse.ast.ClickHouseAggregate;
import sqlancer.clickhouse.ast.ClickHouseAggregateCombinator;
import sqlancer.clickhouse.ast.ClickHouseAliasOperation;
import sqlancer.clickhouse.ast.ClickHouseBinaryFunctionOperation;
import sqlancer.clickhouse.ast.ClickHouseBinaryLogicalOperation;
import sqlancer.clickhouse.ast.ClickHouseCastOperation;
import sqlancer.clickhouse.ast.ClickHouseColumnReference;
import sqlancer.clickhouse.ast.ClickHouseConstant;
import sqlancer.clickhouse.ast.ClickHouseExpression;
import sqlancer.clickhouse.ast.ClickHouseSelect;
import sqlancer.clickhouse.ast.ClickHouseSetOperation;
import sqlancer.clickhouse.ast.ClickHouseTableReference;
import sqlancer.clickhouse.ast.ClickHouseUnaryPostfixOperation;
import sqlancer.clickhouse.ast.ClickHouseUnaryPrefixOperation;
import sqlancer.common.visitor.ToStringVisitor;

public class ClickHouseToStringVisitor extends ToStringVisitor<ClickHouseExpression> implements ClickHouseVisitor {

    @Override
    public void visitSpecific(ClickHouseExpression expr) {
        ClickHouseVisitor.super.visit(expr);
    }

    @Override
    public void visit(ClickHouseBinaryLogicalOperation op) {
        sb.append("(");
        visit(op.getLeft());
        sb.append(") ");
        sb.append(op.getTextRepresentation());
        sb.append(" (");
        visit(op.getRight());
        sb.append(")");
    }

    @Override
    public void visit(ClickHouseUnaryPrefixOperation op) {
        sb.append(op.getOperatorRepresentation());
        sb.append(" (");
        visit(op.getExpression());
        sb.append(")");
    }

    @Override
    public void visit(ClickHouseUnaryPostfixOperation op) {
        sb.append("(");
        visit(op.getExpression());
        sb.append(")");
        sb.append(" ");
        sb.append(op.getOperatorRepresentation());
    }

    @Override
    public void visit(ClickHouseConstant constant) {
        sb.append(constant.toString());
    }

    @Override
    public void visit(ClickHouseSelect select, boolean inner) {
        if (inner) {
            sb.append("(");
        }
        sb.append("SELECT ");
        switch (select.getFromOptions()) {
        case DISTINCT:
            sb.append("DISTINCT ");
            break;
        case ALL:
            sb.append("");
            break;
        default:
            throw new AssertionError(select.getFromOptions());
        }

        visit(select.getFetchColumns());
        List<ClickHouseExpression> fromList = select.getFromList();
        if (fromList != null) {
            sb.append(" FROM ");
            visit(fromList);
        }
        // ARRAY JOIN binds to the table before any regular JOIN per ClickHouse grammar. Default-empty;
        // the generator never populates this field until type-system v2 introduces Array columns.
        List<ClickHouseExpression> arrayJoinExprs = select.getArrayJoinExprs();
        if (!arrayJoinExprs.isEmpty()) {
            sb.append(select.isArrayJoinLeft() ? " LEFT ARRAY JOIN " : " ARRAY JOIN ");
            visit(arrayJoinExprs);
        }
        List<ClickHouseExpression.ClickHouseJoin> joins = select.getJoinClauses();
        if (!joins.isEmpty()) {
            for (ClickHouseExpression.ClickHouseJoin join : joins) {
                visit(join);
            }
        }
        if (select.getWhereClause() != null) {
            sb.append(" WHERE ");
            visit(select.getWhereClause());
        }
        if (!select.getGroupByClause().isEmpty()) {
            sb.append(" GROUP BY ");
            visit(select.getGroupByClause());
        }
        if (select.getHavingClause() != null) {
            sb.append(" HAVING ");
            visit(select.getHavingClause());
        }
        if (!select.getOrderByClauses().isEmpty()) {
            sb.append(" ORDER BY ");
            visit(select.getOrderByClauses());
        }
        if (inner) {
            sb.append(")");
        }
    }

    @Override
    public void visit(ClickHouseSetOperation setOp, boolean inner) {
        if (inner) {
            sb.append("(");
        }
        renderSetOpChild(setOp.getLeft());
        sb.append(" ");
        sb.append(setOp.getOp().getKeyword());
        sb.append(" ");
        renderSetOpChild(setOp.getRight());
        if (inner) {
            sb.append(")");
        }
    }

    private void renderSetOpChild(ClickHouseExpression child) {
        if (child instanceof ClickHouseSelect) {
            visit((ClickHouseSelect) child, false);
        } else if (child instanceof ClickHouseSetOperation) {
            visit((ClickHouseSetOperation) child, true);
        } else {
            visit(child);
        }
    }

    @Override
    public void visit(ClickHouseTableReference tableReference) {
        sb.append(tableReference.getTable().getName()); // Original name, not alias.
        String alias = tableReference.getAlias();
        if (alias != null) {
            sb.append(" AS " + alias);
        }

    }

    @Override
    public void visit(ClickHouseAggregate aggregate) {
        List<ClickHouseAggregateCombinator> chain = aggregate.getChain();
        if (chain.isEmpty()) {
            // Backward-compatible plain-aggregate rendering: keep the enum's upper-case toString
            // so existing oracles that pattern-match on `SUM(...)` etc. remain unaffected.
            sb.append(aggregate.getFunc());
            sb.append("(");
            visit(aggregate.getExpr());
            sb.append(")");
            return;
        }
        // Combinator-chain rendering: fold the suffixes into the function name (lower-cased base
        // because ClickHouse's combinator-token convention is camelCase like `sumIf`), then emit
        // the expression and each combinator's extra args in declaration order inside one paren
        // group. ClickHouse is case-insensitive on the base function name; lower-case is the
        // documented convention for chained forms.
        sb.append(aggregate.getFunc().name().toLowerCase());
        for (ClickHouseAggregateCombinator combinator : chain) {
            sb.append(combinator.getSuffix().getTextual());
        }
        sb.append("(");
        visit(aggregate.getExpr());
        for (ClickHouseAggregateCombinator combinator : chain) {
            for (ClickHouseExpression extra : combinator.getExtraArgs()) {
                sb.append(", ");
                visit(extra);
            }
        }
        sb.append(")");
    }

    @Override
    public void visit(ClickHouseCastOperation cast) {
        sb.append("CAST(");
        visit(cast.getExpression());
        sb.append(" AS ");
        sb.append(cast.getType().toString());
        sb.append(")");
    }

    @Override
    public void visit(ClickHouseExpression.ClickHouseJoin join) {
        ClickHouseExpression.ClickHouseJoin.JoinType type = join.getType();
        if (type == ClickHouseExpression.ClickHouseJoin.JoinType.CROSS) {
            sb.append(" JOIN ");
            visit(join.getRightTable());
        } else if (type == ClickHouseExpression.ClickHouseJoin.JoinType.INNER) {
            sb.append(" INNER JOIN ");
            visit(join.getRightTable());
        } else if (type == ClickHouseExpression.ClickHouseJoin.JoinType.LEFT_OUTER) {
            sb.append(" LEFT OUTER JOIN ");
            visit(join.getRightTable());
        } else if (type == ClickHouseExpression.ClickHouseJoin.JoinType.RIGHT_OUTER) {
            sb.append(" RIGHT OUTER JOIN ");
            visit(join.getRightTable());
        } else if (type == ClickHouseExpression.ClickHouseJoin.JoinType.FULL_OUTER) {
            sb.append(" FULL OUTER JOIN ");
            visit(join.getRightTable());
        } else if (type == ClickHouseExpression.ClickHouseJoin.JoinType.LEFT_ANTI) {
            sb.append(" LEFT ANTI JOIN ");
            visit(join.getRightTable());
        } else if (type == ClickHouseExpression.ClickHouseJoin.JoinType.RIGHT_ANTI) {
            sb.append(" RIGHT ANTI JOIN ");
            visit(join.getRightTable());
        } else {
            throw new UnsupportedOperationException();
        }
        ClickHouseExpression onClause = join.getOnClause();
        if (onClause != null) {
            sb.append(" ON ");
            visit(onClause);
        }
    }

    @Override
    public void visit(ClickHouseColumnReference c) {
        if (c.getTableAlias() != null) {
            sb.append(c.getTableAlias());
            sb.append(".");
            sb.append(c.getColumn().getName());
        } else if (c.getColumn().getTable() == null) {
            sb.append(c.getColumn().getName());
        } else {
            sb.append(c.getColumn().getFullQualifiedName());
        }
        if (c.getAlias() != null) {
            sb.append(" AS " + c.getAlias());
        }
    }

    @Override
    public void visit(ClickHouseBinaryFunctionOperation func) {
        sb.append(func.getOperatorRepresentation());
        sb.append("(");
        visit(func.getLeft());
        sb.append(",");
        visit(func.getRight());
        sb.append(")");
    }

    @Override
    public void visit(ClickHouseAliasOperation alias) {
        visit(alias.getExpression());
        sb.append(" AS `");
        sb.append(alias.getAlias());
        sb.append("`");
    }

    public static String asString(ClickHouseExpression expr) {
        ClickHouseToStringVisitor visitor = new ClickHouseToStringVisitor();
        visitor.visit(expr);
        return visitor.get();
    }
}
