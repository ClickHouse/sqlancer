package sqlancer.clickhouse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import org.junit.jupiter.api.Test;

import sqlancer.clickhouse.ClickHouseType.Array;
import sqlancer.clickhouse.ClickHouseType.Decimal;
import sqlancer.clickhouse.ClickHouseType.Kind;
import sqlancer.clickhouse.ClickHouseType.LowCardinality;
import sqlancer.clickhouse.ClickHouseType.Nullable;
import sqlancer.clickhouse.ClickHouseType.Primitive;
import sqlancer.clickhouse.ClickHouseType.Unknown;

class ClickHouseTypeParserTest {

    @Test
    void parsesEveryPrimitiveKind() {
        for (Kind k : Kind.values()) {
            assertEquals(new Primitive(k), ClickHouseTypeParser.parse(k.name()), () -> "parse(" + k + ")");
        }
    }

    @Test
    void parsesNullableWrapper() {
        assertEquals(new Nullable(new Primitive(Kind.Int32)), ClickHouseTypeParser.parse("Nullable(Int32)"));
        assertEquals(new Nullable(new Primitive(Kind.String)), ClickHouseTypeParser.parse("Nullable(String)"));
    }

    @Test
    void parsesLowCardinalityWrapper() {
        assertEquals(new LowCardinality(new Primitive(Kind.String)),
                ClickHouseTypeParser.parse("LowCardinality(String)"));
    }

    @Test
    void parsesNestedWrappers() {
        assertEquals(new LowCardinality(new Nullable(new Primitive(Kind.String))),
                ClickHouseTypeParser.parse("LowCardinality(Nullable(String))"));
        assertEquals(new Nullable(new LowCardinality(new Primitive(Kind.Int32))),
                ClickHouseTypeParser.parse("Nullable(LowCardinality(Int32))"));
    }

    @Test
    void parsesParameterizedTypes() {
        // The parser now understands Decimal(P, S) and Array(...), and recurses into them under
        // wrappers. These used to cascade to Unknown; they now parse to structured types.
        assertEquals(new Decimal(9, 2), ClickHouseTypeParser.parse("Decimal(9, 2)"));
        assertEquals(new Array(new Primitive(Kind.Int32)), ClickHouseTypeParser.parse("Array(Int32)"));
        // Nullable around a Decimal parses through; spacing is normalised on the inner Decimal.
        assertEquals(new Nullable(new Decimal(9, 2)), ClickHouseTypeParser.parse("Nullable(Decimal(9,2))"));
    }

    @Test
    void unrecognisedNamesBecomeUnknown() {
        assertEquals(new Unknown("UnknownTypeName123"), ClickHouseTypeParser.parse("UnknownTypeName123"));
    }

    @Test
    void emptyStringBecomesUnknown() {
        assertEquals(new Unknown(""), ClickHouseTypeParser.parse(""));
        assertEquals(new Unknown(""), ClickHouseTypeParser.parse(null));
    }

    @Test
    void tolerantOfAmbientWhitespace() {
        // ClickHouse DESCRIBE rows are usually compact but not guaranteed; ambient whitespace within
        // wrapper parens should still parse.
        assertEquals(new Nullable(new Primitive(Kind.Int32)), ClickHouseTypeParser.parse("Nullable( Int32 )"));
    }

    @Test
    void roundTripFromToString() {
        // Every v1 type the generator can emit should parse back to an equal value.
        ClickHouseType[] samples = { new Primitive(Kind.Int32), new Primitive(Kind.UInt256), new Primitive(Kind.String),
                new Primitive(Kind.Date32), new Nullable(new Primitive(Kind.Int8)),
                new LowCardinality(new Primitive(Kind.String)),
                new LowCardinality(new Nullable(new Primitive(Kind.Int32))) };
        for (ClickHouseType sample : samples) {
            assertEquals(sample, ClickHouseTypeParser.parse(sample.toString()), () -> "round-trip for " + sample);
        }
    }

    @Test
    void parserDoesNotThrow() {
        // Defensive: every input must produce a ClickHouseType -- never an exception.
        String[] adversarialInputs = { "(", ")", "Nullable(", "Nullable()", "Nullable(Int32",
                "LowCardinality(Nullable(Float32))", "Nullable(LowCardinality(LowCardinality(String)))",
                "Something(Else(Nested(Deeply)))", "Int32 NOT NULL", "Int32 DEFAULT 5", " ", "  Int32  ",
                "Decimal(38, 0)" };
        for (String input : adversarialInputs) {
            assertInstanceOf(ClickHouseType.class, ClickHouseTypeParser.parse(input),
                    () -> "non-null parse for " + input);
        }
    }
}
