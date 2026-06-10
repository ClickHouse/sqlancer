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

/**
 * Partition-pruning mirror oracle.
 *
 * <p>
 * For every iteration: pick a table that has a {@code PARTITION BY} clause, introspect its DDL via
 * {@code SHOW CREATE TABLE}, build a sister table with the same column schema and {@code ORDER BY} but <strong>no
 * PARTITION BY</strong>, copy data over, and diff the same generated SELECT against both. Drop the sister before
 * returning.
 *
 * <p>
 * Two bug classes are caught simultaneously by this single shape:
 *
 * <ol>
 * <li><strong>Partition-pruning miscomputation</strong> -- the source table mis-prunes a granule while the no-partition
 * mirror has no pruner to mis-compute. ClickHouse#90240 ({@code toYYYYMM} pruning under a {@code toWeek(date, 3)}
 * predicate) is the canonical example.</li>
 * <li><strong>Physical INSERT routing</strong> -- if a row is written into the wrong partition (the predicate-derived
 * partition value disagrees with the materialised partition value), the source-table SELECT loses it but the mirror
 * still returns it. The KeyConditionOracle does NOT catch this class because the physical layout is fixed before any
 * SELECT runs.</li>
 * </ol>
 *
 * <p>
 * The mirror is created and dropped per oracle invocation (cheap: each iteration's data set is small). We do not
 * pre-create mirrors at {@code generateDatabase} time because most tables do not get a {@code PARTITION BY} clause and
 * pre-creating mirrors would double the database-build cost for no signal.
 */
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
        // Filter to plain-MergeTree tables only. Replacing/Summing/Aggregating/Collapsing engines
        // dedup rows that share the ORDER BY tuple at merge time, scoped within a partition;
        // dropping PARTITION BY in the mirror would change the surviving-row set even with
        // correct KeyCondition behaviour, producing false positives. The engine string is captured
        // at schema-load time in ClickHouseSchema.ClickHouseTable.engine, so this filter costs no
        // server roundtrip.
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

        // Pull the CREATE TABLE text and check there is a PARTITION BY at all. ClickHouse round-trips
        // a stable, unambiguous form via SHOW CREATE TABLE; the regex only has to recognise the
        // "PARTITION BY ... (ORDER BY|SETTINGS|TTL|SAMPLE BY|$)" clause boundary, not parse the
        // expression itself.
        String createTableText = showCreateTable(table.getName());
        if (createTableText == null || !createTableText.contains("PARTITION BY")) {
            // Iteration is a no-op for non-partitioned tables; let another iteration try a different
            // table.
            throw new IgnoreMeException();
        }
        String mirror = mirrorName(table.getName());
        String mirrorDdl = stripPartitionBy(createTableText, table.getName(), mirror);
        if (mirrorDdl == null) {
            // Defensive: if the SHOW CREATE TABLE output drifts to a form the stripper doesn't
            // recognise (e.g. a new ClickHouse rendering convention), don't fail the oracle -- skip.
            throw new IgnoreMeException();
        }
        String fqMirror = state.getDatabaseName() + "." + mirror;
        String fqSource = state.getDatabaseName() + "." + table.getName();
        String dropMirror = "DROP TABLE IF EXISTS " + fqMirror + " SYNC";

        try {
            // Setup is not the subject of the oracle. We pass reportException=false so that any
            // server-side rejection (BAD_ARGUMENTS on partition expression that doesn't survive
            // the strip, race with another worker on the database, etc.) returns boolean false
            // rather than raising AssertionError that would otherwise kill the worker thread.
            // The oracle only asserts on the SELECT diff below.
            String insertMirror = "INSERT INTO " + fqMirror + " SELECT * FROM " + fqSource;
            if (state.getOptions().logEachSelect()) {
                // writeCurrent → live -cur.log; logStatement → state.getStatements(), which is
                // what gets dumped to the persistent database<N>.log on AssertionError. Without
                // the second call the mirror DDL+INSERT is invisible in saved reproducers and the
                // failing SELECT references tables that don't exist on replay.
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
            // Defensive -- execute(state, false) should not throw SQLException for server-side
            // errors (those become return-false), but keep the catch for connection-level issues.
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
            // Render the same query against the mirror by swapping the table identifier. ClickHouse
            // identifier rendering in this codebase is unqualified (no leading schema, no backticks
            // unless inserted by the generator), so a string replace bound to a word boundary on
            // both sides is safe.
            String mirrorQuery = swapTableIdentifier(sourceQuery, table.getName(), mirror);
            if (mirrorQuery.equals(sourceQuery)) {
                // Defensive: if the identifier didn't appear (e.g. wholly aliased SELECT), don't
                // fabricate a comparison.
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
            // Best-effort -- the next database recycle will clean up regardless.
        }
    }

    private String mirrorName(String source) {
        return "pmir_" + source + "_" + MIRROR_COUNTER.incrementAndGet();
    }

    // Strip a PARTITION BY clause from a SHOW CREATE TABLE output and rename the table to the mirror
    // name. SHOW CREATE TABLE on 26.x emits one clause per line ("CREATE TABLE ...\n... \nENGINE
    // = ...\nPARTITION BY ...\nORDER BY ...\nSETTINGS ..."). Terminators may be separated by
    // spaces OR newlines; we treat any whitespace boundary as equivalent. We do NOT attempt to
    // handle nested PARTITION BY inside view subqueries -- the table generator does not emit
    // those.
    static String stripPartitionBy(String createTableText, String oldName, String newName) {
        // (?m) for multiline, ^ at start of a line. PARTITION BY is rendered on its own line by
        // SHOW CREATE TABLE; if a future ClickHouse version inlines it the regex still matches
        // because the whitespace class \s+ tolerates spaces too.
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?m)\\s*PARTITION\\s+BY\\b")
                .matcher(createTableText);
        if (!m.find()) {
            return null;
        }
        int partitionIdx = m.start();
        int afterKeyword = m.end();
        // Find the end of the PARTITION BY clause: scan for the next top-level keyword on either a
        // whitespace or a newline boundary. Word-boundary \b on both sides keeps "SAMPLE BY" from
        // matching inside a column comment or default expression.
        java.util.regex.Pattern terminator = java.util.regex.Pattern
                .compile("(?m)(\\s)(ORDER\\s+BY\\b|SAMPLE\\s+BY\\b|TTL\\b|SETTINGS\\b|COMMENT\\b|PRIMARY\\s+KEY\\b)");
        java.util.regex.Matcher tm = terminator.matcher(createTableText);
        int end = createTableText.length();
        if (tm.find(afterKeyword)) {
            end = tm.start();
        }
        // For the edge case where PARTITION BY is the last clause (no terminator), keep `end =
        // length`. The strip drops [partitionIdx, end).
        String stripped = createTableText.substring(0, partitionIdx) + createTableText.substring(end);
        // Rename: SHOW CREATE TABLE always emits "CREATE TABLE db.name (..." -- we rewrite the
        // first occurrence of the bare table name after the "CREATE TABLE " prefix specifically
        // to avoid clobbering substrings inside column comments / defaults.
        java.util.regex.Matcher renameMatcher = java.util.regex.Pattern
                .compile("(CREATE\\s+TABLE\\s+(?:[^\\s.]+\\.)?)" + java.util.regex.Pattern.quote(oldName) + "\\b")
                .matcher(stripped);
        if (!renameMatcher.find()) {
            return null;
        }
        return renameMatcher.replaceFirst("$1" + java.util.regex.Matcher.quoteReplacement(newName));
    }

    // Word-boundary swap of a base table identifier in a generated query. Unaliased FROM clauses
    // emit `db.table` per the visitor; we only need to swap the unqualified `table` token.
    static String swapTableIdentifier(String query, String oldName, String newName) {
        // (?<![\w]) and (?![\w]) form word boundaries that do not require Unicode awareness for
        // ASCII identifiers -- the table generator never emits non-ASCII names.
        return query.replaceAll("(?<![\\w])" + java.util.regex.Pattern.quote(oldName) + "(?![\\w])",
                java.util.regex.Matcher.quoteReplacement(newName));
    }

    private String showCreateTable(String tableName) throws SQLException {
        String sql = "SHOW CREATE TABLE " + state.getDatabaseName() + "." + tableName;
        // We bypass the SQLQueryAdapter / log capture here because the result set is the payload
        // we care about, not the query's exit status. The connection is shared with the rest of
        // the oracle so the SET-on-connect settings still apply. Tolerated CH errors
        // (MEMORY_LIMIT_EXCEEDED, etc.) trigger IgnoreMeException instead of propagating as
        // raw SQLException reproducers.
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
