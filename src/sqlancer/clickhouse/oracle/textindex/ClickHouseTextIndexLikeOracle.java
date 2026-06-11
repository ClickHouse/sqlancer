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

public class ClickHouseTextIndexLikeOracle implements TestOracle<ClickHouseGlobalState> {

    private static final AtomicLong TXTIDX_COUNTER = new AtomicLong();

    private static final AtomicLong INDEX_ENGAGED = new AtomicLong();

    private static final long PROBE_EVERY = 10;

    static final String INDEX_NAME = "tidx";

    static final List<String> TOKEN_VOCABULARY = List.of("alpha", "bravo", "charlie", "delta", "echo4", "foxtrot",
            "golf42", "hotel", "india", "juliet", "kilo9", "lima77", "mike", "november", "oscar", "papa8");

    enum PatternKind {
        TOKEN,
        MID_TOKEN_SUBSTRING,
        BOUNDARY_SPAN,
        ILIKE_CASE_FLIP,
        SHORT_FRAGMENT,
        UNDERSCORE_WILDCARD
    }

    enum Arm {
        DEFAULT(""),
        INDEX_IGNORED(" SETTINGS ignore_data_skipping_indices = '" + INDEX_NAME + "'"),
        DICTIONARY_SCAN_FLIPPED(" SETTINGS use_text_index_like_evaluation_by_dictionary_scan = 0");

        private final String settingsSuffix;

        Arm(String settingsSuffix) {
            this.settingsSuffix = settingsSuffix;
        }

        String getSettingsSuffix() {
            return settingsSuffix;
        }
    }

    static final class LikePattern {
        private final String pattern;
        private final boolean ilike;
        private final PatternKind kind;

        LikePattern(String pattern, boolean ilike, PatternKind kind) {
            this.pattern = pattern;
            this.ilike = ilike;
            this.kind = kind;
        }

        String getPattern() {
            return pattern;
        }

        boolean isIlike() {
            return ilike;
        }

        PatternKind getKind() {
            return kind;
        }

        boolean isGroundTruthComputable() {
            return ClickHouseTextIndexLikeOracle.isGroundTruthComputable(pattern);
        }
    }

    private final ClickHouseGlobalState state;

    private final ExpectedErrors readErrors = new ExpectedErrors();
    private final ExpectedErrors probeErrors = new ExpectedErrors();

    public ClickHouseTextIndexLikeOracle(ClickHouseGlobalState state) {
        this.state = state;
        for (ExpectedErrors e : List.of(readErrors, probeErrors)) {

            ClickHouseErrors.addSessionSettingsErrors(e);

            e.add("Unknown Index type");
            e.add("Unknown index type");

            e.add("Unknown tokenizer");

            e.add("Unexpected text index arguments");

            e.add("full-text index");
            e.add("full_text_index");
            e.add("SUPPORT_IS_DISABLED");

            e.add("UNKNOWN_TABLE");
            e.add("Unknown table expression identifier");

            e.add("(MEMORY_LIMIT_EXCEEDED)");
            e.add("memory limit exceeded");

            e.add("TIMEOUT_EXCEEDED");
            e.add("Timeout exceeded");
        }

        probeErrors.add("INDEX_NOT_USED");
    }

    @Override
    public void check() throws SQLException {
        long id = TXTIDX_COUNTER.incrementAndGet();
        String table = state.getDatabaseName() + ".txtidx_" + id + "_t";
        Randomly r = state.getRandomly();

        boolean ngramsTokenizer = r.getInteger(0, 4) == 0;
        int indexGranularity = 4 + r.getInteger(0, 5);
        String create = renderCreateTable(table, ngramsTokenizer, indexGranularity);

        boolean emptyTable = Randomly.getBooleanWithSmallProbability();
        List<String> corpus = new ArrayList<>();

        try {
            logStmt(create);
            if (!new SQLQueryAdapter(create, readErrors, true).execute(state)) {
                throw new IgnoreMeException();
            }
            if (!emptyTable) {

                int blocks = 3 + r.getInteger(0, 3);
                for (int b = 0; b < blocks; b++) {
                    int rows = 30 + r.getInteger(0, 51);
                    List<String> blockRows = buildCorpus(r, rows, TOKEN_VOCABULARY);
                    String insert = renderInsertBlock(table, corpus.size(), blockRows);
                    logStmt(insert);
                    if (!new SQLQueryAdapter(insert, readErrors, true).execute(state)) {
                        throw new IgnoreMeException();
                    }
                    corpus.addAll(blockRows);
                }
            }

            LikePattern pattern = generatePattern(r, corpus, TOKEN_VOCABULARY);

            Arm[] arms = Arm.values();
            String[] counts = new String[arms.length];
            List<List<String>> keyLists = new ArrayList<>(arms.length);
            for (int i = 0; i < arms.length; i++) {
                counts[i] = readSingleValue(renderCountQuery(table, pattern, arms[i]));
                keyLists.add(ComparatorHelper.getResultSetFirstColumnAsString(renderKeysQuery(table, pattern, arms[i]),
                        readErrors, state));
            }

            for (int i = 1; i < arms.length; i++) {
                if (!counts[0].equals(counts[i])) {
                    throw new AssertionError(String.format(
                            "text-index LIKE count mismatch: pattern %s (%s, kind %s): arm %s saw %s rows but arm %s "
                                    + "saw %s. DDL: %s",
                            pattern.getPattern(), pattern.isIlike() ? "ILIKE" : "LIKE", pattern.getKind(),
                            arms[0], counts[0], arms[i], counts[i], create));
                }
                if (!keyLists.get(0).equals(keyLists.get(i))) {
                    throw new AssertionError(String.format(
                            "text-index LIKE key-list mismatch: pattern %s (%s, kind %s): arm %s keys %s vs arm %s "
                                    + "keys %s. DDL: %s",
                            pattern.getPattern(), pattern.isIlike() ? "ILIKE" : "LIKE", pattern.getKind(), arms[0],
                            truncateForMessage(keyLists.get(0)), arms[i], truncateForMessage(keyLists.get(i)),
                            create));
                }
            }

            if (pattern.isGroundTruthComputable()) {
                long expected = computeExpectedMatches(corpus, pattern.getPattern(), pattern.isIlike());
                if (!String.valueOf(expected).equals(counts[0])) {
                    throw new AssertionError(String.format(
                            "text-index LIKE ground-truth mismatch: pattern %s (%s, kind %s): Java contains() over "
                                    + "the %d-row corpus expects %d matches but all arms agree on %s. DDL: %s",
                            pattern.getPattern(), pattern.isIlike() ? "ILIKE" : "LIKE", pattern.getKind(),
                            corpus.size(), expected, counts[0], create));
                }
            }

            if (id % PROBE_EVERY == 0 && !corpus.isEmpty()) {
                String knownToken = firstTokenOf(corpus.get(0));
                String probe = "SELECT toString(count()) FROM " + table + " WHERE s LIKE '%"
                        + escapeStringLiteral(knownToken) + "%' SETTINGS force_data_skipping_indices = '" + INDEX_NAME
                        + "'";
                List<String> probeResult = ComparatorHelper.getResultSetFirstColumnAsString(probe, probeErrors, state);
                if (probeResult.size() == 1) {
                    INDEX_ENGAGED.incrementAndGet();
                }
            }
        } finally {
            try {
                new SQLQueryAdapter("DROP TABLE IF EXISTS " + table, readErrors, true).execute(state);
            } catch (Exception | AssertionError ignored) {

            }
        }
    }

    static long getIndexEngagedCount() {
        return INDEX_ENGAGED.get();
    }

    static String escapeStringLiteral(String s) {
        return s.replace("\\", "\\\\").replace("'", "\\'");
    }

    static boolean isGroundTruthComputable(String pattern) {
        if (pattern.length() < 3 || !pattern.startsWith("%") || !pattern.endsWith("%")) {
            return false;
        }
        String body = pattern.substring(1, pattern.length() - 1);
        return !body.contains("%") && !body.contains("_") && !body.contains("\\");
    }

    static long computeExpectedMatches(List<String> corpus, String pattern, boolean caseInsensitive) {
        if (!isGroundTruthComputable(pattern)) {
            throw new IllegalArgumentException("pattern has no Java ground truth: " + pattern);
        }
        String body = pattern.substring(1, pattern.length() - 1);
        String needle = caseInsensitive ? body.toLowerCase(Locale.ROOT) : body;
        return corpus.stream().filter(row -> (caseInsensitive ? row.toLowerCase(Locale.ROOT) : row).contains(needle))
                .count();
    }

    static List<String> buildCorpus(Randomly r, int rowCount, List<String> vocabulary) {
        List<String> rows = new ArrayList<>(rowCount);
        for (int i = 0; i < rowCount; i++) {
            int tokens = 2 + r.getInteger(0, 3);
            StringBuilder sb = new StringBuilder();
            for (int t = 0; t < tokens; t++) {
                if (t > 0) {
                    sb.append(' ');
                }
                sb.append(vocabulary.get(r.getInteger(0, vocabulary.size())));
            }
            rows.add(sb.toString());
        }
        return rows;
    }

    static String renderCreateTable(String table, boolean ngramsTokenizer, int indexGranularity) {

        String indexType = ngramsTokenizer ? "text(tokenizer = ngrams(3))" : "text(tokenizer = 'splitByNonAlpha')";
        return "CREATE TABLE " + table + " (k UInt32, s String, INDEX " + INDEX_NAME + " (s) TYPE " + indexType
                + " GRANULARITY 1) ENGINE = MergeTree ORDER BY k SETTINGS index_granularity = " + indexGranularity;
    }

    static String renderInsertBlock(String table, int startKey, List<String> rows) {
        StringBuilder sb = new StringBuilder("INSERT INTO ").append(table).append(" (k, s) VALUES ");
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append('(').append(startKey + i).append(", '").append(escapeStringLiteral(rows.get(i))).append("')");
        }
        return sb.toString();
    }

    static String renderCountQuery(String table, LikePattern pattern, Arm arm) {
        return "SELECT toString(count()) FROM " + table + " WHERE s " + (pattern.isIlike() ? "ILIKE" : "LIKE") + " '"
                + escapeStringLiteral(pattern.getPattern()) + "'" + arm.getSettingsSuffix();
    }

    static String renderKeysQuery(String table, LikePattern pattern, Arm arm) {
        return "SELECT toString(k) FROM " + table + " WHERE s " + (pattern.isIlike() ? "ILIKE" : "LIKE") + " '"
                + escapeStringLiteral(pattern.getPattern()) + "' ORDER BY k" + arm.getSettingsSuffix();
    }

    static LikePattern generatePattern(Randomly r, List<String> corpus, List<String> vocabulary) {
        PatternKind kind = PatternKind.values()[r.getInteger(0, PatternKind.values().length)];
        if (kind == PatternKind.BOUNDARY_SPAN && corpus.isEmpty()) {
            kind = PatternKind.TOKEN;
        }
        String token = vocabulary.get(r.getInteger(0, vocabulary.size()));
        switch (kind) {
        case TOKEN:
            return patternFromToken(token);
        case MID_TOKEN_SUBSTRING:
            return midTokenSubstring(r, pickLongToken(r, vocabulary));
        case BOUNDARY_SPAN:
            return boundarySpanFragment(r, corpus.get(r.getInteger(0, corpus.size())));
        case ILIKE_CASE_FLIP:
            return caseFlippedToken(token);
        case SHORT_FRAGMENT:
            return shortFragment(r, token);
        case UNDERSCORE_WILDCARD:
            return underscorePattern(r, token);
        default:
            throw new AssertionError(kind);
        }
    }

    static LikePattern patternFromToken(String token) {
        return new LikePattern("%" + token + "%", false, PatternKind.TOKEN);
    }

    static LikePattern midTokenSubstring(Randomly r, String token) {
        if (token.length() < 5) {
            throw new IllegalArgumentException("mid-token substring needs a token of length >= 5: " + token);
        }
        int len = r.getInteger(4, token.length());
        int start = r.getInteger(0, token.length() - len + 1);
        return new LikePattern("%" + token.substring(start, start + len) + "%", false,
                PatternKind.MID_TOKEN_SUBSTRING);
    }

    static LikePattern boundarySpanFragment(Randomly r, String rowString) {
        int space = rowString.indexOf(' ');
        if (space < 0) {
            throw new IllegalArgumentException("row has no token boundary: " + rowString);
        }
        int left = r.getInteger(1, 4);
        int right = r.getInteger(Math.max(1, 3 - left), 4);
        String fragment = rowString.substring(space - left, space + 1 + right);
        return new LikePattern("%" + fragment + "%", false, PatternKind.BOUNDARY_SPAN);
    }

    static LikePattern caseFlippedToken(String token) {
        return new LikePattern("%" + token.toUpperCase(Locale.ROOT) + "%", true, PatternKind.ILIKE_CASE_FLIP);
    }

    static LikePattern shortFragment(Randomly r, String token) {
        int len = 2 + r.getInteger(0, 2);
        int start = r.getInteger(0, token.length() - len + 1);
        return new LikePattern("%" + token.substring(start, start + len) + "%", false, PatternKind.SHORT_FRAGMENT);
    }

    static LikePattern underscorePattern(Randomly r, String token) {
        int pos = r.getInteger(0, token.length());
        String withWildcard = token.substring(0, pos) + "_" + token.substring(pos + 1);
        return new LikePattern("%" + withWildcard + "%", false, PatternKind.UNDERSCORE_WILDCARD);
    }

    static String firstTokenOf(String rowString) {
        int space = rowString.indexOf(' ');
        return space < 0 ? rowString : rowString.substring(0, space);
    }

    private static String truncateForMessage(List<String> keys) {
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

    private static String pickLongToken(Randomly r, List<String> vocabulary) {
        List<String> longTokens = vocabulary.stream().filter(t -> t.length() >= 5).toList();
        return longTokens.get(r.getInteger(0, longTokens.size()));
    }

    private void logStmt(String stmt) {
        if (state.getOptions().logEachSelect()) {
            state.getLogger().writeCurrent(stmt);
            state.getState().logStatement(stmt);
        }
    }
}
