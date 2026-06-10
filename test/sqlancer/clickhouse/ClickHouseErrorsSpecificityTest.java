package sqlancer.clickhouse;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Lock down the multi-word-substring discipline for the new error catalogs added by the query-primitives plan.
 *
 * <p>
 * Per institutional learning from the SEMR plan, bare single-word substrings ({@code "Setting"}, {@code "function"})
 * silently absorb dozens of unrelated ClickHouse error messages and mask real findings. Every entry in the set-op /
 * combinator / array-join catalogs must be either multi-word or a recognisable snake-cased error code label.
 * </p>
 */
class ClickHouseErrorsSpecificityTest {

    @Test
    void setOpCatalogEntriesAreSpecific() {
        for (String pattern : ClickHouseErrors.getSetOpErrors()) {
            assertSpecific(pattern, "set-op");
        }
    }

    @Test
    void combinatorCatalogEntriesAreSpecific() {
        for (String pattern : ClickHouseErrors.getCombinatorErrors()) {
            assertSpecific(pattern, "combinator");
        }
    }

    @Test
    void arrayJoinCatalogEntriesAreSpecific() {
        for (String pattern : ClickHouseErrors.getArrayJoinErrors()) {
            assertSpecific(pattern, "array-join");
        }
    }

    @Test
    void noCatalogEntryUsesGenericTokensAlone() {
        // Negative-assertion: certain over-broad substrings must never appear standalone in the
        // new catalogs because they would silently absorb unrelated bugs.
        List<String> banned = Arrays.asList("Setting", "function", "error", "exception", "type");
        for (List<String> catalog : Arrays.asList(ClickHouseErrors.getSetOpErrors(),
                ClickHouseErrors.getCombinatorErrors(), ClickHouseErrors.getArrayJoinErrors())) {
            for (String pattern : catalog) {
                for (String b : banned) {
                    assertFalse(pattern.equals(b), "bare '" + b + "' would absorb unrelated errors");
                }
            }
        }
    }

    @Test
    void knownOpenMutationAnalyzerBugPinsAreSpecificAndNarrow() {
        // The mutation-analyzer pin list tolerates known-open, already-filed CH bugs on the mutation
        // path (#106649). It is the one documented exception to the "never tolerate Code 49" rule,
        // so each entry must be a specific multi-word phrase -- never the bare LOGICAL_ERROR token,
        // never a standalone "Column identifier" (which would tolerate far more than the filed
        // signature per the plan's Key Technical Decisions).
        List<String> pins = ClickHouseErrors.getKnownOpenMutationAnalyzerBugs();
        for (String pattern : pins) {
            assertSpecific(pattern, "known-open-mutation-analyzer-bugs");
            assertFalse(pattern.equals("LOGICAL_ERROR"), "pin must never be the bare LOGICAL_ERROR token");
            assertFalse(pattern.equals("Code: 49"), "pin must never be the bare Code: 49 token");
            assertFalse(pattern.equals("Column identifier"),
                    "bare 'Column identifier' would tolerate far more than the filed #106649 signature");
        }
    }

    @Test
    void setOpCatalogExcludesUnknownSettingFamily() {
        // The startup probe in ClickHouseTLPSetOpOracle catches UNKNOWN_SETTING separately and uses
        // it to disable the oracle for the run. Including it in the catalog would mask the probe's
        // signal and let setting-name drift go undetected.
        for (String pattern : ClickHouseErrors.getSetOpErrors()) {
            assertFalse(pattern.contains("UNKNOWN_SETTING"),
                    "settings-probe error must not leak into the set-op catalog: " + pattern);
            assertFalse(pattern.contains("union_default_mode"), pattern);
            assertFalse(pattern.contains("intersect_default_mode"), pattern);
            assertFalse(pattern.contains("except_default_mode"), pattern);
        }
    }

    private static void assertSpecific(String pattern, String catalogName) {
        boolean multiWord = pattern.contains(" ");
        // Accept snake-cased error-code labels (FOO_BAR_BAZ) as specific.
        boolean codeLike = pattern.matches("[A-Z][A-Z0-9_]+[A-Z0-9]");
        assertTrue(multiWord || codeLike,
                catalogName + " error pattern must be multi-word or a code-label, got: '" + pattern + "'");
    }
}
