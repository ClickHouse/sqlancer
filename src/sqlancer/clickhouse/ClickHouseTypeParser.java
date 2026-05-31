package sqlancer.clickhouse;

import java.util.HashMap;
import java.util.Map;

import sqlancer.clickhouse.ClickHouseType.Array;
import sqlancer.clickhouse.ClickHouseType.DateTime64Type;
import sqlancer.clickhouse.ClickHouseType.Decimal;
import sqlancer.clickhouse.ClickHouseType.FixedString;
import sqlancer.clickhouse.ClickHouseType.Kind;
import sqlancer.clickhouse.ClickHouseType.LowCardinality;
import sqlancer.clickhouse.ClickHouseType.Nullable;
import sqlancer.clickhouse.ClickHouseType.Primitive;
import sqlancer.clickhouse.ClickHouseType.Unknown;

/**
 * Hand-written recursive-descent parser for ClickHouse type strings, v2 grammar.
 *
 * <p>
 * Recognises the v2 primitive kinds (every {@link Kind} name, case-sensitive ClickHouse spelling), the parameterised
 * primitives {@link FixedString}, {@link Decimal}/{@code Decimal32}/{@code Decimal64}/{@code Decimal128}/
 * {@code Decimal256}, {@link DateTime64Type}, and the wrappers {@link Array}, {@link Nullable}, {@link LowCardinality}.
 * Anything outside that surface is preserved verbatim as {@link Unknown} -- the parser never throws on unrecognised
 * input.
 * </p>
 *
 * <p>
 * Bracketed argument lists are scanned with a balanced-paren walker so nested wrappers (e.g.
 * {@code Array(Nullable(Decimal(9, 3)))}) parse correctly. Single-quoted strings inside arguments are skipped verbatim
 * (used by Enum and timezone-bearing DateTime forms, neither of which the parser materialises -- they fall through to
 * {@link Unknown}).
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
    // not match the v2 grammar -- never throws.
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
        // Wrappers first: Nullable / LowCardinality / Array each take exactly one type argument.
        String inner = stripSingleArgWrapper(s, "Nullable");
        if (inner != null) {
            ClickHouseType t = tryParseRecognised(inner.trim());
            return t != null ? new Nullable(t) : null;
        }
        inner = stripSingleArgWrapper(s, "LowCardinality");
        if (inner != null) {
            ClickHouseType t = tryParseRecognised(inner.trim());
            return t != null ? new LowCardinality(t) : null;
        }
        inner = stripSingleArgWrapper(s, "Array");
        if (inner != null) {
            ClickHouseType t = tryParseRecognised(inner.trim());
            return t != null ? new Array(t) : null;
        }
        // Parameterised primitives.
        ClickHouseType fs = tryParseFixedString(s);
        if (fs != null) {
            return fs;
        }
        ClickHouseType dec = tryParseDecimal(s);
        if (dec != null) {
            return dec;
        }
        ClickHouseType dt64 = tryParseDateTime64(s);
        if (dt64 != null) {
            return dt64;
        }
        // (Simple)AggregateFunction(func, T...). Reflected from system catalog after CREATE so the
        // re-read schema carries the proper state type rather than collapsing to Unknown (which
        // would make INSERT generation skip the column and the AggregateStateRoundtrip oracle ignore
        // it). Unit 3.2.
        ClickHouseType agg = tryParseAggregateFunction(s);
        if (agg != null) {
            return agg;
        }
        // Plain DateTime can carry an optional timezone arg: DateTime('Europe/Moscow'). For now we
        // collapse both forms onto the bare DateTime kind -- the timezone is a presentation detail
        // and the value domain is the same. If timezone-bearing forms appear we strip them; if any
        // other arg appears, fall through to Unknown.
        if (s.equals("DateTime")) {
            return new Primitive(Kind.DateTime);
        }
        if (s.startsWith("DateTime(") && s.endsWith(")")) {
            return new Primitive(Kind.DateTime);
        }
        // Bare kind name.
        Kind kind = PRIMITIVES.get(s);
        if (kind != null) {
            return new Primitive(kind);
        }
        return null;
    }

    // Strips `Name(...)` returning the contents between the outermost parens, or null if the input
    // does not match a single balanced `Name(...)` block. Validates that the closing paren is the
    // last character and that parens balance, so e.g. `Name(a, b)Suffix` returns null.
    private static String stripSingleArgWrapper(String s, String wrapperName) {
        String prefix = wrapperName + "(";
        if (!s.startsWith(prefix) || !s.endsWith(")")) {
            return null;
        }
        // Verify the outermost paren block spans exactly from prefix.length()-1 to length()-1.
        int depth = 0;
        boolean inString = false;
        for (int i = prefix.length() - 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (c == '\'') {
                    inString = false;
                }
                continue;
            }
            if (c == '\'') {
                inString = true;
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0 && i != s.length() - 1) {
                    return null;
                }
            }
        }
        return depth == 0 ? s.substring(prefix.length(), s.length() - 1) : null;
    }

    private static ClickHouseType tryParseFixedString(String s) {
        if (!s.startsWith("FixedString(") || !s.endsWith(")")) {
            return null;
        }
        String body = s.substring("FixedString(".length(), s.length() - 1).trim();
        try {
            int n = Integer.parseInt(body);
            if (n < 1 || n > 256) {
                return null;
            }
            return new FixedString(n);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // Decimal(P, S), Decimal32(S), Decimal64(S), Decimal128(S), Decimal256(S). The aliased forms
    // pin precision to a width-derived ceiling; we materialise them with the (P, S) representation
    // ClickHouse normalises to internally.
    private static ClickHouseType tryParseDecimal(String s) {
        if (!s.startsWith("Decimal") || !s.endsWith(")")) {
            return null;
        }
        int parenIdx = s.indexOf('(');
        if (parenIdx < 0) {
            return null;
        }
        String head = s.substring(0, parenIdx);
        String body = s.substring(parenIdx + 1, s.length() - 1).trim();
        try {
            if (head.equals("Decimal")) {
                String[] parts = body.split(",");
                if (parts.length != 2) {
                    return null;
                }
                int p = Integer.parseInt(parts[0].trim());
                int sc = Integer.parseInt(parts[1].trim());
                return new Decimal(p, sc);
            }
            int p;
            switch (head) {
            case "Decimal32":
                p = 9;
                break;
            case "Decimal64":
                p = 18;
                break;
            case "Decimal128":
                p = 38;
                break;
            case "Decimal256":
                p = 76;
                break;
            default:
                return null;
            }
            int sc = Integer.parseInt(body);
            return new Decimal(p, sc);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // SimpleAggregateFunction(func, T) -> SimpleAggregateFunctionType(func, parse(T)).
    // AggregateFunction(func[, T...]) -> AggregateFunctionType(func, [parse(T)...]). The first
    // top-level token is the function spec (kept verbatim -- it may itself be parametric, e.g.
    // `quantiles(0.5, 0.9)`); the remaining tokens are argument types. Returns null if any argument
    // type is unrecognised so the whole thing falls through to Unknown rather than a partial parse.
    private static ClickHouseType tryParseAggregateFunction(String s) {
        boolean simple = s.startsWith("SimpleAggregateFunction(");
        boolean full = !simple && s.startsWith("AggregateFunction(");
        if (!simple && !full || !s.endsWith(")")) {
            return null;
        }
        String wrapper = simple ? "SimpleAggregateFunction" : "AggregateFunction";
        String body = s.substring(wrapper.length() + 1, s.length() - 1);
        java.util.List<String> parts = splitTopLevel(body);
        if (parts.size() < 2) {
            return null;
        }
        String funcName = parts.get(0).trim();
        if (funcName.isEmpty()) {
            return null;
        }
        if (simple) {
            if (parts.size() != 2) {
                return null;
            }
            ClickHouseType inner = tryParseRecognised(parts.get(1).trim());
            return inner != null ? new ClickHouseType.SimpleAggregateFunctionType(funcName, inner) : null;
        }
        java.util.List<ClickHouseType> args = new java.util.ArrayList<>();
        for (int i = 1; i < parts.size(); i++) {
            ClickHouseType a = tryParseRecognised(parts.get(i).trim());
            if (a == null) {
                return null;
            }
            args.add(a);
        }
        return new ClickHouseType.AggregateFunctionType(funcName, args);
    }

    // Split on top-level (depth-0, outside single-quoted strings) commas. Used by the
    // (Simple)AggregateFunction parser so a comma inside a nested parametric function or type
    // argument does not split a token.
    private static java.util.List<String> splitTopLevel(String body) {
        java.util.List<String> out = new java.util.ArrayList<>();
        int depth = 0;
        boolean inString = false;
        int start = 0;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (inString) {
                if (c == '\'') {
                    inString = false;
                }
                continue;
            }
            if (c == '\'') {
                inString = true;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                out.add(body.substring(start, i));
                start = i + 1;
            }
        }
        out.add(body.substring(start));
        return out;
    }

    private static ClickHouseType tryParseDateTime64(String s) {
        if (!s.startsWith("DateTime64(") || !s.endsWith(")")) {
            return null;
        }
        String body = s.substring("DateTime64(".length(), s.length() - 1).trim();
        // Optional timezone arg: DateTime64(3, 'UTC'). Take the leading integer; drop the rest.
        int comma = body.indexOf(',');
        String precPart = comma < 0 ? body : body.substring(0, comma).trim();
        try {
            int prec = Integer.parseInt(precPart);
            if (prec < 0 || prec > 9) {
                return null;
            }
            return new DateTime64Type(prec);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
