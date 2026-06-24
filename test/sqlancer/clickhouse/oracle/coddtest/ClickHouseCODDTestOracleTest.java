package sqlancer.clickhouse.oracle.coddtest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ClickHouseCODDTestOracleTest {

    @Test
    void wideIntegersAreCastToExactType() {
        assertEquals(
                "CAST('115792089237316195423570985008687907853269984665640564039457584007912410239860' AS UInt256)",
                ClickHouseCODDTestOracle.renderLiteral(
                        "115792089237316195423570985008687907853269984665640564039457584007912410239860", "UInt256"));
        assertEquals("CAST('170141183460469231731687303715884105727' AS Int128)",
                ClickHouseCODDTestOracle.renderLiteral("170141183460469231731687303715884105727", "Int128"));
        assertEquals("CAST('340282366920938463463374607431768211455' AS UInt128)",
                ClickHouseCODDTestOracle.renderLiteral("340282366920938463463374607431768211455", "UInt128"));
        assertEquals("CAST('-57896044618658097711785492504343953926634992332820282019728792003956564819968' AS Int256)",
                ClickHouseCODDTestOracle.renderLiteral(
                        "-57896044618658097711785492504343953926634992332820282019728792003956564819968", "Int256"));
    }

    @Test
    void narrowIntegersStayBareLiterals() {

        assertEquals("5", ClickHouseCODDTestOracle.renderLiteral("5", "Int32"));
        assertEquals("-128", ClickHouseCODDTestOracle.renderLiteral("-128", "Int8"));
        assertEquals("18446744073709551615", ClickHouseCODDTestOracle.renderLiteral("18446744073709551615", "UInt64"));
        assertEquals("9223372036854775807", ClickHouseCODDTestOracle.renderLiteral("9223372036854775807", "Int64"));
    }

    @Test
    void wideIntegerCastSurvivesNullableUnwrap() {

        assertEquals("CAST('42' AS UInt256)", ClickHouseCODDTestOracle.renderLiteral("42", "Nullable(UInt256)"));
    }
}
