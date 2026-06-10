package sqlancer.clickhouse.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * DB-free rendering / gating tests for Unit 10 (plan 2026-06-10-002): WHERE-context-only Variant predicate fragments
 * (26.1 PR #90900 Variant-in-all-functions + PR #90677 use_variant_as_common_type default-on). The client-v2 RowBinary
 * reader cannot decode a projected Variant column (R4), so every fragment must be a self-contained Boolean expression
 * in which {@code Variant(...)} appears only inside a CAST consumed by variantElement / variantType / a comparison --
 * the Variant value must never escape the predicate.
 */
class ClickHouseVariantWhereEmissionTest {

    // ----- exact rendering per shape -----

    @Test
    void variantElementEqualsRendersExactly() {
        assertEquals("(variantElement(CAST((toInt64(t0.c0)) AS Variant(Int64, String)), 'Int64') = 5)",
                ClickHouseVariantPredicateFactory.renderVariantElementEquals("toInt64(t0.c0)", "5"));
    }

    @Test
    void variantTypeEqualsRendersExactly() {
        assertEquals("(variantType(CAST((toInt64(t0.c0)) AS Variant(Int64, String))) = 'Int64')",
                ClickHouseVariantPredicateFactory.renderVariantTypeEquals("toInt64(t0.c0)", "Int64"));
        assertEquals("(variantType(CAST((toString(t0.c1)) AS Variant(Int64, String))) = 'String')",
                ClickHouseVariantPredicateFactory.renderVariantTypeEquals("toString(t0.c1)", "String"));
    }

    @Test
    void variantEqualityRendersExactly() {
        assertEquals("(CAST((toInt64(t0.c0)) AS Variant(Int64, String)) = "
                + "CAST((toInt64(t0.c2)) AS Variant(Int64, String)))",
                ClickHouseVariantPredicateFactory.renderVariantEquality("toInt64(t0.c0)", "toInt64(t0.c2)"));
    }

    @Test
    void nullVariantIsNullRendersExactly() {
        assertEquals("(variantElement(CAST(NULL AS Variant(Int64, String)), 'Int64') IS NULL)",
                ClickHouseVariantPredicateFactory.renderNullVariantIsNull());
    }

    // ----- structural containment: Variant( only inside an allowed Boolean-shaped consumer -----

    @Test
    void everyShapeIsBooleanShapedWithVariantContained() {
        assertBooleanShapedVariantFragment(
                ClickHouseVariantPredicateFactory.renderVariantElementEquals("toInt64(t0.c0)", "-7"));
        assertBooleanShapedVariantFragment(
                ClickHouseVariantPredicateFactory.renderVariantTypeEquals("toString(t0.c1)", "String"));
        assertBooleanShapedVariantFragment(
                ClickHouseVariantPredicateFactory.renderVariantEquality("toInt64(t0.c0)", "42"));
        assertBooleanShapedVariantFragment(ClickHouseVariantPredicateFactory.renderNullVariantIsNull());
    }

    @Test
    void randomFragmentAlwaysBooleanShapedWithColumns() {
        List<String> intExprs = Arrays.asList("toInt64(t0.c0)", "toInt64(t0.c2)");
        List<String> strExprs = Arrays.asList("toString(t0.c0)", "toString(t0.c1)");
        for (int i = 0; i < 500; i++) {
            assertBooleanShapedVariantFragment(
                    ClickHouseVariantPredicateFactory.renderRandomFragment(intExprs, strExprs));
        }
    }

    @Test
    void randomFragmentAlwaysBooleanShapedOnColumnlessScope() {
        // Empty scopes must fall back to constants, never produce a malformed/empty inner expression.
        for (int i = 0; i < 500; i++) {
            assertBooleanShapedVariantFragment(ClickHouseVariantPredicateFactory
                    .renderRandomFragment(Collections.emptyList(), Collections.emptyList()));
        }
    }

    // ----- gating: flag off => the generatePredicate branch is unreachable -----

    @Test
    void gateClosedWheneverFlagIsOff() {
        assertFalse(ClickHouseVariantPredicateFactory.gateOpen(false, false));
        assertFalse(ClickHouseVariantPredicateFactory.gateOpen(false, true));
    }

    @Test
    void gateOpenOnlyOnFlagAndRoll() {
        assertFalse(ClickHouseVariantPredicateFactory.gateOpen(true, false));
        assertTrue(ClickHouseVariantPredicateFactory.gateOpen(true, true));
    }

    // ----- helpers -----

    /**
     * Asserts the fragment is a parenthesized Boolean expression whose top-level form is one of the four allowed
     * shapes, and that every {@code Variant(} occurrence sits inside a {@code CAST(... AS Variant(Int64, String))}
     * that is itself consumed by variantElement / variantType / an equality comparison.
     *
     * @param fragment
     *            the rendered predicate fragment under test
     */
    private static void assertBooleanShapedVariantFragment(String fragment) {
        assertTrue(fragment.startsWith("("), "must be parenthesized: " + fragment);
        assertTrue(fragment.endsWith(")"), "must be parenthesized: " + fragment);
        boolean allowedTopLevel = fragment.startsWith("(variantElement(CAST(")
                || fragment.startsWith("(variantType(CAST(")
                || fragment.startsWith("(CAST((") && fragment.contains(") = CAST((");
        assertTrue(allowedTopLevel, "top-level function must be variantElement/variantType/Variant equality: "
                + fragment);
        // Boolean-valued tail: a comparison against a constant or an IS NULL check.
        assertTrue(fragment.contains(" = ") || fragment.endsWith(" IS NULL)"),
                "must be Boolean-valued (comparison or IS NULL): " + fragment);
        // Variant( may only ever appear as the CAST target type "AS Variant(Int64, String)".
        assertEquals(countOccurrences(fragment, "Variant("),
                countOccurrences(fragment, "AS Variant(Int64, String))"),
                "every Variant( must be a CAST target inside the predicate: " + fragment);
        assertTrue(countOccurrences(fragment, "Variant(") > 0, "must exercise Variant at all: " + fragment);
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        for (int idx = haystack.indexOf(needle); idx != -1; idx = haystack.indexOf(needle, idx + 1)) {
            count++;
        }
        return count;
    }
}
