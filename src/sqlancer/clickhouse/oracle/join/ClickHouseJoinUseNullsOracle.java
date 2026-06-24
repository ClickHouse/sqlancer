package sqlancer.clickhouse.oracle.join;

import java.sql.SQLException;
import java.util.List;
import java.util.stream.Collectors;

import sqlancer.ComparatorHelper;
import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.clickhouse.ClickHouseErrors;
import sqlancer.clickhouse.ClickHouseProvider.ClickHouseGlobalState;
import sqlancer.clickhouse.ClickHouseTypeFilters;
import sqlancer.clickhouse.ClickHouseVisitor;
import sqlancer.clickhouse.ast.ClickHouseAliasOperation;
import sqlancer.clickhouse.ast.ClickHouseColumnReference;
import sqlancer.clickhouse.ast.constant.ClickHouseCreateConstant;
import sqlancer.clickhouse.oracle.tlp.ClickHouseTLPBase;

public class ClickHouseJoinUseNullsOracle extends ClickHouseTLPBase {

    public ClickHouseJoinUseNullsOracle(ClickHouseGlobalState state) {
        super(state);
        ClickHouseErrors.addSessionSettingsErrors(errors);
        ClickHouseJoinAlgorithmOracle.addResourceCapErrors(errors);
    }

    @Override
    public void check() throws SQLException {
        super.check();

        if (select.getJoinClauses().size() != 1
                || !ClickHouseJoinAlgorithmOracle.isAlgorithmDeterministic(select.getJoinClauses().get(0).getType())) {
            throw new IgnoreMeException();
        }
        select.setWhereClause(null);

        List<ClickHouseColumnReference> intCols = columns.stream()
                .filter(c -> ClickHouseTypeFilters.isExactIntegerFamily(c.getColumn()))
                .collect(Collectors.toList());

        String inner;
        String outerProjection;
        if (intCols.isEmpty()) {

            select.setFetchColumns(
                    List.of(new ClickHouseAliasOperation(ClickHouseCreateConstant.createInt32Constant(1), "v0")));
            inner = ClickHouseVisitor.asString(select);
            outerProjection = "count()";
        } else {
            ClickHouseColumnReference col = Randomly.fromList(intCols);
            select.setFetchColumns(List.of(new ClickHouseAliasOperation(col, "v0")));
            inner = ClickHouseVisitor.asString(select);

            outerProjection = "ifNull(v0, 0)";
        }

        String base = "SELECT " + outerProjection + " FROM (" + inner + ")";
        String qDefaults = base + " SETTINGS join_use_nulls = 0, " + ClickHouseJoinAlgorithmOracle.CAPS;
        String qNulls = base + " SETTINGS join_use_nulls = 1, " + ClickHouseJoinAlgorithmOracle.CAPS;
        List<String> rowsDefaults = ComparatorHelper.getResultSetFirstColumnAsString(qDefaults, errors, state);
        List<String> rowsNulls = ComparatorHelper.getResultSetFirstColumnAsString(qNulls, errors, state);

        ComparatorHelper.assumeResultSetsAreEqual(rowsDefaults, rowsNulls, qDefaults, List.of(qNulls), state,
                ComparatorHelper.ComparisonMode.MULTISET);
    }
}
