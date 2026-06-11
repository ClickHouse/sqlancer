package sqlancer.datafusion.gen;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import sqlancer.common.ast.BinaryOperatorNode.Operator;
import sqlancer.datafusion.DataFusionSchema.DataFusionDataType;

public class DataFusionBaseExpr implements Operator {
    public String name;
    public int nArgs;
    public DataFusionBaseExprCategory exprType;
    public List<DataFusionDataType> possibleReturnTypes;
    public List<ArgumentType> argTypes;
    public boolean isVariadic;

    DataFusionBaseExpr(String name, int nArgs, DataFusionBaseExprCategory exprCategory,
            List<DataFusionDataType> possibleReturnTypes, List<ArgumentType> argTypes, boolean isVariadic) {
        this.name = name;
        this.nArgs = nArgs;
        this.exprType = exprCategory;
        this.possibleReturnTypes = possibleReturnTypes;
        this.argTypes = argTypes;
        this.isVariadic = isVariadic;
    }

    DataFusionBaseExpr(String name, int nArgs, DataFusionBaseExprCategory exprCategory,
            List<DataFusionDataType> possibleReturnTypes, List<ArgumentType> argTypes) {
        this(name, nArgs, exprCategory, possibleReturnTypes, argTypes, false);
    }

    public static DataFusionBaseExpr createCommonNumericFuncSingleArg(String name) {
        return new DataFusionBaseExpr(name, 1, DataFusionBaseExprCategory.FUNC,
                Arrays.asList(DataFusionDataType.BIGINT, DataFusionDataType.DOUBLE),
                Arrays.asList(new ArgumentType.Fixed(
                        new ArrayList<>(Arrays.asList(DataFusionDataType.BIGINT, DataFusionDataType.DOUBLE)))));
    }

    public static DataFusionBaseExpr createCommonNumericAggrFuncSingleArg(String name) {
        return new DataFusionBaseExpr(name, 1, DataFusionBaseExprCategory.AGGREGATE,
                Arrays.asList(DataFusionDataType.BIGINT, DataFusionDataType.DOUBLE),
                Arrays.asList(new ArgumentType.Fixed(
                        new ArrayList<>(Arrays.asList(DataFusionDataType.BIGINT, DataFusionDataType.DOUBLE)))));
    }

    public static DataFusionBaseExpr createCommonNumericFuncTwoArgs(String name) {
        return new DataFusionBaseExpr(name, 2, DataFusionBaseExprCategory.FUNC,
                Arrays.asList(DataFusionDataType.BIGINT, DataFusionDataType.DOUBLE),
                Arrays.asList(
                        new ArgumentType.Fixed(
                                new ArrayList<>(Arrays.asList(DataFusionDataType.BIGINT, DataFusionDataType.DOUBLE))),
                        new ArgumentType.Fixed(
                                new ArrayList<>(Arrays.asList(DataFusionDataType.BIGINT, DataFusionDataType.DOUBLE)))));
    }

    @Override
    public String getTextRepresentation() {
        return name;
    }

    @Override
    public String toString() {
        return name;
    }

    public enum DataFusionBaseExprCategory {
        UNARY_PREFIX, UNARY_POSTFIX, BINARY, FUNC, AGGREGATE
    }

    public enum DataFusionBaseExprType {

        IS_NULL,
        IS_NOT_NULL,

        ADD,
        SUB,
        MULTIPLICATION,
        DIVISION,
        MODULO,

        EQUAL,
        EQUAL2,
        NOT_EQUAL,
        LESS_THAN,
        LESS_THAN_OR_EQUAL_TO,
        GREATER_THAN,
        GREATER_THAN_OR_EQUAL_TO,

        IS_DISTINCT_FROM,
        IS_NOT_DISTINCT_FROM,

        AND,
        OR,

        BITWISE_AND,
        BITWISE_OR,
        BITWISE_XOR,
        BITWISE_SHIFT_RIGHT,
        BITWISE_SHIFT_LEFT,

        NOT,
        PLUS,
        MINUS,

        FUNC_ABS,
        FUNC_ACOS,
        FUNC_ACOSH,
        FUNC_ASIN,
        FUNC_ASINH,
        FUNC_ATAN,
        FUNC_ATANH,
        FUNC_ATAN2,
        FUNC_CBRT,
        FUNC_CEIL,
        FUNC_COS,
        FUNC_COSH,
        FUNC_DEGREES,
        FUNC_EXP,
        FUNC_FACTORIAL,
        FUNC_FLOOR,
        FUNC_GCD,
        FUNC_ISNAN,
        FUNC_ISZERO,
        FUNC_LCM,
        FUNC_LN,
        FUNC_LOG,
        FUNC_LOG_WITH_BASE,
        FUNC_LOG10,
        FUNC_LOG2,
        FUNC_NANVL,
        FUNC_PI,
        FUNC_POW,
        FUNC_POWER,
        FUNC_RADIANS,

        FUNC_ROUND,
        FUNC_ROUND_WITH_DECIMAL,
        FUNC_SIGNUM,
        FUNC_SIN,
        FUNC_SINH,
        FUNC_SQRT,
        FUNC_TAN,
        FUNC_TANH,
        FUNC_TRUNC,
        FUNC_TRUNC_WITH_DECIMAL,

        FUNC_COALESCE,
        FUNC_NULLIF,
        FUNC_NVL,
        FUNC_NVL2,
        FUNC_IFNULL,

        AGGR_MIN, AGGR_MAX, AGGR_SUM, AGGR_AVG, AGGR_COUNT,
    }

    public abstract static class ArgumentType {
        private ArgumentType() {
        }

        public static class SameAsReturnType extends ArgumentType {
        }

        public static class SameAsFirstArgType extends ArgumentType {
        }

        public static class Fixed extends ArgumentType {
            public List<DataFusionDataType> fixedType;

            public Fixed(List<DataFusionDataType> fixedType) {
                this.fixedType = fixedType;
            }

            public List<DataFusionDataType> getType() {
                return fixedType;
            }
        }
    }
}
