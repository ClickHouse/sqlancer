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

        return ClickHouseVisitor.asString(expr);
    }

    private String buildPivotQuery(List<ClickHouseColumnReference> columnRefs, List<ClickHouseExpression> rectified) {
        String projection = columnRefs.stream().map(ClickHouseVisitor::asString).collect(Collectors.joining(", "));
        String from = pivotByTable.keySet().stream().map(t -> quote(t.getName())).collect(Collectors.joining(", "));
        String whereClause = rectified.stream().map(p -> "(" + ClickHouseVisitor.asString(p) + ")")
                .collect(Collectors.joining(" AND "));

        StringBuilder sb = new StringBuilder("SELECT ");

        if (Randomly.getBooleanWithSmallProbability()) {
            sb.append("DISTINCT ");
        }
        sb.append(projection).append(" FROM ").append(from).append(" WHERE ").append(whereClause);

        if (Randomly.getBooleanWithSmallProbability()) {
            sb.append(" GROUP BY ").append(projection);
        }

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

                throw new IgnoreMeException();
            }
            for (int i = 0; i < columns.size(); i++) {
                ClickHouseColumn c = columns.get(i);

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
