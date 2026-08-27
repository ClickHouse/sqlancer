package sqlancer.clickhouse.oracle.tlp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.clickhouse.data.ClickHouseDataType;

import sqlancer.clickhouse.ClickHouseSchema;
import sqlancer.clickhouse.ast.ClickHouseAggregate;

class ClickHouseCombinatorIdentitiesTest {

    @Test
    void catalogContainsSeedIdentities() {
        List<String> names = ClickHouseCombinatorIdentities.CATALOG.stream()
                .map(ClickHouseCombinatorIdentities.Identity::name).collect(Collectors.toList());
        assertTrue(names.contains("sumIf"), "expected sumIf in catalog");
        assertTrue(names.contains("countIf"), "expected countIf in catalog");
        assertTrue(names.contains("avgOrNull"), "expected avgOrNull in catalog");
        assertTrue(names.contains("sumOrNull"), "expected sumOrNull in catalog");
        assertTrue(names.contains("minIf"), "expected minIf in catalog");
        assertTrue(names.contains("maxIf"), "expected maxIf in catalog");
    }

    @Test
    void orNullFamilyForcesNullForEmptyOff() {

        for (ClickHouseCombinatorIdentities.Identity id : ClickHouseCombinatorIdentities.CATALOG) {
            if (id.name().endsWith("OrNull") || id.name().endsWith("OrDefault")) {
                assertEquals(ClickHouseCombinatorIdentities.SETTINGS_NULL_FOR_EMPTY_OFF, id.settings(),
                        id.name() + " must run with aggregate_functions_null_for_empty=0");
            }
        }
    }

    @Test
    void ifFamilySumLikeKeepsNullForEmptyOn() {

        for (ClickHouseCombinatorIdentities.Identity id : ClickHouseCombinatorIdentities.CATALOG) {
            if (id.name().endsWith("If") && !id.name().equals("countIf")) {
                assertEquals(ClickHouseCombinatorIdentities.SETTINGS_NULL_FOR_EMPTY_ON, id.settings(),
                        id.name() + " must run with aggregate_functions_null_for_empty=1");
            }
        }
    }

    @Test
    void countIfRunsWithNullForEmptyOff() {
        ClickHouseCombinatorIdentities.Identity countIf = ClickHouseCombinatorIdentities.CATALOG.stream()
                .filter(i -> i.name().equals("countIf")).findFirst().orElseThrow();
        assertEquals(ClickHouseCombinatorIdentities.SETTINGS_NULL_FOR_EMPTY_OFF, countIf.settings(),
                "countIf must run with aggregate_functions_null_for_empty=0 to match count's empty-input semantics");
    }

    @Test
    void sumIfTemplatesAreSyntacticallyEquivalentSelectors() {
        ClickHouseCombinatorIdentities.Identity sumIf = ClickHouseCombinatorIdentities.CATALOG.stream()
                .filter(i -> i.name().equals("sumIf")).findFirst().orElseThrow();
        ClickHouseCombinatorIdentities.IdentityArgs args = new ClickHouseCombinatorIdentities.IdentityArgs("t.x",
                "t.c > 0");
        assertEquals("sumIf(t.x, t.c > 0)", sumIf.combinatorForm().apply(args));
        assertEquals("sum(if(t.c > 0, t.x, NULL))", sumIf.rewriteForm().apply(args));
    }

    @Test
    void ifFamilyRewritesUseNullElseBranch() {
        ClickHouseCombinatorIdentities.IdentityArgs args = new ClickHouseCombinatorIdentities.IdentityArgs("t.x",
                "t.c > 0");
        for (ClickHouseCombinatorIdentities.Identity id : ClickHouseCombinatorIdentities.CATALOG) {
            if (!id.name().endsWith("If") || id.name().equals("countIf")) {
                continue;
            }
            String rewrite = id.rewriteForm().apply(args);
            assertTrue(rewrite.contains(", NULL))"),
                    id.name() + " rewrite must use NULL as the else branch, not a neutral value: with"
                            + " aggregate_functions_null_for_empty=1 the combinator returns NULL when the condition"
                            + " selects no row, while a 0 else branch makes the rewrite return 0. Got: " + rewrite);
        }
    }

    @Test
    void countIfIgnoresValueArg() {
        ClickHouseCombinatorIdentities.Identity countIf = ClickHouseCombinatorIdentities.CATALOG.stream()
                .filter(i -> i.name().equals("countIf")).findFirst().orElseThrow();
        ClickHouseCombinatorIdentities.IdentityArgs args = new ClickHouseCombinatorIdentities.IdentityArgs("t.x",
                "t.c > 0");
        assertEquals("countIf(t.c > 0)", countIf.combinatorForm().apply(args));
        assertEquals("sum(toUInt64(ifNull(t.c > 0, 0)))", countIf.rewriteForm().apply(args));
    }

    @Test
    void countIfRewriteCoercesNullConditionToZero() {
        ClickHouseCombinatorIdentities.Identity countIf = ClickHouseCombinatorIdentities.CATALOG.stream()
                .filter(i -> i.name().equals("countIf")).findFirst().orElseThrow();
        String rewrite = countIf.rewriteForm().apply(new ClickHouseCombinatorIdentities.IdentityArgs("t.x", "t.c > 0"));
        assertTrue(rewrite.contains("ifNull("),
                "countIf rewrite must coerce a NULL condition to 0: sum over an argument that is NULL for"
                        + " every row returns NULL, while countIf returns 0. Got: " + rewrite);
    }

    @Test
    void identityPickerReturnsEmptyForUnmatchedAggregate() {

        ClickHouseSchema.ClickHouseLancerDataType intType = new ClickHouseSchema.ClickHouseLancerDataType(
                ClickHouseDataType.Int32);
        java.util.Optional<ClickHouseCombinatorIdentities.Identity> picked = ClickHouseCombinatorIdentities
                .pickIdentity(new sqlancer.Randomly(0L), ClickHouseAggregate.ClickHouseAggregateFunction.COUNT,
                        intType);
        assertTrue(picked.isPresent(), "should pick countIf for COUNT");
        assertEquals("countIf", picked.get().name());
    }

    @Test
    void identityPickerSkipsStringForNumericFamily() {

        ClickHouseSchema.ClickHouseLancerDataType stringType = new ClickHouseSchema.ClickHouseLancerDataType(
                ClickHouseDataType.String);
        java.util.Optional<ClickHouseCombinatorIdentities.Identity> sumPick = ClickHouseCombinatorIdentities
                .pickIdentity(new sqlancer.Randomly(0L), ClickHouseAggregate.ClickHouseAggregateFunction.SUM,
                        stringType);
        assertTrue(sumPick.isEmpty(), "sumIf must not accept String column type");
        java.util.Optional<ClickHouseCombinatorIdentities.Identity> countPick = ClickHouseCombinatorIdentities
                .pickIdentity(new sqlancer.Randomly(0L), ClickHouseAggregate.ClickHouseAggregateFunction.COUNT,
                        stringType);
        assertNotNull(countPick.orElse(null), "countIf must accept String column type");
    }
}
