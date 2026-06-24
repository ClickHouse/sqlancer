package sqlancer.clickhouse;

import java.util.Objects;
import java.util.Optional;

import com.clickhouse.data.ClickHouseDataType;

public sealed interface ClickHouseType permits ClickHouseType.Primitive, ClickHouseType.FixedString, ClickHouseType.Decimal, ClickHouseType.DateTime64Type, ClickHouseType.Array, ClickHouseType.Tuple, ClickHouseType.Map, ClickHouseType.Enum, ClickHouseType.Time, ClickHouseType.Time64, ClickHouseType.Point, ClickHouseType.Ring, ClickHouseType.Polygon, ClickHouseType.MultiPolygon, ClickHouseType.Nested, ClickHouseType.JSON, ClickHouseType.Variant, ClickHouseType.Dynamic, ClickHouseType.IntervalType, ClickHouseType.AggregateFunctionType, ClickHouseType.SimpleAggregateFunctionType, ClickHouseType.Nullable, ClickHouseType.LowCardinality, ClickHouseType.Unknown {

    boolean isNumeric();

    boolean supportsLiteralEmission();

    boolean hasNullSemantics();

    enum Kind {
        Int8, Int16, Int32, Int64, Int128, Int256, UInt8, UInt16, UInt32, UInt64, UInt128, UInt256, Float32, Float64,
        Bool, String, UUID, Date, Date32, DateTime, IPv4, IPv6;

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

    default ClickHouseType unwrap() {
        if (this instanceof Nullable n) {
            return n.inner().unwrap();
        }
        if (this instanceof LowCardinality lc) {
            return lc.inner().unwrap();
        }
        return this;
    }

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

    record FixedString(int length) implements ClickHouseType {

        public FixedString {

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

        public static boolean canWrap(ClickHouseType type) {
            if (type instanceof Array || type instanceof Unknown) {
                return false;
            }
            return type.supportsLiteralEmission();
        }
    }

    record Tuple(java.util.List<ClickHouseType> elements) implements ClickHouseType {

        public Tuple {
            Objects.requireNonNull(elements, "elements");
            if (elements.isEmpty() || elements.size() > 8) {
                throw new IllegalArgumentException("Tuple arity out of range: " + elements.size());
            }
        }

        @Override
        public boolean isNumeric() {
            return false;
        }

        @Override
        public boolean supportsLiteralEmission() {
            for (ClickHouseType e : elements) {
                if (!e.supportsLiteralEmission()) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public boolean hasNullSemantics() {
            return false;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("Tuple(");
            for (int i = 0; i < elements.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(elements.get(i));
            }
            sb.append(")");
            return sb.toString();
        }
    }

    record Enum(int width, java.util.List<EnumEntry> entries) implements ClickHouseType {

        public Enum {
            if (width != 8 && width != 16) {
                throw new IllegalArgumentException("Enum width must be 8 or 16, got " + width);
            }
            Objects.requireNonNull(entries, "entries");
            if (entries.isEmpty() || entries.size() > 16) {
                throw new IllegalArgumentException("Enum entry count out of range: " + entries.size());
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
            StringBuilder sb = new StringBuilder("Enum");
            sb.append(width).append("(");
            for (int i = 0; i < entries.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                EnumEntry e = entries.get(i);
                sb.append("'").append(e.name()).append("' = ").append(e.value());
            }
            sb.append(")");
            return sb.toString();
        }
    }

    record Time() implements ClickHouseType {

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
            return "Time";
        }
    }

    record Time64(int precision) implements ClickHouseType {

        public Time64 {
            if (precision < 0 || precision > 9) {
                throw new IllegalArgumentException("Time64 precision out of range: " + precision);
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
            return "Time64(" + precision + ")";
        }
    }

    record Map(ClickHouseType keyType, ClickHouseType valueType) implements ClickHouseType {

        public Map {
            Objects.requireNonNull(keyType, "keyType");
            Objects.requireNonNull(valueType, "valueType");
        }

        @Override
        public boolean isNumeric() {
            return false;
        }

        @Override
        public boolean supportsLiteralEmission() {
            return keyType.supportsLiteralEmission() && valueType.supportsLiteralEmission();
        }

        @Override
        public boolean hasNullSemantics() {
            return false;
        }

        @Override
        public String toString() {
            return "Map(" + keyType + ", " + valueType + ")";
        }

        public static boolean isValidKey(ClickHouseType t) {
            ClickHouseType u = t.unwrap();
            if (u instanceof Primitive p) {
                switch (p.kind()) {
                case Int8:
                case Int16:
                case Int32:
                case Int64:
                case UInt8:
                case UInt16:
                case UInt32:
                case UInt64:
                case String:
                case UUID:
                case Date:
                case DateTime:
                    return true;
                default:
                    return false;
                }
            }
            return u instanceof FixedString;
        }
    }

    record Point() implements ClickHouseType {

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
            return "Point";
        }
    }

    record Ring() implements ClickHouseType {

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
            return "Ring";
        }
    }

    record Polygon() implements ClickHouseType {

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
            return "Polygon";
        }
    }

    record MultiPolygon() implements ClickHouseType {

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
            return "MultiPolygon";
        }
    }

    record Nested(java.util.List<NestedField> fields) implements ClickHouseType {

        public Nested {
            Objects.requireNonNull(fields, "fields");
            if (fields.isEmpty() || fields.size() > 6) {
                throw new IllegalArgumentException("Nested field count out of range: " + fields.size());
            }
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
            StringBuilder sb = new StringBuilder("Nested(");
            for (int i = 0; i < fields.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                NestedField f = fields.get(i);
                sb.append(f.name()).append(" ").append(f.type());
            }
            sb.append(")");
            return sb.toString();
        }
    }

    record NestedField(String name, ClickHouseType type) {
        public NestedField {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(type, "type");
        }
    }

    record JSON() implements ClickHouseType {
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
            return "JSON";
        }
    }

    record Variant(java.util.List<ClickHouseType> alternatives) implements ClickHouseType {
        public Variant {
            Objects.requireNonNull(alternatives, "alternatives");
            if (alternatives.isEmpty() || alternatives.size() > 6) {
                throw new IllegalArgumentException("Variant alternative count out of range: " + alternatives.size());
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
            StringBuilder sb = new StringBuilder("Variant(");
            for (int i = 0; i < alternatives.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(alternatives.get(i));
            }
            sb.append(")");
            return sb.toString();
        }
    }

    record Dynamic() implements ClickHouseType {
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
            return "Dynamic";
        }
    }

    enum IntervalKind {
        Nanosecond, Microsecond, Millisecond, Second, Minute, Hour, Day, Week, Month, Quarter, Year
    }

    record IntervalType(IntervalKind kind) implements ClickHouseType {
        public IntervalType {
            Objects.requireNonNull(kind, "kind");
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
            return "Interval" + kind.name();
        }
    }

    record AggregateFunctionType(String functionName, java.util.List<ClickHouseType> args) implements ClickHouseType {
        public AggregateFunctionType {
            Objects.requireNonNull(functionName, "functionName");
            Objects.requireNonNull(args, "args");
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
            StringBuilder sb = new StringBuilder("AggregateFunction(");
            sb.append(functionName);
            for (ClickHouseType a : args) {
                sb.append(", ").append(a);
            }
            sb.append(")");
            return sb.toString();
        }
    }

    record SimpleAggregateFunctionType(String functionName, ClickHouseType arg) implements ClickHouseType {
        public SimpleAggregateFunctionType {
            Objects.requireNonNull(functionName, "functionName");
            Objects.requireNonNull(arg, "arg");
        }

        @Override
        public boolean isNumeric() {
            return arg.isNumeric();
        }

        @Override
        public boolean supportsLiteralEmission() {
            return arg.supportsLiteralEmission();
        }

        @Override
        public boolean hasNullSemantics() {
            return false;
        }

        @Override
        public String toString() {
            return "SimpleAggregateFunction(" + functionName + ", " + arg + ")";
        }
    }

    record EnumEntry(String name, int value) {
        public EnumEntry {
            Objects.requireNonNull(name, "name");
            if (name.isEmpty() || name.length() > 32) {
                throw new IllegalArgumentException("Enum entry name length out of range: " + name.length());
            }
            for (int i = 0; i < name.length(); i++) {
                char c = name.charAt(i);
                if (c == '\'' || c == '\\' || c < 0x20) {
                    throw new IllegalArgumentException("Enum entry name contains forbidden character: " + name);
                }
            }
        }
    }

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

        public static boolean canWrap(ClickHouseType type) {
            return type instanceof Primitive || type instanceof FixedString || type instanceof Decimal
                    || type instanceof DateTime64Type || type instanceof Enum || type instanceof Time
                    || type instanceof Time64;
        }
    }

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
