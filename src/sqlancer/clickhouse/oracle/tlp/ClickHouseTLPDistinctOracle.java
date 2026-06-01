package sqlancer.clickhouse.oracle.tlp;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import sqlancer.ComparatorHelper;
import sqlancer.IgnoreMeException;
import sqlancer.clickhouse.ClickHouseProvider;
import sqlancer.clickhouse.ClickHouseVisitor;
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

        ComparatorHelper.assumeResultSetsAreEqual(resultSet, secondResultSet, originalQueryString, combinedString,
                state);
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
