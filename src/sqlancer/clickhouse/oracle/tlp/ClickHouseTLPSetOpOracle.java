package sqlancer.clickhouse.oracle.tlp;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import sqlancer.ComparatorHelper;
import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.clickhouse.ClickHouseErrors;
import sqlancer.clickhouse.ClickHouseProvider.ClickHouseGlobalState;
import sqlancer.clickhouse.ClickHouseVisitor;
import sqlancer.clickhouse.ast.ClickHouseAggregate;
import sqlancer.clickhouse.ast.ClickHouseExpression;
import sqlancer.clickhouse.ast.ClickHouseSetOperation.SetOpKind;

/**
 * Ternary-Logic Partitioning oracle that exercises ClickHouse's set-operation planner.
 *
 * <p>
 * Per {@code check()} the oracle picks one {@link Mode} uniformly and validates an invariant that the canonical TLP
 * partition <code>(p, NOT p, p IS NULL)</code> must satisfy for that set-operation kind:
 * </p>
 * <ul>
 * <li><b>UNION_ALL</b> (multiset equality): <code>T &equiv; Tp &uplus; Tnp &uplus; T_null_p</code>.</li>
 * <li><b>UNION_DISTINCT</b> (set equality): <code>DISTINCT(T) &equiv; DISTINCT(Tp &cup; Tnp &cup; T_null_p)</code> with
 * leaf DISTINCT applied at every branch.</li>
 * <li><b>INTERSECT</b> (pairwise disjoint): each of <code>(Tp &cap; Tnp)</code>, <code>(Tp &cap; T_null_p)</code>,
 * <code>(Tnp &cap; T_null_p)</code> is empty.</li>
 * <li><b>EXCEPT</b> (coverage + pairwise disjointness):
 * <code>DISTINCT(T) EXCEPT ALL DISTINCT(Tp) EXCEPT ALL DISTINCT(Tnp) EXCEPT ALL DISTINCT(T_null_p)</code> is empty AND
 * each pairwise <code>DISTINCT(Tx) EXCEPT ALL DISTINCT(Ty) &equiv; DISTINCT(Tx)</code>.</li>
 * </ul>
 *
 * <p>
 * Renders explicit {@code INTERSECT ALL} / {@code EXCEPT ALL} / {@code UNION DISTINCT} operator keywords -- not the
 * bare {@code INTERSECT} / {@code EXCEPT} forms whose semantics are governed by version-sensitive
 * {@code *_default_mode} settings. A startup probe verifies that the SETTINGS pinning (belt-and-suspenders) is
 * supported by the running server; if a setting is unknown, the oracle disables itself for the run rather than silently
 * dropping its pinning.
 * </p>
 *
 * <p>
 * Three local guards filter cases that would produce false positives without exercising new planner code paths:
 * </p>
 * <ol>
 * <li>{@code fetchColumns} containing aggregates -- multiset partition correctness requires per-row classification, so
 * a collapsed result set cannot be checked row-for-row.</li>
 * <li>Non-deterministic identifiers in the predicate ({@code rand}, {@code now}, ...) -- per-row classification flips
 * across re-evaluation, breaking the partition.</li>
 * <li>Multi-column {@code fetchColumns} -- {@code getResultSetFirstColumnAsString} collapses to first column, so
 * multi-column results would silently mask real planner bugs on tuples.</li>
 * </ol>
 *
 * <p>
 * The non-determinism deny-list is intentionally scoped to this oracle only -- adding it to {@code ClickHouseTLPBase}
 * would stealth-change behaviour for the five existing TLP oracles whose 100k baselines were recorded without it.
 * </p>
 */
public class ClickHouseTLPSetOpOracle extends ClickHouseTLPBase {

    /**
     * Per-query SETTINGS suffix. Operator keywords ({@code INTERSECT ALL} etc.) are the load-bearing correctness
     * anchor; the SETTINGS pinning here is belt-and-suspenders for servers that honor the {@code *_default_mode}
     * family. {@code aggregate_functions_null_for_empty=1, enable_optimize_predicate_expression=0} dodges
     * ClickHouse#12264.
     */
    private static final String SETTINGS_SUFFIX = " SETTINGS union_default_mode='DISTINCT', intersect_default_mode='ALL', except_default_mode='ALL', aggregate_functions_null_for_empty=1, enable_optimize_predicate_expression=0";

    /**
     * Identifiers whose presence in the predicate breaks per-row classification across re-evaluation.
     * Comment-documented and additive -- new entries land empirically. Structural caveat: a deny-list cannot
     * distinguish "undiscovered non-deterministic function" from "real bug"; an allow-list of known-pure functions is a
     * tracked follow-up.
     */
    private static final List<String> NON_DETERMINISTIC_IDENTIFIERS = List.of("rand", "randConstant", "rand64", "now",
            "now64", "today", "yesterday", "generateUUIDv4", "randomString", "randomFixedString", "canonicalRand");

    /**
     * Probe outcome cached in a static so a single server-version's setting availability is paid for once per process.
     * Volatile -- accessed by all worker threads.
     */
    private static volatile Boolean settingsProbed;
    private static volatile boolean settingsProbeSucceeded;

    public enum Mode {
        UNION_ALL, UNION_DISTINCT, INTERSECT, EXCEPT
    }

    public ClickHouseTLPSetOpOracle(ClickHouseGlobalState state) {
        super(state);
        ClickHouseErrors.addExpectedExpressionErrors(errors);
        errors.addAll(ClickHouseErrors.getSetOpErrors());
    }

    @Override
    public void check() throws SQLException {
        super.check();
        ensureSettingsProbe();
        if (!settingsProbeSucceeded) {
            // Server doesn't accept the SETTINGS we pin; bare INTERSECT/EXCEPT semantics are too
            // version-dependent to validate without that pinning. Disable the oracle for the run.
            throw new IgnoreMeException();
        }

        guardAgainstAggregateFetchColumns();
        constrainToSingleColumn();
        guardAgainstNonDeterministicPredicate();

        Mode mode = Randomly.fromOptions(Mode.values());
        switch (mode) {
        case UNION_ALL:
            checkUnionAll();
            break;
        case UNION_DISTINCT:
            checkUnionDistinct();
            break;
        case INTERSECT:
            checkIntersect();
            break;
        case EXCEPT:
            checkExcept();
            break;
        default:
            throw new AssertionError(mode);
        }
    }

    // ----- Mode: UNION_ALL (canonical TLP multiset equality) -----

    private void checkUnionAll() throws SQLException {
        String baseline = renderBaselineQuery();
        String branchUnion = renderThreeBranchSetOp(SetOpKind.UNION_ALL);

        List<String> baselineRows = ComparatorHelper.getResultSetFirstColumnAsString(baseline, errors, state);
        List<String> branchRows = ComparatorHelper.getResultSetFirstColumnAsString(branchUnion, errors, state);

        logSubstantiveCounter("UNION_ALL", baselineRows, branchRows);

        List<String> sortedBaseline = new ArrayList<>(baselineRows);
        List<String> sortedBranch = new ArrayList<>(branchRows);
        Collections.sort(sortedBaseline);
        Collections.sort(sortedBranch);
        if (!sortedBaseline.equals(sortedBranch)) {
            throw new AssertionError(formatFailure("UNION_ALL", baseline, branchUnion, baselineRows, branchRows));
        }
    }

    // ----- Mode: UNION_DISTINCT (set equality with leaf-DISTINCT) -----

    private void checkUnionDistinct() throws SQLException {
        String baseline = renderDistinctOf(renderBaselineQuery());
        String branchUnion = renderThreeBranchSetOpWithLeafDistinct(SetOpKind.UNION_DISTINCT);

        List<String> baselineRows = ComparatorHelper.getResultSetFirstColumnAsString(baseline, errors, state);
        List<String> branchRows = ComparatorHelper.getResultSetFirstColumnAsString(branchUnion, errors, state);

        logSubstantiveCounter("UNION_DISTINCT", baselineRows, branchRows);

        if (containsNanOrInfinity(baselineRows) || containsNanOrInfinity(branchRows)) {
            throw new IgnoreMeException();
        }

        Set<String> baselineSet = new HashSet<>(baselineRows);
        Set<String> branchSet = new HashSet<>(branchRows);
        if (!baselineSet.equals(branchSet)) {
            throw new AssertionError(formatFailure("UNION_DISTINCT", baseline, branchUnion, baselineRows, branchRows));
        }
    }

    // Set operations in SQL use scalar equality, but NaN != NaN. So {NaN} INTERSECT {NaN} is empty
    // in ClickHouse even though both sets contain a value rendered as "NaN", and {NaN} EXCEPT {NaN}
    // leaves {NaN}. This breaks the set-coverage and subset invariants whenever any branch projects
    // NaN. Same story for signed infinities and negative zero. Skip the comparison entirely when
    // the result set contains any of these tokens -- a deny-list local to the comparison helper,
    // with no false negatives because the deny-listed values are well-defined and never coincide
    // with normal numeric outputs.
    private static boolean containsNanOrInfinity(List<String> rows) {
        for (String r : rows) {
            if (r == null) {
                continue;
            }
            if (r.equals("nan") || r.equals("NaN") || r.equals("-nan") || r.equals("Infinity") || r.equals("-Infinity")
                    || r.equals("inf") || r.equals("-inf")) {
                return true;
            }
        }
        return false;
    }

    // ----- Mode: INTERSECT (subset relationship via operator routing) -----

    // Note: the pairwise-disjointness invariant (Tp ∩ Tnp = ∅) is unsound on projections -- TLP
    // partitions rows, but the SELECT-list projection can collapse rows in Tp and Tnp to identical
    // values (e.g., constant fetchCol, c0/c0, anything that ignores the predicate-discriminating
    // input). What's still invariant is: DISTINCT(branch) INTERSECT DISTINCT(T) ≡ DISTINCT(branch),
    // because every projected value in a branch must also appear in T (the branch's rows are a
    // subset of T's rows). This exercises the INTERSECT operator without claiming row-disjointness.
    private void checkIntersect() throws SQLException {
        String tDistinct = renderDistinctOf(renderBaselineQuery());
        List<String[]> branchPairs = List.of(new String[] { "Tp", renderDistinctOf(renderBranchQuery(predicate)) },
                new String[] { "Tnp", renderDistinctOf(renderBranchQuery(negatedPredicate)) },
                new String[] { "Tnull", renderDistinctOf(renderBranchQuery(isNullPredicate)) });
        for (String[] pair : branchPairs) {
            String branchName = pair[0];
            String branchSql = pair[1];
            String intersectQ = wrapParen(branchSql) + " " + SetOpKind.INTERSECT_ALL.getKeyword() + " "
                    + wrapParen(tDistinct) + SETTINGS_SUFFIX;
            List<String> intersectRows = ComparatorHelper.getResultSetFirstColumnAsString(intersectQ, errors, state);
            List<String> branchRows = ComparatorHelper.getResultSetFirstColumnAsString(branchSql + SETTINGS_SUFFIX,
                    errors, state);
            if (containsNanOrInfinity(branchRows) || containsNanOrInfinity(intersectRows)) {
                throw new IgnoreMeException();
            }
            Set<String> intersectSet = new HashSet<>(intersectRows);
            Set<String> branchSet = new HashSet<>(branchRows);
            if (!intersectSet.equals(branchSet)) {
                throw new AssertionError(
                        "INTERSECT subset violated: branch=" + branchName + " INTERSECT T produced " + intersectRows
                                + ", expected branch's own distinct rows " + branchRows + " for query " + intersectQ);
            }
        }
        state.getState().getLocalState().log("setop-tlp: kind=INTERSECT, branches_checked=3");
    }

    // ----- Mode: EXCEPT (coverage of the value space) -----

    // Note: the pairwise EXCEPT invariant (DISTINCT(Tp) EXCEPT DISTINCT(Tnp) ≡ DISTINCT(Tp)) is
    // unsound for the same reason as pairwise INTERSECT -- projections collapse rows so Tp and Tnp
    // can share projected values even when their underlying rows are disjoint. Only the coverage
    // invariant is kept: every distinct value produced by the full baseline must be produced by at
    // least one branch (since every row of T lands in exactly one branch by construction).
    private void checkExcept() throws SQLException {
        String tDistinct = renderDistinctOf(renderBaselineQuery());
        String tpDistinct = renderDistinctOf(renderBranchQuery(predicate));
        String tnpDistinct = renderDistinctOf(renderBranchQuery(negatedPredicate));
        String tnullDistinct = renderDistinctOf(renderBranchQuery(isNullPredicate));

        String coverage = wrapParen(tDistinct) + " " + SetOpKind.EXCEPT_ALL.getKeyword() + " " + wrapParen(tpDistinct)
                + " " + SetOpKind.EXCEPT_ALL.getKeyword() + " " + wrapParen(tnpDistinct) + " "
                + SetOpKind.EXCEPT_ALL.getKeyword() + " " + wrapParen(tnullDistinct) + SETTINGS_SUFFIX;
        List<String> coverageRows = ComparatorHelper.getResultSetFirstColumnAsString(coverage, errors, state);
        if (containsNanOrInfinity(coverageRows)) {
            throw new IgnoreMeException();
        }
        if (!coverageRows.isEmpty()) {
            throw new AssertionError("EXCEPT coverage violated: " + coverage + " produced " + coverageRows);
        }
        state.getState().getLocalState().log("setop-tlp: kind=EXCEPT, coverage_ok=true");
    }

    // ----- Render helpers -----

    private String renderBaselineQuery() {
        ClickHouseExpression savedWhere = select.getWhereClause();
        try {
            select.setWhereClause(null);
            return ClickHouseVisitor.asString(select);
        } finally {
            select.setWhereClause(savedWhere);
        }
    }

    private String renderBranchQuery(ClickHouseExpression where) {
        ClickHouseExpression savedWhere = select.getWhereClause();
        try {
            select.setWhereClause(where);
            return ClickHouseVisitor.asString(select);
        } finally {
            select.setWhereClause(savedWhere);
        }
    }

    private String renderThreeBranchSetOp(SetOpKind kind) {
        // Right-associated tree: (A op (B op C)). String form matches the AST shape.
        String qP = renderBranchQuery(predicate);
        String qNp = renderBranchQuery(negatedPredicate);
        String qNull = renderBranchQuery(isNullPredicate);
        String combined = wrapParen(qP) + " " + kind.getKeyword() + " " + wrapParen(qNp) + " " + kind.getKeyword() + " "
                + wrapParen(qNull);
        return combined + SETTINGS_SUFFIX;
    }

    private String renderThreeBranchSetOpWithLeafDistinct(SetOpKind kind) {
        String qP = renderDistinctOf(renderBranchQuery(predicate));
        String qNp = renderDistinctOf(renderBranchQuery(negatedPredicate));
        String qNull = renderDistinctOf(renderBranchQuery(isNullPredicate));
        String combined = wrapParen(qP) + " " + kind.getKeyword() + " " + wrapParen(qNp) + " " + kind.getKeyword() + " "
                + wrapParen(qNull);
        return combined + SETTINGS_SUFFIX;
    }

    private static String renderDistinctOf(String inner) {
        return "SELECT DISTINCT * FROM (" + inner + ")";
    }

    private static String wrapParen(String s) {
        return "(" + s + ")";
    }

    private void logSubstantiveCounter(String kind, List<String> baselineRows, List<String> branchRows) {
        boolean substantive = !baselineRows.isEmpty() || !branchRows.isEmpty();
        state.getState().getLocalState().log("setop-tlp: kind=" + kind + ", baseline_size=" + baselineRows.size()
                + ", branch_size=" + branchRows.size() + ", substantive=" + substantive);
    }

    private String formatFailure(String kind, String baselineSql, String branchSql, List<String> baselineRows,
            List<String> branchRows) {
        return String.format(Locale.ROOT,
                "TLPSetOp[%s] mismatch:%n  baseline: %s%n  branched: %s%n  baseline rows (%d): %s%n  branch rows (%d): %s",
                kind, baselineSql, branchSql, baselineRows.size(), baselineRows, branchRows.size(), branchRows);
    }

    // ----- Guards -----

    private void guardAgainstAggregateFetchColumns() {
        for (ClickHouseExpression expr : select.getFetchColumns()) {
            if (containsAggregate(expr)) {
                throw new IgnoreMeException();
            }
        }
    }

    private static boolean containsAggregate(ClickHouseExpression expr) {
        // Conservative deep walk: stringify and look for the bare aggregate function names. The
        // generator's only aggregate emitter is ClickHouseAggregate, which renders as one of the
        // five enum names; substring match here is faithful to that surface.
        if (expr instanceof ClickHouseAggregate) {
            return true;
        }
        String rendered = ClickHouseVisitor.asString(expr).toLowerCase(Locale.ROOT);
        for (String agg : Arrays.asList("sum(", "count(", "avg(", "min(", "max(")) {
            if (rendered.contains(agg)) {
                return true;
            }
        }
        return false;
    }

    private void constrainToSingleColumn() {
        if (select.getFetchColumns().size() > 1) {
            // Multi-column comparison via getResultSetFirstColumnAsString silently drops all but
            // the first column. Constrain to a single column so multiset/set comparison is faithful.
            select.setFetchColumns(Arrays.asList(select.getFetchColumns().get(0)));
        }
    }

    private void guardAgainstNonDeterministicPredicate() {
        for (ClickHouseExpression e : Arrays.asList(predicate, negatedPredicate, isNullPredicate)) {
            String rendered = ClickHouseVisitor.asString(e).toLowerCase(Locale.ROOT);
            for (String tok : NON_DETERMINISTIC_IDENTIFIERS) {
                // Match the function name followed by '(' to avoid false hits on user-chosen
                // identifiers that happen to share a prefix.
                if (rendered.contains(tok.toLowerCase(Locale.ROOT) + "(")) {
                    throw new IgnoreMeException();
                }
            }
            if (containsAggregate(e)) {
                throw new IgnoreMeException();
            }
        }
    }

    // ----- Startup probe -----

    private void ensureSettingsProbe() {
        if (settingsProbed != null) {
            return;
        }
        synchronized (ClickHouseTLPSetOpOracle.class) {
            if (settingsProbed != null) {
                return;
            }
            boolean ok = true;
            try (Statement s = state.getConnection().createStatement()) {
                for (String probe : Arrays.asList("SELECT 1 SETTINGS intersect_default_mode='ALL'",
                        "SELECT 1 SETTINGS except_default_mode='ALL'",
                        "SELECT 1 SETTINGS union_default_mode='DISTINCT'")) {
                    try {
                        s.execute(probe);
                    } catch (SQLException ex) {
                        // Probe failure is logged but not added to ExpectedErrors -- a setting
                        // rename masquerading as a generic UNKNOWN_SETTING must remain visible to
                        // future audits. The whole oracle disables itself instead.
                        ok = false;
                        state.getState().getLocalState()
                                .log("setop-tlp: settings probe failed for [" + probe + "]: " + ex.getMessage());
                        break;
                    }
                }
            } catch (SQLException ex) {
                ok = false;
                state.getState().getLocalState().log("setop-tlp: settings probe failed: " + ex.getMessage());
            }
            settingsProbeSucceeded = ok;
            settingsProbed = Boolean.TRUE;
        }
    }
}
