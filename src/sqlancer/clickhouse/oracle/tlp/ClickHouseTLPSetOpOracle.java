package sqlancer.clickhouse.oracle.tlp;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
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

public class ClickHouseTLPSetOpOracle extends ClickHouseTLPBase {

    private static final String SETTINGS_SUFFIX = " SETTINGS union_default_mode='DISTINCT', intersect_default_mode='ALL', except_default_mode='ALL', aggregate_functions_null_for_empty=1, enable_optimize_predicate_expression=0";

    private static final List<String> NON_DETERMINISTIC_IDENTIFIERS = List.of("rand", "randConstant", "rand64", "now",
            "now64", "today", "yesterday", "generateUUIDv4", "randomString", "randomFixedString", "canonicalRand");

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

            throw new IgnoreMeException();
        }

        guardAgainstAggregateFetchColumns();
        constrainToSingleColumn();
        guardAgainstNonDeterministicPredicate();
        guardAgainstFinalNonDeterminism();

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

    private void checkUnionAll() throws SQLException {
        String baseline = renderBaselineQuery();
        String branchUnion = renderThreeBranchSetOp(SetOpKind.UNION_ALL);

        List<String> baselineRows = ComparatorHelper.getResultSetFirstColumnAsString(baseline, errors, state);
        List<String> branchRows = ComparatorHelper.getResultSetFirstColumnAsString(branchUnion, errors, state);

        logSubstantiveCounter("UNION_ALL", baselineRows, branchRows);

        List<String> sortedBaseline = new ArrayList<>(baselineRows);
        List<String> sortedBranch = new ArrayList<>(branchRows);

        sortedBaseline.sort(java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder()));
        sortedBranch.sort(java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder()));
        if (!sortedBaseline.equals(sortedBranch)) {
            throw new AssertionError(formatFailure("UNION_ALL", baseline, branchUnion, baselineRows, branchRows));
        }
    }

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

    private void guardAgainstAggregateFetchColumns() {
        for (ClickHouseExpression expr : select.getFetchColumns()) {
            if (containsAggregate(expr)) {
                throw new IgnoreMeException();
            }
        }
    }

    private static boolean containsAggregate(ClickHouseExpression expr) {

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

            select.setFetchColumns(Arrays.asList(select.getFetchColumns().get(0)));
        }
    }

    private void guardAgainstFinalNonDeterminism() {
        if (select.isFinal()) {
            throw new IgnoreMeException();
        }
    }

    private void guardAgainstNonDeterministicPredicate() {
        for (ClickHouseExpression e : Arrays.asList(predicate, negatedPredicate, isNullPredicate)) {
            String rendered = ClickHouseVisitor.asString(e).toLowerCase(Locale.ROOT);
            for (String tok : NON_DETERMINISTIC_IDENTIFIERS) {

                if (rendered.contains(tok.toLowerCase(Locale.ROOT) + "(")) {
                    throw new IgnoreMeException();
                }
            }
            if (containsAggregate(e)) {
                throw new IgnoreMeException();
            }
        }
    }

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
