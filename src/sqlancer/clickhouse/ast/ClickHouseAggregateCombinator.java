package sqlancer.clickhouse.ast;

import java.util.Collections;
import java.util.List;

/**
 * Suffix that combines with a base aggregate function to form a combinator chain.
 *
 * <p>
 * Each combinator carries a {@link Suffix} (textual form + extra-arg-count contract) and a list of expressions that
 * become the extra arguments to the aggregate call. The expression generator constructs combinators that respect the
 * suffix's required-arg-count; rendering is mechanical -- the to-string visitor folds the chain in declaration order
 * and appends the extra args to the aggregate-call parens.
 * </p>
 *
 * <p>
 * Chain order is significant in ClickHouse ({@code sumIfArray} differs from {@code sumArrayIf}); the chain field on
 * {@link ClickHouseAggregate} preserves order at the AST level and the visitor never reorders it.
 * </p>
 */
public class ClickHouseAggregateCombinator {

    public enum Suffix {
        IF("If", 1), OR_NULL("OrNull", 0), OR_DEFAULT("OrDefault", 0), DISTINCT("Distinct", 0), ARRAY("Array", 0),
        STATE("State", 0), MERGE("Merge", 0), FOR_EACH("ForEach", 0), RESAMPLE("Resample", 3), MAP("Map", 0);

        private final String textual;
        private final int requiredArgCount;

        Suffix(String textual, int requiredArgCount) {
            this.textual = textual;
            this.requiredArgCount = requiredArgCount;
        }

        public String getTextual() {
            return textual;
        }

        public int getRequiredArgCount() {
            return requiredArgCount;
        }
    }

    private final Suffix suffix;
    private final List<ClickHouseExpression> extraArgs;

    public ClickHouseAggregateCombinator(Suffix suffix, List<ClickHouseExpression> extraArgs) {
        this.suffix = suffix;
        this.extraArgs = extraArgs == null ? Collections.emptyList() : List.copyOf(extraArgs);
    }

    public ClickHouseAggregateCombinator(Suffix suffix) {
        this(suffix, Collections.emptyList());
    }

    public Suffix getSuffix() {
        return suffix;
    }

    public List<ClickHouseExpression> getExtraArgs() {
        return extraArgs;
    }
}
