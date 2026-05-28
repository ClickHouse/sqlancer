package sqlancer.clickhouse.oracle.pqs;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.SQLConnection;
import sqlancer.clickhouse.ClickHouseErrors;
import sqlancer.clickhouse.ClickHouseProvider.ClickHouseGlobalState;
import sqlancer.clickhouse.ClickHouseSchema;
import sqlancer.clickhouse.ClickHouseSchema.ClickHouseColumn;
import sqlancer.clickhouse.ClickHouseSchema.ClickHouseRowValue;
import sqlancer.clickhouse.ClickHouseSchema.ClickHouseTable;
import sqlancer.clickhouse.ClickHouseSchema.ClickHouseTables;
import sqlancer.clickhouse.ClickHouseVisitor;
import sqlancer.clickhouse.ast.ClickHouseColumnReference;
import sqlancer.clickhouse.ast.ClickHouseConstant;
import sqlancer.clickhouse.ast.ClickHouseExpression;
import sqlancer.clickhouse.ast.ClickHouseUnaryPostfixOperation;
import sqlancer.clickhouse.ast.ClickHouseUnaryPostfixOperation.ClickHouseUnaryPostfixOperator;
import sqlancer.clickhouse.ast.ClickHouseUnaryPrefixOperation;
import sqlancer.clickhouse.ast.ClickHouseUnaryPrefixOperation.ClickHouseUnaryPrefixOperator;
import sqlancer.clickhouse.gen.ClickHouseExpressionGenerator;
import sqlancer.common.oracle.PivotedQuerySynthesisBase;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.Query;
import sqlancer.common.query.SQLQueryAdapter;

/**
 * Pivoted Query Synthesis (PQS) for ClickHouse, following Rigger &amp; Su, OSDI 2020.
 *
 * The classical SQLancer PQS implementation (e.g. SQLite3) requires every AST node to expose a Java-side
 * {@code getExpectedValue()} that mirrors the DBMS' evaluation semantics. ClickHouse's fork does not provide that for
 * most generated expressions, and reproducing all of ClickHouse's coercion / NULL / arithmetic rules in Java would be
 * an open-ended effort.
 *
 * Instead we delegate rectification to the server: for each randomly generated predicate we ask ClickHouse what the
 * predicate evaluates to on the pivot row by embedding the pivot row's values as literals in a one-row subquery and
 * running the predicate against it. Based on the TRUE / FALSE / NULL answer we either keep the predicate, negate it, or
 * wrap it in {@code IS NULL} so that the conjunction is guaranteed to hold for the pivot row.
 *
 * The pivot row may span 1-3 tables (paper Figure 1 / Section 3.1): each pivot "row" is the cross-product of one
 * randomly-selected row from each chosen table, and predicates reference table-qualified columns from any of them. The
 * optional query elaborations from Section 3.2 (DISTINCT, GROUP BY all pivot columns, ORDER BY) are attached
 * probabilistically; each preserves containment by construction.
 *
 * Containment is checked with {@code INTERSECT}, which treats NULLs as equal in ClickHouse and so handles nullable
 * columns without explicit {@code IS NOT DISTINCT FROM} comparisons.
 */
public class ClickHousePivotedQuerySynthesisOracle extends
        PivotedQuerySynthesisBase<ClickHouseGlobalState, ClickHouseRowValue, ClickHouseExpression, SQLConnection> {

    private static final int MAX_PIVOT_TABLES = 3;

    private final ExpectedErrors expectedErrors;
    private LinkedHashMap<ClickHouseTable, LinkedHashMap<ClickHouseColumn, ClickHouseConstant>> pivotByTable;
    private List<ClickHouseConstant> flatPivotValues;

    public ClickHousePivotedQuerySynthesisOracle(ClickHouseGlobalState globalState) {
        super(globalState);
        this.expectedErrors = ExpectedErrors.newErrors().with(ClickHouseErrors.getExpectedExpressionErrors()).build();
        this.errors.addAll(ClickHouseErrors.getExpectedExpressionErrors());
    }

    @Override
    protected Query<SQLConnection> getRectifiedQuery() throws Exception {
        rectifiedPredicates.clear();
        pivotRowExpression.clear();

        ClickHouseSchema schema = globalState.getSchema();
        List<ClickHouseTable> nonEmpty = schema.getRandomTableNonEmptyTables().getTables();
        if (nonEmpty.isEmpty()) {
            throw new IgnoreMeException();
        }

        // Paper Section 3.1: the pivot row may consist of columns drawn from
        // multiple tables / views. Choose 1-3 distinct tables.
        int desired = (int) Randomly.getNotCachedInteger(1, Math.min(nonEmpty.size(), MAX_PIVOT_TABLES) + 1);
        List<ClickHouseTable> pivotTables = Randomly.nonEmptySubset(nonEmpty, desired);

        pivotByTable = new LinkedHashMap<>();
        flatPivotValues = new ArrayList<>();
        Map<ClickHouseColumn, ClickHouseConstant> diagnosticRowMap = new LinkedHashMap<>();
        for (ClickHouseTable t : pivotTables) {
            List<ClickHouseColumn> cols = t.getColumns();
            if (cols.isEmpty()) {
                throw new IgnoreMeException();
            }
            LinkedHashMap<ClickHouseColumn, ClickHouseConstant> row = fetchPivotRow(t, cols);
            pivotByTable.put(t, row);
            flatPivotValues.addAll(row.values());
            diagnosticRowMap.putAll(row);
        }

        pivotRow = new ClickHouseRowValue(new ClickHouseTables(new ArrayList<>(pivotByTable.keySet())),
                diagnosticRowMap);

        List<ClickHouseColumnReference> columnRefs = new ArrayList<>();
        for (Map.Entry<ClickHouseTable, LinkedHashMap<ClickHouseColumn, ClickHouseConstant>> e : pivotByTable
                .entrySet()) {
            String tableName = e.getKey().getName();
            for (ClickHouseColumn c : e.getValue().keySet()) {
                columnRefs.add(c.asColumnReference(tableName));
            }
        }

        ClickHouseExpressionGenerator gen = new ClickHouseExpressionGenerator(globalState).allowNullLiterals(true);
        gen.addColumns(columnRefs);

        int nrPredicates = 1 + Randomly.smallNumber();
        List<ClickHouseExpression> rectified = new ArrayList<>();
        for (int i = 0; i < nrPredicates; i++) {
            ClickHouseExpression pred = gen.generateExpressionWithColumns(columnRefs, 4);
            ClickHouseExpression r = rectifyAgainstPivot(pred);
            rectified.add(r);
            rectifiedPredicates.add(r);
        }

        return new SQLQueryAdapter(buildPivotQuery(columnRefs, rectified), expectedErrors);
    }

    @Override
    protected Query<SQLConnection> getContainmentCheckQuery(Query<?> pivotRowQuery) throws Exception {
        StringBuilder sb = new StringBuilder("SELECT ");
        sb.append(flatPivotValues.stream().map(ClickHouseConstant::toString).collect(Collectors.joining(", ")));
        sb.append(" INTERSECT SELECT * FROM (").append(pivotRowQuery.getUnterminatedQueryString()).append(")");
        return new SQLQueryAdapter(sb.toString(), expectedErrors);
    }

    @Override
    protected String getExpectedValues(ClickHouseExpression expr) {
        // ClickHouse expressions don't carry per-node expected values; the
        // base class uses this only for the post-failure diagnostic log.
        return ClickHouseVisitor.asString(expr);
    }

    private String buildPivotQuery(List<ClickHouseColumnReference> columnRefs, List<ClickHouseExpression> rectified) {
        String projection = columnRefs.stream().map(ClickHouseVisitor::asString).collect(Collectors.joining(", "));
        String from = pivotByTable.keySet().stream().map(t -> quote(t.getName())).collect(Collectors.joining(", "));
        String whereClause = rectified.stream().map(p -> "(" + ClickHouseVisitor.asString(p) + ")")
                .collect(Collectors.joining(" AND "));

        StringBuilder sb = new StringBuilder("SELECT ");
        // Optional DISTINCT (Section 3.2): preserves the pivot row.
        if (Randomly.getBooleanWithSmallProbability()) {
            sb.append("DISTINCT ");
        }
        sb.append(projection).append(" FROM ").append(from).append(" WHERE ").append(whereClause);

        // Optional GROUP BY (Section 3.2): must include every pivot-row
        // column to keep the row in the grouped result.
        if (Randomly.getBooleanWithSmallProbability()) {
            sb.append(" GROUP BY ").append(projection);
        }
        // Optional ORDER BY (Section 3.2): does not influence membership.
        if (Randomly.getBooleanWithSmallProbability()) {
            sb.append(" ORDER BY ").append(Randomly.fromOptions("rand()", projection));
        }
        return sb.toString();
    }

    private LinkedHashMap<ClickHouseColumn, ClickHouseConstant> fetchPivotRow(ClickHouseTable table,
            List<ClickHouseColumn> columns) throws SQLException {
        StringBuilder sb = new StringBuilder("SELECT ");
        sb.append(columns.stream().map(c -> quote(c.getName())).collect(Collectors.joining(", ")));
        sb.append(" FROM ").append(quote(table.getName())).append(" ORDER BY rand() LIMIT 1");

        LinkedHashMap<ClickHouseColumn, ClickHouseConstant> values = new LinkedHashMap<>();
        try (Statement s = globalState.getConnection().createStatement();
                ResultSet rs = s.executeQuery(sb.toString())) {
            if (!rs.next()) {
                // Table is empty even though the schema reported it as non-empty;
                // a concurrent test run may have truncated it.
                throw new IgnoreMeException();
            }
            for (int i = 0; i < columns.size(); i++) {
                ClickHouseColumn c = columns.get(i);
                // ClickHouseSchema.getConstant covers the v1 primitives and throws IgnoreMeException
                // for anything else, so the pivot attempt is abandoned quietly when a column type
                // is outside the v1 round-trip set.
                values.put(c, ClickHouseSchema.getConstant(rs, i + 1, c.getType().getType()));
            }
        } catch (SQLException e) {
            if (sqlancer.clickhouse.ClickHouseErrors.isToleratedException(e)) {
                throw new IgnoreMeException();
            }
            throw e;
        }
        return values;
    }

    /**
     * Asks ClickHouse what the predicate evaluates to on the pivot row, and returns an equivalent expression that is
     * guaranteed to be TRUE on that row: {@code pred} itself if it was TRUE, {@code NOT pred} if it was FALSE, or
     * {@code pred IS NULL} if it was NULL.
     *
     * <p>
     * For a multi-table pivot, the probe builds a one-row alias per pivot table:
     * {@code (SELECT lit AS c0, lit AS c1) AS t1, (SELECT lit AS c0) AS t2}, so table-qualified column references in
     * {@code pred} resolve against the matching literal-typed subquery.
     *
     * @param pred
     *            the random predicate to rectify
     *
     * @return an expression that evaluates to TRUE on the pivot row
     *
     * @throws SQLException
     *             if the probe query fails with an unexpected error
     */
    private ClickHouseExpression rectifyAgainstPivot(ClickHouseExpression pred) throws SQLException {
        String predSql = ClickHouseVisitor.asString(pred);

        StringBuilder probe = new StringBuilder("SELECT (").append(predSql).append(") FROM ");
        boolean firstTable = true;
        for (Map.Entry<ClickHouseTable, LinkedHashMap<ClickHouseColumn, ClickHouseConstant>> e : pivotByTable
                .entrySet()) {
            if (!firstTable) {
                probe.append(", ");
            }
            firstTable = false;
            probe.append("(SELECT ");
            boolean firstCol = true;
            for (Map.Entry<ClickHouseColumn, ClickHouseConstant> v : e.getValue().entrySet()) {
                if (!firstCol) {
                    probe.append(", ");
                }
                firstCol = false;
                probe.append(v.getValue().toString()).append(" AS ").append(quote(v.getKey().getName()));
            }
            probe.append(") AS ").append(quote(e.getKey().getName()));
        }

        try (Statement s = globalState.getConnection().createStatement();
                ResultSet rs = s.executeQuery(probe.toString())) {
            if (!rs.next()) {
                throw new IgnoreMeException();
            }
            String raw = rs.getString(1);
            if (rs.wasNull() || raw == null) {
                return new ClickHouseUnaryPostfixOperation(pred, ClickHouseUnaryPostfixOperator.IS_NULL, false);
            }
            if (isTrueValue(raw)) {
                return pred;
            }
            return new ClickHouseUnaryPrefixOperation(pred, ClickHouseUnaryPrefixOperator.NOT);
        } catch (SQLException ex) {
            // Type errors, overflows, regex-compile errors, MEMORY_LIMIT_EXCEEDED, etc. in the
            // randomly-generated predicate are not bugs in ClickHouse — drop this attempt.
            // Walk the cause chain via the centralised helper so deeply-nested CH exceptions
            // (wrapped in a JDBC SQLException) get absorbed.
            if (sqlancer.clickhouse.ClickHouseErrors.isToleratedException(ex)) {
                throw new IgnoreMeException();
            }
            String msg = ex.getMessage();
            if (msg != null && expectedErrors.errorIsExpected(msg)) {
                throw new IgnoreMeException();
            }
            throw ex;
        }
    }

    private static boolean isTrueValue(String raw) {
        try {
            return Double.parseDouble(raw) != 0.0;
        } catch (NumberFormatException nf) {
            return !raw.isEmpty();
        }
    }

    private static String quote(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }
}
