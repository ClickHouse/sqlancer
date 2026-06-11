package sqlancer.clickhouse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;

import org.junit.jupiter.api.Test;

import sqlancer.Randomly;

class ClickHouseSessionSettingsTest {

    @Test
    void pickSemrCandidateReturnsKnownName() {
        ClickHouseSessionSettings.SemrCandidate candidate = ClickHouseSessionSettings
                .pickSemrCandidate(new Randomly(42));
        assertTrue(ClickHouseSessionSettings.SEMR_SETTINGS.contains(candidate.name()),
                () -> candidate.name() + " missing from SEMR_SETTINGS");
        assertEquals("0", candidate.valueOff());
        assertEquals("1", candidate.valueOn());
    }

    @Test
    void pickRandomProfileRespectsBudget() {
        for (int i = 0; i < 50; i++) {
            LinkedHashMap<String, String> profile = ClickHouseSessionSettings.pickRandomProfile(new Randomly(7L + i),
                    3);
            assertTrue(profile.size() <= 3, () -> "profile larger than budget: " + profile.size());
            for (String name : profile.keySet()) {
                assertTrue(catalogNames().contains(name), () -> name + " is not a catalog entry");
                assertFalse(ClickHouseSessionSettings.MANAGED_BY_OPTIONS.contains(name), () -> name + " is denylisted");
            }
        }
    }

    @Test
    void pickRandomProfileBudgetZeroIsUnbounded() {
        int max = ClickHouseSessionSettings.RANDOM_SESSION_SETTINGS.size();
        boolean sawFullSize = false;
        for (int i = 0; i < 200; i++) {
            LinkedHashMap<String, String> profile = ClickHouseSessionSettings.pickRandomProfile(new Randomly(11L + i),
                    0);
            assertTrue(profile.size() <= max);
            if (profile.size() == max) {
                sawFullSize = true;
            }
        }
        assertTrue(sawFullSize, "budget=0 should occasionally return the full catalog");
    }

    @Test
    void managedByOptionsAreFilteredFromRandomProfile() {
        for (int i = 0; i < 100; i++) {
            LinkedHashMap<String, String> profile = ClickHouseSessionSettings.pickRandomProfile(new Randomly(13L + i),
                    100);
            for (String name : profile.keySet()) {
                assertFalse(ClickHouseSessionSettings.MANAGED_BY_OPTIONS.contains(name),
                        () -> name + " leaked through the MANAGED_BY_OPTIONS filter");
            }
        }
    }

    @Test
    void pickRandomProfileIsReproducibleUnderFixedSeed() {
        LinkedHashMap<String, String> first = ClickHouseSessionSettings.pickRandomProfile(new Randomly(99), 5);
        LinkedHashMap<String, String> second = ClickHouseSessionSettings.pickRandomProfile(new Randomly(99), 5);
        assertEquals(first, second);
        assertEquals(first.keySet().toString(), second.keySet().toString(),
                "iteration order must be byte-identical for reproducibility");
    }

    @Test
    void semrAndRandomOverlapOnlyOnCompileSettings() {
        // The catalogs overlap deliberately on exactly the compile_* JIT settings: they are both
        // SEMR-eligible (semantically-equivalent metamorphic relation toggles) and independently
        // randomizable. Any overlap beyond these three would be unintentional and must fail.
        Set<String> semr = new HashSet<>(ClickHouseSessionSettings.SEMR_SETTINGS);
        Set<String> random = new HashSet<>();
        for (ClickHouseSessionSettings.RandomEntry entry : ClickHouseSessionSettings.RANDOM_SESSION_SETTINGS) {
            random.add(entry.name());
        }
        Set<String> intersection = new HashSet<>(semr);
        intersection.retainAll(random);
        assertEquals(Set.of("compile_expressions", "compile_aggregate_expressions", "compile_sort_description"),
                intersection);
    }

    @Test
    void hardcodedTlpSettingsAreNotRandomized() {
        // ClickHouseTLPHavingOracle.java:42 and ClickHouseTLPAggregateOracle.java:42 hardcode these
        // in per-query SETTINGS clauses; randomizing them would invalidate TLP invariants.
        Set<String> random = new HashSet<>();
        for (ClickHouseSessionSettings.RandomEntry entry : ClickHouseSessionSettings.RANDOM_SESSION_SETTINGS) {
            random.add(entry.name());
        }
        assertFalse(random.contains("enable_optimize_predicate_expression"));
        assertFalse(random.contains("aggregate_functions_null_for_empty"));
    }

    @Test
    void hardcodedTlpSettingsAreNotSemrEligible() {
        // SEMR composes with TLPHaving/TLPAggregate; varying these names would clash with their
        // hardcoded per-query SETTINGS suffixes and break failure attribution.
        Set<String> semr = new HashSet<>(ClickHouseSessionSettings.SEMR_SETTINGS);
        assertFalse(semr.contains("enable_optimize_predicate_expression"));
        assertFalse(semr.contains("aggregate_functions_null_for_empty"));
    }

    @Test
    void semrCatalogHasNoDuplicates() {
        Set<String> seen = new HashSet<>();
        for (String name : ClickHouseSessionSettings.SEMR_SETTINGS) {
            assertTrue(seen.add(name), () -> name + " appears more than once in SEMR_SETTINGS");
        }
    }

    @Test
    void managedByOptionsAreAbsentFromBothCatalogs() {
        for (String managed : ClickHouseSessionSettings.MANAGED_BY_OPTIONS) {
            assertFalse(ClickHouseSessionSettings.SEMR_SETTINGS.contains(managed),
                    () -> managed + " is managed by a CLI option and must not be SEMR-toggled");
            assertFalse(catalogNames().contains(managed),
                    () -> managed + " is managed by a CLI option and must not be randomized");
        }
    }

    @Test
    void knownBadNamesStayOutOfTheCatalogs() {
        Set<String> semr = new HashSet<>(ClickHouseSessionSettings.SEMR_SETTINGS);
        // 2026-06-11 harness fix: the catalog DECLARE is enable_lazy_columns_replication; the bare
        // name was a silent UNKNOWN_SETTING no-op, so the #94339 coverage never ran.
        assertTrue(semr.contains("enable_lazy_columns_replication"));
        assertFalse(semr.contains("lazy_columns_replication"));
        // MAKE_OBSOLETE as of 26.5 -- toggling is a no-op, pruned 2026-06-11.
        assertFalse(semr.contains("query_plan_use_logical_join_step"));
        // Documented not-result-preserving exclusions (see the block comment in the catalog).
        assertFalse(semr.contains("do_not_merge_across_partitions_select_final"));
        assertFalse(semr.contains("apply_mutations_on_fly"));
        // Float-ULP noise: reorders arithmetic inside aggregates (TLPGroupBy authoring rule).
        assertFalse(semr.contains("optimize_arithmetic_operations_in_aggregate_functions"));
        // Documented result-CHANGING when 0 (approximate FINAL results by contract); only
        // use_skip_indexes_if_final itself is SEMR-safe, and only with exact_mode at its default.
        assertFalse(semr.contains("use_skip_indexes_if_final_exact_mode"));
        // Documented result-CHANGING for ALL (default-strictness) joins: converting JOIN to IN
        // collapses row multiplicity on duplicate keys. Caught by the 2026-06-11 focused smoke.
        assertFalse(semr.contains("query_plan_convert_join_to_in"));
        // 'any'/'break' overflow modes change results; 'throw' is pure untolerated-error noise.
        assertFalse(catalogNames().contains("max_rows_to_group_by"));
        assertFalse(catalogNames().contains("group_by_overflow_mode"));
    }

    @Test
    void negativeBudgetIsRejected() {
        // Defensive: --random-session-settings-budget is a plain int with no min validation on the
        // option class. A picker entry that tries to call Randomly.getNotCachedInteger(0, negative)
        // crashes the per-database thread; rejecting up front turns it into a clean error message.
        assertThrows(IllegalArgumentException.class,
                () -> ClickHouseSessionSettings.pickRandomProfile(new Randomly(1), -1));
    }

    private static Set<String> catalogNames() {
        Set<String> names = new HashSet<>();
        for (ClickHouseSessionSettings.RandomEntry entry : ClickHouseSessionSettings.RANDOM_SESSION_SETTINGS) {
            names.add(entry.name());
        }
        return names;
    }

}
