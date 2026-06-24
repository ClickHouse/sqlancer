package sqlancer.clickhouse.oracle.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import sqlancer.clickhouse.ClickHouseSchema;
import sqlancer.clickhouse.ClickHouseSchema.ClickHouseColumn;
import sqlancer.clickhouse.ClickHouseSchema.ClickHouseTable;

class ClickHouseStatsToggleOracleTest {

    private static ClickHouseColumn col(String name, String type) {
        return new ClickHouseColumn(name, new ClickHouseSchema.ClickHouseLancerDataType(type), false, false, null);
    }

    private static ClickHouseTable table(String name, String engine, boolean isView, ClickHouseColumn... columns) {
        return new ClickHouseTable(name, List.of(columns), List.of(), isView, engine);
    }

    @Test
    void differentialPairRendersExactSettingsSuffixes() {
        String select = "SELECT toString(tuple(`c0`)) FROM t0 WHERE (`c0` < 5)";
        String[] pair = ClickHouseStatsToggleOracle.renderDifferentialPair(select, false);
        assertEquals(2, pair.length);
        assertEquals(select + " SETTINGS use_statistics = 1, allow_statistics_optimize = 1", pair[0]);
        assertEquals(select + " SETTINGS use_statistics = 0, allow_statistics_optimize = 0", pair[1]);
    }

    @Test
    void joinShapedDifferentialPinsJoinOrderOnBothArms() {

        String[] pair = ClickHouseStatsToggleOracle.renderDifferentialPair("SELECT 1", true);
        assertEquals("SELECT 1 SETTINGS use_statistics = 1, allow_statistics_optimize = 1, "
                + "query_plan_optimize_join_order_limit = 0", pair[0]);
        assertEquals("SELECT 1 SETTINGS use_statistics = 0, allow_statistics_optimize = 0, "
                + "query_plan_optimize_join_order_limit = 0", pair[1]);
    }

    @Test
    void tupleProjectionWrapsRefsInToString() {

        assertEquals("toString(tuple(`c0`, a1.`c2`))",
                ClickHouseStatsToggleOracle.renderTupleProjection(List.of("`c0`", "a1.`c2`")));
    }

    @Test
    void stalenessSetupRendersEveryKind() {
        for (String kind : ClickHouseStatsToggleOracle.STATISTICS_KINDS) {
            List<String> seq = ClickHouseStatsToggleOracle.renderStalenessSetup("db.stats_7_t", 600, kind, kind);
            assertTrue(seq.contains("ALTER TABLE db.stats_7_t ADD STATISTICS IF NOT EXISTS k TYPE " + kind), kind);
            assertTrue(seq.contains("ALTER TABLE db.stats_7_t MODIFY STATISTICS k TYPE " + kind), kind);
            assertTrue(seq.contains("ALTER TABLE db.stats_7_t ADD STATISTICS IF NOT EXISTS v TYPE " + kind), kind);
            assertTrue(seq.contains("ALTER TABLE db.stats_7_t MODIFY STATISTICS v TYPE " + kind), kind);
        }
    }

    @Test
    void kindPoolMatchesTheStatisticsGenerators() {
        assertEquals(List.of("tdigest", "uniq", "countmin", "minmax"), ClickHouseStatsToggleOracle.STATISTICS_KINDS);
    }

    @Test
    void dropStatisticsCoversBothColumns() {
        assertEquals("ALTER TABLE db.stats_3_t DROP STATISTICS k, v",
                ClickHouseStatsToggleOracle.renderDropStatistics("db.stats_3_t"));
    }

    @Test
    void stalenessSequenceOrdersCreateInsertStatsMaterialize() {
        List<String> seq = ClickHouseStatsToggleOracle.renderStalenessSetup("db.stats_1_t", 750, "tdigest", "minmax");
        assertEquals(7, seq.size());
        assertEquals("CREATE TABLE db.stats_1_t (k Int32, v Int64) ENGINE = MergeTree ORDER BY k", seq.get(0));
        assertEquals("INSERT INTO db.stats_1_t SELECT toInt32(if(number % 4 = 3, number, number % 3)), "
                + "toInt64(number % 11) FROM numbers(750)", seq.get(1));
        assertEquals("ALTER TABLE db.stats_1_t ADD STATISTICS IF NOT EXISTS k TYPE tdigest", seq.get(2));
        assertEquals("ALTER TABLE db.stats_1_t MODIFY STATISTICS k TYPE tdigest", seq.get(3));
        assertEquals("ALTER TABLE db.stats_1_t ADD STATISTICS IF NOT EXISTS v TYPE minmax", seq.get(4));
        assertEquals("ALTER TABLE db.stats_1_t MODIFY STATISTICS v TYPE minmax", seq.get(5));
        assertEquals("ALTER TABLE db.stats_1_t MATERIALIZE STATISTICS k, v SETTINGS mutations_sync = 1", seq.get(6));
    }

    @Test
    void stalenessMutationsAreSyncAndDomainShifting() {

        assertEquals("ALTER TABLE db.stats_2_t DELETE WHERE k % 2 = 0 SETTINGS mutations_sync = 1",
                ClickHouseStatsToggleOracle.renderStaleDelete("db.stats_2_t"));
        assertEquals("INSERT INTO db.stats_2_t SELECT toInt32(1000000 + number), toInt64(number % 5) "
                + "FROM numbers(900)", ClickHouseStatsToggleOracle.renderStaleInsert("db.stats_2_t", 900));
    }

    @Test
    void stalenessSelectsAreOrderAndLimitFree() {

        for (String stmt : ClickHouseStatsToggleOracle.renderStalenessSetup("db.t", 500, "uniq", "countmin")) {
            assertFalse(stmt.contains("LIMIT"), stmt);
            assertFalse(stmt.contains("ORDER BY t"), stmt);
        }
    }

    @Test
    void plainMergeTreeWithIntegerColumnIsEligible() {
        assertTrue(ClickHouseStatsToggleOracle
                .isEligibleFleetTable(table("t0", "MergeTree", false, col("c0", "Int32"), col("c1", "String"))));
    }

    @Test
    void nullableAndLowCardinalityIntegersCountAsInteger() {

        assertTrue(ClickHouseStatsToggleOracle
                .isEligibleFleetTable(table("t0", "MergeTree", false, col("c0", "Nullable(Int64)"))));
        assertTrue(ClickHouseStatsToggleOracle
                .isEligibleFleetTable(table("t0", "MergeTree", false, col("c0", "LowCardinality(UInt16)"))));
    }

    @Test
    void dedupeEnginesViewsAndUndiscoveredEnginesAreNotEligible() {

        for (String engine : List.of("ReplacingMergeTree", "SummingMergeTree", "AggregatingMergeTree",
                "CollapsingMergeTree", "VersionedCollapsingMergeTree", "Memory", "")) {
            assertFalse(
                    ClickHouseStatsToggleOracle.isEligibleFleetTable(table("t0", engine, false, col("c0", "Int32"))),
                    engine);
        }
        assertFalse(ClickHouseStatsToggleOracle.isEligibleFleetTable(table("v0", "View", true, col("c0", "Int32"))));
    }

    @Test
    void integerFreeTablesAreNotEligible() {
        assertFalse(ClickHouseStatsToggleOracle.isEligibleFleetTable(
                table("t0", "MergeTree", false, col("c0", "String"), col("c1", "Float64"), col("c2", "Date"))));
    }

    @Test
    void exactIntegerFilterExcludesFloatsAndDates() {
        assertTrue(ClickHouseStatsToggleOracle
                .isExactInteger(new ClickHouseSchema.ClickHouseLancerDataType("UInt256").getType()));
        assertFalse(ClickHouseStatsToggleOracle
                .isExactInteger(new ClickHouseSchema.ClickHouseLancerDataType("Float32").getType()));
        assertFalse(ClickHouseStatsToggleOracle
                .isExactInteger(new ClickHouseSchema.ClickHouseLancerDataType("Date32").getType()));
        assertFalse(ClickHouseStatsToggleOracle
                .isExactInteger(new ClickHouseSchema.ClickHouseLancerDataType("String").getType()));
    }

    @Test
    void multisetDiffIsEmptyForEqualMultisetsRegardlessOfOrder() {
        assertTrue(ClickHouseStatsToggleOracle
                .multisetDiff(List.of("(1,2)", "(3,4)", "(3,4)"), List.of("(3,4)", "(1,2)", "(3,4)"), 20).isEmpty());
    }

    @Test
    void multisetDiffReportsBothDirectionsAndRespectsLimit() {
        List<String> on = List.of("(1)", "(2)", "(2)", "(5)");
        List<String> off = List.of("(1)", "(2)", "(3)");
        assertEquals(List.of("(2) (+1 on)", "(3) (+1 off)", "(5) (+1 on)"),
                ClickHouseStatsToggleOracle.multisetDiff(on, off, 20));
        assertEquals(2, ClickHouseStatsToggleOracle.multisetDiff(on, List.of(), 2).size());
    }

    @Test
    void assertMultisetsEqualThrowsWithBothSqlsAndSizes() {
        String onSql = "SELECT 1 SETTINGS use_statistics = 1, allow_statistics_optimize = 1";
        String offSql = "SELECT 1 SETTINGS use_statistics = 0, allow_statistics_optimize = 0";
        AssertionError e = assertThrows(AssertionError.class, () -> ClickHouseStatsToggleOracle
                .assertMultisetsEqual(List.of("(1)", "(2)"), List.of("(1)"), onSql, offSql));
        assertTrue(e.getMessage().contains("2 rows with statistics on vs 1 rows with statistics off"),
                e.getMessage());
        assertTrue(e.getMessage().contains(onSql), e.getMessage());
        assertTrue(e.getMessage().contains(offSql), e.getMessage());
        assertTrue(e.getMessage().contains("(2) (+1 on)"), e.getMessage());
    }

    @Test
    void assertMultisetsEqualPassesOnEqualMultisets() {
        ClickHouseStatsToggleOracle.assertMultisetsEqual(List.of("(7)"), List.of("(7)"), "a", "b");
    }

    @Test
    void kindRotationCoversEveryKindWithinFourIterations() {
        Set<String> seenK = new HashSet<>();
        Set<String> seenV = new HashSet<>();
        for (long id = 1; id <= 4; id++) {
            seenK.add(ClickHouseStatsToggleOracle.kindForIteration(id, 0));
            seenV.add(ClickHouseStatsToggleOracle.kindForIteration(id, 1));
        }
        assertEquals(Set.copyOf(ClickHouseStatsToggleOracle.STATISTICS_KINDS), seenK);
        assertEquals(Set.copyOf(ClickHouseStatsToggleOracle.STATISTICS_KINDS), seenV);
    }

    @Test
    void kindRotationOffsetsKAndVKindsApart() {

        for (long id = 1; id <= 8; id++) {
            assertFalse(ClickHouseStatsToggleOracle.kindForIteration(id, 0)
                    .equals(ClickHouseStatsToggleOracle.kindForIteration(id, 1)), "id " + id);
        }
    }

    @Test
    void materializeCounterExistsForEveryKind() {
        for (String kind : ClickHouseStatsToggleOracle.STATISTICS_KINDS) {
            assertTrue(ClickHouseStatsToggleOracle.materializedCount(kind) >= 0, kind);
        }
    }
}
