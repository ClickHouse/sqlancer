package sqlancer.clickhouse.oracle.eet;

import java.math.BigInteger;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.clickhouse.data.ClickHouseDataType;

import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.clickhouse.ClickHouseErrors;
import sqlancer.clickhouse.ClickHouseProvider.ClickHouseGlobalState;
import sqlancer.clickhouse.ClickHouseSchema.ClickHouseColumn;
import sqlancer.clickhouse.ClickHouseSchema.ClickHouseTable;
import sqlancer.clickhouse.ClickHouseToStringVisitor;
import sqlancer.clickhouse.ClickHouseType;
import sqlancer.clickhouse.ClickHouseType.Primitive;
import sqlancer.clickhouse.ClickHouseTypeParser;
import sqlancer.clickhouse.ClickHouseVisitor;
import sqlancer.clickhouse.ast.ClickHouseAggregate;
import sqlancer.clickhouse.ast.ClickHouseColumnReference;
import sqlancer.clickhouse.ast.ClickHouseExpression;
import sqlancer.clickhouse.ast.ClickHouseSelect;
import sqlancer.clickhouse.ast.ClickHouseTableReference;
import sqlancer.clickhouse.gen.ClickHouseExpressionGenerator;
import sqlancer.common.oracle.CODDTestBase;
import sqlancer.common.oracle.TestOracle;

public class ClickHouseEETOracle extends CODDTestBase<ClickHouseGlobalState>
        implements TestOracle<ClickHouseGlobalState> {

    private static final String PHI_TOKEN = "/*__eet_phi_3c2a91d7__*/";
    private static final int MAX_INJECTED_EXPR_DEPTH = 4;
    private static final int MAX_BASE_PRED_DEPTH = 3;

    private static final String HAVING_SETTINGS_SUFFIX = " SETTINGS aggregate_functions_null_for_empty=1, enable_optimize_predicate_expression=0";

    enum Mode {
        WHERE_INJECT, HAVING_INJECT, EXPR_REWRITE, ALGEBRAIC_ID, MULTIIF_EQUIV,

        COMPOUND_INTERVAL, OVERLAY_EQUIV, OVERLAY_SPLICE, NATURAL_SORT_KEY
    }

    enum Polarity {
        TAUTOLOGY, CONTRADICTION
    }

    enum ExprShape {
        IF, MULTI_IF, CASE_WHEN
    }

    public ClickHouseEETOracle(ClickHouseGlobalState state) {
        super(state);
        ClickHouseErrors.addExpectedExpressionErrors(this.errors);
    }

    @Override
    public void check() throws Exception {
        List<ClickHouseTable> tables = state.getSchema().getRandomTableNonEmptyTables().getTables();
        if (tables.isEmpty()) {
            throw new IgnoreMeException();
        }
        ClickHouseTable table = Randomly.fromList(tables);
        List<ClickHouseColumn> readableColumns = new ArrayList<>(table.getColumns());
        if (readableColumns.isEmpty()) {
            throw new IgnoreMeException();
        }

        Mode mode = pickMode();
        Polarity polarity = Randomly.fromOptions(Polarity.values());
        switch (mode) {
        case WHERE_INJECT:
            checkWhereInject(table, readableColumns, polarity);
            break;
        case HAVING_INJECT:
            checkHavingInject(table, readableColumns, polarity);
            break;
        case EXPR_REWRITE:
            checkExprRewrite(table, readableColumns, polarity);
            break;
        case ALGEBRAIC_ID:

            checkAlgebraicIdentity(table, readableColumns);
            break;
        case MULTIIF_EQUIV:

            checkMultiIfNestedIfEquivalence(table, readableColumns);
            break;
        case COMPOUND_INTERVAL:

            checkCompoundInterval(table, readableColumns);
            break;
        case OVERLAY_EQUIV:
            checkOverlayEquiv(table, readableColumns);
            break;
        case OVERLAY_SPLICE:
            checkOverlaySplice(table, readableColumns);
            break;
        case NATURAL_SORT_KEY:
            checkNaturalSortKey();
            break;
        default:
            throw new AssertionError(mode);
        }
    }

    private Mode pickMode() {
        return Randomly.fromList(candidateModes(state.getClickHouseOptions().eet26xModes));
    }

    static List<Mode> candidateModes(boolean enable26xModes) {
        List<Mode> modes = new ArrayList<>(List.of(Mode.WHERE_INJECT, Mode.HAVING_INJECT, Mode.EXPR_REWRITE,
                Mode.ALGEBRAIC_ID, Mode.MULTIIF_EQUIV));
        if (enable26xModes) {
            modes.addAll(List.of(Mode.COMPOUND_INTERVAL, Mode.OVERLAY_EQUIV, Mode.OVERLAY_SPLICE,
                    Mode.NATURAL_SORT_KEY));
        }
        return modes;
    }

    private void checkWhereInject(ClickHouseTable table, List<ClickHouseColumn> columns, Polarity polarity)
            throws SQLException {
        List<ClickHouseColumnReference> colRefs = columns.stream().map(c -> c.asColumnReference(table.getName()))
                .collect(Collectors.toList());

        ClickHouseExpressionGenerator gen = new ClickHouseExpressionGenerator(state);
        gen.addColumns(colRefs);

        ClickHouseExpression baseExpr = gen.generateExpressionWithColumns(colRefs, MAX_BASE_PRED_DEPTH);
        String baseSql = "(" + ClickHouseToStringVisitor.asString(baseExpr) + ")";

        ClickHouseExpression injectExpr = gen.generateExpressionWithColumns(colRefs, MAX_INJECTED_EXPR_DEPTH);
        String injectSql = ClickHouseToStringVisitor.asString(injectExpr);
        String foldedFragment = polarity == Polarity.TAUTOLOGY ? tautologyFragment(injectSql)
                : contradictionFragment(injectSql);

        String tableQ = quote(table.getName());
        String fetchCols = colRefs.stream().map(c -> tableQ + "." + quote(c.getColumn().getName()))
                .collect(Collectors.joining(", "));

        String queryTemplate = "SELECT " + fetchCols + " FROM " + tableQ + " WHERE " + PHI_TOKEN;
        if (queryTemplate.split(Pattern.quote(PHI_TOKEN), -1).length != 2) {
            throw new IgnoreMeException();
        }

        String originalSql = queryTemplate.replace(PHI_TOKEN, baseSql);
        String transformedSql = queryTemplate.replace(PHI_TOKEN, "(" + baseSql + " AND " + foldedFragment + ")");

        this.auxiliaryQueryString = "-- EET WHERE-" + polarity.name() + " injected fragment: " + foldedFragment;
        this.originalQueryString = originalSql;
        this.foldedQueryString = transformedSql;

        if (state.getOptions().logEachSelect()) {
            state.getLogger().writeCurrent(originalSql);
            state.getLogger().writeCurrent(transformedSql);
        }

        List<String> originalRows = collectRows(originalSql);
        List<String> transformedRows = collectRows(transformedSql);

        if (polarity == Polarity.TAUTOLOGY) {
            if (!originalRows.equals(transformedRows)) {
                throw new AssertionError(String.format(
                        "EET[mode=WHERE-tautology] result mismatch:%n  injected: %s%n  Q:        %s%n  T:        %s%n  Q rows (%d): %s%n  T rows (%d): %s",
                        foldedFragment, originalSql, transformedSql, originalRows.size(), originalRows,
                        transformedRows.size(), transformedRows));
            }
        } else {
            if (!transformedRows.isEmpty()) {
                throw new AssertionError(String.format(
                        "EET[mode=WHERE-contradiction] non-empty result:%n  injected: %s%n  T:        %s%n  T rows (%d): %s",
                        foldedFragment, transformedSql, transformedRows.size(), transformedRows));
            }
        }
    }

    static String tautologyFragment(String eSql) {
        return "((((" + eSql + ") OR NOT (" + eSql + ")) OR (" + eSql + ") IS NULL))";
    }

    static String contradictionFragment(String eSql) {
        return "((((" + eSql + ") AND NOT (" + eSql + ")) AND (" + eSql + ") IS NOT NULL))";
    }

    private void checkHavingInject(ClickHouseTable table, List<ClickHouseColumn> columns, Polarity polarity)
            throws SQLException {
        List<ClickHouseColumnReference> colRefs = columns.stream().map(c -> c.asColumnReference(table.getName()))
                .collect(Collectors.toList());

        ClickHouseExpressionGenerator gen = new ClickHouseExpressionGenerator(state);
        gen.addColumns(colRefs);

        ClickHouseSelect select = new ClickHouseSelect();
        select.setFromClause(new ClickHouseTableReference(table, null));

        List<ClickHouseExpression> fetchColumns = IntStream.range(0, Randomly.smallNumber() + 1)
                .mapToObj(i -> gen.generateAggregateExpressionWithColumns(colRefs, 3)).collect(Collectors.toList());
        select.setFetchColumns(fetchColumns);
        select.setSelectType(ClickHouseSelect.SelectType.ALL);
        List<ClickHouseExpression> groupByColumns = IntStream.range(0, 1 + Randomly.smallNumber())
                .mapToObj(i -> gen.generateExpressionWithColumns(colRefs, 6)).collect(Collectors.toList());
        select.setGroupByClause(groupByColumns);
        select.setHavingClause(null);

        List<ClickHouseExpression> aggregateExprs = fetchColumns.stream().filter(p -> p instanceof ClickHouseAggregate)
                .collect(Collectors.toList());
        if (aggregateExprs.isEmpty()) {

            throw new IgnoreMeException();
        }

        ClickHouseExpression havingBase = gen.generateExpressionWithExpression(aggregateExprs, 6);
        String havingBaseSql = ClickHouseToStringVisitor.asString(havingBase);

        ClickHouseExpression injectExpr = gen.generateExpressionWithExpression(aggregateExprs, MAX_INJECTED_EXPR_DEPTH);
        String injectSql = ClickHouseToStringVisitor.asString(injectExpr);
        String foldedFragment = polarity == Polarity.TAUTOLOGY ? tautologyFragment(injectSql)
                : contradictionFragment(injectSql);

        String selectSql = ClickHouseVisitor.asString(select);
        String template = selectSql + " HAVING " + PHI_TOKEN + HAVING_SETTINGS_SUFFIX;
        if (template.split(Pattern.quote(PHI_TOKEN), -1).length != 2) {
            throw new IgnoreMeException();
        }

        String originalSql = template.replace(PHI_TOKEN, "(" + havingBaseSql + ")");
        String transformedSql = template.replace(PHI_TOKEN, "((" + havingBaseSql + ") AND " + foldedFragment + ")");

        this.auxiliaryQueryString = "-- EET HAVING-" + polarity.name() + " injected fragment: " + foldedFragment;
        this.originalQueryString = originalSql;
        this.foldedQueryString = transformedSql;

        if (state.getOptions().logEachSelect()) {
            state.getLogger().writeCurrent(originalSql);
            state.getLogger().writeCurrent(transformedSql);
        }

        List<String> originalRows = collectRows(originalSql);
        List<String> transformedRows = collectRows(transformedSql);

        if (polarity == Polarity.TAUTOLOGY) {
            if (!originalRows.equals(transformedRows)) {
                throw new AssertionError(String.format(
                        "EET[mode=HAVING-tautology] result mismatch:%n  injected: %s%n  Q:        %s%n  T:        %s%n  Q rows (%d): %s%n  T rows (%d): %s",
                        foldedFragment, originalSql, transformedSql, originalRows.size(), originalRows,
                        transformedRows.size(), transformedRows));
            }
        } else {
            if (!transformedRows.isEmpty()) {
                throw new AssertionError(String.format(
                        "EET[mode=HAVING-contradiction] non-empty result:%n  injected: %s%n  T:        %s%n  T rows (%d): %s",
                        foldedFragment, transformedSql, transformedRows.size(), transformedRows));
            }
        }
    }

    private void checkExprRewrite(ClickHouseTable table, List<ClickHouseColumn> columns, Polarity polarity)
            throws SQLException {
        List<ClickHouseColumnReference> colRefs = columns.stream().map(c -> c.asColumnReference(table.getName()))
                .collect(Collectors.toList());

        ClickHouseExpressionGenerator gen = new ClickHouseExpressionGenerator(state);
        gen.addColumns(colRefs);

        ClickHouseColumnReference picked = Randomly.fromList(colRefs);
        String tableQ = quote(table.getName());
        String xSql = tableQ + "." + quote(picked.getColumn().getName());

        String typeOfX = probeTypeName(table, xSql);
        if (typeOfX == null || !isFoldablePrimitiveTypeName(typeOfX)) {

            throw new IgnoreMeException();
        }

        ClickHouseExpression injectExpr = gen.generateExpressionWithColumns(colRefs, MAX_INJECTED_EXPR_DEPTH);
        String injectSql = ClickHouseToStringVisitor.asString(injectExpr);
        String taut = tautologyFragment(injectSql);
        String contra = contradictionFragment(injectSql);

        String junkSql = "defaultValueOfTypeName(" + sqlQuote(typeOfX) + ")";

        ExprShape shape = Randomly.fromOptions(ExprShape.values());
        String rewriteInner = buildExprRewrite(shape, polarity, xSql, junkSql, taut, contra);
        String transExpr = "cast((" + rewriteInner + "), " + sqlQuote(typeOfX) + ")";
        assertSingleSnapshotEquivalent(table, xSql, transExpr,
                "EXPR-" + shape.name() + "-" + polarity.name() + " typeOfX=" + typeOfX);
    }

    static String buildExprRewrite(ExprShape shape, Polarity polarity, String xSql, String junkSql, String taut,
            String contra) {
        switch (shape) {
        case IF:
            return polarity == Polarity.TAUTOLOGY ? "if(" + taut + ", " + xSql + ", " + xSql + ")"
                    : "if(" + contra + ", " + junkSql + ", " + xSql + ")";
        case MULTI_IF:

            return polarity == Polarity.TAUTOLOGY
                    ? "multiIf(" + taut + ", " + xSql + ", " + contra + ", " + junkSql + ", " + xSql + ")"
                    : "multiIf(" + contra + ", " + junkSql + ", " + taut + ", " + xSql + ", " + xSql + ")";
        case CASE_WHEN:
            return polarity == Polarity.TAUTOLOGY
                    ? "CASE WHEN " + taut + " THEN " + xSql + " WHEN " + contra + " THEN " + junkSql + " ELSE " + xSql
                            + " END"
                    : "CASE WHEN " + contra + " THEN " + junkSql + " WHEN " + taut + " THEN " + xSql + " ELSE " + xSql
                            + " END";
        default:
            throw new AssertionError(shape);
        }
    }

    String probeTypeName(ClickHouseTable table, String exprSql) throws SQLException {
        String query = "SELECT toTypeName(" + exprSql + ") AS t FROM " + quote(table.getName()) + " LIMIT 1";
        try (Statement s = state.getConnection().createStatement(); ResultSet rs = s.executeQuery(query)) {
            if (!rs.next()) {
                return null;
            }
            return rs.getString("t");
        } catch (SQLException ex) {
            throw maybeIgnore(ex);
        }
    }

    static boolean isFoldablePrimitiveTypeName(String typeName) {
        try {
            ClickHouseType inner = ClickHouseTypeParser.parse(typeName).unwrap();
            return inner instanceof Primitive;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private void checkAlgebraicIdentity(ClickHouseTable table, List<ClickHouseColumn> columns) throws SQLException {
        List<ClickHouseColumnReference> colRefs = columns.stream().map(c -> c.asColumnReference(table.getName()))
                .collect(Collectors.toList());
        ClickHouseColumnReference picked = Randomly.fromList(colRefs);
        String tableQ = quote(table.getName());
        String xSql = tableQ + "." + quote(picked.getColumn().getName());

        String typeOfX = probeTypeName(table, xSql);
        if (typeOfX == null) {
            throw new IgnoreMeException();
        }
        Optional<ClickHouseEETIdentities.Identity> picker = ClickHouseEETIdentities
                .pickIdentityForType(state.getRandomly(), typeOfX);
        if (picker.isEmpty()) {

            throw new IgnoreMeException();
        }
        ClickHouseEETIdentities.Identity identity = picker.get();

        String transExpr = "cast((" + identity.applyTo(xSql) + "), " + sqlQuote(typeOfX) + ")";
        assertSingleSnapshotEquivalent(table, xSql, transExpr, "ALG-" + identity.name() + " typeOfX=" + typeOfX);
    }

    private void checkMultiIfNestedIfEquivalence(ClickHouseTable table, List<ClickHouseColumn> columns)
            throws SQLException {
        List<ClickHouseColumnReference> colRefs = columns.stream().map(c -> c.asColumnReference(table.getName()))
                .collect(Collectors.toList());
        ClickHouseExpressionGenerator gen = new ClickHouseExpressionGenerator(state);
        gen.addColumns(colRefs);

        String[] forms = gen.renderMultiIfAndNestedIf(colRefs);
        if (forms == null) {

            throw new IgnoreMeException();
        }

        assertSingleSnapshotEquivalent(table, forms[0], forms[1], "MULTIIF-EQUIV");
    }

    private void checkCompoundInterval(ClickHouseTable table, List<ClickHouseColumn> columns) throws SQLException {
        List<ClickHouseColumnReference> colRefs = columns.stream().map(c -> c.asColumnReference(table.getName()))
                .collect(Collectors.toList());
        ClickHouseExpressionGenerator gen = new ClickHouseExpressionGenerator(state);
        gen.addColumns(colRefs);
        String[] forms = gen.renderCompoundAndDecomposedInterval(colRefs);
        if (forms == null) {

            throw new IgnoreMeException();
        }
        assertSingleSnapshotEquivalent(table, forms[0], forms[1], "COMPOUND-INTERVAL-" + forms[2]);
    }

    private void checkOverlayEquiv(ClickHouseTable table, List<ClickHouseColumn> columns) throws SQLException {
        String sSql = pickStringColumnSql(table, columns);
        if (sSql == null) {
            throw new IgnoreMeException();
        }
        String rLit = sqlQuote(randomShortAscii());
        int p = (int) Randomly.getNotCachedInteger(-2, 11);
        Integer l = Randomly.getBoolean() ? (int) Randomly.getNotCachedInteger(-1, 9) : null;
        assertSingleSnapshotEquivalent(table, overlayKeywordForm(sSql, rLit, p, l),
                overlayFunctionForm(sSql, rLit, p, l), "OVERLAY-EQUIV p=" + p + " l=" + l);
    }

    static String overlayKeywordForm(String sSql, String rLit, int p, Integer l) {
        return "OVERLAY(" + sSql + " PLACING " + rLit + " FROM " + p + (l == null ? "" : " FOR " + l) + ")";
    }

    static String overlayFunctionForm(String sSql, String rLit, int p, Integer l) {
        return "overlay(" + sSql + ", " + rLit + ", " + p + (l == null ? "" : ", " + l) + ")";
    }

    private void checkOverlaySplice(ClickHouseTable table, List<ClickHouseColumn> columns) throws SQLException {
        String sSql = pickStringColumnSql(table, columns);
        if (sSql == null) {
            throw new IgnoreMeException();
        }
        String sx = asciiCappedInput(sSql);
        String rLit = sqlQuote(randomShortAscii());
        int p = 1 + (int) Randomly.getNotCachedInteger(0, 4);
        int l = (int) Randomly.getNotCachedInteger(0, 5);
        assertSingleSnapshotEquivalent(table, guardedOverlayForm(sx, rLit, p, l), guardedSpliceForm(sx, rLit, p, l),
                "OVERLAY-SPLICE p=" + p + " l=" + l);
    }

    static String asciiCappedInput(String sSql) {
        return "substring(replaceRegexpAll(" + sSql + ", '[^ -~]', '?'), 1, 8)";
    }

    static String spliceGuard(String sxSql, int p, int l) {
        return "(length(" + sxSql + ") >= " + (p + l - 1) + ")";
    }

    static String guardedOverlayForm(String sxSql, String rLit, int p, int l) {
        return "if(" + spliceGuard(sxSql, p, l) + ", overlay(" + sxSql + ", " + rLit + ", " + p + ", " + l
                + "), 'skip')";
    }

    static String guardedSpliceForm(String sxSql, String rLit, int p, int l) {
        return "if(" + spliceGuard(sxSql, p, l) + ", concat(substring(" + sxSql + ", 1, " + (p - 1) + "), " + rLit
                + ", substring(" + sxSql + ", " + (p + l) + ")), 'skip')";
    }

    private void checkNaturalSortKey() throws SQLException {
        String[] pair = buildNaturalSortPair();
        int cmp = naturalOrderCompare(pair[0], pair[1]);
        String sqlSide = "(naturalSortKey(" + sqlQuote(pair[0]) + ") < naturalSortKey(" + sqlQuote(pair[1]) + "))";
        String expectedSide = cmp < 0 ? "1" : "0";
        assertConstantEquivalent(sqlSide, expectedSide,
                "NATURAL-SORT-KEY s1=" + sqlQuote(pair[0]) + " s2=" + sqlQuote(pair[1]));
    }

    static String[] buildNaturalSortPair() {
        int shape = (int) Randomly.getNotCachedInteger(0, 5);
        switch (shape) {
        case 0:
            return new String[] {
                    "v" + Randomly.getNotCachedInteger(0, 31) + "." + Randomly.getNotCachedInteger(0, 31),
                    "v" + Randomly.getNotCachedInteger(0, 31) + "." + Randomly.getNotCachedInteger(0, 31) };
        case 1:
            return new String[] { "file" + Randomly.getNotCachedInteger(0, 201),
                    "file" + Randomly.getNotCachedInteger(0, 201) };
        case 2:
            return new String[] { randomAsciiLetters(), randomAsciiLetters() };
        case 3:
            return new String[] { "", "v" + Randomly.getNotCachedInteger(0, 31) };
        default:
            String same = "v" + Randomly.getNotCachedInteger(0, 31) + "." + Randomly.getNotCachedInteger(0, 31);
            return new String[] { same, same };
        }
    }

    private static String randomAsciiLetters() {
        int len = (int) Randomly.getNotCachedInteger(0, 6);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            sb.append((char) ('a' + Randomly.getNotCachedInteger(0, 26)));
        }
        return sb.toString();
    }

    private static String randomShortAscii() {
        return randomAsciiLetters();
    }

    static int naturalOrderCompare(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            boolean da = isAsciiDigit(a.charAt(i));
            boolean db = isAsciiDigit(b.charAt(j));
            if (da && db) {
                int si = i;
                int sj = j;
                while (i < a.length() && isAsciiDigit(a.charAt(i))) {
                    i++;
                }
                while (j < b.length() && isAsciiDigit(b.charAt(j))) {
                    j++;
                }
                int c = new BigInteger(a.substring(si, i)).compareTo(new BigInteger(b.substring(sj, j)));
                if (c != 0) {
                    return c;
                }
                if (i - si != j - sj) {
                    return (i - si) - (j - sj);
                }
            } else {
                char ca = a.charAt(i);
                char cb = b.charAt(j);
                if (ca != cb) {
                    return ca - cb;
                }
                i++;
                j++;
            }
        }
        return (a.length() - i) - (b.length() - j);
    }

    private static boolean isAsciiDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private String pickStringColumnSql(ClickHouseTable table, List<ClickHouseColumn> columns) {
        List<ClickHouseColumn> stringCols = columns.stream()
                .filter(c -> c.getType().getType() == ClickHouseDataType.String).collect(Collectors.toList());
        if (stringCols.isEmpty()) {
            return null;
        }
        ClickHouseColumn picked = Randomly.fromList(stringCols);
        return quote(table.getName()) + "." + quote(picked.getName());
    }

    private void assertSingleSnapshotEquivalent(ClickHouseTable table, String origExpr, String transExpr, String label)
            throws SQLException {
        assertTwoColumnAgreement(origExpr, transExpr, " FROM " + quote(table.getName()), label);
    }

    private void assertConstantEquivalent(String origExpr, String transExpr, String label) throws SQLException {
        assertTwoColumnAgreement(origExpr, transExpr, "", label);
    }

    private void assertTwoColumnAgreement(String origExpr, String transExpr, String fromSuffix, String label)
            throws SQLException {
        String query = "SELECT (" + origExpr + ") AS a, (" + transExpr + ") AS b" + fromSuffix;
        this.auxiliaryQueryString = "-- EET " + label + " (single-snapshot two-column)";
        this.originalQueryString = "SELECT (" + origExpr + ")" + fromSuffix;
        this.foldedQueryString = "SELECT (" + transExpr + ")" + fromSuffix;
        if (state.getOptions().logEachSelect()) {
            state.getLogger().writeCurrent(query);
        }
        try (Statement s = state.getConnection().createStatement(); ResultSet rs = s.executeQuery(query)) {
            long rowIdx = 0;
            while (rs.next()) {
                String a = rs.getString(1);
                boolean aNull = rs.wasNull();
                String b = rs.getString(2);
                boolean bNull = rs.wasNull();
                if (aNull != bNull || !aNull && !a.equals(b)) {
                    throw new AssertionError(String.format(
                            "EET[mode=%s] value mismatch at row %d:%n  Q: %s%n  a (orig)=%s%n  b (trans)=%s", label,
                            rowIdx, query, aNull ? "NULL" : a, bNull ? "NULL" : b));
                }
                rowIdx++;
            }
        } catch (SQLException ex) {
            throw maybeIgnore(ex);
        }
    }

    List<String> collectRows(String query) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Statement s = state.getConnection().createStatement(); ResultSet rs = s.executeQuery(query)) {
            ResultSetMetaData md = rs.getMetaData();
            int colCount = md.getColumnCount();
            while (rs.next()) {
                StringBuilder row = new StringBuilder();
                for (int i = 1; i <= colCount; i++) {
                    if (i > 1) {
                        row.append('|');
                    }
                    String v = rs.getString(i);
                    row.append(rs.wasNull() ? "NULL" : v);
                }
                rows.add(row.toString());
            }
        } catch (SQLException ex) {
            throw maybeIgnore(ex);
        }
        Collections.sort(rows);
        return rows;
    }

    SQLException maybeIgnore(SQLException ex) {
        if (ex.getMessage() != null && errors.errorIsExpected(ex.getMessage())) {
            throw new IgnoreMeException();
        }
        return ex;
    }

    static String quote(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }

    static String sqlQuote(String s) {
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

}
