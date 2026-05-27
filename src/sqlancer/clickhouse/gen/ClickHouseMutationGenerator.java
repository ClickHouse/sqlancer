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
import sqlancer.clickhouse.ast.ClickHouseExpression;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;

/**
 * Emits CH mutation statements: ALTER TABLE ... UPDATE/DELETE (background, async) and the newer
 * lightweight DELETE FROM (synchronous, mark-only). Each one alters table contents -- the
 * couldAffectSchema=false flag is correct because the column shape doesn't change, but the
 * background variant needs an explicit barrier before subsequent oracle queries to avoid stale
 * reads (see {@link ClickHouseMutationBarrier}).
 *
 * <p>Workstream 9 of the coverage expansion plan.
 */
public final class ClickHouseMutationGenerator {

    private ClickHouseMutationGenerator() {
    }

    private enum MutationKind {
        ALTER_UPDATE, ALTER_DELETE, LIGHTWEIGHT_DELETE
    }

    public static SQLQueryAdapter getQuery(ClickHouseGlobalState state) {
        List<ClickHouseTable> tables = state.getSchema().getDatabaseTables();
        if (tables.isEmpty()) {
            throw new IgnoreMeException();
        }
        ClickHouseTable table = Randomly.fromList(tables);
        MutationKind kind = Randomly.fromOptions(MutationKind.values());

        ClickHouseExpressionGenerator gen = new ClickHouseExpressionGenerator(state).allowAggregates(false);
        List<ClickHouseColumn> cols = table.getColumns();
        // Predicates over the table's columns; depth 3 keeps the strings tractable.
        ClickHouseExpression predicate = gen.generateExpressionWithColumns(
                cols.stream().map(c -> c.asColumnReference(null)).collect(Collectors.toList()), 3);

        String fqTable = state.getDatabaseName() + "." + table.getName();
        StringBuilder sb = new StringBuilder();
        switch (kind) {
        case ALTER_UPDATE:
            ClickHouseColumn updateCol = Randomly.fromList(cols);
            // Use an expression generator over the *other* columns so the assignment can't be a
            // pure recursive reference; this isn't strictly required by CH but keeps test variance
            // higher.
            List<ClickHouseColumn> others = cols.stream().filter(c -> c != updateCol).collect(Collectors.toList());
            ClickHouseExpression valueExpr = others.isEmpty()
                    ? gen.generateExpressionWithColumns(
                            cols.stream().map(c -> c.asColumnReference(null)).collect(Collectors.toList()), 2)
                    : gen.generateExpressionWithColumns(
                            others.stream().map(c -> c.asColumnReference(null)).collect(Collectors.toList()), 2);
            sb.append("ALTER TABLE ").append(fqTable).append(" UPDATE ").append(updateCol.getName()).append(" = ")
                    .append(ClickHouseVisitor.asString(valueExpr)).append(" WHERE ")
                    .append(ClickHouseVisitor.asString(predicate));
            break;
        case ALTER_DELETE:
            sb.append("ALTER TABLE ").append(fqTable).append(" DELETE WHERE ")
                    .append(ClickHouseVisitor.asString(predicate));
            break;
        case LIGHTWEIGHT_DELETE:
            sb.append("DELETE FROM ").append(fqTable).append(" WHERE ").append(ClickHouseVisitor.asString(predicate));
            break;
        default:
            throw new AssertionError(kind);
        }

        ExpectedErrors errors = ExpectedErrors.newErrors().with(ClickHouseErrors.getExpectedExpressionErrors())
                .with(ClickHouseErrors.getMutationErrors()).build();
        // couldAffectSchema=false: mutations alter row contents, not column shape. The barrier
        // helper (invoked separately from the action handler) is what serialises observability.
        return new SQLQueryAdapter(sb.toString(), errors, false);
    }

    /**
     * Polls {@code system.mutations} until in-flight mutations against {@code tableName} finish or
     * the timeout elapses. Background ALTER UPDATE/DELETE register an entry there; lightweight
     * DELETE does not. The barrier is a no-op for lightweight DELETE -- there's nothing to wait
     * on.
     *
     * <p>If the timeout fires, the helper does not throw; the next oracle iteration will see
     * whatever state CH has applied so far. The expected-errors list absorbs TIMEOUT_EXCEEDED in
     * case a SELECT trips on a half-applied mutation snapshot.
     */
    public static void waitForMutations(ClickHouseGlobalState state, String tableName, int timeoutSeconds) {
        try {
            sqlancer.SQLConnection con = state.getConnection();
            long deadline = System.nanoTime() + (long) timeoutSeconds * 1_000_000_000L;
            while (System.nanoTime() < deadline) {
                try (Statement s = con.createStatement(); ResultSet rs = s.executeQuery(
                        "SELECT count() FROM system.mutations WHERE database = '" + state.getDatabaseName()
                                + "' AND table = '" + tableName + "' AND is_done = 0")) {
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
            // Polling failures are not bugs the fuzzer should report. Falling through means the
            // next oracle iteration starts; if state is inconsistent it surfaces as a tolerated
            // error there.
        }
    }
}
