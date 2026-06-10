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

/**
 * Cross-query oracle for the ClickHouse query-condition cache.
 *
 * <p>
 * The query-condition cache (controlled by {@code use_query_condition_cache}, default on in 26.x) memoises per-query
 * filter evaluations so that subsequent queries with the same filter shape skip data parts cheaply. A bug in that path
 * -- of which ClickHouse#104781 is the canonical example -- lets one query's cache entry poison a later, structurally
 * different query, silently under-counting its results.
 *
 * <p>
 * Existing single-query oracles (TLP, NoREC, SEMR) cannot reach this class of bug because the poisoning is cross-query:
 * the *baseline* SELECT is correct on its own, and only diverges from truth after an unrelated *trigger* query has been
 * executed against the same connection (and shared server-side cache).
 *
 * <p>
 * Protocol per check():
 *
 * <ol>
 * <li>Drop the server-side query-condition cache so we start clean.</li>
 * <li>Run the baseline {@code SELECT count() FROM t WHERE p} with {@code use_query_condition_cache=0} to record the
 * "truth" cardinality independent of the cache.</li>
 * <li>Run a small number of trigger queries with the cache enabled. Triggers are deliberately shaped like the #104781
 * reproducer: {@code PREWHERE eq + WHERE col IN (literals)}. Each trigger executes through the cache code path and
 * writes a cache entry.</li>
 * <li>Re-run the baseline with the cache enabled. It must return the same result as step 2; otherwise the cache has
 * been poisoned by a trigger.</li>
 * </ol>
 *
 * <p>
 * The oracle is intentionally tolerant of trigger-query errors -- a malformed or type-mismatched trigger is a generator
 * slip, not a bug -- but it does NOT tolerate baseline-mismatches; those are exactly the bug we're hunting.
 */
public class ClickHouseQueryConditionCacheOracle implements TestOracle<ClickHouseGlobalState> {

    // Number of trigger queries to fire between the two baseline runs. Each trigger writes at most
    // one cache entry; more triggers means a higher chance of hitting the poisoning code path but
    // linearly more cost. Three is a balance: enough to amortise the two baseline runs against
    // multiple trigger shapes, low enough that an idle multi-thread burn-in doesn't slow down.
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
        ClickHouseTableReference tableRef = new ClickHouseTableReference(table, null);
        List<ClickHouseColumnReference> columns = tableRef.getColumnReferences();
        if (columns.size() < 2) {
            // Triggers need two distinct columns (one for PREWHERE, one for WHERE IN) to reproduce
            // the #104781 shape. Single-column tables would degenerate to PREWHERE+WHERE on the
            // same column, which is a different code path and would dilute the signal here.
            throw new IgnoreMeException();
        }

        ClickHouseExpressionGenerator gen = new ClickHouseExpressionGenerator(state).allowAggregates(false);
        gen.addColumns(columns);

        // Baseline: SELECT first-column FROM t WHERE <predicate>. We compare full first-column
        // result sets, not just count(), so the oracle catches under-counts AND value corruption.
        ClickHouseSelect baseline = new ClickHouseSelect();
        baseline.setFromClause(tableRef);
        baseline.setFetchColumns(List.of(columns.get(0)));
        baseline.setWhereClause(gen.generatePredicate());
        String baselineBody = ClickHouseVisitor.asString(baseline);

        String dropCache = "SYSTEM DROP QUERY CONDITION CACHE";
        // Cache-drop failures are absorbed and the iteration is skipped -- typically permissions or
        // a build that has the cache compiled out. Either way the oracle has nothing to compare.
        try {
            new SQLQueryAdapter(dropCache, errors, false).execute(state);
        } catch (Exception e) {
            throw new IgnoreMeException();
        }

        String truthQuery = baselineBody + " SETTINGS use_query_condition_cache = 0";
        List<String> truthResult = ComparatorHelper.getResultSetFirstColumnAsString(truthQuery, errors, state);

        List<String> triggerQueries = buildTriggerQueries(table, columns, gen);
        for (String trigger : triggerQueries) {
            // Trigger queries are NOT the oracle's subject -- only baseline divergence is a bug.
            // The trigger's only job is to execute and (maybe) write a poisoned cache entry. Any
            // SQLException raised by the trigger (type mismatch, schema mismatch, malformed IN
            // literal, JDBC format constraint) means the trigger never reached the cache, which
            // is uninteresting -- but the connection is still healthy because SQLQueryAdapter's
            // internalExecute closes the statement before propagating. Absorbing all SQLExceptions
            // here is safe and keeps the oracle focused.
            try {
                new SQLQueryAdapter(trigger, errors, false).execute(state);
            } catch (SQLException e) {
                // Trigger failure -- continue to the next trigger.
            }
        }

        String cachedQuery = baselineBody + " SETTINGS use_query_condition_cache = 1";
        List<String> cachedResult = ComparatorHelper.getResultSetFirstColumnAsString(cachedQuery, errors, state);

        ComparatorHelper.assumeResultSetsAreEqual(truthResult, cachedResult, truthQuery, List.of(cachedQuery), state);
    }

    // Build a list of bug-baiting trigger queries against `table`. We aim for the shape
    // documented in ClickHouse#104781: PREWHERE on one column with equality + WHERE on a different
    // column with IN against a literal list. We also throw in one swapped variant and one
    // combined-PREWHERE variant to exercise neighbouring cache-key code paths.
    //
    // The IN-list values are dummy literals; the bug fires regardless of whether the values exist
    // in the table, which is the whole point of the #104781 reporter's observation.
    private List<String> buildTriggerQueries(ClickHouseTable table, List<ClickHouseColumnReference> columns,
            ClickHouseExpressionGenerator gen) {
        String tableName = table.getName();
        ClickHouseColumnReference prewhereCol = columns.get(0);
        ClickHouseColumnReference whereCol = columns.get(1);
        String prewhereColName = prewhereCol.getColumn().getName();
        String whereColName = whereCol.getColumn().getName();
        // Constants are typed to the column we're filtering on. We render via the expression
        // generator's generateConstant so wrapper types (Nullable, LowCardinality) get the right
        // textual form -- a raw "1" against a String column triggers a type error which would
        // unnecessarily absorb iterations.
        String prewhereLiteral = ClickHouseVisitor.asString(gen.generateConstant(prewhereCol.getColumn().getType()));
        String inLiteralA = ClickHouseVisitor.asString(gen.generateConstant(whereCol.getColumn().getType()));
        String inLiteralB = ClickHouseVisitor.asString(gen.generateConstant(whereCol.getColumn().getType()));

        List<String> triggers = new ArrayList<>();
        // Canonical #104781 shape.
        triggers.add(String.format("SELECT %s FROM %s PREWHERE %s = %s WHERE %s IN (%s, %s)", prewhereColName,
                tableName, prewhereColName, prewhereLiteral, whereColName, inLiteralA, inLiteralB));
        // Swapped shape -- in the original report this DOES NOT poison; we run it anyway because
        // an adjacent code-path regression would flip that immunity into a new bug.
        triggers.add(String.format("SELECT %s FROM %s PREWHERE %s IN (%s, %s) WHERE %s = %s", whereColName, tableName,
                whereColName, inLiteralA, inLiteralB, prewhereColName, prewhereLiteral));
        // Combined-PREWHERE shape -- known no-poison in #104781; same rationale for inclusion.
        triggers.add(String.format("SELECT %s FROM %s PREWHERE %s = %s AND %s IN (%s, %s)", prewhereColName, tableName,
                prewhereColName, prewhereLiteral, whereColName, inLiteralA, inLiteralB));
        // Truncate to TRIGGERS_PER_CHECK; in case future variants are added beyond the cap the
        // canonical shape is always first so it is always exercised.
        if (triggers.size() > TRIGGERS_PER_CHECK) {
            triggers = triggers.subList(0, TRIGGERS_PER_CHECK);
        }
        return triggers;
    }

}
