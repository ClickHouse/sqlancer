package sqlancer.clickhouse;

import java.util.Objects;
import java.util.Optional;

import com.clickhouse.data.ClickHouseDataType;

/**
 * Recursive ADT for ClickHouse types used by the v1 type-system foundation.
 *
 * <p>
 * Replaces the flat {@code (ClickHouseDataType, String)} representation in {@code
 * ClickHouseLancerDataType} with four constructors: {@link Primitive}, {@link Nullable}, {@link LowCardinality}, and
 * {@link Unknown}. Unknown is a defensive fallback for type strings that the v1 reflection parser does not recognise;
 * oracles and generators are expected to skip Unknown columns via {@link sqlancer.IgnoreMeException}.
 * </p>
 */
public sealed interface ClickHouseType permits ClickHouseType.Primitive, ClickHouseType.Nullable, ClickHouseType.LowCardinality, ClickHouseType.Unknown {

    // true for integer/float primitives; recurses through Nullable/LowCardinality.
    boolean isNumeric();

    // true when the type has a literal form the constant emitters can produce.
    boolean supportsLiteralEmission();

    // true iff the outer term is Nullable -- not transitive.
    boolean hasNullSemantics();

    // v1 set of primitive kinds. Listed in Key Technical Decisions of the type-system foundation
    // plan; deferred kinds (Decimal, FixedString, Enum, DateTime*) land in v1.1+ together with
    // their literal emitters and capability extensions.
    enum Kind {
        Int8, Int16, Int32, Int64, Int128, Int256, UInt8, UInt16, UInt32, UInt64, UInt128, UInt256, Float32, Float64,
        Bool, String, UUID, Date, Date32, IPv4, IPv6;

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
            case IPv4:
                return ClickHouseDataType.IPv4;
            case IPv6:
                return ClickHouseDataType.IPv6;
            default:
                throw new AssertionError(this);
            }
        }

        // Inverse of toClickHouseDataType. Returns empty when the JDBC type does not belong to the
        // v1 kind set -- callers should treat that as Unknown.
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
            case IPv4:
                return Optional.of(IPv4);
            case IPv6:
                return Optional.of(IPv6);
            default:
                return Optional.empty();
            }
        }
    }

    // Unwraps Nullable and LowCardinality; returns `this` for primitives and unknown.
    default ClickHouseType unwrap() {
        if (this instanceof Nullable n) {
            return n.inner().unwrap();
        }
        if (this instanceof LowCardinality lc) {
            return lc.inner().unwrap();
        }
        return this;
    }

    // Atomic primitive type (no parameters in v1).
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

        // Nullable cannot self-nest and cannot wrap LowCardinality or Unknown.
        public static boolean canWrap(ClickHouseType type) {
            return type instanceof Primitive;
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

        // LowCardinality accepts a conservative v1 inner set: String, all signed and unsigned
        // integer kinds, Date, Date32, and Nullable of those. Floats, Bool, UUID, IPv4/IPv6,
        // Decimal, composites, and other wrappers are rejected -- the generator consults this
        // predicate before constructing.
        public static boolean canWrap(ClickHouseType type) {
            if (type instanceof Nullable n) {
                return canWrap(n.inner());
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
                case String:
                case Date:
                case Date32:
                    return true;
                default:
                    return false;
                }
            }
            return false;
        }
    }

    // Defensive fallback when the reflection parser does not recognise a type string.
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
