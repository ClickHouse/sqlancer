package sqlancer.clickhouse.oracle.parallelism;

import java.sql.SQLException;
import java.util.List;

import sqlancer.ComparatorHelper;
import sqlancer.clickhouse.ClickHouseErrors;
import sqlancer.clickhouse.ClickHouseProvider.ClickHouseGlobalState;
import sqlancer.clickhouse.ClickHouseVisitor;
import sqlancer.clickhouse.oracle.tlp.ClickHouseTLPBase;

public class ClickHouseParallelismOracle extends ClickHouseTLPBase {

    public ClickHouseParallelismOracle(ClickHouseGlobalState state) {
        super(state);
        ClickHouseErrors.addSessionSettingsErrors(errors);
    }

    @Override
    public void check() throws SQLException {
        super.check();

        select.setWhereClause(null);
        String baseQuery = ClickHouseVisitor.asString(select);

        String querySerial = baseQuery + " SETTINGS max_threads = 1, max_block_size = 1024";
        String queryParallel = baseQuery + " SETTINGS max_threads = 8, max_block_size = 65536";
        String queryTwoLevel = baseQuery
                + " SETTINGS max_threads = 4, group_by_two_level_threshold = 1, max_block_size = 8192";

        List<String> rowsSerial = ComparatorHelper.getResultSetFirstColumnAsString(querySerial, errors, state);
        List<String> rowsParallel = ComparatorHelper.getResultSetFirstColumnAsString(queryParallel, errors, state);
        List<String> rowsTwoLevel = ComparatorHelper.getResultSetFirstColumnAsString(queryTwoLevel, errors, state);
        ComparatorHelper.assumeResultSetsAreEqual(rowsSerial, rowsParallel, querySerial, List.of(queryParallel), state);
        ComparatorHelper.assumeResultSetsAreEqual(rowsSerial, rowsTwoLevel, querySerial, List.of(queryTwoLevel), state);
    }
}
