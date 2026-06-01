package sqlancer.clickhouse.oracle.tlp;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import sqlancer.ComparatorHelper;
import sqlancer.IgnoreMeException;
import sqlancer.clickhouse.ClickHouseProvider;
import sqlancer.clickhouse.ClickHouseVisitor;
import sqlancer.clickhouse.ast.ClickHouseExpression;
import sqlancer.clickhouse.ast.ClickHouseSelect;

public class ClickHouseTLPDistinctOracle extends ClickHouseTLPBase {

    public ClickHouseTLPDistinctOracle(ClickHouseProvider.ClickHouseGlobalState state) {
        super(state);
    }

    @Override
    public void check() throws SQLException {
        // TLPDistinct's RHS already collapses partition multiplicity via UNION DISTINCT
        // (getCombinedResultSetNoDuplicates with asUnion=false wraps the UNION ALL in a SELECT
        // DISTINCT). The same defect that bites TLPGroupBy -- a row appearing across multiple
        // partition branches -- is already handled here. Do not "fix" by switching to UNION ALL.
        super.check();
        select.setSelectType(ClickHouseSelect.SelectType.DISTINCT);
        select.setWhereClause(null);
        String originalQueryString = ClickHouseVisitor.asString(select);

        List<String> resultSet = ComparatorHelper.getResultSetFirstColumnAsString(originalQueryString, errors, state);

        select.setWhereClause(predicate);
        String firstQueryString = ClickHouseVisitor.asString(select);
        select.setWhereClause(negatedPredicate);
        String secondQueryString = ClickHouseVisitor.asString(select);
        select.setWhereClause(isNullPredicate);
        String thirdQueryString = ClickHouseVisitor.asString(select);
        List<String> combinedString = new ArrayList<>();
        List<String> secondResultSet = ComparatorHelper.getCombinedResultSetNoDuplicates(firstQueryString,
                secondQueryString, thirdQueryString, combinedString, false, state, errors);

        // NaN/Inf guard: starting in CH 26.6 a single-pass SELECT DISTINCT coalesces different
        // NaN bit-patterns into one row, while the UNION-ALL + outer-DISTINCT path (the RHS here)
        // keeps them separate (or vice-versa). The two formulations then return different
        // cardinalities for the SAME projected value -- a non-finite float. Since NaN != NaN, the
        // distinct-count over a NaN-producing projection is implementation-defined, so the TLP
        // invariant genuinely does not hold; this is not a wrong-result. (All NaN/Inf values
        // render to the same "nan"/"inf" token through our reader, so the divergence is purely in
        // the row COUNT CH reports.) Skip when either side surfaces a non-finite leading value.
        if (resultSet.stream().anyMatch(ClickHouseTLPDistinctOracle::isNonFiniteFloat)
                || secondResultSet.stream().anyMatch(ClickHouseTLPDistinctOracle::isNonFiniteFloat)) {
            throw new IgnoreMeException();
        }
        // The check above only sees the leading projected column (getResultSetFirstColumnAsString).
        // A multi-column DISTINCT is over the whole tuple, so a NaN/Inf in ANY non-leading column
        // also makes the distinct-count unreliable. Probe every projected column server-side.
        // (iter8 db7: min2(sqrt(c2),..) / (c1/c2)/(c0%c2) produced NaN/Inf in columns 2-3 while
        // column 1 -c2 was finite, slipping past the leading-value check.)
        List<ClickHouseExpression> fetch = select.getFetchColumns();
        if (fetch.size() > 1 && projectionHasNonFinite(originalQueryString, fetch)) {
            throw new IgnoreMeException();
        }

        ComparatorHelper.assumeResultSetsAreEqual(resultSet, secondResultSet, originalQueryString, combinedString,
                state);
    }

    // Best-effort server-side probe: returns true iff any projected column yields a non-finite
    // (NaN/Inf) value on any row. Reuses the FROM/PREWHERE/FINAL/JOIN tail of the original
    // DISTINCT query (which carries no WHERE, so it covers all rows). Each column is normalised
    // through toFloat64OrZero(toString(...)) so non-numeric columns parse to 0 (not flagged) while
    // a float NaN/Inf renders to "nan"/"inf" and parses back to NaN/Inf. Any probe failure returns
    // false (do not skip) so the probe never manufactures a reproducer of its own.
    private boolean projectionHasNonFinite(String originalQueryString, List<ClickHouseExpression> fetch) {
        int fromIdx = findOuterFrom(originalQueryString);
        if (fromIdx < 0) {
            return false;
        }
        String fromTail = originalQueryString.substring(fromIdx + 1); // keep "FROM ..."
        StringBuilder cond = new StringBuilder();
        for (int i = 0; i < fetch.size(); i++) {
            if (i > 0) {
                cond.append(" OR ");
            }
            String e = ClickHouseVisitor.asString(fetch.get(i));
            cond.append("isNaN(toFloat64OrZero(toString(").append(e).append("))) OR isInfinite(toFloat64OrZero(toString(")
                    .append(e).append(")))");
        }
        String probe = "SELECT max(" + cond + ") " + fromTail;
        try {
            return ComparatorHelper.getResultSetFirstColumnAsString(probe, errors, state).stream()
                    .anyMatch("1"::equals);
        } catch (Exception e) {
            return false;
        }
    }

    // Index of the outer-scope " FROM " in a rendered SELECT, skipping any " FROM " nested inside
    // parentheses (e.g. a scalar-subquery fetch column carries its own FROM). Mirrors the helper in
    // ClickHouseTLPCombinatorOracle.
    private static int findOuterFrom(String rendered) {
        int depth = 0;
        for (int i = 0; i < rendered.length() - 6; i++) {
            char c = rendered.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (depth == 0 && c == ' ' && rendered.regionMatches(i, " FROM ", 0, 6)) {
                return i;
            }
        }
        return -1;
    }

    // True iff the rendered value is a non-finite float token (nan / inf / infinity, any sign),
    // matched exactly so ordinary String column values like "information" are not caught.
    private static boolean isNonFiniteFloat(String v) {
        if (v == null) {
            return false;
        }
        String s = v.trim();
        if (!s.isEmpty() && (s.charAt(0) == '+' || s.charAt(0) == '-')) {
            s = s.substring(1);
        }
        s = s.toLowerCase(Locale.ROOT);
        return s.equals("nan") || s.equals("inf") || s.equals("infinity");
    }

}
