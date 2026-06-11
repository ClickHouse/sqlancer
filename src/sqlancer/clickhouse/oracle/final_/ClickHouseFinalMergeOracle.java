package sqlancer.clickhouse.oracle.final_;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import sqlancer.ComparatorHelper;
import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.clickhouse.ClickHouseErrors;
import sqlancer.clickhouse.ClickHouseProvider.ClickHouseGlobalState;
import sqlancer.clickhouse.ClickHouseSchema.ClickHouseTable;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.ExpectedErrors;

public class ClickHouseFinalMergeOracle implements TestOracle<ClickHouseGlobalState> {

    private final ClickHouseGlobalState state;
    private final ExpectedErrors errors;

    public ClickHouseFinalMergeOracle(ClickHouseGlobalState state) {
        this.state = state;
        this.errors = ExpectedErrors.newErrors().with(ClickHouseErrors.getExpectedExpressionErrors())
                .with(ClickHouseErrors.getMutationErrors()).build();
    }

    @Override
    public void check() throws SQLException {
        List<ClickHouseTable> tables = state.getSchema().getDatabaseTables().stream()
                .filter(ClickHouseTable::supportsFinal).toList();
        if (tables.isEmpty()) {

            throw new IgnoreMeException();
        }
        ClickHouseTable table = Randomly.fromList(tables);
        String fqTable = state.getDatabaseName() + "." + table.getName();

        String baseSelect = "SELECT count() FROM " + fqTable;

        List<String> resultBefore = ComparatorHelper.getResultSetFirstColumnAsString(baseSelect, errors, state);

        List<String> resultFinal = ComparatorHelper.getResultSetFirstColumnAsString(
                baseSelect + " FINAL SETTINGS do_not_merge_across_partitions_select_final=1", errors, state);

        try (Statement s = state.getConnection().createStatement()) {
            s.execute("OPTIMIZE TABLE " + fqTable + " FINAL");
        } catch (SQLException e) {

            String msg = e.getMessage();
            if (msg == null || errors.errorIsExpected(msg) || msg.contains("ORDER BY cannot be empty")
                    || msg.contains("BAD_ARGUMENTS")) {
                throw new IgnoreMeException();
            }
            throw e;
        }

        String afterSelect = baseSelect + " FINAL SETTINGS do_not_merge_across_partitions_select_final=1";
        List<String> resultAfter = ComparatorHelper.getResultSetFirstColumnAsString(afterSelect, errors, state);

        ComparatorHelper.assumeResultSetsAreEqual(resultFinal, resultAfter,
                baseSelect + " FINAL  -- vs OPTIMIZE+post-merge\n-- result_before=" + resultBefore,
                java.util.Collections.singletonList(afterSelect + " (after OPTIMIZE FINAL)"), state,
                ComparatorHelper.ComparisonMode.MULTISET);
    }
}
