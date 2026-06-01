package sqlancer.clickhouse.oracle.eet;

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

/**
 * Equivalent Expression Transformation (EET) oracle for ClickHouse, the companion approach to CODDTest from Zhang and
 * Rigger, SIGMOD 2025 (CODDTest: <a href="https://doi.org/10.1145/3709674">DOI 10.1145/3709674</a>).
 *
 * <p>
 * Where CODDTest folds a sub-expression to its precomputed value, EET goes the opposite direction: it <em>injects</em>
 * an expression that should fold to a fixed value (a tautology, a contradiction, or an algebraic identity) and asserts
 * the rewrite is semantics-preserving. Any divergence is a logic bug in the rewrite pipeline.
 * </p>
 *
 * <p>
 * Each {@code check()} picks one mode uniformly:
 * </p>
 * <ul>
 * <li><strong>WHERE injection.</strong> Generate base predicate {@code predQ} and a random expression {@code e}; build
 * the 3VL tautology {@code (((e) OR NOT (e)) OR (e) IS NULL)} and the contradiction
 * {@code (((e) AND NOT (e)) AND (e) IS NOT NULL)}. Assert
 * {@code rows(SELECT * FROM t WHERE predQ AND taut) == rows(SELECT * FROM t WHERE predQ)} and
 * {@code rows(SELECT * FROM t WHERE predQ AND contra)} is empty.</li>
 * <li><strong>HAVING injection.</strong> Same shapes injected into an aggregated query's HAVING clause. (Unit 2.)</li>
 * <li><strong>Expression-position rewrite.</strong> {@code if(taut, x, x)} / {@code multiIf} /
 * {@code CASE WHEN ... END} substitution on a SELECT-list column. (Unit 3.)</li>
 * <li><strong>Algebraic identity.</strong> Type-safe substitution like {@code x + 0}, {@code concat(x, '')}, etc. (Unit
 * 4.)</li>
 * </ul>
 *
 * <p>
 * Tautology/contradiction parenthesization is deliberately binding-tight: ClickHouse's parser binds {@code OR} looser
 * than {@code NOT} and tighter than {@code AND}, so an unparenthesized injection inside {@code pred AND ...} would
 * parse the wrong way. Every reference to {@code e} in the injected fragment is wrapped in its own parentheses.
 * </p>
 *
 * <p>
 * v1 reuses {@link CODDTestBase} for failure-attribution fields ({@code originalQueryString},
 * {@code foldedQueryString}, {@code auxiliaryQueryString}); the naming is a deliberate trade-off documented in the plan
 * rather than introducing a sibling base class for one extra oracle.
 * </p>
 */
public class ClickHouseEETOracle extends CODDTestBase<ClickHouseGlobalState>
        implements TestOracle<ClickHouseGlobalState> {

    private static final String PHI_TOKEN = "/*__eet_phi_3c2a91d7__*/";
    private static final int MAX_INJECTED_EXPR_DEPTH = 4;
    private static final int MAX_BASE_PRED_DEPTH = 3;

    // TLPHaving's hardcoded dodge for ClickHouse#12264; required on both sides of any HAVING-mode
    // comparison or the bug surfaces as a false-positive EET finding.
    private static final String HAVING_SETTINGS_SUFFIX = " SETTINGS aggregate_functions_null_for_empty=1, enable_optimize_predicate_expression=0";

    enum Mode {
        WHERE_INJECT, HAVING_INJECT, EXPR_REWRITE, ALGEBRAIC_ID, MULTIIF_EQUIV
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
            // Polarity is irrelevant for the algebraic-identity mode (every catalog entry is
            // unconditionally x-preserving), so we ignore the picked polarity here.
            checkAlgebraicIdentity(table, readableColumns);
            break;
        case MULTIIF_EQUIV:
            // Polarity is irrelevant: the two forms are structurally equivalent regardless.
            checkMultiIfNestedIfEquivalence(table, readableColumns);
            break;
        default:
            throw new AssertionError(mode);
        }
    }

    // Uniform mode picker -- mirrors CODDTest's three-mode picker at ClickHouseCODDTestOracle.check().
    private Mode pickMode() {
        return Randomly.fromOptions(Mode.values());
    }

    // ----- Mode: WHERE injection -----

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

        // Template carries a single sentinel; substitute the original predicate vs the predicate
        // conjoined with the injection. Asserting the token appears exactly once protects against
        // accidental embedding of the sentinel by a future generator change.
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

    // 3VL tautology with binding-tight parenthesization. Each reference to e is wrapped in its own
    // parens so the fragment composes safely inside any larger AND/OR/NOT expression.
    static String tautologyFragment(String eSql) {
        return "((((" + eSql + ") OR NOT (" + eSql + ")) OR (" + eSql + ") IS NULL))";
    }

    // 3VL contradiction with the same parenthesization discipline.
    static String contradictionFragment(String eSql) {
        return "((((" + eSql + ") AND NOT (" + eSql + ")) AND (" + eSql + ") IS NOT NULL))";
    }

    // ----- Mode: HAVING injection -----

    // Build a GROUP-BY aggregated SELECT via the AST (matching TLPHaving), stringify it with HAVING
    // null, then append a placeholder HAVING and the mandatory TLPHaving SETTINGS suffix. The
    // tautology/contradiction is injected inside the HAVING clause where the optimizer's
    // predicate-folding logic runs over aggregate expressions -- a different code path from WHERE
    // folding and a documented bug-class home in CODDTest's paper.
    private void checkHavingInject(ClickHouseTable table, List<ClickHouseColumn> columns, Polarity polarity)
            throws SQLException {
        List<ClickHouseColumnReference> colRefs = columns.stream().map(c -> c.asColumnReference(table.getName()))
                .collect(Collectors.toList());

        ClickHouseExpressionGenerator gen = new ClickHouseExpressionGenerator(state);
        gen.addColumns(colRefs);

        ClickHouseSelect select = new ClickHouseSelect();
        select.setFromClause(new ClickHouseTableReference(table, null));
        // Build a mix of aggregate fetch columns and group-by keys, matching TLPHaving's shape.
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
            // Without aggregates the HAVING clause can't reference a function-of-group result; the
            // generator's column-only fallback would yield "not under aggregate function and not in
            // GROUP BY" errors that aren't EET findings.
            throw new IgnoreMeException();
        }

        ClickHouseExpression havingBase = gen.generateExpressionWithExpression(aggregateExprs, 6);
        String havingBaseSql = ClickHouseToStringVisitor.asString(havingBase);

        ClickHouseExpression injectExpr = gen.generateExpressionWithExpression(aggregateExprs, MAX_INJECTED_EXPR_DEPTH);
        String injectSql = ClickHouseToStringVisitor.asString(injectExpr);
        String foldedFragment = polarity == Polarity.TAUTOLOGY ? tautologyFragment(injectSql)
                : contradictionFragment(injectSql);

        // Use ClickHouseVisitor.asString (not ClickHouseToStringVisitor.asString) to render the
        // outer SELECT without enclosing parens. ToStringVisitor.asString dispatches via the
        // generic visit path that treats the SELECT as an inner subquery and wraps it in `(...)`,
        // which produces `(SELECT ... FROM t GROUP BY ...) HAVING ...` -- a SYNTAX_ERROR.
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

    // ----- Mode: expression-position rewriting (if / multiIf / CASE) -----

    // Pick a column-reference x, probe its runtime type, wrap a SELECT-list expression with one of
    // three boolean-fold shapes that should reduce to x. The transformed column is cast back to
    // x's exact type so result formatting matches even when the optimizer's tautology recognition
    // would otherwise produce a slightly different intermediate type.
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
            // Non-primitive column types (Array, Tuple, Map, Nothing, ...) are skipped to avoid
            // false positives where the cast-back coerces representation in ways unrelated to the
            // tautology-folding code path we're testing.
            throw new IgnoreMeException();
        }

        ClickHouseExpression injectExpr = gen.generateExpressionWithColumns(colRefs, MAX_INJECTED_EXPR_DEPTH);
        String injectSql = ClickHouseToStringVisitor.asString(injectExpr);
        String taut = tautologyFragment(injectSql);
        String contra = contradictionFragment(injectSql);
        // Junk-branch value must be the same type as x or ClickHouse rejects multiIf/CASE at parse
        // time (e.g., cast(NULL, 'LowCardinality(String)') fails because LowCardinality is not
        // nullable). defaultValueOfTypeName yields a non-NULL typed default for any ClickHouse
        // type, so the optimizer still has a real dead branch to recognize and fold past.
        String junkSql = "defaultValueOfTypeName(" + sqlQuote(typeOfX) + ")";

        ExprShape shape = Randomly.fromOptions(ExprShape.values());
        String rewriteInner = buildExprRewrite(shape, polarity, xSql, junkSql, taut, contra);
        String transExpr = "cast((" + rewriteInner + "), " + sqlQuote(typeOfX) + ")";
        assertSingleSnapshotEquivalent(table, xSql, transExpr,
                "EXPR-" + shape.name() + "-" + polarity.name() + " typeOfX=" + typeOfX);
    }

    // Construct the if/multiIf/CASE shape that must fold to x regardless of polarity:
    // - For tautology: the truth-path branch is x; the off-path branches are x or junk in slots
    // the condition will not select.
    // - For contradiction: the false-path branch is junk; x lands in the slot that wins.
    // The two-condition shapes (multiIf with 5 args, CASE with 2 WHEN clauses) exercise the
    // optimizer's recognition of redundant branches, which is a distinct code path from the bare
    // 2-arm conditional that `if` exercises.
    static String buildExprRewrite(ExprShape shape, Polarity polarity, String xSql, String junkSql, String taut,
            String contra) {
        switch (shape) {
        case IF:
            return polarity == Polarity.TAUTOLOGY ? "if(" + taut + ", " + xSql + ", " + xSql + ")"
                    : "if(" + contra + ", " + junkSql + ", " + xSql + ")";
        case MULTI_IF:
            // multiIf(cond1, then1, cond2, then2, else) -- 5 args. Tautology: first cond is true,
            // returns x. Contradiction: first cond is false, second cond is true, returns x.
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

    // Single-row probe of toTypeName(x) at the running server. Returns null when the table is empty
    // (no rows to evaluate), which the caller treats as IgnoreMeException via the type-foldability
    // check.
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

    // A type is "foldable" for EET expression rewriting if its inner term (after stripping Nullable
    // and LowCardinality wrappers) is a primitive type. Mirrors CODDTest's isFoldableColumnTerm
    // discipline but operates on the textual type name returned by toTypeName.
    static boolean isFoldablePrimitiveTypeName(String typeName) {
        try {
            ClickHouseType inner = ClickHouseTypeParser.parse(typeName).unwrap();
            return inner instanceof Primitive;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    // ----- Mode: algebraic identity rewriting -----

    // Pick a column-reference x, probe its runtime type, look up a type-safe identity in the
    // catalog, and rewrite the SELECT-list expression as the identity applied to x. The cast-back
    // wrap eliminates any type-widening introduced by the identity (e.g., plus(Int8, 0) widens to
    // Int16; the cast restores Int8). Polarity is unused for this mode -- every identity is
    // unconditionally x-preserving.
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
            // No identity in the v1 catalog accepts this type (Array, Tuple, Map, Float-only,
            // Decimal-only, etc.). Skip the attempt.
            throw new IgnoreMeException();
        }
        ClickHouseEETIdentities.Identity identity = picker.get();

        String transExpr = "cast((" + identity.applyTo(xSql) + "), " + sqlQuote(typeOfX) + ")";
        assertSingleSnapshotEquivalent(table, xSql, transExpr, "ALG-" + identity.name() + " typeOfX=" + typeOfX);
    }

    // ----- Mode: multiIf <-> nested-if structural equivalence (Unit 6.1) -----

    // Assert the catalog identity multiIf(c1, a, c2, b, d) == if(c1, a, if(c2, b, d)). The
    // generator renders both forms from the SAME (c1, a, c2, b, d) components, so the VALUE each
    // produces on every row must agree; a divergence is a branch-selection or short-circuit-folding
    // bug -- the surface the plan's WS6 multiIf unit targets.
    //
    // IMPORTANT: multiIf and nested-if do NOT necessarily agree on the RESULT TYPE. ClickHouse
    // unifies the branch types of an n-ary multiIf in one pass but unifies a nested if pairwise
    // from the inside out, so e.g. multiIf(.., lcm()->UInt, .., c0->Int32, max2()->Float64) settles
    // on an integer type while if(.., lcm, if(.., c0, max2)) settles on Float64. The values are
    // identical (-1875264158 vs -1.875264158E9), only the textual rendering differs. That is a
    // legitimate type-inference difference, not a wrong result, so comparing the raw renderings is
    // unsound. We normalise both forms through CAST(... AS Float64) so the comparison is purely on
    // value. Trade-off: two genuinely-different integer values above 2^53 could collide after the
    // Float64 round; that narrow blind spot is acceptable -- every branch-selection / short-circuit
    // bug changes the value far more than one ULP, and this mirrors the codebase's existing
    // float-tolerance stance for aggregate oracles.
    private void checkMultiIfNestedIfEquivalence(ClickHouseTable table, List<ClickHouseColumn> columns)
            throws SQLException {
        List<ClickHouseColumnReference> colRefs = columns.stream().map(c -> c.asColumnReference(table.getName()))
                .collect(Collectors.toList());
        ClickHouseExpressionGenerator gen = new ClickHouseExpressionGenerator(state);
        gen.addColumns(colRefs);

        String[] forms = gen.renderMultiIfAndNestedIf(colRefs);
        if (forms == null) {
            // No numeric column to build branch values from.
            throw new IgnoreMeException();
        }
        // forms[0]/[1] already CAST to a common Nullable(Float64) (see renderMultiIfAndNestedIf):
        // that normalises away the legitimate multiIf-vs-nested-if type-inference difference and
        // keeps the column wire-readable, so we compare the two forms directly.
        assertSingleSnapshotEquivalent(table, forms[0], forms[1], "MULTIIF-EQUIV");
    }

    // Single-snapshot value-equivalence check shared by the ALGEBRAIC_ID, EXPR_REWRITE and
    // MULTIIF_EQUIV modes. Both expressions are projected as two columns of ONE SELECT, so they are
    // evaluated against the same table snapshot. This is what makes the comparison sound under
    // concurrent async mutations: a previous design read the original and transformed forms as two
    // SEPARATE queries, and an in-flight `ALTER TABLE ... DELETE WHERE <truthy>` mutation completing
    // between the two reads produced a spurious "16 rows vs 0 rows" mismatch (root-caused 2026-06-02
    // on the reverse_reverse identity). Comparing two columns of the same query removes the race and
    // halves the query count. Rows are compared positionally (no sort): both columns come from the
    // same rows in the same order, and a per-row a!=b is the divergence.
    private void assertSingleSnapshotEquivalent(ClickHouseTable table, String origExpr, String transExpr, String label)
            throws SQLException {
        String tableQ = quote(table.getName());
        String query = "SELECT (" + origExpr + ") AS a, (" + transExpr + ") AS b FROM " + tableQ;
        this.auxiliaryQueryString = "-- EET " + label + " (single-snapshot two-column)";
        this.originalQueryString = "SELECT (" + origExpr + ") FROM " + tableQ;
        this.foldedQueryString = "SELECT (" + transExpr + ") FROM " + tableQ;
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
                            "EET[mode=%s] value mismatch at row %d:%n  Q: %s%n  a (orig)=%s%n  b (trans)=%s",
                            label, rowIdx, query, aNull ? "NULL" : a, bNull ? "NULL" : b));
                }
                rowIdx++;
            }
        } catch (SQLException ex) {
            throw maybeIgnore(ex);
        }
    }

    // ----- Row collection and SQL helpers (shared with later units) -----

    // Execute a query and return its rows as a Java-side sorted list of pipe-delimited strings.
    // Sorting on the Java side avoids relying on an SQL ORDER BY, which would itself be subject to
    // the constant-folding pipeline EET is testing.
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

    // Bridge expected-error catalog matching: throw IgnoreMeException for known noise, propagate
    // anything else as a real failure.
    SQLException maybeIgnore(SQLException ex) {
        if (ex.getMessage() != null && errors.errorIsExpected(ex.getMessage())) {
            throw new IgnoreMeException();
        }
        return ex;
    }

    static String quote(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }

    // Single-quote a string literal for embedding in SQL, with backslash and quote escaping.
    static String sqlQuote(String s) {
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

}
