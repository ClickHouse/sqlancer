package sqlancer.clickhouse.oracle.tlp;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import com.clickhouse.data.ClickHouseDataType;

import sqlancer.Randomly;
import sqlancer.clickhouse.ClickHouseSchema.ClickHouseColumn;
import sqlancer.clickhouse.ClickHouseSchema.ClickHouseLancerDataType;
import sqlancer.clickhouse.ast.ClickHouseAggregate;

/**
 * Typed algebraic-identity catalog for the combinator-identity oracle.
 *
 * <p>
 * Each entry pairs a combinator-form template with an equivalent rewrite that does not use the combinator. The
 * {@code safeForType} predicate filters out columns whose runtime type would make the rewrite version diverge (NULL
 * propagation, intermediate widening, etc.). The {@code settings} field records the
 * {@code aggregate_functions_null_for_empty} value the identity needs: {@code =1} is the default, but the
 * {@code -OrNull} family forces {@code =0} to avoid the setting double-encoding the empty-NULL semantics on top of the
 * combinator (plan, Unit 5).
 * </p>
 */
final class ClickHouseCombinatorIdentities {

    // Operand context passed to rewrite templates. `xSql` is the value/condition; `condSql` is the
    // IF predicate (null when unused).
    record IdentityArgs(String xSql, String condSql) {
    }

    record Identity(String name, Predicate<ClickHouseAggregate.ClickHouseAggregateFunction> safeForFunc,
            Predicate<ClickHouseLancerDataType> safeForType, String settings, boolean needsCondition,
            java.util.function.Function<IdentityArgs, String> combinatorForm,
            java.util.function.Function<IdentityArgs, String> rewriteForm) {
    }

    static final String SETTINGS_NULL_FOR_EMPTY_ON = "aggregate_functions_null_for_empty=1, enable_optimize_predicate_expression=0";
    static final String SETTINGS_NULL_FOR_EMPTY_OFF = "aggregate_functions_null_for_empty=0, enable_optimize_predicate_expression=0";

    /**
     * v1 catalog. Each row is auditable independently: name, applicability predicates, settings, and the two SQL
     * templates. New identities land as one-row additions. Multi-extra-arg chains (e.g., sumIfResample) are explicitly
     * excluded -- the catalog's value comes from named simple rewrites, not from cross-product enumeration.
     */
    static final List<Identity> CATALOG = List.of(
            new Identity("sumIf", fn -> fn == ClickHouseAggregate.ClickHouseAggregateFunction.SUM,
                    ClickHouseCombinatorIdentities::isNumericType, SETTINGS_NULL_FOR_EMPTY_ON, true,
                    args -> "sumIf(" + args.xSql() + ", " + args.condSql() + ")",
                    args -> "sum(if(" + args.condSql() + ", " + args.xSql() + ", 0))"),
            new Identity("countIf", fn -> fn == ClickHouseAggregate.ClickHouseAggregateFunction.COUNT, t -> true,
                    SETTINGS_NULL_FOR_EMPTY_ON, true, args -> "countIf(" + args.condSql() + ")",
                    args -> "sum(toUInt64(" + args.condSql() + "))"),
            new Identity("avgOrNull", fn -> fn == ClickHouseAggregate.ClickHouseAggregateFunction.AVG,
                    ClickHouseCombinatorIdentities::isNumericType, SETTINGS_NULL_FOR_EMPTY_OFF, false,
                    args -> "avgOrNull(" + args.xSql() + ")",
                    args -> "if(count(" + args.xSql() + ")=0, NULL, sum(" + args.xSql() + ")/count(" + args.xSql()
                            + "))"),
            new Identity("sumOrNull", fn -> fn == ClickHouseAggregate.ClickHouseAggregateFunction.SUM,
                    ClickHouseCombinatorIdentities::isNumericType, SETTINGS_NULL_FOR_EMPTY_OFF, false,
                    args -> "sumOrNull(" + args.xSql() + ")",
                    args -> "if(count(" + args.xSql() + ")=0, NULL, sum(" + args.xSql() + "))"),
            new Identity("minIf", fn -> fn == ClickHouseAggregate.ClickHouseAggregateFunction.MIN,
                    ClickHouseCombinatorIdentities::isNumericType, SETTINGS_NULL_FOR_EMPTY_ON, true,
                    args -> "minIf(" + args.xSql() + ", " + args.condSql() + ")",
                    args -> "min(if(" + args.condSql() + ", " + args.xSql() + ", NULL))"),
            new Identity("maxIf", fn -> fn == ClickHouseAggregate.ClickHouseAggregateFunction.MAX,
                    ClickHouseCombinatorIdentities::isNumericType, SETTINGS_NULL_FOR_EMPTY_ON, true,
                    args -> "maxIf(" + args.xSql() + ", " + args.condSql() + ")",
                    args -> "max(if(" + args.condSql() + ", " + args.xSql() + ", NULL))"));

    private ClickHouseCombinatorIdentities() {
    }

    // Pick an identity whose predicates accept the (aggregate, column-type) pair. Returns empty
    // when no entry in the catalog matches -- the caller treats that as IgnoreMeException.
    static Optional<Identity> pickIdentity(Randomly r, ClickHouseAggregate.ClickHouseAggregateFunction agg,
            ClickHouseLancerDataType colType) {
        List<Identity> eligible = CATALOG.stream().filter(id -> id.safeForFunc().test(agg))
                .filter(id -> id.safeForType().test(colType)).toList();
        if (eligible.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(Randomly.fromList(eligible));
    }

    static boolean isNumericType(ClickHouseLancerDataType t) {
        ClickHouseDataType dt = t.getType();
        switch (dt) {
        case Int8:
        case Int16:
        case Int32:
        case Int64:
        case UInt8:
        case UInt16:
        case UInt32:
        case UInt64:
        case Float32:
        case Float64:
            return true;
        default:
            return false;
        }
    }

    static boolean isColumnSuitableAsValue(ClickHouseColumn col) {
        return isNumericType(col.getType());
    }
}
