package sqlancer.clickhouse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import sqlancer.clickhouse.ClickHouseSchema.ClickHouseLancerDataType;
import sqlancer.clickhouse.ClickHouseType.Kind;
import sqlancer.clickhouse.ClickHouseType.LowCardinality;
import sqlancer.clickhouse.ClickHouseType.Nullable;
import sqlancer.clickhouse.ClickHouseType.Primitive;

/**
 * Unit tests for the v1 type-generation surface. State-aware random picking is exercised end-to-end in the integration
 * suite; here we only validate the static no-state path and the wrapper guard rails -- both reachable without spinning
 * up a ClickHouse instance.
 */
class ClickHouseTypeGenerationTest {

    @Test
    void getRandomWithoutStateAlwaysReturnsPrimitive() {
        // The no-state form is used by AST scaffolding and test fixtures; it must not emit wrapper
        // types regardless of any package-level state.
        for (int i = 0; i < 256; i++) {
            ClickHouseLancerDataType t = ClickHouseLancerDataType.getRandom();
            assertTrue(t.getTypeTerm() instanceof Primitive,
                    () -> "expected Primitive but got " + t.getTypeTerm().getClass().getSimpleName());
        }
    }

    @Test
    void nullableCanWrapPrimitivesAndForbidsSelfNest() {
        // Direct canWrap exercise -- the random picker consults this before constructing Nullable.
        assertTrue(Nullable.canWrap(new Primitive(Kind.Int32)));
        assertFalse(Nullable.canWrap(new Nullable(new Primitive(Kind.Int32))));
    }

    @Test
    void lowCardinalityCanWrapRejectsFloat() {
        assertFalse(LowCardinality.canWrap(new Primitive(Kind.Float64)));
        assertTrue(LowCardinality.canWrap(new Primitive(Kind.String)));
        assertTrue(LowCardinality.canWrap(new Nullable(new Primitive(Kind.Int32))));
    }

    // Unit 1.2: scalar kinds the picker now emits as columns must render the correct DDL type
    // spelling and map cleanly onto the JDBC data-type enum the column builder consumes.

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
        // Date32 is a valid LowCardinality inner (dictionary-encodable); IPv*/UUID are not.
        assertTrue(LowCardinality.canWrap(new Primitive(Kind.Date32)));
        assertFalse(LowCardinality.canWrap(new Primitive(Kind.IPv4)));
        assertFalse(LowCardinality.canWrap(new Primitive(Kind.UUID)));
    }
}
