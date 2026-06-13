package sqlancer.clickhouse.oracle.container;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;

import sqlancer.ComparatorHelper;
import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.clickhouse.ClickHouseErrors;
import sqlancer.clickhouse.ClickHouseProvider.ClickHouseGlobalState;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;

public class ClickHouseMapTupleContainerOracle implements TestOracle<ClickHouseGlobalState> {

    private static final AtomicLong CTR = new AtomicLong();

    private static final List<String> WORDS = List.of("alpha", "bravo", "charlie", "delta", "echo", "foxtrot", "golf",
            "hotel");

    private final ClickHouseGlobalState state;

    private final ExpectedErrors createErrors = new ExpectedErrors();
    private final ExpectedErrors readErrors = new ExpectedErrors();

    public ClickHouseMapTupleContainerOracle(ClickHouseGlobalState state) {
        this.state = state;
        for (ExpectedErrors e : List.of(createErrors, readErrors)) {
            ClickHouseErrors.addSessionSettingsErrors(e);
            ClickHouseErrors.addExpectedExpressionErrors(e);

            e.add("UNKNOWN_TABLE");
            e.add("Unknown table expression identifier");
            e.add("UNKNOWN_TYPE");
            e.add("Unknown data type");
            e.add("SUPPORT_IS_DISABLED");
            e.add("NOT_IMPLEMENTED");
            e.add("ILLEGAL_TYPE_OF_ARGUMENT");
            e.add("experimental");
            e.add("allow_experimental");
            e.add("(MEMORY_LIMIT_EXCEEDED)");
            e.add("memory limit exceeded");
            e.add("TIMEOUT_EXCEEDED");
            e.add("Timeout exceeded");
        }
    }

    @Override
    public void check() throws SQLException {
        if (!state.getClickHouseOptions().mapTupleContainerOracle) {
            throw new IgnoreMeException();
        }
        long id = CTR.incrementAndGet();
        Randomly r = state.getRandomly();
        String table = state.getDatabaseName() + ".cont_" + id + "_t";
        String create = "CREATE TABLE " + table
                + " (k UInt32, m Map(String, Int64), t Tuple(Int64, String), arr Array(Int64)) "
                + "ENGINE = MergeTree ORDER BY k";

        List<Map<String, Long>> mapModel = new ArrayList<>();
        List<Long> tupleNumModel = new ArrayList<>();
        List<String> tupleStrModel = new ArrayList<>();
        List<List<Long>> arrModel = new ArrayList<>();

        try {
            logStmt(create);
            if (!new SQLQueryAdapter(create, createErrors, true).execute(state)) {
                throw new IgnoreMeException();
            }

            int rows = 20 + r.getInteger(0, 41);
            StringBuilder sb = new StringBuilder("INSERT INTO ").append(table).append(" (k, m, t, arr) VALUES ");
            for (int i = 0; i < rows; i++) {
                Map<String, Long> entries = new LinkedHashMap<>();
                int n = r.getInteger(0, 4);
                for (int j = 0; j < n; j++) {
                    String key = WORDS.get(r.getInteger(0, WORDS.size()));
                    long val = r.getInteger(0, 100);
                    entries.put(key, val);
                }
                mapModel.add(entries);

                long tNum = r.getInteger(0, 1000);
                String tStr = WORDS.get(r.getInteger(0, WORDS.size()));
                tupleNumModel.add(tNum);
                tupleStrModel.add(tStr);

                List<Long> arr = new ArrayList<>();
                int an = r.getInteger(0, 5);
                for (int j = 0; j < an; j++) {
                    arr.add((long) r.getInteger(0, 100));
                }
                arrModel.add(arr);

                if (i > 0) {
                    sb.append(", ");
                }
                sb.append('(').append(i).append(", ").append(renderIntMapLiteral(entries)).append(", (").append(tNum)
                        .append(", '").append(esc(tStr)).append("'), ").append(renderIntArrayLiteral(arr)).append(')');
            }
            logStmt(sb.toString());
            if (!new SQLQueryAdapter(sb.toString(), readErrors, true).execute(state)) {
                throw new IgnoreMeException();
            }

            List<String> expMapKeys = new ArrayList<>();
            List<String> expMapValues = new ArrayList<>();
            List<String> expMapLength = new ArrayList<>();
            List<String> expTupleNum = new ArrayList<>();
            List<String> expTupleStr = new ArrayList<>();
            List<String> expArrLength = new ArrayList<>();
            List<String> expArrSorted = new ArrayList<>();
            for (int i = 0; i < rows; i++) {
                Map<String, Long> m = mapModel.get(i);
                expMapKeys.add(renderStringArrayText(new ArrayList<>(new TreeSet<>(m.keySet()))));
                List<Long> sortedVals = new ArrayList<>(m.values());
                sortedVals.sort(Long::compareTo);
                expMapValues.add(renderIntArrayText(sortedVals));
                expMapLength.add(String.valueOf(m.size()));
                expTupleNum.add(String.valueOf(tupleNumModel.get(i)));
                expTupleStr.add(tupleStrModel.get(i));
                List<Long> sortedArr = new ArrayList<>(arrModel.get(i));
                sortedArr.sort(Long::compareTo);
                expArrLength.add(String.valueOf(arrModel.get(i).size()));
                expArrSorted.add(renderIntArrayText(sortedArr));
            }

            assertProbe(table, "toString(arraySort(mapKeys(m)))", expMapKeys, "mapKeys");
            assertProbe(table, "toString(arraySort(mapValues(m)))", expMapValues, "mapValues");
            assertProbe(table, "toString(length(m))", expMapLength, "length(m)");
            assertProbe(table, "toString(tupleElement(t, 1))", expTupleNum, "tupleElement1");
            assertProbe(table, "toString(t.1)", expTupleNum, "t.1");
            assertProbe(table, "toString(tupleElement(t, 2))", expTupleStr, "tupleElement2");
            assertProbe(table, "toString(t.2)", expTupleStr, "t.2");
            assertProbe(table, "toString(length(arr))", expArrLength, "length(arr)");
            assertProbe(table, "toString(arraySort(arr))", expArrSorted, "arraySort(arr)");

            String word = WORDS.get(r.getInteger(0, WORDS.size()));
            List<String> expContains = new ArrayList<>();
            for (int i = 0; i < rows; i++) {
                expContains.add(mapModel.get(i).containsKey(word) ? "1" : "0");
            }
            assertProbe(table, "toString(mapContains(m, '" + esc(word) + "'))", expContains, "mapContains");
        } finally {
            dropQuietly(table);
        }
    }

    private void assertProbe(String table, String projection, List<String> expected, String label) throws SQLException {
        String query = "SELECT " + projection + " FROM " + table + " ORDER BY k";
        logStmt(query);
        List<String> actual = ComparatorHelper.getResultSetFirstColumnAsString(query, readErrors, state);
        if (actual.size() != expected.size()) {
            throw new AssertionError(String.format(
                    "container ground-truth row-count mismatch (%s): Java expects %d rows but query returned %d. Q: %s",
                    label, expected.size(), actual.size(), query));
        }
        for (int i = 0; i < expected.size(); i++) {
            if (!expected.get(i).equals(actual.get(i))) {
                throw new AssertionError(String.format(
                        "container ground-truth value mismatch (%s) at row %d: Java expects %s but query returned %s. "
                                + "Q: %s",
                        label, i, expected.get(i), actual.get(i), query));
            }
        }
    }

    static String renderIntMapLiteral(Map<String, Long> entries) {
        StringBuilder sb = new StringBuilder("map(");
        boolean first = true;
        for (Map.Entry<String, Long> e : entries.entrySet()) {
            if (!first) {
                sb.append(", ");
            }
            first = false;
            sb.append('\'').append(esc(e.getKey())).append("', ").append(e.getValue());
        }
        return sb.append(')').toString();
    }

    static String renderIntArrayLiteral(List<Long> elems) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < elems.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(elems.get(i));
        }
        return sb.append(']').toString();
    }

    static String renderIntArrayText(List<Long> elems) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < elems.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(elems.get(i));
        }
        return sb.append(']').toString();
    }

    static String renderStringArrayText(List<String> elems) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < elems.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('\'').append(elems.get(i)).append('\'');
        }
        return sb.append(']').toString();
    }

    static String esc(String s) {
        return s.replace("\\", "\\\\").replace("'", "\\'");
    }

    private void dropQuietly(String table) {
        try {
            new SQLQueryAdapter("DROP TABLE IF EXISTS " + table, readErrors, true).execute(state);
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
