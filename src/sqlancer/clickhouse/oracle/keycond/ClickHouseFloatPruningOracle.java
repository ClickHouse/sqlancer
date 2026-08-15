package sqlancer.clickhouse.oracle.keycond;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

import sqlancer.ComparatorHelper;
import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.clickhouse.ClickHouseErrors;
import sqlancer.clickhouse.ClickHouseProvider.ClickHouseGlobalState;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;

public class ClickHouseFloatPruningOracle implements TestOracle<ClickHouseGlobalState> {

    private static final int DIFF_LIMIT = 20;
    private static final AtomicLong FP_COUNTER = new AtomicLong();

    private static final List<String> FLOAT_LITERALS = List.of("nan", "inf", "-inf", "0", "-0.0", "1.5", "-1.5", "100",
            "-100", "3.14", "-3.14", "0.0000001");

    private static final List<String> COMPARISON_LITERALS = List.of("0", "-0.0", "1.5", "-1.5", "3.14", "-3.14", "100",
            "nan", "inf", "-inf");

    private static final String FLOAT_COLUMN_F32 = "f32";
    private static final String FLOAT_COLUMN_F64 = "f64";
    private static final String FLOAT_COLUMN_NF64 = "nf64";

    private static final String PRUNING_OFF = "use_skip_indexes = 0, use_skip_indexes_on_data_read = 0, "
            + "allow_statistics_optimize = 0, use_query_condition_cache = 0, optimize_move_to_prewhere = 0, "
            + "convert_query_to_cnf = 0, force_primary_key = 0, optimize_use_implicit_projections = 0";

    private final ClickHouseGlobalState state;
    private final ExpectedErrors ddlErrors = new ExpectedErrors();
    private final ExpectedErrors readErrors = new ExpectedErrors();
    private final ExpectedErrors statsErrors = new ExpectedErrors();

    public ClickHouseFloatPruningOracle(ClickHouseGlobalState state) {
        this.state = state;
        for (ExpectedErrors e : List.of(ddlErrors, readErrors, statsErrors)) {
            ClickHouseErrors.addSessionSettingsErrors(e);
            e.add("UNKNOWN_TABLE");
            e.add("Unknown table expression identifier");
            e.add("(MEMORY_LIMIT_EXCEEDED)");
            e.add("memory limit exceeded");
            e.add("TIMEOUT_EXCEEDED");
            e.add("Timeout exceeded");
            e.add("Limit for result exceeded");
            e.add("TOO_MANY_ROWS_OR_BYTES");
        }
        ddlErrors.add("Floating point partition key is not supported");
        ddlErrors.add("allow_floating_point_partition_key");
        ddlErrors.add("TOO_MANY_PARTS");
        ddlErrors.add("Too many partitions");
        ClickHouseErrors.addStatisticsErrors(statsErrors);
        statsErrors.add("already contains statistics");
        statsErrors.add("Exception happened during execution of mutation");
        statsErrors.add("UNFINISHED");
    }

    @Override
    public void check() throws SQLException {
        if (!state.getClickHouseOptions().floatPruningOracle) {
            throw new IgnoreMeException();
        }

        long id = FP_COUNTER.incrementAndGet();
        String table = state.getDatabaseName() + ".fprune_" + id;
        try {
            if (!createFixture(table)) {
                throw new IgnoreMeException();
            }
            seedFixture(table);
            if (Randomly.getBoolean()) {
                materializeStatisticsBestEffort(table);
            }

            String predicate = buildPredicate();
            checkRowSetEquivalence(table, predicate);
            checkTruthValuePartition(table, predicate);
        } finally {
            dropQuietly(table);
        }
    }

    private boolean createFixture(String table) throws SQLException {
        StringBuilder sb = new StringBuilder("CREATE TABLE ").append(table)
                .append(" (k Int64, f32 Float32, f64 Float64, nf64 Nullable(Float64)");
        if (Randomly.getBoolean()) {
            sb.append(", INDEX idx_f64 f64 TYPE minmax GRANULARITY 1");
        }
        if (Randomly.getBoolean()) {
            sb.append(", INDEX idx_f32 f32 TYPE bloom_filter(0.01) GRANULARITY 1");
        }
        if (Randomly.getBoolean()) {
            sb.append(", INDEX idx_nf64 nf64 TYPE minmax GRANULARITY 1");
        }
        sb.append(") ENGINE = MergeTree ORDER BY ");
        sb.append(Randomly.fromOptions("(f64, k)", "(f32, k)", "k", "(nf64, k)"));
        boolean floatPartition = Randomly.getBoolean();
        if (floatPartition) {
            sb.append(" PARTITION BY ").append(Randomly.fromOptions("f32", "f64"));
        }
        sb.append(" SETTINGS index_granularity = 8, allow_nullable_key = 1, allow_suspicious_indices = 1");
        if (floatPartition) {
            sb.append(", allow_floating_point_partition_key = 1");
        }
        String create = sb.toString();
        log(create);
        return new SQLQueryAdapter(create, ddlErrors, true).execute(state);
    }

    private void seedFixture(String table) throws SQLException {
        int blocks = 3 + (int) Randomly.getNotCachedInteger(0, 3);
        long key = 0;
        for (int b = 0; b < blocks; b++) {
            boolean allNaNBlock = b == 1;
            int rows = 4 + (int) Randomly.getNotCachedInteger(0, 12);
            StringBuilder values = new StringBuilder();
            for (int r = 0; r < rows; r++) {
                if (r > 0) {
                    values.append(", ");
                }
                String f32 = allNaNBlock ? "nan" : Randomly.fromList(FLOAT_LITERALS);
                String f64 = allNaNBlock ? "nan" : Randomly.fromList(FLOAT_LITERALS);
                String nf64;
                if (allNaNBlock) {
                    nf64 = "nan";
                } else {
                    nf64 = Randomly.getBooleanWithRatherLowProbability() ? "NULL" : Randomly.fromList(FLOAT_LITERALS);
                }
                values.append("(").append(key++).append(", ").append(f32).append(", ").append(f64).append(", ")
                        .append(nf64).append(")");
            }
            String insert = "INSERT INTO " + table + " VALUES " + values;
            log(insert);
            if (!new SQLQueryAdapter(insert, ddlErrors, true).execute(state)) {
                throw new IgnoreMeException();
            }
        }
    }

    private void materializeStatisticsBestEffort(String table) throws SQLException {
        String column = Randomly.fromOptions(FLOAT_COLUMN_F32, FLOAT_COLUMN_F64);
        String kind = Randomly.fromOptions("minmax", "tdigest", "minmax, tdigest");
        String add = "ALTER TABLE " + table + " ADD STATISTICS IF NOT EXISTS " + column + " TYPE " + kind
                + " SETTINGS allow_experimental_statistics = 1";
        log(add);
        if (!new SQLQueryAdapter(add, statsErrors, false).execute(state)) {
            return;
        }
        String materialize = "ALTER TABLE " + table + " MATERIALIZE STATISTICS " + column
                + " SETTINGS mutations_sync = 2, allow_experimental_statistics = 1";
        log(materialize);
        new SQLQueryAdapter(materialize, statsErrors, false).execute(state);
    }

    private String buildPredicate() {
        String first = buildAtom();
        if (Randomly.getBoolean()) {
            return first;
        }
        String second = buildAtom();
        String connective = Randomly.fromOptions(" AND ", " OR ");
        String combined = "(" + first + ")" + connective + "(" + second + ")";
        return Randomly.getBoolean() ? combined : "NOT (" + combined + ")";
    }

    private String buildAtom() {
        String column = Randomly.fromOptions(FLOAT_COLUMN_F32, FLOAT_COLUMN_F64, FLOAT_COLUMN_NF64);
        String literal = Randomly.fromList(COMPARISON_LITERALS);
        int form = (int) Randomly.getNotCachedInteger(0, 9);
        switch (form) {
        case 0:
            return "NOT (%" + column + "% < " + literal + ")";
        case 1:
            return "NOT (%" + column + "% > " + literal + ")";
        case 2:
            return "NOT (%" + column + "% <= " + literal + ")";
        case 3:
            return "NOT (%" + column + "% >= " + literal + ")";
        case 4:
            return "NOT (%" + column + "% BETWEEN " + literal + " AND " + Randomly.fromList(COMPARISON_LITERALS) + ")";
        case 5:
            return "NOT (NOT (%" + column + "% < " + literal + "))";
        case 6:
            return "NOT (%" + column + "% = " + literal + ")";
        case 7:
            return "%" + column + "% IS NULL";
        default:
            return "%" + column + "% IS NOT NULL";
        }
    }

    private static String render(String predicateTemplate, boolean materialized) {
        String out = predicateTemplate;
        for (String c : List.of(FLOAT_COLUMN_F32, FLOAT_COLUMN_F64, FLOAT_COLUMN_NF64)) {
            out = out.replace("%" + c + "%", materialized ? "materialize(" + c + ")" : c);
        }
        return out;
    }

    private void checkRowSetEquivalence(String table, String predicateTemplate) throws SQLException {
        String pruningOn = "SETTINGS use_skip_indexes = 1, use_skip_indexes_on_data_read = 1, "
                + "allow_statistics_optimize = " + (Randomly.getBoolean() ? 1 : 0) + ", convert_query_to_cnf = "
                + (Randomly.getBoolean() ? 1 : 0) + ", optimize_move_to_prewhere = " + (Randomly.getBoolean() ? 1 : 0);

        String pruned = "SELECT toString(k) FROM " + table + " WHERE " + render(predicateTemplate, false) + " "
                + pruningOn;
        String scanned = "SELECT arrayStringConcat(arraySort(groupArrayIf(k, ifNull((" + render(predicateTemplate, true)
                + "), 0))), ',') FROM " + table + " SETTINGS " + PRUNING_OFF;

        log(pruned);
        List<String> prunedRows = ComparatorHelper.getResultSetFirstColumnAsString(pruned, readErrors, state);
        log(scanned);
        List<String> scannedRows = ComparatorHelper.getResultSetFirstColumnAsString(scanned, readErrors, state);
        if (scannedRows.size() != 1) {
            throw new IgnoreMeException();
        }

        List<String> groundTruth = new ArrayList<>();
        String packed = scannedRows.get(0);
        if (packed != null && !packed.isEmpty()) {
            for (String part : packed.split(",")) {
                groundTruth.add(part);
            }
        }

        List<String> diff = multisetDiff(groundTruth, prunedRows, DIFF_LIMIT);
        if (!diff.isEmpty()) {
            throw new AssertionError(String.format(
                    "float pruning dropped or added rows: a full scan that evaluates the predicate as an aggregate "
                            + "argument (so nothing can be pruned) selects %d keys, the same predicate in WHERE "
                            + "selects %d, over a fixture containing NaN, +/-inf, -0.0 and NULL.%nfull scan: %s%n"
                            + "pruned:   %s%nfirst %d differing keys: %s",
                    groundTruth.size(), prunedRows.size(), scanned, pruned, diff.size(), diff));
        }
    }

    private void checkTruthValuePartition(String table, String predicateTemplate) throws SQLException {
        String predicate = render(predicateTemplate, false);
        String settings = Randomly.getBoolean() ? " SETTINGS convert_query_to_cnf = 1" : "";

        long total = scalar("SELECT toString(count()) FROM " + table);
        long truthy = scalar("SELECT toString(count()) FROM " + table + " WHERE (" + predicate + ")" + settings);
        long falsy = scalar("SELECT toString(count()) FROM " + table + " WHERE NOT (" + predicate + ")" + settings);
        long unknown = scalar(
                "SELECT toString(count()) FROM " + table + " WHERE (" + predicate + ") IS NULL" + settings);

        if (truthy + falsy + unknown != total) {
            throw new AssertionError(String.format(
                    "float pruning ternary partition violated: count(P)=%d + count(NOT P)=%d + count(P IS NULL)=%d "
                            + "= %d, but the table holds %d rows.%n  table: %s%n  P: %s%n  settings:%s",
                    truthy, falsy, unknown, truthy + falsy + unknown, total, table, predicate,
                    settings.isEmpty() ? " (defaults)" : settings));
        }
    }

    private long scalar(String query) throws SQLException {
        log(query);
        List<String> rows = ComparatorHelper.getResultSetFirstColumnAsString(query, readErrors, state);
        if (rows.size() != 1 || rows.get(0) == null) {
            throw new IgnoreMeException();
        }
        try {
            return Long.parseLong(rows.get(0).trim());
        } catch (NumberFormatException e) {
            throw new IgnoreMeException();
        }
    }

    private void log(String sql) {
        if (state.getOptions().logEachSelect()) {
            state.getLogger().writeCurrent(sql);
            state.getState().logStatement(sql);
        }
    }

    private void dropQuietly(String table) {
        try {
            new SQLQueryAdapter("DROP TABLE IF EXISTS " + table, ddlErrors, true).execute(state);
        } catch (Exception | AssertionError ignored) {
        }
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
            diff.add(e.getKey() + " (+" + Math.abs(c) + " " + (c > 0 ? "full scan" : "pruned") + ")");
        }
        return diff;
    }
}
