package sqlancer.clickhouse.oracle.eet;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import sqlancer.Randomly;
import sqlancer.clickhouse.ClickHouseType;
import sqlancer.clickhouse.ClickHouseType.Kind;
import sqlancer.clickhouse.ClickHouseType.Primitive;
import sqlancer.clickhouse.ClickHouseTypeParser;

public final class ClickHouseEETIdentities {

    private ClickHouseEETIdentities() {
    }

    public record Identity(String name, String sqlTemplate, Predicate<ClickHouseType> safeFor) {
        public String applyTo(String xSql) {
            return String.format(sqlTemplate, xSql, xSql);
        }
    }

    static final List<Identity> CATALOG = List.of(
            new Identity("plus_zero", "plus(%s, 0)", t -> isIntegerKind(unwrapped(t))),
            new Identity("multiply_one", "multiply(%s, 1)", t -> isIntegerKind(unwrapped(t))),
            new Identity("concat_empty", "concat(%s, '')", t -> isPlainStringKind(t)),
            new Identity("coalesce_self", "coalesce(%s, %s)", t -> isFoldablePrimitive(unwrapped(t))),
            new Identity("if_true", "if(true, %s, %s)", t -> isFoldablePrimitive(unwrapped(t))),

            new Identity("reverse_reverse", "reverse(reverse(%s))", t -> isPlainStringKind(t)),
            new Identity("substring_whole", "substring(%s, 1)", t -> isPlainStringKind(t)),
            new Identity("concat_substring_split", "concat(substring(%s, 1, 1), substring(%s, 2))",
                    t -> isPlainStringKind(t)),
            new Identity("replace_regexp_nomatch", "replaceRegexpAll(%s, 'zzqq_never_matches_9181', 'Q')",
                    t -> isPlainStringKind(t)),

            new Identity("unhex_hex", "unhex(hex(%s))", t -> isPlainStringKind(t)),
            new Identity("base64_roundtrip", "base64Decode(base64Encode(%s))", t -> isPlainStringKind(t)),
            new Identity("try_base64_roundtrip", "tryBase64Decode(base64Encode(%s))", t -> isPlainStringKind(t)),

            new Identity("ipv4_num_string_roundtrip", "toIPv4(IPv4NumToString(toUInt32(%s)))",
                    t -> isIPv4Kind(t)),
            new Identity("ipv6_num_string_roundtrip", "toIPv6(IPv6NumToString(%s))", t -> isIPv6Kind(t)));

    public static Optional<Identity> pickIdentityForType(Randomly r, String typeName) {
        ClickHouseType term;
        try {
            term = ClickHouseTypeParser.parse(typeName);
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
        List<Identity> eligible = CATALOG.stream().filter(id -> id.safeFor().test(term)).toList();
        if (eligible.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(Randomly.fromList(eligible));
    }

    private static ClickHouseType unwrapped(ClickHouseType t) {
        return t.unwrap();
    }

    private static Kind kindOf(ClickHouseType inner) {
        if (inner instanceof Primitive p) {
            return p.kind();
        }
        return null;
    }

    private static boolean isFoldablePrimitive(ClickHouseType inner) {
        return inner instanceof Primitive;
    }

    private static boolean isPlainStringKind(ClickHouseType t) {
        return kindOf(unwrapped(t)) == Kind.String;
    }

    private static boolean isIPv4Kind(ClickHouseType t) {
        return kindOf(unwrapped(t)) == Kind.IPv4;
    }

    private static boolean isIPv6Kind(ClickHouseType t) {
        return kindOf(unwrapped(t)) == Kind.IPv6;
    }

    private static boolean isIntegerKind(ClickHouseType inner) {
        Kind k = kindOf(inner);
        if (k == null) {
            return false;
        }
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
            return true;
        default:
            return false;
        }
    }

}
