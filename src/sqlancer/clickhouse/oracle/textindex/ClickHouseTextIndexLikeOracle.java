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

/**
 * Text-index LIKE/ILIKE oracle: exercises the 26.4 text-index acceleration of {@code LIKE} / {@code ILIKE}
 * (PR #98149 -- {@code splitByNonAlpha} tokenizer; 26.5 extended the tokenizer set). The bug class is the index
 * <b>dropping matching rows</b>: false-negative granule skipping or dictionary-scan misses are invisible to oracles
 * that compare two indexed paths against each other, so this oracle compares the indexed path against the same query
 * with the index disabled.
 *
 * <p>
 * Self-contained (Shape C, MutationAnalyzer template): each {@code check()} creates a private AtomicLong-suffixed
 * plain-MergeTree table {@code <db>.txtidx_<id>_t (k UInt32, s String)} with
 * {@code INDEX tidx (s) TYPE text(tokenizer = 'splitByNonAlpha') GRANULARITY 1} (occasionally the
 * {@code ngrams} tokenizer variant), a deliberately small {@code index_granularity} (4-8), and 3-5 separate INSERT
 * blocks of 30-80 rows so multiple granules and parts exist and granule skipping is actually observable. Rows are
 * built from a fixed 16-token alphanumeric vocabulary (every token length &gt;= 4 so token patterns clear
 * {@code text_index_like_min_pattern_length = 4}); the ground-truth row strings are kept in a Java list so the oracle
 * can compute the expected match count itself wherever the pattern's semantics are unambiguous.
 *
 * <p>
 * Per iteration ONE pattern is drawn from an adversarial pool: {@code %token%}; a mid-token substring (len &gt;= 4); a
 * fragment spanning a token boundary including the space (LIKE is substring semantics -- the tokenizer is not -- so
 * the dictionary scan must still match these); a case-flipped token used with ILIKE; a sub-4-char fragment (must
 * transparently fall back below the min pattern length); and a pattern containing a literal {@code _} wildcard
 * (agreement-only -- {@code _} semantics are deliberately NOT modelled in Java ground truth).
 *
 * <p>
 * Three arms, each issued as both {@code SELECT count()} and {@code SELECT k ... ORDER BY k}:
 * <ol>
 * <li>default settings (index eligible),</li>
 * <li>{@code SETTINGS ignore_data_skipping_indices = 'tidx'} (index off -- same data, same parts, no DDL churn),</li>
 * <li>{@code SETTINGS use_text_index_like_evaluation_by_dictionary_scan = 0} (the 26.4 dictionary-scan evaluation
 * path flipped off its default).</li>
 * </ol>
 * All arms must agree pairwise (string-compared count, positionally-compared ordered key lists -- both ends are
 * {@code ORDER BY k} over a non-nullable key, so no Java-side sorting ever happens). Where ground truth applies
 * (simple {@code %fragment%} containment), the count must also equal the Java-computed expectation.
 *
 * <p>
 * <b>Vacuity guard:</b> the index silently never engaging (pattern length, tokenizer mismatch, granule layout) would
 * make every comparison trivially pass. Every ~10th iteration runs ONE extra known-token count query with
 * {@code SETTINGS force_data_skipping_indices = 'tidx'}: per correction #2 of the 26.x plan, that setting does not
 * force anything -- it <b>fails the query with INDEX_NOT_USED</b> when the named index did not participate. A failed
 * probe is "feature not engaged" (IgnoreMe, NOT a finding, engagement counter untouched); a successful probe bumps a
 * lifetime engagement counter ({@link #getIndexEngagedCount()}) that the convergence run inspects. The
 * {@code INDEX_NOT_USED} tolerance lives ONLY on the probe's ExpectedErrors -- never on the three comparison arms and
 * never globally -- because anywhere else it is exactly the bug-shaped signal this oracle exists to catch (an arm
 * unexpectedly erroring is a finding, and a global tolerance would blind every other oracle to it).
 *
 * <p>
 * Error tolerance mirrors MutationAnalyzer's layering: one narrow read set for CREATE/INSERT/SELECT/DROP (session
 * settings on older builds, text-index DDL gating on pre-26.4 builds, drop/recreate races, load-shed memory/timeout),
 * plus the probe-only INDEX_NOT_USED set. A tolerated CREATE/INSERT failure abandons the iteration via
 * {@link IgnoreMeException}; tables are dropped in {@code finally} catching {@code Exception | AssertionError}.
 */
public class ClickHouseTextIndexLikeOracle implements TestOracle<ClickHouseGlobalState> {

    private static final AtomicLong TXTIDX_COUNTER = new AtomicLong();
    // Lifetime count of successful force_data_skipping_indices probes: proves the text index
    // actually participates in granule filtering at least sometimes (anti-vacuity evidence for the
    // convergence run). Deliberately NOT incremented when the probe fails with INDEX_NOT_USED.
    private static final AtomicLong INDEX_ENGAGED = new AtomicLong();
    // Run the vacuity probe on every PROBE_EVERY-th iteration, not per pattern (plan Unit 1).
    private static final long PROBE_EVERY = 10;

    static final String INDEX_NAME = "tidx";

    // Fixed token vocabulary. All tokens alphanumeric (splitByNonAlpha keeps digits inside a
    // token) and every token length >= 4 so a bare %token% pattern clears the default
    // text_index_like_min_pattern_length = 4 and is index-eligible.
    static final List<String> TOKEN_VOCABULARY = List.of("alpha", "bravo", "charlie", "delta", "echo4", "foxtrot",
            "golf42", "hotel", "india", "juliet", "kilo9", "lima77", "mike", "november", "oscar", "papa8");

    enum PatternKind {
        TOKEN, // %token% straight from the vocabulary
        MID_TOKEN_SUBSTRING, // %ovembe%-style fragment strictly inside one token, len >= 4
        BOUNDARY_SPAN, // fragment crossing a token boundary, includes the space
        ILIKE_CASE_FLIP, // upper-cased token, issued with ILIKE
        SHORT_FRAGMENT, // 2-3 char fragment: below min pattern length, must fall back
        UNDERSCORE_WILDCARD // token with one char replaced by literal _: agreement-only
    }

    enum Arm {
        DEFAULT(""), // (a) index eligible
        INDEX_IGNORED(" SETTINGS ignore_data_skipping_indices = '" + INDEX_NAME + "'"), // (b)
        DICTIONARY_SCAN_FLIPPED(" SETTINGS use_text_index_like_evaluation_by_dictionary_scan = 0"); // (c)

        private final String settingsSuffix;

        Arm(String settingsSuffix) {
            this.settingsSuffix = settingsSuffix;
        }

        String getSettingsSuffix() {
            return settingsSuffix;
        }
    }

    /**
     * One generated LIKE/ILIKE pattern. Ground-truth computability is derived structurally from the pattern text
     * ({@link #isGroundTruthComputable(String)}), never hand-set, so a future pattern kind cannot accidentally claim
     * a ground truth it does not have.
     */
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
    // Narrow tolerance, MutationAnalyzer layering:
    //   readErrors  -- CREATE / INSERT / SELECT / DROP. Every statement here is hand-built static
    //                  SQL over two columns, so generic analyzer noise ("Missing columns", ...)
    //                  can only mean a server bug and must surface; no global expression list.
    //   probeErrors -- the force_data_skipping_indices vacuity probe ONLY: readErrors plus
    //                  INDEX_NOT_USED. Keeping INDEX_NOT_USED off the read path means an arm that
    //                  unexpectedly trips it still writes a reproducer.
    private final ExpectedErrors readErrors = new ExpectedErrors();
    private final ExpectedErrors probeErrors = new ExpectedErrors();

    public ClickHouseTextIndexLikeOracle(ClickHouseGlobalState state) {
        this.state = state;
        for (ExpectedErrors e : List.of(readErrors, probeErrors)) {
            // Unknown setting names on older builds: keeps arm (c)'s 26.4-only
            // use_text_index_like_evaluation_by_dictionary_scan toggle (and the probe's
            // force_data_skipping_indices spelling drift, if any) runnable against pre-26.4
            // images -- the iteration degrades to IgnoreMe instead of a false reproducer.
            ClickHouseErrors.addSessionSettingsErrors(e);
            // Text-index DDL gating on older builds, each string narrow and message-anchored:
            // - "Unknown Index type": MergeTreeIndexFactory's rejection on builds that predate the
            //   `text` index type entirely (probe-pending against head; dev-vm smoke confirms the
            //   exact casing -- both casings kept until then).
            e.add("Unknown Index type");
            e.add("Unknown index type");
            // - "Unknown tokenizer": the text-index argument parser's rejection when the tokenizer
            //   name / named-arg grammar differs on the running build (the exact `tokenizer = ...`
            //   grammar is probe-pending against head, see renderSkipIndex).
            e.add("Unknown tokenizer");
            // - "Unexpected text index arguments": the argument-validation rejection for grammar
            //   drift (the 2026-06-10 smoke hit exactly this with the named-arg ngram_size form;
            //   the grammar is fixed to the probed `ngrams(3)` form, the tolerance stays as the
            //   belt so a future head-side grammar change degrades to IgnoreMe, not worker death).
            e.add("Unexpected text index arguments");
            // - experimental/beta gating family: enable_full_text_index defaults to true on head,
            //   but older 25.x/26.x builds gate the type behind an experimental flag and reject the
            //   CREATE with a SUPPORT_IS_DISABLED-class message naming the full-text index feature.
            e.add("full-text index");
            e.add("full_text_index");
            e.add("SUPPORT_IS_DISABLED");
            // Per-thread database drop/recreate race (MutationAnalyzer/MV precedent): reads hitting
            // a dropped namespace are not text-index bugs.
            e.add("UNKNOWN_TABLE");
            e.add("Unknown table expression identifier");
            // Code 241 load-shedding under the squeezed dev-vm container cap (-m=6g): any statement
            // -- including the finally-DROP -- can be rejected at the cgroup limit. Environment
            // artifact, not an index bug.
            e.add("(MEMORY_LIMIT_EXCEEDED)");
            e.add("memory limit exceeded");
            // Benign load-shed timeout on a squeezed server: unlike MutationAnalyzer's shape (b)
            // there is no deadlock class hiding behind a timeout here, so it is tolerated on every
            // statement rather than producing misleading SELECT reproducers.
            e.add("TIMEOUT_EXCEEDED");
            e.add("Timeout exceeded");
        }
        // Probe-only: force_data_skipping_indices FAILS the query with INDEX_NOT_USED when the
        // named index did not participate (plan correction #2). On the probe that means "feature
        // not engaged" (IgnoreMe); on any comparison arm it would be a finding, so it stays off
        // readErrors and off the global list.
        probeErrors.add("INDEX_NOT_USED");
    }

    @Override
    public void check() throws SQLException {
        long id = TXTIDX_COUNTER.incrementAndGet();
        String table = state.getDatabaseName() + ".txtidx_" + id + "_t";
        Randomly r = state.getRandomly();

        // Occasional ngrams-tokenizer variant; splitByNonAlpha is the PR #98149 LIKE-acceleration
        // tokenizer and stays the common case.
        boolean ngramsTokenizer = r.getInteger(0, 4) == 0;
        int indexGranularity = 4 + r.getInteger(0, 5); // 4..8: small so several granules exist
        String create = renderCreateTable(table, ngramsTokenizer, indexGranularity);

        // Small-probability empty-table edge: all arms must agree on 0 matches with zero parts.
        boolean emptyTable = Randomly.getBooleanWithSmallProbability();
        List<String> corpus = new ArrayList<>();

        try {
            logStmt(create);
            if (!new SQLQueryAdapter(create, readErrors, true).execute(state)) {
                throw new IgnoreMeException();
            }
            if (!emptyTable) {
                // 3-5 separate INSERT blocks of 30-80 rows each: multiple parts + multiple granules
                // per part (index_granularity 4-8), so granule skipping is observable.
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

            // Pairwise agreement: DEFAULT vs each other arm (transitively covers (b) vs (c)).
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

            // Ground truth: simple %fragment% containment is modelled in Java; mismatch against the
            // DEFAULT arm means even the agreed-on answer is wrong (all-arms-wrong bug class that a
            // pure differential cannot see).
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

            // Vacuity guard, every ~10th iteration: prove the index can engage at all. INDEX_NOT_USED
            // is tolerated on this probe only -> IgnoreMe without touching the engagement counter.
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
                // Best effort, MutationAnalyzer precedent: SQLQueryAdapter.execute() throws an
                // AssertionError (not an exception) on an untolerated error, and a DROP failure
                // must neither mask the real finding propagating out of the try block nor write a
                // misleading DROP reproducer. The disk-cleanup script reaps orphans.
            }
        }
    }

    /** Lifetime count of successful vacuity probes; the convergence run asserts this is > 0. */
    static long getIndexEngagedCount() {
        return INDEX_ENGAGED.get();
    }

    // --- static helpers, package-private for the DB-free unit tests ---

    // Escapes a fragment for embedding in a single-quoted ClickHouse string literal. Tokens are
    // alphanumeric so this is near-trivial, but the helper keeps the invariant explicit (and a
    // future vocabulary change cannot silently produce broken SQL). Backslash first, then quote.
    static String escapeStringLiteral(String s) {
        return s.replace("\\", "\\\\").replace("'", "\\'");
    }

    // Ground truth is computable iff the pattern is %<body>% where the body is wildcard-free:
    // plain substring containment, modelled exactly by Java String.contains. Patterns carrying
    // `_` (or inner `%` / `\`) are arms-agreement-only -- their semantics are deliberately not
    // re-implemented in Java.
    static boolean isGroundTruthComputable(String pattern) {
        if (pattern.length() < 3 || !pattern.startsWith("%") || !pattern.endsWith("%")) {
            return false;
        }
        String body = pattern.substring(1, pattern.length() - 1);
        return !body.contains("%") && !body.contains("_") && !body.contains("\\");
    }

    // Expected match count over the kept-in-Java corpus for a %fragment% pattern. ASCII-only
    // vocabulary, so Locale.ROOT lower-casing matches ClickHouse ILIKE's case folding.
    static long computeExpectedMatches(List<String> corpus, String pattern, boolean caseInsensitive) {
        if (!isGroundTruthComputable(pattern)) {
            throw new IllegalArgumentException("pattern has no Java ground truth: " + pattern);
        }
        String body = pattern.substring(1, pattern.length() - 1);
        String needle = caseInsensitive ? body.toLowerCase(Locale.ROOT) : body;
        return corpus.stream().filter(row -> (caseInsensitive ? row.toLowerCase(Locale.ROOT) : row).contains(needle))
                .count();
    }

    // Each row: 2-4 vocabulary tokens joined by single spaces.
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
        // Grammar probed against head 26.6.1.599 (2026-06-10): ngrams takes its size
        // function-style and unquoted (`tokenizer = ngrams(3)`); the named-arg `ngram_size = N`
        // form is rejected with BAD_ARGUMENTS. splitByNonAlpha accepts both quoted and unquoted.
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
            kind = PatternKind.TOKEN; // boundary fragments need a real row; empty-table edge
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

    // Fragment strictly inside one token, length >= 4 (index-eligible) but shorter than the token.
    // Requires token.length() >= 5.
    static LikePattern midTokenSubstring(Randomly r, String token) {
        if (token.length() < 5) {
            throw new IllegalArgumentException("mid-token substring needs a token of length >= 5: " + token);
        }
        int len = r.getInteger(4, token.length());
        int start = r.getInteger(0, token.length() - len + 1);
        return new LikePattern("%" + token.substring(start, start + len) + "%", false,
                PatternKind.MID_TOKEN_SUBSTRING);
    }

    // Fragment of an actual corpus row crossing a token boundary: suffix of one token + the space
    // + prefix of the next. LIKE is substring semantics over the whole string, so the tokenized
    // dictionary scan must still find these rows. Total length >= 4 (every token is >= 4 chars, so
    // the clamps below always hold).
    static LikePattern boundarySpanFragment(Randomly r, String rowString) {
        int space = rowString.indexOf(' ');
        if (space < 0) {
            throw new IllegalArgumentException("row has no token boundary: " + rowString);
        }
        int left = r.getInteger(1, 4); // 1-3 chars before the space
        int right = r.getInteger(Math.max(1, 3 - left), 4); // 1-3 after; left+1+right >= 4
        String fragment = rowString.substring(space - left, space + 1 + right);
        return new LikePattern("%" + fragment + "%", false, PatternKind.BOUNDARY_SPAN);
    }

    // Vocabulary is all-lowercase; the flipped pattern matches only case-insensitively.
    static LikePattern caseFlippedToken(String token) {
        return new LikePattern("%" + token.toUpperCase(Locale.ROOT) + "%", true, PatternKind.ILIKE_CASE_FLIP);
    }

    // 2-3 char fragment: below text_index_like_min_pattern_length = 4, the server must
    // transparently fall back to a non-indexed evaluation. Ground truth still applies (plain
    // containment).
    static LikePattern shortFragment(Randomly r, String token) {
        int len = 2 + r.getInteger(0, 2);
        int start = r.getInteger(0, token.length() - len + 1);
        return new LikePattern("%" + token.substring(start, start + len) + "%", false, PatternKind.SHORT_FRAGMENT);
    }

    // One token char replaced by a literal `_` wildcard. Agreement-only by construction:
    // isGroundTruthComputable() rejects `_`, so the arms-must-agree assertion is the only check.
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
