package sqlancer.clickhouse.oracle.jsonidx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import sqlancer.Randomly;
import sqlancer.clickhouse.gen.ClickHouseJsonDocumentGenerator;
import sqlancer.clickhouse.gen.ClickHouseJsonDocumentGenerator.Document;
import sqlancer.clickhouse.gen.ClickHouseJsonDocumentGenerator.Leaf;
import sqlancer.clickhouse.gen.ClickHouseJsonDocumentGenerator.LeafKind;
import sqlancer.clickhouse.oracle.jsonidx.ClickHouseJsonSkipIndexOracle.Arm;
import sqlancer.clickhouse.oracle.jsonidx.ClickHouseJsonSkipIndexOracle.IndexVariant;
import sqlancer.clickhouse.oracle.jsonidx.ClickHouseJsonSkipIndexOracle.JsonPredicate;
import sqlancer.clickhouse.oracle.jsonidx.ClickHouseJsonSkipIndexOracle.PredicateKind;

class ClickHouseJsonSkipIndexOracleTest {

    private static Document doc(Object... pathLeafPairs) {
        LinkedHashMap<String, Leaf> leaves = new LinkedHashMap<>();
        for (int i = 0; i < pathLeafPairs.length; i += 2) {
            leaves.put((String) pathLeafPairs[i], (Leaf) pathLeafPairs[i + 1]);
        }
        return new Document(leaves);
    }

    private static List<Document> handCorpus() {
        return List.of(
                doc("p_int", Leaf.ofInt(7), "p_str", Leaf.ofString("val1"), "u0", Leaf.ofInt(7)),
                doc("p_int", Leaf.ofInt(7), "p_str", Leaf.ofString("val2"), "u1", Leaf.ofString("w1")),
                doc("p_int", Leaf.ofInt(9), "p_str", Leaf.ofString("val1")), doc());
    }

    @Test
    void renderedCreateContainsTheChosenIndexVariant() {
        String table = "db.jsidx_1_t";
        String bloom = ClickHouseJsonSkipIndexOracle.renderCreateTable(table, IndexVariant.ALL_PATHS_BLOOM, 6);
        assertTrue(bloom.contains("(k UInt32, j JSON(p_int Int64, p_str String), "), bloom);
        assertTrue(bloom.contains("INDEX jx JSONAllPaths(j) TYPE bloom_filter(0.01) GRANULARITY 1"), bloom);
        assertTrue(bloom.contains("ENGINE = MergeTree ORDER BY k"), bloom);
        assertTrue(bloom.contains("SETTINGS index_granularity = 6"), bloom);

        assertTrue(ClickHouseJsonSkipIndexOracle.renderCreateTable(table, IndexVariant.ALL_PATHS_TOKENBF, 4)
                .contains("INDEX jx JSONAllPaths(j) TYPE tokenbf_v1(256, 2, 0) GRANULARITY 1"));
        assertTrue(ClickHouseJsonSkipIndexOracle.renderCreateTable(table, IndexVariant.ALL_PATHS_NGRAMBF, 4)
                .contains("INDEX jx JSONAllPaths(j) TYPE ngrambf_v1(3, 256, 2, 0) GRANULARITY 1"));
        assertTrue(ClickHouseJsonSkipIndexOracle.renderCreateTable(table, IndexVariant.ALL_PATHS_TEXT, 4)
                .contains("INDEX jx JSONAllPaths(j) TYPE text(tokenizer = 'splitByNonAlpha') GRANULARITY 1"));
        assertTrue(ClickHouseJsonSkipIndexOracle.renderCreateTable(table, IndexVariant.ALL_VALUES_TEXT, 4)
                .contains("INDEX jx JSONAllValues(j) TYPE text(tokenizer = 'splitByNonAlpha') GRANULARITY 1"));
    }

    @Test
    void insertBlockEmbedsRenderedJsonInSingleQuotedLiterals() {
        List<Document> docs = List.of(doc("p_int", Leaf.ofInt(1), "p_str", Leaf.ofString("val2")), doc());
        String insert = ClickHouseJsonSkipIndexOracle.renderInsertBlock("db.t", 5, docs);
        assertEquals("INSERT INTO db.t (k, j) VALUES (5, '{\"p_int\":1,\"p_str\":\"val2\"}'), (6, '{}')", insert);
    }

    @Test
    void typedPredicatesRenderSubcolumnAccessAndCarryExactCounts() {
        List<Document> corpus = handCorpus();

        JsonPredicate intEq = ClickHouseJsonSkipIndexOracle.typedIntEquals(7, corpus);
        assertEquals("j.p_int = 7", intEq.getWhereSql());
        assertTrue(intEq.isGroundTruthComputable());
        assertEquals(2, intEq.getExpectedCount());

        JsonPredicate strEq = ClickHouseJsonSkipIndexOracle.typedStrEquals("val1", corpus);
        assertEquals("j.p_str = 'val1'", strEq.getWhereSql());
        assertEquals(2, strEq.getExpectedCount());

        JsonPredicate in = ClickHouseJsonSkipIndexOracle.typedIntIn(List.of(7L, 999L), corpus);
        assertEquals("j.p_int IN (7, 999)", in.getWhereSql());
        assertEquals(2, in.getExpectedCount());

        JsonPredicate exists = ClickHouseJsonSkipIndexOracle.pathExists("u1", corpus);
        assertEquals("has(JSONAllPaths(j), 'u1')", exists.getWhereSql());
        assertEquals(1, exists.getExpectedCount());
        JsonPredicate phantom = ClickHouseJsonSkipIndexOracle
                .pathExists(ClickHouseJsonDocumentGenerator.PHANTOM_PATH, corpus);
        assertEquals(0, phantom.getExpectedCount());
    }

    @Test
    void untypedPredicatesAreAgreementOnlyAndWhereSideOnly() {
        JsonPredicate strLeaf = ClickHouseJsonSkipIndexOracle.untypedEquals("u1", LeafKind.STRING, "w1");
        assertEquals("j.u1 = 'w1'", strLeaf.getWhereSql());
        assertFalse(strLeaf.isGroundTruthComputable());
        assertThrows(IllegalStateException.class, strLeaf::getExpectedCount);

        JsonPredicate intLeaf = ClickHouseJsonSkipIndexOracle.untypedEquals("u0", LeafKind.INT, "7");
        assertEquals("j.u0 = 7", intLeaf.getWhereSql());

        JsonPredicate nested = ClickHouseJsonSkipIndexOracle.untypedEquals("n.a.b.c", LeafKind.STRING, "deep1");
        assertEquals("j.n.a.b.c = 'deep1'", nested.getWhereSql());

        assertEquals("j.p_str IS NOT NULL", ClickHouseJsonSkipIndexOracle.typedStrIsNotNull().getWhereSql());
        assertEquals("j.u2 IS NOT NULL", ClickHouseJsonSkipIndexOracle.untypedIsNotNull("u2").getWhereSql());
    }

    @Test
    void groundTruthClassificationPerPredicateKindIsStructural() {
        assertTrue(ClickHouseJsonSkipIndexOracle.isGroundTruthComputable(PredicateKind.TYPED_INT_EQ));
        assertTrue(ClickHouseJsonSkipIndexOracle.isGroundTruthComputable(PredicateKind.TYPED_STR_EQ));
        assertTrue(ClickHouseJsonSkipIndexOracle.isGroundTruthComputable(PredicateKind.TYPED_INT_IN));
        assertTrue(ClickHouseJsonSkipIndexOracle.isGroundTruthComputable(PredicateKind.PATH_EXISTS));

        assertFalse(ClickHouseJsonSkipIndexOracle.isGroundTruthComputable(PredicateKind.UNTYPED_EQ));
        assertFalse(ClickHouseJsonSkipIndexOracle.isGroundTruthComputable(PredicateKind.TYPED_STR_IS_NOT_NULL));
        assertFalse(ClickHouseJsonSkipIndexOracle.isGroundTruthComputable(PredicateKind.UNTYPED_IS_NOT_NULL));
    }

    @Test
    void escapeStringLiteralHandlesQuotesAndBackslashes() {
        assertEquals("val1", ClickHouseJsonSkipIndexOracle.escapeStringLiteral("val1"));
        assertEquals("a\\'b", ClickHouseJsonSkipIndexOracle.escapeStringLiteral("a'b"));
        assertEquals("a\\\\b", ClickHouseJsonSkipIndexOracle.escapeStringLiteral("a\\b"));

        assertEquals("{\"p_str\":\"a\\\\nb\"}",
                ClickHouseJsonSkipIndexOracle.escapeStringLiteral("{\"p_str\":\"a\\nb\"}"));
    }

    @Test
    void armSettingsSuffixesAreCorrectlyAttached() {
        List<Document> corpus = handCorpus();
        JsonPredicate p = ClickHouseJsonSkipIndexOracle.typedIntEquals(7, corpus);

        String def = ClickHouseJsonSkipIndexOracle.renderCountQuery("db.t", p, Arm.DEFAULT);
        assertEquals("SELECT toString(count()) FROM db.t WHERE j.p_int = 7", def);
        assertFalse(def.contains("SETTINGS"), "default arm must carry no settings");

        String ignored = ClickHouseJsonSkipIndexOracle.renderCountQuery("db.t", p, Arm.INDEX_IGNORED);
        assertTrue(ignored.endsWith(" SETTINGS ignore_data_skipping_indices = 'jx'"), ignored);

        String keys = ClickHouseJsonSkipIndexOracle.renderKeysQuery("db.t", p, Arm.INDEX_IGNORED);
        assertEquals("SELECT toString(k) FROM db.t WHERE j.p_int = 7 ORDER BY k"
                + " SETTINGS ignore_data_skipping_indices = 'jx'", keys);
    }

    @Test
    void readsNeverProjectRawJsonOrUntypedPaths() {
        List<Document> corpus = handCorpus();
        List<JsonPredicate> predicates = List.of(ClickHouseJsonSkipIndexOracle.typedIntEquals(7, corpus),
                ClickHouseJsonSkipIndexOracle.untypedEquals("u0", LeafKind.INT, "7"),
                ClickHouseJsonSkipIndexOracle.pathExists("u1", corpus),
                ClickHouseJsonSkipIndexOracle.typedStrIsNotNull());
        List<String> projections = new ArrayList<>();
        for (JsonPredicate p : predicates) {
            for (Arm arm : Arm.values()) {
                projections.add(projectionOf(ClickHouseJsonSkipIndexOracle.renderCountQuery("db.t", p, arm)));
                projections.add(projectionOf(ClickHouseJsonSkipIndexOracle.renderKeysQuery("db.t", p, arm)));
                projections
                        .add(projectionOf(ClickHouseJsonSkipIndexOracle.renderTypedRowImageQuery("db.t", p, arm)));
            }
        }

        Pattern bareJ = Pattern.compile("\\bj\\b");
        for (String projection : projections) {
            String stripped = projection.replace("j.p_int", "").replace("j.p_str", "")
                    .replace("JSONAllPaths(j)", "").replace("JSONAllValues(j)", "");
            assertFalse(bareJ.matcher(stripped).find(),
                    () -> "projection references raw j outside the allowed forms: " + projection);
            assertFalse(stripped.contains("j.u"), () -> "projection reads an untyped path: " + projection);
        }
    }

    private static String projectionOf(String query) {
        int select = query.indexOf("SELECT ");
        int from = query.indexOf(" FROM ");
        assertTrue(select == 0 && from > 0, query);
        return query.substring("SELECT ".length(), from);
    }

    @Test
    void probePredicateMatchesTheIndexVariant() {
        List<Document> corpus = handCorpus();

        String allValues = ClickHouseJsonSkipIndexOracle.renderProbePredicate(IndexVariant.ALL_VALUES_TEXT, corpus);
        assertEquals("j.p_str = 'val1'", allValues);

        for (IndexVariant v : List.of(IndexVariant.ALL_PATHS_BLOOM, IndexVariant.ALL_PATHS_TOKENBF,
                IndexVariant.ALL_PATHS_NGRAMBF, IndexVariant.ALL_PATHS_TEXT)) {
            assertEquals("has(JSONAllPaths(j), 'u0')", ClickHouseJsonSkipIndexOracle.renderProbePredicate(v, corpus));
        }
    }

    @Test
    void probePredicateIsNullWhenNoDocumentIsUsable() {
        List<Document> allEmpty = List.of(doc(), doc());
        assertNull(ClickHouseJsonSkipIndexOracle.renderProbePredicate(IndexVariant.ALL_VALUES_TEXT, allEmpty));
        assertNull(ClickHouseJsonSkipIndexOracle.renderProbePredicate(IndexVariant.ALL_PATHS_BLOOM, allEmpty));
    }

    @Test
    void generatePredicateCoversAllKindsAcrossSeeds() {
        ClickHouseJsonDocumentGenerator gen = new ClickHouseJsonDocumentGenerator(new Randomly(551L));
        List<Document> corpus = gen.generateDocuments(60);
        List<String> untyped = gen.getActiveUntypedPaths();
        Set<String> seenKinds = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            Randomly r = new Randomly(613L + i);
            JsonPredicate p = ClickHouseJsonSkipIndexOracle.generatePredicate(r, corpus, untyped);
            assertNotNull(p.getWhereSql());
            seenKinds.add(p.getKind().name());

            assertEquals(ClickHouseJsonSkipIndexOracle.isGroundTruthComputable(p.getKind()),
                    p.isGroundTruthComputable());
            if (p.isGroundTruthComputable()) {
                assertTrue(p.getExpectedCount() >= 0 && p.getExpectedCount() <= corpus.size());
            }

            if (p.getWhereSql().contains(ClickHouseJsonDocumentGenerator.PHANTOM_PATH)) {
                assertEquals(PredicateKind.PATH_EXISTS, p.getKind());
            }
        }
        for (PredicateKind kind : PredicateKind.values()) {
            assertTrue(seenKinds.contains(kind.name()),
                    () -> kind + " not picked across 500 attempts; saw " + seenKinds);
        }
    }

    @Test
    void generatePredicateOnEmptyCorpusYieldsZeroExpectations() {
        for (int i = 0; i < 300; i++) {
            Randomly r = new Randomly(733L + i);
            JsonPredicate p = ClickHouseJsonSkipIndexOracle.generatePredicate(r, List.of(), List.of("u0", "u1"));
            if (p.isGroundTruthComputable()) {
                assertEquals(0, p.getExpectedCount(), () -> "empty corpus must expect 0 for " + p.getWhereSql());
            }
        }
    }
}
