package sqlancer.clickhouse.oracle.qcc;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import sqlancer.ComparatorHelper;
import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.clickhouse.ClickHouseErrors;
import sqlancer.clickhouse.ClickHouseProvider.ClickHouseGlobalState;
import sqlancer.clickhouse.ClickHouseSchema;
import sqlancer.clickhouse.ClickHouseSchema.ClickHouseTable;
import sqlancer.clickhouse.ClickHouseVisitor;
import sqlancer.clickhouse.ast.ClickHouseColumnReference;
import sqlancer.clickhouse.ast.ClickHouseSelect;
import sqlancer.clickhouse.ast.ClickHouseTableReference;
import sqlancer.clickhouse.gen.ClickHouseExpressionGenerator;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;

public class ClickHouseQueryConditionCacheOracle implements TestOracle<ClickHouseGlobalState> {

    private static final int TRIGGERS_PER_CHECK = 3;

    private final ClickHouseGlobalState state;
    private final ExpectedErrors errors = new ExpectedErrors();

    public ClickHouseQueryConditionCacheOracle(ClickHouseGlobalState state) {
        this.state = state;
        ClickHouseErrors.addExpectedExpressionErrors(errors);
        ClickHouseErrors.addSessionSettingsErrors(errors);
    }

    @Override
    public void check() throws SQLException {
        ClickHouseSchema schema = state.getSchema();
        List<ClickHouseTable> tables = schema.getRandomTableNonEmptyTables().getTables();
        if (tables.isEmpty()) {
            throw new IgnoreMeException();
        }
        ClickHouseTable table = tables.get((int) Randomly.getNotCachedInteger(0, tables.size()));
        if (table.isView()) {

            throw new IgnoreMeException();
        }
        ClickHouseTableReference tableRef = new ClickHouseTableReference(table, null);
        List<ClickHouseColumnReference> columns = tableRef.getColumnReferences();
        if (columns.size() < 2) {

            throw new IgnoreMeException();
        }

        ClickHouseExpressionGenerator gen = new ClickHouseExpressionGenerator(state).allowAggregates(false);
        gen.addColumns(columns);

        ClickHouseSelect baseline = new ClickHouseSelect();
        baseline.setFromClause(tableRef);
        baseline.setFetchColumns(List.of(columns.get(0)));
        baseline.setWhereClause(gen.generatePredicate());
        String baselineBody = ClickHouseVisitor.asString(baseline);
        if (Randomly.getBoolean()) {
            baselineBody += " ORDER BY " + ClickHouseVisitor.asString(columns.get(0)) + " ASC LIMIT "
                    + (1 + Randomly.getNotCachedInteger(0, 20));
        }

        if (!dropCache()) {
            throw new IgnoreMeException();
        }

        String truthQuery = baselineBody + " SETTINGS use_query_condition_cache = 0";
        List<String> truthResult = ComparatorHelper.getResultSetFirstColumnAsString(truthQuery, errors, state);

        List<String> triggerQueries = buildTriggerQueries(table, columns, gen);
        for (String trigger : triggerQueries) {

            try {
                new SQLQueryAdapter(trigger, errors, false).execute(state);
            } catch (SQLException e) {

            }
        }

        String cachedQuery = baselineBody + " SETTINGS use_query_condition_cache = 1, "
                + "use_query_condition_cache_for_top_k = 1";
        List<String> cachedResult = ComparatorHelper.getResultSetFirstColumnAsString(cachedQuery, errors, state);

        if (sameMultiset(truthResult, cachedResult)) {
            return;
        }

        if (!dropCache()) {
            throw new IgnoreMeException();
        }
        List<String> truthAfterDrop = ComparatorHelper.getResultSetFirstColumnAsString(truthQuery, errors, state);
        List<String> cachedAfterDrop = ComparatorHelper.getResultSetFirstColumnAsString(cachedQuery, errors, state);
        if (!sameMultiset(truthAfterDrop, cachedAfterDrop) || !sameMultiset(truthAfterDrop, truthResult)) {

            throw new IgnoreMeException();
        }

        throw new AssertionError(String.format(
                "query-condition-cache poisoning: after the trigger queries ran, the same read returned %d rows with "
                        + "the cache on and %d rows with the cache off, and the divergence disappeared after SYSTEM "
                        + "DROP QUERY CONDITION CACHE -- so the cache, not the data, produced the wrong answer.%n"
                        + "  cache off: %s%n  cache on:  %s%n  triggers:  %s%n  cache-off rows: %s%n"
                        + "  cache-on rows:  %s",
                truthResult.size(), cachedResult.size(), truthQuery, cachedQuery, triggerQueries, truthResult,
                cachedResult));
    }

    private static boolean sameMultiset(List<String> a, List<String> b) {
        if (a.size() != b.size()) {
            return false;
        }
        List<String> left = new ArrayList<>(a.size());
        List<String> right = new ArrayList<>(b.size());
        for (String s : a) {
            left.add(s == null ? "\\N" : s);
        }
        for (String s : b) {
            right.add(s == null ? "\\N" : s);
        }
        left.sort(String::compareTo);
        right.sort(String::compareTo);
        return left.equals(right);
    }

    private boolean dropCache() {
        try {
            return new SQLQueryAdapter("SYSTEM DROP QUERY CONDITION CACHE", errors, false).execute(state);
        } catch (Exception e) {
            return false;
        }
    }

    private List<String> buildTriggerQueries(ClickHouseTable table, List<ClickHouseColumnReference> columns,
            ClickHouseExpressionGenerator gen) {
        String tableName = table.getName();
        ClickHouseColumnReference prewhereCol = columns.get(0);
        ClickHouseColumnReference whereCol = columns.get(1);
        String prewhereColName = prewhereCol.getColumn().getName();
        String whereColName = whereCol.getColumn().getName();

        String prewhereLiteral = ClickHouseVisitor.asString(gen.generateConstant(prewhereCol.getColumn().getType()));
        String inLiteralA = ClickHouseVisitor.asString(gen.generateConstant(whereCol.getColumn().getType()));
        String inLiteralB = ClickHouseVisitor.asString(gen.generateConstant(whereCol.getColumn().getType()));

        List<String> triggers = new ArrayList<>();

        triggers.add(String.format("SELECT %s FROM %s PREWHERE %s = %s WHERE %s IN (%s, %s)", prewhereColName,
                tableName, prewhereColName, prewhereLiteral, whereColName, inLiteralA, inLiteralB));

        triggers.add(String.format("SELECT %s FROM %s PREWHERE %s IN (%s, %s) WHERE %s = %s", whereColName, tableName,
                whereColName, inLiteralA, inLiteralB, prewhereColName, prewhereLiteral));

        triggers.add(String.format("SELECT %s FROM %s PREWHERE %s = %s AND %s IN (%s, %s)", prewhereColName, tableName,
                prewhereColName, prewhereLiteral, whereColName, inLiteralA, inLiteralB));

        triggers.add(String.format(
                "SELECT a.%s FROM %s AS a INNER JOIN %s AS b ON a.%s = b.%s WHERE a.%s = %s SETTINGS "
                        + "query_plan_join_shard_by_pk_ranges = 1, join_algorithm = 'full_sorting_merge', "
                        + "max_threads = 4, use_query_condition_cache = 1",
                prewhereColName, tableName, tableName, prewhereColName, prewhereColName, prewhereColName,
                prewhereLiteral));

        triggers.add(String.format(
                "SELECT %s FROM cluster('default', currentDatabase(), %s) WHERE %s IN (%s, %s) SETTINGS "
                        + "enable_parallel_replicas = 1, max_parallel_replicas = 3, "
                        + "parallel_replicas_for_non_replicated_merge_tree = 1, use_query_condition_cache = 1",
                whereColName, tableName, whereColName, inLiteralA, inLiteralB));

        java.util.Collections.shuffle(triggers,
                new java.util.Random(Randomly.getNotCachedInteger(0, Integer.MAX_VALUE)));
        if (triggers.size() > TRIGGERS_PER_CHECK) {
            triggers = triggers.subList(0, TRIGGERS_PER_CHECK);
        }
        return triggers;
    }

}
