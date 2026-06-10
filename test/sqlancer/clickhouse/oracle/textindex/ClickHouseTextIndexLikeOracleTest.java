package sqlancer.clickhouse.oracle.textindex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.junit.jupiter.api.Test;

import sqlancer.Randomly;
import sqlancer.clickhouse.oracle.textindex.ClickHouseTextIndexLikeOracle.Arm;
import sqlancer.clickhouse.oracle.textindex.ClickHouseTextIndexLikeOracle.LikePattern;
import sqlancer.clickhouse.oracle.textindex.ClickHouseTextIndexLikeOracle.PatternKind;

class ClickHouseTextIndexLikeOracleTest {

    @Test
    void vocabularyTokensAreIndexEligible() {
        // Every token must clear text_index_like_min_pattern_length = 4 and survive the
        // splitByNonAlpha tokenizer as ONE token (alphanumeric only -- a separator inside a token
        // would silently change what the index dictionary contains).
        assertEquals(16, ClickHouseTextIndexLikeOracle.TOKEN_VOCABULARY.size());
        for (String token : ClickHouseTextIndexLikeOracle.TOKEN_VOCABULARY) {
            assertTrue(token.length() >= 4, () -> token + " is shorter than the min pattern length");
            assertTrue(token.chars().allMatch(c -> Character.isLetterOrDigit((char) c)),
                    () -> token + " is not purely alphanumeric");
            assertEquals(token.toLowerCase(Locale.ROOT), token,
                    () -> token + " must be lowercase (ILIKE case-flip relies on it)");
        }
    }

    @Test
    void escapeStringLiteralHandlesQuotesAndBackslashes() {
        assertEquals("alpha", ClickHouseTextIndexLikeOracle.escapeStringLiteral("alpha"));
        assertEquals("a\\'b", ClickHouseTextIndexLikeOracle.escapeStringLiteral("a'b"));
        assertEquals("a\\\\b", ClickHouseTextIndexLikeOracle.escapeStringLiteral("a\\b"));
        // Backslash-then-quote: the backslash is escaped first, so the quote's own escape survives.
        assertEquals("\\\\\\'", ClickHouseTextIndexLikeOracle.escapeStringLiteral("\\'"));
    }

    @Test
    void groundTruthComputabilityIsStructural() {
        assertTrue(ClickHouseTextIndexLikeOracle.isGroundTruthComputable("%alpha%"));
        assertTrue(ClickHouseTextIndexLikeOracle.isGroundTruthComputable("%ab%"), "sub-4-char is still containment");
        assertTrue(ClickHouseTextIndexLikeOracle.isGroundTruthComputable("%va char%"),
                "boundary fragment with space is still containment");
        // `_` wildcards are agreement-only by design: their semantics are not modelled in Java.
        assertFalse(ClickHouseTextIndexLikeOracle.isGroundTruthComputable("%alp_a%"));
        // Inner % / escapes / non-%...%-shaped patterns have no Java model either.
        assertFalse(ClickHouseTextIndexLikeOracle.isGroundTruthComputable("%al%pha%"));
        assertFalse(ClickHouseTextIndexLikeOracle.isGroundTruthComputable("%al\\pha%"));
        assertFalse(ClickHouseTextIndexLikeOracle.isGroundTruthComputable("alpha%"));
        assertFalse(ClickHouseTextIndexLikeOracle.isGroundTruthComputable("%alpha"));
        assertFalse(ClickHouseTextIndexLikeOracle.isGroundTruthComputable("%%"));
    }

    @Test
    void expectedMatchesFollowJavaContains() {
        List<String> corpus = List.of("alpha bravo", "bravo charlie", "delta echo4 alpha", "mike");
        assertEquals(2, ClickHouseTextIndexLikeOracle.computeExpectedMatches(corpus, "%alpha%", false));
        assertEquals(2, ClickHouseTextIndexLikeOracle.computeExpectedMatches(corpus, "%bravo%", false));
        assertEquals(0, ClickHouseTextIndexLikeOracle.computeExpectedMatches(corpus, "%november%", false));
        // Boundary-spanning fragment: containment over the full row string, not per-token
        // ("bravo charlie" contains "vo char" across the space).
        assertEquals(1, ClickHouseTextIndexLikeOracle.computeExpectedMatches(corpus, "%vo char%", false));
        // Sub-4-char fragment: "al" appears only inside "alpha" (rows 1 and 3).
        assertEquals(2, ClickHouseTextIndexLikeOracle.computeExpectedMatches(corpus, "%al%", false));
    }

    @Test
    void expectedMatchesIlikeIsCaseInsensitiveContains() {
        List<String> corpus = List.of("alpha bravo", "bravo charlie", "mike");
        assertEquals(0, ClickHouseTextIndexLikeOracle.computeExpectedMatches(corpus, "%ALPHA%", false),
                "case-sensitive LIKE must not match the lowercase corpus");
        assertEquals(1, ClickHouseTextIndexLikeOracle.computeExpectedMatches(corpus, "%ALPHA%", true));
        assertEquals(2, ClickHouseTextIndexLikeOracle.computeExpectedMatches(corpus, "%BrAvO%", true));
    }

    @Test
    void caseFlippedTokenIsIlikeAndGroundTruthComputable() {
        LikePattern p = ClickHouseTextIndexLikeOracle.caseFlippedToken("alpha");
        assertEquals("%ALPHA%", p.getPattern());
        assertTrue(p.isIlike());
        assertTrue(p.isGroundTruthComputable());
        assertEquals(1, ClickHouseTextIndexLikeOracle.computeExpectedMatches(List.of("alpha bravo"), p.getPattern(),
                p.isIlike()));
    }

    @Test
    void boundarySpanFragmentIsSubstringOfRowAndSpansTheSpace() {
        for (int i = 0; i < 200; i++) {
            Randomly r = new Randomly(211L + i);
            String row = "alpha bravo charlie";
            LikePattern p = ClickHouseTextIndexLikeOracle.boundarySpanFragment(r, row);
            String body = p.getPattern().substring(1, p.getPattern().length() - 1);
            assertTrue(row.contains(body), () -> body + " is not a substring of " + row);
            assertTrue(body.contains(" "), () -> body + " does not span a token boundary");
            assertTrue(body.length() >= 4, () -> body + " is below the index-eligible pattern length");
            assertTrue(p.isGroundTruthComputable());
            assertFalse(p.isIlike());
        }
    }

    @Test
    void midTokenSubstringIsInsideTokenAndIndexEligible() {
        for (int i = 0; i < 200; i++) {
            Randomly r = new Randomly(307L + i);
            String token = "november";
            LikePattern p = ClickHouseTextIndexLikeOracle.midTokenSubstring(r, token);
            String body = p.getPattern().substring(1, p.getPattern().length() - 1);
            assertTrue(token.contains(body), () -> body + " is not a substring of " + token);
            assertTrue(body.length() >= 4 && body.length() < token.length(),
                    () -> body + " must be a proper fragment of length >= 4");
            assertTrue(p.isGroundTruthComputable());
        }
    }

    @Test
    void shortFragmentIsBelowMinPatternLengthButStillComputable() {
        for (int i = 0; i < 200; i++) {
            Randomly r = new Randomly(401L + i);
            LikePattern p = ClickHouseTextIndexLikeOracle.shortFragment(r, "charlie");
            String body = p.getPattern().substring(1, p.getPattern().length() - 1);
            assertTrue(body.length() >= 2 && body.length() <= 3, () -> body + " must be 2-3 chars (fallback path)");
            assertTrue("charlie".contains(body));
            assertTrue(p.isGroundTruthComputable(), "containment ground truth survives the fallback path");
        }
    }

    @Test
    void underscorePatternIsAgreementOnly() {
        for (int i = 0; i < 200; i++) {
            Randomly r = new Randomly(503L + i);
            LikePattern p = ClickHouseTextIndexLikeOracle.underscorePattern(r, "juliet");
            assertEquals(PatternKind.UNDERSCORE_WILDCARD, p.getKind());
            assertTrue(p.getPattern().contains("_"));
            assertFalse(p.isGroundTruthComputable(), "`_` patterns must never claim a Java ground truth");
            // Same length as the original token: exactly one char was replaced, none inserted.
            assertEquals("juliet".length() + 2, p.getPattern().length());
        }
    }

    @Test
    void renderedDdlCarriesTextIndexClause() {
        String ddl = ClickHouseTextIndexLikeOracle.renderCreateTable("db.txtidx_1_t", false, 6);
        assertTrue(ddl.contains("INDEX tidx (s) TYPE text(tokenizer = 'splitByNonAlpha') GRANULARITY 1"), ddl);
        assertTrue(ddl.contains("ENGINE = MergeTree ORDER BY k"), ddl);
        assertTrue(ddl.contains("SETTINGS index_granularity = 6"), ddl);

        String ngrams = ClickHouseTextIndexLikeOracle.renderCreateTable("db.txtidx_2_t", true, 4);
        assertTrue(ngrams.contains("TYPE text(tokenizer = 'ngrams', ngram_size = 3)"), ngrams);
    }

    @Test
    void armSettingsSuffixesAreCorrectlyAttached() {
        LikePattern p = ClickHouseTextIndexLikeOracle.patternFromToken("alpha");
        String def = ClickHouseTextIndexLikeOracle.renderCountQuery("db.t", p, Arm.DEFAULT);
        String ignored = ClickHouseTextIndexLikeOracle.renderCountQuery("db.t", p, Arm.INDEX_IGNORED);
        String flipped = ClickHouseTextIndexLikeOracle.renderCountQuery("db.t", p, Arm.DICTIONARY_SCAN_FLIPPED);

        assertEquals("SELECT toString(count()) FROM db.t WHERE s LIKE '%alpha%'", def);
        assertFalse(def.contains("SETTINGS"), "default arm must carry no settings");
        assertTrue(ignored.endsWith(" SETTINGS ignore_data_skipping_indices = 'tidx'"), ignored);
        assertTrue(flipped.endsWith(" SETTINGS use_text_index_like_evaluation_by_dictionary_scan = 0"), flipped);
    }

    @Test
    void keysQueryOrdersBeforeSettingsAndUsesIlikeOperator() {
        LikePattern like = ClickHouseTextIndexLikeOracle.patternFromToken("alpha");
        String keys = ClickHouseTextIndexLikeOracle.renderKeysQuery("db.t", like, Arm.INDEX_IGNORED);
        assertEquals("SELECT toString(k) FROM db.t WHERE s LIKE '%alpha%' ORDER BY k"
                + " SETTINGS ignore_data_skipping_indices = 'tidx'", keys);

        LikePattern ilike = ClickHouseTextIndexLikeOracle.caseFlippedToken("alpha");
        String ilikeKeys = ClickHouseTextIndexLikeOracle.renderKeysQuery("db.t", ilike, Arm.DEFAULT);
        assertEquals("SELECT toString(k) FROM db.t WHERE s ILIKE '%ALPHA%' ORDER BY k", ilikeKeys);
    }

    @Test
    void corpusRowsAreTwoToFourVocabularyTokensJoinedBySingleSpaces() {
        Randomly r = new Randomly(617L);
        List<String> corpus = ClickHouseTextIndexLikeOracle.buildCorpus(r, 100,
                ClickHouseTextIndexLikeOracle.TOKEN_VOCABULARY);
        assertEquals(100, corpus.size());
        for (String row : corpus) {
            String[] tokens = row.split(" ");
            assertTrue(tokens.length >= 2 && tokens.length <= 4, () -> row + " must have 2-4 tokens");
            for (String token : tokens) {
                assertTrue(ClickHouseTextIndexLikeOracle.TOKEN_VOCABULARY.contains(token),
                        () -> token + " is not in the vocabulary");
            }
            assertFalse(row.contains("  "), () -> row + " must join tokens by single spaces");
        }
    }

    @Test
    void insertBlockRendersSequentialKeysAndEscapedValues() {
        String insert = ClickHouseTextIndexLikeOracle.renderInsertBlock("db.t", 5, List.of("alpha bravo", "mike"));
        assertEquals("INSERT INTO db.t (k, s) VALUES (5, 'alpha bravo'), (6, 'mike')", insert);
    }

    @Test
    void generatePatternCoversAllKindsAcrossSeeds() {
        Randomly seedSetter = new Randomly(731L);
        List<String> corpus = ClickHouseTextIndexLikeOracle.buildCorpus(seedSetter, 20,
                ClickHouseTextIndexLikeOracle.TOKEN_VOCABULARY);
        Set<String> seenKinds = new HashSet<>();
        for (int i = 0; i < 400; i++) {
            Randomly r = new Randomly(829L + i);
            LikePattern p = ClickHouseTextIndexLikeOracle.generatePattern(r, corpus,
                    ClickHouseTextIndexLikeOracle.TOKEN_VOCABULARY);
            seenKinds.add(p.getKind().name());
            // ILIKE is exactly the case-flip arm; every other kind compares LIKE.
            assertEquals(p.getKind() == PatternKind.ILIKE_CASE_FLIP, p.isIlike());
            // Computability is structural and kind-consistent: only `_` patterns lack ground truth.
            assertEquals(p.getKind() != PatternKind.UNDERSCORE_WILDCARD, p.isGroundTruthComputable(),
                    () -> p.getKind() + " computability wrong for " + p.getPattern());
        }
        for (PatternKind kind : PatternKind.values()) {
            assertTrue(seenKinds.contains(kind.name()), () -> kind + " not picked across 400 attempts; saw "
                    + seenKinds);
        }
    }

    @Test
    void generatePatternFallsBackToTokenOnEmptyCorpus() {
        // The empty-table edge: BOUNDARY_SPAN needs a real row, so an empty corpus must never
        // yield it (or throw).
        for (int i = 0; i < 400; i++) {
            Randomly r = new Randomly(911L + i);
            LikePattern p = ClickHouseTextIndexLikeOracle.generatePattern(r, List.of(),
                    ClickHouseTextIndexLikeOracle.TOKEN_VOCABULARY);
            assertFalse(p.getKind() == PatternKind.BOUNDARY_SPAN, "empty corpus cannot span a boundary");
        }
    }

    @Test
    void firstTokenOfStripsAtFirstSpace() {
        assertEquals("alpha", ClickHouseTextIndexLikeOracle.firstTokenOf("alpha bravo charlie"));
        assertEquals("mike", ClickHouseTextIndexLikeOracle.firstTokenOf("mike"));
    }
}
