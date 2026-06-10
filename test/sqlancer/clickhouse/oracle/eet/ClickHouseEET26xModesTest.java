package sqlancer.clickhouse.oracle.eet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import sqlancer.clickhouse.gen.ClickHouseExpressionGenerator;
import sqlancer.clickhouse.gen.ClickHouseExpressionGenerator.CompoundIntervalKind;

/**
 * DB-free rendering / gating tests for the 26.x EET modes (plan 2026-06-10-002, Units 4 + 6): compound INTERVAL
 * literal decomposition, OVERLAY keyword-vs-function parser sugar, the ASCII-restricted overlay splice construction,
 * the Java natural-order reference comparator, and the --eet-26x-modes pickMode gating.
 */
class ClickHouseEET26xModesTest {

    // ----- Unit 4: compound INTERVAL literal rendering, one exact string per kind pair -----

    @Test
    void compoundLiteralRendersDayToSecondExample() {
        // The PR #100453 headline example.
        assertEquals("INTERVAL '5 12:30:45' DAY TO SECOND", ClickHouseExpressionGenerator
                .renderCompoundIntervalLiteral(CompoundIntervalKind.DAY_TO_SECOND, new int[] { 5, 12, 30, 45 }));
    }

    @Test
    void compoundLiteralRendersAllKindPairs() {
        assertEquals("INTERVAL '1-11' YEAR TO MONTH", ClickHouseExpressionGenerator
                .renderCompoundIntervalLiteral(CompoundIntervalKind.YEAR_TO_MONTH, new int[] { 1, 11 }));
        assertEquals("INTERVAL '5 12' DAY TO HOUR", ClickHouseExpressionGenerator
                .renderCompoundIntervalLiteral(CompoundIntervalKind.DAY_TO_HOUR, new int[] { 5, 12 }));
        assertEquals("INTERVAL '5 12:30' DAY TO MINUTE", ClickHouseExpressionGenerator
                .renderCompoundIntervalLiteral(CompoundIntervalKind.DAY_TO_MINUTE, new int[] { 5, 12, 30 }));
        assertEquals("INTERVAL '12:30' HOUR TO MINUTE", ClickHouseExpressionGenerator
                .renderCompoundIntervalLiteral(CompoundIntervalKind.HOUR_TO_MINUTE, new int[] { 12, 30 }));
        assertEquals("INTERVAL '12:30:45' HOUR TO SECOND", ClickHouseExpressionGenerator
                .renderCompoundIntervalLiteral(CompoundIntervalKind.HOUR_TO_SECOND, new int[] { 12, 30, 45 }));
        assertEquals("INTERVAL '30:45' MINUTE TO SECOND", ClickHouseExpressionGenerator
                .renderCompoundIntervalLiteral(CompoundIntervalKind.MINUTE_TO_SECOND, new int[] { 30, 45 }));
    }

    @Test
    void compoundLiteralZeroComponents() {
        // All-zero components must render with the zero-padded time fields intact.
        assertEquals("INTERVAL '0 00:00:00' DAY TO SECOND", ClickHouseExpressionGenerator
                .renderCompoundIntervalLiteral(CompoundIntervalKind.DAY_TO_SECOND, new int[] { 0, 0, 0, 0 }));
        assertEquals("INTERVAL '0-0' YEAR TO MONTH", ClickHouseExpressionGenerator
                .renderCompoundIntervalLiteral(CompoundIntervalKind.YEAR_TO_MONTH, new int[] { 0, 0 }));
    }

    @Test
    void compoundLiteralCarryishAndPaddedComponents() {
        // Carry-ish values near unit boundaries ('1-11' is the plan's example) and single-digit
        // time fields, which must zero-pad ('3 04:05:09').
        assertEquals("INTERVAL '1-11' YEAR TO MONTH", ClickHouseExpressionGenerator
                .renderCompoundIntervalLiteral(CompoundIntervalKind.YEAR_TO_MONTH, new int[] { 1, 11 }));
        assertEquals("INTERVAL '3 04:05:09' DAY TO SECOND", ClickHouseExpressionGenerator
                .renderCompoundIntervalLiteral(CompoundIntervalKind.DAY_TO_SECOND, new int[] { 3, 4, 5, 9 }));
        assertEquals("INTERVAL '23:59' HOUR TO MINUTE", ClickHouseExpressionGenerator
                .renderCompoundIntervalLiteral(CompoundIntervalKind.HOUR_TO_MINUTE, new int[] { 23, 59 }));
        // Leading HOUR/MINUTE fields below 10 are zero-padded too ('hh:mm' / 'mm:ss').
        assertEquals("INTERVAL '05:30' HOUR TO MINUTE", ClickHouseExpressionGenerator
                .renderCompoundIntervalLiteral(CompoundIntervalKind.HOUR_TO_MINUTE, new int[] { 5, 30 }));
    }

    @Test
    void compoundLiteralRejectsComponentCountMismatch() {
        assertThrows(AssertionError.class, () -> ClickHouseExpressionGenerator
                .renderCompoundIntervalLiteral(CompoundIntervalKind.DAY_TO_SECOND, new int[] { 5, 12 }));
        assertThrows(AssertionError.class, () -> ClickHouseExpressionGenerator
                .renderDecomposedIntervalArith("d", "+", CompoundIntervalKind.YEAR_TO_MONTH, new int[] { 1 }));
    }

    // ----- Unit 4: decomposition renders from the SAME components -----

    @Test
    void decompositionMatchesCompoundComponentsAddition() {
        int[] comps = { 5, 12, 30, 45 };
        assertEquals("(t.c0 + INTERVAL '5 12:30:45' DAY TO SECOND)", ClickHouseExpressionGenerator
                .renderCompoundIntervalArith("t.c0", "+", CompoundIntervalKind.DAY_TO_SECOND, comps));
        assertEquals("(t.c0 + INTERVAL 5 DAY + INTERVAL 12 HOUR + INTERVAL 30 MINUTE + INTERVAL 45 SECOND)",
                ClickHouseExpressionGenerator.renderDecomposedIntervalArith("t.c0", "+",
                        CompoundIntervalKind.DAY_TO_SECOND, comps));
    }

    @Test
    void decompositionYearToMonth() {
        int[] comps = { 1, 11 };
        assertEquals("(t.c0 + INTERVAL '1-11' YEAR TO MONTH)", ClickHouseExpressionGenerator
                .renderCompoundIntervalArith("t.c0", "+", CompoundIntervalKind.YEAR_TO_MONTH, comps));
        assertEquals("(t.c0 + INTERVAL 1 YEAR + INTERVAL 11 MONTH)", ClickHouseExpressionGenerator
                .renderDecomposedIntervalArith("t.c0", "+", CompoundIntervalKind.YEAR_TO_MONTH, comps));
    }

    @Test
    void subtractionArmCarriesMinusOnEveryComponent() {
        int[] comps = { 2, 3, 4 };
        assertEquals("(t.c0 - INTERVAL '2 03:04' DAY TO MINUTE)", ClickHouseExpressionGenerator
                .renderCompoundIntervalArith("t.c0", "-", CompoundIntervalKind.DAY_TO_MINUTE, comps));
        assertEquals("(t.c0 - INTERVAL 2 DAY - INTERVAL 3 HOUR - INTERVAL 4 MINUTE)", ClickHouseExpressionGenerator
                .renderDecomposedIntervalArith("t.c0", "-", CompoundIntervalKind.DAY_TO_MINUTE, comps));
    }

    @Test
    void randomComponentsRespectUnitRanges() {
        for (CompoundIntervalKind kind : CompoundIntervalKind.values()) {
            String[] units = kind.getUnits();
            for (int round = 0; round < 200; round++) {
                int[] comps = ClickHouseExpressionGenerator.randomCompoundIntervalComponents(kind);
                assertEquals(units.length, comps.length, kind.name());
                assertTrue(comps[0] >= 0 && comps[0] <= 30, () -> kind + " leading component out of range");
                for (int k = 1; k < comps.length; k++) {
                    int max = switch (units[k]) {
                    case "MONTH" -> 11;
                    case "HOUR" -> 23;
                    default -> 59;
                    };
                    int v = comps[k];
                    String unit = units[k];
                    assertTrue(v >= 0 && v <= max, () -> kind + " " + unit + "=" + v + " out of range");
                }
            }
        }
    }

    // ----- Unit 6: OVERLAY keyword form vs function form render textually parallel -----

    @Test
    void overlayFormsParallelFromOnly() {
        assertEquals("OVERLAY(t.s PLACING 'ab' FROM 3)", ClickHouseEETOracle.overlayKeywordForm("t.s", "'ab'", 3, null));
        assertEquals("overlay(t.s, 'ab', 3)", ClickHouseEETOracle.overlayFunctionForm("t.s", "'ab'", 3, null));
    }

    @Test
    void overlayFormsParallelFromFor() {
        assertEquals("OVERLAY(t.s PLACING 'ab' FROM 3 FOR 2)",
                ClickHouseEETOracle.overlayKeywordForm("t.s", "'ab'", 3, 2));
        assertEquals("overlay(t.s, 'ab', 3, 2)", ClickHouseEETOracle.overlayFunctionForm("t.s", "'ab'", 3, 2));
    }

    @Test
    void overlayFormsCarryNegativeAndZeroPositionsIdentically() {
        // Out-of-range / negative p and l must appear verbatim in BOTH forms -- the identity is
        // "forms agree", not "forms are in range".
        assertEquals("OVERLAY(t.s PLACING '' FROM -2 FOR -1)", ClickHouseEETOracle.overlayKeywordForm("t.s", "''", -2, -1));
        assertEquals("overlay(t.s, '', -2, -1)", ClickHouseEETOracle.overlayFunctionForm("t.s", "''", -2, -1));
        assertEquals("OVERLAY(t.s PLACING 'x' FROM 0)", ClickHouseEETOracle.overlayKeywordForm("t.s", "'x'", 0, null));
        assertEquals("overlay(t.s, 'x', 0)", ClickHouseEETOracle.overlayFunctionForm("t.s", "'x'", 0, null));
    }

    // ----- Unit 6: splice construction -- guards identical on both sides -----

    @Test
    void spliceFormsShareTheIdenticalGuard() {
        String sx = ClickHouseEETOracle.asciiCappedInput("t.s");
        assertEquals("substring(replaceRegexpAll(t.s, '[^ -~]', '?'), 1, 8)", sx);
        for (int p = 1; p <= 4; p++) {
            for (int l = 0; l <= 4; l++) {
                String guard = ClickHouseEETOracle.spliceGuard(sx, p, l);
                String overlayForm = ClickHouseEETOracle.guardedOverlayForm(sx, "'r'", p, l);
                String spliceForm = ClickHouseEETOracle.guardedSpliceForm(sx, "'r'", p, l);
                // Both sides open with the same if(<guard>, ...) and close on the same skip branch.
                assertTrue(overlayForm.startsWith("if(" + guard + ", "), overlayForm);
                assertTrue(spliceForm.startsWith("if(" + guard + ", "), spliceForm);
                assertTrue(overlayForm.endsWith(", 'skip')"), overlayForm);
                assertTrue(spliceForm.endsWith(", 'skip')"), spliceForm);
            }
        }
    }

    @Test
    void spliceFormRendersExpectedSql() {
        String sx = "sx";
        assertEquals("if((length(sx) >= 4), overlay(sx, 'r', 2, 3), 'skip')",
                ClickHouseEETOracle.guardedOverlayForm(sx, "'r'", 2, 3));
        assertEquals("if((length(sx) >= 4), concat(substring(sx, 1, 1), 'r', substring(sx, 5)), 'skip')",
                ClickHouseEETOracle.guardedSpliceForm(sx, "'r'", 2, 3));
        // p=1, l=0: pure insertion at the start; guard folds to length >= 0 (always true).
        assertEquals("if((length(sx) >= 0), overlay(sx, 'r', 1, 0), 'skip')",
                ClickHouseEETOracle.guardedOverlayForm(sx, "'r'", 1, 0));
        assertEquals("if((length(sx) >= 0), concat(substring(sx, 1, 0), 'r', substring(sx, 1)), 'skip')",
                ClickHouseEETOracle.guardedSpliceForm(sx, "'r'", 1, 0));
    }

    // ----- Unit 6: Java natural-order reference comparator -----

    @Test
    void naturalCompareNumericAwareVersionStrings() {
        // The classic case byte order gets wrong: 'v1.2' < 'v1.10' numerically.
        assertTrue(ClickHouseEETOracle.naturalOrderCompare("v1.2", "v1.10") < 0);
        assertTrue(ClickHouseEETOracle.naturalOrderCompare("v1.10", "v1.2") > 0);
        assertTrue(ClickHouseEETOracle.naturalOrderCompare("file2", "file10") < 0);
        assertTrue(ClickHouseEETOracle.naturalOrderCompare("v2.5", "v10.0") < 0);
    }

    @Test
    void naturalCompareNoDigitStringsByteOrder() {
        assertTrue(ClickHouseEETOracle.naturalOrderCompare("abc", "abd") < 0);
        assertTrue(ClickHouseEETOracle.naturalOrderCompare("b", "ab") > 0);
        assertTrue(ClickHouseEETOracle.naturalOrderCompare("ab", "abc") < 0); // prefix sorts first
    }

    @Test
    void naturalCompareEqualAndEmptyStrings() {
        assertEquals(0, ClickHouseEETOracle.naturalOrderCompare("v3.7", "v3.7"));
        assertEquals(0, ClickHouseEETOracle.naturalOrderCompare("", ""));
        assertTrue(ClickHouseEETOracle.naturalOrderCompare("", "v1") < 0);
        assertTrue(ClickHouseEETOracle.naturalOrderCompare("v1", "") > 0);
    }

    @Test
    void naturalCompareLongDigitRunsBeyondInt64() {
        // BigInteger-backed digit-run compare: 20-digit runs (beyond Int64) order numerically.
        assertTrue(ClickHouseEETOracle.naturalOrderCompare("a99999999999999999998", "a99999999999999999999") < 0);
    }

    @Test
    void buildNaturalSortPairStaysInTheAsciiNoLeadingZeroRegime() {
        for (int i = 0; i < 500; i++) {
            String[] pair = ClickHouseEETOracle.buildNaturalSortPair();
            assertEquals(2, pair.length);
            for (String s : pair) {
                for (int k = 0; k < s.length(); k++) {
                    char c = s.charAt(k);
                    assertTrue(c >= ' ' && c <= '~', () -> "non-ASCII char in " + s);
                    // No leading-zero digit runs: a '0' must not be followed by another digit
                    // unless preceded by a digit (Integer.toString never produces them, this
                    // guards the construction).
                    if (c == '0' && k + 1 < s.length() && Character.isDigit(s.charAt(k + 1))) {
                        assertTrue(k > 0 && Character.isDigit(s.charAt(k - 1)),
                                () -> "leading-zero digit run in " + s);
                    }
                }
            }
        }
    }

    // ----- Flag gating: --eet-26x-modes controls pickMode's candidate list -----

    @Test
    void newModesExcludedWhenFlagOff() {
        List<ClickHouseEETOracle.Mode> modes = ClickHouseEETOracle.candidateModes(false);
        assertEquals(List.of(ClickHouseEETOracle.Mode.WHERE_INJECT, ClickHouseEETOracle.Mode.HAVING_INJECT,
                ClickHouseEETOracle.Mode.EXPR_REWRITE, ClickHouseEETOracle.Mode.ALGEBRAIC_ID,
                ClickHouseEETOracle.Mode.MULTIIF_EQUIV), modes);
        assertFalse(modes.contains(ClickHouseEETOracle.Mode.COMPOUND_INTERVAL));
        assertFalse(modes.contains(ClickHouseEETOracle.Mode.OVERLAY_EQUIV));
        assertFalse(modes.contains(ClickHouseEETOracle.Mode.OVERLAY_SPLICE));
        assertFalse(modes.contains(ClickHouseEETOracle.Mode.NATURAL_SORT_KEY));
    }

    @Test
    void newModesIncludedWhenFlagOn() {
        List<ClickHouseEETOracle.Mode> modes = ClickHouseEETOracle.candidateModes(true);
        assertTrue(modes.containsAll(List.of(ClickHouseEETOracle.Mode.values())),
                "with the flag on, every mode must be pickable");
        assertEquals(ClickHouseEETOracle.Mode.values().length, modes.size(), "no duplicates expected");
    }
}
