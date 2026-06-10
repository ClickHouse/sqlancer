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

/**
 * Emits CH mutation statements: ALTER TABLE ... UPDATE/DELETE (background, async) and the newer lightweight DELETE FROM
 * (synchronous, mark-only). Each one alters table contents -- the couldAffectSchema=false flag is correct because the
 * column shape doesn't change, but the background variant needs an explicit barrier before subsequent oracle queries to
 * avoid stale reads (see {@link ClickHouseMutationBarrier}).
 *
 * <p>
 * Workstream 9 of the coverage expansion plan.
 */
public final class ClickHouseMutationGenerator {

    private ClickHouseMutationGenerator() {
    }

    private enum MutationKind {
        ALTER_UPDATE, ALTER_DELETE, LIGHTWEIGHT_DELETE, LIGHTWEIGHT_UPDATE
    }

    // Columns a generated value expression can safely target: the existing expression generator
    // produces well-typed values only for plain primitives. Enum / composite / geo / JSON-family
    // reject arbitrary integer expressions with CANNOT_CONVERT_TYPE; aliased / materialised columns
    // cannot be assigned. Shared by ALTER_UPDATE and LIGHTWEIGHT_UPDATE.
    private static List<ClickHouseColumn> updatableColumns(List<ClickHouseColumn> cols) {
        return cols.stream().filter(c -> {
            sqlancer.clickhouse.ClickHouseType term = c.getType().getTypeTerm().unwrap();
            return term instanceof sqlancer.clickhouse.ClickHouseType.Primitive
                    || term instanceof sqlancer.clickhouse.ClickHouseType.Decimal
                    || term instanceof sqlancer.clickhouse.ClickHouseType.FixedString
                    || term instanceof sqlancer.clickhouse.ClickHouseType.DateTime64Type;
        }).collect(Collectors.toList());
    }

    // Render a single "<col> = <expr>" assignment for an UPDATE-family statement. The value
    // expression is generated over the *other* columns so the assignment is not a pure recursive
    // self-reference; this is not required by CH but keeps test variance higher. Falls back to all
    // columns when the target is the only column. Throws IgnoreMeException when no column is
    // assignable.
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

    // Mutation-analyzer coverage plan U2: mutation WHEREs draw from three arms so the PR #98884
    // analyzer mutation path is reachable by the general fleet, not just the dedicated oracle.
    //   - forced #106649 trigger arm (~10%): IN-subquery joining two derived tables with colliding
    //     projected names -- the exact filed shape, fired deterministically often;
    //   - full predicate path (~35%): generatePredicate() brings IN-subqueries, scalar subqueries,
    //     date transforms and typed-constant conjuncts into mutation WHEREs;
    //   - numeric path (~55%): the original generateExpressionWithColumns descent.
    // generatePredicate() reads the generator's internal column-ref state, which must be populated
    // via addColumns first -- naive wiring compiles but degenerates to constant-only predicates.
    // Probabilities are a U5-convergence tuning knob.
    private static ClickHouseExpression generateWhere(ClickHouseExpressionGenerator gen,
            List<ClickHouseColumnReference> colRefs) {
        int roll = (int) Randomly.getNotCachedInteger(0, 100);
        if (roll < 10) {
            ClickHouseExpression forced = gen.generateJoinedDerivedInPredicate(colRefs);
            if (forced != null) {
                return forced;
            }
            // Schema can't support the joined shape (no numeric columns); use the predicate path.
            roll = 10;
        }
        if (roll < 45) {
            gen.addColumns(colRefs);
            return gen.generatePredicate();
        }
        // Predicates over the table's columns; depth 3 keeps the strings tractable.
        return gen.generateExpressionWithColumns(colRefs, 3);
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
        ClickHouseExpression predicate = generateWhere(gen,
                cols.stream().map(c -> c.asColumnReference("")).collect(Collectors.toList()));

        String fqTable = state.getDatabaseName() + "." + table.getName();
        StringBuilder sb = new StringBuilder();
        switch (kind) {
        case ALTER_UPDATE:
            // Background (async) mutation. Registers in system.mutations; needs the barrier before
            // subsequent oracle reads (see waitForMutations).
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
            // Lightweight UPDATE (UPDATE ... SET, GA on CH HEAD). Unlike ALTER ... UPDATE this is
            // synchronous and -- on a table carrying enable_block_number_column /
            // enable_block_offset_column (the ~10% patch-eligible flavor from
            // ClickHouseTableGenerator) -- writes an unmerged *patch part* rather than rewriting the
            // whole part. Those live patch parts are the precondition for the
            // NOT_FOUND_COLUMN_IN_BLOCK / _part_offset read-path crash family (CH support #7912 ->
            // upstream #98227, #99023, #102904, #103910): any later ORDER BY ... LIMIT read under
            // lazy materialization touches readPatches. enable_lightweight_update=1 is the explicit
            // gate (no-op where lightweight UPDATE is already on by default). On a non-patch-eligible
            // table CH either rewrites this as a heavy mutation or rejects it -- both tolerated. We
            // deliberately do NOT run a mutation barrier / OPTIMIZE after this: the unmerged-patch
            // window is the whole point, so the next oracle iteration reads while patches are live.
            sb.append("UPDATE ").append(fqTable).append(" SET ").append(renderAssignment(state, gen, cols))
                    .append(" WHERE ").append(ClickHouseVisitor.asString(predicate))
                    .append(" SETTINGS enable_lightweight_update=1");
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
     * Polls {@code system.mutations} until in-flight mutations against {@code tableName} finish or the timeout elapses.
     * Background ALTER UPDATE/DELETE register an entry there; lightweight DELETE does not. The barrier is a no-op for
     * lightweight DELETE -- there's nothing to wait on.
     *
     * <p>
     * If the timeout fires, the helper does not throw; the next oracle iteration will see whatever state CH has applied
     * so far. The expected-errors list absorbs TIMEOUT_EXCEEDED in case a SELECT trips on a half-applied mutation
     * snapshot.
     *
     * @param state
     *            the global state providing the database connection
     * @param tableName
     *            the table whose pending mutations to wait for
     * @param timeoutSeconds
     *            the maximum time to wait, in seconds
     */
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
            // Polling failures are not bugs the fuzzer should report. Falling through means the
            // next oracle iteration starts; if state is inconsistent it surfaces as a tolerated
            // error there.
        }
    }
}
