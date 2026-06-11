package sqlancer.clickhouse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import sqlancer.clickhouse.ClickHouseSchema.ClickHouseLancerDataType;
import sqlancer.clickhouse.ClickHouseType.Array;
import sqlancer.clickhouse.ClickHouseType.Kind;
import sqlancer.clickhouse.ClickHouseType.LowCardinality;
import sqlancer.clickhouse.ClickHouseType.Nullable;
import sqlancer.clickhouse.ClickHouseType.Primitive;

class ClickHouseTypeGenerationTest {

    @Test
    void getRandomWithoutStateReturnsScalar() {

        for (int i = 0; i < 256; i++) {
            ClickHouseLancerDataType t = ClickHouseLancerDataType.getRandom();
            ClickHouseType term = t.getTypeTerm();
            assertFalse(term instanceof Nullable || term instanceof LowCardinality || term instanceof Array,
                    () -> "expected a scalar (non-wrapper) type but got " + term.getClass().getSimpleName());
        }
    }

    @Test
    void nullableCanWrapPrimitivesAndForbidsSelfNest() {

        assertTrue(Nullable.canWrap(new Primitive(Kind.Int32)));
        assertFalse(Nullable.canWrap(new Nullable(new Primitive(Kind.Int32))));
    }

    @Test
    void lowCardinalityCanWrapAllowsFloat() {

        assertTrue(LowCardinality.canWrap(new Primitive(Kind.Float64)));
        assertTrue(LowCardinality.canWrap(new Primitive(Kind.String)));
        assertTrue(LowCardinality.canWrap(new Nullable(new Primitive(Kind.Int32))));
    }

    @Test
    void unit12NewScalarKindsRenderAsColumnTypes() {
        assertEquals("Date32", new Primitive(Kind.Date32).toString());
        assertEquals("UInt16", new Primitive(Kind.UInt16).toString());
        assertEquals("UInt128", new Primitive(Kind.UInt128).toString());
        assertEquals("UInt256", new Primitive(Kind.UInt256).toString());
        assertEquals("IPv4", new Primitive(Kind.IPv4).toString());
        assertEquals("IPv6", new Primitive(Kind.IPv6).toString());
        assertEquals("UUID", new Primitive(Kind.UUID).toString());
    }

    @Test
    void unit12NewScalarKindsMapToJdbcType() {
        for (Kind k : new Kind[] { Kind.Date32, Kind.UInt16, Kind.UInt128, Kind.UInt256, Kind.IPv4, Kind.IPv6,
                Kind.UUID }) {
            assertNotNull(k.toClickHouseDataType(), () -> "no JDBC mapping for " + k);
        }
    }

    @Test
    void unit12LowCardinalityAcceptsDate32NotIpOrUuid() {

        assertTrue(LowCardinality.canWrap(new Primitive(Kind.Date32)));
        assertFalse(LowCardinality.canWrap(new Primitive(Kind.IPv4)));
        assertFalse(LowCardinality.canWrap(new Primitive(Kind.UUID)));
    }
}
