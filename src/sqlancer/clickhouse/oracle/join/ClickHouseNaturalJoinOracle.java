package sqlancer.clickhouse.oracle.join;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import sqlancer.ComparatorHelper;
import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.clickhouse.ClickHouseErrors;
import sqlancer.clickhouse.ClickHouseProvider.ClickHouseGlobalState;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;

/**
 * NATURAL JOIN rewrite-equivalence oracle for the 26.4 surface (PR #99840). ClickHouse implements
 * {@code NATURAL [INNER|LEFT|RIGHT|FULL] JOIN} as a <b>parser-level rewrite</b> to
 * {@code JOIN ... USING (&lt;common columns&gt;)} -- the implicit USING set is every column name the two sides share.
 * The two bug classes this oracle pins are therefore (a) a wrong implicit column set (the rewrite picks up too
 * few/too many shared names, or exposes shared columns twice), and (b) semantic divergence between the rewritten form
 * and the equivalent hand-written JOIN.
 *
 * <p>
 * Per iteration it builds two private plain-MergeTree tables with an oracle-<b>known</b> schema overlap -- k shared
 * column names ({@code sh0..}) of identical scalar types from {Int32, Nullable(Int32), String}, plus 0-2 per-table
 * private columns ({@code pa0..} / {@code pb0..}) -- seeds them with small-domain keys (0-4, deliberate duplicates on
 * both sides so row multiplication must be identical across forms) and, whenever a shared column is Nullable, NULLs
 * on BOTH sides (USING equality never matches NULL -- a priority seed shape), then compares as multisets over an
 * explicit projection rendered as {@code toString(tuple(...))}:
 * <ol>
 * <li>{@code SELECT <proj> FROM a NATURAL <KIND> JOIN b}</li>
 * <li>{@code SELECT <proj> FROM a <KIND> JOIN b USING (sh0, ...)}</li>
 * <li>the explicit-ON form {@code ... a <KIND> JOIN b ON a.sh0 = b.sh0 AND ...} whose projection reproduces USING
 * semantics by qualifying the shared columns with the side USING would expose: {@code a.shN} for INNER/LEFT,
 * {@code b.shN} for RIGHT. <b>FULL is deliberately excluded from form (3)</b>: for FULL JOIN the USING-exposed value
 * is a.shN when the row has a left match and b.shN otherwise, and no side-qualified projection reproduces that --
 * {@code ifNull(a.shN, b.shN)} would conflate a legitimately-NULL left value of a Nullable shared column with "no
 * left match". FULL therefore compares forms (1) vs (2) only, which still pins the rewrite itself.</li>
 * </ol>
 *
 * <p>
 * <b>Zero-shared-columns trap, pinned explicitly:</b> when the two tables share no column name, NATURAL JOIN
 * documented-silently degenerates into a <b>CROSS JOIN</b> (there is nothing to equate on). The k=0 arm therefore
 * compares form (1) against an explicit {@code CROSS JOIN} and skips the USING/ON forms (there is no USING list to
 * render). Only the plain {@code NATURAL JOIN} spelling is exercised in that arm.
 *
 * <p>
 * Column-set invariant: after form (1) succeeds, {@code SELECT * FROM a NATURAL <KIND> JOIN b LIMIT 0} is probed via
 * {@link java.sql.Statement} and the {@link ResultSetMetaData} column count must equal k + |a-private| + |b-private|
 * (shared columns exposed exactly once). A mismatch is the wrong-implicit-column-set bug class and raises an
 * AssertionError directly.
 *
 * <p>
 * Which NATURAL variants PR #99840 actually parses is probe-pending (plan Unit 5 defers it): each variant tolerates a
 * narrow syntax-rejection set on the NATURAL-form statement only and self-disables after
 * {@value #MAX_SYNTAX_REJECTIONS} rejections (static per-variant counters). On a pre-26.4 server every variant
 * self-disables the same way and the oracle becomes a no-op -- by construction, not by version sniffing.
 *
 * <p>
 * Construction style follows {@link sqlancer.clickhouse.oracle.mutate.ClickHouseMutationAnalyzerOracle} (Shape C):
 * AtomicLong-suffixed private tables, hand-built SQL, narrow error tolerance, DROP in {@code finally}. The private
 * tables receive no concurrent writers, so the multi-statement (rather than single-snapshot two-column) comparison is
 * race-free here -- the Shape-C exemption to the single-snapshot authoring rule.
 */
public class ClickHouseNaturalJoinOracle implements TestOracle<ClickHouseGlobalState> {

    private static final AtomicLong NATJ_COUNTER = new AtomicLong();
    private static final int MAX_SYNTAX_REJECTIONS = 3;
    private static final int DIFF_BOUND = 5;
    private static final int KEY_DOMAIN = 5; // keys drawn from 0..4 / 'a'..'e' -- duplicates by construction

    static final List<String> COLUMN_TYPES = List.of("Int32", "Nullable(Int32)", "String");

    // Per-variant syntax-rejection counters (probe-pending variant support, see class javadoc). Static so the
    // self-disable persists across oracle instances/threads for the whole run.
    private static final Map<JoinVariant, AtomicInteger> SYNTAX_REJECTIONS = buildRejectionCounters();

    private final ClickHouseGlobalState state;
    // Narrow tolerance (MutationAnalyzer precedent, no global expression list): every statement here is hand-built
    // static SQL over private tables, so resolution-shaped messages ("Missing columns", "Ambiguous column", ...)
    // can only mean a rewrite bug and must surface.
    private final ExpectedErrors readErrors = new ExpectedErrors();
    // Applied ONLY to the NATURAL-form statements (form (1) and the SELECT * probe): pre-#99840 servers reject the
    // syntax, and per-variant support is probe-pending. Kept off every other statement so a syntax error in the
    // hand-written USING/ON/CROSS forms (which are valid on all supported servers) still surfaces.
    private final ExpectedErrors naturalSyntaxErrors = new ExpectedErrors();

    enum JoinVariant {
        INNER("NATURAL JOIN", "JOIN"), LEFT("NATURAL LEFT JOIN", "LEFT JOIN"),
        RIGHT("NATURAL RIGHT JOIN", "RIGHT JOIN"), FULL("NATURAL FULL JOIN", "FULL JOIN");

        private final String naturalKeyword;
        private final String explicitKeyword;

        JoinVariant(String naturalKeyword, String explicitKeyword) {
            this.naturalKeyword = naturalKeyword;
            this.explicitKeyword = explicitKeyword;
        }

        String getNaturalKeyword() {
            return naturalKeyword;
        }

        String getExplicitKeyword() {
            return explicitKeyword;
        }
    }

    /**
     * The generated schema overlap: k shared column names/types (identical on both tables) plus per-table private
     * columns. Column names are positional: {@code sh0..sh(k-1)}, {@code pa0..}, {@code pb0..}.
     */
    static final class JoinSpec {
        final JoinVariant variant;
        final List<String> sharedTypes;
        final List<String> aPrivateTypes;
        final List<String> bPrivateTypes;

        JoinSpec(JoinVariant variant, List<String> sharedTypes, List<String> aPrivateTypes,
                List<String> bPrivateTypes) {
            this.variant = variant;
            this.sharedTypes = List.copyOf(sharedTypes);
            this.aPrivateTypes = List.copyOf(aPrivateTypes);
            this.bPrivateTypes = List.copyOf(bPrivateTypes);
        }

        int sharedCount() {
            return sharedTypes.size();
        }

        List<String> sharedNames() {
            return prefixedNames("sh", sharedTypes.size());
        }

        List<String> aPrivateNames() {
            return prefixedNames("pa", aPrivateTypes.size());
        }

        List<String> bPrivateNames() {
            return prefixedNames("pb", bPrivateTypes.size());
        }

        List<String> tableColumnNames(boolean aSide) {
            List<String> names = new ArrayList<>(sharedNames());
            names.addAll(aSide ? aPrivateNames() : bPrivateNames());
            return names;
        }

        List<String> tableColumnTypes(boolean aSide) {
            List<String> types = new ArrayList<>(sharedTypes);
            types.addAll(aSide ? aPrivateTypes : bPrivateTypes);
            return types;
        }

        private static List<String> prefixedNames(String prefix, int count) {
            List<String> names = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                names.add(prefix + i);
            }
            return names;
        }
    }

    private static Map<JoinVariant, AtomicInteger> buildRejectionCounters() {
        Map<JoinVariant, AtomicInteger> counters = new EnumMap<>(JoinVariant.class);
        for (JoinVariant v : JoinVariant.values()) {
            counters.put(v, new AtomicInteger());
        }
        return counters;
    }

    public ClickHouseNaturalJoinOracle(ClickHouseGlobalState state) {
        this.state = state;
        // Unknown session-setting names on older builds (the provider pins per-connection settings).
        ClickHouseErrors.addSessionSettingsErrors(readErrors);
        // Per-thread database drop/recreate race (same as the MV / PatchPart / MutationAnalyzer oracles).
        readErrors.add("UNKNOWN_TABLE");
        readErrors.add("Unknown table expression identifier");
        // Code 241 load-shedding under the squeezed dev-vm container cap (-m=6g): environment artifact.
        readErrors.add("(MEMORY_LIMIT_EXCEEDED)");
        readErrors.add("memory limit exceeded");
        // Benign load-shed timeouts on a saturated server: nothing here is deadlock-shaped, so tolerating the
        // timeout family on all statements is safe (unlike MutationAnalyzer's shape (b)).
        readErrors.add("TIMEOUT_EXCEEDED");
        readErrors.add("Timeout exceeded");

        // ClickHouse parse failures render as
        //   "Code: 62. DB::Exception: Syntax error: failed at position N ('...'): ... Expected one of: ..."
        // with exception name SYNTAX_ERROR. On a pre-26.4 server "NATURAL" parses as a table alias and the
        // then-ON/USING-less JOIN fails either at parse time ("Expected one of: ON, USING, ...") or -- on paths
        // where the parser accepts it and the analyzer rejects the missing join expression -- as
        // INVALID_JOIN_ON_EXPRESSION. Deliberately tight: nothing else is tolerated on the NATURAL form.
        naturalSyntaxErrors.add("SYNTAX_ERROR");
        naturalSyntaxErrors.add("Syntax error");
        naturalSyntaxErrors.add("Expected one of");
        naturalSyntaxErrors.add("INVALID_JOIN_ON_EXPRESSION");
    }

    @Override
    public void check() throws SQLException {
        JoinSpec spec = generateSpec();
        if (isDisabled(spec.variant)) {
            // The variant accumulated MAX_SYNTAX_REJECTIONS parse rejections: the server does not support it.
            throw new IgnoreMeException();
        }
        long id = NATJ_COUNTER.incrementAndGet();
        String db = state.getDatabaseName();
        String tA = db + ".natj_" + id + "_a";
        String tB = db + ".natj_" + id + "_b";

        try {
            for (String stmt : List.of(renderCreateTable(tA, spec, true), renderCreateTable(tB, spec, false),
                    renderInsert(tA, spec, true), renderInsert(tB, spec, false))) {
                logStmt(stmt);
                if (!new SQLQueryAdapter(stmt, readErrors, true).execute(state)) {
                    throw new IgnoreMeException();
                }
            }

            String naturalSql = renderNaturalForm(spec, tA, tB);
            List<String> naturalRows = readNaturalForm(naturalSql, spec.variant);

            // Form (1) parsed and ran => NATURAL is supported here; the column-set invariant applies.
            checkStarColumnCount(spec, tA, tB);

            if (spec.sharedCount() == 0) {
                // The documented silent degeneration: no common columns => CROSS JOIN.
                String crossSql = renderCrossForm(spec, tA, tB);
                compareMultisets(spec, naturalSql, naturalRows, crossSql, readRows(crossSql));
            } else {
                String usingSql = renderUsingForm(spec, tA, tB);
                List<String> usingRows = readRows(usingSql);
                compareMultisets(spec, naturalSql, naturalRows, usingSql, usingRows);
                if (onFormApplicable(spec.variant)) {
                    String onSql = renderOnForm(spec, tA, tB);
                    compareMultisets(spec, usingSql, usingRows, onSql, readRows(onSql));
                }
            }
        } finally {
            for (String t : List.of(tA, tB)) {
                try {
                    new SQLQueryAdapter("DROP TABLE IF EXISTS " + t, readErrors, true).execute(state);
                } catch (Exception | AssertionError ignored) {
                    // Best effort; AssertionError too, since SQLQueryAdapter.execute() throws AssertionError (not
                    // an exception) on an untolerated error and that must not clobber the real finding or abort
                    // cleanup of the sibling table. Orphans are reaped by the disk-cleanup script.
                }
            }
        }
    }

    // ---- schema/spec generation (static and DB-free for unit tests) ------------------------------------------

    static JoinSpec generateSpec() {
        List<String> shared = new ArrayList<>();
        List<String> aPrivate = new ArrayList<>();
        List<String> bPrivate = new ArrayList<>();
        int mode = (int) Randomly.getNotCachedInteger(0, 4); // 0..2 = that many shared cols; 3 = all-shared
        if (mode == 3) {
            // All columns shared: the implicit USING set is the entire column list, no private columns.
            int k = 1 + (int) Randomly.getNotCachedInteger(0, 3);
            for (int i = 0; i < k; i++) {
                shared.add(randomColumnType());
            }
        } else {
            for (int i = 0; i < mode; i++) {
                shared.add(randomColumnType());
            }
            // k=0 requires at least one private column per side, else the tables would have no columns at all.
            int minPrivate = mode == 0 ? 1 : 0;
            int aCount = minPrivate + (int) Randomly.getNotCachedInteger(0, 3 - minPrivate);
            int bCount = minPrivate + (int) Randomly.getNotCachedInteger(0, 3 - minPrivate);
            for (int i = 0; i < aCount; i++) {
                aPrivate.add(randomColumnType());
            }
            for (int i = 0; i < bCount; i++) {
                bPrivate.add(randomColumnType());
            }
        }
        // k=0 exercises only the plain NATURAL JOIN spelling (the CROSS degeneration is documented for it; the
        // LEFT/RIGHT/FULL spellings with an empty USING set are not a defined surface to compare against).
        JoinVariant variant = shared.isEmpty() ? JoinVariant.INNER : Randomly.fromOptions(JoinVariant.values());
        return new JoinSpec(variant, shared, aPrivate, bPrivate);
    }

    private static String randomColumnType() {
        return Randomly.fromList(COLUMN_TYPES);
    }

    // ---- SQL rendering (static and DB-free for unit tests) ---------------------------------------------------

    static String renderCreateTable(String fqName, JoinSpec spec, boolean aSide) {
        List<String> names = spec.tableColumnNames(aSide);
        List<String> types = spec.tableColumnTypes(aSide);
        List<String> defs = new ArrayList<>(names.size());
        for (int i = 0; i < names.size(); i++) {
            defs.add(names.get(i) + " " + types.get(i));
        }
        return "CREATE TABLE " + fqName + " (" + String.join(", ", defs) + ") ENGINE = MergeTree ORDER BY tuple()";
    }

    static String renderProjection(JoinSpec spec) {
        List<String> cols = new ArrayList<>(spec.sharedNames());
        cols.addAll(spec.aPrivateNames());
        cols.addAll(spec.bPrivateNames());
        // Single column: every shared column once + all private columns, fixed order, rendered through CH's tuple
        // text serializer. The result is a non-Nullable String, so the Java-side sort in compareMultisets can never
        // hit the ComparableTimSort-on-null trap.
        return "toString(tuple(" + String.join(", ", cols) + "))";
    }

    static String renderNaturalForm(JoinSpec spec, String tA, String tB) {
        return "SELECT " + renderProjection(spec) + " FROM " + tA + " " + spec.variant.getNaturalKeyword() + " " + tB;
    }

    static String renderUsingForm(JoinSpec spec, String tA, String tB) {
        return "SELECT " + renderProjection(spec) + " FROM " + tA + " " + spec.variant.getExplicitKeyword() + " " + tB
                + " USING (" + String.join(", ", spec.sharedNames()) + ")";
    }

    static boolean onFormApplicable(JoinVariant variant) {
        // FULL is excluded: USING exposes a.shN-or-b.shN depending on which side matched, which no side-qualified
        // projection reproduces soundly when shared columns are Nullable (see class javadoc).
        return variant != JoinVariant.FULL;
    }

    static String renderOnForm(JoinSpec spec, String tA, String tB) {
        // USING-semantics side for the shared columns: the kept side is 'a' for INNER/LEFT and 'b' for RIGHT.
        String sharedSide = spec.variant == JoinVariant.RIGHT ? "b." : "a.";
        List<String> cols = new ArrayList<>();
        for (String sh : spec.sharedNames()) {
            cols.add(sharedSide + sh);
        }
        for (String p : spec.aPrivateNames()) {
            cols.add("a." + p);
        }
        for (String p : spec.bPrivateNames()) {
            cols.add("b." + p);
        }
        List<String> conds = new ArrayList<>();
        for (String sh : spec.sharedNames()) {
            conds.add("a." + sh + " = b." + sh);
        }
        return "SELECT toString(tuple(" + String.join(", ", cols) + ")) FROM " + tA + " AS a "
                + spec.variant.getExplicitKeyword() + " " + tB + " AS b ON " + String.join(" AND ", conds);
    }

    static String renderCrossForm(JoinSpec spec, String tA, String tB) {
        return "SELECT " + renderProjection(spec) + " FROM " + tA + " CROSS JOIN " + tB;
    }

    static int expectedStarColumnCount(JoinSpec spec) {
        // NATURAL must expose each shared column exactly once, plus both sides' private columns.
        return spec.sharedCount() + spec.aPrivateTypes.size() + spec.bPrivateTypes.size();
    }

    // ---- seeding ----------------------------------------------------------------------------------------------

    private static String renderInsert(String fqName, JoinSpec spec, boolean aSide) {
        List<String> types = spec.tableColumnTypes(aSide);
        boolean hasNullableShared = spec.sharedTypes.contains("Nullable(Int32)");
        int n = 10 + (int) Randomly.getNotCachedInteger(0, 29);
        List<String> rows = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            rows.add(renderRow(types, spec.sharedCount(), false));
        }
        if (hasNullableShared) {
            // Priority seed shape: a row whose Nullable shared columns are NULL, on BOTH sides -- USING equality
            // (and the ON form's a.sh = b.sh) must never match it.
            rows.add(renderRow(types, spec.sharedCount(), true));
        }
        // Deliberate full-row duplicate: row multiplication must be identical across forms.
        rows.add(rows.get(0));
        return "INSERT INTO " + fqName + " VALUES " + String.join(", ", rows);
    }

    private static String renderRow(List<String> types, int sharedCount, boolean forceNullShared) {
        List<String> vals = new ArrayList<>(types.size());
        for (int i = 0; i < types.size(); i++) {
            vals.add(renderLiteral(types.get(i), forceNullShared && i < sharedCount));
        }
        return "(" + String.join(", ", vals) + ")";
    }

    private static String renderLiteral(String type, boolean forceNull) {
        if ("Nullable(Int32)".equals(type)) {
            if (forceNull || Randomly.getNotCachedInteger(0, 4) == 0) {
                return "NULL";
            }
            return String.valueOf(Randomly.getNotCachedInteger(0, KEY_DOMAIN));
        }
        if ("Int32".equals(type)) {
            return String.valueOf(Randomly.getNotCachedInteger(0, KEY_DOMAIN));
        }
        return "'" + (char) ('a' + Randomly.getNotCachedInteger(0, KEY_DOMAIN)) + "'";
    }

    // ---- execution & comparison ---------------------------------------------------------------------------------

    private List<String> readRows(String query) throws SQLException {
        // getResultSetFirstColumnAsString logs the query itself (logEachSelect) and converts tolerated errors to
        // IgnoreMeException.
        return ComparatorHelper.getResultSetFirstColumnAsString(query, readErrors, state);
    }

    private List<String> readNaturalForm(String query, JoinVariant variant) throws SQLException {
        try {
            return readRows(query);
        } catch (AssertionError e) {
            // getResultSetFirstColumnAsString wraps an untolerated SQLException into AssertionError(query, cause);
            // unwrap and check the narrow NATURAL-syntax set before letting it surface.
            if (matchesNaturalSyntaxRejection(e)) {
                registerSyntaxRejection(variant);
                throw new IgnoreMeException();
            }
            throw e;
        }
    }

    private void checkStarColumnCount(JoinSpec spec, String tA, String tB) throws SQLException {
        String probe = "SELECT * FROM " + tA + " " + spec.variant.getNaturalKeyword() + " " + tB + " LIMIT 0";
        logStmt(probe);
        int expected = expectedStarColumnCount(spec);
        try (Statement s = state.getConnection().createStatement(); ResultSet rs = s.executeQuery(probe)) {
            ResultSetMetaData md = rs.getMetaData();
            int actual = md.getColumnCount();
            if (actual != expected) {
                List<String> names = new ArrayList<>(actual);
                for (int i = 1; i <= actual; i++) {
                    names.add(md.getColumnName(i));
                }
                throw new AssertionError(String.format(
                        "NATURAL JOIN implicit column set is wrong (variant=%s, shared=%s, a-private=%s, "
                                + "b-private=%s): SELECT * exposed %d columns %s, expected %d "
                                + "(each shared column once + both sides' private columns). probe: %s",
                        spec.variant, spec.sharedNames(), spec.aPrivateNames(), spec.bPrivateNames(), actual, names,
                        expected, probe));
            }
        } catch (SQLException e) {
            // Form (1) already ran, so a parse rejection here is unexpected but not a wrong-result; classify both
            // the syntax set and the read tolerance as IgnoreMe, surface everything else.
            if (e.getMessage() != null
                    && (naturalSyntaxErrors.errorIsExpected(e.getMessage()) || readErrors.errorIsExpected(e.getMessage()))) {
                throw new IgnoreMeException();
            }
            throw e;
        }
    }

    private static void compareMultisets(JoinSpec spec, String firstSql, List<String> firstRows, String secondSql,
            List<String> secondRows) {
        List<String> sortedFirst = new ArrayList<>(firstRows);
        List<String> sortedSecond = new ArrayList<>(secondRows);
        // Tuple-rendered rows are plain Strings, never Java null (toString(tuple(...)) is non-Nullable), so the
        // sort cannot NPE.
        Collections.sort(sortedFirst);
        Collections.sort(sortedSecond);
        if (sortedFirst.equals(sortedSecond)) {
            return;
        }
        throw new AssertionError(String.format(
                "NATURAL JOIN rewrite mismatch (variant=%s, shared=%s): %d vs %d rows.%n  first:  %s%n  second: %s%n"
                        + "  only-in-first (max %d): %s%n  only-in-second (max %d): %s",
                spec.variant, spec.sharedNames(), firstRows.size(), secondRows.size(), firstSql, secondSql, DIFF_BOUND,
                boundedMultisetDiff(firstRows, secondRows), DIFF_BOUND, boundedMultisetDiff(secondRows, firstRows)));
    }

    private static List<String> boundedMultisetDiff(List<String> xs, List<String> ys) {
        Map<String, Integer> counts = new TreeMap<>();
        for (String x : xs) {
            counts.merge(x, 1, Integer::sum);
        }
        for (String y : ys) {
            counts.merge(y, -1, Integer::sum);
        }
        List<String> diff = new ArrayList<>();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() > 0) {
                diff.add(e.getKey() + " (x" + e.getValue() + ")");
                if (diff.size() >= DIFF_BOUND) {
                    break;
                }
            }
        }
        return diff;
    }

    // ---- variant self-disable -----------------------------------------------------------------------------------

    private static boolean isDisabled(JoinVariant variant) {
        return SYNTAX_REJECTIONS.get(variant).get() >= MAX_SYNTAX_REJECTIONS;
    }

    private static void registerSyntaxRejection(JoinVariant variant) {
        SYNTAX_REJECTIONS.get(variant).incrementAndGet();
    }

    private boolean matchesNaturalSyntaxRejection(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current.getMessage() != null && naturalSyntaxErrors.errorIsExpected(current.getMessage())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void logStmt(String stmt) {
        if (state.getOptions().logEachSelect()) {
            state.getLogger().writeCurrent(stmt);
            state.getState().logStatement(stmt);
        }
    }
}
