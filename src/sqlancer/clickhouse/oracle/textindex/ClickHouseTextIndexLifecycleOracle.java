package sqlancer.clickhouse.oracle.textindex;

import java.sql.SQLException;
import java.util.ArrayList;
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

public class ClickHouseTextIndexLifecycleOracle implements TestOracle<ClickHouseGlobalState> {

    private static final AtomicLong CTR = new AtomicLong();

    static final String INDEX_NAME = "tidx";

    private final ClickHouseGlobalState state;

    private final ExpectedErrors createErrors = new ExpectedErrors();
    private final ExpectedErrors alterErrors = new ExpectedErrors();
    private final ExpectedErrors readErrors = new ExpectedErrors();

    public ClickHouseTextIndexLifecycleOracle(ClickHouseGlobalState state) {
        this.state = state;
        for (ExpectedErrors e : List.of(createErrors, alterErrors, readErrors)) {
            ClickHouseErrors.addSessionSettingsErrors(e);
            ClickHouseErrors.addTextIndexErrors(e);

            e.add("UNKNOWN_TABLE");
            e.add("Unknown table expression identifier");

            e.add("(MEMORY_LIMIT_EXCEEDED)");
            e.add("memory limit exceeded");

            e.add("TIMEOUT_EXCEEDED");
            e.add("Timeout exceeded");
        }
        alterErrors.addAll(ClickHouseErrors.getAlterErrors());
        alterErrors.addAll(ClickHouseErrors.getMutationErrors());
    }

    @Override
    public void check() throws SQLException {
        long id = CTR.incrementAndGet();
        String tableA = state.getDatabaseName() + ".txtlc_" + id + "_a";
        String tableB = state.getDatabaseName() + ".txtlc_" + id + "_b";
        Randomly r = state.getRandomly();

        boolean splitByNonAlpha = r.getInteger(0, 4) != 0;
        String indexType = splitByNonAlpha ? "text(tokenizer = 'splitByNonAlpha')" : "text(tokenizer = ngrams(3))";

        String createA = "CREATE TABLE " + tableA + " (k UInt32, s String, INDEX " + INDEX_NAME + " (s) TYPE "
                + indexType + " GRANULARITY 1) ENGINE = MergeTree ORDER BY k";
        String createB = "CREATE TABLE " + tableB + " (k UInt32, s String) ENGINE = MergeTree ORDER BY k";

        List<String> corpus = new ArrayList<>();
        try {
            logStmt(createA);
            if (!new SQLQueryAdapter(createA, createErrors, true).execute(state)) {
                throw new IgnoreMeException();
            }
            logStmt(createB);
            if (!new SQLQueryAdapter(createB, createErrors, true).execute(state)) {
                throw new IgnoreMeException();
            }

            int blocks = 3 + r.getInteger(0, 3);
            for (int b = 0; b < blocks; b++) {
                int rows = 30 + r.getInteger(0, 51);
                List<String> blockRows = ClickHouseTextIndexLikeOracle.buildCorpus(r, rows,
                        ClickHouseTextIndexLikeOracle.TOKEN_VOCABULARY);
                String insertA = ClickHouseTextIndexLikeOracle.renderInsertBlock(tableA, corpus.size(), blockRows);
                String insertB = ClickHouseTextIndexLikeOracle.renderInsertBlock(tableB, corpus.size(), blockRows);
                logStmt(insertA);
                if (!new SQLQueryAdapter(insertA, readErrors, true).execute(state)) {
                    throw new IgnoreMeException();
                }
                logStmt(insertB);
                if (!new SQLQueryAdapter(insertB, readErrors, true).execute(state)) {
                    throw new IgnoreMeException();
                }
                corpus.addAll(blockRows);
            }

            String addIndex = "ALTER TABLE " + tableB + " ADD INDEX " + INDEX_NAME + " (s) TYPE " + indexType
                    + " GRANULARITY 1";
            String materialize = "ALTER TABLE " + tableB + " MATERIALIZE INDEX " + INDEX_NAME
                    + " SETTINGS mutations_sync = 2";
            logStmt(addIndex);
            if (!new SQLQueryAdapter(addIndex, alterErrors, true).execute(state)) {
                throw new IgnoreMeException();
            }
            logStmt(materialize);
            if (!new SQLQueryAdapter(materialize, alterErrors, true).execute(state)) {
                throw new IgnoreMeException();
            }

            for (String predicate : predicateBattery(r, splitByNonAlpha)) {
                List<String> keysA = keys(tableA, predicate, "");
                List<String> keysB = keys(tableB, predicate, "");
                List<String> keysScan = keys(tableB, predicate, " SETTINGS use_skip_indexes = 0");

                if (!keysA.equals(keysScan)) {
                    throw new AssertionError(String.format(
                            "text-index lifecycle mismatch (born-with-index vs scan): predicate %s. A keys %s vs "
                                    + "scan keys %s. index type %s",
                            predicate, truncate(keysA), truncate(keysScan), indexType));
                }
                if (!keysB.equals(keysScan)) {
                    throw new AssertionError(String.format(
                            "text-index lifecycle mismatch (ADD+MATERIALIZE vs scan): predicate %s. B keys %s vs "
                                    + "scan keys %s. index type %s",
                            predicate, truncate(keysB), truncate(keysScan), indexType));
                }
            }
        } finally {
            dropQuietly(tableA);
            dropQuietly(tableB);
        }
    }

    private static List<String> predicateBattery(Randomly r, boolean splitByNonAlpha) {
        List<String> vocab = ClickHouseTextIndexLikeOracle.TOKEN_VOCABULARY;
        String w1 = ClickHouseTextIndexLikeOracle.escapeStringLiteral(vocab.get(r.getInteger(0, vocab.size())));
        String w2 = ClickHouseTextIndexLikeOracle.escapeStringLiteral(vocab.get(r.getInteger(0, vocab.size())));
        List<String> battery = new ArrayList<>();
        battery.add("s LIKE '%" + w1 + "%'");
        battery.add("hasToken(s, '" + w1 + "')");
        if (splitByNonAlpha) {
            battery.add("hasAllTokens(s, '" + w1 + " " + w2 + "')");
            battery.add("hasAnyTokens(s, '" + w1 + " " + w2 + "')");
        }
        return battery;
    }

    private List<String> keys(String table, String predicate, String suffix) throws SQLException {
        return ComparatorHelper.getResultSetFirstColumnAsString(
                "SELECT toString(k) FROM " + table + " WHERE " + predicate + " ORDER BY k" + suffix, readErrors, state);
    }

    private static String truncate(List<String> keys) {
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
