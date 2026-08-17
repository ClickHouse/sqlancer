package sqlancer.clickhouse.oracle.stats;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import com.clickhouse.data.ClickHouseDataType;

import sqlancer.ComparatorHelper;
import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.clickhouse.ClickHouseErrors;
import sqlancer.clickhouse.ClickHouseProvider.ClickHouseGlobalState;
import sqlancer.clickhouse.ClickHouseSchema.ClickHouseColumn;
import sqlancer.clickhouse.ClickHouseSchema.ClickHouseTable;
import sqlancer.clickhouse.ast.ClickHouseAlterStatistics;
import sqlancer.clickhouse.gen.ClickHouseStatisticsGenerator;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;

public class ClickHouseStatsToggleOracle implements TestOracle<ClickHouseGlobalState> {

    private static final AtomicLong STATS_COUNTER = new AtomicLong();
    private static final int DIFF_LIMIT = 20;

    static final List<String> STATISTICS_KINDS = List.of("tdigest", "uniq", "countmin", "minmax", "uniq_v2", "basic");

    private static final Map<String, AtomicLong> MATERIALIZED_BY_KIND = Map.of("tdigest", new AtomicLong(), "uniq",
            new AtomicLong(), "countmin", new AtomicLong(), "minmax", new AtomicLong(), "uniq_v2", new AtomicLong(),
            "basic", new AtomicLong());

    static final String ARM_STATS_ON = "SETTINGS use_statistics = 1, allow_statistics_optimize = 1, "
            + "use_statistics_for_part_pruning = 1";
    static final String ARM_STATS_OFF = "SETTINGS use_statistics = 0, allow_statistics_optimize = 0, "
            + "use_statistics_for_part_pruning = 0";

    static final String PIN_JOIN_ORDER = ", query_plan_optimize_join_order_limit = 0";

    private final ClickHouseGlobalState state;

    private final ExpectedErrors readErrors = new ExpectedErrors();
    private final ExpectedErrors statsDdlErrors = new ExpectedErrors();

    public ClickHouseStatsToggleOracle(ClickHouseGlobalState state) {
        this.state = state;

        for (ExpectedErrors e : List.of(readErrors, statsDdlErrors)) {

            ClickHouseErrors.addSessionSettingsErrors(e);
            ClickHouseErrors.addExpectedExpressionErrors(e);

            e.add("UNKNOWN_TABLE");
            e.add("Unknown table expression identifier");

            e.add("UNKNOWN_IDENTIFIER");
            e.add("Missing columns");

            e.add("(MEMORY_LIMIT_EXCEEDED)");
            e.add("memory limit exceeded");

            e.add("TIMEOUT_EXCEEDED");
            e.add("Timeout exceeded");

            e.add("Limit for result exceeded");
            e.add("TOO_MANY_ROWS_OR_BYTES");

            e.add("There is no supertype");
            e.add("NO_COMMON_TYPE");
        }

        ClickHouseErrors.addStatisticsErrors(statsDdlErrors);

        statsDdlErrors.add("ILLEGAL_STATISTICS");
        statsDdlErrors.add("already contains statistics");

        statsDdlErrors.add("because it's affected by mutation with ID");
        statsDdlErrors.add("Exception happened during execution of mutation");
        statsDdlErrors.add("UNFINISHED");
        statsDdlErrors.add("contains a duplicate expression");
    }

    @Override
    public void check() throws SQLException {
        if (Randomly.getNotCachedInteger(0, 100) < 70) {
            List<ClickHouseTable> eligible = state.getSchema().getDatabaseTables().stream()
                    .filter(ClickHouseStatsToggleOracle::isEligibleFleetTable).collect(Collectors.toList());
            if (!eligible.isEmpty()) {
                checkFleetArm(eligible);
                return;
            }

        }
        checkStalenessArm();
    }

    private void checkFleetArm(List<ClickHouseTable> eligible) throws SQLException {
        ClickHouseTable table = eligible.get((int) Randomly.getNotCachedInteger(0, eligible.size()));
        List<ClickHouseColumn> intCols = integerColumns(table);

        List<String> countRows = ComparatorHelper
                .getResultSetFirstColumnAsString("SELECT toString(count()) FROM " + table.getName(), readErrors, state);
        if (countRows.size() != 1 || "0".equals(countRows.get(0))) {
            throw new IgnoreMeException();
        }

        if (Randomly.getNotCachedInteger(0, 100) < 40) {
            ClickHouseAlterStatistics ddl = ClickHouseStatisticsGenerator.buildStatement(state);
            logStmt(ddl.getSql());
            new SQLQueryAdapter(ddl.getSql(), statsDdlErrors, false).execute(state);
        }

        List<ClickHouseTable> partners = eligible.stream().filter(t -> !t.getName().equals(table.getName()))
                .collect(Collectors.toList());
        ClickHouseTable partner = null;
        if (!partners.isEmpty() && Randomly.getBoolean()) {
            partner = partners.get((int) Randomly.getNotCachedInteger(0, partners.size()));
        }

        String select;
        boolean pinJoinOrder = partner != null;
        if (partner == null) {
            List<String> refs = intCols.stream().map(c -> ref(null, c.getName())).collect(Collectors.toList());
            select = "SELECT " + renderTupleProjection(pickRefs(refs, 3)) + " FROM " + table.getName() + " WHERE "
                    + buildWhere(refs);
        } else {
            List<ClickHouseColumn> partnerInts = integerColumns(partner);
            List<String> leftRefs = intCols.stream().map(c -> ref("a0", c.getName())).collect(Collectors.toList());
            List<String> projection = new ArrayList<>(pickRefs(leftRefs, 2));
            projection.add(ref("a1", partnerInts.get((int) Randomly.getNotCachedInteger(0, partnerInts.size()))
                    .getName()));
            String onLeft = leftRefs.get((int) Randomly.getNotCachedInteger(0, leftRefs.size()));
            String onRight = ref("a1",
                    partnerInts.get((int) Randomly.getNotCachedInteger(0, partnerInts.size())).getName());
            select = "SELECT " + renderTupleProjection(projection) + " FROM " + table.getName() + " AS a0 INNER JOIN "
                    + partner.getName() + " AS a1 ON " + onLeft + " = " + onRight + " WHERE " + buildWhere(leftRefs);
        }
        runDifferential(select, pinJoinOrder);
    }

    private void checkStalenessArm() throws SQLException {
        long id = STATS_COUNTER.incrementAndGet();
        String table = state.getDatabaseName() + ".stats_" + id + "_t";
        long rows = 500 + Randomly.getNotCachedInteger(0, 1501);
        String kindK = kindForIteration(id, 0);
        String kindV = kindForIteration(id, 1);

        try {
            List<String> setup = renderStalenessSetup(table, rows, kindK, kindV);
            for (int i = 0; i < setup.size(); i++) {
                String stmt = setup.get(i);
                boolean isStatsDdl = i >= 2;
                logStmt(stmt);
                if (!new SQLQueryAdapter(stmt, isStatsDdl ? statsDdlErrors : readErrors, !isStatsDdl)
                        .execute(state)) {

                    throw new IgnoreMeException();
                }
            }
            MATERIALIZED_BY_KIND.get(kindK).incrementAndGet();
            MATERIALIZED_BY_KIND.get(kindV).incrementAndGet();

            String stale = Randomly.getBoolean() ? renderStaleDelete(table) : renderStaleInsert(table, rows);
            logStmt(stale);
            if (!new SQLQueryAdapter(stale, readErrors, false).execute(state)) {
                throw new IgnoreMeException();
            }

            String select = "SELECT toString(tuple(k, v)) FROM " + table + " WHERE " + buildWhere(List.of("k", "v"));
            runDifferential(select, false);

            if (Randomly.getNotCachedInteger(0, 100) < 30) {
                String drop = renderDropStatistics(table);
                logStmt(drop);
                if (new SQLQueryAdapter(drop, statsDdlErrors, false).execute(state)) {
                    runDifferential(select, false);
                }
            }
        } finally {
            try {
                new SQLQueryAdapter("DROP TABLE IF EXISTS " + table, readErrors, true).execute(state);
            } catch (Exception | AssertionError ignored) {

            }
        }
    }

    private void runDifferential(String select, boolean pinJoinOrder) throws SQLException {
        String[] pair = renderDifferentialPair(select, pinJoinOrder);

        List<String> statsOnRows = ComparatorHelper.getResultSetFirstColumnAsString(pair[0], readErrors, state);
        List<String> statsOffRows = ComparatorHelper.getResultSetFirstColumnAsString(pair[1], readErrors, state);
        assertMultisetsEqual(statsOnRows, statsOffRows, pair[0], pair[1]);
    }

    static String[] renderDifferentialPair(String select, boolean pinJoinOrder) {
        String pin = pinJoinOrder ? PIN_JOIN_ORDER : "";
        return new String[] { select + " " + ARM_STATS_ON + pin, select + " " + ARM_STATS_OFF + pin };
    }

    static void assertMultisetsEqual(List<String> statsOnRows, List<String> statsOffRows, String statsOnSql,
            String statsOffSql) {
        List<String> diff = multisetDiff(statsOnRows, statsOffRows, DIFF_LIMIT);
        if (diff.isEmpty()) {
            return;
        }
        throw new AssertionError(String.format(
                "stats-toggle multiset mismatch: %d rows with statistics on vs %d rows with statistics off.%n"
                        + "on:  %s%noff: %s%nfirst %d differing entries (value (+count side)): %s",
                statsOnRows.size(), statsOffRows.size(), statsOnSql, statsOffSql, diff.size(), diff));
    }

    static List<String> multisetDiff(List<String> statsOnRows, List<String> statsOffRows, int limit) {
        Map<String, Long> counts = new TreeMap<>();
        for (String s : statsOnRows) {
            counts.merge(s == null ? "\\N" : s, 1L, Long::sum);
        }
        for (String s : statsOffRows) {
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
            diff.add(e.getKey() + " (+" + Math.abs(c) + " " + (c > 0 ? "on" : "off") + ")");
        }
        return diff;
    }

    static String renderAutoStatisticsTypes() {
        List<String> pool = new ArrayList<>(STATISTICS_KINDS);
        java.util.Collections.shuffle(pool, new java.util.Random(Randomly.getNotCachedInteger(0, Integer.MAX_VALUE)));
        int n = (int) Randomly.getNotCachedInteger(0, 4);
        return String.join(", ", pool.subList(0, Math.min(n, pool.size())));
    }

    static List<String> renderStalenessSetup(String table, long rows, String kindK, String kindV) {
        return List.of(
                "CREATE TABLE " + table + " (k Int32, v Int64) ENGINE = MergeTree ORDER BY k SETTINGS "
                        + "auto_statistics_types = '" + renderAutoStatisticsTypes() + "', "
                        + "materialize_statistics_on_merge = " + (Randomly.getBoolean() ? 1 : 0),
                "INSERT INTO " + table + " SELECT toInt32(if(number % 4 = 3, number, number % 3)), "
                        + "toInt64(number % 11) FROM numbers(" + rows + ")",
                "ALTER TABLE " + table + " ADD STATISTICS IF NOT EXISTS k TYPE " + kindK,
                "ALTER TABLE " + table + " MODIFY STATISTICS k TYPE " + kindK,
                "ALTER TABLE " + table + " ADD STATISTICS IF NOT EXISTS v TYPE " + kindV,
                "ALTER TABLE " + table + " MODIFY STATISTICS v TYPE " + kindV,
                "ALTER TABLE " + table + " MATERIALIZE STATISTICS k, v SETTINGS mutations_sync = 1");
    }

    static String renderStaleDelete(String table) {
        return "ALTER TABLE " + table + " DELETE WHERE k % 2 = 0 SETTINGS mutations_sync = 1";
    }

    static String renderStaleInsert(String table, long rows) {
        return "INSERT INTO " + table + " SELECT toInt32(1000000 + number), toInt64(number % 5) FROM numbers(" + rows
                + ")";
    }

    static String renderDropStatistics(String table) {
        return "ALTER TABLE " + table + " DROP STATISTICS k, v";
    }

    static String renderTupleProjection(List<String> columnRefs) {
        return "toString(tuple(" + String.join(", ", columnRefs) + "))";
    }

    static String buildWhere(List<String> columnRefs) {
        int predicates = 1 + (int) Randomly.getNotCachedInteger(0, 3);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < predicates; i++) {
            if (i > 0) {
                sb.append(Randomly.getBoolean() ? " AND " : " OR ");
            }
            String col = columnRefs.get((int) Randomly.getNotCachedInteger(0, columnRefs.size()));
            if (Randomly.getBooleanWithRatherLowProbability()) {
                int modulus = 2 + (int) Randomly.getNotCachedInteger(0, 9);
                sb.append("(").append(col).append(" % ").append(modulus).append(" = ")
                        .append(Randomly.getNotCachedInteger(0, modulus)).append(")");
            } else {
                String op = Randomly.fromOptions("<", "<=", ">", ">=", "=", "!=");
                sb.append("(").append(col).append(" ").append(op).append(" ")
                        .append(Randomly.getNotCachedInteger(-1000, 1000)).append(")");
            }
        }
        return sb.toString();
    }

    static boolean isEligibleFleetTable(ClickHouseTable table) {
        return !table.isView() && "MergeTree".equals(table.getEngine())
                && table.getColumns().stream().anyMatch(c -> isExactInteger(c.getType().getType()));
    }

    static boolean isExactInteger(ClickHouseDataType t) {
        switch (t) {
        case Int8:
        case Int16:
        case Int32:
        case Int64:
        case Int128:
        case Int256:
        case UInt8:
        case UInt16:
        case UInt32:
        case UInt64:
        case UInt128:
        case UInt256:
            return true;
        default:
            return false;
        }
    }

    static String kindForIteration(long id, int offset) {
        return STATISTICS_KINDS.get((int) Math.floorMod(id + offset, STATISTICS_KINDS.size()));
    }

    static long materializedCount(String kind) {
        return MATERIALIZED_BY_KIND.get(kind).get();
    }

    private static List<ClickHouseColumn> integerColumns(ClickHouseTable table) {
        return table.getColumns().stream().filter(c -> isExactInteger(c.getType().getType()))
                .collect(Collectors.toList());
    }

    private static String ref(String aliasOrNull, String columnName) {
        return (aliasOrNull == null ? "" : aliasOrNull + ".") + "`" + columnName + "`";
    }

    private static List<String> pickRefs(List<String> refs, int max) {
        List<String> pool = new ArrayList<>(refs);
        int n = 1 + (int) Randomly.getNotCachedInteger(0, Math.min(max, pool.size()));
        List<String> picked = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            picked.add(pool.remove((int) Randomly.getNotCachedInteger(0, pool.size())));
        }
        return picked;
    }

    private void logStmt(String stmt) {
        if (state.getOptions().logEachSelect()) {
            state.getLogger().writeCurrent(stmt);
            state.getState().logStatement(stmt);
        }
    }
}
