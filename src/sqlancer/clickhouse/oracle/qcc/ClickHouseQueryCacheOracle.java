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

/**
 * Query-result-cache coherence oracle (settings-coverage plan section 5): the {@code use_query_cache} sibling of
 * {@link ClickHouseQueryConditionCacheOracle}, which established the A-then-B template for cross-query cache-poisoning
 * bugs (#104781 pattern). The query cache proper is structurally identical risk -- a server-wide cache keyed on query
 * text + settings, holding serialized result blocks -- and was previously untested here.
 *
 * <p>
 * Soundness note: ClickHouse's query cache deliberately does NOT invalidate on data change (entries live for
 * {@code query_cache_ttl}), so the naive "SELECT, mutate, SELECT must see new data" protocol would report documented
 * staleness as a bug. This oracle therefore never modifies data mid-check; what it asserts is cache
 * <i>mechanics</i>, each of which must be result-preserving:
 *
 * <ol>
 * <li><b>Write-path transparency:</b> the first cached execution (cache miss + entry write) must return the same
 * result as an uncached run.</li>
 * <li><b>Serialization round-trip + key collisions:</b> after trigger queries with nearby cache keys (same text under
 * different settings, and a structurally different wrapper query) execute against the cache, re-running the original
 * query must serve the original result -- a divergence means either a corrupted entry read back differently than it
 * was written, or a trigger's entry was served for the wrong key.</li>
 * </ol>
 *
 * <p>
 * Concurrency / TTL are absorbed structurally: another worker dropping the server-wide cache, an evicted or expired
 * entry, or an entry that was never written (size limits) all make the re-read recompute -- which must still equal the
 * uncached truth, so no false positive is possible from cache absence; absence merely degrades this check toward arm 1.
 *
 * <p>
 * {@code query_cache_for_subqueries} (26.5, default false) is flipped on for a random subset of iterations to reach
 * the per-subquery caching path.
 */
public class ClickHouseQueryCacheOracle extends ClickHouseTLPBase {

    public ClickHouseQueryCacheOracle(ClickHouseGlobalState state) {
        super(state);
        ClickHouseErrors.addSessionSettingsErrors(errors);
        // The cache refuses queries with non-deterministic functions (code 704) or system-table
        // reads (code 719) under the default 'throw' handling; the generator can legitimately emit
        // the former. Refusal means "this iteration is uninformative", not a bug.
        errors.add("QUERY_CACHE_USED_WITH_NONDETERMINISTIC_FUNCTIONS");
        errors.add("non-deterministic function");
        errors.add("QUERY_CACHE_USED_WITH_SYSTEM_TABLE");
        // Code 731: cache + non-throw overflow mode. The provider pins result_overflow_mode='throw'
        // on every connection, but keep the refusal absorbed in case a per-query SETTINGS suffix in
        // a future TLPBase emission changes the mode.
        errors.add("QUERY_CACHE_USED_WITH_NON_THROW_OVERFLOW_MODE");
    }

    @Override
    public void check() throws SQLException {
        super.check();
        select.setWhereClause(null);
        String body = ClickHouseVisitor.asString(select);

        // Start from a clean server-wide cache so the write arm is a genuine cache miss. Failures
        // (permissions, cache compiled out) leave nothing to test.
        try {
            new SQLQueryAdapter("SYSTEM DROP QUERY CACHE", errors, false).execute(state);
        } catch (Exception e) {
            throw new IgnoreMeException();
        }

        boolean subqueryCaching = Randomly.getBoolean();
        String cacheOn = " SETTINGS use_query_cache = 1" + (subqueryCaching ? ", query_cache_for_subqueries = 1" : "");

        String truthQuery = body + " SETTINGS use_query_cache = 0";
        List<String> truth = ComparatorHelper.getResultSetFirstColumnAsString(truthQuery, errors, state);

        // Arm 1: cache miss + entry write must be transparent.
        String cachedQuery = body + cacheOn;
        List<String> written = ComparatorHelper.getResultSetFirstColumnAsString(cachedQuery, errors, state);
        // MULTISET: a corrupted cache entry that shuffles duplicate counts must not slip through a
        // set-shaped comparison.
        ComparatorHelper.assumeResultSetsAreEqual(truth, written, truthQuery, List.of(cachedQuery), state,
                ComparatorHelper.ComparisonMode.MULTISET);

        // Triggers: occupy nearby cache keys. Settings are part of the cache key, so the same text
        // under different execution settings must land in a different slot; the count() wrapper is
        // a structurally different query whose result must never be served for the original key.
        // Trigger failures are absorbed -- they only matter if they poison the re-read below.
        List<String> triggers = List.of(
                body + " SETTINGS use_query_cache = 1, max_block_size = " + Randomly.fromOptions(1024, 4096),
                "SELECT count() FROM (" + body + ")" + cacheOn);
        for (String trigger : triggers) {
            try {
                new SQLQueryAdapter(trigger, errors, false).execute(state);
            } catch (SQLException e) {
                // Uninformative trigger; continue.
            }
        }

        // Arm 2: identical text + settings as the write arm -- served from the cache when the entry
        // survived, recomputed otherwise; either way it must equal the uncached truth.
        List<String> reread = ComparatorHelper.getResultSetFirstColumnAsString(cachedQuery, errors, state);
        ComparatorHelper.assumeResultSetsAreEqual(truth, reread, truthQuery, List.of(cachedQuery), state,
                ComparatorHelper.ComparisonMode.MULTISET);
    }
}
