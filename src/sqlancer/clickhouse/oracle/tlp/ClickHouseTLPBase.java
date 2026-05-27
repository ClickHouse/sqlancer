package sqlancer.clickhouse.oracle.tlp;

import static java.lang.Math.min;
import static java.util.stream.IntStream.range;

import java.sql.SQLException;
import java.util.List;
import java.util.stream.Collectors;

import sqlancer.ComparatorHelper;
import sqlancer.Randomly;
import sqlancer.clickhouse.ClickHouseErrors;
import sqlancer.clickhouse.ClickHouseProvider.ClickHouseGlobalState;
import sqlancer.clickhouse.ClickHouseSchema;
import sqlancer.clickhouse.ClickHouseSchema.ClickHouseTable;
import sqlancer.clickhouse.ClickHouseVisitor;
import sqlancer.clickhouse.ast.ClickHouseColumnReference;
import sqlancer.clickhouse.ast.ClickHouseExpression;
import sqlancer.clickhouse.ast.ClickHouseExpression.ClickHouseJoin;
import sqlancer.clickhouse.ast.ClickHouseSelect;
import sqlancer.clickhouse.ast.ClickHouseTableReference;
import sqlancer.clickhouse.gen.ClickHouseExpressionGenerator;
import sqlancer.common.gen.ExpressionGenerator;
import sqlancer.common.oracle.TernaryLogicPartitioningOracleBase;
import sqlancer.common.oracle.TestOracle;

public class ClickHouseTLPBase extends TernaryLogicPartitioningOracleBase<ClickHouseExpression, ClickHouseGlobalState>
        implements TestOracle<ClickHouseGlobalState> {

    // Visibility note: `columns` and `gen` are accessed by oracles in sibling packages
    // (e.g. setop_limit.*Oracle) that extend this base. Promoted to protected so subclasses can
    // reuse the table+columns+predicate scaffolding without re-running it.
    protected ClickHouseSchema schema;
    protected List<ClickHouseColumnReference> columns;
    protected ClickHouseExpressionGenerator gen;
    protected ClickHouseSelect select;

    public ClickHouseTLPBase(ClickHouseGlobalState state) {
        super(state);
        ClickHouseErrors.addExpectedExpressionErrors(errors);
    }

    @Override
    public void check() throws SQLException {
        gen = new ClickHouseExpressionGenerator(state);
        schema = state.getSchema();
        select = new ClickHouseSelect();
        List<ClickHouseTable> tables = schema.getRandomTableNonEmptyTables().getTables();
        ClickHouseTableReference table = new ClickHouseTableReference(
                tables.get((int) Randomly.getNotCachedInteger(0, tables.size())),
                Randomly.getBoolean() ? "left" : null);
        select.setFromClause(table);
        columns = table.getColumnReferences();

        if (state.getClickHouseOptions().testJoins && Randomly.getBoolean()) {
            List<ClickHouseJoin> joinStatements = gen.getRandomJoinClauses(table, tables);
            columns.addAll(joinStatements.stream().flatMap(j -> j.getRightTable().getColumnReferences().stream())
                    .collect(Collectors.toList()));
            select.setJoinClauses(joinStatements);
        }
        // ARRAY JOIN expansion. Gated on --test-array-join AND the base table having at least one
        // Array(T) column (introduced by type-system v2). Emitted with low probability because
        // every emission shrinks the rest of the predicate / join surface for this iteration.
        if (state.getClickHouseOptions().enableArrayJoin && select.getJoinClauses().isEmpty()
                && Randomly.getBooleanWithRatherLowProbability()) {
            List<ClickHouseColumnReference> arrayCols = table.getColumnReferences().stream().filter(
                    c -> c.getColumn().getType().getTypeTerm() instanceof sqlancer.clickhouse.ClickHouseType.Array)
                    .collect(Collectors.toList());
            if (!arrayCols.isEmpty()) {
                ClickHouseColumnReference arrayCol = Randomly.fromList(arrayCols);
                select.setArrayJoinExprs(List.of(arrayCol));
                select.setArrayJoinLeft(Randomly.getBoolean());
            }
        }
        gen.addColumns(columns);
        int small = Randomly.smallNumber();
        List<ClickHouseExpression> from = range(0, 1 + small)
                .mapToObj(i -> gen.generateExpressionWithColumns(columns, 5)).collect(Collectors.toList());
        // Sprinkle composite-access / geo-call expressions when the schema has the matching
        // column shapes. Each is best-effort: if no candidate columns exist, the helper returns
        // null and we skip. Workstreams 2, 4, 6 of the 2026-05-27 coverage expansion plan.
        if (Randomly.getBooleanWithRatherLowProbability()) {
            ClickHouseExpression composite = gen.generateCompositeAccess(columns);
            if (composite != null) {
                from = new java.util.ArrayList<>(from);
                from.add(composite);
            }
        }
        if (Randomly.getBooleanWithRatherLowProbability()) {
            ClickHouseExpression geo = gen.generateGeoCall(columns);
            if (geo != null) {
                from = new java.util.ArrayList<>(from);
                from.add(geo);
            }
        }
        // Higher-order array function emission (workstream 22). 20% probability per the plan;
        // fires only when an Array(T) column is in scope.
        if (Randomly.getBoolean() && Randomly.getBooleanWithRatherLowProbability()) {
            ClickHouseExpression hof = gen.generateHigherOrderArrayCall(columns);
            if (hof != null) {
                from = new java.util.ArrayList<>(from);
                from.add(hof);
            }
        }
        // Window function emission (workstream 19). Lower probability because window-function
        // SELECTs hit a separate analyzer path and produce longer queries.
        if (Randomly.getBooleanWithRatherLowProbability()) {
            ClickHouseExpression win = gen.generateWindowCall(columns);
            if (win != null) {
                from = new java.util.ArrayList<>(from);
                from.add(win);
            }
        }
        // Date + Interval arithmetic (workstream 3). Fires only when a Date / DateTime column
        // is in scope. Adds (date_col + INTERVAL N UNIT) / dateAdd(UNIT, N, date_col).
        if (Randomly.getBooleanWithRatherLowProbability()) {
            ClickHouseExpression dt = gen.generateDateIntervalArith(columns);
            if (dt != null) {
                from = new java.util.ArrayList<>(from);
                from.add(dt);
            }
        }
        // Scalar subquery in SELECT (workstream 16). Emits (SELECT count() FROM other_table) as
        // an additional fetch column. Bounded at one per SELECT per the plan spec.
        if (Randomly.getBooleanWithRatherLowProbability()) {
            ClickHouseExpression sq = gen.generateScalarSubquery();
            if (sq != null) {
                from = new java.util.ArrayList<>(from);
                from.add(sq);
            }
        }
        select.setFetchColumns(from);
        select.setWhereClause(null);
        // ClickHouse-specific: emit a PREWHERE clause on the base SELECT with a small probability,
        // only when there are no joins (PREWHERE binds to the leftmost-table read step and the
        // generator does not yet restrict the predicate's column references to that table).
        //
        // PREWHERE is functionally redundant with WHERE -- the post-filter row set is identical --
        // but ClickHouse compiles the two clauses through different cache-key and skip-index code
        // paths. ClickHouse#104781 (`use_query_condition_cache=1` returning under-counts) is
        // sensitive specifically to a PREWHERE+WHERE split, so emitting the shape from the
        // generator unlocks every oracle downstream (TLP, SEMR, ...) to catch this family of bugs.
        if (select.getJoinClauses().isEmpty() && Randomly.getBooleanWithRatherLowProbability()) {
            select.setPrewhereClause(gen.generateExpressionWithColumns(columns, 3));
        }
        // FINAL is only accepted by Replacing/Summing/Aggregating/Collapsing variants -- plain
        // MergeTree raises ILLEGAL_FINAL. The schema reader records each table's engine so we can
        // gate emission precisely. The probability is low because the FINAL-specific bug surface
        // is narrow (FINAL+PREWHERE, FINAL+skip-index, FINAL+row-policy) and dominating the run
        // with FINAL queries would dilute non-FINAL coverage.
        //
        // FINAL preserves TLP invariants: it forces merge-on-read deduplication, but the predicate
        // ternary partition (p | NOT p | p IS NULL) commutes with deduplication. So every TLP
        // oracle inherits FINAL coverage for free. Bugs targeted: #97076 (FINAL+PREWHERE+row
        // policy), #98097 (FINAL+skip-indexes), #91847 (FINAL+PREWHERE block-structure mismatch).
        if (select.getJoinClauses().isEmpty() && table.getTable().supportsFinal()
                && Randomly.getBooleanWithRatherLowProbability()) {
            select.setFinal(true);
        }
        // CTE (alias-form only). Emit 1-3 alias-CTEs with simple constant or column-ref bodies.
        // Even unused, the WITH path exercises the analyzer's CTE normalisation -- and the
        // analyzer/SEMR pair is one of the documented bug surfaces. Workstream 17.
        if (Randomly.getBooleanWithRatherLowProbability()) {
            int cteCount = 1 + (int) Randomly.getNotCachedInteger(0, 3);
            java.util.List<ClickHouseExpression> withList = new java.util.ArrayList<>();
            for (int i = 0; i < cteCount; i++) {
                ClickHouseExpression body = gen.generateExpressionWithColumns(columns, 3);
                String alias = "cte" + i;
                withList.add(new sqlancer.clickhouse.ast.ClickHouseAliasOperation(body, alias));
            }
            select.setWithClauses(withList);
        }
        initializeTernaryPredicateVariants();
        // Smoke check
        String query = ClickHouseVisitor.asString(select);
        ComparatorHelper.getResultSetFirstColumnAsString(query, errors, state);
    }

    List<ClickHouseExpression> generateFetchColumns(List<ClickHouseColumnReference> columns) {
        List<ClickHouseColumnReference> list = Randomly.extractNrRandomColumns(columns,
                min(1 + Randomly.smallNumber(), columns.size()));
        return list.stream().map(c -> (ClickHouseExpression) c).collect(Collectors.toList());
    }

    @Override
    protected ExpressionGenerator<ClickHouseExpression> getGen() {
        return gen;
    }

}
