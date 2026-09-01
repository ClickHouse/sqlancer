package sqlancer.clickhouse.gen;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.clickhouse.ClickHouseErrors;
import sqlancer.clickhouse.ClickHouseProvider.ClickHouseGlobalState;
import sqlancer.clickhouse.ClickHouseSchema.ClickHouseColumn;
import sqlancer.clickhouse.ClickHouseSchema.ClickHouseTable;
import sqlancer.clickhouse.ClickHouseToStringVisitor;
import sqlancer.clickhouse.ast.ClickHouseExpression;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;

public final class ClickHouseViewGenerator {

    private static final AtomicLong VIEW_COUNTER = new AtomicLong();
    private static final int MAX_VIEWS_PER_DATABASE = 3;

    private ClickHouseViewGenerator() {
    }

    public static SQLQueryAdapter getQuery(ClickHouseGlobalState state) {
        if (!state.getClickHouseOptions().persistentViewEmission) {
            throw new IgnoreMeException();
        }
        if (state.getSchema().getViews().size() >= MAX_VIEWS_PER_DATABASE) {
            throw new IgnoreMeException();
        }
        List<ClickHouseTable> tables = state.getSchema().getDatabaseTablesWithoutViews().stream()
                .filter(ClickHouseTable::isStableForRepeatedReads).collect(Collectors.toList());
        if (tables.isEmpty()) {
            throw new IgnoreMeException();
        }
        ClickHouseTable table = Randomly.fromList(tables);
        List<ClickHouseColumn> readable = table.getColumns();
        if (readable.isEmpty()) {
            throw new IgnoreMeException();
        }

        String db = state.getDatabaseName();
        String name = "v" + VIEW_COUNTER.incrementAndGet();
        StringBuilder sb = new StringBuilder("CREATE VIEW IF NOT EXISTS ").append(db).append('.').append(name)
                .append(" AS SELECT ");
        if (Randomly.getBoolean()) {
            sb.append("*");
        } else {
            sb.append(readable.stream().map(ClickHouseColumn::getName).collect(Collectors.joining(", ")));
        }
        sb.append(" FROM ").append(db).append('.').append(table.getName());
        if (Randomly.getBooleanWithRatherLowProbability()) {
            ClickHouseExpressionGenerator gen = new ClickHouseExpressionGenerator(state).allowAggregates(false);
            gen.addColumns(readable.stream().map(c -> c.asColumnReference("")).collect(Collectors.toList()));
            ClickHouseExpression predicate = gen.generatePredicate();
            sb.append(" WHERE ").append(ClickHouseToStringVisitor.asString(predicate));
        }

        ExpectedErrors errors = new ExpectedErrors();
        ClickHouseErrors.addExpectedExpressionErrors(errors);
        errors.add("UNKNOWN_TABLE");
        errors.add("TABLE_ALREADY_EXISTS");
        errors.add("ACCESS_DENIED");
        errors.add("Not enough privileges");
        errors.add("UNSUPPORTED_METHOD");
        return new SQLQueryAdapter(sb.toString(), errors, true);
    }
}
