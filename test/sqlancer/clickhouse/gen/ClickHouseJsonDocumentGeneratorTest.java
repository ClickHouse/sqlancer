package sqlancer.clickhouse.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import sqlancer.Randomly;
import sqlancer.clickhouse.gen.ClickHouseJsonDocumentGenerator.Document;
import sqlancer.clickhouse.gen.ClickHouseJsonDocumentGenerator.Leaf;
import sqlancer.clickhouse.gen.ClickHouseJsonDocumentGenerator.LeafKind;

class ClickHouseJsonDocumentGeneratorTest {

    // --- hand-built corpus: ground truth helpers must be exact on a hand-checkable corpus ---

    private static Document doc(Object... pathLeafPairs) {
        LinkedHashMap<String, Leaf> leaves = new LinkedHashMap<>();
        for (int i = 0; i < pathLeafPairs.length; i += 2) {
            leaves.put((String) pathLeafPairs[i], (Leaf) pathLeafPairs[i + 1]);
        }
        return new Document(leaves);
    }

    private static List<Document> handCorpus() {
        // 5 documents, hand-checkable:
        // d0: p_int=7, p_str=val1, u0=int 7, u1=w1
        // d1: p_int=7, p_str=val2, u0=str "w1"
        // d2: p_int=9, p_str=val1, n.a.b.c=deep1
        // d3: empty {}
        // d4: p_int=12, p_str=val1, u1=w1, n.a.b.c=deep2
        return List.of(
                doc("p_int", Leaf.ofInt(7), "p_str", Leaf.ofString("val1"), "u0", Leaf.ofInt(7), "u1",
                        Leaf.ofString("w1")),
                doc("p_int", Leaf.ofInt(7), "p_str", Leaf.ofString("val2"), "u0", Leaf.ofString("w1")),
                doc("p_int", Leaf.ofInt(9), "p_str", Leaf.ofString("val1"), "n.a.b.c", Leaf.ofString("deep1")),
                doc(),
                doc("p_int", Leaf.ofInt(12), "p_str", Leaf.ofString("val1"), "u1", Leaf.ofString("w1"), "n.a.b.c",
                        Leaf.ofString("deep2")));
    }

    @Test
    void typedEqualityGroundTruthCountsExactly() {
        List<Document> corpus = handCorpus();
        assertEquals(2, ClickHouseJsonDocumentGenerator.countWhereTypedIntEquals(corpus, 7));
        assertEquals(1, ClickHouseJsonDocumentGenerator.countWhereTypedIntEquals(corpus, 9));
        assertEquals(0, ClickHouseJsonDocumentGenerator.countWhereTypedIntEquals(corpus, 555));
        // The empty document has no p_int: it must never count, including for hypothetical
        // default-y values (the default-sentinel guarantee makes 0 unreachable anyway).
        assertEquals(0, ClickHouseJsonDocumentGenerator.countWhereTypedIntEquals(corpus, 0));

        assertEquals(3, ClickHouseJsonDocumentGenerator.countWhereTypedStrEquals(corpus, "val1"));
        assertEquals(1, ClickHouseJsonDocumentGenerator.countWhereTypedStrEquals(corpus, "val2"));
        assertEquals(0, ClickHouseJsonDocumentGenerator.countWhereTypedStrEquals(corpus, ""));
    }

    @Test
    void typedInListGroundTruthIsUnionOfMatches() {
        List<Document> corpus = handCorpus();
        assertEquals(3, ClickHouseJsonDocumentGenerator.countWhereTypedIntIn(corpus, List.of(7L, 9L)));
        assertEquals(1, ClickHouseJsonDocumentGenerator.countWhereTypedIntIn(corpus, List.of(12L, 999L)));
        assertEquals(0, ClickHouseJsonDocumentGenerator.countWhereTypedIntIn(corpus, List.of(999L)));
    }

    @Test
    void pathExistenceGroundTruthSpansZeroSomeAll() {
        List<Document> corpus = handCorpus();
        assertEquals(2, ClickHouseJsonDocumentGenerator.countWherePathExists(corpus, "u0"));
        assertEquals(2, ClickHouseJsonDocumentGenerator.countWherePathExists(corpus, "u1"));
        assertEquals(2, ClickHouseJsonDocumentGenerator.countWherePathExists(corpus, "n.a.b.c"));
        assertEquals(0, ClickHouseJsonDocumentGenerator.countWherePathExists(corpus,
                ClickHouseJsonDocumentGenerator.PHANTOM_PATH));
    }

    @Test
    void existenceGroundTruthRejectsTypedPaths() {
        // JSONAllPaths reports typed paths unconditionally on the server, so a per-document
        // presence model for them would be wrong by design.
        List<Document> corpus = handCorpus();
        assertThrows(IllegalArgumentException.class,
                () -> ClickHouseJsonDocumentGenerator.countWherePathExists(corpus, "p_int"));
        assertThrows(IllegalArgumentException.class,
                () -> ClickHouseJsonDocumentGenerator.countWherePathExists(corpus, "p_str"));
    }

    @Test
    void untypedStringEqualityIsStrictSameType() {
        List<Document> corpus = handCorpus();
        // d0 has u0 = INT 7 and d1 has u0 = STRING "w1": strict same-type equality must not
        // cross-match (d0's int 7 never equals the string "7").
        assertEquals(1, ClickHouseJsonDocumentGenerator.countWhereUntypedStringEquals(corpus, "u0", "w1"));
        assertEquals(0, ClickHouseJsonDocumentGenerator.countWhereUntypedStringEquals(corpus, "u0", "7"));
        assertEquals(2, ClickHouseJsonDocumentGenerator.countWhereUntypedStringEquals(corpus, "u1", "w1"));
    }

    // --- rendering ---

    @Test
    void renderingIsExactForHandBuiltDocuments() {
        Document d = doc("p_int", Leaf.ofInt(42), "p_str", Leaf.ofString("val3"), "u0", Leaf.ofInt(7), "n.a.b.c",
                Leaf.ofString("deep1"));
        assertEquals("{\"p_int\":42,\"p_str\":\"val3\",\"u0\":7,\"n\":{\"a\":{\"b\":{\"c\":\"deep1\"}}}}", d.render());
        assertEquals("{}", doc().render());
        assertTrue(doc().isEmpty());
    }

    @Test
    void escapeJsonStringHandlesQuotesBackslashesAndControlChars() {
        assertEquals("alpha7", ClickHouseJsonDocumentGenerator.escapeJsonString("alpha7"));
        assertEquals("a\\\"b", ClickHouseJsonDocumentGenerator.escapeJsonString("a\"b"));
        assertEquals("a\\\\b", ClickHouseJsonDocumentGenerator.escapeJsonString("a\\b"));
        assertEquals("a\\nb\\tc\\rd\\be\\ff", ClickHouseJsonDocumentGenerator.escapeJsonString("a\nb\tc\rd\be\ff"));
        // Unnamed control char below U+0020 -> \\u00XX.
        assertEquals("x\\u0001y", ClickHouseJsonDocumentGenerator.escapeJsonString("x\u0001y"));
        // Backslash-then-quote: backslash escaped first, quote escape survives.
        assertEquals("\\\\\\\"", ClickHouseJsonDocumentGenerator.escapeJsonString("\\\""));
    }

    @Test
    void escapedSpecialCharLeafRendersAsValidJsonStringBody() {
        Document d = doc("p_str", Leaf.ofString("a\"b\\c"));
        assertEquals("{\"p_str\":\"a\\\"b\\\\c\"}", d.render());
    }

    // --- generated corpora ---

    @Test
    void generationIsDeterministicGivenTheSeed() {
        List<String> first = renderedCorpusForSeed(424242L, 150);
        List<String> second = renderedCorpusForSeed(424242L, 150);
        assertEquals(first, second, "same seed must produce byte-identical rendered documents");
    }

    @Test
    void activePathsAreAPrefixOfThePoolOfSizeTwoToFour() {
        for (int i = 0; i < 50; i++) {
            ClickHouseJsonDocumentGenerator gen = new ClickHouseJsonDocumentGenerator(new Randomly(1000L + i));
            List<String> active = gen.getActiveUntypedPaths();
            assertTrue(active.size() >= 2 && active.size() <= 4, () -> "active untyped paths: " + active);
            assertEquals(ClickHouseJsonDocumentGenerator.UNTYPED_PATH_POOL.subList(0, active.size()), active);
            assertEquals(ClickHouseJsonDocumentGenerator.MIXED_TYPE_PATH, active.get(0),
                    "the mixed-type path must always be active");
        }
    }

    @Test
    void generatedDocumentsAreStructurallySoundJson() {
        ClickHouseJsonDocumentGenerator gen = new ClickHouseJsonDocumentGenerator(new Randomly(7L));
        Set<String> allowedPaths = new HashSet<>(gen.getActiveUntypedPaths());
        allowedPaths.add(ClickHouseJsonDocumentGenerator.TYPED_INT_PATH);
        allowedPaths.add(ClickHouseJsonDocumentGenerator.TYPED_STR_PATH);
        allowedPaths.add(ClickHouseJsonDocumentGenerator.NESTED_PATH);
        for (Document d : gen.generateDocuments(300)) {
            String json = d.render();
            assertTrue(json.startsWith("{") && json.endsWith("}"), json);
            assertEquals(json.chars().filter(c -> c == '{').count(), json.chars().filter(c -> c == '}').count(),
                    json);
            assertEquals(0, json.chars().filter(c -> c == '"').count() % 2, json);
            if (!d.isEmpty()) {
                // Typed paths are present in every non-empty document, as quoted keys.
                assertTrue(json.contains("\"p_int\":"), json);
                assertTrue(json.contains("\"p_str\":\""), json);
                // Default-sentinel guarantee: int leaves >= 1, string leaves non-empty.
                Leaf typedInt = d.getLeaf(ClickHouseJsonDocumentGenerator.TYPED_INT_PATH);
                assertNotNull(typedInt);
                assertEquals(LeafKind.INT, typedInt.getKind());
                assertTrue(Long.parseLong(typedInt.getText()) >= 1, json);
                Leaf typedStr = d.getLeaf(ClickHouseJsonDocumentGenerator.TYPED_STR_PATH);
                assertNotNull(typedStr);
                assertEquals(LeafKind.STRING, typedStr.getKind());
                assertFalse(typedStr.getText().isEmpty(), json);
            }
            // No path outside the instance schema, and never the phantom path.
            assertTrue(allowedPaths.containsAll(d.getPaths()),
                    () -> "document carries a path outside the instance schema: " + d.getPaths());
            assertFalse(d.hasPath(ClickHouseJsonDocumentGenerator.PHANTOM_PATH));
        }
    }

    @Test
    void corpusExhibitsAbsenceMixedTypesEmptyDocsAndNesting() {
        ClickHouseJsonDocumentGenerator gen = new ClickHouseJsonDocumentGenerator(new Randomly(99L));
        List<Document> corpus = gen.generateDocuments(300);

        boolean sawEmpty = false;
        boolean sawNested = false;
        boolean sawMixedInt = false;
        boolean sawMixedString = false;
        boolean sawAbsentUntyped = false;
        for (Document d : corpus) {
            sawEmpty |= d.isEmpty();
            sawNested |= d.hasPath(ClickHouseJsonDocumentGenerator.NESTED_PATH);
            Leaf mixed = d.getLeaf(ClickHouseJsonDocumentGenerator.MIXED_TYPE_PATH);
            if (mixed != null) {
                sawMixedInt |= mixed.getKind() == LeafKind.INT;
                sawMixedString |= mixed.getKind() == LeafKind.STRING;
            } else if (!d.isEmpty()) {
                sawAbsentUntyped = true; // per-document optional absence (PR #98886 edge)
            }
        }
        assertTrue(sawEmpty, "no empty {} document in 300 draws");
        assertTrue(sawNested, "nested path never emitted in 300 draws");
        assertTrue(sawMixedInt && sawMixedString, "mixed-type path must mix Int64 and String leaves across rows");
        assertTrue(sawAbsentUntyped, "untyped paths must sometimes be absent from non-empty documents");
    }

    private static List<String> renderedCorpusForSeed(long seed, int count) {
        ClickHouseJsonDocumentGenerator gen = new ClickHouseJsonDocumentGenerator(new Randomly(seed));
        List<String> rendered = new ArrayList<>(count + 1);
        rendered.add(String.join(",", gen.getActiveUntypedPaths()));
        for (Document d : gen.generateDocuments(count)) {
            rendered.add(d.render());
        }
        return rendered;
    }
}
