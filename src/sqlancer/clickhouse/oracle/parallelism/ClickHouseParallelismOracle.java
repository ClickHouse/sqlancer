package sqlancer.clickhouse.oracle.parallelism;

import java.sql.SQLException;
import java.util.List;

import sqlancer.ComparatorHelper;
import sqlancer.clickhouse.ClickHouseErrors;
import sqlancer.clickhouse.ClickHouseProvider.ClickHouseGlobalState;
import sqlancer.clickhouse.ClickHouseVisitor;
import sqlancer.clickhouse.oracle.tlp.ClickHouseTLPBase;

/**
 * Parallelism-differential oracle.
 *
 * <p>
 * Issues the same generated SELECT three times under different threading and chunking profiles, and asserts the result
 * multisets agree:
 *
 * <ol>
 * <li>{@code max_threads = 1, max_block_size = 1024} -- single-thread, small blocks. Forces a sequential merge path
 * and exercises the aggregator's serial reduction code.</li>
 * <li>{@code max_threads = 8, max_block_size = 65536} -- parallel, large blocks. Hits the multi-thread partial-merge
 * and the two-level GROUP BY threshold.</li>
 * <li>{@code max_threads = 4, group_by_two_level_threshold = 1, max_block_size = 8192} -- forces the two-level
 * aggregator path at a low cardinality, which is where ClickHouse#99109 / #99111 (sum(Float64) /
 * projection-vs-full-scan) reproduces.</li>
 * </ol>
 *
 * <p>
 * The base SELECT reuses {@link ClickHouseTLPBase} so JOINs, PREWHERE, FINAL, ARRAY JOIN, and skip-indexes are all
 * exercised. We deliberately drop the WHERE clause from the base for the comparison -- the predicate is generated
 * fresh per call by TLPBase and is not the variable under test here; we want a stable shape across three runs.
 *
 * <p>
 * The two-level GROUP BY corner deserves explicit coverage because the threshold flip is *not* monotonic in
 * cardinality: a 1-row table can hit the two-level path under {@code threshold=1} while the single-thread baseline
 * uses single-level. ClickHouse#99109 is exactly this shape (sum(Float64) GROUP BY producing different results
 * depending on {@code max_threads}).
 */
public class ClickHouseParallelismOracle extends ClickHouseTLPBase {

    public ClickHouseParallelismOracle(ClickHouseGlobalState state) {
        super(state);
        ClickHouseErrors.addSessionSettingsErrors(errors);
    }

    @Override
    public void check() throws SQLException {
        super.check();
        // Drop the WHERE clause to keep the diff comparing the unfiltered base SELECT. Predicate-
        // induced shape variation is what TLP* oracles cover; here we want a stable shape so the
        // parallelism profile is the only variable.
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
