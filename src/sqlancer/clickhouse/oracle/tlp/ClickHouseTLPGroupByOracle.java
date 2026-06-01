package sqlancer.clickhouse.oracle.tlp;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import sqlancer.ComparatorHelper;
import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.clickhouse.ClickHouseProvider;
import sqlancer.clickhouse.ClickHouseVisitor;
import sqlancer.clickhouse.ast.ClickHouseExpression;

public class ClickHouseTLPGroupByOracle extends ClickHouseTLPBase {

    public ClickHouseTLPGroupByOracle(ClickHouseProvider.ClickHouseGlobalState state) {
        super(state);
    }

    @Override
    public void check() throws SQLException {
        super.check();
        List<ClickHouseExpression> groupByColumns = IntStream.range(0, 1 + Randomly.smallNumber())
                .mapToObj(i -> gen.generateExpressionWithColumns(columns, 5)).collect(Collectors.toList());

        // Project ONLY the group-by keys (not the arbitrary fetch columns super.check() set).
        // TLPBase populates fetchColumns with arbitrary expressions, which CH evaluates with
        // implicit any() per group. Two different groups can collide on any() projection value
        // (especially with NaN-producing functions over float keys), yielding LHS rows that
        // differ in group identity but match on projection -- the outer DISTINCT * on the RHS
        // collapses what LHS preserves. Projecting the group keys directly makes the row
        // identity = the group identity, so the TLP partition invariant holds structurally.
        select.setFetchColumns(groupByColumns);
        select.setGroupByClause(groupByColumns);
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

        // TLPGroupBy partitioning splits rows across {p, NOT p, p IS NULL} branches; each branch
        // re-applies GROUP BY independently. A group key that lands in more than one branch shows
        // up multiple times in the UNION ALL, with per-branch (not global) aggregate values --
        // diverging from the original GROUP BY's per-key single row. Wrapping the UNION ALL in
        // SELECT DISTINCT * (asUnion=false on the helper) collapses the per-branch duplicates so
        // the comparison is set-shaped on the surviving projection. The plan acknowledges this
        // loses some adversarial coverage; --tlp-groupby-strict opts back into UNION ALL for
        // periodic strict sweeps.
        //
        // Note on the helper's asUnion flag: passing true emits a bare `UNION` which ClickHouse
        // rejects with EXPECTED_ALL_OR_DISTINCT unless `union_default_mode` is set. asUnion=false
        // uses the `SELECT DISTINCT * FROM (... UNION ALL ...)` shape which is portable.
        boolean strict = state.getClickHouseOptions().tlpGroupByStrict;
        List<String> secondResultSet;
        if (strict) {
            secondResultSet = ComparatorHelper.getCombinedResultSet(firstQueryString, secondQueryString,
                    thirdQueryString, combinedString, true, state, errors);
        } else {
            secondResultSet = ComparatorHelper.getCombinedResultSetNoDuplicates(firstQueryString, secondQueryString,
                    thirdQueryString, combinedString, false, state, errors);
        }
        // NaN/Inf guard (shared with TLPDistinct via ClickHouseTLPBase): a GROUP BY key that is a
        // NaN/Inf-producing expression makes the distinct-group count implementation-defined --
        // single-pass GROUP BY coalesces NaN bit-patterns differently than the UNION-ALL + outer
        // DISTINCT reformulation, so the cardinalities legitimately diverge. (iter9 db6:
        // (-(c0-c0)) * (max2(c0,c0) % sign(c0)) -> 0*(c0%0) = NaN for c0=0, giving a 2-vs-3 split.)
        if (projectionMayBeNonFinite(resultSet, secondResultSet, originalQueryString)) {
            throw new IgnoreMeException();
        }

        ComparatorHelper.assumeResultSetsAreEqual(resultSet, secondResultSet, originalQueryString, combinedString,
                state, strict ? ComparatorHelper.ComparisonMode.MULTISET : ComparatorHelper.ComparisonMode.SET);
    }
}
