package sqlancer.clickhouse.gen;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import sqlancer.Randomly;

public class ClickHouseJsonDocumentGenerator {

    public static final String TYPED_INT_PATH = "p_int";

    public static final String TYPED_STR_PATH = "p_str";

    public static final String MIXED_TYPE_PATH = "u0";

    public static final String NESTED_PATH = "n.a.b.c";

    public static final String PHANTOM_PATH = "zz_phantom";

    public static final List<String> UNTYPED_PATH_POOL = List.of("u0", "u1", "u2", "u3");

    private static final int TYPED_INT_MAX = 200;
    private static final int TYPED_STR_CARDINALITY = 30;
    private static final int UNTYPED_INT_MAX = 50;
    private static final int UNTYPED_STR_CARDINALITY = 20;
    private static final int NESTED_STR_CARDINALITY = 9;

    public enum LeafKind {
        INT, STRING
    }

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

        public String getText() {
            return text;
        }
    }

    public static final class Document {
        private final Map<String, Leaf> leaves;
        private final String rendered;

        public Document(LinkedHashMap<String, Leaf> leaves) {
            this.leaves = leaves;
            this.rendered = renderJson(leaves);
        }

        public String render() {
            return rendered;
        }

        public boolean isEmpty() {
            return leaves.isEmpty();
        }

        public boolean hasPath(String path) {
            return leaves.containsKey(path);
        }

        public Leaf getLeaf(String path) {
            return leaves.get(path);
        }

        public Set<String> getPaths() {
            return Collections.unmodifiableSet(leaves.keySet());
        }
    }

    private final Randomly r;
    private final List<String> activeUntypedPaths;

    public ClickHouseJsonDocumentGenerator(Randomly r) {
        this.r = r;

        int untypedCount = 2 + r.getInteger(0, 3);
        this.activeUntypedPaths = UNTYPED_PATH_POOL.subList(0, untypedCount);
    }

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

    public Document generateDocument() {
        LinkedHashMap<String, Leaf> leaves = new LinkedHashMap<>();
        if (r.getInteger(0, 12) == 0) {
            return new Document(leaves);
        }
        leaves.put(TYPED_INT_PATH, Leaf.ofInt(1 + r.getInteger(0, TYPED_INT_MAX)));
        leaves.put(TYPED_STR_PATH, Leaf.ofString("val" + (1 + r.getInteger(0, TYPED_STR_CARDINALITY))));
        for (String path : activeUntypedPaths) {
            if (r.getInteger(0, 3) == 0) {
                continue;
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

    public static long countWhereTypedIntEquals(List<Document> corpus, long value) {
        String text = String.valueOf(value);
        return corpus.stream().filter(d -> leafEquals(d, TYPED_INT_PATH, LeafKind.INT, text)).count();
    }

    public static long countWhereTypedStrEquals(List<Document> corpus, String value) {
        return corpus.stream().filter(d -> leafEquals(d, TYPED_STR_PATH, LeafKind.STRING, value)).count();
    }

    public static long countWhereTypedIntIn(List<Document> corpus, Collection<Long> values) {
        return corpus.stream()
                .filter(d -> values.stream().anyMatch(v -> leafEquals(d, TYPED_INT_PATH, LeafKind.INT,
                        String.valueOf(v))))
                .count();
    }

    public static long countWherePathExists(List<Document> corpus, String path) {
        if (path.equals(TYPED_INT_PATH) || path.equals(TYPED_STR_PATH)) {
            throw new IllegalArgumentException(
                    "existence ground truth is untyped-only; JSONAllPaths always reports typed path " + path);
        }
        return corpus.stream().filter(d -> d.hasPath(path)).count();
    }

    public static long countWhereUntypedStringEquals(List<Document> corpus, String path, String value) {
        return corpus.stream().filter(d -> leafEquals(d, path, LeafKind.STRING, value)).count();
    }

    private static boolean leafEquals(Document d, String path, LeafKind kind, String text) {
        Leaf leaf = d.getLeaf(path);
        return leaf != null && leaf.getKind() == kind && leaf.getText().equals(text);
    }

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
