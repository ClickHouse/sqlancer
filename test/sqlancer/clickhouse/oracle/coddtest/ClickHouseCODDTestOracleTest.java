package sqlancer.clickhouse.oracle.coddtest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for ClickHouseCODDTestOracle's literal rendering. The folded query substitutes an
 * auxiliary-query scalar value as a literal; for wide integers a bare decimal literal exceeding
 * (U)Int64 range is reparsed by ClickHouse as Float64 and loses precision, producing spurious
 * CODDTest mismatches (surfaced by the UInt256 column emission). Those must be cast to their exact
 * type.
 */
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
        // CH types these as the smallest fitting (U)Int*; a bare literal round-trips exactly.
        assertEquals("5", ClickHouseCODDTestOracle.renderLiteral("5", "Int32"));
        assertEquals("-128", ClickHouseCODDTestOracle.renderLiteral("-128", "Int8"));
        assertEquals("18446744073709551615", ClickHouseCODDTestOracle.renderLiteral("18446744073709551615", "UInt64"));
        assertEquals("9223372036854775807", ClickHouseCODDTestOracle.renderLiteral("9223372036854775807", "Int64"));
    }

    @Test
    void wideIntegerCastSurvivesNullableUnwrap() {
        // The aux query's toTypeName may report Nullable(UInt256); renderLiteral unwraps it.
        assertEquals("CAST('42' AS UInt256)", ClickHouseCODDTestOracle.renderLiteral("42", "Nullable(UInt256)"));
    }
}
