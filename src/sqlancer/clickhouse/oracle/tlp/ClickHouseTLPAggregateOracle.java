package sqlancer.clickhouse.oracle.tlp;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import sqlancer.ComparatorHelper;
import sqlancer.Randomly;
import sqlancer.clickhouse.ClickHouseErrors;
import sqlancer.clickhouse.ClickHouseProvider;
import sqlancer.clickhouse.ClickHouseVisitor;
import sqlancer.clickhouse.ast.ClickHouseAggregate;
import sqlancer.clickhouse.ast.ClickHouseAliasOperation;

public class ClickHouseTLPAggregateOracle extends ClickHouseTLPBase {

    public ClickHouseTLPAggregateOracle(ClickHouseProvider.ClickHouseGlobalState state) {
        super(state);
        ClickHouseErrors.addExpectedExpressionErrors(errors);
    }

    @Override
    public void check() throws SQLException {
        super.check();
        if (Randomly.getBooleanWithRatherLowProbability()) {
            select.setOrderByClauses(IntStream.range(0, 1 + Randomly.smallNumber())
                    .mapToObj(i -> gen.generateExpressionWithColumns(columns, 5)).collect(Collectors.toList()));
        }

        ClickHouseAggregate.ClickHouseAggregateFunction windowFunction = Randomly.fromOptions(
                ClickHouseAggregate.ClickHouseAggregateFunction.MIN,
                ClickHouseAggregate.ClickHouseAggregateFunction.MAX,
                ClickHouseAggregate.ClickHouseAggregateFunction.SUM);

        ClickHouseAggregate aggregate = new ClickHouseAggregate(gen.generateExpressionWithColumns(columns, 6),
                windowFunction);
        select.setFetchColumns(Arrays.asList(aggregate));

        String originalQuery = ClickHouseVisitor.asString(select);
        originalQuery += " SETTINGS aggregate_functions_null_for_empty = 1";

        select.setFetchColumns(Arrays.asList(new ClickHouseAliasOperation(aggregate, "aggr")));

        select.setWhereClause(predicate);
        // Inner GROUP BY removed: with GROUP BY in the partition branches, the inner SELECT
        // produces multiple aggregate rows per partition (one per group key). The outer SUM
        // then aggregates them, but NaN-producing functions (tan/sin/cos/sqrt/log on float
        // columns) propagate NaN through grouped vs ungrouped aggregations differently. The
        // 2026-05-27 25-oracle validation produced 24 reproducers all in this shape -- every
        // one was the SUM-of-SUM-over-groups-with-NaN pattern, not a real CH bug. The TLP
        // invariant the oracle wants to assert (SUM over all rows = SUM over partition SUMs
        // when no GROUP BY is present) is only sound without the inner GROUP BY.
        if (Randomly.getBoolean()) {
            select.setOrderByClauses(IntStream.range(0, 1 + Randomly.smallNumber())
                    .mapToObj(i -> gen.generateExpressionWithColumns(columns, 5)).collect(Collectors.toList()));
        }

        String metamorphicText = "SELECT " + aggregate.getFunc().toString() + "(aggr) FROM (";
        metamorphicText += ClickHouseVisitor.asString(select) + " UNION ALL ";
        select.setWhereClause(negatedPredicate);
        metamorphicText += ClickHouseVisitor.asString(select) + " UNION ALL ";
        select.setWhereClause(isNullPredicate);
        metamorphicText += ClickHouseVisitor.asString(select);
        metamorphicText += ")";
        metamorphicText += " SETTINGS aggregate_functions_null_for_empty = 1";
        List<String> firstResult = ComparatorHelper.getResultSetFirstColumnAsString(originalQuery, errors, state);

        List<String> secondResult = ComparatorHelper.getResultSetFirstColumnAsString(metamorphicText, errors, state);

        state.getState().getLocalState()
                .log("--" + originalQuery + "\n--" + metamorphicText + "\n-- " + firstResult + "\n-- " + secondResult
                        + "\n--first size " + firstResult.size() + "\n--second size " + secondResult.size());

        // Route every comparison through ComparatorHelper with ULP-tolerant multiset semantics.
        // Aggregate outputs are float-heavy and rendering differs by ULP between equivalent CH
        // aggregate paths (e.g. avgOrNull vs sum/count). The historical 1x1 special-case here
        // skipped multi-row aggregate-with-GROUP BY shapes and silently passed bugs in that
        // surface; using the full comparator catches them while preserving the float tolerance.
        ComparatorHelper.assumeResultSetsAreEqual(firstResult, secondResult, originalQuery,
                Collections.singletonList(metamorphicText), state, ComparatorHelper.ComparisonMode.ULP_TOLERANT_MULTISET);
    }

}
