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
    /**
     * Additional positional value arguments beyond {@link #expr}, in source order. Empty for the single-argument
     * aggregates ({@code sum(x)}, {@code uniqExact(x)}); non-empty for multi-arg forms ({@code argMin(value, key)}).
     * The to-string visitor renders these immediately after {@link #expr} and before any combinator extra args.
     */
    private final List<ClickHouseExpression> extraValueArgs;
    /**
     * Combinator chain in source order; empty when the aggregate is plain (e.g., {@code SUM(x)}). Non-empty chains
     * render as {@code <funcName><Suffix1><Suffix2>(expr, extraArgsSuffix1.., extraArgsSuffix2..)}.
     */
    private final List<ClickHouseAggregateCombinator> chain;

    /**
     * Catalog of aggregate functions the generator and oracles draw from. Each entry carries:
     * <ul>
     * <li><b>textual</b> -- the rendered function name. The five historical entries keep their upper-case spelling
     * (e.g. {@code SUM}) so existing oracle/test string matches stay stable; new entries use ClickHouse's documented
     * camelCase (e.g. {@code uniqExact}).</li>
     * <li><b>numValueArgs</b> -- positional value-argument count (1 for most; 2 for {@code argMin}/{@code argMax}).
     * Single-argument call sites must only ever draw functions with {@code numValueArgs == 1}.</li>
     * <li><b>multisetSafe</b> -- whether the function is deterministic and order-insensitive, so its result is stable
     * regardless of read order / parallelism / partition split. Only multiset-safe functions may feed the multiset /
     * TLP equality oracles (plan requirement R7). {@code argMin}/{@code argMax} are NOT multiset-safe: ties on the key
     * column are broken by encounter order, which differs across read paths.</li>
     * </ul>
     */
    public enum ClickHouseAggregateFunction {
        AVG("AVG", 1, true, ClickHouseDataType.Int8, ClickHouseDataType.Int16, ClickHouseDataType.Int32,
                ClickHouseDataType.Int64, ClickHouseDataType.UInt8, ClickHouseDataType.UInt16, ClickHouseDataType.UInt32,
                ClickHouseDataType.UInt64, ClickHouseDataType.Float32, ClickHouseDataType.Float64),
        COUNT("COUNT", 1, true, ClickHouseDataType.Int8, ClickHouseDataType.Int16, ClickHouseDataType.Int32,
                ClickHouseDataType.Int64, ClickHouseDataType.UInt8, ClickHouseDataType.UInt16, ClickHouseDataType.UInt32,
                ClickHouseDataType.UInt64, ClickHouseDataType.Float32, ClickHouseDataType.Float64,
                ClickHouseDataType.String),
        MAX("MAX", 1, true), MIN("MIN", 1, true),
        SUM("SUM", 1, true, ClickHouseDataType.Int8, ClickHouseDataType.Int16, ClickHouseDataType.Int32,
                ClickHouseDataType.Int64, ClickHouseDataType.UInt8, ClickHouseDataType.UInt16, ClickHouseDataType.UInt32,
                ClickHouseDataType.UInt64, ClickHouseDataType.Float32, ClickHouseDataType.Float64),
        // Unit 3.1 additions. All single-argument and deterministic/order-insensitive (multiset-safe):
        // uniqExact -- exact distinct count (UInt64); quantileExact -- exact median (sorts internally, order-free);
        // groupBitAnd/Or/Xor -- bitwise reductions, commutative+associative (integer args only).
        UNIQ_EXACT("uniqExact", 1, true), QUANTILE_EXACT("quantileExact", 1, true, ClickHouseDataType.Int8,
                ClickHouseDataType.Int16, ClickHouseDataType.Int32, ClickHouseDataType.Int64, ClickHouseDataType.UInt8,
                ClickHouseDataType.UInt16, ClickHouseDataType.UInt32, ClickHouseDataType.UInt64,
                ClickHouseDataType.Float32, ClickHouseDataType.Float64),
        GROUP_BIT_AND("groupBitAnd", 1, true, ClickHouseDataType.Int8, ClickHouseDataType.Int16,
                ClickHouseDataType.Int32, ClickHouseDataType.Int64, ClickHouseDataType.UInt8, ClickHouseDataType.UInt16,
                ClickHouseDataType.UInt32, ClickHouseDataType.UInt64),
        GROUP_BIT_OR("groupBitOr", 1, true, ClickHouseDataType.Int8, ClickHouseDataType.Int16, ClickHouseDataType.Int32,
                ClickHouseDataType.Int64, ClickHouseDataType.UInt8, ClickHouseDataType.UInt16, ClickHouseDataType.UInt32,
                ClickHouseDataType.UInt64),
        GROUP_BIT_XOR("groupBitXor", 1, true, ClickHouseDataType.Int8, ClickHouseDataType.Int16,
                ClickHouseDataType.Int32, ClickHouseDataType.Int64, ClickHouseDataType.UInt8, ClickHouseDataType.UInt16,
                ClickHouseDataType.UInt32, ClickHouseDataType.UInt64),
        // Two-argument forms. NOT multiset-safe (tie-break is encounter-order dependent), so excluded from every
        // random draw that feeds an equality oracle; rendered + unit-tested for capability coverage.
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

        // The original five aggregates the historical oracles draw from. getRandom() keeps returning ONLY these so
        // every single-argument call site (the combinator-identity oracle, TLPAggregate's hardcoded set) is byte-for-
        // byte unchanged by the Unit 3.1 enum widening.
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

        // Random single-argument, multiset-safe aggregate -- the widened set the expression generator feeds into
        // SELECT / HAVING / aggregate contexts. Excludes argMin/argMax (2-arg, non-deterministic) so the result is
        // always a well-formed single-arg call that is safe for the equality oracles.
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
