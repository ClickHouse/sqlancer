package sqlancer.clickhouse.oracle.qcc;

import java.sql.SQLException;
import java.util.List;

import sqlancer.ComparatorHelper;
import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.clickhouse.ClickHouseErrors;
import sqlancer.clickhouse.ClickHouseProvider.ClickHouseGlobalState;
import sqlancer.clickhouse.ClickHouseVisitor;
import sqlancer.clickhouse.oracle.tlp.ClickHouseTLPBase;
import sqlancer.common.query.SQLQueryAdapter;

public class ClickHouseQueryCacheOracle extends ClickHouseTLPBase {

    public ClickHouseQueryCacheOracle(ClickHouseGlobalState state) {
        super(state);
        ClickHouseErrors.addSessionSettingsErrors(errors);

        errors.add("QUERY_CACHE_USED_WITH_NONDETERMINISTIC_FUNCTIONS");
        errors.add("non-deterministic function");
        errors.add("QUERY_CACHE_USED_WITH_SYSTEM_TABLE");

        errors.add("QUERY_CACHE_USED_WITH_NON_THROW_OVERFLOW_MODE");
    }

    @Override
    public void check() throws SQLException {
        super.check();
        select.setWhereClause(null);
        String body = ClickHouseVisitor.asString(select);

        try {
            new SQLQueryAdapter("SYSTEM DROP QUERY CACHE", errors, false).execute(state);
        } catch (Exception e) {
            throw new IgnoreMeException();
        }

        boolean subqueryCaching = Randomly.getBoolean();
        String cacheOn = " SETTINGS use_query_cache = 1" + (subqueryCaching ? ", query_cache_for_subqueries = 1" : "");

        String truthQuery = body + " SETTINGS use_query_cache = 0";
        List<String> truth = ComparatorHelper.getResultSetFirstColumnAsString(truthQuery, errors, state);

        String cachedQuery = body + cacheOn;
        List<String> written = ComparatorHelper.getResultSetFirstColumnAsString(cachedQuery, errors, state);

        ComparatorHelper.assumeResultSetsAreEqual(truth, written, truthQuery, List.of(cachedQuery), state,
                ComparatorHelper.ComparisonMode.MULTISET);

        List<String> triggers = List.of(
                body + " SETTINGS use_query_cache = 1, max_block_size = " + Randomly.fromOptions(1024, 4096),
                "SELECT count() FROM (" + body + ")" + cacheOn);
        for (String trigger : triggers) {
            try {
                new SQLQueryAdapter(trigger, errors, false).execute(state);
            } catch (SQLException e) {

            }
        }

        List<String> reread = ComparatorHelper.getResultSetFirstColumnAsString(cachedQuery, errors, state);
        ComparatorHelper.assumeResultSetsAreEqual(truth, reread, truthQuery, List.of(cachedQuery), state,
                ComparatorHelper.ComparisonMode.MULTISET);
    }
}
