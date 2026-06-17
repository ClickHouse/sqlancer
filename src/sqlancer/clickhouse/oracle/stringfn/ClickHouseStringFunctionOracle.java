package sqlancer.clickhouse.oracle.stringfn;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import sqlancer.ComparatorHelper;
import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.clickhouse.ClickHouseErrors;
import sqlancer.clickhouse.ClickHouseProvider.ClickHouseGlobalState;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;

public class ClickHouseStringFunctionOracle implements TestOracle<ClickHouseGlobalState> {

    private static final AtomicLong CTR = new AtomicLong();

    private static final List<String> WORDS = List.of(
            "alpha", "bravo", "charlie", "delta", "echo", "foxtrot");

    private static final List<String> SEPARATORS = List.of("-", "_");

    private static final List<String> NEEDLE_CANDIDATES = List.of(
            "alpha", "bravo", "charlie", "delta", "echo", "foxtrot",
            "al", "br", "ch", "de", "ec", "fo");

    enum Probe {
        LENGTH,
        LENGTH_UTF8,
        LOWER,
        UPPER,
        REVERSE,
        SUBSTRING_2_3,
        POSITION,
        COUNT_SUBSTRINGS,
        STARTS_WITH,
        ENDS_WITH,
        CONCAT,
        REPEAT_2,
        REPLACE_ALL,
        EMPTY,
        NOT_EMPTY
    }

    private final ClickHouseGlobalState state;

    private final ExpectedErrors createErrors = new ExpectedErrors();
    private final ExpectedErrors readErrors = new ExpectedErrors();

    public ClickHouseStringFunctionOracle(ClickHouseGlobalState state) {
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
        if (!state.getClickHouseOptions().stringFunctionOracle) {
            throw new IgnoreMeException();
        }
        long id = CTR.incrementAndGet();
        Randomly r = state.getRandomly();
        String table = state.getDatabaseName() + ".strfn_" + id;
        String create = "CREATE TABLE " + table
                + " (k UInt32, s String) ENGINE = MergeTree ORDER BY k";

        List<String> model = new ArrayList<>();

        try {
            logStmt(create);
            if (!new SQLQueryAdapter(create, createErrors, true).execute(state)) {
                throw new IgnoreMeException();
            }

            int rows = 25 + r.getInteger(0, 21);
            String sep = SEPARATORS.get(r.getInteger(0, SEPARATORS.size()));
            StringBuilder sb = new StringBuilder("INSERT INTO ").append(table).append(" (k, s) VALUES ");
            for (int i = 0; i < rows; i++) {
                int parts = 1 + r.getInteger(0, 3);
                StringBuilder wordJoiner = new StringBuilder();
                for (int j = 0; j < parts; j++) {
                    if (j > 0) {
                        wordJoiner.append(sep);
                    }
                    wordJoiner.append(WORDS.get(r.getInteger(0, WORDS.size())));
                }
                String s = wordJoiner.toString();
                model.add(s);
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append('(').append(i).append(", '").append(esc(s)).append("')");
            }
            logStmt(sb.toString());
            if (!new SQLQueryAdapter(sb.toString(), readErrors, true).execute(state)) {
                throw new IgnoreMeException();
            }

            String needle = NEEDLE_CANDIDATES.get(r.getInteger(0, NEEDLE_CANDIDATES.size()));
            String suffix = WORDS.get(r.getInteger(0, WORDS.size()));

            List<Probe> allProbes = new ArrayList<>(List.of(Probe.values()));
            Collections.shuffle(allProbes);
            int probeCount = 4 + r.getInteger(0, 3);
            List<Probe> selected = allProbes.subList(0, Math.min(probeCount, allProbes.size()));

            for (Probe probe : selected) {
                runProbe(probe, table, model, needle, suffix, rows);
            }

        } finally {
            dropQuietly(table);
        }
    }

    private void runProbe(Probe probe, String table, List<String> model,
            String needle, String suffix, int rows) throws SQLException {
        switch (probe) {
        case LENGTH: {
            List<String> expected = new ArrayList<>(rows);
            for (String s : model) {
                expected.add(String.valueOf(s.length()));
            }
            assertProbe(table, "toString(length(s))", expected, "length");
            break;
        }
        case LENGTH_UTF8: {
            List<String> expected = new ArrayList<>(rows);
            for (String s : model) {
                expected.add(String.valueOf(s.length()));
            }
            assertProbe(table, "toString(lengthUTF8(s))", expected, "lengthUTF8");
            break;
        }
        case LOWER: {
            List<String> expected = new ArrayList<>(rows);
            for (String s : model) {
                expected.add(s.toLowerCase());
            }
            assertProbe(table, "lower(s)", expected, "lower");
            break;
        }
        case UPPER: {
            List<String> expected = new ArrayList<>(rows);
            for (String s : model) {
                expected.add(s.toUpperCase());
            }
            assertProbe(table, "upper(s)", expected, "upper");
            break;
        }
        case REVERSE: {
            List<String> expected = new ArrayList<>(rows);
            for (String s : model) {
                expected.add(new StringBuilder(s).reverse().toString());
            }
            assertProbe(table, "reverse(s)", expected, "reverse");
            break;
        }
        case SUBSTRING_2_3: {
            List<String> expected = new ArrayList<>(rows);
            for (String s : model) {
                int startJava = 1;
                if (startJava >= s.length()) {
                    expected.add("");
                } else {
                    int end = Math.min(s.length(), startJava + 3);
                    expected.add(s.substring(startJava, end));
                }
            }
            assertProbe(table, "substring(s, 2, 3)", expected, "substring(2,3)");
            break;
        }
        case POSITION: {
            List<String> expected = new ArrayList<>(rows);
            for (String s : model) {
                int idx = s.indexOf(needle);
                expected.add(String.valueOf(idx < 0 ? 0 : idx + 1));
            }
            assertProbe(table, "toString(position(s, '" + esc(needle) + "'))", expected, "position");
            break;
        }
        case COUNT_SUBSTRINGS: {
            List<String> expected = new ArrayList<>(rows);
            for (String s : model) {
                expected.add(String.valueOf(countNonOverlapping(s, needle)));
            }
            assertProbe(table, "toString(countSubstrings(s, '" + esc(needle) + "'))", expected, "countSubstrings");
            break;
        }
        case STARTS_WITH: {
            List<String> expected = new ArrayList<>(rows);
            for (String s : model) {
                expected.add(s.startsWith(needle) ? "1" : "0");
            }
            assertProbe(table, "toString(startsWith(s, '" + esc(needle) + "'))", expected, "startsWith");
            break;
        }
        case ENDS_WITH: {
            List<String> expected = new ArrayList<>(rows);
            for (String s : model) {
                expected.add(s.endsWith(suffix) ? "1" : "0");
            }
            assertProbe(table, "toString(endsWith(s, '" + esc(suffix) + "'))", expected, "endsWith");
            break;
        }
        case CONCAT: {
            List<String> expected = new ArrayList<>(rows);
            for (String s : model) {
                expected.add(s + suffix);
            }
            assertProbe(table, "concat(s, '" + esc(suffix) + "')", expected, "concat");
            break;
        }
        case REPEAT_2: {
            List<String> expected = new ArrayList<>(rows);
            for (String s : model) {
                expected.add(s + s);
            }
            assertProbe(table, "repeat(s, 2)", expected, "repeat(2)");
            break;
        }
        case REPLACE_ALL: {
            List<String> expected = new ArrayList<>(rows);
            for (String s : model) {
                expected.add(s.replace(needle, "X"));
            }
            assertProbe(table, "replaceAll(s, '" + esc(needle) + "', 'X')", expected, "replaceAll");
            break;
        }
        case EMPTY: {
            List<String> expected = new ArrayList<>(rows);
            for (String s : model) {
                expected.add(s.isEmpty() ? "1" : "0");
            }
            assertProbe(table, "toString(empty(s))", expected, "empty");
            break;
        }
        case NOT_EMPTY: {
            List<String> expected = new ArrayList<>(rows);
            for (String s : model) {
                expected.add(s.isEmpty() ? "0" : "1");
            }
            assertProbe(table, "toString(notEmpty(s))", expected, "notEmpty");
            break;
        }
        default:
            break;
        }
    }

    private static int countNonOverlapping(String haystack, String needle) {
        if (needle.isEmpty()) {
            return 0;
        }
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    private void assertProbe(String table, String projection, List<String> expected, String label) throws SQLException {
        String query = "SELECT " + projection + " FROM " + table + " ORDER BY k";
        logStmt(query);
        List<String> actual = ComparatorHelper.getResultSetFirstColumnAsString(query, readErrors, state);
        if (actual.size() != expected.size()) {
            throw new AssertionError(String.format(
                    "stringfn ground-truth row-count mismatch (%s): Java expects %d rows but query returned %d. Q: %s",
                    label, expected.size(), actual.size(), query));
        }
        for (int i = 0; i < expected.size(); i++) {
            String exp = expected.get(i);
            String act = actual.get(i) == null ? "" : actual.get(i);
            if (!exp.equals(act)) {
                throw new AssertionError(String.format(
                        "stringfn ground-truth value mismatch (%s) at row %d: Java expects %s but query returned %s. Q: %s",
                        label, i, exp, act, query));
            }
        }
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
