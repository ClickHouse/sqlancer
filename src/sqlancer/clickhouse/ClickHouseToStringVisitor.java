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

        if (!select.getWithClauses().isEmpty()) {
            sb.append("WITH ");
            for (int i = 0; i < select.getWithClauses().size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                visit(select.getWithClauses().get(i));
            }
            sb.append(" ");
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

        if (select.isFinal()) {
            sb.append(" FINAL");
        }

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

        if (select.getPrewhereClause() != null) {
            sb.append(" PREWHERE ");
            visit(select.getPrewhereClause());
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
        sb.append(tableReference.getTable().getName());
        String alias = tableReference.getAlias();
        if (alias != null) {
            sb.append(" AS " + alias);
        }

    }

    @Override
    public void visit(ClickHouseAggregate aggregate) {
        List<ClickHouseAggregateCombinator> chain = aggregate.getChain();
        if (chain.isEmpty()) {

            sb.append(aggregate.getFunc().getName());
            sb.append("(");
            visit(aggregate.getExpr());
            for (ClickHouseExpression extra : aggregate.getExtraValueArgs()) {
                sb.append(", ");
                visit(extra);
            }
            sb.append(")");
            return;
        }

        sb.append(aggregate.getFunc().getName().toLowerCase());
        for (ClickHouseAggregateCombinator combinator : chain) {
            sb.append(combinator.getSuffix().getTextual());
        }
        sb.append("(");
        visit(aggregate.getExpr());
        for (ClickHouseExpression extra : aggregate.getExtraValueArgs()) {
            sb.append(", ");
            visit(extra);
        }
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
        sb.append(cast.getCompoundType().toString());
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
        } else if (type == ClickHouseExpression.ClickHouseJoin.JoinType.LEFT_ANY) {
            sb.append(" LEFT ANY JOIN ");
            visit(join.getRightTable());
        } else if (type == ClickHouseExpression.ClickHouseJoin.JoinType.RIGHT_ANY) {
            sb.append(" RIGHT ANY JOIN ");
            visit(join.getRightTable());
        } else if (type == ClickHouseExpression.ClickHouseJoin.JoinType.ANY_INNER) {
            sb.append(" ANY INNER JOIN ");
            visit(join.getRightTable());
        } else if (type == ClickHouseExpression.ClickHouseJoin.JoinType.LEFT_SEMI) {
            sb.append(" LEFT SEMI JOIN ");
            visit(join.getRightTable());
        } else if (type == ClickHouseExpression.ClickHouseJoin.JoinType.RIGHT_SEMI) {
            sb.append(" RIGHT SEMI JOIN ");
            visit(join.getRightTable());
        } else if (type == ClickHouseExpression.ClickHouseJoin.JoinType.ASOF_INNER) {
            sb.append(" ASOF JOIN ");
            visit(join.getRightTable());
        } else if (type == ClickHouseExpression.ClickHouseJoin.JoinType.ASOF_LEFT_OUTER) {
            sb.append(" ASOF LEFT JOIN ");
            visit(join.getRightTable());
        } else if (type == ClickHouseExpression.ClickHouseJoin.JoinType.PASTE) {
            sb.append(" PASTE JOIN ");
            visit(join.getRightTable());
        } else if (type == ClickHouseExpression.ClickHouseJoin.JoinType.INNER_ALL) {
            sb.append(" ALL INNER JOIN ");
            visit(join.getRightTable());
        } else if (type == ClickHouseExpression.ClickHouseJoin.JoinType.INNER_DISTINCT) {
            sb.append(" DISTINCT INNER JOIN ");
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
        if (c.getTableAlias() != null && !c.getTableAlias().isEmpty()) {
            sb.append(c.getTableAlias());
            sb.append(".");
            sb.append(c.getColumn().getName());
        } else if (c.getTableAlias() != null) {

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
    public void visit(sqlancer.clickhouse.ast.ClickHouseRawText raw) {
        sb.append(raw.getSql());
    }

    @Override
    public void visit(ClickHouseExpression.ClickHousePostfixText op) {

        if (op.getExpression() != null) {
            visit(op.getExpression());
        }
        sb.append(op.getText());
    }

    @Override
    public void visit(sqlancer.clickhouse.ast.ClickHouseTupleAccess access) {
        visit(access.getTuple());
        sb.append(".").append(access.getIndex());
    }

    @Override
    public void visit(sqlancer.clickhouse.ast.ClickHouseMapAccess access) {
        visit(access.getMap());
        sb.append("[");
        visit(access.getKey());
        sb.append("]");
    }

    @Override
    public void visit(sqlancer.clickhouse.ast.ClickHouseJsonPath path) {
        visit(path.getJson());
        for (String seg : path.getPath()) {
            sb.append(".").append(seg);
        }
        if (path.getTypeCast() != null) {
            sb.append(".^").append(path.getTypeCast());
        }
    }

    @Override
    public void visit(sqlancer.clickhouse.ast.ClickHouseVariantElement element) {
        if (element.isFunctionForm()) {
            sb.append("variantElement(");
            visit(element.getVariant());
            sb.append(", '").append(element.getElementType()).append("')");
        } else {
            visit(element.getVariant());
            sb.append(".").append(element.getElementType());
        }
    }

    @Override
    public void visit(sqlancer.clickhouse.ast.ClickHouseDynamicElement element) {
        if (element.isFunctionForm()) {
            sb.append("dynamicElement(");
            visit(element.getDyn());
            sb.append(", '").append(element.getElementType()).append("')");
        } else {
            visit(element.getDyn());
            sb.append(".").append(element.getElementType());
        }
    }

    @Override
    public void visit(sqlancer.clickhouse.ast.ClickHouseWindowFunction window) {
        sb.append(window.renderName()).append("(");
        if (window.getArgument() != null) {
            visit(window.getArgument());
        }
        sb.append(")");
        sb.append(" OVER (");
        boolean spaceNeeded = false;
        if (!window.getPartitionBy().isEmpty()) {
            sb.append("PARTITION BY ");
            for (int i = 0; i < window.getPartitionBy().size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                visit(window.getPartitionBy().get(i));
            }
            spaceNeeded = true;
        }
        if (!window.getOrderBy().isEmpty()) {
            if (spaceNeeded) {
                sb.append(" ");
            }
            sb.append("ORDER BY ");
            for (int i = 0; i < window.getOrderBy().size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                visit(window.getOrderBy().get(i));
            }
        }
        sb.append(")");
    }

    @Override
    public void visit(sqlancer.clickhouse.ast.ClickHouseLambda lambda) {

        sb.append("(");
        java.util.List<String> params = lambda.getParams();
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(params.get(i));
        }
        sb.append(") -> ");
        visit(lambda.getBody());
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
