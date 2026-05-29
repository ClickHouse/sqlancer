package sqlancer.clickhouse.oracle.materialize;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

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
import sqlancer.common.query.SQLQueryAdapter;

/**
 * Cross-statement subquery / predicate-materialization differential oracle.
 *
 * <p>
 * This oracle targets the wrong-result family that external fuzzing (GitHub user AnotherYx) surfaced en masse on
 * v26.5.1.882 -- ClickHouse#106080, #106082, #106083, #106084, #105717 and #105718 -- using a single structural
 * technique that none of the existing ClickHouse oracles in this fork exercise: take a query, <strong>persist</strong>
 * an intermediate result-set (a derived table / one filter step) into a physical temp table, then run the rest of the
 * query against that temp table, and compare to the all-in-one-query result.
 *
 * <p>
 * Crossing the temp-table boundary reclassifies the materialized side as a plain physical scan, which <em>disables</em>
 * the optimizer rule that is actually buggy (OR-splitting, "predicate_temp_N" predicate materialization, scalar-subquery
 * pushdown, RIGHT-JOIN default-value handling, ...). The existing {@code KeyConditionOracle} wraps columns in
 * {@code materialize()} <strong>within</strong> a single query -- that is a different transformation: it leaves those
 * optimizer passes enabled and so cannot reproduce this divergence. The boundary here is a CREATE TABLE / DROP TABLE
 * pair, not an in-query function wrap.
 *
 * <p>
 * The two variants compared per iteration are:
 *
 * <ol>
 * <li><strong>Inline (baseline)</strong>:
 * {@code SELECT c0 FROM ( SELECT <cols> FROM T WHERE P_inner ) AS sub WHERE P_outer} -- the optimizer is free to fold
 * {@code P_outer} back through the derived table and into the base-table scan, applying whichever rewrite rule is under
 * test.</li>
 * <li><strong>Materialized</strong>:
 * {@code CREATE TABLE tmp ENGINE = <Memory|Log> AS SELECT <cols> FROM T WHERE P_inner;} then
 * {@code SELECT c0 FROM tmp WHERE P_outer;} -- the inner result is frozen into a physical table, so {@code P_outer}
 * evaluates against a plain scan with no pushdown opportunity across the boundary.</li>
 * </ol>
 *
 * <p>
 * Both variants must yield the same row multiset: the temp table is a value-preserving copy of the inner result and
 * {@code P_outer} is identical, so the only thing that differs is whether the cross-boundary optimizer rewrite fired.
 * Any mismatch is a candidate wrong-result bug of exactly the class AnotherYx found.
 *
 * <p>
 * Soundness notes:
 *
 * <ul>
 * <li>This is a row-passthrough differential -- it never aggregates (no SUM/AVG), so the float aggregation-order
 * divergence class that bites the TLPAggregate / Parallelism oracles does not apply here.</li>
 * <li>Comparison is by multiset via {@link ComparatorHelper#getResultSetFirstColumnAsString} +
 * {@link ComparatorHelper#assumeResultSetsAreEqual} (SET/float-canonicalised), so row <em>order</em> is never
 * significant; the oracle deliberately emits no ORDER BY.</li>
 * <li>Memory vs Log engine choice only changes physical storage, not row identity, so randomising it is safe and widens
 * the storage-path coverage (Log goes through the on-disk mark/granule reader, Memory does not).</li>
 * <li>All column identifiers are rendered as bare names ({@code c0}, {@code c1}, ...): the table generator names columns
 * {@code c<n>} (see {@code ClickHouseCommon.createColumnName}), and bare names resolve identically inside the inner
 * scan over {@code T}, across the derived-table boundary, and against the {@code tmp} copy -- a table-qualified name
 * ({@code T.c0}) would be invalid against {@code sub} / {@code tmp}.</li>
 * </ul>
 */
public class ClickHouseSubqueryMaterializeOracle implements TestOracle<ClickHouseGlobalState> {

    private static final AtomicLong TMP_COUNTER = new AtomicLong();

    private final ClickHouseGlobalState state;
    private final ExpectedErrors errors = new ExpectedErrors();

    public ClickHouseSubqueryMaterializeOracle(ClickHouseGlobalState state) {
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
        // The columns as they live on the base table -- their names (c0, c1, ...) are reused below
        // to build bare-name references that are valid in all three identifier contexts (inner scan
        // over T, the derived table `sub`, and the materialized copy `tmp`).
        List<ClickHouseColumn> tableColumns = table.getColumns();
        if (tableColumns.isEmpty()) {
            throw new IgnoreMeException();
        }

        // Build a parallel set of bare-name column references. We deliberately do NOT use
        // ClickHouseTableReference.getColumnReferences(): those carry the owning table, so the
        // visitor renders them as `T.c0` (getFullQualifiedName). That qualified form is invalid the
        // moment we reference the column through `sub` or `tmp`. Re-wrapping each column with a
        // null table makes the visitor emit the bare `c0` token, which resolves correctly in every
        // context this oracle uses.
        List<ClickHouseColumnReference> bareColumns = new ArrayList<>(tableColumns.size());
        for (ClickHouseColumn c : tableColumns) {
            ClickHouseColumn bare = new ClickHouseColumn(c.getName(), c.getType(), false, false, null);
            bareColumns.add(new ClickHouseColumnReference(bare, null, null));
        }

        // Two independent predicates over the same bare-name column set. P_inner filters the inner
        // scan (and thus what gets materialised); P_outer filters the outer/temp-table read. Two
        // separate generatePredicate() calls give the cross-boundary optimizer two distinct
        // predicates to (mis-)combine, which is exactly where the OR-split / predicate-temp rewrites
        // live.
        ClickHouseExpressionGenerator gen = new ClickHouseExpressionGenerator(state).allowAggregates(false);
        gen.addColumns(bareColumns);
        ClickHouseExpression pInner = gen.generatePredicate();
        ClickHouseExpression pOuter = gen.generatePredicate();

        String col0 = bareColumns.get(0).getColumn().getName();
        // Project ALL base columns into the inner SELECT / temp table so that P_outer (which may
        // reference any column) resolves against both `sub` and `tmp`. Projecting only c0 would make
        // a P_outer that touches c1 a generator slip rather than a comparison.
        String projection = renderColumnList(bareColumns);
        String pInnerSql = ClickHouseToStringVisitor.asString(pInner);
        String pOuterSql = ClickHouseToStringVisitor.asString(pOuter);

        String fqSource = state.getDatabaseName() + "." + table.getName();
        // Inner derived-table query. The inner scan reads the real base table T; `sub` exposes the
        // projected bare-name columns to the outer WHERE.
        String innerSelect = "SELECT " + projection + " FROM " + fqSource + " WHERE " + pInnerSql;
        String inlineQuery = "SELECT " + col0 + " FROM (" + innerSelect + ") AS sub WHERE " + pOuterSql;

        // Materialized variant. Memory and Log are both plain row stores with no merge-time dedupe
        // and no PARTITION/ORDER pruning, so the temp copy is a faithful value-preserving snapshot
        // of the inner result -- the only structural difference from the inline form is the
        // CREATE/DROP boundary that defeats cross-query pushdown.
        String engine = Randomly.getBoolean() ? "Memory" : "Log";
        String tmpName = tmpName(table.getName());
        String fqTmp = state.getDatabaseName() + "." + tmpName;
        String dropTmp = "DROP TABLE IF EXISTS " + fqTmp + " SYNC";
        String createTmp = "CREATE TABLE " + fqTmp + " ENGINE = " + engine + " AS " + innerSelect;
        String tmpSelect = "SELECT " + col0 + " FROM " + fqTmp + " WHERE " + pOuterSql;

        try {
            // Mirror PartitionMirrorOracle's logging discipline: writeCurrent feeds the live
            // -cur.log; logStatement feeds state.getStatements(), which is what gets serialised into
            // the persistent database<N>.log on AssertionError. Without the second call the temp
            // CREATE/DROP would be missing from saved reproducers and tmpSelect would reference a
            // table that does not exist on replay.
            if (state.getOptions().logEachSelect()) {
                state.getLogger().writeCurrent(dropTmp);
                state.getLogger().writeCurrent(createTmp);
                state.getLogger().writeCurrent(tmpSelect);
                state.getLogger().writeCurrent(dropTmp);
                state.getState().logStatement(dropTmp);
                state.getState().logStatement(createTmp);
                state.getState().logStatement(tmpSelect);
                state.getState().logStatement(dropTmp);
            }
            // Setup statements are not the subject of the oracle. reportException=false turns any
            // server-side rejection (a predicate the inner CREATE AS cannot type-check, a race with
            // another worker on the database, a tolerated MEMORY_LIMIT_EXCEEDED, ...) into a
            // return-false rather than an AssertionError that would kill the worker thread. The
            // oracle only asserts on the SELECT diff below.
            new SQLQueryAdapter(dropTmp, errors, true).execute(state, false);
            boolean created = new SQLQueryAdapter(createTmp, errors, true).execute(state, false);
            if (!created) {
                // A predicate that the server rejects at CREATE-AS time is a generator slip, not a
                // wrong-result bug. Drop the iteration.
                throw new IgnoreMeException();
            }
        } catch (SQLException e) {
            // Defensive -- execute(state, false) turns server-side errors into return-false, but
            // keep the catch for connection-level failures so they don't surface as raw reproducers.
            safeDrop(dropTmp);
            throw new IgnoreMeException();
        }

        try {
            List<String> inlineRows;
            try {
                inlineRows = ComparatorHelper.getResultSetFirstColumnAsString(inlineQuery, errors, state);
            } catch (IgnoreMeException e) {
                // The inline query references the same predicates; if the server rejects it, the
                // predicate pair is the slip, not a CH bug.
                throw e;
            }
            List<String> tmpRows = ComparatorHelper.getResultSetFirstColumnAsString(tmpSelect, errors, state);
            ComparatorHelper.assumeResultSetsAreEqual(inlineRows, tmpRows, inlineQuery, List.of(tmpSelect), state);
        } finally {
            safeDrop(dropTmp);
        }
    }

    // Render a comma-separated bare-name projection list. We render each column reference through
    // the standard visitor (which emits the bare `c<n>` token because the wrapped column has a null
    // table) so any future change to identifier quoting stays centralised in the visitor.
    private String renderColumnList(List<ClickHouseColumnReference> columns) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(ClickHouseToStringVisitor.asString(columns.get(i)));
        }
        return sb.toString();
    }

    private void safeDrop(String dropTmp) {
        try {
            new SQLQueryAdapter(dropTmp, errors, true).execute(state, false);
        } catch (SQLException ignored) {
            // Best-effort -- the next database recycle will clean up regardless.
        }
    }

    private String tmpName(String source) {
        return "smat_" + source + "_" + TMP_COUNTER.incrementAndGet();
    }
}
