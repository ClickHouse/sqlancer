package sqlancer.clickhouse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import sqlancer.clickhouse.ClickHouseType.Kind;
import sqlancer.clickhouse.ClickHouseType.LowCardinality;
import sqlancer.clickhouse.ClickHouseType.Nullable;
import sqlancer.clickhouse.ClickHouseType.Primitive;
import sqlancer.clickhouse.ClickHouseType.Unknown;

class ClickHouseTypeTest {

    // ----- Unit 1: ADT shape, equality, toString -----

    @Test
    void primitiveToString() {
        assertEquals("Int32", new Primitive(Kind.Int32).toString());
        assertEquals("String", new Primitive(Kind.String).toString());
        assertEquals("Float64", new Primitive(Kind.Float64).toString());
    }

    @Test
    void primitiveValueEquality() {
        Primitive a = new Primitive(Kind.Int32);
        Primitive b = new Primitive(Kind.Int32);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void nullableToStringRoundTrip() {
        ClickHouseType t = new Nullable(new Primitive(Kind.Int32));
        assertEquals("Nullable(Int32)", t.toString());
        assertEquals(new Nullable(new Primitive(Kind.Int32)), t);
    }

    @Test
    void lowCardinalityNullableNesting() {
        ClickHouseType t = new LowCardinality(new Nullable(new Primitive(Kind.String)));
        assertEquals("LowCardinality(Nullable(String))", t.toString());
        ClickHouseType other = new LowCardinality(new Nullable(new Primitive(Kind.String)));
        assertEquals(t, other);
    }

    @Test
    void unknownPreservesRawText() {
        Unknown u = new Unknown("Decimal(9, 2)");
        assertEquals("Decimal(9, 2)", u.toString());
        assertEquals(new Unknown("Decimal(9, 2)"), u);
        assertNotEquals(new Unknown("Decimal(9,2)"), u);
    }

    @Test
    void unwrapReturnsInnerPrimitive() {
        ClickHouseType wrapped = new LowCardinality(new Nullable(new Primitive(Kind.String)));
        assertEquals(new Primitive(Kind.String), wrapped.unwrap());
    }

    // ----- Unit 1: canWrap rules -----

    @Test
    void nullableCanWrapPrimitivesOnly() {
        assertTrue(Nullable.canWrap(new Primitive(Kind.Int32)));
        assertTrue(Nullable.canWrap(new Primitive(Kind.String)));
        assertFalse(Nullable.canWrap(new Nullable(new Primitive(Kind.Int32))));
        assertFalse(Nullable.canWrap(new LowCardinality(new Primitive(Kind.String))));
        assertFalse(Nullable.canWrap(new Unknown("Decimal(9,2)")));
    }

    @Test
    void lowCardinalityCanWrapRules() {
        assertTrue(LowCardinality.canWrap(new Primitive(Kind.String)));
        assertTrue(LowCardinality.canWrap(new Primitive(Kind.Int32)));
        assertTrue(LowCardinality.canWrap(new Primitive(Kind.UInt64)));
        assertTrue(LowCardinality.canWrap(new Primitive(Kind.Date)));
        assertTrue(LowCardinality.canWrap(new Primitive(Kind.Date32)));
        assertTrue(LowCardinality.canWrap(new Nullable(new Primitive(Kind.Int32))));
        assertTrue(LowCardinality.canWrap(new Nullable(new Primitive(Kind.String))));

        assertFalse(LowCardinality.canWrap(new Primitive(Kind.Float32)));
        assertFalse(LowCardinality.canWrap(new Primitive(Kind.Float64)));
        assertFalse(LowCardinality.canWrap(new Primitive(Kind.Bool)));
        assertFalse(LowCardinality.canWrap(new Primitive(Kind.UUID)));
        assertFalse(LowCardinality.canWrap(new Primitive(Kind.IPv4)));
        assertFalse(LowCardinality.canWrap(new Primitive(Kind.IPv6)));
        assertFalse(LowCardinality.canWrap(new LowCardinality(new Primitive(Kind.String))));
        assertFalse(LowCardinality.canWrap(new Unknown("Decimal(9,2)")));
    }

    // ----- Unit 2: Capability predicates -----

    @Test
    void isNumericTableByKind() {
        for (Kind k : Kind.values()) {
            boolean expectedNumeric = isExpectedNumeric(k);
            assertEquals(expectedNumeric, new Primitive(k).isNumeric(), () -> "isNumeric for " + k);
        }
    }

    @Test
    void isNumericRecursesThroughWrappers() {
        assertTrue(new Nullable(new Primitive(Kind.Int32)).isNumeric());
        assertFalse(new Nullable(new Primitive(Kind.String)).isNumeric());
        assertTrue(new LowCardinality(new Primitive(Kind.Int32)).isNumeric());
        assertTrue(new LowCardinality(new Nullable(new Primitive(Kind.Int32))).isNumeric());
    }

    @Test
    void supportsLiteralEmissionForEveryPrimitive() {
        for (Kind k : Kind.values()) {
            assertTrue(new Primitive(k).supportsLiteralEmission(), () -> "supportsLiteralEmission for " + k);
        }
        assertTrue(new Nullable(new Primitive(Kind.Int32)).supportsLiteralEmission());
        assertTrue(new LowCardinality(new Primitive(Kind.String)).supportsLiteralEmission());
        assertFalse(new Unknown("Decimal(9,2)").supportsLiteralEmission());
    }

    @Test
    void hasNullSemanticsOnlyAtNullableRoot() {
        assertFalse(new Primitive(Kind.Int32).hasNullSemantics());
        assertTrue(new Nullable(new Primitive(Kind.Int32)).hasNullSemantics());
        assertFalse(new LowCardinality(new Nullable(new Primitive(Kind.String))).hasNullSemantics());
        assertFalse(new LowCardinality(new Primitive(Kind.String)).hasNullSemantics());
        assertFalse(new Unknown("Decimal(9,2)").hasNullSemantics());
    }

    @Test
    void unknownNeverHasCapabilities() {
        Unknown u = new Unknown("Decimal(9, 2)");
        assertFalse(u.isNumeric());
        assertFalse(u.supportsLiteralEmission());
        assertFalse(u.hasNullSemantics());
    }

    private static boolean isExpectedNumeric(Kind k) {
        switch (k) {
        case Int8:
        case Int16:
        case Int32:
        case Int64:
        case Int128:
        case Int256:
        case UInt8:
        case UInt16:
        case UInt32:
        case UInt64:
        case UInt128:
        case UInt256:
        case Float32:
        case Float64:
            return true;
        default:
            return false;
        }
    }
}
