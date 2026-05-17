package sqlancer.clickhouse;

import java.util.HashMap;
import java.util.Map;

import sqlancer.clickhouse.ClickHouseType.Kind;
import sqlancer.clickhouse.ClickHouseType.LowCardinality;
import sqlancer.clickhouse.ClickHouseType.Nullable;
import sqlancer.clickhouse.ClickHouseType.Primitive;
import sqlancer.clickhouse.ClickHouseType.Unknown;

/**
 * Hand-written recursive-descent parser for ClickHouse type strings, scoped to the v1 ADT.
 *
 * <p>
 * Recognises the v1 primitive kinds (every {@link Kind} name, case-sensitive ClickHouse spelling) and the wrappers
 * {@code Nullable(...)} and {@code LowCardinality(...)}. Anything outside that surface is preserved verbatim as
 * {@link Unknown} -- the parser never throws on unrecognised input.
 * </p>
 *
 * <p>
 * Codec / DEFAULT / ALIAS / MATERIALIZED suffixes are out of scope here; they are stripped at the {@code DESCRIBE} row
 * level in {@code ClickHouseSchema.getTableColumns} before the type string reaches the parser.
 * </p>
 */
public final class ClickHouseTypeParser {

    private static final Map<String, Kind> PRIMITIVES = new HashMap<>();

    static {
        for (Kind k : Kind.values()) {
            PRIMITIVES.put(k.name(), k);
        }
    }

    private ClickHouseTypeParser() {
    }

    // Parse a ClickHouse type string into a ClickHouseType. Returns Unknown for any input that does
    // not match the v1 grammar -- never throws.
    public static ClickHouseType parse(String typeString) {
        if (typeString == null) {
            return new Unknown("");
        }
        String trimmed = typeString.trim();
        ClickHouseType parsed = tryParseRecognised(trimmed);
        return parsed != null ? parsed : new Unknown(typeString);
    }

    private static ClickHouseType tryParseRecognised(String s) {
        if (s.isEmpty()) {
            return null;
        }
        String stripped = stripWrapper(s, "Nullable");
        if (stripped != null) {
            ClickHouseType inner = tryParseRecognised(stripped.trim());
            return inner != null ? new Nullable(inner) : null;
        }
        stripped = stripWrapper(s, "LowCardinality");
        if (stripped != null) {
            ClickHouseType inner = tryParseRecognised(stripped.trim());
            return inner != null ? new LowCardinality(inner) : null;
        }
        Kind kind = PRIMITIVES.get(s);
        if (kind != null) {
            return new Primitive(kind);
        }
        return null;
    }

    private static String stripWrapper(String s, String wrapperName) {
        String prefix = wrapperName + "(";
        if (s.startsWith(prefix) && s.endsWith(")")) {
            return s.substring(prefix.length(), s.length() - 1);
        }
        return null;
    }
}
