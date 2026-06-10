package sqlancer.clickhouse.ast;

import com.clickhouse.data.ClickHouseDataType;

import sqlancer.LikeImplementationHelper;
import sqlancer.Randomly;
import sqlancer.clickhouse.ast.constant.ClickHouseCreateConstant;
import sqlancer.common.visitor.BinaryOperation;

public class ClickHouseBinaryComparisonOperation extends ClickHouseExpression
        implements BinaryOperation<ClickHouseExpression> {

    private final ClickHouseBinaryComparisonOperator operation;
    private final ClickHouseExpression left;
    private final ClickHouseExpression right;

    public ClickHouseBinaryComparisonOperation(ClickHouseExpression left, ClickHouseExpression right,
            ClickHouseBinaryComparisonOperator operation) {
        this.left = left;
        this.right = right;
        this.operation = operation;
    }

    public ClickHouseBinaryComparisonOperator getOperator() {
        return operation;
    }

    @Override
    public ClickHouseExpression getLeft() {
        return left;
    }

    @Override
    public ClickHouseExpression getRight() {
        return right;
    }

    @Override
    public String getOperatorRepresentation() {
        return operation.getTextRepresentation();
    }

    @Override
    public ClickHouseConstant getExpectedValue() {
        ClickHouseConstant leftExpected = left.getExpectedValue();
        ClickHouseConstant rightExpected = right.getExpectedValue();
        if (leftExpected == null || rightExpected == null) {
            return null;
        }

        return operation.apply(leftExpected, rightExpected);
    }

    public static ClickHouseBinaryComparisonOperation create(ClickHouseExpression leftVal,
            ClickHouseExpression rightVal, ClickHouseBinaryComparisonOperator op) {
        return new ClickHouseBinaryComparisonOperation(leftVal, rightVal, op);
    }

    public enum ClickHouseBinaryComparisonOperator {
        SMALLER("<") {
            @Override
            ClickHouseConstant apply(ClickHouseConstant left, ClickHouseConstant right) {
                return left.applyLess(right);
            }

        },
        SMALLER_EQUALS("<=") {
            @Override
            ClickHouseConstant apply(ClickHouseConstant left, ClickHouseConstant right) {
                ClickHouseConstant lessThan = left.applyLess(right);
                ClickHouseConstant equals = left.applyEquals(right);
                if (lessThan == null || equals == null) {
                    return null;
                } else {
                    if (equals.asInt() == 1) {
                        return equals;
                    } else if (lessThan.asInt() >= 1) {
                        return lessThan;
                    } else {
                        return ClickHouseCreateConstant.createFalse();
                    }
                }
            }

        },
        GREATER(">") {
            @Override
            ClickHouseConstant apply(ClickHouseConstant left, ClickHouseConstant right) {
                ClickHouseConstant equals = left.applyEquals(right);
                if (equals == null) {
                    return null;
                }
                if (equals.getDataType() == ClickHouseDataType.Int8 && equals.getDataType() == ClickHouseDataType.UInt8
                        && equals.getDataType() == ClickHouseDataType.Int16
                        && equals.getDataType() == ClickHouseDataType.UInt16
                        && equals.getDataType() == ClickHouseDataType.Int32
                        && equals.getDataType() == ClickHouseDataType.UInt32
                        && equals.getDataType() == ClickHouseDataType.Int64
                        && equals.getDataType() == ClickHouseDataType.UInt64 && equals.asInt() == 1) {
                    return ClickHouseCreateConstant.createFalse();
                } else {
                    ClickHouseConstant applyLess = left.applyLess(right);
                    if (applyLess == null) {
                        return null;
                    }
                    return ClickHouseUnaryPrefixOperation.ClickHouseUnaryPrefixOperator.NOT.apply(applyLess);
                }
            }

        },
        GREATER_EQUALS(">=") {
            @Override
            ClickHouseConstant apply(ClickHouseConstant left, ClickHouseConstant right) {
                ClickHouseConstant lessThan = left.applyLess(right);
                if (lessThan == null) {
                    return null;
                }
                if (lessThan.getDataType() == ClickHouseDataType.Int8
                        && lessThan.getDataType() == ClickHouseDataType.UInt8
                        && lessThan.getDataType() == ClickHouseDataType.Int16
                        && lessThan.getDataType() == ClickHouseDataType.UInt16
                        && lessThan.getDataType() == ClickHouseDataType.Int32
                        && lessThan.getDataType() == ClickHouseDataType.UInt32
                        && lessThan.getDataType() == ClickHouseDataType.Int64
                        && lessThan.getDataType() == ClickHouseDataType.UInt64 && lessThan.asInt() >= 1) {
                    return ClickHouseCreateConstant.createTrue();
                } else {
                    ClickHouseConstant applyLess = left.applyLess(right);
                    if (applyLess == null) {
                        return null;
                    }
                    return ClickHouseUnaryPrefixOperation.ClickHouseUnaryPrefixOperator.NOT.apply(applyLess);
                }
            }

        },
        EQUALS("=", "==") {
            @Override
            ClickHouseConstant apply(ClickHouseConstant left, ClickHouseConstant right) {
                return left.applyEquals(right);
            }

        },
        NOT_EQUALS("!=", "<>") {
            @Override
            ClickHouseConstant apply(ClickHouseConstant left, ClickHouseConstant right) {
                if (left == null || right == null) {
                    return null;
                }
                if (left.isNull() || right.isNull()) {
                    return ClickHouseCreateConstant.createNullConstant();
                } else {
                    ClickHouseConstant applyEquals = left.applyEquals(right);
                    if (applyEquals == null) {
                        return null;
                    }
                    boolean equals = applyEquals.asInt() == 1;
                    return ClickHouseCreateConstant.createBoolean(!equals);
                }
            }

        },
        LIKE("LIKE") {
            @Override
            ClickHouseConstant apply(ClickHouseConstant left, ClickHouseConstant right) {
                if (left == null || right == null) {
                    return null;
                }
                if (left.isNull() || right.isNull()) {
                    return ClickHouseCreateConstant.createNullConstant();
                }
                ClickHouseConstant leftStr = ClickHouseCast.castToText(left);
                ClickHouseConstant rightStr = ClickHouseCast.castToText(right);
                if (leftStr == null || rightStr == null) {
                    return null;
                }
                boolean val = LikeImplementationHelper.match(leftStr.asString(), rightStr.asString(), 0, 0, false);
                return ClickHouseCreateConstant.createBoolean(val);
            }

        },
        // IN / NOT IN against a subquery RHS. The right operand is a pre-rendered
        // `(SELECT ...)` fragment (ClickHouseRawText) with no constant value, so the PQS-style
        // expected-value computation is intentionally left as the no-op base `apply` (returns
        // null) -- these operators are emitted only by generatePredicate's IN-subquery branch and
        // are deliberately excluded from getRandomOperator() so a generic comparison can never
        // pick them with a scalar RHS (which would be invalid SQL).
        IN("IN"), NOT_IN("NOT IN");

        private final String[] textRepresentation;

        ClickHouseConstant apply(ClickHouseConstant left, ClickHouseConstant right) {
            return null;
        }

        ClickHouseBinaryComparisonOperator(String... textRepresentation) {
            this.textRepresentation = textRepresentation.clone();
        }

        public static ClickHouseBinaryComparisonOperator getRandomOperator() {
            // Excludes IN / NOT_IN: those require a subquery/set RHS and are emitted only by the
            // dedicated IN-subquery predicate branch, never by a generic scalar comparison.
            return Randomly.fromOptions(SMALLER, SMALLER_EQUALS, GREATER, GREATER_EQUALS, EQUALS, NOT_EQUALS, LIKE);
        }

        public static ClickHouseBinaryComparisonOperator getRandomRowValueOperator() {
            return Randomly.fromOptions(SMALLER, SMALLER_EQUALS, GREATER, GREATER_EQUALS, EQUALS, NOT_EQUALS);
        }

        public String getTextRepresentation() {
            return Randomly.fromOptions(textRepresentation);
        }
    }
}
