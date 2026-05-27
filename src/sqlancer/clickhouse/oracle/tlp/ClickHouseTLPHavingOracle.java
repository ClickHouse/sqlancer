package sqlancer.clickhouse.oracle.tlp;

import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import sqlancer.ComparatorHelper;
import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.clickhouse.ClickHouseErrors;
import sqlancer.clickhouse.ClickHouseProvider;
import sqlancer.clickhouse.ClickHouseVisitor;
import sqlancer.clickhouse.ast.ClickHouseAggregate;
import sqlancer.clickhouse.ast.ClickHouseExpression;
import sqlancer.clickhouse.ast.ClickHouseSelect;
import sqlancer.clickhouse.ast.ClickHouseUnaryPostfixOperation;
import sqlancer.clickhouse.ast.ClickHouseUnaryPrefixOperation;

public class ClickHouseTLPHavingOracle extends ClickHouseTLPBase {

    public ClickHouseTLPHavingOracle(ClickHouseProvider.ClickHouseGlobalState state) {
        super(state);
        ClickHouseErrors.addExpectedExpressionErrors(errors);
    }

    @Override
    public void check() throws SQLException {
        super.check();
        select.setFetchColumns(IntStream.range(0, Randomly.smallNumber() + 1)
                .mapToObj(i -> gen.generateAggregateExpressionWithColumns(columns, 3)).collect(Collectors.toList()));
        select.setSelectType(ClickHouseSelect.SelectType.ALL);
        // TODO order by?

        List<ClickHouseExpression> groupByColumns = IntStream.range(0, 1 + Randomly.smallNumber())
                .mapToObj(i -> gen.generateExpressionWithColumns(columns, 6)).collect(Collectors.toList());

        select.setGroupByClause(groupByColumns);
        select.setHavingClause(null);
        String originalQueryString = ClickHouseVisitor.asString(select);
        originalQueryString += " SETTINGS aggregate_functions_null_for_empty=1, enable_optimize_predicate_expression=0"; // https://github.com/ClickHouse/ClickHouse/issues/12264

        List<String> resultSet = ComparatorHelper.getResultSetFirstColumnAsString(originalQueryString, errors, state);

        List<ClickHouseExpression> aggregateExprs = select.getFetchColumns().stream()
                .filter(p -> p instanceof ClickHouseAggregate).collect(Collectors.toList());
        if (aggregateExprs.isEmpty()) {
            throw new IgnoreMeException();
        }
        ClickHouseExpression predicate = gen.generateExpressionWithExpression(aggregateExprs, 6);
        select.setHavingClause(predicate);
        String firstQueryString = ClickHouseVisitor.asString(select);
        select.setHavingClause(new ClickHouseUnaryPrefixOperation(predicate,
                ClickHouseUnaryPrefixOperation.ClickHouseUnaryPrefixOperator.NOT));
        String secondQueryString = ClickHouseVisitor.asString(select);
        select.setHavingClause(new ClickHouseUnaryPostfixOperation(predicate,
                ClickHouseUnaryPostfixOperation.ClickHouseUnaryPostfixOperator.IS_NULL, false));
        String thirdQueryString = ClickHouseVisitor.asString(select);
        String combinedString = firstQueryString + " UNION ALL " + secondQueryString + " UNION ALL " + thirdQueryString;
        combinedString += " SETTINGS aggregate_functions_null_for_empty=1, enable_optimize_predicate_expression=0"; // https://github.com/ClickHouse/ClickHouse/issues/12264
        List<String> secondResultSet = ComparatorHelper.getResultSetFirstColumnAsString(combinedString, errors, state);
        if (state.getOptions().logEachSelect()) {
            state.getLogger().writeCurrent(originalQueryString);
            state.getLogger().writeCurrent(combinedString);
        }
        // Multiset semantics: HAVING-filtered aggregate rows can repeat structurally identical
        // group entries when the HAVING predicate is NULL across branches, and the count of
        // those entries must match between LHS and RHS for the TLP invariant to hold.
        ComparatorHelper.assumeResultSetsAreEqual(resultSet, secondResultSet, originalQueryString,
                Collections.singletonList(combinedString), state, ComparatorHelper.ComparisonMode.MULTISET);
    }
}
