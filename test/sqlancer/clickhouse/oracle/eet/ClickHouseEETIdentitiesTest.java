package sqlancer.clickhouse.oracle.eet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import sqlancer.Randomly;

class ClickHouseEETIdentitiesTest {

    @Test
    void integerTypeAcceptsArithmeticIdentities() {
        // Loop 200 times so probabilistic selection eventually exercises both plus_zero and
        // multiply_one alongside the type-agnostic identities. The assertion is on membership in
        // the eligible set, not on order.
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            Optional<ClickHouseEETIdentities.Identity> picked = ClickHouseEETIdentities
                    .pickIdentityForType(new Randomly(31L + i), "Int32");
            assertTrue(picked.isPresent(), "Int32 should always have an eligible identity");
            seen.add(picked.get().name());
        }
        assertTrue(seen.contains("plus_zero"), () -> "plus_zero not picked across 200 attempts; saw " + seen);
        assertTrue(seen.contains("multiply_one"), () -> "multiply_one not picked across 200 attempts; saw " + seen);
        assertTrue(seen.contains("coalesce_self"), () -> "coalesce_self not picked across 200 attempts; saw " + seen);
        assertTrue(seen.contains("if_true"), () -> "if_true not picked across 200 attempts; saw " + seen);
        assertFalse(seen.contains("concat_empty"), () -> "concat_empty must not apply to Int32; saw " + seen);
    }

    @Test
    void stringTypeAcceptsConcatAndTypeAgnostic() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            Optional<ClickHouseEETIdentities.Identity> picked = ClickHouseEETIdentities
                    .pickIdentityForType(new Randomly(127L + i), "String");
            assertTrue(picked.isPresent(), "String should have eligible identities");
            seen.add(picked.get().name());
        }
        assertTrue(seen.contains("concat_empty"), () -> "concat_empty not picked for String; saw " + seen);
        assertFalse(seen.contains("plus_zero"), () -> "plus_zero must not apply to String; saw " + seen);
        assertFalse(seen.contains("multiply_one"), () -> "multiply_one must not apply to String; saw " + seen);
    }

    @Test
    void floatTypeExcludesArithmeticIdentities() {
        // Locks down the v1 scope boundary: Float must never be eligible for plus_zero or
        // multiply_one because of NaN / -0.0 false-positive risk. If someone widens the predicate
        // later without updating the cast-back machinery or the comparison normalization, this
        // negative assertion will fail.
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            Optional<ClickHouseEETIdentities.Identity> picked = ClickHouseEETIdentities
                    .pickIdentityForType(new Randomly(7L + i), "Float64");
            assertTrue(picked.isPresent(), "Float64 should still have type-agnostic identities");
            seen.add(picked.get().name());
        }
        assertFalse(seen.contains("plus_zero"), () -> "plus_zero must not apply to Float64; saw " + seen);
        assertFalse(seen.contains("multiply_one"), () -> "multiply_one must not apply to Float64; saw " + seen);
        assertTrue(seen.contains("coalesce_self") || seen.contains("if_true"),
                () -> "type-agnostic identities expected for Float64; saw " + seen);
    }

    @Test
    void nullableWrapperIsTransparent() {
        Optional<ClickHouseEETIdentities.Identity> picked = ClickHouseEETIdentities
                .pickIdentityForType(new Randomly(99L), "Nullable(Int32)");
        assertTrue(picked.isPresent(), "Nullable wrapper must not block eligibility");
    }

    @Test
    void lowCardinalityWrapperIsTransparent() {
        Optional<ClickHouseEETIdentities.Identity> picked = ClickHouseEETIdentities
                .pickIdentityForType(new Randomly(1001L), "LowCardinality(String)");
        assertTrue(picked.isPresent(), "LowCardinality(String) must accept at least concat_empty");
    }

    @Test
    void arrayTypeYieldsNoIdentity() {
        Optional<ClickHouseEETIdentities.Identity> picked = ClickHouseEETIdentities
                .pickIdentityForType(new Randomly(5L), "Array(Int32)");
        assertTrue(picked.isEmpty(), () -> "Array(Int32) must not yield an identity; got "
                + picked.map(ClickHouseEETIdentities.Identity::name).orElse("<none>"));
    }

    @Test
    void identityAppliesFormatterToBothSlots() {
        // coalesce_self has two %s slots; ensure applyTo fills both with the same xSql.
        ClickHouseEETIdentities.Identity coalesce = ClickHouseEETIdentities.CATALOG.stream()
                .filter(id -> id.name().equals("coalesce_self")).findFirst().orElseThrow();
        String applied = coalesce.applyTo("t.col");
        assertEquals("coalesce(t.col, t.col)", applied);
    }

    @Test
    void plusZeroTemplateUsesFirstSlotOnly() {
        ClickHouseEETIdentities.Identity plus = ClickHouseEETIdentities.CATALOG.stream()
                .filter(id -> id.name().equals("plus_zero")).findFirst().orElseThrow();
        // applyTo passes xSql for both slots; plus_zero's template ignores the second slot, so the
        // rendered output is still the single-arg form.
        String applied = plus.applyTo("t.col");
        assertEquals("plus(t.col, 0)", applied);
    }

    @Test
    void catalogIsNonEmpty() {
        assertNotNull(ClickHouseEETIdentities.CATALOG);
        assertFalse(ClickHouseEETIdentities.CATALOG.isEmpty(), "catalog must have at least one identity");
    }

}
