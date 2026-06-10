package sqlancer.clickhouse.gen;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import sqlancer.Randomly;

/**
 * Reusable JSON document-corpus generator for the ClickHouse {@code JSON} column model (26.x). Owned by the
 * JsonSkipIndex oracle (plan Unit 7) but deliberately a standalone class because roadmap Unit 4.1's future fleet JSON
 * emission reuses the same corpus model.
 *
 * <p>
 * Each generator instance fixes one <b>path schema</b>:
 * <ul>
 * <li>two typed paths -- {@link #TYPED_INT_PATH} ({@code Int64}) and {@link #TYPED_STR_PATH} ({@code String}) --
 * matching the column declaration {@code JSON(p_int Int64, p_str String)}; present in every non-empty document,</li>
 * <li>2-4 untyped paths drawn in order from {@link #UNTYPED_PATH_POOL} ({@code u0..u3}), each <b>optionally absent
 * per document</b> (the absent-path edge of PR #98886's skip-avoidance rule); {@link #MIXED_TYPE_PATH} ({@code u0})
 * mixes Int64 and String leaves across rows (Dynamic typing inside JSON),</li>
 * <li>one deeply nested path {@link #NESTED_PATH} ({@code n.a.b.c}), present in a minority of documents,</li>
 * <li>occasional fully empty {@code {}} documents (no paths at all, typed ones included).</li>
 * </ul>
 *
 * <p>
 * Every document carries Java-side ground truth (path -&gt; leaf), so expected counts for typed-path
 * equality/IN and untyped path-existence predicates are computable without the server:
 * {@link #countWhereTypedIntEquals}, {@link #countWhereTypedStrEquals}, {@link #countWhereTypedIntIn},
 * {@link #countWherePathExists}, {@link #countWhereUntypedStringEquals}.
 *
 * <p>
 * <b>Default-sentinel guarantee:</b> generated Int64 leaves are always &gt;= 1 and String leaves always non-empty.
 * A document missing a typed path therefore can never equal a generated leaf value regardless of whether the server
 * reads the absent typed subcolumn as NULL or as the type default ({@code 0} / {@code ''}) -- the equality ground
 * truth ("path present and leaf equal") is sound under both semantics, so callers never need to model that server
 * detail. {@code IS NOT NULL} ground truth is deliberately NOT offered for the same reason (it would have to pick a
 * side).
 *
 * <p>
 * <b>Existence ground truth is untyped-only:</b> per the JSON docs, {@code JSONAllPaths} reports declared typed paths
 * unconditionally (they are always stored), so {@code has(JSONAllPaths(j), '&lt;typed&gt;')} does not reflect
 * per-document presence. {@link #countWherePathExists} rejects typed paths outright rather than risk a false-positive
 * model.
 *
 * <p>
 * Rendering is deterministic given the {@link Randomly} seed: leaf vocabularies are fixed, key order is fixed
 * (typed, then untyped in pool order, then nested), and JSON string escaping ({@link #escapeJsonString}) is total
 * (quotes, backslashes, control characters) even though generated leaves are simple alphanumerics by construction.
 */
public class ClickHouseJsonDocumentGenerator {

    /** Declared typed path of type Int64 -- readable as the concrete subcolumn {@code j.p_int}. */
    public static final String TYPED_INT_PATH = "p_int";
    /** Declared typed path of type String -- readable as the concrete subcolumn {@code j.p_str}. */
    public static final String TYPED_STR_PATH = "p_str";
    /** The untyped path whose leaf type mixes Int64 and String across documents (always active). */
    public static final String MIXED_TYPE_PATH = "u0";
    /** The single deeply nested untyped path. */
    public static final String NESTED_PATH = "n.a.b.c";
    /** A path the generator never emits: existence predicates on it must match 0 rows. */
    public static final String PHANTOM_PATH = "zz_phantom";

    /** Untyped flat paths, activated as a prefix of this list (2-4 per instance). */
    public static final List<String> UNTYPED_PATH_POOL = List.of("u0", "u1", "u2", "u3");

    // Leaf value ranges. Int leaves >= 1 and string leaves non-empty: see the default-sentinel
    // guarantee in the class javadoc. Ranges are small so corpus-drawn equality predicates match
    // multiple documents (selectivity, not needle-in-haystack).
    private static final int TYPED_INT_MAX = 200;
    private static final int TYPED_STR_CARDINALITY = 30;
    private static final int UNTYPED_INT_MAX = 50;
    private static final int UNTYPED_STR_CARDINALITY = 20;
    private static final int NESTED_STR_CARDINALITY = 9;

    /** Leaf type tag: drives JSON rendering (quoted or not) and SQL predicate rendering at the call site. */
    public enum LeafKind {
        INT, STRING
    }

    /** One leaf value: a type tag plus its text (decimal for INT, raw unescaped characters for STRING). */
    public static final class Leaf {
        private final LeafKind kind;
        private final String text;

        private Leaf(LeafKind kind, String text) {
            this.kind = kind;
            this.text = text;
        }

        public static Leaf ofInt(long value) {
            return new Leaf(LeafKind.INT, String.valueOf(value));
        }

        public static Leaf ofString(String value) {
            return new Leaf(LeafKind.STRING, value);
        }

        public LeafKind getKind() {
            return kind;
        }

        /** Raw leaf text: decimal rendering for INT leaves, the unescaped string for STRING leaves. */
        public String getText() {
            return text;
        }
    }

    /**
     * One generated document: the rendered JSON object string (for INSERT embedding) plus the Java-side ground truth
     * map (leaf path -&gt; leaf). Paths use dotted notation ({@code n.a.b.c}); an absent path is an absent key.
     */
    public static final class Document {
        private final Map<String, Leaf> leaves;
        private final String rendered;

        /** Public so tests and corpus-assembling callers (roadmap Unit 4.1) can hand-build fixed documents. */
        public Document(LinkedHashMap<String, Leaf> leaves) {
            this.leaves = leaves;
            this.rendered = renderJson(leaves);
        }

        /** The rendered JSON object, e.g. {@code {"p_int":42,"p_str":"val3","u0":7,"n":{"a":{"b":{"c":"deep1"}}}}}. */
        public String render() {
            return rendered;
        }

        public boolean isEmpty() {
            return leaves.isEmpty();
        }

        public boolean hasPath(String path) {
            return leaves.containsKey(path);
        }

        /** Leaf at {@code path}, or null when the path is absent from this document. */
        public Leaf getLeaf(String path) {
            return leaves.get(path);
        }

        /** All leaf paths present in this document (dotted notation), in render order. */
        public Set<String> getPaths() {
            return Collections.unmodifiableSet(leaves.keySet());
        }
    }

    private final Randomly r;
    private final List<String> activeUntypedPaths;

    public ClickHouseJsonDocumentGenerator(Randomly r) {
        this.r = r;
        // 2-4 untyped paths per instance, always a prefix of the pool so u0 (the mixed-type path)
        // is always active.
        int untypedCount = 2 + r.getInteger(0, 3);
        this.activeUntypedPaths = UNTYPED_PATH_POOL.subList(0, untypedCount);
    }

    /** The untyped flat paths this instance can emit (prefix of {@link #UNTYPED_PATH_POOL}, length 2-4). */
    public List<String> getActiveUntypedPaths() {
        return activeUntypedPaths;
    }

    public List<Document> generateDocuments(int count) {
        List<Document> docs = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            docs.add(generateDocument());
        }
        return docs;
    }

    /**
     * One document. ~1/12 are completely empty {@code {}}; otherwise both typed paths are always present, each active
     * untyped path is present with probability 2/3 (per-document optional absence), and the nested path with
     * probability 2/5. {@code u0} leaves are Int64 half the time and String otherwise (mixed leaf types across rows);
     * all other untyped leaves are String.
     */
    public Document generateDocument() {
        LinkedHashMap<String, Leaf> leaves = new LinkedHashMap<>();
        if (r.getInteger(0, 12) == 0) {
            return new Document(leaves); // empty {} document: even the typed paths are absent
        }
        leaves.put(TYPED_INT_PATH, Leaf.ofInt(1 + r.getInteger(0, TYPED_INT_MAX)));
        leaves.put(TYPED_STR_PATH, Leaf.ofString("val" + (1 + r.getInteger(0, TYPED_STR_CARDINALITY))));
        for (String path : activeUntypedPaths) {
            if (r.getInteger(0, 3) == 0) {
                continue; // absent in this document (PR #98886 absent-path edge)
            }
            if (path.equals(MIXED_TYPE_PATH) && r.getInteger(0, 2) == 0) {
                leaves.put(path, Leaf.ofInt(1 + r.getInteger(0, UNTYPED_INT_MAX)));
            } else {
                leaves.put(path, Leaf.ofString("w" + (1 + r.getInteger(0, UNTYPED_STR_CARDINALITY))));
            }
        }
        if (r.getInteger(0, 5) < 2) {
            leaves.put(NESTED_PATH, Leaf.ofString("deep" + (1 + r.getInteger(0, NESTED_STR_CARDINALITY))));
        }
        return new Document(leaves);
    }

    // --- ground-truth query helpers: pure Java over a corpus, no server involved ---

    /** Documents where the typed Int64 path is present AND equals {@code value} (sound for values &gt;= 1). */
    public static long countWhereTypedIntEquals(List<Document> corpus, long value) {
        String text = String.valueOf(value);
        return corpus.stream().filter(d -> leafEquals(d, TYPED_INT_PATH, LeafKind.INT, text)).count();
    }

    /** Documents where the typed String path is present AND equals {@code value} (sound for non-empty values). */
    public static long countWhereTypedStrEquals(List<Document> corpus, String value) {
        return corpus.stream().filter(d -> leafEquals(d, TYPED_STR_PATH, LeafKind.STRING, value)).count();
    }

    /** Documents where the typed Int64 path is present AND its value is in {@code values}. */
    public static long countWhereTypedIntIn(List<Document> corpus, Collection<Long> values) {
        return corpus.stream()
                .filter(d -> values.stream().anyMatch(v -> leafEquals(d, TYPED_INT_PATH, LeafKind.INT,
                        String.valueOf(v))))
                .count();
    }

    /**
     * Documents that contain {@code path}. Untyped paths only: {@code JSONAllPaths} reports declared typed paths
     * unconditionally on the server, so a per-document presence model for them would be wrong by design (see class
     * javadoc) -- typed paths are rejected here.
     */
    public static long countWherePathExists(List<Document> corpus, String path) {
        if (path.equals(TYPED_INT_PATH) || path.equals(TYPED_STR_PATH)) {
            throw new IllegalArgumentException(
                    "existence ground truth is untyped-only; JSONAllPaths always reports typed path " + path);
        }
        return corpus.stream().filter(d -> d.hasPath(path)).count();
    }

    /**
     * Documents where the untyped {@code path} holds a String leaf equal to {@code value}. NOTE: this models strict
     * same-type equality; an oracle predicating equality on a Dynamic-typed untyped path should treat the comparison
     * as agreement-only (server-side Dynamic comparison semantics are not re-implemented here) -- this helper exists
     * for corpus introspection and roadmap Unit 4.1 reuse.
     */
    public static long countWhereUntypedStringEquals(List<Document> corpus, String path, String value) {
        return corpus.stream().filter(d -> leafEquals(d, path, LeafKind.STRING, value)).count();
    }

    private static boolean leafEquals(Document d, String path, LeafKind kind, String text) {
        Leaf leaf = d.getLeaf(path);
        return leaf != null && leaf.getKind() == kind && leaf.getText().equals(text);
    }

    // --- rendering ---

    /**
     * Escapes a leaf string (or key) for embedding inside a double-quoted JSON string. Generated leaves are simple
     * alphanumerics by construction, but the helper is total anyway: backslash and quote are escaped, the named
     * control escapes are used where JSON defines them, and any other character below U+0020 becomes {@code \\u00XX}.
     */
    public static String escapeJsonString(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
            case '\\':
                sb.append("\\\\");
                break;
            case '"':
                sb.append("\\\"");
                break;
            case '\b':
                sb.append("\\b");
                break;
            case '\f':
                sb.append("\\f");
                break;
            case '\n':
                sb.append("\\n");
                break;
            case '\r':
                sb.append("\\r");
                break;
            case '\t':
                sb.append("\\t");
                break;
            default:
                if (c < 0x20) {
                    sb.append(String.format("\\u%04x", (int) c));
                } else {
                    sb.append(c);
                }
                break;
            }
        }
        return sb.toString();
    }

    // Renders the dotted-path leaf map as one JSON object: paths are split on '.' and folded into
    // a nested object tree (insertion-ordered, so rendering is deterministic), then the tree is
    // serialized depth-first. Tree node values are either Leaf (leaf) or LinkedHashMap (object).
    static String renderJson(Map<String, Leaf> leaves) {
        LinkedHashMap<String, Object> root = new LinkedHashMap<>();
        for (Map.Entry<String, Leaf> entry : leaves.entrySet()) {
            String[] segments = entry.getKey().split("\\.");
            LinkedHashMap<String, Object> node = root;
            for (int i = 0; i < segments.length - 1; i++) {
                Object child = node.computeIfAbsent(segments[i], k -> new LinkedHashMap<String, Object>());
                if (!(child instanceof LinkedHashMap)) {
                    throw new IllegalArgumentException("path " + entry.getKey() + " collides with a leaf at segment "
                            + segments[i]);
                }
                @SuppressWarnings("unchecked")
                LinkedHashMap<String, Object> childMap = (LinkedHashMap<String, Object>) child;
                node = childMap;
            }
            node.put(segments[segments.length - 1], entry.getValue());
        }
        StringBuilder sb = new StringBuilder();
        renderNode(root, sb);
        return sb.toString();
    }

    private static void renderNode(Map<String, Object> node, StringBuilder sb) {
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> entry : node.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(escapeJsonString(entry.getKey())).append("\":");
            if (entry.getValue() instanceof Leaf) {
                Leaf leaf = (Leaf) entry.getValue();
                if (leaf.getKind() == LeafKind.INT) {
                    sb.append(leaf.getText());
                } else {
                    sb.append('"').append(escapeJsonString(leaf.getText())).append('"');
                }
            } else {
                @SuppressWarnings("unchecked")
                Map<String, Object> child = (Map<String, Object>) entry.getValue();
                renderNode(child, sb);
            }
        }
        sb.append('}');
    }
}
