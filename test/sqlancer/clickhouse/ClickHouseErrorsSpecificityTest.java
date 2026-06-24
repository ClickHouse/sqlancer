package sqlancer.clickhouse;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

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

        boolean codeLike = pattern.matches("[A-Z][A-Z0-9_]+[A-Z0-9]");
        assertTrue(multiWord || codeLike,
                catalogName + " error pattern must be multi-word or a code-label, got: '" + pattern + "'");
    }
}
