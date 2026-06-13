package sqlancer.clickhouse.oracle.textindex;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

import sqlancer.ComparatorHelper;
import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.clickhouse.ClickHouseErrors;
import sqlancer.clickhouse.ClickHouseProvider.ClickHouseGlobalState;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;

public class ClickHouseTextIndexPreprocessorOracle implements TestOracle<ClickHouseGlobalState> {

    private static final AtomicLong PP_COUNTER = new AtomicLong();

    static final String PREPROCESSOR_INDEX = "idxp";

    private static final String FORCE_DIRECT_READ = " SETTINGS force_data_skipping_indices = '" + PREPROCESSOR_INDEX
            + "', query_plan_direct_read_from_text_index = 1, query_plan_text_index_add_hint = 0";

    private final ClickHouseGlobalState state;

    private final ExpectedErrors createErrors = new ExpectedErrors();
    private final ExpectedErrors readErrors = new ExpectedErrors();

    public ClickHouseTextIndexPreprocessorOracle(ClickHouseGlobalState state) {
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
        long id = PP_COUNTER.incrementAndGet();
        String table = state.getDatabaseName() + ".txtpp_" + id + "_t";
        Randomly r = state.getRandomly();

        String create = "CREATE TABLE " + table + " (k UInt32, s String, INDEX " + PREPROCESSOR_INDEX
                + " (s) TYPE text(tokenizer = 'splitByNonAlpha', preprocessor = lower(s)) GRANULARITY 1) "
                + "ENGINE = MergeTree ORDER BY k";

        List<String> corpus = new ArrayList<>();
        try {
            logStmt(create);
            if (!new SQLQueryAdapter(create, createErrors, true).execute(state)) {
                throw new IgnoreMeException();
            }

            int blocks = 3 + r.getInteger(0, 3);
            for (int b = 0; b < blocks; b++) {
                int rows = 30 + r.getInteger(0, 51);
                List<String> blockRows = buildMixedCaseCorpus(r, rows);
                String insert = renderInsertBlock(table, corpus.size(), blockRows);
                logStmt(insert);
                if (!new SQLQueryAdapter(insert, readErrors, true).execute(state)) {
                    throw new IgnoreMeException();
                }
                corpus.addAll(blockRows);
            }

            String mixedNeedle = mixedCase(r, ClickHouseTextIndexLikeOracle.TOKEN_VOCABULARY
                    .get(r.getInteger(0, ClickHouseTextIndexLikeOracle.TOKEN_VOCABULARY.size())));

            String query = "SELECT toString(k) FROM " + table + " WHERE hasToken(s, '" + escape(mixedNeedle)
                    + "') ORDER BY k" + FORCE_DIRECT_READ;
            List<String> keys = ComparatorHelper.getResultSetFirstColumnAsString(query, readErrors, state);

            List<String> expected = new ArrayList<>();
            for (int i = 0; i < corpus.size(); i++) {
                if (lowerTokenMatch(corpus.get(i), mixedNeedle)) {
                    expected.add(String.valueOf(i));
                }
            }

            if (!keys.equals(expected)) {
                throw new AssertionError(String.format(
                        "text-index preprocessor mismatch: needle '%s'. direct-read over preprocessor=lower(s) index "
                                + "returned keys %s but Java lower()-token ground truth over the %d-row corpus expects "
                                + "%s. DDL: %s",
                        mixedNeedle, truncateForMessage(keys), corpus.size(), truncateForMessage(expected), create));
            }
        } finally {
            dropQuietly(table);
        }
    }

    static boolean lowerTokenMatch(String row, String needle) {
        String ln = needle.toLowerCase(Locale.ROOT);
        for (String tok : row.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (tok.equals(ln)) {
                return true;
            }
        }
        return false;
    }

    static List<String> buildMixedCaseCorpus(Randomly r, int rowCount) {
        List<String> rows = new ArrayList<>(rowCount);
        for (int i = 0; i < rowCount; i++) {
            int tokens = 2 + r.getInteger(0, 3);
            StringBuilder sb = new StringBuilder();
            for (int t = 0; t < tokens; t++) {
                if (t > 0) {
                    sb.append(' ');
                }
                String word = ClickHouseTextIndexLikeOracle.TOKEN_VOCABULARY
                        .get(r.getInteger(0, ClickHouseTextIndexLikeOracle.TOKEN_VOCABULARY.size()));
                sb.append(mixedCase(r, word));
            }
            rows.add(sb.toString());
        }
        return rows;
    }

    static String mixedCase(Randomly r, String word) {
        switch (r.getInteger(0, 3)) {
        case 0:
            return word.toUpperCase(Locale.ROOT);
        case 1:
            return word.isEmpty() ? word : Character.toUpperCase(word.charAt(0)) + word.substring(1);
        default:
            return word;
        }
    }

    static String renderInsertBlock(String table, int startKey, List<String> rows) {
        StringBuilder sb = new StringBuilder("INSERT INTO ").append(table).append(" (k, s) VALUES ");
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append('(').append(startKey + i).append(", '").append(escape(rows.get(i))).append("')");
        }
        return sb.toString();
    }

    static String escape(String s) {
        return s.replace("\\", "\\\\").replace("'", "\\'");
    }

    private static String truncateForMessage(List<String> keys) {
        int limit = 50;
        if (keys.size() <= limit) {
            return keys.toString();
        }
        return keys.subList(0, limit) + "... (" + keys.size() + " total)";
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
