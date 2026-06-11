package sqlancer.clickhouse.ast;

import sqlancer.Randomly;

public class ClickHouseBinaryFunctionOperation extends ClickHouseExpression {

    public enum ClickHouseBinaryFunctionOperator {

        INT_DIV("intDiv", true), GCD("gcd", true), LCM("lcm", true), MAX2("max2", false), MIN2("min2", false),
        POW("pow", false);

        private final String textRepresentation;
        private final boolean requiresIntegerOperands;

        ClickHouseBinaryFunctionOperator(String textRepresentation, boolean requiresIntegerOperands) {
            this.textRepresentation = textRepresentation;
            this.requiresIntegerOperands = requiresIntegerOperands;
        }

        public static ClickHouseBinaryFunctionOperator getRandom() {
            return Randomly.fromOptions(values());
        }

        public static ClickHouseBinaryFunctionOperator getRandomAnyNumeric() {
            return Randomly.fromOptions(MAX2, MIN2, POW);
        }

        public boolean requiresIntegerOperands() {
            return requiresIntegerOperands;
        }

        public String getTextRepresentation() {
            return textRepresentation;
        }
    }

    private final ClickHouseBinaryFunctionOperator operation;
    private final ClickHouseExpression left;
    private final ClickHouseExpression right;

    public ClickHouseBinaryFunctionOperation(ClickHouseExpression left, ClickHouseExpression right,
            ClickHouseBinaryFunctionOperator operation) {
        this.left = left;
        this.right = right;
        this.operation = operation;
    }

    public ClickHouseBinaryFunctionOperator getOperator() {
        return operation;
    }

    public ClickHouseExpression getLeft() {
        return left;
    }

    public ClickHouseExpression getRight() {
        return right;
    }

    public String getOperatorRepresentation() {
        return operation.getTextRepresentation();
    }

    public static ClickHouseBinaryFunctionOperation create(ClickHouseExpression left, ClickHouseExpression right,
            ClickHouseBinaryFunctionOperator op) {
        return new ClickHouseBinaryFunctionOperation(left, right, op);
    }

}
