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

import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.clickhouse.ClickHouseErrors;
import sqlancer.clickhouse.ClickHouseProvider.ClickHouseGlobalState;
import sqlancer.clickhouse.ClickHouseSchema.ClickHouseColumn;
import sqlancer.clickhouse.ClickHouseSchema.ClickHouseTable;
import sqlancer.clickhouse.ClickHouseType;
import sqlancer.clickhouse.ClickHouseType.Kind;
import sqlancer.clickhouse.ClickHouseType.LowCardinality;
import sqlancer.clickhouse.ClickHouseType.Nullable;
import sqlancer.clickhouse.ClickHouseType.Primitive;
import sqlancer.clickhouse.ClickHouseType.Unknown;
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
 * Cardinality Estimation Restriction Testing for ClickHouse, following Ba and Rigger, ICSE 2024 (CERT: Finding
 * Performance Issues in Database Systems Through the Lens of Cardinality Estimation,
 * <a href="https://doi.org/10.1145/3597503.3639076">DOI 10.1145/3597503.3639076</a>).
 *
 * <p>
 * Generates a random query Q, derives a strictly more restrictive query Q' from it through one or more one-directional
 * mutations (add or AND-tighten a WHERE predicate, drop an OR operand from an existing disjunction, promote a
 * non-DISTINCT SELECT to DISTINCT, or AND-tighten the HAVING when the query has a GROUP BY), then asserts the
 * <em>cardinality restriction monotonicity</em> property:
 * </p>
 *
 * <pre>
 * EstCard(Q', D) &le; EstCard(Q, D)
 * </pre>
 *
 * <p>
 * The estimate is read from {@code EXPLAIN ESTIMATE}, which in ClickHouse returns one row per table read with
 * {@code parts}, {@code rows}, and {@code marks} columns -- the sum of {@code rows} across those tuples is the
 * estimator's projection of how many rows the query has to read. In keeping with the paper, the queries themselves are
 * <strong>never executed</strong>; this oracle tests the estimator, not the runtime.
 * </p>
 *
 * <p>
 * Effective coverage on ClickHouse depends on three things, all addressed below:
 * </p>
 * <ul>
 * <li><strong>Table size vs. granule boundary.</strong> {@code EXPLAIN ESTIMATE} reflects MergeTree primary-key granule
 * pruning; with default {@code index_granularity=8192} and the small inserts the schema generator emits, every table
 * fits in one granule and the estimate cannot move. The oracle bulk-loads up to {@link #TARGET_ROWS} rows from
 * {@code numbers()} so multiple granules exist.</li>
 * <li><strong>Predicates touching the PK.</strong> A WHERE filter on a non-indexed column does not change the estimate.
 * Primary-key columns are looked up at the start of every check and duplicated in the predicate generator's column list
 * so a generated predicate is much more likely to reference one of them.</li>
 * <li><strong>HAVING pushdown.</strong> A HAVING predicate on a PK column is pushed down through the optimizer to the
 * scan, where it can prune granules; this is the only paper rule beyond WHERE/OR that meaningfully changes the
 * ClickHouse estimate. The oracle sometimes builds Q with a {@code GROUP BY <pk_col>} so the HAVING mutator can
 * fire.</li>
 * </ul>
 *
 * <p>
 * {@code EXPLAIN ESTIMATE} only meaningfully responds to filters that reference an indexed column. For tables stored
 * with engines {@code Log}, {@code Memory}, {@code TinyLog}, or {@code StripeLog}, or for MergeTree tables ordered by
 * {@code tuple()}, the statement returns an empty result; the oracle skips such attempts via {@link IgnoreMeException}.
 * Likewise, queries whose plans become structurally dissimilar after the mutation are skipped, because in that regime
 * the two estimates are no longer comparable along a single axis -- this is the structural-similarity gate from the
 * paper (Section 4.3).
 * </p>
 */
public class ClickHouseCERTOracle extends CERTOracleBase<ClickHouseGlobalState>
        implements TestOracle<ClickHouseGlobalState> {

    private static final long TARGET_ROWS = 10_000L;
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
     * Restrictive OR mutation per the paper: if the existing WHERE has a top-level OR, drop one of its operands. If
     * there is no OR to drop, fall back to AND with a fresh predicate, which is also restrictive.
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
     * AND-tighten the HAVING clause with a fresh predicate biased toward PK columns. Requires a GROUP BY to be present;
     * otherwise fall back to AND-tightening the WHERE so the call is never a no-op. The HAVING predicate on a PK column
     * is pushed down through the optimizer to the scan in ClickHouse, where it can prune granules -- this is the only
     * paper rule beyond WHERE/OR that meaningfully moves the estimate.
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
            select.setHavingClause(new ClickHouseBinaryLogicalOperation(h, extra, ClickHouseBinaryLogicalOperator.AND));
        }
        return false;
    }

    // Ensure the table has enough rows to span multiple MergeTree granules. With the default
    // index_granularity=8192 that the schema generator uses, a table with only ~10-30 rows never
    // triggers granule pruning regardless of WHERE predicate, so EXPLAIN ESTIMATE always returns
    // the full row count. Bulk-loading up to TARGET_ROWS rows from numbers() fixes this.
    // Idempotent: tables already above the threshold are left alone.
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
            sb.append(generatorExprFor(c.getType().getTypeTerm())).append(" AS ").append(quote(c.getName()));
        }
        sb.append(" FROM numbers(").append(toInsert).append(")");
        if (state.getOptions().logEachSelect()) {
            // writeCurrent updates the live `-cur.log` for tailing; logStatement adds it to the
            // persisted reproducer that gets dumped on AssertionError (built from
            // state.getStatements()). Without the second call CERT's bulk INSERT shows up in
            // -cur.log but never in the saved database<N>.log, so saved reproducers from
            // post-CERT iterations are missing the cardinality that triggered the bug.
            state.getLogger().writeCurrent(sb.toString());
            state.getState().logStatement(sb.toString());
        }
        try (Statement s = state.getConnection().createStatement()) {
            s.execute(sb.toString());
        } catch (SQLException ignored) {
            // INSERT may fail for engines that don't accept INSERT SELECT (Log/Memory bulk paths,
            // tables with MATERIALIZED columns referencing other columns, etc.). Proceed; the
            // oracle just won't get extra coverage for this iteration.
        }
    }

    // Build a numbers()-driven SQL expression that supplies values for the term's Java-side type.
    // Wrappers are handled compositionally: Nullable wraps the inner generator with a
    // small-probability NULL via if(rand() % 10 = 0, ...), while LowCardinality is transparent at
    // INSERT time -- ClickHouse coerces the inner generator's result into the dictionary encoding
    // automatically.
    static String generatorExprFor(ClickHouseType term) {
        if (term instanceof Unknown) {
            throw new IgnoreMeException();
        }
        if (term instanceof Nullable n) {
            String inner = generatorExprFor(n.inner());
            return "if(rand() % 10 = 0, NULL, " + inner + ")";
        }
        if (term instanceof LowCardinality lc) {
            return generatorExprFor(lc.inner());
        }
        if (term instanceof Primitive p) {
            return generatorExprForPrimitive(p.kind());
        }
        throw new IgnoreMeException();
    }

    private static String generatorExprForPrimitive(Kind kind) {
        // Each branch must produce a value that ClickHouse will accept for that exact column type.
        // The pre-2026-05-26 implementation emitted `toInt32(number - 25000)` for every kind,
        // which silently broke INSERTs into UInt*/Date/DateTime/UUID/IPv* columns: negatives went
        // into unsigned, before-epoch ints went into DateTime, large ints went into UInt8, etc.
        // The catch-and-ignore around the INSERT then hid the failure -- countRows() honestly
        // reported 0 rows, ensureLargeEnough() refilled, and CERT looped forever, materialising
        // a numbers(N)-sized result on the server each round and blowing CH's memory cap.
        // See database8.log from the 2026-05-25 10h dev-VM run -- 1076 retries against a single
        // unfillable t2 in one database iteration.
        switch (kind) {
        case String:
            return "toString(number)";
        case Float32:
            return "toFloat32(number)";
        case Float64:
            return "toFloat64(number)";
        case Bool:
            return "toBool(number % 2)";
        // Signed integers: route number through Int64 so the subtraction is signed and can go
        // negative without underflowing UInt64 arithmetic. The result fits Int8 (-100..99),
        // Int16 (-30000..29999), and is unconstrained for Int32+.
        case Int8:
            return "toInt8(toInt32(number % 200) - 100)";
        case Int16:
            return "toInt16(toInt32(number % 60000) - 30000)";
        case Int32:
            return "toInt32(toInt64(number) - 25000)";
        case Int64:
            return "toInt64(toInt64(number) - 25000)";
        case Int128:
            return "toInt128(toInt64(number) - 25000)";
        case Int256:
            return "toInt256(toInt64(number) - 25000)";
        // Unsigned integers: stay non-negative. number is UInt64; just modulo into the type's range.
        case UInt8:
            return "toUInt8(number % 256)";
        case UInt16:
            return "toUInt16(number % 65536)";
        case UInt32:
            return "toUInt32(number)";
        case UInt64:
            return "toUInt64(number)";
        case UInt128:
            return "toUInt128(number)";
        case UInt256:
            return "toUInt256(number)";
        // Date is UInt16 days since 1970-01-01 (max ~2149-06-06). 50000 days ≈ 2107, safely inside.
        case Date:
            return "toDate(toUInt32(number % 50000))";
        // Date32 has a much wider range (1900..2299); number fits trivially.
        case Date32:
            return "toDate32(toInt32(number))";
        // DateTime is UInt32 seconds since epoch; number fits trivially.
        case DateTime:
            return "toDateTime(toUInt32(number))";
        // UUID requires the canonical 8-4-4-4-12 hex layout. leftPad zero-pads the variable part;
        // digits 0-9 are valid hex so the result parses regardless of N.
        case UUID:
            return "toUUID(concat('00000000-0000-0000-0000-', leftPad(toString(number), 12, '0')))";
        // IPv4/IPv6: constants are good enough -- CERT's invariant only needs rows to exist,
        // not value diversity in IP columns. Use IANA documentation-reserved addresses so any
        // future audit grepping for these in logs is unambiguous.
        case IPv4:
            return "toIPv4('192.0.2.1')";
        case IPv6:
            return "toIPv6('2001:db8::1')";
        default:
            // Exhaustive over Kind as of the 2026-05-26 audit. If a new kind lands here without
            // a handler, skip the iteration loudly rather than falling back to a wrong-typed
            // generator that recreates the pre-2026-05-26 bug.
            throw new IgnoreMeException();
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

    // Look up the table's primary-key columns via system.columns.is_in_primary_key and return the
    // matching ClickHouseColumnReferences. Empty list means the table has no PK (e.g. ORDER BY
    // tuple() or a non-MergeTree engine), in which case the caller falls back to unbiased column
    // selection.
    private List<ClickHouseColumnReference> fetchPkColumns(ClickHouseTable table, List<ClickHouseColumnReference> all) {
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

    // Build the column list passed to the expression generator. PK columns are duplicated so a
    // randomly-chosen leaf is far more likely to be a PK column. With PK_WEIGHT = 4 and say 1 PK
    // column out of 3, the PK is picked 4/(4 + 2) = 67% of the time vs 33% unweighted.
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
