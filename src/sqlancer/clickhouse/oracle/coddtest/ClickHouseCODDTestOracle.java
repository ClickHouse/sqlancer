package sqlancer.clickhouse.oracle.coddtest;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import com.clickhouse.data.ClickHouseDataType;

import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.clickhouse.ClickHouseErrors;
import sqlancer.clickhouse.ClickHouseProvider.ClickHouseGlobalState;
import sqlancer.clickhouse.ClickHouseSchema;
import sqlancer.clickhouse.ClickHouseSchema.ClickHouseColumn;
import sqlancer.clickhouse.ClickHouseSchema.ClickHouseTable;
import sqlancer.clickhouse.ast.ClickHouseConstant;
import sqlancer.common.oracle.CODDTestBase;
import sqlancer.common.oracle.TestOracle;

/**
 * Constant Optimization Driven Database System Testing for ClickHouse, following Zhang and Rigger,
 * SIGMOD 2025 (CODDTest: <a href="https://doi.org/10.1145/3709674">DOI 10.1145/3709674</a>).
 *
 * <p>
 * For a query Q with a sub-expression E, the oracle builds an auxiliary query A that evaluates E in
 * isolation, reads the resulting constant value V, then builds a folded query F by substituting V
 * for E in Q. Since constant folding and propagation are semantic-preserving rewrites, Q and F must
 * return identical result sets; any discrepancy is a logic bug in the DBMS.
 * </p>
 *
 * <p>
 * This implementation folds a scalar subquery used inside the {@code WHERE} predicate of a SELECT
 * statement, the simplest variant in the paper's taxonomy. Concretely:
 * </p>
 *
 * <pre>
 * auxiliary:  SELECT min(c)/max(c) FROM t                                   -> value V
 * original:   SELECT * FROM t WHERE col op (SELECT min(c)/max(c) FROM t)
 * folded:     SELECT * FROM t WHERE col op V
 * </pre>
 *
 * <p>
 * The aggregate is chosen so that the scalar subquery always returns exactly one row -- the paper's
 * "scalar subquery" case (DuckDBCODDTestOracle in upstream PR #1054). Folding is restricted to
 * {@code Int32} and {@code String} columns because they are the only types the existing schema
 * generator and {@link ClickHouseSchema#getConstant} support; other types raise
 * {@link IgnoreMeException} so the test attempt is dropped rather than flagged as a bug.
 * </p>
 */
public class ClickHouseCODDTestOracle extends CODDTestBase<ClickHouseGlobalState>
        implements TestOracle<ClickHouseGlobalState> {

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
        List<ClickHouseColumn> columns = table.getColumns();
        if (columns.isEmpty()) {
            throw new IgnoreMeException();
        }

        ClickHouseColumn filterColumn = Randomly.fromList(columns);
        ClickHouseColumn aggColumn = Randomly.fromList(columns);

        ClickHouseDataType filterType = filterColumn.getType().getType();
        ClickHouseDataType aggType = aggColumn.getType().getType();
        if (filterType != aggType) {
            // Keep both sides type-compatible to avoid steering the oracle into ClickHouse's type
            // coercion logic, which is interesting territory but orthogonal to constant folding.
            throw new IgnoreMeException();
        }
        if (filterType != ClickHouseDataType.Int32 && filterType != ClickHouseDataType.String) {
            throw new IgnoreMeException();
        }

        String tableQ = quote(table.getName());
        String aggColQ = quote(aggColumn.getName());
        String filterColQ = quote(filterColumn.getName());
        String aggFn = Randomly.fromOptions("min", "max");
        String op = Randomly.fromOptions("=", "<", ">", "<=", ">=", "!=");
        String aggExpr = aggFn + "(" + aggColQ + ")";

        auxiliaryQueryString = "SELECT " + aggExpr + " FROM " + tableQ;
        ClickHouseConstant value = evaluateScalar(auxiliaryQueryString, aggType);
        if (value == null || value.isNull()) {
            // A NULL constant cannot be folded into "col op NULL" without changing semantics
            // (NULL-propagation makes the predicate UNKNOWN for every row); skip rather than
            // pretend the two formulations are equivalent.
            throw new IgnoreMeException();
        }
        String literal = value.toString();

        String fetchCols = columns.stream().map(c -> tableQ + "." + quote(c.getName()))
                .collect(Collectors.joining(", "));
        String prefix = "SELECT " + fetchCols + " FROM " + tableQ + " WHERE " + filterColQ + " " + op + " ";
        originalQueryString = prefix + "(" + auxiliaryQueryString + ")";
        foldedQueryString = prefix + literal;

        List<String> originalRows = collectRows(originalQueryString);
        List<String> foldedRows = collectRows(foldedQueryString);

        if (!originalRows.equals(foldedRows)) {
            throw new AssertionError(String.format(
                    "CODDTest result mismatch:%n  aux:    %s -> %s%n  Q:      %s%n  folded: %s%n  Q rows (%d): %s%n  F rows (%d): %s",
                    auxiliaryQueryString, literal, originalQueryString, foldedQueryString, originalRows.size(),
                    originalRows, foldedRows.size(), foldedRows));
        }
    }

    private ClickHouseConstant evaluateScalar(String query, ClickHouseDataType type) throws SQLException {
        try (Statement s = state.getConnection().createStatement(); ResultSet rs = s.executeQuery(query)) {
            if (!rs.next()) {
                return null;
            }
            try {
                return ClickHouseSchema.getConstant(rs, 1, type);
            } catch (AssertionError unsupportedType) {
                throw new IgnoreMeException();
            }
        } catch (SQLException ex) {
            if (ex.getMessage() != null && errors.errorIsExpected(ex.getMessage())) {
                throw new IgnoreMeException();
            }
            throw ex;
        }
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
            if (ex.getMessage() != null && errors.errorIsExpected(ex.getMessage())) {
                throw new IgnoreMeException();
            }
            throw ex;
        }
        Collections.sort(rows);
        return rows;
    }

    private static String quote(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }
}
