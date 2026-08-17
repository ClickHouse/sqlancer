package sqlancer.clickhouse;

import sqlancer.clickhouse.ast.ClickHouseAggregate;
import sqlancer.clickhouse.ast.ClickHouseAliasOperation;
import sqlancer.clickhouse.ast.ClickHouseBinaryComparisonOperation;
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

public interface ClickHouseVisitor {

    default void visit(ClickHouseBinaryComparisonOperation op) {

    }

    default void visit(ClickHouseBinaryLogicalOperation op) {

    }

    default void visit(ClickHouseUnaryPrefixOperation exp) {

    }

    default void visit(ClickHouseUnaryPostfixOperation op) {

    }

    default void visit(ClickHouseConstant c) {

    }

    default void visit(ClickHouseSelect s, boolean inner) {

    };

    default void visit(ClickHouseSetOperation s, boolean inner) {

    };

    default void visit(ClickHouseColumnReference columnReference) {

    };

    default void visit(ClickHouseExpression.ClickHousePostfixText op) {

    }

    void visit(ClickHouseTableReference tableReference);

    void visit(ClickHouseCastOperation cast);

    void visit(ClickHouseAliasOperation alias);

    void visit(ClickHouseExpression.ClickHouseJoin join);

    void visit(ClickHouseAggregate aggregate);

    void visit(ClickHouseBinaryFunctionOperation func);

    void visit(sqlancer.clickhouse.ast.ClickHouseLambda lambda);

    void visit(sqlancer.clickhouse.ast.ClickHouseWindowFunction window);

    void visit(sqlancer.clickhouse.ast.ClickHouseTupleAccess access);

    void visit(sqlancer.clickhouse.ast.ClickHouseMapAccess access);

    void visit(sqlancer.clickhouse.ast.ClickHouseJsonPath path);

    void visit(sqlancer.clickhouse.ast.ClickHouseVariantElement element);

    void visit(sqlancer.clickhouse.ast.ClickHouseDynamicElement element);

    void visit(sqlancer.clickhouse.ast.ClickHouseRawText raw);

    void visit(sqlancer.clickhouse.ast.ClickHouseWrappedExpression wrapped);

    default void visit(ClickHouseExpression expr) {
        if (expr instanceof ClickHouseBinaryFunctionOperation) {
            visit((ClickHouseBinaryFunctionOperation) expr);
        } else if (expr instanceof ClickHouseBinaryComparisonOperation) {
            visit((ClickHouseBinaryComparisonOperation) expr);
        } else if (expr instanceof ClickHouseBinaryLogicalOperation) {
            visit((ClickHouseBinaryLogicalOperation) expr);
        } else if (expr instanceof ClickHouseConstant) {
            visit((ClickHouseConstant) expr);
        } else if (expr instanceof ClickHouseUnaryPrefixOperation) {
            visit((ClickHouseUnaryPrefixOperation) expr);
        } else if (expr instanceof ClickHouseSelect) {
            visit((ClickHouseSelect) expr, true);
        } else if (expr instanceof ClickHouseSetOperation) {
            visit((ClickHouseSetOperation) expr, true);
        } else if (expr instanceof ClickHouseColumnReference) {
            visit((ClickHouseColumnReference) expr);
        } else if (expr instanceof ClickHouseTableReference) {
            visit((ClickHouseTableReference) expr);
        } else if (expr instanceof ClickHouseCastOperation) {
            visit((ClickHouseCastOperation) expr);
        } else if (expr instanceof ClickHouseExpression.ClickHouseJoin) {
            visit((ClickHouseExpression.ClickHouseJoin) expr);
        } else if (expr instanceof ClickHouseExpression.ClickHousePostfixText) {
            visit((ClickHouseExpression.ClickHousePostfixText) expr);
        } else if (expr instanceof ClickHouseAggregate) {
            visit((ClickHouseAggregate) expr);
        } else if (expr instanceof ClickHouseAliasOperation) {
            visit((ClickHouseAliasOperation) expr);
        } else if (expr instanceof sqlancer.clickhouse.ast.ClickHouseLambda) {
            visit((sqlancer.clickhouse.ast.ClickHouseLambda) expr);
        } else if (expr instanceof sqlancer.clickhouse.ast.ClickHouseWindowFunction) {
            visit((sqlancer.clickhouse.ast.ClickHouseWindowFunction) expr);
        } else if (expr instanceof sqlancer.clickhouse.ast.ClickHouseTupleAccess) {
            visit((sqlancer.clickhouse.ast.ClickHouseTupleAccess) expr);
        } else if (expr instanceof sqlancer.clickhouse.ast.ClickHouseMapAccess) {
            visit((sqlancer.clickhouse.ast.ClickHouseMapAccess) expr);
        } else if (expr instanceof sqlancer.clickhouse.ast.ClickHouseJsonPath) {
            visit((sqlancer.clickhouse.ast.ClickHouseJsonPath) expr);
        } else if (expr instanceof sqlancer.clickhouse.ast.ClickHouseVariantElement) {
            visit((sqlancer.clickhouse.ast.ClickHouseVariantElement) expr);
        } else if (expr instanceof sqlancer.clickhouse.ast.ClickHouseDynamicElement) {
            visit((sqlancer.clickhouse.ast.ClickHouseDynamicElement) expr);
        } else if (expr instanceof sqlancer.clickhouse.ast.ClickHouseRawText) {
            visit((sqlancer.clickhouse.ast.ClickHouseRawText) expr);
        } else if (expr instanceof sqlancer.clickhouse.ast.ClickHouseWrappedExpression) {
            visit((sqlancer.clickhouse.ast.ClickHouseWrappedExpression) expr);
        } else if (expr instanceof ClickHouseExpression.ClickHouseJoinOnClause) {
            visit((ClickHouseExpression.ClickHouseJoinOnClause) expr);
        } else {
            throw new AssertionError(expr);
        }
    }

    static String asString(ClickHouseExpression expr) {
        if (expr == null) {
            throw new AssertionError();
        }
        ClickHouseToStringVisitor visitor = new ClickHouseToStringVisitor();
        if (expr instanceof ClickHouseSelect) {
            visitor.visit((ClickHouseSelect) expr, false);
        } else if (expr instanceof ClickHouseSetOperation) {
            visitor.visit((ClickHouseSetOperation) expr, false);
        } else {
            visitor.visit(expr);
        }
        return visitor.get();
    }

}
