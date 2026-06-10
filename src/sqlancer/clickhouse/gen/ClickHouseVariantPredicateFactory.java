package sqlancer.clickhouse.gen;

import java.util.List;

import sqlancer.Randomly;

/**
 * Unit 10 of plan 2026-06-10-002: Variant predicate-side coverage. 26.1 added Variant support in all functions (PR
 * #90900) and turned {@code use_variant_as_common_type} on by default (PR #90677). The client-v2 RowBinary reader
 * CANNOT decode a projected {@code Variant} column -- it throws {@code IndexOutOfBoundsException} and kills the worker
 * (R4) -- so Variant values may appear ONLY inside WHERE predicates. Every fragment rendered here is a self-contained,
 * parenthesized Boolean expression that constructs the Variant via CAST, consumes it via {@code variantElement} /
 * {@code variantType} / Variant equality, and never lets it escape to a fetch column.
 *
 * <p>
 * Smoke-run blocking condition: any reader {@code IndexOutOfBoundsException} in a run with
 * {@code --variant-where-emission} on means a Variant leaked into a fetch column -- a unit-blocking bug, fix before
 * enabling further.
 *
 * <p>
 * Callers must pass <b>total</b> inner expressions: integer expressions are {@code toInt64(...)}-wrapped (total for any
 * integer source -- wrap-around, never throws -- so {@code CAST(... AS Variant(Int64, String))} cannot fail) and
 * string expressions are
 * {@code toString(...)}-wrapped (total on every CH type).
 */
public final class ClickHouseVariantPredicateFactory {

    private ClickHouseVariantPredicateFactory() {
    }

    /**
     * Gate for the generatePredicate branch: reachable only when {@code --variant-where-emission} is on AND the
     * per-call low-probability roll succeeds. Factored out so the flag-off unreachability invariant is directly
     * unit-testable.
     *
     * @param variantWhereEmission
     *            the {@code ClickHouseOptions.variantWhereEmission} flag value
     * @param lowProbabilityRoll
     *            the per-call probability roll (e.g. {@code Randomly.getBooleanWithSmallProbability()})
     *
     * @return whether the Variant predicate branch may emit
     */
    static boolean gateOpen(boolean variantWhereEmission, boolean lowProbabilityRoll) {
        return variantWhereEmission && lowProbabilityRoll;
    }

    // Shape 1: variantElement extraction compared to a typed constant. variantElement(v, 'Int64')
    // returns Nullable(Int64) -- the comparison yields NULL on the inactive/NULL arm, which is
    // falsy in WHERE and deterministic, so the TLP partition invariant holds.
    static String renderVariantElementEquals(String intExpr, String intLiteral) {
        return "(variantElement(CAST((" + intExpr + ") AS Variant(Int64, String)), 'Int64') = " + intLiteral + ")";
    }

    // Shape 2: variantType discriminator compared to a type-name literal (String-typed both sides).
    static String renderVariantTypeEquals(String innerExpr, String activeTypeName) {
        return "(variantType(CAST((" + innerExpr + ") AS Variant(Int64, String))) = '" + activeTypeName + "')";
    }

    // Shape 3: Variant-vs-Variant equality -- the 26.1 "Variant in all functions" comparison dispatch.
    static String renderVariantEquality(String leftIntExpr, String rightIntExpr) {
        return "(CAST((" + leftIntExpr + ") AS Variant(Int64, String)) = CAST((" + rightIntExpr
                + ") AS Variant(Int64, String)))";
    }

    // Shape 4: NULL arm -- variantElement of a NULL Variant is NULL, so the fragment is constant-TRUE.
    static String renderNullVariantIsNull() {
        return "(variantElement(CAST(NULL AS Variant(Int64, String)), 'Int64') IS NULL)";
    }

    /**
     * Picks one of the four shapes at random. Either list may be empty -- constant fallbacks keep every shape
     * emittable on column-less scopes.
     *
     * @param intExprs
     *            rendered integer-total expressions (caller wraps columns in {@code toInt64})
     * @param strExprs
     *            rendered String-total expressions (caller wraps columns in {@code toString})
     *
     * @return a self-contained, parenthesized Boolean-valued predicate fragment
     */
    static String renderRandomFragment(List<String> intExprs, List<String> strExprs) {
        String intExpr = intExprs.isEmpty() ? "42" : Randomly.fromList(intExprs);
        switch ((int) Randomly.getNotCachedInteger(0, 4)) {
        case 0:
            return renderVariantElementEquals(intExpr, String.valueOf(Randomly.getNotCachedInteger(-128, 128)));
        case 1:
            boolean strArm = !strExprs.isEmpty() && Randomly.getBoolean();
            return renderVariantTypeEquals(strArm ? Randomly.fromList(strExprs) : intExpr,
                    strArm ? "String" : "Int64");
        case 2:
            return renderVariantEquality(intExpr, intExprs.isEmpty() ? "42" : Randomly.fromList(intExprs));
        default:
            return renderNullVariantIsNull();
        }
    }
}
