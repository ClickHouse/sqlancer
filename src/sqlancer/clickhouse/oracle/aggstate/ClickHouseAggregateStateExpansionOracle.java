package sqlancer.clickhouse.oracle.aggstate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.clickhouse.ClickHouseErrors;
import sqlancer.clickhouse.ClickHouseProvider.ClickHouseGlobalState;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;

public class ClickHouseAggregateStateExpansionOracle implements TestOracle<ClickHouseGlobalState> {

    private static final AtomicLong CTR = new AtomicLong();

    enum Mode {
        SINGLE_TABLE_IDENTITY,
        AGGREGATING_MERGE
    }

    enum Agg {
        SUM,
        MIN,
        MAX,
        UNIQ_EXACT,
        QUANTILE_EXACT_MEDIAN,
        GROUP_ARRAY_SORTED
    }

    private final ClickHouseGlobalState state;

    private final ExpectedErrors errors = new ExpectedErrors();

    public ClickHouseAggregateStateExpansionOracle(ClickHouseGlobalState state) {
        this.state = state;
        ClickHouseErrors.addExpectedExpressionErrors(errors);
        errors.add("UNKNOWN_TABLE");
        errors.add("Unknown table expression identifier");
        errors.add("UNKNOWN_TYPE");
        errors.add("Unknown data type");
        errors.add("SUPPORT_IS_DISABLED");
        errors.add("NOT_IMPLEMENTED");
        errors.add("ILLEGAL_TYPE_OF_ARGUMENT");
        errors.add("experimental");
        errors.add("allow_experimental");
        errors.add("(MEMORY_LIMIT_EXCEEDED)");
        errors.add("memory limit exceeded");
        errors.add("TIMEOUT_EXCEEDED");
        errors.add("Timeout exceeded");
    }

    @Override
    public void check() throws SQLException {
        if (!state.getClickHouseOptions().aggregateStateExpansionOracle) {
            throw new IgnoreMeException();
        }
        long id = CTR.incrementAndGet();
        Mode mode = Randomly.fromOptions(Mode.values());
        if (mode == Mode.SINGLE_TABLE_IDENTITY) {
            checkSingleTableIdentity(id);
        } else {
            checkAggregatingMerge(id);
        }
    }

    private void checkSingleTableIdentity(long id) throws SQLException {
        String table = state.getDatabaseName() + ".aggstexp_" + id + "_b";
        Randomly r = state.getRandomly();
        String create = "CREATE TABLE " + table + " (k UInt32, x Int64) ENGINE = MergeTree ORDER BY k";

        List<Long> values = new ArrayList<>();
        try {
            logStmt(create);
            if (!new SQLQueryAdapter(create, errors, true).execute(state)) {
                throw new IgnoreMeException();
            }

            int blocks = 2 + r.getInteger(0, 3);
            int kk = 0;
            for (int b = 0; b < blocks; b++) {
                int rows = 40 + r.getInteger(0, 61);
                StringBuilder sb = new StringBuilder("INSERT INTO ").append(table).append(" (k, x) VALUES ");
                for (int i = 0; i < rows; i++) {
                    long v = sampleInt(r);
                    values.add(v);
                    if (i > 0) {
                        sb.append(", ");
                    }
                    sb.append('(').append(kk++).append(", ").append(v).append(')');
                }
                logStmt(sb.toString());
                if (!new SQLQueryAdapter(sb.toString(), errors, true).execute(state)) {
                    throw new IgnoreMeException();
                }
            }
            if (values.isEmpty()) {
                throw new IgnoreMeException();
            }

            Agg agg = Randomly.fromOptions(Agg.values());
            String stateForm;
            String directForm;
            String expected;
            switch (agg) {
            case MIN:
                stateForm = "toString(finalizeAggregation(arrayReduce('minState', groupArray(x))))";
                directForm = "toString(min(x))";
                expected = String.valueOf(values.stream().mapToLong(Long::longValue).min().getAsLong());
                break;
            case MAX:
                stateForm = "toString(finalizeAggregation(arrayReduce('maxState', groupArray(x))))";
                directForm = "toString(max(x))";
                expected = String.valueOf(values.stream().mapToLong(Long::longValue).max().getAsLong());
                break;
            case UNIQ_EXACT:
                stateForm = "toString(finalizeAggregation(arrayReduce('uniqExactState', groupArray(x))))";
                directForm = "toString(uniqExact(x))";
                expected = String.valueOf(values.stream().distinct().count());
                break;
            case QUANTILE_EXACT_MEDIAN:
                stateForm = "toString(finalizeAggregation(arrayReduce('quantileExactState(0.5)', groupArray(x))))";
                directForm = "toString(quantileExact(0.5)(x))";
                expected = null;
                break;
            case GROUP_ARRAY_SORTED:
                stateForm = "toString(arraySort(finalizeAggregation(arrayReduce('groupArrayState', groupArray(x)))))";
                directForm = "toString(arraySort(groupArray(x)))";
                expected = renderSortedArray(values);
                break;
            default:
                stateForm = "toString(finalizeAggregation(arrayReduce('sumState', groupArray(x))))";
                directForm = "toString(sum(x))";
                expected = String.valueOf(values.stream().mapToLong(Long::longValue).sum());
                break;
            }

            String query = "SELECT (" + stateForm + ") AS a, (" + directForm + ") AS b FROM " + table;
            logStmt(query);
            String[] pair = readTwoStrings(query);
            String stateVal = pair[0];
            String directVal = pair[1];

            if (!stateVal.equals(directVal)) {
                throw new AssertionError(String.format(
                        "aggregate-state expansion identity mismatch (%s): state-expansion form %s != direct form %s. "
                                + "DDL: %s",
                        agg, stateVal, directVal, create));
            }
            if (expected != null && !expected.equals(directVal)) {
                throw new AssertionError(String.format(
                        "aggregate-state expansion ground-truth mismatch (%s): Java expects %s but ClickHouse %s. "
                                + "DDL: %s",
                        agg, expected, directVal, create));
            }
        } finally {
            dropQuietly(table);
        }
    }

    private void checkAggregatingMerge(long id) throws SQLException {
        String table = state.getDatabaseName() + ".aggstexp_" + id + "_a";
        Randomly r = state.getRandomly();
        String create = "CREATE TABLE " + table + " (k UInt32, s AggregateFunction(sum, Int64), "
                + "u AggregateFunction(uniqExact, Int64)) ENGINE = AggregatingMergeTree ORDER BY k";

        int keyCount = 3 + r.getInteger(0, 5);
        Map<Integer, Long> sumByKey = new LinkedHashMap<>();
        Map<Integer, List<Long>> valuesByKey = new LinkedHashMap<>();
        for (int k = 0; k < keyCount; k++) {
            sumByKey.put(k, 0L);
            valuesByKey.put(k, new ArrayList<>());
        }

        try {
            logStmt(create);
            if (!new SQLQueryAdapter(create, errors, true).execute(state)) {
                throw new IgnoreMeException();
            }

            int blocks = 2 + r.getInteger(0, 3);
            for (int b = 0; b < blocks; b++) {
                Map<Integer, List<Long>> blockValues = new LinkedHashMap<>();
                for (int k = 0; k < keyCount; k++) {
                    blockValues.put(k, new ArrayList<>());
                }
                int rows = 30 + r.getInteger(0, 41);
                for (int i = 0; i < rows; i++) {
                    int k = r.getInteger(0, keyCount);
                    long v = sampleInt(r);
                    blockValues.get(k).add(v);
                    sumByKey.put(k, sumByKey.get(k) + v);
                    valuesByKey.get(k).add(v);
                }

                StringBuilder sb = new StringBuilder("INSERT INTO ").append(table)
                        .append(" (k, s, u) SELECT k, sumState(x), uniqExactState(x) FROM (SELECT k, x FROM (");
                boolean firstUnion = true;
                boolean anyRow = false;
                for (int k = 0; k < keyCount; k++) {
                    List<Long> vs = blockValues.get(k);
                    for (Long v : vs) {
                        if (!firstUnion) {
                            sb.append(" UNION ALL ");
                        }
                        firstUnion = false;
                        anyRow = true;
                        sb.append("SELECT toUInt32(").append(k).append(") AS k, toInt64(").append(v).append(") AS x");
                    }
                }
                sb.append(")) GROUP BY k");
                if (!anyRow) {
                    continue;
                }
                logStmt(sb.toString());
                if (!new SQLQueryAdapter(sb.toString(), errors, true).execute(state)) {
                    throw new IgnoreMeException();
                }
            }

            String optimize = "OPTIMIZE TABLE " + table + " FINAL";
            logStmt(optimize);
            if (!new SQLQueryAdapter(optimize, errors, true).execute(state)) {
                throw new IgnoreMeException();
            }

            String query = "SELECT toString(k) AS kk, toString(finalizeAggregation(sumMerge(s))) AS ss, "
                    + "toString(finalizeAggregation(uniqExactMerge(u))) AS uu FROM " + table + " GROUP BY k ORDER BY k";
            logStmt(query);

            Map<String, String[]> observed = new TreeMap<>();
            try (Statement st = state.getConnection().createStatement(); ResultSet rs = st.executeQuery(query)) {
                while (rs.next()) {
                    String kk = rs.getString(1);
                    String ss = rs.getString(2);
                    String uu = rs.getString(3);
                    observed.put(kk, new String[] { ss, uu });
                }
            } catch (SQLException ex) {
                throw maybeIgnore(ex);
            }

            Map<String, String[]> expected = new TreeMap<>();
            for (int k = 0; k < keyCount; k++) {
                if (valuesByKey.get(k).isEmpty()) {
                    continue;
                }
                String sumStr = String.valueOf(sumByKey.get(k));
                String uniqStr = String.valueOf(valuesByKey.get(k).stream().distinct().count());
                expected.put(String.valueOf(k), new String[] { sumStr, uniqStr });
            }

            if (!expected.keySet().equals(observed.keySet())) {
                throw new AssertionError(String.format(
                        "aggregating-merge key-set mismatch: Java expects keys %s but ClickHouse returned %s. DDL: %s",
                        expected.keySet(), observed.keySet(), create));
            }
            for (Map.Entry<String, String[]> e : expected.entrySet()) {
                String[] exp = e.getValue();
                String[] obs = observed.get(e.getKey());
                if (!exp[0].equals(obs[0])) {
                    throw new AssertionError(String.format(
                            "aggregating-merge sumMerge mismatch at key %s: Java expects %s but ClickHouse %s. DDL: %s",
                            e.getKey(), exp[0], obs[0], create));
                }
                if (!exp[1].equals(obs[1])) {
                    throw new AssertionError(String.format(
                            "aggregating-merge uniqExactMerge mismatch at key %s: Java expects %s but ClickHouse %s. "
                                    + "DDL: %s",
                            e.getKey(), exp[1], obs[1], create));
                }
            }
        } finally {
            dropQuietly(table);
        }
    }

    private static long sampleInt(Randomly r) {
        return r.getInteger(-1000, 1001);
    }

    static String renderSortedArray(List<Long> values) {
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        return "[" + sorted.stream().map(String::valueOf).collect(Collectors.joining(",")) + "]";
    }

    private String[] readTwoStrings(String query) throws SQLException {
        try (Statement st = state.getConnection().createStatement(); ResultSet rs = st.executeQuery(query)) {
            if (!rs.next()) {
                throw new IgnoreMeException();
            }
            String a = rs.getString(1);
            String b = rs.getString(2);
            if (a == null || b == null) {
                throw new IgnoreMeException();
            }
            return new String[] { a, b };
        } catch (SQLException ex) {
            throw maybeIgnore(ex);
        }
    }

    private SQLException maybeIgnore(SQLException ex) {
        if (ex.getMessage() != null && errors.errorIsExpected(ex.getMessage())) {
            throw new IgnoreMeException();
        }
        return ex;
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
