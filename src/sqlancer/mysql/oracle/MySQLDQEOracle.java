package sqlancer.mysql.oracle;

import static sqlancer.ComparatorHelper.getResultSetFirstColumnAsString;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.beust.jcommander.Strings;

import sqlancer.Randomly;
import sqlancer.common.oracle.DQEBase;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.common.query.SQLQueryError;
import sqlancer.common.query.SQLancerResultSet;
import sqlancer.common.schema.AbstractRelationalTable;
import sqlancer.common.schema.AbstractTable;
import sqlancer.common.schema.AbstractTables;
import sqlancer.mysql.MySQLErrors;
import sqlancer.mysql.MySQLGlobalState;
import sqlancer.mysql.MySQLSchema;
import sqlancer.mysql.MySQLSchema.MySQLColumn;
import sqlancer.mysql.MySQLSchema.MySQLTable;
import sqlancer.mysql.MySQLSchema.MySQLTables;
import sqlancer.mysql.MySQLVisitor;
import sqlancer.mysql.ast.MySQLExpression;
import sqlancer.mysql.gen.MySQLExpressionGenerator;

public class MySQLDQEOracle extends DQEBase<MySQLGlobalState> implements TestOracle<MySQLGlobalState> {

    private final MySQLSchema schema;
    private static final String APPEND_ORDER_BY = "%s ORDER BY %s";
    private static final String APPEND_LIMIT = "%s LIMIT %d";
    private final List<String> orderColumns = new ArrayList<>();
    private boolean generateLimit;
    private boolean generateOrderBy;
    private boolean operateOnSingleTable;
    private int limit;

    public MySQLDQEOracle(MySQLGlobalState state) {
        super(state);
        schema = state.getSchema();

        MySQLErrors.addExpressionErrors(selectExpectedErrors);

        MySQLErrors.addExpressionErrors(updateExpectedErrors);
        MySQLErrors.addInsertUpdateErrors(updateExpectedErrors);

        MySQLErrors.addExpressionErrors(deleteExpectedErrors);
        deleteExpectedErrors.add("a foreign key constraint fails");
    }

    @Override
    public String generateSelectStatement(AbstractTables<?, ?> tables, String tableName, String whereClauseStr) {
        operateOnSingleTable = tables.getTables().size() == 1;
        List<String> selectColumns = new ArrayList<>();
        MySQLTables mySQLTables = (MySQLTables) tables;
        for (MySQLTable table : mySQLTables.getTables()) {
            selectColumns.add(table.getName() + "." + COLUMN_ROWID);
        }
        if (operateOnSingleTable && Randomly.getBooleanWithSmallProbability()) {
            generateOrderBy = true;

            for (MySQLColumn column : Randomly.nonEmptySubset(mySQLTables.getColumns())) {
                orderColumns.add(column.getFullQualifiedName());
            }

            if (Randomly.getBooleanWithRatherLowProbability()) {
                generateLimit = true;
                limit = (int) Randomly.getNotCachedInteger(1, 10);
            }
        }

        String selectStmt = String.format("SELECT %s FROM %s WHERE %s", Strings.join(",", selectColumns).toLowerCase(),
                tableName, whereClauseStr);
        if (generateOrderBy) {
            selectStmt = String.format(APPEND_ORDER_BY, selectStmt, String.join(",", orderColumns));
            if (generateLimit) {
                selectStmt = String.format(APPEND_LIMIT, selectStmt, limit);
            }
        }
        return selectStmt;
    }

    @Override
    public String generateUpdateStatement(AbstractTables<?, ?> tables, String tableName, String whereClauseStr) {
        List<String> updateColumns = new ArrayList<>();
        MySQLTables mySQLTables = (MySQLTables) tables;
        for (MySQLTable table : mySQLTables.getTables()) {
            updateColumns.add(String.format("%s = 1", table.getName() + "." + COLUMN_UPDATED));
        }
        String updateStmt = String.format("UPDATE %s SET %s WHERE %s", tableName, Strings.join(",", updateColumns),
                whereClauseStr);
        if (generateOrderBy) {
            updateStmt = String.format(APPEND_ORDER_BY, updateStmt, String.join(",", orderColumns));
            if (generateLimit) {
                updateStmt = String.format(APPEND_LIMIT, updateStmt, limit);
            }
        }
        return updateStmt;
    }

    @Override
    public String generateDeleteStatement(String tableName, String whereClauseStr) {
        String deleteStmt;
        if (operateOnSingleTable) {
            deleteStmt = String.format("DELETE FROM %s WHERE %s", tableName, whereClauseStr);
            if (generateOrderBy) {
                deleteStmt = String.format(APPEND_ORDER_BY, deleteStmt, String.join(",", orderColumns));
                if (generateLimit) {
                    deleteStmt = String.format(APPEND_LIMIT, deleteStmt, limit);
                }
            }
        } else {
            deleteStmt = String.format("DELETE %s FROM %s WHERE %s", tableName, tableName, whereClauseStr);
        }
        return deleteStmt;
    }

    @Override
    public void check() throws SQLException {

        MySQLTables tables = schema.getRandomTableNonEmptyTables();
        String tableName = tables.getTables().stream().map(AbstractTable::getName).collect(Collectors.joining(","));

        MySQLExpressionGenerator expressionGenerator = new MySQLExpressionGenerator(state)
                .setColumns(tables.getColumns());
        MySQLExpression whereClause = expressionGenerator.generateExpression();

        String whereClauseStr = MySQLVisitor.asString(whereClause);

        String selectStmt = generateSelectStatement(tables, tableName, whereClauseStr);

        String updateStmt = generateUpdateStatement(tables, tableName, whereClauseStr);

        String deleteStmt = generateDeleteStatement(tableName, whereClauseStr);

        for (MySQLTable table : tables.getTables()) {
            addAuxiliaryColumns(table);
        }

        state.getState().getLocalState().log(selectStmt);
        SQLQueryResult selectExecutionResult = executeSelect(selectStmt, tables);
        state.getState().getLocalState().log(selectExecutionResult.getAccessedRows().values().toString());
        state.getState().getLocalState().log(selectExecutionResult.getQueryErrors().toString());

        state.getState().getLocalState().log(updateStmt);
        SQLQueryResult updateExecutionResult = executeUpdate(updateStmt, tables);
        state.getState().getLocalState().log(updateExecutionResult.getAccessedRows().values().toString());
        state.getState().getLocalState().log(updateExecutionResult.getQueryErrors().toString());

        state.getState().getLocalState().log(deleteStmt);
        SQLQueryResult deleteExecutionResult = executeDelete(deleteStmt, tables);
        state.getState().getLocalState().log(deleteExecutionResult.getAccessedRows().values().toString());
        state.getState().getLocalState().log(deleteExecutionResult.getQueryErrors().toString());

        String compareSelectAndUpdate = compareSelectAndUpdate(selectExecutionResult, updateExecutionResult);
        String compareSelectAndDelete = compareSelectAndDelete(selectExecutionResult, deleteExecutionResult);
        String compareUpdateAndDelete = compareUpdateAndDelete(updateExecutionResult, deleteExecutionResult);

        String errorMessage = compareSelectAndUpdate == null ? "" : compareSelectAndUpdate + "\n";
        errorMessage += compareSelectAndDelete == null ? "" : compareSelectAndDelete + "\n";
        errorMessage += compareUpdateAndDelete == null ? "" : compareUpdateAndDelete + "\n";

        if (!errorMessage.isEmpty()) {
            throw new AssertionError(errorMessage);
        }

        for (MySQLTable table : tables.getTables()) {
            dropAuxiliaryColumns(table);
        }
    }

    public String compareSelectAndUpdate(SQLQueryResult selectResult, SQLQueryResult updateResult) {
        if (updateResult.hasEmptyErrors()) {
            if (!selectResult.hasEmptyErrors()) {
                return "SELECT has errors, but UPDATE does not.";
            }
            if (!selectResult.hasSameAccessedRows(updateResult)) {
                return "SELECT accessed different rows from UPDATE.";
            }
        } else {
            if (hasUpdateSpecificErrors(updateResult)) {
                if (updateResult.hasAccessedRows()) {
                    return "UPDATE accessed non-empty rows when specific errors happen.";
                } else {

                    return null;
                }
            }

            List<SQLQueryError> selectErrors = new ArrayList<>(selectResult.getQueryErrors());
            for (int i = 0; i < updateResult.getQueryErrors().size(); i++) {
                SQLQueryError updateError = updateResult.getQueryErrors().get(i);
                if (!isFound(selectErrors, updateError)) {
                    return "SELECT has different errors from UPDATE.";
                }
            }

            if (hasStopErrors(updateResult)) {
                if (updateResult.hasAccessedRows()) {
                    return "UPDATE accessed non-empty rows when stop errors happen.";
                }
            } else {
                if (!selectResult.hasSameAccessedRows(updateResult)) {
                    return "SELECT accessed different rows from UPDATE when errors happen.";
                }
            }
        }
        return null;
    }

    private static boolean isFound(List<SQLQueryError> selectErrors, SQLQueryError targetError) {
        boolean found = false;
        for (int i = 0; i < selectErrors.size(); i++) {
            SQLQueryError selectError = selectErrors.get(i);
            if (selectError.hasSameCodeAndMessage(targetError)) {
                selectErrors.remove(i);
                found = true;
                break;
            }
        }
        return found;
    }

    public String compareSelectAndDelete(SQLQueryResult selectResult, SQLQueryResult deleteResult) {
        if (deleteResult.hasEmptyErrors()) {
            if (!selectResult.hasEmptyErrors()) {
                return "SELECT has errors, but DELETE does not.";
            }
            if (!selectResult.hasSameAccessedRows(deleteResult)) {
                return "SELECT accessed different rows from DELETE.";
            }
        } else {
            if (hasDeleteSpecificErrors(deleteResult)) {
                if (deleteResult.hasAccessedRows()) {
                    return "DELETE accessed non-empty rows when specific errors happen.";
                } else {

                    return null;
                }
            }

            List<SQLQueryError> selectErrors = new ArrayList<>(selectResult.getQueryErrors());
            for (int i = 0; i < deleteResult.getQueryErrors().size(); i++) {
                SQLQueryError deleteError = deleteResult.getQueryErrors().get(i);
                if (!isFound(selectErrors, deleteError)) {
                    return "SELECT has different errors from DELETE.";
                }
            }

            if (hasStopErrors(deleteResult)) {
                if (deleteResult.hasAccessedRows()) {
                    return "DELETE accessed non-empty rows when stop errors happen.";
                }
            } else {
                if (!selectResult.hasSameAccessedRows(deleteResult)) {
                    return "SELECT accessed different rows from DELETE when errors happen.";
                }
            }
        }
        return null;
    }

    public String compareUpdateAndDelete(SQLQueryResult updateResult, SQLQueryResult deleteResult) {
        if (updateResult.hasEmptyErrors() && deleteResult.hasEmptyErrors()) {
            if (updateResult.hasSameAccessedRows(deleteResult)) {
                return null;
            } else {
                return "UPDATE accessed different rows from DELETE.";
            }
        } else {
            boolean hasSpecificErrors = false;

            if (hasUpdateSpecificErrors(updateResult)) {
                hasSpecificErrors = true;
                if (updateResult.hasAccessedRows()) {
                    return "UPDATE accessed non-empty rows when specific errors happen.";
                }
            }

            if (hasDeleteSpecificErrors(deleteResult)) {
                hasSpecificErrors = true;
                if (deleteResult.hasAccessedRows()) {
                    return "DELETE accessed non-empty rows when specific errors happen.";
                }
            }

            if (hasSpecificErrors) {
                return null;
            }

            if (!updateResult.hasSameErrors(deleteResult)) {
                return "UPDATE has different errors from DELETE.";
            } else {
                if (!hasStopErrors(updateResult)) {
                    if (!updateResult.hasSameAccessedRows(deleteResult)) {
                        return "UPDATE accessed different rows from DELETE.";
                    }
                } else {
                    if (updateResult.hasAccessedRows() || deleteResult.hasAccessedRows()) {
                        return "UPDATE or DELETE accessed non-empty rows when stop errors happen.";
                    }
                }
            }

            return null;
        }
    }

    private boolean hasUpdateSpecificErrors(SQLQueryResult updateResult) {
        return updateResult.getQueryErrors().stream().anyMatch(
                error -> new MySQLErrorCodeStrategy().getUpdateSpecificErrorCodes().contains(error.getCode()));
    }

    private boolean hasDeleteSpecificErrors(SQLQueryResult deleteResult) {
        return deleteResult.getQueryErrors().stream().anyMatch(
                error -> new MySQLErrorCodeStrategy().getDeleteSpecificErrorCodes().contains(error.getCode()));

    }

    private boolean hasStopErrors(SQLQueryResult queryResult) {
        return queryResult.getQueryErrors().stream()
                .anyMatch(error -> error.getLevel() == SQLQueryError.ErrorLevel.ERROR);
    }

    private SQLQueryResult executeSelect(String selectStmt, MySQLTables tables) throws SQLException {
        Map<AbstractRelationalTable<?, ?, ?>, Set<String>> accessedRows = new HashMap<>();
        List<SQLQueryError> queryErrors;
        SQLancerResultSet resultSet = null;
        try {
            resultSet = new SQLQueryAdapter(selectStmt, selectExpectedErrors).executeAndGet(state, false);
        } catch (SQLException ignored) {

        } finally {
            queryErrors = getErrors();

            if (resultSet != null) {
                for (MySQLTable table : tables.getTables()) {
                    HashSet<String> rows = new HashSet<>();
                    accessedRows.put(table, rows);
                }
                while (resultSet.next()) {
                    for (MySQLTable table : tables.getTables()) {
                        accessedRows.get(table).add(resultSet.getString(table.getName() + "." + COLUMN_ROWID));
                    }
                }
                resultSet.close();
            }
        }

        return new SQLQueryResult(accessedRows, queryErrors);
    }

    private SQLQueryResult executeUpdate(String updateStmt, MySQLTables tables) throws SQLException {
        Map<AbstractRelationalTable<?, ?, ?>, Set<String>> accessedRows = new HashMap<>();
        List<SQLQueryError> queryErrors;
        try {
            new SQLQueryAdapter("BEGIN").execute(state, false);
            new SQLQueryAdapter(updateStmt, updateExpectedErrors).execute(state, false);
        } catch (SQLException ignored) {

        } finally {
            queryErrors = getErrors();

            for (MySQLTable table : tables.getTables()) {
                String tableName = table.getName();
                String rowId = tableName + "." + COLUMN_ROWID;
                String updated = tableName + "." + COLUMN_UPDATED;
                String selectRowIdWithUpdated = String.format("SELECT %s FROM %s WHERE %s = 1", rowId, tableName,
                        updated);
                HashSet<String> rows = new HashSet<>(
                        getResultSetFirstColumnAsString(selectRowIdWithUpdated, updateExpectedErrors, state));
                accessedRows.put(table, rows);
            }

            new SQLQueryAdapter("ROLLBACK").execute(state, false);
        }

        return new SQLQueryResult(accessedRows, queryErrors);
    }

    private SQLQueryResult executeDelete(String deleteStmt, MySQLTables tables) throws SQLException {
        Map<AbstractRelationalTable<?, ?, ?>, Set<String>> accessedRows = new HashMap<>();
        List<SQLQueryError> queryErrors;
        try {
            for (MySQLTable table : tables.getTables()) {
                String tableName = table.getName();
                String rowId = tableName + "." + COLUMN_ROWID;
                String selectRowId = String.format("SELECT %s FROM %s", rowId, tableName);
                HashSet<String> rows = new HashSet<>(
                        getResultSetFirstColumnAsString(selectRowId, deleteExpectedErrors, state));
                accessedRows.put(table, rows);
            }

            new SQLQueryAdapter("BEGIN").execute(state, false);
            new SQLQueryAdapter(deleteStmt, deleteExpectedErrors).execute(state, false);
        } catch (SQLException ignored) {

        } finally {
            queryErrors = getErrors();

            for (MySQLTable table : tables.getTables()) {
                String tableName = table.getName();
                String rowId = tableName + "." + COLUMN_ROWID;
                String selectRowId = String.format("SELECT %s FROM %s", rowId, tableName);
                HashSet<String> rows = new HashSet<>(
                        getResultSetFirstColumnAsString(selectRowId, deleteExpectedErrors, state));
                accessedRows.get(table).removeAll(rows);
            }

            new SQLQueryAdapter("ROLLBACK").execute(state, false);
        }

        return new SQLQueryResult(accessedRows, queryErrors);
    }

    private List<SQLQueryError> getErrors() throws SQLException {
        SQLancerResultSet resultSet = new SQLQueryAdapter("SHOW WARNINGS").executeAndGet(state, false);
        List<SQLQueryError> queryErrors = new ArrayList<>();
        if (resultSet != null) {
            while (resultSet.next()) {
                SQLQueryError queryError = new SQLQueryError();
                queryError.setLevel(resultSet.getString("Level").equalsIgnoreCase("ERROR")
                        ? SQLQueryError.ErrorLevel.ERROR : SQLQueryError.ErrorLevel.WARNING);
                queryError.setCode(resultSet.getInt("Code"));
                queryError.setMessage(resultSet.getString("Message"));
                queryErrors.add(queryError);
            }
            resultSet.close();
        }
        Collections.sort(queryErrors);
        return queryErrors;
    }

    @Override
    public void addAuxiliaryColumns(AbstractRelationalTable<?, ?, ?> table) throws SQLException {
        String tableName = table.getName();

        String addColumnRowID = String.format("ALTER TABLE %s ADD %s TEXT", tableName, COLUMN_ROWID);
        new SQLQueryAdapter(addColumnRowID).execute(state, false);
        state.getState().getLocalState().log(addColumnRowID);

        String addColumnUpdated = String.format("ALTER TABLE %s ADD %s INT DEFAULT 0", tableName, COLUMN_UPDATED);
        new SQLQueryAdapter(addColumnUpdated).execute(state, false);
        state.getState().getLocalState().log(addColumnUpdated);

        String updateRowsWithUniqueID = String.format("UPDATE %s SET %s = UUID()", tableName, COLUMN_ROWID);
        new SQLQueryAdapter(updateRowsWithUniqueID).execute(state, false);
        state.getState().getLocalState().log(updateRowsWithUniqueID);
    }

    public static class MySQLErrorCodeStrategy implements ErrorCodeStrategy {
        @Override
        public Set<Integer> getUpdateSpecificErrorCodes() {

            return Set.of(1048, 1062, 3105);
        }

        @Override
        public Set<Integer> getDeleteSpecificErrorCodes() {

            return Set.of(1451);
        }
    }
}
