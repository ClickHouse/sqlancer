package sqlancer.clickhouse.oracle.eet;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import sqlancer.Randomly;
import sqlancer.clickhouse.ClickHouseType;
import sqlancer.clickhouse.ClickHouseType.Kind;
import sqlancer.clickhouse.ClickHouseType.Primitive;
import sqlancer.clickhouse.ClickHouseTypeParser;

/**
 * Typed algebraic-identity catalog for the EET oracle.
 *
 * <p>
 * Each entry pairs a rewrite template with a safe-type predicate: the rewrite is applied only when the predicate
 * accepts the column's runtime type (probed via {@code toTypeName}). v1 catalog:
 * </p>
 * <ul>
 * <li>{@code plus(x, 0)} -- safe for signed/unsigned integer kinds only (Float, Decimal, Bool excluded)</li>
 * <li>{@code multiply(x, 1)} -- same safe-type predicate as plus</li>
 * <li>{@code concat(x, '')} -- safe for {@code String} only (FixedString excluded)</li>
 * <li>{@code coalesce(x, x)} -- safe for any foldable primitive (Nullable / LowCardinality wrappers are
 * transparent)</li>
 * <li>{@code if(true, x, x)} -- safe for any foldable primitive</li>
 * </ul>
 *
 * <p>
 * Float types are excluded from {@code plus}/{@code multiply} despite {@code NaN + 0 = NaN} being technically safe:
 * {@code +0.0} versus {@code -0.0} formatting and Inf-arithmetic edge cases produce false-positive noise that outweighs
 * the marginal coverage gain. Decimal is excluded for v1 because intermediate widening rules can change scale;
 * re-evaluate after the first regression run.
 * </p>
 */
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
            // Unit 6.2 -- String roundtrip / search identities. All fold to x byte-for-byte on a
            // plain String (FixedString excluded: its trailing-NUL padding round-trips unevenly
            // through these functions and the cast-back, producing formatting-only false positives).
            // reverse is a byte reversal, so reverse(reverse(s)) == s for any byte sequence including
            // truncated UTF-8; substring/concat operate on bytes too, so a split-and-rejoin is the
            // identity; the regex pattern is chosen to never match real data, exercising the
            // re2/Hyperscan compile+scan path without depending on replacement semantics.
            new Identity("reverse_reverse", "reverse(reverse(%s))", t -> isPlainStringKind(t)),
            new Identity("substring_whole", "substring(%s, 1)", t -> isPlainStringKind(t)),
            new Identity("concat_substring_split", "concat(substring(%s, 1, 1), substring(%s, 2))",
                    t -> isPlainStringKind(t)),
            new Identity("replace_regexp_nomatch", "replaceRegexpAll(%s, 'zzqq_never_matches_9181', 'Q')",
                    t -> isPlainStringKind(t)));

    /**
     * Pick an identity whose safe-type predicate accepts the given runtime type name.
     *
     * @param r
     *            the PRNG used to pick uniformly among eligible identities
     * @param typeName
     *            the textual ClickHouse type, as returned by {@code toTypeName(expr)}
     *
     * @return empty if no entry in the catalog accepts the type (e.g., Array, Tuple, Map columns), otherwise a randomly
     *         selected eligible entry
     */
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

    // Plain String only (after stripping Nullable / LowCardinality wrappers). FixedString is
    // deliberately excluded: it is a distinct Kind, and its fixed-width NUL padding does not
    // survive a String-returning function + cast-back cleanly.
    private static boolean isPlainStringKind(ClickHouseType t) {
        return kindOf(unwrapped(t)) == Kind.String;
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
