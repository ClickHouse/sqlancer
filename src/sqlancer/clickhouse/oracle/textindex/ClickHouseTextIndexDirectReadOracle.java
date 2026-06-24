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

public class ClickHouseTextIndexDirectReadOracle implements TestOracle<ClickHouseGlobalState> {

    private static final AtomicLong CTR = new AtomicLong();

    static final List<String> CJK_DOCS = List.of("我来自北京邮电大学", "北京大学计算机", "上海交通大学", "清华大学软件学院",
            "深圳腾讯科技公司");

    enum Scenario {
        SPLIT_CONTROL("text(tokenizer = 'splitByNonAlpha')"),
        ASCII_CJK("text(tokenizer = 'asciiCJK')"),
        ARRAY("text(tokenizer = array)"),
        NGRAMS("text(tokenizer = ngrams(3))"),
        SPARSEGRAMS("text(tokenizer = sparseGrams(3, 5))"),
        SPLIT_BY_STRING("text(tokenizer = splitByString([' ']))"),
        PREPROCESSOR_LOWER("text(tokenizer = 'splitByNonAlpha', preprocessor = lower(s))");

        private final String indexType;

        Scenario(String indexType) {
            this.indexType = indexType;
        }
    }

    private final ClickHouseGlobalState state;

    private final ExpectedErrors createErrors = new ExpectedErrors();
    private final ExpectedErrors readErrors = new ExpectedErrors();

    public ClickHouseTextIndexDirectReadOracle(ClickHouseGlobalState state) {
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
    }

    @Override
    public void check() throws SQLException {
        long id = CTR.incrementAndGet();
        String table = state.getDatabaseName() + ".txtdr_" + id + "_t";
        Randomly r = state.getRandomly();

        Scenario scenario = Scenario.values()[(int) Randomly.getNotCachedInteger(0, Scenario.values().length)];
        String create = "CREATE TABLE " + table + " (k UInt32, s String, INDEX idx (s) TYPE " + scenario.indexType
                + " GRANULARITY 1) ENGINE = MergeTree ORDER BY k";

        try {
            logStmt(create);
            if (!new SQLQueryAdapter(create, createErrors, true).execute(state)) {
                throw new IgnoreMeException();
            }

            List<String> corpus = buildCorpus(r, scenario);
            for (int start = 0; start < corpus.size(); start += 50) {
                int end = Math.min(start + 50, corpus.size());
                String insert = renderInsertBlock(table, start, corpus.subList(start, end));
                logStmt(insert);
                if (!new SQLQueryAdapter(insert, readErrors, true).execute(state)) {
                    throw new IgnoreMeException();
                }
            }

            String predicate = pickPredicate(r, scenario, corpus);

            List<String> keysDefault = keys(table, predicate, "");
            List<String> keysScan = keys(table, predicate, " SETTINGS use_skip_indexes = 0");

            if (!keysDefault.equals(keysScan)) {
                throw new AssertionError(String.format(
                        "text-index direct-read divergence (likely ClickHouse#107186): scenario %s, index %s, "
                                + "predicate %s. default (direct_read=1) keys %s vs use_skip_indexes=0 keys %s. DDL: %s",
                        scenario, scenario.indexType, predicate, truncate(keysDefault), truncate(keysScan), create));
            }
        } finally {
            dropQuietly(table);
        }
    }

    private static List<String> buildCorpus(Randomly r, Scenario scenario) {
        List<String> rows = new ArrayList<>();
        int n = 20 + r.getInteger(0, 21);
        if (scenario == Scenario.ASCII_CJK) {
            for (int i = 0; i < n; i++) {
                if (r.getInteger(0, 3) == 0) {
                    rows.add(ClickHouseTextIndexLikeOracle.TOKEN_VOCABULARY
                            .get(r.getInteger(0, ClickHouseTextIndexLikeOracle.TOKEN_VOCABULARY.size())));
                } else {
                    rows.add(CJK_DOCS.get(r.getInteger(0, CJK_DOCS.size())));
                }
            }
            return rows;
        }
        for (int i = 0; i < n; i++) {
            int words = 1 + r.getInteger(0, 3);
            StringBuilder sb = new StringBuilder();
            String sep = scenario == Scenario.SPLIT_BY_STRING ? "-" : " ";
            for (int w = 0; w < words; w++) {
                if (w > 0) {
                    sb.append(sep);
                }
                String word = ClickHouseTextIndexLikeOracle.TOKEN_VOCABULARY
                        .get(r.getInteger(0, ClickHouseTextIndexLikeOracle.TOKEN_VOCABULARY.size()));
                sb.append(scenario == Scenario.PREPROCESSOR_LOWER ? mixedCase(r, word) : word);
            }
            rows.add(sb.toString());
        }
        return rows;
    }

    private static String pickPredicate(Randomly r, Scenario scenario, List<String> corpus) {
        List<String> vocab = ClickHouseTextIndexLikeOracle.TOKEN_VOCABULARY;
        switch (scenario) {
        case ASCII_CJK: {
            String doc = CJK_DOCS.get(r.getInteger(0, CJK_DOCS.size()));
            String needle = doc.length() > 2 ? doc.substring(2) : doc;
            return "hasToken(s, '" + esc(needle) + "')";
        }
        case ARRAY:
            return "hasToken(s, '" + esc(vocab.get(r.getInteger(0, vocab.size()))) + "')";
        case NGRAMS:
        case SPARSEGRAMS: {
            String word = pickLong(r, vocab);
            String fragment = word.substring(1);
            return "hasToken(s, '" + esc(fragment) + "')";
        }
        case SPLIT_BY_STRING:
            return "hasToken(s, '" + esc(vocab.get(r.getInteger(0, vocab.size()))) + "')";
        case PREPROCESSOR_LOWER:
            return "hasToken(s, '" + esc(vocab.get(r.getInteger(0, vocab.size())).toLowerCase(Locale.ROOT)) + "')";
        default:
            break;
        }
        String w1 = esc(vocab.get(r.getInteger(0, vocab.size())));
        String w2 = esc(vocab.get(r.getInteger(0, vocab.size())));
        switch (r.getInteger(0, 3)) {
        case 0:
            return "hasAllTokens(s, '" + w1 + " " + w2 + "')";
        case 1:
            return "hasAnyTokens(s, '" + w1 + " " + w2 + "')";
        default:
            return "hasToken(s, '" + w1 + "')";
        }
    }

    private static String pickLong(Randomly r, List<String> vocab) {
        List<String> longWords = vocab.stream().filter(w -> w.length() >= 5).toList();
        return longWords.get(r.getInteger(0, longWords.size()));
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
            sb.append('(').append(startKey + i).append(", '").append(esc(rows.get(i))).append("')");
        }
        return sb.toString();
    }

    private List<String> keys(String table, String predicate, String suffix) throws SQLException {
        return ComparatorHelper.getResultSetFirstColumnAsString(
                "SELECT toString(k) FROM " + table + " WHERE " + predicate + " ORDER BY k" + suffix, readErrors, state);
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
