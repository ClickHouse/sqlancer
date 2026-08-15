package sqlancer.clickhouse.gen;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.stream.Collectors;

import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.clickhouse.ClickHouseErrors;
import sqlancer.clickhouse.ClickHouseProvider.ClickHouseGlobalState;
import sqlancer.clickhouse.ClickHouseSchema.ClickHouseColumn;
import sqlancer.clickhouse.ClickHouseSchema.ClickHouseTable;
import sqlancer.clickhouse.ClickHouseVisitor;
import sqlancer.clickhouse.ast.ClickHouseColumnReference;
import sqlancer.clickhouse.ast.ClickHouseExpression;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;

public final class ClickHouseMutationGenerator {

    private ClickHouseMutationGenerator() {
    }

    private enum MutationKind {
        ALTER_UPDATE, ALTER_DELETE, LIGHTWEIGHT_DELETE, LIGHTWEIGHT_UPDATE
    }

    private static List<ClickHouseColumn> updatableColumns(List<ClickHouseColumn> cols) {
        return cols.stream().filter(c -> {
            sqlancer.clickhouse.ClickHouseType term = c.getType().getTypeTerm().unwrap();
            return term instanceof sqlancer.clickhouse.ClickHouseType.Primitive
                    || term instanceof sqlancer.clickhouse.ClickHouseType.Decimal
                    || term instanceof sqlancer.clickhouse.ClickHouseType.FixedString
                    || term instanceof sqlancer.clickhouse.ClickHouseType.DateTime64Type;
        }).collect(Collectors.toList());
    }

    private static String renderAssignment(ClickHouseGlobalState state, ClickHouseExpressionGenerator gen,
            List<ClickHouseColumn> cols) {
        List<ClickHouseColumn> updatable = updatableColumns(cols);
        if (updatable.isEmpty()) {
            throw new IgnoreMeException();
        }
        ClickHouseColumn updateCol = Randomly.fromList(updatable);
        List<ClickHouseColumn> others = cols.stream().filter(c -> c != updateCol).collect(Collectors.toList());
        ClickHouseExpression valueExpr = others.isEmpty()
                ? gen.generateExpressionWithColumns(
                        cols.stream().map(c -> c.asColumnReference("")).collect(Collectors.toList()), 2)
                : gen.generateExpressionWithColumns(
                        others.stream().map(c -> c.asColumnReference("")).collect(Collectors.toList()), 2);
        return updateCol.getName() + " = " + ClickHouseVisitor.asString(valueExpr);
    }

    private static ClickHouseExpression generateWhere(ClickHouseExpressionGenerator gen,
            List<ClickHouseColumnReference> colRefs) {
        int roll = (int) Randomly.getNotCachedInteger(0, 100);
        if (roll < 10) {
            ClickHouseExpression forced = gen.generateJoinedDerivedInPredicate(colRefs);
            if (forced != null) {
                return forced;
            }

            roll = 10;
        }
        if (roll < 45) {
            gen.addColumns(colRefs);
            return gen.generatePredicate();
        }

        return gen.generateExpressionWithColumns(colRefs, 3);
    }

    public static SQLQueryAdapter getQuery(ClickHouseGlobalState state) {
        List<ClickHouseTable> tables = state.getSchema().getDatabaseTablesWithoutViews();
        if (tables.isEmpty()) {
            throw new IgnoreMeException();
        }
        ClickHouseTable table = Randomly.fromList(tables);
        MutationKind kind = Randomly.fromOptions(MutationKind.values());

        ClickHouseExpressionGenerator gen = new ClickHouseExpressionGenerator(state).allowAggregates(false);
        List<ClickHouseColumn> cols = table.getColumns();
        ClickHouseExpression predicate = generateWhere(gen,
                cols.stream().map(c -> c.asColumnReference("")).collect(Collectors.toList()));

        String fqTable = state.getDatabaseName() + "." + table.getName();
        StringBuilder sb = new StringBuilder();
        switch (kind) {
        case ALTER_UPDATE:

            sb.append("ALTER TABLE ").append(fqTable).append(" UPDATE ").append(renderAssignment(state, gen, cols))
                    .append(" WHERE ").append(ClickHouseVisitor.asString(predicate));
            break;
        case ALTER_DELETE:
            sb.append("ALTER TABLE ").append(fqTable).append(" DELETE WHERE ")
                    .append(ClickHouseVisitor.asString(predicate));
            break;
        case LIGHTWEIGHT_DELETE:
            sb.append("DELETE FROM ").append(fqTable).append(" WHERE ").append(ClickHouseVisitor.asString(predicate));
            break;
        case LIGHTWEIGHT_UPDATE:

            sb.append("UPDATE ").append(fqTable).append(" SET ").append(renderAssignment(state, gen, cols))
                    .append(" WHERE ").append(ClickHouseVisitor.asString(predicate))
                    .append(" SETTINGS enable_lightweight_update=1");
            break;
        default:
            throw new AssertionError(kind);
        }

        ExpectedErrors errors = ExpectedErrors.newErrors().with(ClickHouseErrors.getExpectedExpressionErrors())
                .with(ClickHouseErrors.getMutationErrors())

                .with(ClickHouseErrors.getKnownOpenMutationAnalyzerBugs()).build();

        return new SQLQueryAdapter(sb.toString(), errors, false);
    }

    public static void waitForMutations(ClickHouseGlobalState state, String tableName, int timeoutSeconds) {
        try {
            sqlancer.SQLConnection con = state.getConnection();
            long deadline = System.nanoTime() + (long) timeoutSeconds * 1_000_000_000L;
            while (System.nanoTime() < deadline) {
                try (Statement s = con.createStatement();
                        ResultSet rs = s.executeQuery("SELECT count() FROM system.mutations WHERE database = '"
                                + state.getDatabaseName() + "' AND table = '" + tableName + "' AND is_done = 0")) {
                    if (rs.next() && rs.getLong(1) == 0L) {
                        return;
                    }
                }
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        } catch (SQLException e) {

        }
    }
}
