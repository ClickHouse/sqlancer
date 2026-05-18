package sqlancer.clickhouse;

import java.util.Objects;
import java.util.Optional;

import com.clickhouse.data.ClickHouseDataType;

/**
 * Recursive ADT for ClickHouse types -- v2.
 *
 * <p>
 * Eight constructors: {@link Primitive}, {@link FixedString}, {@link Decimal}, {@link DateTime64Type}, {@link Array},
 * {@link Nullable}, {@link LowCardinality}, {@link Unknown}. v1 emitted only {@link Primitive}, {@link Nullable},
 * {@link LowCardinality}, {@link Unknown} and only picked {@code Int32}/{@code String} kinds; v2 widens the kind set to
 * every entry of {@link Kind} and adds the four parameterised constructors above so the generator can produce
 * mixed-width integers, dates/datetimes, decimals, fixed-length strings, and array columns. Unknown remains the
 * defensive fallback for type strings outside the parsed v2 surface.
 * </p>
 */
public sealed interface ClickHouseType permits ClickHouseType.Primitive, ClickHouseType.FixedString, ClickHouseType.Decimal, ClickHouseType.DateTime64Type, ClickHouseType.Array, ClickHouseType.Nullable, ClickHouseType.LowCardinality, ClickHouseType.Unknown {

    // true for integer/float primitives + Decimal; recurses through Nullable/LowCardinality. Array is
    // not numeric (the array itself is a composite); inner-array element type does not propagate.
    boolean isNumeric();

    // true when the type has a literal form the constant emitters can produce.
    boolean supportsLiteralEmission();

    // true iff the outer term is Nullable -- not transitive.
    boolean hasNullSemantics();

    // v2 set of primitive kinds. Each Kind has a zero-parameter spelling in ClickHouse. Parameterised
    // types (FixedString(N), Decimal(p,s), DateTime64(prec), Array(T)) are separate constructors,
    // not kinds. DateTime64 lives outside the Kind enum because it carries a precision; plain
    // DateTime (second-resolution) is here.
    enum Kind {
        Int8, Int16, Int32, Int64, Int128, Int256, UInt8, UInt16, UInt32, UInt64, UInt128, UInt256, Float32, Float64,
        Bool, String, UUID, Date, Date32, DateTime, IPv4, IPv6;

        // Map this kind back to the JDBC driver's flat enum for legacy code paths.
        public ClickHouseDataType toClickHouseDataType() {
            switch (this) {
            case Int8:
                return ClickHouseDataType.Int8;
            case Int16:
                return ClickHouseDataType.Int16;
            case Int32:
                return ClickHouseDataType.Int32;
            case Int64:
                return ClickHouseDataType.Int64;
            case Int128:
                return ClickHouseDataType.Int128;
            case Int256:
                return ClickHouseDataType.Int256;
            case UInt8:
                return ClickHouseDataType.UInt8;
            case UInt16:
                return ClickHouseDataType.UInt16;
            case UInt32:
                return ClickHouseDataType.UInt32;
            case UInt64:
                return ClickHouseDataType.UInt64;
            case UInt128:
                return ClickHouseDataType.UInt128;
            case UInt256:
                return ClickHouseDataType.UInt256;
            case Float32:
                return ClickHouseDataType.Float32;
            case Float64:
                return ClickHouseDataType.Float64;
            case Bool:
                return ClickHouseDataType.Bool;
            case String:
                return ClickHouseDataType.String;
            case UUID:
                return ClickHouseDataType.UUID;
            case Date:
                return ClickHouseDataType.Date;
            case Date32:
                return ClickHouseDataType.Date32;
            case DateTime:
                return ClickHouseDataType.DateTime;
            case IPv4:
                return ClickHouseDataType.IPv4;
            case IPv6:
                return ClickHouseDataType.IPv6;
            default:
                throw new AssertionError(this);
            }
        }

        // Inverse of toClickHouseDataType. Returns empty when the JDBC type does not belong to the
        // Kind set -- callers should treat that as Unknown or use a parameterised constructor.
        public static Optional<Kind> fromClickHouseDataType(ClickHouseDataType type) {
            if (type == null) {
                return Optional.empty();
            }
            switch (type) {
            case Int8:
                return Optional.of(Int8);
            case Int16:
                return Optional.of(Int16);
            case Int32:
                return Optional.of(Int32);
            case Int64:
                return Optional.of(Int64);
            case Int128:
                return Optional.of(Int128);
            case Int256:
                return Optional.of(Int256);
            case UInt8:
                return Optional.of(UInt8);
            case UInt16:
                return Optional.of(UInt16);
            case UInt32:
                return Optional.of(UInt32);
            case UInt64:
                return Optional.of(UInt64);
            case UInt128:
                return Optional.of(UInt128);
            case UInt256:
                return Optional.of(UInt256);
            case Float32:
                return Optional.of(Float32);
            case Float64:
                return Optional.of(Float64);
            case Bool:
                return Optional.of(Bool);
            case String:
                return Optional.of(String);
            case UUID:
                return Optional.of(UUID);
            case Date:
                return Optional.of(Date);
            case Date32:
                return Optional.of(Date32);
            case DateTime:
            case DateTime32:
                return Optional.of(DateTime);
            case IPv4:
                return Optional.of(IPv4);
            case IPv6:
                return Optional.of(IPv6);
            default:
                return Optional.empty();
            }
        }
    }

    // Unwraps Nullable and LowCardinality; returns `this` for primitives, parameterised types,
    // Array, and Unknown. Note: Array is *not* unwrapped -- the array itself is the value.
    default ClickHouseType unwrap() {
        if (this instanceof Nullable n) {
            return n.inner().unwrap();
        }
        if (this instanceof LowCardinality lc) {
            return lc.inner().unwrap();
        }
        return this;
    }

    // Atomic primitive type (no parameters).
    record Primitive(Kind kind) implements ClickHouseType {

        public Primitive {
            Objects.requireNonNull(kind, "kind");
        }

        @Override
        public boolean isNumeric() {
            switch (kind) {
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

        @Override
        public boolean supportsLiteralEmission() {
            return true;
        }

        @Override
        public boolean hasNullSemantics() {
            return false;
        }

        @Override
        public String toString() {
            return kind.name();
        }
    }

    // FixedString(N) -- fixed-length binary string, N bytes. N is clamped at construction.
    record FixedString(int length) implements ClickHouseType {

        public FixedString {
            // ClickHouse accepts any N >= 1; cap at 256 to keep insert payloads bounded.
            if (length < 1 || length > 256) {
                throw new IllegalArgumentException("FixedString length out of range: " + length);
            }
        }

        @Override
        public boolean isNumeric() {
            return false;
        }

        @Override
        public boolean supportsLiteralEmission() {
            return true;
        }

        @Override
        public boolean hasNullSemantics() {
            return false;
        }

        @Override
        public String toString() {
            return "FixedString(" + length + ")";
        }
    }

    // Decimal(precision, scale). precision in [1,76], scale in [0, precision].
    record Decimal(int precision, int scale) implements ClickHouseType {

        public Decimal {
            if (precision < 1 || precision > 76 || scale < 0 || scale > precision) {
                throw new IllegalArgumentException("Decimal out of range: P=" + precision + " S=" + scale);
            }
        }

        @Override
        public boolean isNumeric() {
            return true;
        }

        @Override
        public boolean supportsLiteralEmission() {
            return true;
        }

        @Override
        public boolean hasNullSemantics() {
            return false;
        }

        @Override
        public String toString() {
            return "Decimal(" + precision + ", " + scale + ")";
        }
    }

    // DateTime64(precision[, timezone]). Timezone omitted for now; server uses session tz. Precision
    // in [0,9] -- ClickHouse-documented bounds.
    record DateTime64Type(int precision) implements ClickHouseType {

        public DateTime64Type {
            if (precision < 0 || precision > 9) {
                throw new IllegalArgumentException("DateTime64 precision out of range: " + precision);
            }
        }

        @Override
        public boolean isNumeric() {
            return false;
        }

        @Override
        public boolean supportsLiteralEmission() {
            return true;
        }

        @Override
        public boolean hasNullSemantics() {
            return false;
        }

        @Override
        public String toString() {
            return "DateTime64(" + precision + ")";
        }
    }

    // Array(inner). Inner cannot be Nullable(Array(...)) -- nested arrays must be plain; Array of
    // Nullable scalar IS allowed (e.g. Array(Nullable(Int32))). canWrap encodes that.
    record Array(ClickHouseType inner) implements ClickHouseType {

        public Array {
            Objects.requireNonNull(inner, "inner");
        }

        @Override
        public boolean isNumeric() {
            return false;
        }

        @Override
        public boolean supportsLiteralEmission() {
            return inner.supportsLiteralEmission();
        }

        @Override
        public boolean hasNullSemantics() {
            return false;
        }

        @Override
        public String toString() {
            return "Array(" + inner + ")";
        }

        // Array(Nullable(scalar)) is allowed. Array(LowCardinality(scalar)) is allowed. We restrict
        // Array(Array(...)) -- nested arrays multiply the constant-emission surface and add no
        // bug-finding signal at this stage.
        public static boolean canWrap(ClickHouseType type) {
            if (type instanceof Array || type instanceof Unknown) {
                return false;
            }
            return type.supportsLiteralEmission();
        }
    }

    // Nullable(inner) -- the value domain of `inner` extended with NULL.
    record Nullable(ClickHouseType inner) implements ClickHouseType {

        public Nullable {
            Objects.requireNonNull(inner, "inner");
        }

        @Override
        public boolean isNumeric() {
            return inner.isNumeric();
        }

        @Override
        public boolean supportsLiteralEmission() {
            return inner.supportsLiteralEmission();
        }

        @Override
        public boolean hasNullSemantics() {
            return true;
        }

        @Override
        public String toString() {
            return "Nullable(" + inner + ")";
        }

        // Nullable can wrap any non-composite primitive-like value (Primitive, FixedString, Decimal,
        // DateTime64). ClickHouse rejects Nullable(Array(...)), Nullable(Nullable(...)),
        // Nullable(LowCardinality(...)) (LowCardinality must be the outer wrapper), and
        // Nullable(Unknown).
        public static boolean canWrap(ClickHouseType type) {
            return type instanceof Primitive || type instanceof FixedString || type instanceof Decimal
                    || type instanceof DateTime64Type;
        }
    }

    // LowCardinality(inner) -- dictionary-encoded inner type.
    record LowCardinality(ClickHouseType inner) implements ClickHouseType {

        public LowCardinality {
            Objects.requireNonNull(inner, "inner");
        }

        @Override
        public boolean isNumeric() {
            return inner.isNumeric();
        }

        @Override
        public boolean supportsLiteralEmission() {
            return inner.supportsLiteralEmission();
        }

        @Override
        public boolean hasNullSemantics() {
            return false;
        }

        @Override
        public String toString() {
            return "LowCardinality(" + inner + ")";
        }

        // LowCardinality accepts: String, FixedString, all integer kinds, Date, Date32, DateTime,
        // and Nullable of those. Floats/Bool/UUID/IPv*/Decimal/DateTime64/Array/composites are
        // rejected. The session setting `allow_suspicious_low_cardinality_types=1` lifts some of
        // those bans (Float, Decimal); the generator opts in via the JDBC URL.
        public static boolean canWrap(ClickHouseType type) {
            if (type instanceof Nullable n) {
                return canWrap(n.inner());
            }
            if (type instanceof FixedString) {
                return true;
            }
            if (type instanceof Primitive p) {
                switch (p.kind()) {
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
                case String:
                case Date:
                case Date32:
                case DateTime:
                    return true;
                default:
                    return false;
                }
            }
            return false;
        }
    }

    // Defensive fallback when the parser does not recognise a type string.
    record Unknown(String raw) implements ClickHouseType {

        public Unknown {
            Objects.requireNonNull(raw, "raw");
        }

        @Override
        public boolean isNumeric() {
            return false;
        }

        @Override
        public boolean supportsLiteralEmission() {
            return false;
        }

        @Override
        public boolean hasNullSemantics() {
            return false;
        }

        @Override
        public String toString() {
            return raw;
        }
    }
}
