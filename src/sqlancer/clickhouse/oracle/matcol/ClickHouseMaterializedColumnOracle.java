package sqlancer.clickhouse.oracle.matcol;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.clickhouse.ClickHouseErrors;
import sqlancer.clickhouse.ClickHouseProvider.ClickHouseGlobalState;
import sqlancer.clickhouse.ClickHouseSchema.ClickHouseTable;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.ExpectedErrors;

public class ClickHouseMaterializedColumnOracle implements TestOracle<ClickHouseGlobalState> {

    private static final class ComputedColumn {
        private final String name;
        private final String type;
        private final String expression;

        private ComputedColumn(String name, String type, String expression) {
            this.name = name;
            this.type = type;
            this.expression = expression;
        }
    }

    private final ClickHouseGlobalState state;
    private final ExpectedErrors errors = new ExpectedErrors();

    public ClickHouseMaterializedColumnOracle(ClickHouseGlobalState state) {
        this.state = state;
        ClickHouseErrors.addExpectedExpressionErrors(errors);
        ClickHouseErrors.addSessionSettingsErrors(errors);
        errors.add("UNKNOWN_IDENTIFIER");
        errors.add("Unknown identifier");
    }

    @Override
    public void check() throws SQLException {
        if (!state.getClickHouseOptions().materializedColumnOracle) {
            throw new IgnoreMeException();
        }
        List<ClickHouseTable> tables = state.getSchema().getRandomTableNonEmptyTables().getTables();
        List<ClickHouseTable> eligible = new ArrayList<>();
        for (ClickHouseTable t : tables) {
            if (!t.isView()) {
                eligible.add(t);
            }
        }
        if (eligible.isEmpty()) {
            throw new IgnoreMeException();
        }
        ClickHouseTable table = Randomly.fromList(eligible);

        List<ComputedColumn> computed = discoverComputedColumns(table.getName());
        if (computed.isEmpty()) {
            throw new IgnoreMeException();
        }
        ComputedColumn picked = Randomly.fromList(computed);

        String query = "SELECT toString(" + quote(picked.name) + ") AS a, toString(CAST((" + picked.expression
                + ") AS " + picked.type + ")) AS b FROM " + quote(state.getDatabaseName()) + "."
                + quote(table.getName());
        if (state.getOptions().logEachSelect()) {
            state.getLogger().writeCurrent(query);
        }
        try (Statement s = state.getConnection().createStatement(); ResultSet rs = s.executeQuery(query)) {
            long rowIdx = 0;
            while (rs.next()) {
                String a = rs.getString(1);
                boolean aNull = rs.wasNull();
                String b = rs.getString(2);
                boolean bNull = rs.wasNull();
                if (aNull != bNull || !aNull && !a.equals(b)) {
                    throw new AssertionError(String.format(
                            "computed-column value mismatch at row %d:%n  column: %s%n  expr:   %s%n  Q: %s%n  stored=%s%n  recomputed=%s",
                            rowIdx, picked.name, picked.expression, query, aNull ? "NULL" : a, bNull ? "NULL" : b));
                }
                rowIdx++;
            }
        } catch (SQLException ex) {
            throw maybeIgnore(ex);
        }
    }

    private List<ComputedColumn> discoverComputedColumns(String tableName) throws SQLException {
        String query = "SELECT name, type, default_expression FROM system.columns WHERE database = currentDatabase() "
                + "AND table = " + sqlQuote(tableName)
                + " AND default_kind IN ('MATERIALIZED', 'ALIAS') AND default_expression != ''";
        List<ComputedColumn> result = new ArrayList<>();
        try (Statement s = state.getConnection().createStatement(); ResultSet rs = s.executeQuery(query)) {
            while (rs.next()) {
                String name = rs.getString(1);
                String type = rs.getString(2);
                String expression = rs.getString(3);
                if (name == null || type == null || type.isEmpty() || expression == null || expression.isEmpty()) {
                    continue;
                }
                result.add(new ComputedColumn(name, type, expression));
            }
        } catch (SQLException ex) {
            throw maybeIgnore(ex);
        }
        return result;
    }

    private SQLException maybeIgnore(SQLException ex) {
        if (ex.getMessage() != null && errors.errorIsExpected(ex.getMessage())) {
            throw new IgnoreMeException();
        }
        return ex;
    }

    private static String quote(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }

    private static String sqlQuote(String s) {
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }
}
