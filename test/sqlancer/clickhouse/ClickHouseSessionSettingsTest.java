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
    void semrAndRandomCatalogsAreDisjoint() {
        Set<String> semr = new HashSet<>(ClickHouseSessionSettings.SEMR_SETTINGS);
        Set<String> random = new HashSet<>();
        for (ClickHouseSessionSettings.RandomEntry entry : ClickHouseSessionSettings.RANDOM_SESSION_SETTINGS) {
            random.add(entry.name());
        }
        Set<String> intersection = new HashSet<>(semr);
        intersection.retainAll(random);
        assertTrue(intersection.isEmpty(), () -> "SEMR_SETTINGS and RANDOM_SESSION_SETTINGS overlap: " + intersection);
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
