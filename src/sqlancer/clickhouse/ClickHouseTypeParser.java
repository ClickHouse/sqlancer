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

public final class ClickHouseTypeParser {

    private static final Map<String, Kind> PRIMITIVES = new HashMap<>();

    static {
        for (Kind k : Kind.values()) {
            PRIMITIVES.put(k.name(), k);
        }
    }

    private ClickHouseTypeParser() {
    }

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

        ClickHouseType agg = tryParseAggregateFunction(s);
        if (agg != null) {
            return agg;
        }

        if (s.equals("DateTime")) {
            return new Primitive(Kind.DateTime);
        }
        if (s.startsWith("DateTime(") && s.endsWith(")")) {
            return new Primitive(Kind.DateTime);
        }

        Kind kind = PRIMITIVES.get(s);
        if (kind != null) {
            return new Primitive(kind);
        }
        return null;
    }

    private static String stripSingleArgWrapper(String s, String wrapperName) {
        String prefix = wrapperName + "(";
        if (!s.startsWith(prefix) || !s.endsWith(")")) {
            return null;
        }

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
