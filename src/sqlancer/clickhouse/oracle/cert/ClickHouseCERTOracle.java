package sqlancer.clickhouse.oracle.cert;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.clickhouse.ClickHouseErrors;
import sqlancer.clickhouse.ClickHouseProvider.ClickHouseGlobalState;
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
 * Generates a random query Q, derives a strictly more restrictive query Q' from it through a single
 * one-directional mutation (add or AND-tighten a WHERE predicate, drop an OR operand from an
 * existing disjunction, or promote a non-DISTINCT SELECT to DISTINCT), then asserts the
 * <em>cardinality restriction monotonicity</em> property:
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
 * {@code EXPLAIN ESTIMATE} only meaningfully responds to filters that reference an indexed column
 * (MergeTree primary key, partition key, projections). For tables stored with engines {@code Log},
 * {@code Memory}, {@code TinyLog}, or {@code StripeLog}, or for MergeTree tables ordered by
 * {@code tuple()}, the statement returns an empty result; the oracle skips such attempts via
 * {@link IgnoreMeException}. Likewise, queries whose plans become structurally dissimilar after the
 * mutation are skipped, because in that regime the two estimates are no longer comparable along a
 * single axis -- this is the structural-similarity gate from the paper (Section 4.3).
 * </p>
 */
public class ClickHouseCERTOracle extends CERTOracleBase<ClickHouseGlobalState>
        implements TestOracle<ClickHouseGlobalState> {

    private ClickHouseExpressionGenerator gen;
    private ClickHouseSelect select;
    private List<ClickHouseColumnReference> columns;

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
        ClickHouseTableReference table = new ClickHouseTableReference(Randomly.fromList(tables), null);
        select = new ClickHouseSelect();
        select.setFromClause(table);
        columns = table.getColumnReferences();
        if (columns.isEmpty()) {
            throw new IgnoreMeException();
        }
        gen = new ClickHouseExpressionGenerator(state);
        gen.addColumns(columns);
        select.setFetchColumns(columns.stream().map(c -> (ClickHouseExpression) c).collect(Collectors.toList()));
        if (Randomly.getBoolean()) {
            select.setWhereClause(gen.generateExpressionWithColumns(columns, 4));
        }

        String q1 = ClickHouseVisitor.asString(select);
        long card1 = explainEstimateRows(q1);
        if (card1 < 0) {
            throw new IgnoreMeException();
        }
        queryPlan1Sequences = explainPlanSequence(q1);

        // Restrict to the mutators we implement one-directionally below. The other CERT mutators
        // in the base class would either need richer query shapes than this generator produces or
        // rely on syntax (LIMIT, GROUP BY) the ClickHouse visitor does not emit.
        boolean expectedIncrease = mutate(Mutator.JOIN, Mutator.GROUPBY, Mutator.HAVING, Mutator.LIMIT);
        // All our mutators are strictly restrictive, so expectedIncrease must be false.
        if (expectedIncrease) {
            throw new IgnoreMeException();
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
        ClickHouseExpression extra = gen.generateExpressionWithColumns(columns, 3);
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
}
