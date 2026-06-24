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

public class ClickHouseNaturalJoinOracle implements TestOracle<ClickHouseGlobalState> {

    private static final AtomicLong NATJ_COUNTER = new AtomicLong();
    private static final int MAX_SYNTAX_REJECTIONS = 3;
    private static final int DIFF_BOUND = 5;
    private static final int KEY_DOMAIN = 5;

    static final List<String> COLUMN_TYPES = List.of("Int32", "Nullable(Int32)", "String");

    private static final Map<JoinVariant, AtomicInteger> SYNTAX_REJECTIONS = buildRejectionCounters();

    private final ClickHouseGlobalState state;

    private final ExpectedErrors readErrors = new ExpectedErrors();

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

        ClickHouseErrors.addSessionSettingsErrors(readErrors);

        readErrors.add("UNKNOWN_TABLE");
        readErrors.add("Unknown table expression identifier");

        readErrors.add("(MEMORY_LIMIT_EXCEEDED)");
        readErrors.add("memory limit exceeded");

        readErrors.add("TIMEOUT_EXCEEDED");
        readErrors.add("Timeout exceeded");

        naturalSyntaxErrors.add("SYNTAX_ERROR");
        naturalSyntaxErrors.add("Syntax error");
        naturalSyntaxErrors.add("Expected one of");
        naturalSyntaxErrors.add("INVALID_JOIN_ON_EXPRESSION");
    }

    @Override
    public void check() throws SQLException {
        JoinSpec spec = generateSpec();
        if (isDisabled(spec.variant)) {

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

            checkStarColumnCount(spec, tA, tB);

            if (spec.sharedCount() == 0) {

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

                }
            }
        }
    }

    static JoinSpec generateSpec() {
        List<String> shared = new ArrayList<>();
        List<String> aPrivate = new ArrayList<>();
        List<String> bPrivate = new ArrayList<>();
        int mode = (int) Randomly.getNotCachedInteger(0, 4);
        if (mode == 3) {

            int k = 1 + (int) Randomly.getNotCachedInteger(0, 3);
            for (int i = 0; i < k; i++) {
                shared.add(randomColumnType());
            }
        } else {
            for (int i = 0; i < mode; i++) {
                shared.add(randomColumnType());
            }

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

        JoinVariant variant = shared.isEmpty() ? JoinVariant.INNER : Randomly.fromOptions(JoinVariant.values());
        return new JoinSpec(variant, shared, aPrivate, bPrivate);
    }

    private static String randomColumnType() {
        return Randomly.fromList(COLUMN_TYPES);
    }

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

        return variant != JoinVariant.FULL;
    }

    static String renderOnForm(JoinSpec spec, String tA, String tB) {

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

        return spec.sharedCount() + spec.aPrivateTypes.size() + spec.bPrivateTypes.size();
    }

    private static String renderInsert(String fqName, JoinSpec spec, boolean aSide) {
        List<String> types = spec.tableColumnTypes(aSide);
        boolean hasNullableShared = spec.sharedTypes.contains("Nullable(Int32)");
        int n = 10 + (int) Randomly.getNotCachedInteger(0, 29);
        List<String> rows = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            rows.add(renderRow(types, spec.sharedCount(), false));
        }
        if (hasNullableShared) {

            rows.add(renderRow(types, spec.sharedCount(), true));
        }

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

    private List<String> readRows(String query) throws SQLException {

        return ComparatorHelper.getResultSetFirstColumnAsString(query, readErrors, state);
    }

    private List<String> readNaturalForm(String query, JoinVariant variant) throws SQLException {
        try {
            return readRows(query);
        } catch (AssertionError e) {

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
