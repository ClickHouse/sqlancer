package sqlancer.clickhouse.oracle.coddtest;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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
import sqlancer.clickhouse.ast.ClickHouseColumnReference;
import sqlancer.clickhouse.ast.ClickHouseExpression;
import sqlancer.clickhouse.ast.constant.ClickHouseCreateConstant;
import sqlancer.clickhouse.gen.ClickHouseExpressionGenerator;
import sqlancer.common.oracle.CODDTestBase;
import sqlancer.common.oracle.TestOracle;

/**
 * Constant Optimization Driven Database System Testing for ClickHouse, following Zhang and Rigger, SIGMOD 2025
 * (CODDTest: <a href="https://doi.org/10.1145/3709674">DOI 10.1145/3709674</a>).
 *
 * <p>
 * For a query Q with a sub-expression &phi;, the oracle builds an auxiliary query A that evaluates &phi; in isolation,
 * derives a constant (or per-row mapping) R<sub>&phi;</sub> from A's result, then builds a folded query F by
 * substituting R<sub>&phi;</sub> for &phi; in Q. Since constant folding and propagation are semantics-preserving
 * rewrites, Q and F must return identical result sets; any discrepancy is a logic bug in the DBMS.
 * </p>
 *
 * <p>
 * The paper distinguishes three flavors of &phi; depending on the set of referenced outer-context columns; this oracle
 * implements all three, selecting uniformly per check:
 * </p>
 * <ul>
 * <li><strong>Constant expression</strong> (paper Section 3.1, no column references):
 *
 * <pre>
 * aux:    SELECT (&phi;)                                 -&gt; literal V
 * Q:      SELECT * FROM t WHERE col op (&phi;)
 * F:      SELECT * FROM t WHERE col op V
 * </pre>
 *
 * </li>
 * <li><strong>Scalar non-correlated subquery</strong> (Section 3.1, &phi; is an aggregate subquery):
 *
 * <pre>
 * aux:    SELECT min/max(c) FROM t                  -&gt; literal V
 * Q:      SELECT * FROM t WHERE col op (SELECT min/max(c) FROM t)
 * F:      SELECT * FROM t WHERE col op V
 * </pre>
 *
 * </li>
 * <li><strong>Dependent expression</strong> (Section 3.2, &phi; references one outer column k):
 *
 * <pre>
 * aux:    SELECT DISTINCT k, (&phi;) FROM t              -&gt; mapping {k_i -&gt; r_i}
 * Q:      SELECT * FROM t WHERE col op (&phi;)
 * F:      SELECT * FROM t WHERE col op CASE WHEN k=k_1 THEN r_1 ... END
 * </pre>
 *
 * </li>
 * </ul>
 *
 * <p>
 * The outer query is a single-table {@code SELECT * FROM t WHERE pred}. The predicate template is one of: a single
 * comparison {@code <col> <op> <phi>}, or that comparison combined via AND/OR with a freely-generated boolean
 * expression -- the latter exercises constant folding through compound predicates, which is where the paper's bug
 * pattern often lives.
 * </p>
 *
 * <p>
 * Folding is limited to columns and result types the schema generator currently produces ({@code
 * Int32} and {@code String}); other types raise {@link IgnoreMeException}. NULL auxiliary results are likewise skipped
 * because NULL-propagation would change the predicate's three-valued result.
 * </p>
 */
public class ClickHouseCODDTestOracle extends CODDTestBase<ClickHouseGlobalState>
        implements TestOracle<ClickHouseGlobalState> {

    private static final String PHI_TOKEN = "/*__codd_phi_47ad13f4__*/";
    private static final int MAX_CASE_BRANCHES = 64;
    private static final int MAX_EXPR_DEPTH = 4;

    public ClickHouseCODDTestOracle(ClickHouseGlobalState state) {
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
        List<ClickHouseColumn> readableColumns = readableColumns(table);
        if (readableColumns.isEmpty()) {
            throw new IgnoreMeException();
        }

        // Pick a mode: paper Section 3.1 case 1, case 2, or Section 3.2.
        int mode = (int) Randomly.getNotCachedInteger(0, 3);
        Phi phi;
        switch (mode) {
        case 0:
            phi = buildConstantPhi();
            break;
        case 1:
            phi = buildScalarSubqueryPhi(table, readableColumns);
            break;
        default:
            phi = buildDependentPhi(table, readableColumns);
            break;
        }
        if (phi == null) {
            throw new IgnoreMeException();
        }

        runComparison(table, readableColumns, phi);
    }

    // ----- Mode 0: independent expression, no column references (Section 3.1 case 1) -----

    // Build a column-free random expression and constant-fold it against the server.
    //
    // generateExpressionWithColumns short-circuits to a single constant when the column list is
    // empty, so we seed generateExpressionWithExpression with a handful of typed constants instead
    // -- this lets it combine them into arbitrarily nested arithmetic, logical, comparison, and
    // function expressions while staying column-free by construction.
    private Phi buildConstantPhi() throws SQLException {
        ClickHouseExpressionGenerator gen = new ClickHouseExpressionGenerator(state);
        List<ClickHouseExpression> seed = List.of(ClickHouseCreateConstant.createInt32Constant(0L),
                ClickHouseCreateConstant.createInt32Constant(1L),
                ClickHouseCreateConstant.createInt32Constant(state.getRandomly().getInteger()),
                ClickHouseCreateConstant.createInt32Constant(state.getRandomly().getInteger()),
                ClickHouseCreateConstant.createStringConstant(state.getRandomly().getString()),
                ClickHouseCreateConstant.createStringConstant(state.getRandomly().getString()));
        ClickHouseExpression phi = gen.generateExpressionWithExpression(seed, MAX_EXPR_DEPTH);
        String phiSql = "(" + ClickHouseToStringVisitor.asString(phi) + ")";

        String aux = "SELECT toTypeName(" + phiSql + ") AS t, " + phiSql + " AS v";
        EvalResult eval = evaluateSingleRow(aux);
        if (eval == null || eval.valueText == null) {
            // NULL or unexpected server error -- skip the test attempt rather than risk a false
            // positive from NULL-propagation in the predicate.
            return null;
        }
        String literal = renderLiteral(eval.valueText, eval.typeName);
        if (literal == null) {
            return null;
        }
        return new Phi(phiSql, "(" + literal + ")", aux, /* expectedType */ eval.typeName);
    }

    // ----- Mode 1: scalar non-correlated subquery (Section 3.1 case 2) -----

    // The PR's original implementation, restated under the new framework: a non-correlated
    // aggregate subquery yields exactly one scalar, which we read and embed as a literal.
    private Phi buildScalarSubqueryPhi(ClickHouseTable table, List<ClickHouseColumn> columns) throws SQLException {
        ClickHouseColumn aggCol = Randomly.fromList(columns);
        if (!isFoldableColumnTerm(aggCol.getType().getTypeTerm())) {
            return null;
        }
        String aggFn = Randomly.fromOptions("min", "max");
        String tableQ = quote(table.getName());
        String subquery = "SELECT " + aggFn + "(" + quote(aggCol.getName()) + ") FROM " + tableQ;
        String phiSql = "(" + subquery + ")";

        // Evaluate via the subquery wrapper so its scalar result and type are captured the same way
        // as the other modes -- a single row with (typeName, value).
        String aux = "SELECT toTypeName(" + phiSql + ") AS t, " + phiSql + " AS v";
        EvalResult eval = evaluateSingleRow(aux);
        if (eval == null || eval.valueText == null) {
            return null;
        }
        String literal = renderLiteral(eval.valueText, eval.typeName);
        if (literal == null) {
            return null;
        }
        return new Phi(phiSql, "(" + literal + ")", aux, eval.typeName);
    }

    // ----- Mode 2: dependent expression, references one outer column k (Section 3.2) -----

    // Constant-fold an expression that references a single outer column into a CASE mapping. Each
    // distinct value of the key column k yields one WHEN branch; the folded predicate evaluates the
    // CASE per row, which by construction returns the same value as the original phi(k). The CASE
    // is wrapped in cast(..., 'expectedType') so that the folded predicate sees the exact same
    // operand type as the original -- without this, integer literals like 5 get narrowed to UInt8
    // during parsing, which can shift the result of compound predicates.
    private Phi buildDependentPhi(ClickHouseTable table, List<ClickHouseColumn> columns) throws SQLException {
        ClickHouseColumn keyCol = Randomly.fromList(columns);
        ClickHouseType keyTerm = keyCol.getType().getTypeTerm();
        if (!isFoldableColumnTerm(keyTerm)) {
            return null;
        }
        ClickHouseDataType keyType = keyCol.getType().getType();

        // Generate a random expression whose only outer-context dependency is the key column.
        ClickHouseColumnReference keyRef = keyCol.asColumnReference(table.getName());
        ClickHouseExpressionGenerator gen = new ClickHouseExpressionGenerator(state);
        gen.addColumns(Collections.singletonList(keyRef));
        ClickHouseExpression phi = gen.generateExpressionWithColumns(Collections.singletonList(keyRef), MAX_EXPR_DEPTH);
        String phiSql = "(" + ClickHouseToStringVisitor.asString(phi) + ")";

        String tableQ = quote(table.getName());
        String keyRefSql = tableQ + "." + quote(keyCol.getName());
        // DISTINCT prunes duplicate rows; the auxiliary fits into MAX_CASE_BRANCHES branches for any
        // table whose key cardinality is bounded. Tables with larger cardinality are skipped --
        // expanding the CASE without bound would generate huge predicates that swamp the server.
        String aux = "SELECT DISTINCT " + keyRefSql + " AS k, " + phiSql + " AS v, " + "toTypeName(" + phiSql
                + ") AS t FROM " + tableQ;

        Map<String, String> branches = new LinkedHashMap<>();
        boolean hasNullKey = false;
        String nullKeyLiteral = null;
        String expectedType = null;

        try (Statement s = state.getConnection().createStatement(); ResultSet rs = s.executeQuery(aux)) {
            while (rs.next()) {
                if (branches.size() >= MAX_CASE_BRANCHES) {
                    return null;
                }
                String keyText = rs.getString("k");
                boolean keyNull = rs.wasNull();
                String valText = rs.getString("v");
                boolean valNull = rs.wasNull();
                String typeText = rs.getString("t");

                if (expectedType == null) {
                    expectedType = typeText;
                } else if (!expectedType.equals(typeText)) {
                    // Per-row result type changed (e.g., Nullable vs non-Nullable across rows);
                    // CASE would coerce to the supertype but the original phi sees the per-row
                    // type. Skip to stay sound.
                    return null;
                }

                String valLiteral = valNull ? "NULL" : renderLiteral(valText, typeText);
                if (valLiteral == null) {
                    return null;
                }

                if (keyNull) {
                    if (hasNullKey && !Objects.equals(nullKeyLiteral, valLiteral)) {
                        // Two NULL-key rows with different phi values -- phi is non-deterministic.
                        return null;
                    }
                    hasNullKey = true;
                    nullKeyLiteral = valLiteral;
                    continue;
                }
                String keyLiteral = renderLiteral(keyText, clickHouseTypeName(keyType));
                if (keyLiteral == null) {
                    return null;
                }
                String existing = branches.get(keyLiteral);
                if (existing != null && !Objects.equals(existing, valLiteral)) {
                    // Two rows with the same key but different phi values -- phi is not a function
                    // of k alone. Most likely a non-deterministic function leaked through (rand,
                    // now, etc.); skip rather than emit a misleading CASE.
                    return null;
                }
                branches.put(keyLiteral, valLiteral);
            }
        } catch (SQLException ex) {
            throw maybeIgnore(ex);
        }

        if (branches.isEmpty() && !hasNullKey) {
            return null;
        }

        StringBuilder caseSb = new StringBuilder();
        caseSb.append("CASE");
        if (hasNullKey) {
            caseSb.append(" WHEN ").append(keyRefSql).append(" IS NULL THEN ").append(nullKeyLiteral);
        }
        for (Map.Entry<String, String> e : branches.entrySet()) {
            caseSb.append(" WHEN ").append(keyRefSql).append(" = ").append(e.getKey()).append(" THEN ")
                    .append(e.getValue());
        }
        // ELSE NULL is the implicit default; spelling it out documents intent and matches the
        // paper's CASE construction.
        caseSb.append(" ELSE NULL END");
        // Use the function form `cast(expr, 'TypeName')` (a string second argument is required);
        // the keyword form `CAST(expr AS Type)` is a parse-level construct that does not accept a
        // string literal for the type, even though ClickHouse uses the same internal cast routine.
        String foldedSql = "cast((" + caseSb.toString() + "), " + sqlQuote(expectedType) + ")";

        return new Phi(phiSql, foldedSql, aux, expectedType);
    }

    // ----- Outer query construction and comparison -----

    // Build SELECT * FROM t WHERE pred where pred embeds the phi placeholder, then substitute phi
    // with the original expression and the folded expression respectively and compare result sets.
    private void runComparison(ClickHouseTable table, List<ClickHouseColumn> columns, Phi phi) throws SQLException {
        String tableQ = quote(table.getName());
        String fetchCols = columns.stream().map(c -> tableQ + "." + quote(c.getName()))
                .collect(Collectors.joining(", "));

        // Pick a filter column compatible with phi's expected result type. Falling back to any
        // column lets the test attempt proceed; ClickHouse's coercion logic will be exercised when
        // types don't match exactly.
        ClickHouseColumn filterCol = pickFilterColumn(columns, phi.expectedType);
        String filterColQ = tableQ + "." + quote(filterCol.getName());
        String op = Randomly.fromOptions("=", "<", ">", "<=", ">=", "!=");

        // The most common shape is the bare comparison; the compound shapes (AND/OR with a freshly
        // generated boolean) are picked occasionally so &phi; passes through richer predicate
        // structure where constant folding has historically been buggier.
        String predicateTemplate;
        int shape = (int) Randomly.getNotCachedInteger(0, 4);
        if (shape == 0) {
            // 1/4: bare comparison
            predicateTemplate = filterColQ + " " + op + " " + PHI_TOKEN;
        } else if (shape == 1) {
            // 1/4: phi inside a top-level AND with a column-only predicate
            predicateTemplate = "(" + filterColQ + " " + op + " " + PHI_TOKEN + ") AND ("
                    + randomColumnPredicate(table, columns) + ")";
        } else if (shape == 2) {
            // 1/4: phi inside a top-level OR with a column-only predicate
            predicateTemplate = "(" + filterColQ + " " + op + " " + PHI_TOKEN + ") OR ("
                    + randomColumnPredicate(table, columns) + ")";
        } else {
            // 1/4: phi negated -- exercises NOT folding in addition to the comparison
            predicateTemplate = "NOT (" + filterColQ + " " + op + " " + PHI_TOKEN + ")";
        }

        String queryTemplate = "SELECT " + fetchCols + " FROM " + tableQ + " WHERE " + predicateTemplate;
        // Sanity: the placeholder must appear exactly once -- if any future change passes a phi SQL
        // that itself contains the token, sortRowsForComparison would silently miss the swap.
        if (queryTemplate.split(Pattern.quote(PHI_TOKEN), -1).length != 2) {
            throw new IgnoreMeException();
        }

        String originalSql = queryTemplate.replace(PHI_TOKEN, phi.originalSql);
        String foldedSql = queryTemplate.replace(PHI_TOKEN, phi.foldedSql);

        // Surface the queries for the failure log and the SQLancer state dump.
        this.auxiliaryQueryString = phi.auxiliarySql;
        this.originalQueryString = originalSql;
        this.foldedQueryString = foldedSql;

        List<String> originalRows = collectRows(originalSql);
        List<String> foldedRows = collectRows(foldedSql);

        if (!originalRows.equals(foldedRows)) {
            throw new AssertionError(String.format(
                    "CODDTest result mismatch:%n  aux:    %s%n  Q:      %s%n  folded: %s%n  Q rows (%d): %s%n  F rows (%d): %s",
                    phi.auxiliarySql, originalSql, foldedSql, originalRows.size(), originalRows, foldedRows.size(),
                    foldedRows));
        }
    }

    // Pick a column whose type matches the expected phi type if possible; otherwise any column.
    private ClickHouseColumn pickFilterColumn(List<ClickHouseColumn> columns, String expectedType) {
        if (expectedType == null) {
            return Randomly.fromList(columns);
        }
        ClickHouseDataType target = parseType(expectedType);
        if (target == null) {
            return Randomly.fromList(columns);
        }
        List<ClickHouseColumn> matching = columns.stream().filter(c -> c.getType().getType() == target)
                .collect(Collectors.toList());
        return matching.isEmpty() ? Randomly.fromList(columns) : Randomly.fromList(matching);
    }

    // Generate a fresh boolean predicate from the columns of {@code table} -- used as the second
    // operand of compound shapes. The expression generator returns arbitrary expressions, which
    // ClickHouse interprets as predicates by truth-testing the numeric result.
    private String randomColumnPredicate(ClickHouseTable table, List<ClickHouseColumn> columns) {
        List<ClickHouseColumnReference> colRefs = columns.stream().map(c -> c.asColumnReference(table.getName()))
                .collect(Collectors.toList());
        ClickHouseExpressionGenerator gen = new ClickHouseExpressionGenerator(state);
        gen.addColumns(colRefs);
        ClickHouseExpression expr = gen.generateExpressionWithColumns(colRefs, 3);
        return ClickHouseToStringVisitor.asString(expr);
    }

    // ----- Result evaluation and rendering -----

    private static final class EvalResult {
        final String typeName;
        final String valueText;

        EvalResult(String typeName, String valueText) {
            this.typeName = typeName;
            this.valueText = valueText;
        }
    }

    // Run a 1-row, 2-column query of shape SELECT typeName, value and return the cells.
    private EvalResult evaluateSingleRow(String query) throws SQLException {
        try (Statement s = state.getConnection().createStatement(); ResultSet rs = s.executeQuery(query)) {
            if (!rs.next()) {
                return null;
            }
            String type = rs.getString("t");
            String value = rs.getString("v");
            if (rs.wasNull()) {
                return new EvalResult(type, null);
            }
            return new EvalResult(type, value);
        } catch (SQLException ex) {
            throw maybeIgnore(ex);
        }
    }

    // Render a value retrieved as text from JDBC back into a SQL literal suitable for direct
    // embedding. Returning null signals that the type isn't safely foldable in the current
    // implementation -- the caller should skip the test attempt. Floats are excluded because the
    // paper flags float folding as a source of false alarms (Section 4.1).
    static String renderLiteral(String value, String typeName) {
        if (value == null) {
            return "NULL";
        }
        ClickHouseType inner = ClickHouseTypeParser.parse(typeName).unwrap();
        if (!(inner instanceof Primitive p)) {
            return null;
        }
        switch (p.kind()) {
        case Int128:
        case Int256:
        case UInt128:
        case UInt256:
            // Wide integers: a bare decimal literal exceeding (U)Int64 range is typed by ClickHouse
            // as Float64, which silently loses precision (e.g. a UInt256 max folds to a Float64 that
            // no longer equals any stored row -> spurious CODDTest mismatch). Wrap in a typed cast so
            // the folded literal carries the exact wide-integer value. The narrower ints below are
            // safe as bare literals (CH types them as the smallest fitting (U)Int* type).
            return "CAST('" + value + "' AS " + p.kind().name() + ")";
        case Int8:
        case Int16:
        case Int32:
        case Int64:
        case UInt8:
        case UInt16:
        case UInt32:
        case UInt64:
            // JDBC's getString produces canonical decimal text. Trust it.
            return value;
        case Bool:
            // ClickHouse renders Bool as "true"/"false" or "1"/"0" depending on driver/version.
            // Normalize to numeric so the literal parses uniformly.
            return ("true".equalsIgnoreCase(value) || "1".equals(value)) ? "1" : "0";
        case String:
            return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'";
        default:
            return null;
        }
    }

    // A column term is foldable under the v1 CODDTest filter if its values can be round-tripped
    // through renderLiteral -- integer/Bool/String primitives, optionally wrapped in Nullable or
    // LowCardinality.
    static boolean isFoldableColumnTerm(ClickHouseType term) {
        if (!term.supportsLiteralEmission()) {
            return false;
        }
        ClickHouseType inner = term.unwrap();
        if (!(inner instanceof Primitive p)) {
            return false;
        }
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
        case Bool:
        case String:
            return true;
        default:
            return false;
        }
    }

    // Parse the ClickHouse `toTypeName` text into the v1 ADT and return its root flat enum.
    private static ClickHouseDataType parseType(String typeName) {
        ClickHouseType inner = ClickHouseTypeParser.parse(typeName).unwrap();
        if (inner instanceof Primitive p) {
            return p.kind().toClickHouseDataType();
        }
        return null;
    }

    // Convert the schema's ClickHouseDataType back to the textual form `toTypeName` uses.
    private static String clickHouseTypeName(ClickHouseDataType type) {
        return type.name();
    }

    // ----- Row collection -----

    // Execute a query and return its rows as a sorted list of pipe-delimited strings. Sorting on
    // the Java side avoids needing an SQL ORDER BY (which would itself be subject to constant
    // folding inside the planner) and keeps the comparison deterministic.
    private List<String> collectRows(String query) throws SQLException {
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

    // ----- Plumbing -----

    private SQLException maybeIgnore(SQLException ex) {
        if (ex.getMessage() != null && errors.errorIsExpected(ex.getMessage())) {
            // IgnoreMeException is a RuntimeException; throwing it from a context that requires
            // SQLException would compile fail. Callers always rethrow whatever this returns.
            throw new IgnoreMeException();
        }
        return ex;
    }

    // Filter the table's columns down to those safe to read in our outer query. ALIAS/MATERIALIZED
    // columns are kept (they're readable), but they can throw at evaluation time -- the expected
    // errors filter catches those.
    private static List<ClickHouseColumn> readableColumns(ClickHouseTable table) {
        return new ArrayList<>(table.getColumns());
    }

    private static String quote(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }

    // Single-quote a string literal for embedding in SQL, with backslash and quote escaping.
    private static String sqlQuote(String s) {
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    // ----- Phi: a sub-expression and its constant-folded form -----

    private static final class Phi {
        final String originalSql;
        final String foldedSql;
        final String auxiliarySql;
        final String expectedType;

        Phi(String originalSql, String foldedSql, String auxiliarySql, String expectedType) {
            this.originalSql = originalSql;
            this.foldedSql = foldedSql;
            this.auxiliarySql = auxiliarySql;
            this.expectedType = expectedType;
        }
    }
}
