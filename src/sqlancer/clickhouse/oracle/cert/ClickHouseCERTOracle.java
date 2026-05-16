package sqlancer.clickhouse.oracle.cert;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.clickhouse.data.ClickHouseDataType;

import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.clickhouse.ClickHouseErrors;
import sqlancer.clickhouse.ClickHouseProvider.ClickHouseGlobalState;
import sqlancer.clickhouse.ClickHouseSchema.ClickHouseColumn;
import sqlancer.clickhouse.ClickHouseSchema.ClickHouseTable;
import sqlancer.clickhouse.ClickHouseVisitor;
import sqlancer.clickhouse.ast.ClickHouseBinaryLogicalOperation;
import sqlancer.clickhouse.ast.ClickHouseBinaryLogicalOperation.ClickHouseBinaryLogicalOperator;
import sqlancer.clickhouse.ast.ClickHouseColumnReference;
import sqlancer.clickhouse.ast.ClickHouseExpression;
import sqlancer.clickhouse.ast.ClickHouseSelect;
import sqlancer.clickhouse.ast.ClickHouseSelect.SelectType;
import sqlancer.clickhouse.ast.ClickHouseTableReference;
import sqlancer.clickhouse.gen.ClickHouseExpressionGenerator;
import sqlancer.common.DBMSCommon;
import sqlancer.common.oracle.CERTOracleBase;
import sqlancer.common.oracle.TestOracle;

/**
 * Cardinality Estimation Restriction Testing for ClickHouse, following Ba and Rigger, ICSE 2024
 * (CERT: Finding Performance Issues in Database Systems Through the Lens of Cardinality Estimation,
 * <a href="https://doi.org/10.1145/3597503.3639076">DOI 10.1145/3597503.3639076</a>).
 *
 * <p>
 * Generates a random query Q, derives a strictly more restrictive query Q' from it through one or
 * more one-directional mutations (add or AND-tighten a WHERE predicate, drop an OR operand from an
 * existing disjunction, promote a non-DISTINCT SELECT to DISTINCT, or AND-tighten the HAVING when
 * the query has a GROUP BY), then asserts the <em>cardinality restriction monotonicity</em>
 * property:
 * </p>
 *
 * <pre>EstCard(Q', D) &le; EstCard(Q, D)</pre>
 *
 * <p>
 * The estimate is read from {@code EXPLAIN ESTIMATE}, which in ClickHouse returns one row per table
 * read with {@code parts}, {@code rows}, and {@code marks} columns -- the sum of {@code rows}
 * across those tuples is the estimator's projection of how many rows the query has to read. In
 * keeping with the paper, the queries themselves are <strong>never executed</strong>; this oracle
 * tests the estimator, not the runtime.
 * </p>
 *
 * <p>
 * Effective coverage on ClickHouse depends on three things, all addressed below:
 * </p>
 * <ul>
 * <li><strong>Table size vs. granule boundary.</strong> {@code EXPLAIN ESTIMATE} reflects MergeTree
 * primary-key granule pruning; with default {@code index_granularity=8192} and the small inserts
 * the schema generator emits, every table fits in one granule and the estimate cannot move. The
 * oracle bulk-loads up to {@link #TARGET_ROWS} rows from {@code numbers()} so multiple granules
 * exist.</li>
 * <li><strong>Predicates touching the PK.</strong> A WHERE filter on a non-indexed column does not
 * change the estimate. Primary-key columns are looked up at the start of every check and
 * duplicated in the predicate generator's column list so a generated predicate is much more likely
 * to reference one of them.</li>
 * <li><strong>HAVING pushdown.</strong> A HAVING predicate on a PK column is pushed down through
 * the optimizer to the scan, where it can prune granules; this is the only paper rule beyond
 * WHERE/OR that meaningfully changes the ClickHouse estimate. The oracle sometimes builds Q with a
 * {@code GROUP BY <pk_col>} so the HAVING mutator can fire.</li>
 * </ul>
 *
 * <p>
 * {@code EXPLAIN ESTIMATE} only meaningfully responds to filters that reference an indexed column.
 * For tables stored with engines {@code Log}, {@code Memory}, {@code TinyLog}, or
 * {@code StripeLog}, or for MergeTree tables ordered by {@code tuple()}, the statement returns an
 * empty result; the oracle skips such attempts via {@link IgnoreMeException}. Likewise, queries
 * whose plans become structurally dissimilar after the mutation are skipped, because in that regime
 * the two estimates are no longer comparable along a single axis -- this is the
 * structural-similarity gate from the paper (Section 4.3).
 * </p>
 */
public class ClickHouseCERTOracle extends CERTOracleBase<ClickHouseGlobalState>
        implements TestOracle<ClickHouseGlobalState> {

    private static final long TARGET_ROWS = 50_000L;
    private static final int PK_WEIGHT = 4;

    private ClickHouseExpressionGenerator gen;
    private ClickHouseSelect select;
    private List<ClickHouseColumnReference> columns;
    private List<ClickHouseColumnReference> pkColumns;
    private List<ClickHouseColumnReference> weightedColumns;

    public ClickHouseCERTOracle(ClickHouseGlobalState state) {
        super(state);
        ClickHouseErrors.addExpectedExpressionErrors(this.errors);
    }

    @Override
    public void check() throws SQLException {
        queryPlan1Sequences = new ArrayList<>();
        queryPlan2Sequences = new ArrayList<>();

        List<ClickHouseTable> tables = state.getSchema().getRandomTableNonEmptyTables().getTables();
        if (tables.isEmpty()) {
            throw new IgnoreMeException();
        }
        ClickHouseTable pivotTable = Randomly.fromList(tables);
        ensureLargeEnough(pivotTable);

        ClickHouseTableReference table = new ClickHouseTableReference(pivotTable, null);
        select = new ClickHouseSelect();
        select.setFromClause(table);
        columns = table.getColumnReferences();
        if (columns.isEmpty()) {
            throw new IgnoreMeException();
        }
        pkColumns = fetchPkColumns(pivotTable, columns);
        weightedColumns = buildWeightedColumns(columns, pkColumns);

        gen = new ClickHouseExpressionGenerator(state);
        gen.addColumns(weightedColumns);
        select.setFetchColumns(columns.stream().map(c -> (ClickHouseExpression) c).collect(Collectors.toList()));

        // 25% of the time, build Q with a GROUP BY <pk_col> so the HAVING mutator can fire.
        if (!pkColumns.isEmpty() && Randomly.getBooleanWithRatherLowProbability()) {
            ClickHouseColumnReference pk = Randomly.fromList(pkColumns);
            select.setFetchColumns(Collections.singletonList(pk));
            select.setGroupByClause(Collections.singletonList(pk));
        }
        if (Randomly.getBoolean()) {
            select.setWhereClause(gen.generateExpressionWithColumns(weightedColumns, 4));
        }

        String q1 = ClickHouseVisitor.asString(select);
        long card1 = explainEstimateRows(q1);
        if (card1 < 0) {
            throw new IgnoreMeException();
        }
        queryPlan1Sequences = explainPlanSequence(q1);

        // Apply 1-3 restriction mutators per attempt. JOIN, GROUPBY, and LIMIT remain excluded
        // because the visitor does not emit explicit JOIN syntax for these query shapes and
        // because both LIMIT and bare GROUPBY are invariant under ClickHouse's EXPLAIN ESTIMATE.
        int nrMutations = 1 + (int) Randomly.getNotCachedInteger(0, 3);
        for (int i = 0; i < nrMutations; i++) {
            boolean expectedIncrease = mutate(Mutator.JOIN, Mutator.GROUPBY, Mutator.LIMIT);
            if (expectedIncrease) {
                // All our implemented mutators are restrictive, so expectedIncrease must be false.
                throw new IgnoreMeException();
            }
        }

        String q2 = ClickHouseVisitor.asString(select);
        long card2 = explainEstimateRows(q2);
        if (card2 < 0) {
            throw new IgnoreMeException();
        }
        queryPlan2Sequences = explainPlanSequence(q2);

        if (queryPlan1Sequences.isEmpty() || queryPlan2Sequences.isEmpty()) {
            return;
        }
        if (DBMSCommon.editDistance(queryPlan1Sequences, queryPlan2Sequences) > 1) {
            return;
        }

        if (card2 > card1) {
            throw new AssertionError(String.format(
                    "CERT: more-restrictive query has higher estimated cardinality (%d > %d)%n  Q1: %s%n  Q2: %s",
                    card2, card1, q1, q2));
        }
    }

    @Override
    protected boolean mutateWhere() {
        ClickHouseExpression extra = gen.generateExpressionWithColumns(weightedColumns, 3);
        ClickHouseExpression w = select.getWhereClause();
        if (w == null) {
            select.setWhereClause(extra);
        } else {
            select.setWhereClause(new ClickHouseBinaryLogicalOperation(w, extra, ClickHouseBinaryLogicalOperator.AND));
        }
        return false;
    }

    @Override
    protected boolean mutateAnd() {
        return mutateWhere();
    }

    /**
     * Restrictive OR mutation per the paper: if the existing WHERE has a top-level OR, drop one
     * of its operands. If there is no OR to drop, fall back to AND with a fresh predicate, which
     * is also restrictive.
     *
     * @return always {@code false} -- restrictive direction, estimate must not grow.
     */
    @Override
    protected boolean mutateOr() {
        ClickHouseExpression w = select.getWhereClause();
        if (w instanceof ClickHouseBinaryLogicalOperation) {
            ClickHouseBinaryLogicalOperation bl = (ClickHouseBinaryLogicalOperation) w;
            if (bl.getOp() == ClickHouseBinaryLogicalOperator.OR) {
                select.setWhereClause(Randomly.getBoolean() ? bl.getLeft() : bl.getRight());
                return false;
            }
        }
        return mutateWhere();
    }

    @Override
    protected boolean mutateDistinct() {
        if (select.getFromOptions() == SelectType.DISTINCT) {
            // Already DISTINCT; fall through to AND-tightening which is always available.
            return mutateWhere();
        }
        select.setSelectType(SelectType.DISTINCT);
        return false;
    }

    /**
     * AND-tighten the HAVING clause with a fresh predicate biased toward PK columns. Requires a
     * GROUP BY to be present; otherwise fall back to AND-tightening the WHERE so the call is never
     * a no-op. The HAVING predicate on a PK column is pushed down through the optimizer to the
     * scan in ClickHouse, where it can prune granules -- this is the only paper rule beyond
     * WHERE/OR that meaningfully moves the estimate.
     *
     * @return always {@code false} -- restrictive direction, estimate must not grow.
     */
    @Override
    protected boolean mutateHaving() {
        if (select.getGroupByClause().isEmpty()) {
            return mutateWhere();
        }
        List<ClickHouseColumnReference> bias = pkColumns.isEmpty() ? weightedColumns : pkColumns;
        ClickHouseExpression extra = gen.generateExpressionWithColumns(bias, 3);
        ClickHouseExpression h = select.getHavingClause();
        if (h == null) {
            select.setHavingClause(extra);
        } else {
            select.setHavingClause(
                    new ClickHouseBinaryLogicalOperation(h, extra, ClickHouseBinaryLogicalOperator.AND));
        }
        return false;
    }

    /**
     * Ensure the table has enough rows to span multiple MergeTree granules. With the default
     * {@code index_granularity=8192} that the schema generator uses, a table with only ~10-30 rows
     * never triggers granule pruning regardless of WHERE predicate, so {@code EXPLAIN ESTIMATE}
     * always returns the full row count. Bulk-loading up to {@link #TARGET_ROWS} rows from
     * {@code numbers()} fixes this. Idempotent: tables already above the threshold are left alone.
     */
    private void ensureLargeEnough(ClickHouseTable table) {
        long rows = countRows(table);
        if (rows < 0 || rows >= TARGET_ROWS) {
            return;
        }
        long toInsert = TARGET_ROWS - rows;
        StringBuilder sb = new StringBuilder("INSERT INTO ");
        sb.append(quote(table.getName())).append(" SELECT ");
        boolean first = true;
        for (ClickHouseColumn c : table.getColumns()) {
            if (c.isAlias() || c.isMaterialized()) {
                continue;
            }
            if (!first) {
                sb.append(", ");
            }
            first = false;
            sb.append(generatorExprFor(c.getType().getType())).append(" AS ").append(quote(c.getName()));
        }
        sb.append(" FROM numbers(").append(toInsert).append(")");
        try (Statement s = state.getConnection().createStatement()) {
            s.execute(sb.toString());
        } catch (SQLException ignored) {
            // INSERT may fail for engines that don't accept INSERT SELECT (Log/Memory bulk paths,
            // tables with MATERIALIZED columns referencing other columns, etc.). Proceed; the
            // oracle just won't get extra coverage for this iteration.
        }
    }

    private static String generatorExprFor(ClickHouseDataType type) {
        switch (type) {
        case String:
            return "toString(number)";
        case Float32:
        case Float64:
            return "toFloat64(number)";
        default:
            return "toInt32(number - 25000)";
        }
    }

    private long countRows(ClickHouseTable table) {
        try (Statement s = state.getConnection().createStatement();
                ResultSet rs = s.executeQuery("SELECT count() FROM " + quote(table.getName()))) {
            return rs.next() ? rs.getLong(1) : -1;
        } catch (SQLException ignored) {
            return -1;
        }
    }

    /**
     * Look up the table's primary-key columns via {@code system.columns.is_in_primary_key} and
     * return the matching {@link ClickHouseColumnReference}s. Empty list means the table has no PK
     * (e.g. {@code ORDER BY tuple()} or a non-MergeTree engine), in which case the caller falls
     * back to unbiased column selection.
     */
    private List<ClickHouseColumnReference> fetchPkColumns(ClickHouseTable table,
            List<ClickHouseColumnReference> all) {
        Set<String> pkNames = new LinkedHashSet<>();
        String sql = String.format(
                "SELECT name FROM system.columns WHERE database = '%s' AND table = '%s' AND is_in_primary_key = 1",
                state.getDatabaseName().replace("'", "''"), table.getName().replace("'", "''"));
        try (Statement s = state.getConnection().createStatement(); ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) {
                pkNames.add(rs.getString(1));
            }
        } catch (SQLException ignored) {
            return Collections.emptyList();
        }
        if (pkNames.isEmpty()) {
            return Collections.emptyList();
        }
        return all.stream().filter(c -> pkNames.contains(c.getColumn().getName())).collect(Collectors.toList());
    }

    /**
     * Build the column list passed to the expression generator. PK columns are duplicated so a
     * randomly-chosen leaf is far more likely to be a PK column. With {@link #PK_WEIGHT} = 4 and
     * say 1 PK column out of 3, the PK is picked 4/(4 + 2) = 67% of the time vs 33% unweighted.
     */
    private static List<ClickHouseColumnReference> buildWeightedColumns(List<ClickHouseColumnReference> all,
            List<ClickHouseColumnReference> pk) {
        if (pk.isEmpty()) {
            return all;
        }
        List<ClickHouseColumnReference> weighted = new ArrayList<>(all);
        for (int i = 0; i < PK_WEIGHT - 1; i++) {
            weighted.addAll(pk);
        }
        return weighted;
    }

    private long explainEstimateRows(String query) {
        try (Statement s = state.getConnection().createStatement();
                ResultSet rs = s.executeQuery("EXPLAIN ESTIMATE " + query)) {
            long total = 0;
            boolean any = false;
            while (rs.next()) {
                any = true;
                total += rs.getLong("rows");
            }
            return any ? total : -1;
        } catch (SQLException ignored) {
            // Non-MergeTree engines, unsupported expressions, etc. -- signal "no estimate".
            return -1;
        }
    }

    private List<String> explainPlanSequence(String query) {
        List<String> plan = new ArrayList<>();
        try (Statement s = state.getConnection().createStatement();
                ResultSet rs = s.executeQuery("EXPLAIN PLAN " + query)) {
            while (rs.next()) {
                String line = rs.getString(1);
                if (line == null) {
                    continue;
                }
                String op = line.trim().split("[\\s(]", 2)[0];
                if (!op.isEmpty()) {
                    plan.add(op);
                }
            }
        } catch (SQLException ignored) {
            // Empty plan => caller treats as "skip".
        }
        return plan;
    }

    private static String quote(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }
}
