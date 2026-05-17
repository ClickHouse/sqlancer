package sqlancer.clickhouse.oracle.cert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import sqlancer.IgnoreMeException;
import sqlancer.clickhouse.ClickHouseTypeParser;

class ClickHouseCERTGeneratorTest {

    @Test
    void primitiveIntDispatchesToInt32() {
        assertEquals("toInt32(number - 25000)",
                ClickHouseCERTOracle.generatorExprFor(ClickHouseTypeParser.parse("Int8")));
        assertEquals("toInt32(number - 25000)",
                ClickHouseCERTOracle.generatorExprFor(ClickHouseTypeParser.parse("Int32")));
        assertEquals("toInt32(number - 25000)",
                ClickHouseCERTOracle.generatorExprFor(ClickHouseTypeParser.parse("UInt64")));
    }

    @Test
    void primitiveStringDispatchesToToString() {
        assertEquals("toString(number)", ClickHouseCERTOracle.generatorExprFor(ClickHouseTypeParser.parse("String")));
    }

    @Test
    void primitiveFloatDispatchesToToFloat64() {
        assertEquals("toFloat64(number)", ClickHouseCERTOracle.generatorExprFor(ClickHouseTypeParser.parse("Float64")));
        assertEquals("toFloat64(number)", ClickHouseCERTOracle.generatorExprFor(ClickHouseTypeParser.parse("Float32")));
    }

    @Test
    void lowCardinalityIsTransparent() {
        assertEquals("toString(number)",
                ClickHouseCERTOracle.generatorExprFor(ClickHouseTypeParser.parse("LowCardinality(String)")));
        assertEquals("toInt32(number - 25000)",
                ClickHouseCERTOracle.generatorExprFor(ClickHouseTypeParser.parse("LowCardinality(Int32)")));
    }

    @Test
    void nullableWrapsWithSmallProbabilityNull() {
        String expr = ClickHouseCERTOracle.generatorExprFor(ClickHouseTypeParser.parse("Nullable(Int32)"));
        assertTrue(expr.contains("NULL"));
        assertTrue(expr.contains("toInt32(number - 25000)"));
    }

    @Test
    void nullableLowCardinalityRecurses() {
        String expr = ClickHouseCERTOracle
                .generatorExprFor(ClickHouseTypeParser.parse("LowCardinality(Nullable(String))"));
        assertTrue(expr.contains("NULL"));
        assertTrue(expr.contains("toString(number)"));
    }

    @Test
    void unknownThrowsIgnoreMe() {
        assertThrows(IgnoreMeException.class,
                () -> ClickHouseCERTOracle.generatorExprFor(ClickHouseTypeParser.parse("Decimal(9, 2)")));
    }
}
