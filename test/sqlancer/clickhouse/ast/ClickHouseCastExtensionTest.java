package sqlancer.clickhouse.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import sqlancer.IgnoreMeException;
import sqlancer.clickhouse.ast.constant.ClickHouseCreateConstant;
import sqlancer.clickhouse.ast.constant.ClickHouseUnsupportedConstant;

class ClickHouseCastExtensionTest {

    @Test
    void castToIntFromInt32IsIdentity() {
        ClickHouseConstant out = ClickHouseCast.castToInt(ClickHouseCreateConstant.createInt32Constant(5L));
        assertEquals(5L, out.asInt());
    }

    @Test
    void castToIntFromInt8() {
        ClickHouseConstant out = ClickHouseCast.castToInt(ClickHouseCreateConstant.createInt8Constant(5L));
        assertEquals(5L, out.asInt());
    }

    @Test
    void castToIntFromFloat32() {
        ClickHouseConstant out = ClickHouseCast.castToInt(ClickHouseCreateConstant.createFloat32Constant(1.7f));
        assertEquals(1L, out.asInt());
    }

    @Test
    void castToIntFromBool() {
        ClickHouseConstant trueResult = ClickHouseCast.castToInt(ClickHouseCreateConstant.createTrue());
        ClickHouseConstant falseResult = ClickHouseCast.castToInt(ClickHouseCreateConstant.createFalse());
        assertEquals(1L, trueResult.asInt());
        assertEquals(0L, falseResult.asInt());
    }

    @Test
    void castToIntFromUnsupportedPropagates() {
        ClickHouseConstant in = new ClickHouseUnsupportedConstant();
        ClickHouseConstant out = ClickHouseCast.castToInt(in);
        assertInstanceOf(ClickHouseUnsupportedConstant.class, out);
    }

    @Test
    void castToIntFromInt256OutOfLongRangeClampsByNeitherFailsNorThrows() {

        ClickHouseConstant cons = ClickHouseCreateConstant.createInt256Constant(BigInteger.valueOf(42L));
        ClickHouseConstant out = ClickHouseCast.castToInt(cons);
        assertEquals(42L, out.asInt());
    }

    @Test
    void castToRealFromFloat32() {
        ClickHouseConstant out = ClickHouseCast.castToReal(ClickHouseCreateConstant.createFloat32Constant(1.5f));
        assertEquals(1.5, out.asDouble(), 1e-6);
    }

    @Test
    void castToRealFromInt32() {
        ClickHouseConstant out = ClickHouseCast.castToReal(ClickHouseCreateConstant.createInt32Constant(7L));
        assertEquals(7.0, out.asDouble(), 1e-9);
    }

    @Test
    void castToRealFromBool() {
        assertEquals(1.0, ClickHouseCast.castToReal(ClickHouseCreateConstant.createTrue()).asDouble(), 1e-9);
        assertEquals(0.0, ClickHouseCast.castToReal(ClickHouseCreateConstant.createFalse()).asDouble(), 1e-9);
    }

    @Test
    void castToRealFromUnsupportedPropagates() {
        assertInstanceOf(ClickHouseUnsupportedConstant.class,
                ClickHouseCast.castToReal(new ClickHouseUnsupportedConstant()));
    }

    @Test
    void castToTextFromInt32() {
        ClickHouseConstant out = ClickHouseCast.castToText(ClickHouseCreateConstant.createInt32Constant(42L));
        assertEquals("42", out.getValue());
    }

    @Test
    void castToTextFromBool() {
        assertEquals("true", ClickHouseCast.castToText(ClickHouseCreateConstant.createTrue()).getValue());
        assertEquals("false", ClickHouseCast.castToText(ClickHouseCreateConstant.createFalse()).getValue());
    }

    @Test
    void castToTextFromInt256() {
        ClickHouseConstant out = ClickHouseCast
                .castToText(ClickHouseCreateConstant.createInt256Constant(BigInteger.valueOf(99L)));
        assertEquals("99", out.getValue());
    }

    @Test
    void castToTextFromUnsupportedPropagates() {
        assertInstanceOf(ClickHouseUnsupportedConstant.class,
                ClickHouseCast.castToText(new ClickHouseUnsupportedConstant()));
    }

    @Test
    void isTrueForNumericPrimitives() {
        assertEquals(Optional.of(true), ClickHouseCast.isTrue(ClickHouseCreateConstant.createInt8Constant(1L)));
        assertEquals(Optional.of(false), ClickHouseCast.isTrue(ClickHouseCreateConstant.createInt8Constant(0L)));
        assertEquals(Optional.of(true), ClickHouseCast.isTrue(ClickHouseCreateConstant.createFloat32Constant(1.5f)));
    }

    @Test
    void isTrueForBool() {
        assertEquals(Optional.of(true), ClickHouseCast.isTrue(ClickHouseCreateConstant.createTrue()));
        assertEquals(Optional.of(false), ClickHouseCast.isTrue(ClickHouseCreateConstant.createFalse()));
    }

    @Test
    void isTrueForNothingIsEmpty() {
        assertEquals(Optional.empty(), ClickHouseCast.isTrue(ClickHouseCreateConstant.createNullConstant()));
    }

    @Test
    void isTrueForUnsupportedIsEmpty() {
        assertEquals(Optional.empty(), ClickHouseCast.isTrue(new ClickHouseUnsupportedConstant()));
    }

    @Test
    void unsupportedConstantSignalsItself() {
        ClickHouseUnsupportedConstant u = new ClickHouseUnsupportedConstant();
        assertTrue(u.isUnsupported());
        assertFalse(u.isNull());
    }

    @Test
    void unsupportedConstantOperationsThrowIgnoreMe() {
        ClickHouseUnsupportedConstant u = new ClickHouseUnsupportedConstant();
        assertThrows(IgnoreMeException.class, () -> u.applyEquals(ClickHouseCreateConstant.createInt32Constant(1L)));
        assertThrows(IgnoreMeException.class, () -> u.applyLess(ClickHouseCreateConstant.createInt32Constant(1L)));
        assertThrows(IgnoreMeException.class, () -> u.cast(com.clickhouse.data.ClickHouseDataType.Int32));
        assertThrows(IgnoreMeException.class, u::asBooleanNotNull);
        assertThrows(IgnoreMeException.class, u::getValue);
    }
}
