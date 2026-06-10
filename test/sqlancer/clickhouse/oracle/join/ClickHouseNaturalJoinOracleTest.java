package sqlancer.clickhouse.oracle.join;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import sqlancer.clickhouse.oracle.join.ClickHouseNaturalJoinOracle.JoinSpec;
import sqlancer.clickhouse.oracle.join.ClickHouseNaturalJoinOracle.JoinVariant;

/**
 * DB-free tests for the NATURAL JOIN rewrite-equivalence oracle's schema/rendering helpers: the computed USING list,
 * the exact SQL of all three comparison forms (plus the zero-shared CROSS parity arm), and the expected column count
 * for the {@code SELECT *} implicit-column-set invariant.
 */
class ClickHouseNaturalJoinOracleTest {

    private static final String TA = "db.natj_1_a";
    private static final String TB = "db.natj_1_b";

    @Test
    void sharedAndPrivateNamesArePositional() {
        JoinSpec spec = new JoinSpec(JoinVariant.INNER, List.of("Int32", "Nullable(Int32)"), List.of("String"),
                List.of("Int32", "String"));
        assertEquals(List.of("sh0", "sh1"), spec.sharedNames());
        assertEquals(List.of("pa0"), spec.aPrivateNames());
        assertEquals(List.of("pb0", "pb1"), spec.bPrivateNames());
        assertEquals(List.of("sh0", "sh1", "pa0"), spec.tableColumnNames(true));
        assertEquals(List.of("sh0", "sh1", "pb0", "pb1"), spec.tableColumnNames(false));
        // The USING list inside the rendered form is exactly the shared names, in order.
        assertTrue(ClickHouseNaturalJoinOracle.renderUsingForm(spec, TA, TB).endsWith("USING (sh0, sh1)"));
    }

    @Test
    void rendersOneSharedInnerAllThreeForms() {
        JoinSpec spec = new JoinSpec(JoinVariant.INNER, List.of("Int32"), List.of("Int32"), List.of("String"));
        assertEquals("SELECT toString(tuple(sh0, pa0, pb0)) FROM db.natj_1_a NATURAL JOIN db.natj_1_b",
                ClickHouseNaturalJoinOracle.renderNaturalForm(spec, TA, TB));
        assertEquals("SELECT toString(tuple(sh0, pa0, pb0)) FROM db.natj_1_a JOIN db.natj_1_b USING (sh0)",
                ClickHouseNaturalJoinOracle.renderUsingForm(spec, TA, TB));
        assertTrue(ClickHouseNaturalJoinOracle.onFormApplicable(JoinVariant.INNER));
        assertEquals(
                "SELECT toString(tuple(a.sh0, a.pa0, b.pb0)) FROM db.natj_1_a AS a JOIN db.natj_1_b AS b "
                        + "ON a.sh0 = b.sh0",
                ClickHouseNaturalJoinOracle.renderOnForm(spec, TA, TB));
    }

    @Test
    void rendersTwoSharedFullWithoutOnForm() {
        JoinSpec spec = new JoinSpec(JoinVariant.FULL, List.of("Int32", "Nullable(Int32)"), List.of("String"),
                List.of());
        assertEquals("SELECT toString(tuple(sh0, sh1, pa0)) FROM db.natj_1_a NATURAL FULL JOIN db.natj_1_b",
                ClickHouseNaturalJoinOracle.renderNaturalForm(spec, TA, TB));
        assertEquals(
                "SELECT toString(tuple(sh0, sh1, pa0)) FROM db.natj_1_a FULL JOIN db.natj_1_b USING (sh0, sh1)",
                ClickHouseNaturalJoinOracle.renderUsingForm(spec, TA, TB));
        // FULL is restricted to forms (1) vs (2): no side-qualified projection reproduces USING's
        // matched-side-exposure semantics for FULL when shared columns can be legitimately NULL.
        assertFalse(ClickHouseNaturalJoinOracle.onFormApplicable(JoinVariant.FULL));
    }

    @Test
    void rendersZeroSharedCrossParity() {
        // Zero common columns: NATURAL JOIN silently degenerates to CROSS JOIN -- the parity arm compares the
        // plain NATURAL spelling against an explicit CROSS JOIN over the private columns only.
        JoinSpec spec = new JoinSpec(JoinVariant.INNER, List.of(), List.of("Int32"), List.of("String"));
        assertEquals("SELECT toString(tuple(pa0, pb0)) FROM db.natj_1_a NATURAL JOIN db.natj_1_b",
                ClickHouseNaturalJoinOracle.renderNaturalForm(spec, TA, TB));
        assertEquals("SELECT toString(tuple(pa0, pb0)) FROM db.natj_1_a CROSS JOIN db.natj_1_b",
                ClickHouseNaturalJoinOracle.renderCrossForm(spec, TA, TB));
    }

    @Test
    void rightVariantOnFormExposesBSideSharedColumns() {
        JoinSpec spec = new JoinSpec(JoinVariant.RIGHT, List.of("String"), List.of("Int32"), List.of("Int32"));
        assertEquals(
                "SELECT toString(tuple(b.sh0, a.pa0, b.pb0)) FROM db.natj_1_a AS a RIGHT JOIN db.natj_1_b AS b "
                        + "ON a.sh0 = b.sh0",
                ClickHouseNaturalJoinOracle.renderOnForm(spec, TA, TB));
        assertEquals("SELECT toString(tuple(sh0, pa0, pb0)) FROM db.natj_1_a NATURAL RIGHT JOIN db.natj_1_b",
                ClickHouseNaturalJoinOracle.renderNaturalForm(spec, TA, TB));
    }

    @Test
    void leftVariantOnFormExposesASideSharedColumns() {
        JoinSpec spec = new JoinSpec(JoinVariant.LEFT, List.of("Int32", "Int32"), List.of(), List.of("String"));
        assertEquals(
                "SELECT toString(tuple(a.sh0, a.sh1, b.pb0)) FROM db.natj_1_a AS a LEFT JOIN db.natj_1_b AS b "
                        + "ON a.sh0 = b.sh0 AND a.sh1 = b.sh1",
                ClickHouseNaturalJoinOracle.renderOnForm(spec, TA, TB));
    }

    @Test
    void rendersCreateTablePerSide() {
        JoinSpec spec = new JoinSpec(JoinVariant.INNER, List.of("Int32", "Nullable(Int32)"), List.of("String"),
                List.of("Int32"));
        assertEquals(
                "CREATE TABLE db.natj_1_a (sh0 Int32, sh1 Nullable(Int32), pa0 String) "
                        + "ENGINE = MergeTree ORDER BY tuple()",
                ClickHouseNaturalJoinOracle.renderCreateTable(TA, spec, true));
        assertEquals(
                "CREATE TABLE db.natj_1_b (sh0 Int32, sh1 Nullable(Int32), pb0 Int32) "
                        + "ENGINE = MergeTree ORDER BY tuple()",
                ClickHouseNaturalJoinOracle.renderCreateTable(TB, spec, false));
    }

    @Test
    void expectedStarColumnCountExposesSharedOnce() {
        assertEquals(3, ClickHouseNaturalJoinOracle.expectedStarColumnCount(
                new JoinSpec(JoinVariant.INNER, List.of("Int32"), List.of("Int32"), List.of("String"))));
        assertEquals(3, ClickHouseNaturalJoinOracle.expectedStarColumnCount(
                new JoinSpec(JoinVariant.FULL, List.of("Int32", "Nullable(Int32)"), List.of("String"), List.of())));
        // Zero shared: NATURAL == CROSS, every column of both sides survives.
        assertEquals(2, ClickHouseNaturalJoinOracle.expectedStarColumnCount(
                new JoinSpec(JoinVariant.INNER, List.of(), List.of("Int32"), List.of("String"))));
        // All shared: only the shared set, once.
        assertEquals(3, ClickHouseNaturalJoinOracle.expectedStarColumnCount(
                new JoinSpec(JoinVariant.LEFT, List.of("Int32", "String", "Int32"), List.of(), List.of())));
    }

    @Test
    void generatedSpecsHoldStructuralInvariants() {
        boolean sawZeroShared = false;
        boolean sawAllShared = false;
        for (int i = 0; i < 500; i++) {
            JoinSpec spec = ClickHouseNaturalJoinOracle.generateSpec();
            // Every column type comes from the supported scalar set.
            for (String t : spec.sharedTypes) {
                assertTrue(ClickHouseNaturalJoinOracle.COLUMN_TYPES.contains(t), t);
            }
            for (String t : spec.aPrivateTypes) {
                assertTrue(ClickHouseNaturalJoinOracle.COLUMN_TYPES.contains(t), t);
            }
            for (String t : spec.bPrivateTypes) {
                assertTrue(ClickHouseNaturalJoinOracle.COLUMN_TYPES.contains(t), t);
            }
            // Both tables must have at least one column; CREATE TABLE () is impossible.
            assertTrue(spec.tableColumnNames(true).size() >= 1);
            assertTrue(spec.tableColumnNames(false).size() >= 1);
            // Bounded shapes: k in {0,1,2} with 0-2 privates, or all-shared with k in {1..3} and no privates.
            assertTrue(spec.sharedCount() <= 3);
            assertTrue(spec.aPrivateTypes.size() <= 2);
            assertTrue(spec.bPrivateTypes.size() <= 2);
            if (spec.sharedCount() == 0) {
                sawZeroShared = true;
                // The zero-shared (CROSS-degeneration) arm uses only the plain NATURAL JOIN spelling and needs
                // private columns on both sides.
                assertEquals(JoinVariant.INNER, spec.variant);
                assertTrue(spec.aPrivateTypes.size() >= 1);
                assertTrue(spec.bPrivateTypes.size() >= 1);
            }
            if (spec.sharedCount() == 3) {
                sawAllShared = true;
                assertEquals(0, spec.aPrivateTypes.size());
                assertEquals(0, spec.bPrivateTypes.size());
            }
        }
        assertTrue(sawZeroShared, "zero-shared arm never generated across 500 draws");
        assertTrue(sawAllShared, "all-shared (k=3) arm never generated across 500 draws");
    }
}
