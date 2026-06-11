package sqlancer.clickhouse.oracle;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import sqlancer.clickhouse.ClickHouseTypeParser;
import sqlancer.clickhouse.oracle.coddtest.ClickHouseCODDTestFilters;

class ClickHouseCODDTestFilterTest {

    @Test
    void acceptsBareIntAndString() {
        assertTrue(ClickHouseCODDTestFilters.isFoldable(ClickHouseTypeParser.parse("Int32")));
        assertTrue(ClickHouseCODDTestFilters.isFoldable(ClickHouseTypeParser.parse("String")));
        assertTrue(ClickHouseCODDTestFilters.isFoldable(ClickHouseTypeParser.parse("Int8")));
        assertTrue(ClickHouseCODDTestFilters.isFoldable(ClickHouseTypeParser.parse("UInt256")));
        assertTrue(ClickHouseCODDTestFilters.isFoldable(ClickHouseTypeParser.parse("Bool")));
    }

    @Test
    void acceptsWrappedIntAndString() {
        assertTrue(ClickHouseCODDTestFilters.isFoldable(ClickHouseTypeParser.parse("Nullable(Int32)")));
        assertTrue(ClickHouseCODDTestFilters.isFoldable(ClickHouseTypeParser.parse("LowCardinality(String)")));
        assertTrue(
                ClickHouseCODDTestFilters.isFoldable(ClickHouseTypeParser.parse("LowCardinality(Nullable(String))")));
    }

    @Test
    void rejectsFloatsAndKnownNonFoldable() {
        assertFalse(ClickHouseCODDTestFilters.isFoldable(ClickHouseTypeParser.parse("Float32")));
        assertFalse(ClickHouseCODDTestFilters.isFoldable(ClickHouseTypeParser.parse("Float64")));
        assertFalse(ClickHouseCODDTestFilters.isFoldable(ClickHouseTypeParser.parse("Nullable(Float64)")));
        assertFalse(ClickHouseCODDTestFilters.isFoldable(ClickHouseTypeParser.parse("UUID")));
        assertFalse(ClickHouseCODDTestFilters.isFoldable(ClickHouseTypeParser.parse("Date")));
    }

    @Test
    void rejectsUnknown() {
        assertFalse(ClickHouseCODDTestFilters.isFoldable(ClickHouseTypeParser.parse("Decimal(9, 2)")));
        assertFalse(ClickHouseCODDTestFilters.isFoldable(ClickHouseTypeParser.parse("Array(Int32)")));
        assertFalse(ClickHouseCODDTestFilters.isFoldable(ClickHouseTypeParser.parse("")));
    }
}
