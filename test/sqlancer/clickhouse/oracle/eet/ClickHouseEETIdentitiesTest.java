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
    void stringTypeAcceptsNewRoundtripIdentities() {

        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 400; i++) {
            Optional<ClickHouseEETIdentities.Identity> picked = ClickHouseEETIdentities
                    .pickIdentityForType(new Randomly(913L + i), "String");
            assertTrue(picked.isPresent());
            seen.add(picked.get().name());
        }
        assertTrue(seen.contains("reverse_reverse"), () -> "reverse_reverse not picked for String; saw " + seen);
        assertTrue(seen.contains("substring_whole"), () -> "substring_whole not picked for String; saw " + seen);
        assertTrue(seen.contains("concat_substring_split"),
                () -> "concat_substring_split not picked for String; saw " + seen);
        assertTrue(seen.contains("replace_regexp_nomatch"),
                () -> "replace_regexp_nomatch not picked for String; saw " + seen);
    }

    @Test
    void newStringIdentitiesExcludedFromIntAndFloat() {
        Set<String> stringOnly = Set.of("reverse_reverse", "substring_whole", "concat_substring_split",
                "replace_regexp_nomatch", "concat_empty");
        for (String typeName : new String[] { "Int32", "UInt64", "Float64" }) {
            for (int i = 0; i < 200; i++) {
                Optional<ClickHouseEETIdentities.Identity> picked = ClickHouseEETIdentities
                        .pickIdentityForType(new Randomly(41L + i), typeName);
                assertTrue(picked.isPresent());
                assertFalse(stringOnly.contains(picked.get().name()),
                        () -> typeName + " must not pick a String-only identity; got " + picked.get().name());
            }
        }
    }

    @Test
    void newStringIdentitiesRenderExpectedSql() {
        assertEquals("reverse(reverse(t.c))", identity("reverse_reverse").applyTo("t.c"));
        assertEquals("substring(t.c, 1)", identity("substring_whole").applyTo("t.c"));
        assertEquals("concat(substring(t.c, 1, 1), substring(t.c, 2))",
                identity("concat_substring_split").applyTo("t.c"));
        assertEquals("replaceRegexpAll(t.c, 'zzqq_never_matches_9181', 'Q')",
                identity("replace_regexp_nomatch").applyTo("t.c"));
    }

    @Test
    void fixedStringExcludedFromStringIdentities() {

        Set<String> stringOnly = Set.of("reverse_reverse", "substring_whole", "concat_substring_split",
                "replace_regexp_nomatch", "concat_empty");
        for (int i = 0; i < 200; i++) {
            Optional<ClickHouseEETIdentities.Identity> picked = ClickHouseEETIdentities
                    .pickIdentityForType(new Randomly(77L + i), "FixedString(8)");
            if (picked.isPresent()) {
                assertFalse(stringOnly.contains(picked.get().name()),
                        () -> "FixedString must not pick a plain-String identity; got " + picked.get().name());
            }
        }
    }

    private static ClickHouseEETIdentities.Identity identity(String name) {
        return ClickHouseEETIdentities.CATALOG.stream().filter(id -> id.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void floatTypeExcludesArithmeticIdentities() {

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

        ClickHouseEETIdentities.Identity coalesce = ClickHouseEETIdentities.CATALOG.stream()
                .filter(id -> id.name().equals("coalesce_self")).findFirst().orElseThrow();
        String applied = coalesce.applyTo("t.col");
        assertEquals("coalesce(t.col, t.col)", applied);
    }

    @Test
    void plusZeroTemplateUsesFirstSlotOnly() {
        ClickHouseEETIdentities.Identity plus = ClickHouseEETIdentities.CATALOG.stream()
                .filter(id -> id.name().equals("plus_zero")).findFirst().orElseThrow();

        String applied = plus.applyTo("t.col");
        assertEquals("plus(t.col, 0)", applied);
    }

    @Test
    void catalogIsNonEmpty() {
        assertNotNull(ClickHouseEETIdentities.CATALOG);
        assertFalse(ClickHouseEETIdentities.CATALOG.isEmpty(), "catalog must have at least one identity");
    }

}
