package sqlancer.clickhouse.oracle.prewhere;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import sqlancer.ComparatorHelper;
import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.clickhouse.ClickHouseErrors;
import sqlancer.clickhouse.ClickHouseProvider.ClickHouseGlobalState;
import sqlancer.clickhouse.ClickHouseSchema;
import sqlancer.clickhouse.ClickHouseSchema.ClickHouseColumn;
import sqlancer.clickhouse.ClickHouseSchema.ClickHouseTable;
import sqlancer.clickhouse.ClickHouseToStringVisitor;
import sqlancer.clickhouse.ast.ClickHouseColumnReference;
import sqlancer.clickhouse.ast.ClickHouseExpression;
import sqlancer.clickhouse.gen.ClickHouseExpressionGenerator;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.ExpectedErrors;

public class ClickHousePrewhereEquivalenceOracle implements TestOracle<ClickHouseGlobalState> {

    private static final int DIFF_LIMIT = 20;

    private final ClickHouseGlobalState state;
    private final ExpectedErrors errors = new ExpectedErrors();

    public ClickHousePrewhereEquivalenceOracle(ClickHouseGlobalState state) {
        this.state = state;
        ClickHouseErrors.addExpectedExpressionErrors(errors);
        ClickHouseErrors.addSessionSettingsErrors(errors);
        errors.add("ILLEGAL_PREWHERE");
        errors.add("PREWHERE");
    }

    @Override
    public void check() throws SQLException {
        if (!state.getClickHouseOptions().prewhereEquivalenceOracle) {
            throw new IgnoreMeException();
        }

        ClickHouseSchema schema = state.getSchema();
        List<ClickHouseTable> tables = schema.getRandomTableNonEmptyTables().getTables().stream()
                .filter(t -> !t.isView() && t.getEngine().contains("MergeTree")).collect(Collectors.toList());
        if (tables.isEmpty()) {
            throw new IgnoreMeException();
        }
        ClickHouseTable table = tables.get((int) Randomly.getNotCachedInteger(0, tables.size()));

        List<ClickHouseColumn> physicalColumns = table.getColumns().stream()
                .filter(c -> !c.isAlias() && !c.isMaterialized()).collect(Collectors.toList());
        if (physicalColumns.isEmpty()) {
            throw new IgnoreMeException();
        }

        List<ClickHouseColumnReference> columnRefs = physicalColumns.stream()
                .map(c -> c.asColumnReference(table.getName())).collect(Collectors.toList());

        ClickHouseExpressionGenerator gen = new ClickHouseExpressionGenerator(state).allowAggregates(false);
        gen.addColumns(columnRefs);
        ClickHouseExpression predicateExpr = gen.generatePredicate();
        String predicate = ClickHouseToStringVisitor.asString(predicateExpr);

        String projection = physicalColumns.stream().map(c -> "`" + c.getName() + "`")
                .collect(Collectors.joining(", ", "toString(tuple(", "))"));
        String base = "SELECT " + projection + " FROM " + table.getName();

        String whereQuery = base + " WHERE " + predicate;
        String prewhereQuery = base + " PREWHERE " + predicate;
        String movePrewhereOffQuery = base + " WHERE " + predicate + " SETTINGS optimize_move_to_prewhere = 0";

        List<String> whereRows = ComparatorHelper.getResultSetFirstColumnAsString(whereQuery, errors, state);
        List<String> prewhereRows = ComparatorHelper.getResultSetFirstColumnAsString(prewhereQuery, errors, state);
        List<String> movePrewhereOffRows = ComparatorHelper.getResultSetFirstColumnAsString(movePrewhereOffQuery,
                errors, state);

        assertMultisetsEqual(whereRows, prewhereRows, whereQuery, prewhereQuery, "WHERE-vs-PREWHERE");
        assertMultisetsEqual(whereRows, movePrewhereOffRows, whereQuery, movePrewhereOffQuery,
                "WHERE-vs-move_to_prewhere=0");
    }

    static void assertMultisetsEqual(List<String> leftRows, List<String> rightRows, String leftSql, String rightSql,
            String label) {
        List<String> diff = multisetDiff(leftRows, rightRows, DIFF_LIMIT);
        if (diff.isEmpty()) {
            return;
        }
        throw new AssertionError(String.format(
                "prewhere-equivalence multiset mismatch (%s): %d rows on left vs %d rows on right.%n"
                        + "left:  %s%nright: %s%nfirst %d differing entries (value (+count side)): %s",
                label, leftRows.size(), rightRows.size(), leftSql, rightSql, diff.size(), diff));
    }

    static List<String> multisetDiff(List<String> leftRows, List<String> rightRows, int limit) {
        Map<String, Long> counts = new TreeMap<>();
        for (String s : leftRows) {
            counts.merge(s == null ? "\\N" : s, 1L, Long::sum);
        }
        for (String s : rightRows) {
            counts.merge(s == null ? "\\N" : s, -1L, Long::sum);
        }
        List<String> diff = new ArrayList<>();
        for (Map.Entry<String, Long> e : counts.entrySet()) {
            if (e.getValue() == 0) {
                continue;
            }
            if (diff.size() >= limit) {
                break;
            }
            long c = e.getValue();
            diff.add(e.getKey() + " (+" + Math.abs(c) + " " + (c > 0 ? "left" : "right") + ")");
        }
        return diff;
    }
}
