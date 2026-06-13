package sqlancer.clickhouse.oracle.textindex;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import sqlancer.ComparatorHelper;
import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.clickhouse.ClickHouseErrors;
import sqlancer.clickhouse.ClickHouseProvider.ClickHouseGlobalState;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;

public class ClickHouseTextIndexContainerOracle implements TestOracle<ClickHouseGlobalState> {

    private static final AtomicLong CTR = new AtomicLong();

    static final List<String> VOCAB = ClickHouseTextIndexLikeOracle.TOKEN_VOCABULARY;

    enum Mode {
        ARRAY,
        MAP
    }

    private final ClickHouseGlobalState state;

    private final ExpectedErrors createErrors = new ExpectedErrors();
    private final ExpectedErrors readErrors = new ExpectedErrors();

    public ClickHouseTextIndexContainerOracle(ClickHouseGlobalState state) {
        this.state = state;
        for (ExpectedErrors e : List.of(createErrors, readErrors)) {
            ClickHouseErrors.addSessionSettingsErrors(e);
            ClickHouseErrors.addTextIndexErrors(e);

            e.add("UNKNOWN_TABLE");
            e.add("Unknown table expression identifier");

            e.add("(MEMORY_LIMIT_EXCEEDED)");
            e.add("memory limit exceeded");

            e.add("TIMEOUT_EXCEEDED");
            e.add("Timeout exceeded");
        }
        readErrors.add("INDEX_NOT_USED");
    }

    @Override
    public void check() throws SQLException {
        long id = CTR.incrementAndGet();
        Mode mode = Mode.values()[(int) Randomly.getNotCachedInteger(0, Mode.values().length)];
        if (mode == Mode.ARRAY) {
            checkArray(id);
        } else {
            checkMap(id);
        }
    }

    private void checkArray(long id) throws SQLException {
        String table = state.getDatabaseName() + ".txtarr_" + id + "_t";
        Randomly r = state.getRandomly();
        String create = "CREATE TABLE " + table + " (k UInt32, arr Array(String), INDEX aidx (arr) "
                + "TYPE text(tokenizer = array) GRANULARITY 1) ENGINE = MergeTree ORDER BY k";

        List<List<String>> corpus = new ArrayList<>();
        try {
            logStmt(create);
            if (!new SQLQueryAdapter(create, createErrors, true).execute(state)) {
                throw new IgnoreMeException();
            }
            int blocks = 3 + r.getInteger(0, 3);
            for (int b = 0; b < blocks; b++) {
                int rows = 30 + r.getInteger(0, 51);
                List<List<String>> block = new ArrayList<>(rows);
                StringBuilder sb = new StringBuilder("INSERT INTO ").append(table).append(" (k, arr) VALUES ");
                for (int i = 0; i < rows; i++) {
                    List<String> elems = new ArrayList<>();
                    int n = r.getInteger(0, 4);
                    for (int j = 0; j < n; j++) {
                        elems.add(VOCAB.get(r.getInteger(0, VOCAB.size())));
                    }
                    block.add(elems);
                    if (i > 0) {
                        sb.append(", ");
                    }
                    sb.append('(').append(corpus.size() + i).append(", ").append(renderArrayLiteral(elems)).append(')');
                }
                logStmt(sb.toString());
                if (!new SQLQueryAdapter(sb.toString(), readErrors, true).execute(state)) {
                    throw new IgnoreMeException();
                }
                corpus.addAll(block);
            }

            String w1 = VOCAB.get(r.getInteger(0, VOCAB.size()));
            String w2 = VOCAB.get(r.getInteger(0, VOCAB.size()));
            String predicate;
            long expected;
            String fn = Randomly.fromOptions("has", "hasAny", "hasAll");
            switch (fn) {
            case "hasAny":
                predicate = "hasAny(arr, ['" + esc(w1) + "', '" + esc(w2) + "'])";
                expected = corpus.stream().filter(row -> row.contains(w1) || row.contains(w2)).count();
                break;
            case "hasAll":
                predicate = "hasAll(arr, ['" + esc(w1) + "', '" + esc(w2) + "'])";
                expected = corpus.stream().filter(row -> row.contains(w1) && row.contains(w2)).count();
                break;
            default:
                predicate = "has(arr, '" + esc(w1) + "')";
                expected = corpus.stream().filter(row -> row.contains(w1)).count();
                break;
            }
            assertArmsAndGroundTruth(table, predicate, "aidx", expected, fn, create);
        } finally {
            dropQuietly(table);
        }
    }

    private void checkMap(long id) throws SQLException {
        String table = state.getDatabaseName() + ".txtmap_" + id + "_t";
        Randomly r = state.getRandomly();
        String create = "CREATE TABLE " + table + " (k UInt32, m Map(String, String), "
                + "INDEX mkidx mapKeys(m) TYPE text(tokenizer = array) GRANULARITY 1, "
                + "INDEX mvidx mapValues(m) TYPE text(tokenizer = array) GRANULARITY 1) "
                + "ENGINE = MergeTree ORDER BY k";

        List<Map<String, String>> corpus = new ArrayList<>();
        try {
            logStmt(create);
            if (!new SQLQueryAdapter(create, createErrors, true).execute(state)) {
                throw new IgnoreMeException();
            }
            int blocks = 3 + r.getInteger(0, 3);
            for (int b = 0; b < blocks; b++) {
                int rows = 30 + r.getInteger(0, 51);
                List<Map<String, String>> block = new ArrayList<>(rows);
                StringBuilder sb = new StringBuilder("INSERT INTO ").append(table).append(" (k, m) VALUES ");
                for (int i = 0; i < rows; i++) {
                    Map<String, String> entries = new LinkedHashMap<>();
                    int n = r.getInteger(0, 4);
                    for (int j = 0; j < n; j++) {
                        String key = VOCAB.get(r.getInteger(0, VOCAB.size()));
                        String val = VOCAB.get(r.getInteger(0, VOCAB.size()));
                        entries.put(key, val);
                    }
                    block.add(entries);
                    if (i > 0) {
                        sb.append(", ");
                    }
                    sb.append('(').append(corpus.size() + i).append(", ").append(renderMapLiteral(entries)).append(')');
                }
                logStmt(sb.toString());
                if (!new SQLQueryAdapter(sb.toString(), readErrors, true).execute(state)) {
                    throw new IgnoreMeException();
                }
                corpus.addAll(block);
            }

            String w = VOCAB.get(r.getInteger(0, VOCAB.size()));
            boolean onKeys = Randomly.getBoolean();
            String predicate;
            long expected;
            if (onKeys) {
                predicate = "mapContainsKey(m, '" + esc(w) + "')";
                expected = corpus.stream().filter(m -> m.containsKey(w)).count();
            } else {
                predicate = "mapContainsValue(m, '" + esc(w) + "')";
                expected = corpus.stream().filter(m -> m.containsValue(w)).count();
            }
            assertArmsAndGroundTruth(table, predicate, "mkidx,mvidx", expected, onKeys ? "mapContainsKey" : "mapContainsValue",
                    create);
        } finally {
            dropQuietly(table);
        }
    }

    private void assertArmsAndGroundTruth(String table, String predicate, String indices, long expected, String label,
            String create) throws SQLException {
        String[] suffixes = { "", " SETTINGS ignore_data_skipping_indices = '" + indices + "'",
                " SETTINGS use_skip_indexes = 0" };
        String[] names = { "DEFAULT", "INDEX_IGNORED", "SCAN_NO_SKIP" };

        String[] counts = new String[suffixes.length];
        List<List<String>> keyLists = new ArrayList<>(suffixes.length);
        for (int i = 0; i < suffixes.length; i++) {
            counts[i] = readSingleValue(
                    "SELECT toString(count()) FROM " + table + " WHERE " + predicate + suffixes[i]);
            keyLists.add(ComparatorHelper.getResultSetFirstColumnAsString(
                    "SELECT toString(k) FROM " + table + " WHERE " + predicate + " ORDER BY k" + suffixes[i],
                    readErrors, state));
        }

        for (int i = 1; i < suffixes.length; i++) {
            if (!counts[0].equals(counts[i])) {
                throw new AssertionError(String.format(
                        "container text-index count mismatch: predicate %s (%s): arm %s saw %s but arm %s saw %s. "
                                + "DDL: %s",
                        predicate, label, names[0], counts[0], names[i], counts[i], create));
            }
            if (!keyLists.get(0).equals(keyLists.get(i))) {
                throw new AssertionError(String.format(
                        "container text-index key mismatch: predicate %s (%s): arm %s keys %s vs arm %s keys %s. "
                                + "DDL: %s",
                        predicate, label, names[0], truncate(keyLists.get(0)), names[i], truncate(keyLists.get(i)),
                        create));
            }
        }

        if (!String.valueOf(expected).equals(counts[0])) {
            throw new AssertionError(String.format(
                    "container text-index ground-truth mismatch: predicate %s (%s): Java expects %d matches but all "
                            + "arms agree on %s. DDL: %s",
                    predicate, label, expected, counts[0], create));
        }
    }

    static String renderArrayLiteral(List<String> elems) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < elems.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append('\'').append(esc(elems.get(i))).append('\'');
        }
        return sb.append(']').toString();
    }

    static String renderMapLiteral(Map<String, String> entries) {
        StringBuilder sb = new StringBuilder("map(");
        boolean first = true;
        for (Map.Entry<String, String> e : entries.entrySet()) {
            if (!first) {
                sb.append(", ");
            }
            first = false;
            sb.append('\'').append(esc(e.getKey())).append("', '").append(esc(e.getValue())).append('\'');
        }
        return sb.append(')').toString();
    }

    static String esc(String s) {
        return s.replace("\\", "\\\\").replace("'", "\\'");
    }

    private static String truncate(List<String> keys) {
        int limit = 50;
        if (keys.size() <= limit) {
            return keys.toString();
        }
        return keys.subList(0, limit) + "... (" + keys.size() + " total)";
    }

    private String readSingleValue(String query) throws SQLException {
        List<String> rows = ComparatorHelper.getResultSetFirstColumnAsString(query, readErrors, state);
        if (rows.size() != 1) {
            throw new IgnoreMeException();
        }
        return rows.get(0);
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
