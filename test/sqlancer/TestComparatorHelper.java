package sqlancer;

import static org.junit.jupiter.api.Assertions.assertThrowsExactly;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import sqlancer.h2.H2Options;
import sqlancer.h2.H2Schema;

public class TestComparatorHelper {
    // TODO: Implement tests for the other ComparatorHelper methods

    // TODO: create test state that not depends on specific database
    final SQLGlobalState<H2Options, H2Schema> state = new SQLGlobalState<H2Options, H2Schema>() {

        @Override
        protected H2Schema readSchema() throws SQLException {
            return H2Schema.fromConnection(getConnection(), getDatabaseName());
        }

        @Override
        public MainOptions getOptions() {
            return new MainOptions();
        }
    };

    @Test
    public void testAssumeResultSetsAreEqualWithEqualSets() {
        List<String> r1 = Arrays.asList("a", "b", "c");
        List<String> r2 = Arrays.asList("a", "b", "c");
        ComparatorHelper.assumeResultSetsAreEqual(r1, r2, "", Arrays.asList(""), state);

    }

    @Test
    public void testAssumeResultSetsAreEqualWithUnequalLengthSets() {
        List<String> r1 = Arrays.asList("a", "b", "c");
        List<String> r2 = Arrays.asList("a", "b", "c", "d", "g");
        // NullPointerException is raised instead of AssertionError because state is null and the state.getState()...
        // line occurs before AssertionError is thrown, but it's good enough as an indicator that one of the Exceptions
        // is raised
        assertThrowsExactly(NullPointerException.class, () -> {
            ComparatorHelper.assumeResultSetsAreEqual(r1, r2, "", Arrays.asList(""), state);
        });
    }

    @Test
    public void testAssumeResultSetsAreEqualWithUnequalValueSets() {
        List<String> r1 = Arrays.asList("a", "b", "c");
        List<String> r2 = Arrays.asList("a", "b", "d");
        // NullPointerException is raised instead of AssertionError because state is null and the state.getState()...
        // line occurs before AssertionError is thrown, but it's good enough as an indicator that one of the Exceptions
        // is raised
        assertThrowsExactly(NullPointerException.class, () -> {
            ComparatorHelper.assumeResultSetsAreEqual(r1, r2, "", Arrays.asList(""), state);
        });
    }

    @Test
    public void testAssumeResultSetsAreEqualWithCanonicalizationRule() {
        List<String> r1 = Arrays.asList("a", "b", "c");
        List<String> r2 = Arrays.asList("a", "b", "d");
        ComparatorHelper.assumeResultSetsAreEqual(r1, r2, "", Arrays.asList(""), state, (String s) -> {
            return s.equals("d") ? "c" : s;
        });
    }

    // ===== Workstream 1 (2026-05-27 coverage expansion plan) tests =====

    @Test
    public void testIsEqualDoubleHandlesUlpDifferences() {
        // Two slightly different representations of the same logical double.
        org.junit.jupiter.api.Assertions
                .assertTrue(ComparatorHelper.isEqualDouble("0.123456789012345678", "0.12345678901234568"));
        org.junit.jupiter.api.Assertions.assertTrue(ComparatorHelper.isEqualDouble("100.0", "100.0"));
        org.junit.jupiter.api.Assertions.assertTrue(ComparatorHelper.isEqualDouble("100.0001", "100.0002"));
    }

    @Test
    public void testIsEqualDoubleScale() {
        org.junit.jupiter.api.Assertions.assertTrue(ComparatorHelper.isEqualDouble("1e10", "10000000000.0"));
    }

    @Test
    public void testIsEqualDoubleRejectsDifferentMagnitudes() {
        org.junit.jupiter.api.Assertions.assertFalse(ComparatorHelper.isEqualDouble("100.0", "200.0"));
    }

    @Test
    public void testIsEqualDoubleRejectsNonNumericStrings() {
        org.junit.jupiter.api.Assertions.assertFalse(ComparatorHelper.isEqualDouble("abc", "100.0"));
        org.junit.jupiter.api.Assertions.assertFalse(ComparatorHelper.isEqualDouble("100.0", "abc"));
    }

    @Test
    public void testCanonicalizeResultValueCollapsesNegativeZero() {
        org.junit.jupiter.api.Assertions.assertEquals("0.0", ComparatorHelper.canonicalizeResultValue("-0.0"));
        org.junit.jupiter.api.Assertions.assertEquals("0", ComparatorHelper.canonicalizeResultValue("-0"));
    }

    @Test
    public void testCanonicalizeResultValuePreservesOtherValues() {
        org.junit.jupiter.api.Assertions.assertEquals("42", ComparatorHelper.canonicalizeResultValue("42"));
        org.junit.jupiter.api.Assertions.assertEquals("NaN", ComparatorHelper.canonicalizeResultValue("NaN"));
        org.junit.jupiter.api.Assertions.assertEquals("Infinity", ComparatorHelper.canonicalizeResultValue("Infinity"));
        org.junit.jupiter.api.Assertions.assertNull(ComparatorHelper.canonicalizeResultValue(null));
    }

    @Test
    public void testComparisonModeEnumComplete() {
        ComparatorHelper.ComparisonMode[] modes = ComparatorHelper.ComparisonMode.values();
        java.util.Set<ComparatorHelper.ComparisonMode> set = new java.util.HashSet<>(java.util.Arrays.asList(modes));
        org.junit.jupiter.api.Assertions.assertTrue(set.contains(ComparatorHelper.ComparisonMode.SET));
        org.junit.jupiter.api.Assertions.assertTrue(set.contains(ComparatorHelper.ComparisonMode.MULTISET));
        org.junit.jupiter.api.Assertions
                .assertTrue(set.contains(ComparatorHelper.ComparisonMode.ULP_TOLERANT_MULTISET));
    }

    @Test
    public void testAssumeResultSetsAreEqualMultisetCatchesDuplicates() {
        // ["x","x","y"] vs ["x","y","y"] have equal sets but unequal multisets.
        List<String> r1 = Arrays.asList("x", "x", "y");
        List<String> r2 = Arrays.asList("x", "y", "y");
        // MULTISET mode must fail; route through NullPointerException since state is partial.
        assertThrowsExactly(NullPointerException.class, () -> {
            ComparatorHelper.assumeResultSetsAreEqual(r1, r2, "", Arrays.asList(""), state,
                    ComparatorHelper.ComparisonMode.MULTISET);
        });
    }

    @Test
    public void testAssumeResultSetsAreEqualUlpTolerantAcceptsFloatVariance() {
        // Two equivalent float renderings -- ULP_TOLERANT mode treats them as equal.
        List<String> r1 = Arrays.asList("0.123456789012345678", "1.0");
        List<String> r2 = Arrays.asList("0.12345678901234568", "1.0");
        // No throw expected -- the canonicaliser folds the float pair before set comparison.
        ComparatorHelper.assumeResultSetsAreEqual(r1, r2, "", Arrays.asList(""), state,
                ComparatorHelper.ComparisonMode.ULP_TOLERANT_MULTISET);
    }

    @Test
    public void testAssumeResultSetsAreEqualPreservesNaN() {
        // NaN strings round-trip through the canonicaliser as-is (Double.parseDouble accepts
        // "NaN" but Double.toString(NaN) is "NaN" -- identity preserved).
        List<String> r1 = Arrays.asList("NaN", "NaN");
        List<String> r2 = Arrays.asList("NaN", "NaN");
        ComparatorHelper.assumeResultSetsAreEqual(r1, r2, "", Arrays.asList(""), state,
                ComparatorHelper.ComparisonMode.ULP_TOLERANT_MULTISET);
    }

}
