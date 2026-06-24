package sqlancer.clickhouse.oracle.view;

import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import sqlancer.ComparatorHelper;
import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.clickhouse.ClickHouseErrors;
import sqlancer.clickhouse.ClickHouseProvider.ClickHouseGlobalState;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;

public class ClickHouseMaterializedViewConsistencyOracle implements TestOracle<ClickHouseGlobalState> {

    private static final AtomicLong MV_COUNTER = new AtomicLong();

    private final ClickHouseGlobalState state;
    private final ExpectedErrors errors = new ExpectedErrors();

    public ClickHouseMaterializedViewConsistencyOracle(ClickHouseGlobalState state) {
        this.state = state;
        ClickHouseErrors.addExpectedExpressionErrors(errors);
        ClickHouseErrors.addSessionSettingsErrors(errors);

        errors.add("UNKNOWN_TABLE");
        errors.add("Unknown table expression identifier");
    }

    @Override
    public void check() throws SQLException {
        long id = MV_COUNTER.incrementAndGet();
        String db = state.getDatabaseName();
        String src = db + ".mvsrc_" + id;
        String mv = db + ".mv_" + id;

        int keyCardinality = 2 + (int) Randomly.getNotCachedInteger(0, 18);
        int blocks = 2 + (int) Randomly.getNotCachedInteger(0, 3);
        int rowsPerBlock = 20 + (int) Randomly.getNotCachedInteger(0, 200);

        boolean aggregating = Randomly.getBoolean();
        String createSrc = "CREATE TABLE " + src + " (k Int32, v Int64) ENGINE = MergeTree ORDER BY k";
        String createMv;
        String mvRead;

        String mvTotal;
        if (aggregating) {
            createMv = "CREATE MATERIALIZED VIEW " + mv + " ENGINE = AggregatingMergeTree() ORDER BY k AS "
                    + "SELECT k, sumState(v) AS sv, countState() AS cv FROM " + src + " GROUP BY k";
            mvRead = "SELECT concat(toString(k), '#', toString(sumMerge(sv)), '#', toString(countMerge(cv))) FROM " + mv
                    + " GROUP BY k ORDER BY k";
            mvTotal = "SELECT toString(countMerge(cv)) FROM " + mv;
        } else {
            createMv = "CREATE MATERIALIZED VIEW " + mv + " ENGINE = SummingMergeTree() ORDER BY k AS "
                    + "SELECT k, sum(v) AS sv, count() AS cv FROM " + src + " GROUP BY k";
            mvRead = "SELECT concat(toString(k), '#', toString(sum(sv)), '#', toString(sum(cv))) FROM " + mv
                    + " GROUP BY k ORDER BY k";
            mvTotal = "SELECT toString(sum(cv)) FROM " + mv;
        }
        String srcTotal = "SELECT toString(count()) FROM " + src;
        String groundTruth = "SELECT concat(toString(k), '#', toString(sum(v)), '#', toString(count())) FROM " + src
                + " GROUP BY k ORDER BY k";
        String dropMv = "DROP VIEW IF EXISTS " + mv;
        String dropSrc = "DROP TABLE IF EXISTS " + src;

        if (state.getOptions().logEachSelect()) {

            for (String stmt : List.of(dropMv, dropSrc, createSrc, createMv)) {
                state.getLogger().writeCurrent(stmt);
                state.getState().logStatement(stmt);
            }
        }

        try {

            if (!new SQLQueryAdapter(createSrc, errors, true).execute(state)) {
                throw new IgnoreMeException();
            }
            if (!new SQLQueryAdapter(createMv, errors, true).execute(state)) {
                throw new IgnoreMeException();
            }

            for (int b = 0; b < blocks; b++) {
                long offset = (long) b * rowsPerBlock;

                String insert = "INSERT INTO " + src + " SELECT toInt32(number % " + keyCardinality
                        + ") AS k, toInt64(number) AS v FROM numbers(" + offset + ", " + rowsPerBlock + ")";
                if (state.getOptions().logEachSelect()) {
                    state.getLogger().writeCurrent(insert);
                    state.getState().logStatement(insert);
                }
                if (!new SQLQueryAdapter(insert, errors, true).execute(state)) {
                    throw new IgnoreMeException();
                }
            }

            List<String> srcCnt = ComparatorHelper.getResultSetFirstColumnAsString(srcTotal, errors, state);
            List<String> mvCnt = ComparatorHelper.getResultSetFirstColumnAsString(mvTotal, errors, state);
            if (srcCnt.size() != 1 || mvCnt.size() != 1 || !srcCnt.get(0).equals(mvCnt.get(0))) {
                throw new IgnoreMeException();
            }

            if (state.getOptions().logEachSelect()) {
                state.getLogger().writeCurrent(groundTruth);
                state.getLogger().writeCurrent(mvRead);
            }
            List<String> baseRows = ComparatorHelper.getResultSetFirstColumnAsString(groundTruth, errors, state);
            List<String> mvRows = ComparatorHelper.getResultSetFirstColumnAsString(mvRead, errors, state);
            ComparatorHelper.assumeResultSetsAreEqual(baseRows, mvRows, groundTruth, List.of(mvRead), state);
        } finally {
            try {
                new SQLQueryAdapter(dropMv, errors, true).execute(state);
            } catch (SQLException ignored) {

            }
            try {
                new SQLQueryAdapter(dropSrc, errors, true).execute(state);
            } catch (SQLException ignored) {

            }
        }
    }
}
