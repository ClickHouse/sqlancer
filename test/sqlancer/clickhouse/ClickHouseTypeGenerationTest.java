package sqlancer.clickhouse;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
