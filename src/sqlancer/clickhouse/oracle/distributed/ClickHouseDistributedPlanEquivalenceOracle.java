package sqlancer.clickhouse.oracle.distributed;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;

import sqlancer.ComparatorHelper;
import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.clickhouse.ClickHouseErrors;
import sqlancer.clickhouse.ClickHouseProvider.ClickHouseGlobalState;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;

public class ClickHouseDistributedPlanEquivalenceOracle implements TestOracle<ClickHouseGlobalState> {

    private static final int DIFF_LIMIT = 20;
    private static final AtomicLong DPE_COUNTER = new AtomicLong();

    private static final String PARALLEL_REPLICAS_SETTINGS = "enable_parallel_replicas = 1, max_parallel_replicas = 3, "
            + "cluster_for_parallel_replicas = 'default', parallel_replicas_for_non_replicated_merge_tree = 1";

    enum Shape {
        FULL_READ, GROUP_AGG, JOIN_WITH_VIEW, IN_SUBQUERY, ORDER_BY_LIMIT
    }

    private static final class Profile {
        private final String label;
        private final UnaryOperator<String> relation;
        private final String settings;

        private Profile(String label, UnaryOperator<String> relation, String settings) {
            this.label = label;
            this.relation = relation;
            this.settings = settings;
        }
    }

    private final ClickHouseGlobalState state;
    private final ExpectedErrors ddlErrors = new ExpectedErrors();
    private final ExpectedErrors readErrors = new ExpectedErrors();

    public ClickHouseDistributedPlanEquivalenceOracle(ClickHouseGlobalState state) {
        this.state = state;
        for (ExpectedErrors e : List.of(ddlErrors, readErrors)) {
            ClickHouseErrors.addExpectedExpressionErrors(e);
            ClickHouseErrors.addSessionSettingsErrors(e);

            e.add("UNKNOWN_TABLE");
            e.add("Unknown table expression identifier");
            e.add("UNKNOWN_STORAGE");
            e.add("Unknown table engine");
            e.add("SUPPORT_IS_DISABLED");
            e.add("NOT_IMPLEMENTED");

            e.add("(MEMORY_LIMIT_EXCEEDED)");
            e.add("memory limit exceeded");
            e.add("TIMEOUT_EXCEEDED");
            e.add("Timeout exceeded");
            e.add("Limit for result exceeded");
            e.add("TOO_MANY_ROWS_OR_BYTES");

            e.add("Requested cluster");
            e.add("CLUSTER_DOESNT_EXIST");
            e.add("There is no Cluster");
            e.add("NETWORK_ERROR");
            e.add("Connection refused");
            e.add("All connection tries failed");
            e.add("ACCESS_DENIED");
            e.add("Not enough privileges");
            e.add("TOO_MANY_SIMULTANEOUS_QUERIES");
        }

        readErrors.add("parallel replicas");
        readErrors.add("Parallel replicas");
        readErrors.add("PARALLEL_REPLICAS_UNAVAILABLE");
        readErrors.add("distributed plan");
        readErrors.add("Distributed plan");
        readErrors.add("serialize_query_plan");
        readErrors.add("QUERY_PLAN_SERIALIZATION_ERROR");
        readErrors.add("ALIAS_REQUIRED");
        readErrors.add("INCORRECT_QUERY");
    }

    @Override
    public void check() throws SQLException {
        if (!state.getClickHouseOptions().distributedPlanEquivalenceOracle) {
            throw new IgnoreMeException();
        }

        long id = DPE_COUNTER.incrementAndGet();
        String db = state.getDatabaseName();
        String factBare = "dpe_fact_" + id;
        String dimBare = "dpe_dim_" + id;
        String viewBare = "dpe_v_" + id;
        String distFactBare = "dpe_dist_fact_" + id;
        String distDimBare = "dpe_dist_dim_" + id;
        String distViewBare = "dpe_dist_v_" + id;

        List<String> created = new ArrayList<>();
        try {
            int groups = 2 + (int) Randomly.getNotCachedInteger(0, 8);
            int rowsPerBlock = 200 + (int) Randomly.getNotCachedInteger(0, 800);
            int blocks = 2 + (int) Randomly.getNotCachedInteger(0, 3);

            execOrIgnore("CREATE TABLE " + db + "." + factBare
                    + " (id UInt32, g UInt32, v Int64) ENGINE = MergeTree ORDER BY id", created, factBare);
            execOrIgnore("CREATE TABLE " + db + "." + dimBare
                    + " (id UInt32, g UInt32, v Int64) ENGINE = MergeTree ORDER BY id", created, dimBare);
            for (int b = 0; b < blocks; b++) {
                long offset = (long) b * rowsPerBlock;
                execOrIgnore("INSERT INTO " + db + "." + factBare + " SELECT toUInt32(number + " + offset
                        + ") AS id, toUInt32(number % " + groups + ") AS g, toInt64(number % 100) AS v FROM numbers("
                        + rowsPerBlock + ")", null, null);
            }
            execOrIgnore("INSERT INTO " + db + "." + dimBare + " SELECT toUInt32(number) AS id, toUInt32(number % "
                    + groups + ") AS g, toInt64(number % 7) AS v FROM numbers(" + groups + ")", null, null);

            execOrIgnore("CREATE VIEW " + db + "." + viewBare + " AS SELECT id, g, v FROM " + db + "." + dimBare,
                    created, viewBare);

            execOrIgnore("CREATE TABLE " + db + "." + distFactBare + " AS " + db + "." + factBare
                    + " ENGINE = Distributed('default', currentDatabase(), '" + factBare + "')", created,
                    distFactBare);
            execOrIgnore("CREATE TABLE " + db + "." + distDimBare + " AS " + db + "." + dimBare
                    + " ENGINE = Distributed('default', currentDatabase(), '" + dimBare + "')", created, distDimBare);
            execOrIgnore("CREATE TABLE " + db + "." + distViewBare + " AS " + db + "." + viewBare
                    + " ENGINE = Distributed('default', currentDatabase(), '" + viewBare + "')", created,
                    distViewBare);

            Map<String, String> distributedNames = Map.of(factBare, distFactBare, dimBare, distDimBare, viewBare,
                    distViewBare);

            UnaryOperator<String> localRel = bare -> db + "." + bare;
            UnaryOperator<String> clusterRel = bare -> "cluster('default', currentDatabase(), '" + bare + "')";
            UnaryOperator<String> distributedRel = bare -> db + "." + distributedNames.get(bare);

            List<Profile> profiles = List.of(new Profile("make_distributed_plan", localRel, "make_distributed_plan = 1"),
                    new Profile("serialize_query_plan", localRel, "serialize_query_plan = 1"),
                    new Profile("cluster + local plan", clusterRel, "parallel_replicas_local_plan = 1"),
                    new Profile("cluster + no local plan", clusterRel, "parallel_replicas_local_plan = 0"),
                    new Profile("distributed + parallel replicas", distributedRel, PARALLEL_REPLICAS_SETTINGS),
                    new Profile("local + parallel replicas", localRel, PARALLEL_REPLICAS_SETTINGS));

            Shape shape = Randomly.fromOptions(Shape.values());
            String baselineQuery = renderQuery(shape, localRel, factBare, dimBare, viewBare, null);
            log(baselineQuery);
            List<String> baselineRows = ComparatorHelper.getResultSetFirstColumnAsString(baselineQuery, readErrors,
                    state);

            for (Profile profile : profiles) {
                String query = renderQuery(shape, profile.relation, factBare, dimBare, viewBare, profile.settings);
                List<String> rows;
                try {
                    log(query);
                    rows = ComparatorHelper.getResultSetFirstColumnAsString(query, readErrors, state);
                } catch (IgnoreMeException e) {
                    continue;
                }
                assertMultisetsEqual(shape, profile.label, baselineQuery, baselineRows, query, rows);
            }
        } finally {
            for (int i = created.size() - 1; i >= 0; i--) {
                dropQuietly(db + "." + created.get(i));
            }
        }
    }

    private static String renderQuery(Shape shape, UnaryOperator<String> rel, String fact, String dim, String view,
            String settings) {
        String suffix = settings == null ? "" : " SETTINGS " + settings;
        switch (shape) {
        case FULL_READ:
            return "SELECT toString(tuple(id, g, v)) FROM " + rel.apply(fact) + suffix;
        case GROUP_AGG:
            return "SELECT toString(tuple(g, count(), sum(v), min(v), max(v))) FROM " + rel.apply(fact)
                    + " GROUP BY g ORDER BY g" + suffix;
        case JOIN_WITH_VIEW:
            return "SELECT toString(tuple(a.id, b.g, c.v)) FROM " + rel.apply(fact) + " AS a, " + rel.apply(view)
                    + " AS b, " + rel.apply(dim) + " AS c WHERE a.g = b.g AND b.id = c.id" + suffix;
        case IN_SUBQUERY:
            return "SELECT toString(tuple(id, g, v)) FROM " + rel.apply(fact) + " WHERE g IN (SELECT g FROM "
                    + rel.apply(view) + " WHERE v >= 2)" + suffix;
        case ORDER_BY_LIMIT:
            return "SELECT toString(tuple(id, g, v)) FROM " + rel.apply(fact) + " ORDER BY id ASC, g ASC, v ASC "
                    + "LIMIT 50" + suffix;
        default:
            throw new AssertionError(shape);
        }
    }

    private void execOrIgnore(String sql, List<String> created, String createdBareName) throws SQLException {
        log(sql);
        if (!new SQLQueryAdapter(sql, ddlErrors, true).execute(state)) {
            throw new IgnoreMeException();
        }
        if (created != null) {
            created.add(createdBareName);
        }
    }

    private void log(String sql) {
        if (state.getOptions().logEachSelect()) {
            state.getLogger().writeCurrent(sql);
            state.getState().logStatement(sql);
        }
    }

    private void dropQuietly(String name) {
        try {
            new SQLQueryAdapter("DROP TABLE IF EXISTS " + name, ddlErrors, true).execute(state);
        } catch (Exception | AssertionError ignored) {
        }
        try {
            new SQLQueryAdapter("DROP VIEW IF EXISTS " + name, ddlErrors, true).execute(state);
        } catch (Exception | AssertionError ignored) {
        }
    }

    private static void assertMultisetsEqual(Shape shape, String label, String baselineQuery,
            List<String> baselineRows, String query, List<String> rows) {
        List<String> diff = multisetDiff(baselineRows, rows, DIFF_LIMIT);
        if (diff.isEmpty()) {
            return;
        }
        throw new AssertionError(String.format(
                "distributed-plan equivalence mismatch (%s shape, %s profile): %d baseline rows vs %d profile rows."
                        + "%nbaseline: %s%nprofile:  %s%nfirst %d differing entries: %s",
                shape, label, baselineRows.size(), rows.size(), baselineQuery, query, diff.size(), diff));
    }

    private static List<String> multisetDiff(List<String> a, List<String> b, int limit) {
        Map<String, Long> counts = new TreeMap<>();
        for (String s : a) {
            counts.merge(s == null ? "\\N" : s, 1L, Long::sum);
        }
        for (String s : b) {
            counts.merge(s == null ? "\\N" : s, -1L, Long::sum);
        }
        List<String> diff = new ArrayList<>();
        for (Map.Entry<String, Long> e : counts.entrySet()) {
            if (e.getValue() == 0) {
                continue;
            }
            if (diff.size() >= limit) {
                break;
            }
            long c = e.getValue();
            diff.add(e.getKey() + " (+" + Math.abs(c) + " " + (c > 0 ? "baseline" : "profile") + ")");
        }
        return diff;
    }
}
