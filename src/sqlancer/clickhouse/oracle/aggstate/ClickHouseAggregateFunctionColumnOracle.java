package sqlancer.clickhouse.oracle.aggstate;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import sqlancer.ComparatorHelper;
import sqlancer.IgnoreMeException;
import sqlancer.clickhouse.ClickHouseErrors;
import sqlancer.clickhouse.ClickHouseProvider.ClickHouseGlobalState;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;

public class ClickHouseAggregateFunctionColumnOracle implements TestOracle<ClickHouseGlobalState> {

    private static final AtomicLong CTR = new AtomicLong();

    private final ClickHouseGlobalState state;

    private final ExpectedErrors errors = new ExpectedErrors();

    public ClickHouseAggregateFunctionColumnOracle(ClickHouseGlobalState state) {
        this.state = state;
        ClickHouseErrors.addSessionSettingsErrors(errors);
        ClickHouseErrors.addExpectedExpressionErrors(errors);
        errors.add("UNKNOWN_TABLE");
        errors.add("(MEMORY_LIMIT_EXCEEDED)");
        errors.add("memory limit exceeded");
        errors.add("TIMEOUT_EXCEEDED");
        errors.add("Timeout exceeded");
        errors.add("Limit for result exceeded");
        errors.add("TOO_MANY_ROWS_OR_BYTES");
        errors.add("NOT_IMPLEMENTED");
        errors.add("ILLEGAL_TYPE_OF_ARGUMENT");
        errors.add("Incompatible data types");
    }

    @Override
    public void check() throws SQLException {
        if (!state.getClickHouseOptions().aggregateFunctionColumnOracle) {
            throw new IgnoreMeException();
        }
        long id = CTR.incrementAndGet();
        String src = state.getDatabaseName() + ".aggcol_src_" + id;
        String agg = state.getDatabaseName() + ".aggcol_agg_" + id;
        try {
            setupAndVerify(src, agg);
        } finally {
            dropQuietly(src);
            dropQuietly(agg);
        }
    }

    private void setupAndVerify(String src, String agg) throws SQLException {
        String createSrc = "CREATE TABLE " + src
                + " (k UInt32, v Int64) ENGINE = MergeTree ORDER BY k";
        if (!execute(createSrc)) {
            throw new IgnoreMeException();
        }

        String insertSrc1 = "INSERT INTO " + src + " (k, v) VALUES "
                + "(0,-3),(1,7),(2,-1),(3,5),(0,2),(1,-4),(2,9),(3,-6),(4,1),(5,8),(6,-2),"
                + "(0,10),(1,0),(2,-5),(3,3),(4,7),(5,-9),(6,4)";
        if (!execute(insertSrc1)) {
            throw new IgnoreMeException();
        }

        String insertSrc2 = "INSERT INTO " + src + " (k, v) VALUES "
                + "(0,-7),(1,6),(2,3),(3,-2),(4,-4),(5,11),(6,-8),"
                + "(0,1),(1,-3),(2,5),(3,0),(4,9),(5,-1),(6,2)";
        if (!execute(insertSrc2)) {
            throw new IgnoreMeException();
        }

        String createAgg = "CREATE TABLE " + agg
                + " (k UInt32,"
                + " s AggregateFunction(sum, Int64),"
                + " mn AggregateFunction(min, Int64),"
                + " mx AggregateFunction(max, Int64),"
                + " c AggregateFunction(count)"
                + ") ENGINE = AggregatingMergeTree ORDER BY k";
        if (!execute(createAgg)) {
            throw new IgnoreMeException();
        }

        String insertAgg1 = "INSERT INTO " + agg
                + " SELECT k, sumState(v), minState(v), maxState(v), countState()"
                + " FROM " + src + " WHERE v % 2 = 0 GROUP BY k";
        if (!execute(insertAgg1)) {
            throw new IgnoreMeException();
        }

        String insertAgg2 = "INSERT INTO " + agg
                + " SELECT k, sumState(v), minState(v), maxState(v), countState()"
                + " FROM " + src + " WHERE v % 2 != 0 GROUP BY k";
        if (!execute(insertAgg2)) {
            throw new IgnoreMeException();
        }

        String optimize = "OPTIMIZE TABLE " + agg + " FINAL";
        if (!execute(optimize)) {
            throw new IgnoreMeException();
        }

        String queryMerge = "SELECT toString(tuple(k, sumMerge(s), minMerge(mn), maxMerge(mx), countMerge(c)))"
                + " FROM " + agg + " GROUP BY k ORDER BY k";
        String queryDirect = "SELECT toString(tuple(k, sum(v), min(v), max(v), count()))"
                + " FROM " + src + " GROUP BY k ORDER BY k";

        List<String> mergeRows = ComparatorHelper.getResultSetFirstColumnAsString(queryMerge, errors, state);
        List<String> directRows = ComparatorHelper.getResultSetFirstColumnAsString(queryDirect, errors, state);

        if (mergeRows.size() != directRows.size()) {
            throw new AssertionError(String.format(
                    "AggregateFunction column oracle: row count mismatch: "
                            + "merge query returned %d rows, direct query returned %d rows.\n"
                            + "merge query: %s\ndirect query: %s",
                    mergeRows.size(), directRows.size(), queryMerge, queryDirect));
        }

        List<String> diffs = new ArrayList<>();
        for (int i = 0; i < mergeRows.size() && diffs.size() < 5; i++) {
            String m = mergeRows.get(i);
            String d = directRows.get(i);
            if (!m.equals(d)) {
                diffs.add("row " + i + ": merge=" + m + " direct=" + d);
            }
        }
        if (!diffs.isEmpty()) {
            throw new AssertionError(String.format(
                    "AggregateFunction column oracle: merge vs direct mismatch (first differing entries: %s).\n"
                            + "merge query: %s\ndirect query: %s",
                    diffs, queryMerge, queryDirect));
        }
    }

    private boolean execute(String stmt) throws SQLException {
        logStmt(stmt);
        return new SQLQueryAdapter(stmt, errors, true).execute(state);
    }

    private void dropQuietly(String table) {
        try {
            new SQLQueryAdapter("DROP TABLE IF EXISTS " + table, errors, true).execute(state);
        } catch (Exception | AssertionError ignored) {
        }
    }

    private void logStmt(String stmt) {
        if (state.getOptions().logEachSelect()) {
            state.getLogger().writeCurrent(stmt);
            state.getState().logStatement(stmt);
        }
    }
}
