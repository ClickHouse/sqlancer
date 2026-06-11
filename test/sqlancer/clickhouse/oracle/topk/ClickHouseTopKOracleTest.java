package sqlancer.clickhouse.oracle.topk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import sqlancer.clickhouse.ClickHouseSchema.ClickHouseLancerDataType;
import sqlancer.clickhouse.oracle.topk.ClickHouseTopKOracle.Cell;
import sqlancer.clickhouse.oracle.topk.ClickHouseTopKOracle.NullsOrder;
import sqlancer.clickhouse.oracle.topk.ClickHouseTopKOracle.SortKey;

class ClickHouseTopKOracleTest {

    @Test
    void rendersSingleIntKeyAscWithLimit() {
        String sql = ClickHouseTopKOracle.renderQuery("t0", null,
                List.of(new SortKey("c0", true, NullsOrder.DEFAULT)), 10, -1, "");
        assertEquals("SELECT `c0` FROM t0 ORDER BY `c0` ASC LIMIT 10", sql);
    }

    @Test
    void rendersMultiKeyMixedDirectionsWithNullsAndOffset() {
        String sql = ClickHouseTopKOracle.renderQuery("t1", null,
                List.of(new SortKey("c0", true, NullsOrder.FIRST), new SortKey("c1", false, NullsOrder.LAST),
                        new SortKey("c2", false, NullsOrder.DEFAULT)),
                5, 2, "");
        assertEquals("SELECT `c0`, `c1`, `c2` FROM t1 ORDER BY `c0` ASC NULLS FIRST, `c1` DESC NULLS LAST,"
                + " `c2` DESC LIMIT 5 OFFSET 2", sql);
    }

    @Test
    void rendersOffArmSettingsSuffixVerbatim() {
        String sql = ClickHouseTopKOracle.renderQuery("t0", null,
                List.of(new SortKey("c0", false, NullsOrder.DEFAULT)), 0, -1, ClickHouseTopKOracle.OFF_SETTINGS);
        assertEquals("SELECT `c0` FROM t0 ORDER BY `c0` DESC LIMIT 0 SETTINGS use_top_k_dynamic_filtering = 0,"
                + " use_skip_indexes_for_top_k = 0, query_plan_top_k_through_join = 0", sql);
    }

    @Test
    void rendersVarLengthOptInSuffix() {
        String sql = ClickHouseTopKOracle.renderQuery("t0", null,
                List.of(new SortKey("c3", true, NullsOrder.DEFAULT)), 1, -1,
                ClickHouseTopKOracle.VAR_LENGTH_OPT_IN_SETTINGS);
        assertEquals("SELECT `c3` FROM t0 ORDER BY `c3` ASC LIMIT 1"
                + " SETTINGS use_top_k_dynamic_filtering_for_variable_length_types = 1", sql);
    }

    @Test
    void joinClauseQualifiesProjectionAndOrderBy() {

        String join = ClickHouseTopKOracle.renderLeftJoin("t0", "c1", "t1", "c2");
        assertEquals("LEFT JOIN t1 ON t0.`c1` = t1.`c2`", join);
        String sql = ClickHouseTopKOracle.renderQuery("t0", join,
                List.of(new SortKey("c0", true, NullsOrder.DEFAULT), new SortKey("c1", false, NullsOrder.DEFAULT)), 7,
                -1, "");
        assertEquals("SELECT t0.`c0`, t0.`c1` FROM t0 LEFT JOIN t1 ON t0.`c1` = t1.`c2`"
                + " ORDER BY t0.`c0` ASC, t0.`c1` DESC LIMIT 7", sql);
    }

    @Test
    void quoteEscapesEmbeddedBackticks() {
        assertEquals("`we``ird`", ClickHouseTopKOracle.quote("we`ird"));
    }

    @Test
    void projectionIsExactlyTheOrderByColumnsInOrder() {

        String sql = ClickHouseTopKOracle.renderQuery("t0", null,
                List.of(new SortKey("b", false, NullsOrder.DEFAULT), new SortKey("a", true, NullsOrder.DEFAULT)), 3,
                -1, "");
        assertEquals("SELECT `b`, `a` FROM t0 ORDER BY `b` DESC, `a` ASC LIMIT 3", sql);
    }

    @Test
    void floatKeysAreExcludedIncludingWrappedForms() {
        for (String t : new String[] { "Float32", "Float64", "Nullable(Float64)", "LowCardinality(Float32)",
                "Nullable(LowCardinality(Float64))" }) {
            assertFalse(ClickHouseTopKOracle.isEligibleSortKey(new ClickHouseLancerDataType(t)),
                    () -> t + " must not be an eligible sort key");
        }
    }

    @Test
    void scalarFixedRenderTypesAreEligible() {
        for (String t : new String[] { "Int8", "Int64", "Int256", "UInt8", "UInt64", "UInt256", "Date", "Date32",
                "DateTime", "DateTime64(3)", "String", "FixedString(16)", "Nullable(Int32)",
                "LowCardinality(String)", "LowCardinality(Nullable(String))", "Nullable(DateTime)" }) {
            assertTrue(ClickHouseTopKOracle.isEligibleSortKey(new ClickHouseLancerDataType(t)),
                    () -> t + " should be an eligible sort key");
        }
    }

    @Test
    void exoticAndCompositeTypesAreExcluded() {
        for (String t : new String[] { "Array(Int32)", "Tuple(Int32, String)", "Map(String, Int32)", "UUID", "Bool",
                "Decimal(9, 2)", "IPv4", "IPv6", "Enum8('a' = 1)", "Time", "Time64(3)",
                "SimpleAggregateFunction(sum, Int64)", "Variant(Int64, String)", "Dynamic", "JSON" }) {
            assertFalse(ClickHouseTopKOracle.isEligibleSortKey(new ClickHouseLancerDataType(t)),
                    () -> t + " must not be an eligible sort key");
        }
    }

    @Test
    void varLengthClassificationCoversStringShapes() {
        for (String t : new String[] { "String", "FixedString(8)", "LowCardinality(String)", "Nullable(String)",
                "LowCardinality(Nullable(String))" }) {
            assertTrue(ClickHouseTopKOracle.isVarLengthKey(new ClickHouseLancerDataType(t)),
                    () -> t + " should be var-length for the opt-in arm");
        }
        for (String t : new String[] { "Int32", "UInt64", "Date", "DateTime64(3)", "Nullable(Int64)" }) {
            assertFalse(ClickHouseTopKOracle.isVarLengthKey(new ClickHouseLancerDataType(t)),
                    () -> t + " must not be classified var-length");
        }
    }

    @Test
    void nullableDetectionUnwrapsLowCardinality() {
        assertTrue(ClickHouseTopKOracle.isNullableKey(new ClickHouseLancerDataType("Nullable(Int32)")));
        assertTrue(
                ClickHouseTopKOracle.isNullableKey(new ClickHouseLancerDataType("LowCardinality(Nullable(String))")));
        assertFalse(ClickHouseTopKOracle.isNullableKey(new ClickHouseLancerDataType("Int32")));
        assertFalse(ClickHouseTopKOracle.isNullableKey(new ClickHouseLancerDataType("LowCardinality(String)")));
    }

    @Test
    void equalListsReportNoDivergence() {
        List<List<Cell>> a = List.of(List.of(Cell.of("1"), Cell.NULL), List.of(Cell.of("2"), Cell.of("x")));
        List<List<Cell>> b = List.of(List.of(Cell.of("1"), Cell.NULL), List.of(Cell.of("2"), Cell.of("x")));
        assertEquals(-1, ClickHouseTopKOracle.firstDivergence(a, b));
    }

    @Test
    void firstDivergentRowIndexIsReported() {
        List<List<Cell>> a = List.of(List.of(Cell.of("1")), List.of(Cell.of("2")), List.of(Cell.of("3")));
        List<List<Cell>> b = List.of(List.of(Cell.of("1")), List.of(Cell.of("9")), List.of(Cell.of("3")));
        assertEquals(1, ClickHouseTopKOracle.firstDivergence(a, b));
    }

    @Test
    void prefixListDivergesAtTheBoundary() {

        List<List<Cell>> a = List.of(List.of(Cell.of("1")), List.of(Cell.of("2")));
        List<List<Cell>> b = List.of(List.of(Cell.of("1")), List.of(Cell.of("2")), List.of(Cell.of("3")));
        assertEquals(2, ClickHouseTopKOracle.firstDivergence(a, b));
        assertEquals(2, ClickHouseTopKOracle.firstDivergence(b, a));
        assertEquals("<absent>", ClickHouseTopKOracle.renderRowAt(a, 2));
        assertEquals("3", ClickHouseTopKOracle.renderRowAt(b, 2));
    }

    @Test
    void orderMattersComparisonIsPositional() {

        List<List<Cell>> a = List.of(List.of(Cell.of("1")), List.of(Cell.of("2")));
        List<List<Cell>> b = List.of(List.of(Cell.of("2")), List.of(Cell.of("1")));
        assertEquals(0, ClickHouseTopKOracle.firstDivergence(a, b));
    }

    @Test
    void sqlNullIsDistinctFromLiteralNullString() {

        assertNotEquals(Cell.NULL, Cell.of("NULL"));
        List<List<Cell>> a = List.of(List.of(Cell.NULL));
        List<List<Cell>> b = List.of(List.of(Cell.of("NULL")));
        assertEquals(0, ClickHouseTopKOracle.firstDivergence(a, b));

        assertEquals("NULL", ClickHouseTopKOracle.renderRowAt(a, 0));
        assertEquals("NULL", ClickHouseTopKOracle.renderRowAt(b, 0));
    }

    @Test
    void emptyListsAreEqual() {
        assertEquals(-1, ClickHouseTopKOracle.firstDivergence(List.of(), List.of()));
    }
}
