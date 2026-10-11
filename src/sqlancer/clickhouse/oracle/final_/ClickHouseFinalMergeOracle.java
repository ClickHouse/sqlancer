package sqlancer.clickhouse.oracle.final_;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import sqlancer.ComparatorHelper;
import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.clickhouse.ClickHouseErrors;
import sqlancer.clickhouse.ClickHouseProvider.ClickHouseGlobalState;
import sqlancer.clickhouse.ClickHouseSchema.ClickHouseTable;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.ExpectedErrors;

public class ClickHouseFinalMergeOracle implements TestOracle<ClickHouseGlobalState> {

    private static final String SUMMING_ENGINE = "SummingMergeTree";

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

        String rowCount = SUMMING_ENGINE.equals(table.getEngine()) ? countOfRowsNotAllZero(table) : "count()";
        String baseSelect = "SELECT " + rowCount + " FROM " + fqTable;

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

    private String countOfRowsNotAllZero(ClickHouseTable summingTable) throws SQLException {
        if (summingTable.hasUnfinishedMutations()) {
            throw new IgnoreMeException();
        }
        String database = state.getDatabaseName().replace("'", "''");
        String table = summingTable.getName().replace("'", "''");
        List<String> engineFull = queryFirstColumn(
                "SELECT engine_full FROM system.tables WHERE database = '" + database + "' AND name = '" + table + "'");
        if (engineFull.size() != 1) {
            throw new IgnoreMeException();
        }
        Set<String> summingColumnsArgument = summingColumnsArgument(engineFull.get(0));
        List<String> exactlySummed = queryFirstColumn("SELECT name FROM system.columns WHERE database = '" + database
                + "' AND table = '" + table + "' AND NOT is_in_sorting_key AND NOT is_in_partition_key"
                + " AND default_kind IN ('', 'DEFAULT', 'MATERIALIZED')"
                + " AND (match(type, '^U?Int[0-9]+$') OR startsWith(type, 'Decimal(')) ORDER BY position").stream()
                        .filter(c -> summingColumnsArgument.isEmpty() || summingColumnsArgument.contains(c)).toList();
        if (exactlySummed.isEmpty()) {
            throw new IgnoreMeException();
        }
        return exactlySummed.stream().map(c -> c + " = 0").collect(Collectors.joining(" AND ", "countIf(NOT (", "))"));
    }

    private static Set<String> summingColumnsArgument(String engineFull) {
        String prefix = SUMMING_ENGINE + "(";
        if (!engineFull.startsWith(prefix)) {
            return Set.of();
        }
        int end = engineFull.indexOf(')', prefix.length());
        if (end < 0) {
            throw new IgnoreMeException();
        }
        return Arrays.stream(engineFull.substring(prefix.length(), end).replace("(", "").replace("`", "").split(","))
                .map(String::trim).filter(c -> !c.isEmpty()).collect(Collectors.toSet());
    }

    private List<String> queryFirstColumn(String query) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Statement s = state.getConnection().createStatement(); ResultSet rs = s.executeQuery(query)) {
            while (rs.next()) {
                values.add(rs.getString(1));
            }
        } catch (SQLException e) {
            if (ClickHouseErrors.isToleratedException(e)) {
                throw new IgnoreMeException();
            }
            throw e;
        }
        return values;
    }
}
