package sqlancer.clickhouse.ast;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import com.clickhouse.data.ClickHouseDataType;

import sqlancer.Randomly;
import sqlancer.clickhouse.ClickHouseSchema;

public class ClickHouseAggregate extends ClickHouseExpression {

    private final ClickHouseAggregate.ClickHouseAggregateFunction func;
    private final ClickHouseExpression expr;

    private final List<ClickHouseExpression> extraValueArgs;

    private final List<ClickHouseAggregateCombinator> chain;

    public enum ClickHouseAggregateFunction {
        AVG("AVG", 1, true, ClickHouseDataType.Int8, ClickHouseDataType.Int16, ClickHouseDataType.Int32,
                ClickHouseDataType.Int64, ClickHouseDataType.UInt8, ClickHouseDataType.UInt16,
                ClickHouseDataType.UInt32, ClickHouseDataType.UInt64, ClickHouseDataType.Float32,
                ClickHouseDataType.Float64),
        COUNT("COUNT", 1, true, ClickHouseDataType.Int8, ClickHouseDataType.Int16, ClickHouseDataType.Int32,
                ClickHouseDataType.Int64, ClickHouseDataType.UInt8, ClickHouseDataType.UInt16,
                ClickHouseDataType.UInt32, ClickHouseDataType.UInt64, ClickHouseDataType.Float32,
                ClickHouseDataType.Float64, ClickHouseDataType.String),
        MAX("MAX", 1, true), MIN("MIN", 1, true),
        SUM("SUM", 1, true, ClickHouseDataType.Int8, ClickHouseDataType.Int16, ClickHouseDataType.Int32,
                ClickHouseDataType.Int64, ClickHouseDataType.UInt8, ClickHouseDataType.UInt16,
                ClickHouseDataType.UInt32, ClickHouseDataType.UInt64, ClickHouseDataType.Float32,
                ClickHouseDataType.Float64),

        UNIQ_EXACT("uniqExact", 1, true),
        QUANTILE_EXACT("quantileExact", 1, true, ClickHouseDataType.Int8, ClickHouseDataType.Int16,
                ClickHouseDataType.Int32, ClickHouseDataType.Int64, ClickHouseDataType.UInt8, ClickHouseDataType.UInt16,
                ClickHouseDataType.UInt32, ClickHouseDataType.UInt64, ClickHouseDataType.Float32,
                ClickHouseDataType.Float64),
        GROUP_BIT_AND("groupBitAnd", 1, true, ClickHouseDataType.Int8, ClickHouseDataType.Int16,
                ClickHouseDataType.Int32, ClickHouseDataType.Int64, ClickHouseDataType.UInt8, ClickHouseDataType.UInt16,
                ClickHouseDataType.UInt32, ClickHouseDataType.UInt64),
        GROUP_BIT_OR("groupBitOr", 1, true, ClickHouseDataType.Int8, ClickHouseDataType.Int16, ClickHouseDataType.Int32,
                ClickHouseDataType.Int64, ClickHouseDataType.UInt8, ClickHouseDataType.UInt16,
                ClickHouseDataType.UInt32, ClickHouseDataType.UInt64),
        GROUP_BIT_XOR("groupBitXor", 1, true, ClickHouseDataType.Int8, ClickHouseDataType.Int16,
                ClickHouseDataType.Int32, ClickHouseDataType.Int64, ClickHouseDataType.UInt8, ClickHouseDataType.UInt16,
                ClickHouseDataType.UInt32, ClickHouseDataType.UInt64),

        ARG_MIN("argMin", 2, false), ARG_MAX("argMax", 2, false);

        private final String textual;
        private final int numValueArgs;
        private final boolean multisetSafe;
        private ClickHouseDataType[] supportedReturnTypes;

        ClickHouseAggregateFunction(String textual, int numValueArgs, boolean multisetSafe,
                ClickHouseDataType... supportedReturnTypes) {
            this.textual = textual;
            this.numValueArgs = numValueArgs;
            this.multisetSafe = multisetSafe;
            this.supportedReturnTypes = supportedReturnTypes.clone();
        }

        private static final List<ClickHouseAggregateFunction> BASE = List.of(AVG, COUNT, MAX, MIN, SUM);

        public String getName() {
            return textual;
        }

        public int getNumValueArgs() {
            return numValueArgs;
        }

        public boolean isMultisetSafe() {
            return multisetSafe;
        }

        public static ClickHouseAggregateFunction getRandom() {
            return Randomly.fromList(BASE);
        }

        public static ClickHouseAggregateFunction getRandom(ClickHouseDataType type) {
            return Randomly.fromList(BASE);
        }

        public static ClickHouseAggregateFunction getRandomScalar() {
            List<ClickHouseAggregateFunction> pool = Arrays.asList(values()).stream()
                    .filter(f -> f.numValueArgs == 1 && f.multisetSafe).collect(Collectors.toList());
            return Randomly.fromList(pool);
        }

        public ClickHouseDataType getType(ClickHouseDataType returnType) {
            return returnType;
        }

        public boolean supportsReturnType(ClickHouseDataType returnType) {
            return Arrays.asList(supportedReturnTypes).stream().anyMatch(t -> t == returnType)
                    || supportedReturnTypes.length == 0;
        }

        public static List<ClickHouseAggregateFunction> getAggregates(ClickHouseDataType type) {
            return Arrays.asList(values()).stream().filter(p -> p.supportsReturnType(type))
                    .collect(Collectors.toList());
        }

        public ClickHouseSchema.ClickHouseLancerDataType getRandomReturnType() {
            if (supportedReturnTypes.length == 0) {
                return ClickHouseSchema.ClickHouseLancerDataType.getRandom();
            } else {
                return new ClickHouseSchema.ClickHouseLancerDataType(Randomly.fromOptions(supportedReturnTypes));
            }
        }

    }

    public ClickHouseAggregate(ClickHouseExpression expr, ClickHouseAggregateFunction func) {
        this(expr, func, Collections.emptyList());
    }

    public ClickHouseAggregate(ClickHouseExpression expr, ClickHouseAggregateFunction func,
            List<ClickHouseAggregateCombinator> chain) {
        this(expr, func, Collections.emptyList(), chain);
    }

    public ClickHouseAggregate(ClickHouseExpression expr, ClickHouseAggregateFunction func,
            List<ClickHouseExpression> extraValueArgs, List<ClickHouseAggregateCombinator> chain) {
        this.expr = expr;
        this.func = func;
        this.extraValueArgs = extraValueArgs == null ? Collections.emptyList() : List.copyOf(extraValueArgs);
        this.chain = chain == null ? Collections.emptyList() : List.copyOf(chain);
    }

    public ClickHouseAggregate.ClickHouseAggregateFunction getFunc() {
        return func;
    }

    public ClickHouseExpression getExpr() {
        return expr;
    }

    public List<ClickHouseExpression> getExtraValueArgs() {
        return extraValueArgs;
    }

    public List<ClickHouseAggregateCombinator> getChain() {
        return chain;
    }

}
