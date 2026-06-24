package sqlancer.clickhouse.oracle.partition;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import sqlancer.ComparatorHelper;
import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.clickhouse.ClickHouseErrors;
import sqlancer.clickhouse.ClickHouseProvider.ClickHouseGlobalState;
import sqlancer.clickhouse.ClickHouseSchema;
import sqlancer.clickhouse.ClickHouseSchema.ClickHouseTable;
import sqlancer.clickhouse.ClickHouseToStringVisitor;
import sqlancer.clickhouse.ast.ClickHouseColumnReference;
import sqlancer.clickhouse.ast.ClickHouseExpression;
import sqlancer.clickhouse.ast.ClickHouseSelect;
import sqlancer.clickhouse.ast.ClickHouseTableReference;
import sqlancer.clickhouse.gen.ClickHouseExpressionGenerator;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;

public class ClickHousePartitionMirrorOracle implements TestOracle<ClickHouseGlobalState> {

    private static final AtomicLong MIRROR_COUNTER = new AtomicLong();

    private final ClickHouseGlobalState state;
    private final ExpectedErrors errors = new ExpectedErrors();

    public ClickHousePartitionMirrorOracle(ClickHouseGlobalState state) {
        this.state = state;
        ClickHouseErrors.addExpectedExpressionErrors(errors);
        ClickHouseErrors.addSessionSettingsErrors(errors);
    }

    @Override
    public void check() throws SQLException {
        ClickHouseSchema schema = state.getSchema();
        List<ClickHouseTable> allTables = schema.getRandomTableNonEmptyTables().getTables();
        if (allTables.isEmpty()) {
            throw new IgnoreMeException();
        }

        List<ClickHouseTable> candidates = new java.util.ArrayList<>();
        for (ClickHouseTable t : allTables) {
            if ("MergeTree".equals(t.getEngine())) {
                candidates.add(t);
            }
        }
        if (candidates.isEmpty()) {
            throw new IgnoreMeException();
        }
        ClickHouseTable table = candidates.get((int) Randomly.getNotCachedInteger(0, candidates.size()));

        String createTableText = showCreateTable(table.getName());
        if (createTableText == null || !createTableText.contains("PARTITION BY")) {

            throw new IgnoreMeException();
        }
        String mirror = mirrorName(table.getName());
        String mirrorDdl = stripPartitionBy(createTableText, table.getName(), mirror);
        if (mirrorDdl == null) {

            throw new IgnoreMeException();
        }
        String fqMirror = state.getDatabaseName() + "." + mirror;
        String fqSource = state.getDatabaseName() + "." + table.getName();
        String dropMirror = "DROP TABLE IF EXISTS " + fqMirror + " SYNC";

        try {

            String insertMirror = "INSERT INTO " + fqMirror + " SELECT * FROM " + fqSource;
            if (state.getOptions().logEachSelect()) {

                state.getLogger().writeCurrent(dropMirror);
                state.getLogger().writeCurrent(mirrorDdl);
                state.getLogger().writeCurrent(insertMirror);
                state.getState().logStatement(dropMirror);
                state.getState().logStatement(mirrorDdl);
                state.getState().logStatement(insertMirror);
            }
            new SQLQueryAdapter(dropMirror, errors, true).execute(state, false);
            boolean created = new SQLQueryAdapter(mirrorDdl, errors, true).execute(state, false);
            if (!created) {
                throw new IgnoreMeException();
            }
            boolean inserted = new SQLQueryAdapter(insertMirror, errors, false).execute(state, false);
            if (!inserted) {
                safeDrop(dropMirror);
                throw new IgnoreMeException();
            }
        } catch (SQLException e) {

            safeDrop(dropMirror);
            throw new IgnoreMeException();
        }

        try {
            ClickHouseTableReference sourceRef = new ClickHouseTableReference(table, null);
            List<ClickHouseColumnReference> columns = sourceRef.getColumnReferences();
            if (columns.isEmpty()) {
                throw new IgnoreMeException();
            }
            ClickHouseExpressionGenerator gen = new ClickHouseExpressionGenerator(state).allowAggregates(false);
            gen.addColumns(columns);
            ClickHouseExpression predicate = gen.generatePredicate();

            ClickHouseSelect base = new ClickHouseSelect();
            base.setFromClause(sourceRef);
            base.setFetchColumns(List.of(columns.get(0)));
            base.setWhereClause(predicate);
            String sourceQuery = ClickHouseToStringVisitor.asString(base);

            String mirrorQuery = swapTableIdentifier(sourceQuery, table.getName(), mirror);
            if (mirrorQuery.equals(sourceQuery)) {

                throw new IgnoreMeException();
            }

            List<String> sourceRows;
            try {
                sourceRows = ComparatorHelper.getResultSetFirstColumnAsString(sourceQuery, errors, state);
            } catch (IgnoreMeException e) {
                throw e;
            }
            List<String> mirrorRows = ComparatorHelper.getResultSetFirstColumnAsString(mirrorQuery, errors, state);
            ComparatorHelper.assumeResultSetsAreEqual(sourceRows, mirrorRows, sourceQuery, List.of(mirrorQuery), state);
        } finally {
            safeDrop(dropMirror);
        }
    }

    private void safeDrop(String dropMirror) {
        try {
            new SQLQueryAdapter(dropMirror, errors, true).execute(state, false);
        } catch (SQLException ignored) {

        }
    }

    private String mirrorName(String source) {
        return "pmir_" + source + "_" + MIRROR_COUNTER.incrementAndGet();
    }

    static String stripPartitionBy(String createTableText, String oldName, String newName) {

        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?m)\\s*PARTITION\\s+BY\\b")
                .matcher(createTableText);
        if (!m.find()) {
            return null;
        }
        int partitionIdx = m.start();
        int afterKeyword = m.end();

        java.util.regex.Pattern terminator = java.util.regex.Pattern
                .compile("(?m)(\\s)(ORDER\\s+BY\\b|SAMPLE\\s+BY\\b|TTL\\b|SETTINGS\\b|COMMENT\\b|PRIMARY\\s+KEY\\b)");
        java.util.regex.Matcher tm = terminator.matcher(createTableText);
        int end = createTableText.length();
        if (tm.find(afterKeyword)) {
            end = tm.start();
        }

        String stripped = createTableText.substring(0, partitionIdx) + createTableText.substring(end);

        java.util.regex.Matcher renameMatcher = java.util.regex.Pattern
                .compile("(CREATE\\s+TABLE\\s+(?:[^\\s.]+\\.)?)" + java.util.regex.Pattern.quote(oldName) + "\\b")
                .matcher(stripped);
        if (!renameMatcher.find()) {
            return null;
        }
        return renameMatcher.replaceFirst("$1" + java.util.regex.Matcher.quoteReplacement(newName));
    }

    static String swapTableIdentifier(String query, String oldName, String newName) {

        return query.replaceAll("(?<![\\w])" + java.util.regex.Pattern.quote(oldName) + "(?![\\w])",
                java.util.regex.Matcher.quoteReplacement(newName));
    }

    private String showCreateTable(String tableName) throws SQLException {
        String sql = "SHOW CREATE TABLE " + state.getDatabaseName() + "." + tableName;

        try (Statement s = state.getConnection().createStatement(); ResultSet rs = s.executeQuery(sql)) {
            if (rs.next()) {
                return rs.getString(1);
            }
            return null;
        } catch (SQLException e) {
            if (sqlancer.clickhouse.ClickHouseErrors.isToleratedException(e)) {
                throw new sqlancer.IgnoreMeException();
            }
            throw e;
        }
    }
}
