package sqlancer.clickhouse.oracle.coddtest;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.clickhouse.data.ClickHouseDataType;

import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.clickhouse.ClickHouseErrors;
import sqlancer.clickhouse.ClickHouseProvider.ClickHouseGlobalState;
import sqlancer.clickhouse.ClickHouseSchema.ClickHouseColumn;
import sqlancer.clickhouse.ClickHouseSchema.ClickHouseTable;
import sqlancer.clickhouse.ClickHouseToStringVisitor;
import sqlancer.clickhouse.ClickHouseType;
import sqlancer.clickhouse.ClickHouseType.Primitive;
import sqlancer.clickhouse.ClickHouseTypeParser;
import sqlancer.clickhouse.ast.ClickHouseColumnReference;
import sqlancer.clickhouse.ast.ClickHouseExpression;
import sqlancer.clickhouse.ast.constant.ClickHouseCreateConstant;
import sqlancer.clickhouse.gen.ClickHouseExpressionGenerator;
import sqlancer.common.oracle.CODDTestBase;
import sqlancer.common.oracle.TestOracle;

public class ClickHouseCODDTestOracle extends CODDTestBase<ClickHouseGlobalState>
        implements TestOracle<ClickHouseGlobalState> {

    private static final String PHI_TOKEN = "/*__codd_phi_47ad13f4__*/";
    private static final int MAX_CASE_BRANCHES = 64;
    private static final int MAX_EXPR_DEPTH = 4;

    public ClickHouseCODDTestOracle(ClickHouseGlobalState state) {
        super(state);
        ClickHouseErrors.addExpectedExpressionErrors(this.errors);
    }

    @Override
    public void check() throws Exception {
        List<ClickHouseTable> tables = state.getSchema().getRandomTableNonEmptyTables().getTables();
        if (tables.isEmpty()) {
            throw new IgnoreMeException();
        }
        ClickHouseTable table = Randomly.fromList(tables);
        List<ClickHouseColumn> readableColumns = readableColumns(table);
        if (readableColumns.isEmpty()) {
            throw new IgnoreMeException();
        }

        int mode = (int) Randomly.getNotCachedInteger(0, 3);
        Phi phi;
        switch (mode) {
        case 0:
            phi = buildConstantPhi();
            break;
        case 1:
            phi = buildScalarSubqueryPhi(table, readableColumns);
            break;
        default:
            phi = buildDependentPhi(table, readableColumns);
            break;
        }
        if (phi == null) {
            throw new IgnoreMeException();
        }

        runComparison(table, readableColumns, phi);
    }

    private Phi buildConstantPhi() throws SQLException {
        ClickHouseExpressionGenerator gen = new ClickHouseExpressionGenerator(state);
        List<ClickHouseExpression> seed = List.of(ClickHouseCreateConstant.createInt32Constant(0L),
                ClickHouseCreateConstant.createInt32Constant(1L),
                ClickHouseCreateConstant.createInt32Constant(state.getRandomly().getInteger()),
                ClickHouseCreateConstant.createInt32Constant(state.getRandomly().getInteger()),
                ClickHouseCreateConstant.createStringConstant(state.getRandomly().getString()),
                ClickHouseCreateConstant.createStringConstant(state.getRandomly().getString()));
        ClickHouseExpression phi = gen.generateExpressionWithExpression(seed, MAX_EXPR_DEPTH);
        String phiSql = "(" + ClickHouseToStringVisitor.asString(phi) + ")";

        String aux = "SELECT toTypeName(" + phiSql + ") AS t, " + phiSql + " AS v";
        EvalResult eval = evaluateSingleRow(aux);
        if (eval == null || eval.valueText == null) {

            return null;
        }
        String literal = renderLiteral(eval.valueText, eval.typeName);
        if (literal == null) {
            return null;
        }
        return new Phi(phiSql, "(" + literal + ")", aux,  eval.typeName);
    }

    private Phi buildScalarSubqueryPhi(ClickHouseTable table, List<ClickHouseColumn> columns) throws SQLException {
        ClickHouseColumn aggCol = Randomly.fromList(columns);
        if (!isFoldableColumnTerm(aggCol.getType().getTypeTerm())) {
            return null;
        }
        String aggFn = Randomly.fromOptions("min", "max");
        String tableQ = quote(table.getName());
        String subquery = "SELECT " + aggFn + "(" + quote(aggCol.getName()) + ") FROM " + tableQ;
        String phiSql = "(" + subquery + ")";

        String aux = "SELECT toTypeName(" + phiSql + ") AS t, " + phiSql + " AS v";
        EvalResult eval = evaluateSingleRow(aux);
        if (eval == null || eval.valueText == null) {
            return null;
        }
        String literal = renderLiteral(eval.valueText, eval.typeName);
        if (literal == null) {
            return null;
        }
        return new Phi(phiSql, "(" + literal + ")", aux, eval.typeName);
    }

    private Phi buildDependentPhi(ClickHouseTable table, List<ClickHouseColumn> columns) throws SQLException {
        ClickHouseColumn keyCol = Randomly.fromList(columns);
        ClickHouseType keyTerm = keyCol.getType().getTypeTerm();
        if (!isFoldableColumnTerm(keyTerm)) {
            return null;
        }
        ClickHouseDataType keyType = keyCol.getType().getType();

        ClickHouseColumnReference keyRef = keyCol.asColumnReference(table.getName());
        ClickHouseExpressionGenerator gen = new ClickHouseExpressionGenerator(state);
        gen.addColumns(Collections.singletonList(keyRef));
        ClickHouseExpression phi = gen.generateExpressionWithColumns(Collections.singletonList(keyRef), MAX_EXPR_DEPTH);
        String phiSql = "(" + ClickHouseToStringVisitor.asString(phi) + ")";

        String tableQ = quote(table.getName());
        String keyRefSql = tableQ + "." + quote(keyCol.getName());

        String aux = "SELECT DISTINCT " + keyRefSql + " AS k, " + phiSql + " AS v, " + "toTypeName(" + phiSql
                + ") AS t FROM " + tableQ;

        Map<String, String> branches = new LinkedHashMap<>();
        boolean hasNullKey = false;
        String nullKeyLiteral = null;
        String expectedType = null;

        try (Statement s = state.getConnection().createStatement(); ResultSet rs = s.executeQuery(aux)) {
            while (rs.next()) {
                if (branches.size() >= MAX_CASE_BRANCHES) {
                    return null;
                }
                String keyText = rs.getString("k");
                boolean keyNull = rs.wasNull();
                String valText = rs.getString("v");
                boolean valNull = rs.wasNull();
                String typeText = rs.getString("t");

                if (expectedType == null) {
                    expectedType = typeText;
                } else if (!expectedType.equals(typeText)) {

                    return null;
                }

                String valLiteral = valNull ? "NULL" : renderLiteral(valText, typeText);
                if (valLiteral == null) {
                    return null;
                }

                if (keyNull) {
                    if (hasNullKey && !Objects.equals(nullKeyLiteral, valLiteral)) {

                        return null;
                    }
                    hasNullKey = true;
                    nullKeyLiteral = valLiteral;
                    continue;
                }
                String keyLiteral = renderLiteral(keyText, clickHouseTypeName(keyType));
                if (keyLiteral == null) {
                    return null;
                }
                String existing = branches.get(keyLiteral);
                if (existing != null && !Objects.equals(existing, valLiteral)) {

                    return null;
                }
                branches.put(keyLiteral, valLiteral);
            }
        } catch (SQLException ex) {
            throw maybeIgnore(ex);
        }

        if (branches.isEmpty() && !hasNullKey) {
            return null;
        }

        StringBuilder caseSb = new StringBuilder();
        caseSb.append("CASE");
        if (hasNullKey) {
            caseSb.append(" WHEN ").append(keyRefSql).append(" IS NULL THEN ").append(nullKeyLiteral);
        }
        for (Map.Entry<String, String> e : branches.entrySet()) {
            caseSb.append(" WHEN ").append(keyRefSql).append(" = ").append(e.getKey()).append(" THEN ")
                    .append(e.getValue());
        }

        caseSb.append(" ELSE NULL END");

        String foldedSql = "cast((" + caseSb.toString() + "), " + sqlQuote(expectedType) + ")";

        return new Phi(phiSql, foldedSql, aux, expectedType);
    }

    private void runComparison(ClickHouseTable table, List<ClickHouseColumn> columns, Phi phi) throws SQLException {
        String tableQ = quote(table.getName());
        String fetchCols = columns.stream().map(c -> tableQ + "." + quote(c.getName()))
                .collect(Collectors.joining(", "));

        ClickHouseColumn filterCol = pickFilterColumn(columns, phi.expectedType);
        String filterColQ = tableQ + "." + quote(filterCol.getName());
        String op = Randomly.fromOptions("=", "<", ">", "<=", ">=", "!=");

        String predicateTemplate;
        int shape = (int) Randomly.getNotCachedInteger(0, 4);
        if (shape == 0) {

            predicateTemplate = filterColQ + " " + op + " " + PHI_TOKEN;
        } else if (shape == 1) {

            predicateTemplate = "(" + filterColQ + " " + op + " " + PHI_TOKEN + ") AND ("
                    + randomColumnPredicate(table, columns) + ")";
        } else if (shape == 2) {

            predicateTemplate = "(" + filterColQ + " " + op + " " + PHI_TOKEN + ") OR ("
                    + randomColumnPredicate(table, columns) + ")";
        } else {

            predicateTemplate = "NOT (" + filterColQ + " " + op + " " + PHI_TOKEN + ")";
        }

        String queryTemplate = "SELECT " + fetchCols + " FROM " + tableQ + " WHERE " + predicateTemplate;

        if (queryTemplate.split(Pattern.quote(PHI_TOKEN), -1).length != 2) {
            throw new IgnoreMeException();
        }

        String originalSql = queryTemplate.replace(PHI_TOKEN, phi.originalSql);
        String foldedSql = queryTemplate.replace(PHI_TOKEN, phi.foldedSql);

        this.auxiliaryQueryString = phi.auxiliarySql;
        this.originalQueryString = originalSql;
        this.foldedQueryString = foldedSql;

        List<String> originalRows = collectRows(originalSql);
        List<String> foldedRows = collectRows(foldedSql);

        if (!originalRows.equals(foldedRows)) {
            throw new AssertionError(String.format(
                    "CODDTest result mismatch:%n  aux:    %s%n  Q:      %s%n  folded: %s%n  Q rows (%d): %s%n  F rows (%d): %s",
                    phi.auxiliarySql, originalSql, foldedSql, originalRows.size(), originalRows, foldedRows.size(),
                    foldedRows));
        }
    }

    private ClickHouseColumn pickFilterColumn(List<ClickHouseColumn> columns, String expectedType) {
        if (expectedType == null) {
            return Randomly.fromList(columns);
        }
        ClickHouseDataType target = parseType(expectedType);
        if (target == null) {
            return Randomly.fromList(columns);
        }
        List<ClickHouseColumn> matching = columns.stream().filter(c -> c.getType().getType() == target)
                .collect(Collectors.toList());
        return matching.isEmpty() ? Randomly.fromList(columns) : Randomly.fromList(matching);
    }

    private String randomColumnPredicate(ClickHouseTable table, List<ClickHouseColumn> columns) {
        List<ClickHouseColumnReference> colRefs = columns.stream().map(c -> c.asColumnReference(table.getName()))
                .collect(Collectors.toList());
        ClickHouseExpressionGenerator gen = new ClickHouseExpressionGenerator(state);
        gen.addColumns(colRefs);
        ClickHouseExpression expr = gen.generateExpressionWithColumns(colRefs, 3);
        return ClickHouseToStringVisitor.asString(expr);
    }

    private static final class EvalResult {
        final String typeName;
        final String valueText;

        EvalResult(String typeName, String valueText) {
            this.typeName = typeName;
            this.valueText = valueText;
        }
    }

    private EvalResult evaluateSingleRow(String query) throws SQLException {
        try (Statement s = state.getConnection().createStatement(); ResultSet rs = s.executeQuery(query)) {
            if (!rs.next()) {
                return null;
            }
            String type = rs.getString("t");
            String value = rs.getString("v");
            if (rs.wasNull()) {
                return new EvalResult(type, null);
            }
            return new EvalResult(type, value);
        } catch (SQLException ex) {
            throw maybeIgnore(ex);
        }
    }

    static String renderLiteral(String value, String typeName) {
        if (value == null) {
            return "NULL";
        }
        ClickHouseType inner = ClickHouseTypeParser.parse(typeName).unwrap();
        if (!(inner instanceof Primitive p)) {
            return null;
        }
        switch (p.kind()) {
        case Int128:
        case Int256:
        case UInt128:
        case UInt256:

            return "CAST('" + value + "' AS " + p.kind().name() + ")";
        case Int8:
        case Int16:
        case Int32:
        case Int64:
        case UInt8:
        case UInt16:
        case UInt32:
        case UInt64:

            return value;
        case Bool:

            return ("true".equalsIgnoreCase(value) || "1".equals(value)) ? "1" : "0";
        case String:
            return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'";
        default:
            return null;
        }
    }

    static boolean isFoldableColumnTerm(ClickHouseType term) {
        if (!term.supportsLiteralEmission()) {
            return false;
        }
        ClickHouseType inner = term.unwrap();
        if (!(inner instanceof Primitive p)) {
            return false;
        }
        switch (p.kind()) {
        case Int8:
        case Int16:
        case Int32:
        case Int64:
        case Int128:
        case Int256:
        case UInt8:
        case UInt16:
        case UInt32:
        case UInt64:
        case UInt128:
        case UInt256:
        case Bool:
        case String:
            return true;
        default:
            return false;
        }
    }

    private static ClickHouseDataType parseType(String typeName) {
        ClickHouseType inner = ClickHouseTypeParser.parse(typeName).unwrap();
        if (inner instanceof Primitive p) {
            return p.kind().toClickHouseDataType();
        }
        return null;
    }

    private static String clickHouseTypeName(ClickHouseDataType type) {
        return type.name();
    }

    private List<String> collectRows(String query) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Statement s = state.getConnection().createStatement(); ResultSet rs = s.executeQuery(query)) {
            ResultSetMetaData md = rs.getMetaData();
            int colCount = md.getColumnCount();
            while (rs.next()) {
                StringBuilder row = new StringBuilder();
                for (int i = 1; i <= colCount; i++) {
                    if (i > 1) {
                        row.append('|');
                    }
                    String v = rs.getString(i);
                    row.append(rs.wasNull() ? "NULL" : v);
                }
                rows.add(row.toString());
            }
        } catch (SQLException ex) {
            throw maybeIgnore(ex);
        }
        Collections.sort(rows);
        return rows;
    }

    private SQLException maybeIgnore(SQLException ex) {
        if (ex.getMessage() != null && errors.errorIsExpected(ex.getMessage())) {

            throw new IgnoreMeException();
        }
        return ex;
    }

    private static List<ClickHouseColumn> readableColumns(ClickHouseTable table) {
        return new ArrayList<>(table.getColumns());
    }

    private static String quote(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }

    private static String sqlQuote(String s) {
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    private static final class Phi {
        final String originalSql;
        final String foldedSql;
        final String auxiliarySql;
        final String expectedType;

        Phi(String originalSql, String foldedSql, String auxiliarySql, String expectedType) {
            this.originalSql = originalSql;
            this.foldedSql = foldedSql;
            this.auxiliarySql = auxiliarySql;
            this.expectedType = expectedType;
        }
    }
}
