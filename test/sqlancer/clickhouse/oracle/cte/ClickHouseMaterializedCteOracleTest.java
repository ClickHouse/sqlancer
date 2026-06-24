package sqlancer.clickhouse.oracle.cte;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import sqlancer.clickhouse.oracle.cte.ClickHouseMaterializedCteOracle.BodyShape;
import sqlancer.clickhouse.oracle.cte.ClickHouseMaterializedCteOracle.OuterShape;

class ClickHouseMaterializedCteOracleTest {

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = haystack.indexOf(needle);
        while (idx >= 0) {
            count++;
            idx = haystack.indexOf(needle, idx + needle.length());
        }
        return count;
    }

    @Test
    void groupCountBodyRendersExactly() {
        assertEquals("SELECT `c0` AS c, count() AS n FROM t0 WHERE `c0` % 3 = 1 GROUP BY `c0`",
                ClickHouseMaterializedCteOracle.renderBody(BodyShape.GROUP_COUNT, "t0", "c0", 3, 1));
    }

    @Test
    void distinctFilterBodyRendersExactly() {
        assertEquals("SELECT DISTINCT `c2` AS c FROM t1 WHERE `c2` > -42",
                ClickHouseMaterializedCteOracle.renderBody(BodyShape.DISTINCT_FILTER, "t1", "c2", 5, -42));
    }

    @Test
    void plainFilterBodyRendersExactly() {
        assertEquals("SELECT `c1` AS c FROM t0 WHERE `c1` % 7 = 4",
                ClickHouseMaterializedCteOracle.renderBody(BodyShape.PLAIN_FILTER, "t0", "c1", 7, 4));
    }

    @Test
    void bodiesAreLimitFree() {

        for (BodyShape body : BodyShape.values()) {
            String sql = ClickHouseMaterializedCteOracle.renderBody(body, "t0", "c0", 3, 1);
            assertFalse(sql.contains("LIMIT"), () -> "body must be LIMIT-free: " + sql);
            assertFalse(sql.contains("ORDER BY"), () -> "body must be ORDER-BY-free: " + sql);
        }
    }

    @Test
    void materializedFormCarriesKeywordAndGateSettings() {
        String a = ClickHouseMaterializedCteOracle.renderStatement(OuterShape.SINGLE_REF, BodyShape.PLAIN_FILTER, "t0",
                "c0", 2, 0, true);
        assertTrue(a.startsWith("WITH mcte_x AS MATERIALIZED (SELECT `c0` AS c FROM t0 WHERE `c0` % 2 = 0)"), a);
        assertTrue(a.endsWith(" SETTINGS enable_materialized_cte = 1"), a);
    }

    @Test
    void inlinedFormHasNeitherKeywordNorSettings() {
        String b = ClickHouseMaterializedCteOracle.renderStatement(OuterShape.SINGLE_REF, BodyShape.PLAIN_FILTER, "t0",
                "c0", 2, 0, false);
        assertEquals("WITH mcte_x AS (SELECT `c0` AS c FROM t0 WHERE `c0` % 2 = 0) "
                + "SELECT toString(tuple(c)) FROM mcte_x", b);
        assertFalse(b.contains("MATERIALIZED"), b);
        assertFalse(b.contains("SETTINGS"), b);
        assertFalse(b.contains("enable_materialized_cte"), b);
    }

    @Test
    void formsDifferOnlyInMaterializedKeywordAndSettingsClause() {

        for (OuterShape outer : OuterShape.values()) {
            for (BodyShape body : BodyShape.values()) {
                String a = ClickHouseMaterializedCteOracle.renderStatement(outer, body, "t9", "c3", 4, 2, true);
                String b = ClickHouseMaterializedCteOracle.renderStatement(outer, body, "t9", "c3", 4, 2, false);
                String stripped = a.replace(" AS MATERIALIZED (", " AS (")
                        .replace(" SETTINGS enable_materialized_cte = 1", "");
                assertEquals(b, stripped, () -> "forms diverge structurally for " + outer + "/" + body);
            }
        }
    }

    @Test
    void selfJoinOuterReferencesCteAliasTwice() {
        String outer = ClickHouseMaterializedCteOracle.renderOuter(OuterShape.SELF_JOIN, BodyShape.PLAIN_FILTER);
        assertEquals(2, countOccurrences(outer, "mcte_x"), outer);
        assertTrue(outer.contains("FROM mcte_x AS x1 JOIN mcte_x AS x2 ON x1.c = x2.c"), outer);
    }

    @Test
    void scalarSubqueryAndUnionAllReferenceCteTwice() {
        String scalar = ClickHouseMaterializedCteOracle.renderOuter(OuterShape.SCALAR_SUBQUERY,
                BodyShape.DISTINCT_FILTER);
        assertEquals(2, countOccurrences(scalar, "mcte_x"), scalar);
        assertTrue(scalar.contains("(SELECT max(c) FROM mcte_x)"), scalar);

        String union = ClickHouseMaterializedCteOracle.renderOuter(OuterShape.UNION_ALL, BodyShape.PLAIN_FILTER);
        assertEquals(2, countOccurrences(union, "mcte_x"), union);
        assertTrue(union.contains("UNION ALL"), union);

        assertTrue(union.startsWith("SELECT r FROM ("), union);
    }

    @Test
    void singleRefOuterReferencesCteOnce() {
        String outer = ClickHouseMaterializedCteOracle.renderOuter(OuterShape.SINGLE_REF, BodyShape.PLAIN_FILTER);
        assertEquals(1, countOccurrences(outer, "mcte_x"), outer);
    }

    @Test
    void groupCountBodyCarriesAggregateThroughOuterTuple() {
        String outer = ClickHouseMaterializedCteOracle.renderOuter(OuterShape.SINGLE_REF, BodyShape.GROUP_COUNT);
        assertEquals("SELECT toString(tuple(c, n)) FROM mcte_x", outer);
    }

    @Test
    void chainedShapeDeclaresBothCtesMaterializedInFormA() {
        String a = ClickHouseMaterializedCteOracle.renderStatement(OuterShape.CHAINED, BodyShape.GROUP_COUNT, "t0",
                "c0", 4, 2, true);
        assertTrue(a.contains("WITH mcte_a AS MATERIALIZED ("), a);
        assertTrue(a.contains(", mcte_b AS MATERIALIZED (SELECT c FROM mcte_a WHERE c % 2 = 0)"), a);
        assertTrue(a.contains("FROM mcte_b AS b1 JOIN mcte_a AS a1 ON b1.c = a1.c"), a);
        assertTrue(a.endsWith(" SETTINGS enable_materialized_cte = 1"), a);
    }

    @Test
    void chainedShapeDeclaresNeitherCteMaterializedInFormB() {
        String b = ClickHouseMaterializedCteOracle.renderStatement(OuterShape.CHAINED, BodyShape.GROUP_COUNT, "t0",
                "c0", 4, 2, false);
        assertFalse(b.contains("MATERIALIZED"), b);
        assertTrue(b.contains("WITH mcte_a AS ("), b);
        assertTrue(b.contains(", mcte_b AS (SELECT c FROM mcte_a WHERE c % 2 = 0)"), b);
        assertFalse(b.contains("enable_materialized_cte"), b);
    }

    @Test
    void boundedDiffReportsBothSidesAndRespectsLimit() {
        List<String> mat = List.of("(1)", "(2)", "(2)", "(5)");
        List<String> inl = List.of("(1)", "(2)", "(3)");
        List<String> diff = ClickHouseMaterializedCteOracle.boundedDiff(mat, inl, 20);
        assertEquals(List.of("materialized-only: (2)", "inlined-only: (3)", "materialized-only: (5)"), diff);

        List<String> bounded = ClickHouseMaterializedCteOracle.boundedDiff(mat, List.of(), 2);
        assertEquals(2, bounded.size());
    }

    @Test
    void probeQueryUsesPerQuerySettingsClause() {

        assertEquals("SELECT 1 SETTINGS enable_materialized_cte = 1", ClickHouseMaterializedCteOracle.PROBE_QUERY);
    }

}
