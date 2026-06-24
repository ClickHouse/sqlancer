package sqlancer.clickhouse.oracle.jsonidx;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import sqlancer.ComparatorHelper;
import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.clickhouse.ClickHouseErrors;
import sqlancer.clickhouse.ClickHouseProvider.ClickHouseGlobalState;
import sqlancer.clickhouse.gen.ClickHouseJsonDocumentGenerator;
import sqlancer.clickhouse.gen.ClickHouseJsonDocumentGenerator.Document;
import sqlancer.clickhouse.gen.ClickHouseJsonDocumentGenerator.Leaf;
import sqlancer.clickhouse.gen.ClickHouseJsonDocumentGenerator.LeafKind;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;

public class ClickHouseJsonSkipIndexOracle implements TestOracle<ClickHouseGlobalState> {

    private static final AtomicLong JSIDX_COUNTER = new AtomicLong();

    private static final AtomicLong INDEX_ENGAGED = new AtomicLong();

    private static final long PROBE_EVERY = 10;

    static final String INDEX_NAME = "jx";

    enum IndexVariant {
        ALL_PATHS_BLOOM("JSONAllPaths(j)", "bloom_filter(0.01)"),
        ALL_PATHS_TOKENBF("JSONAllPaths(j)", "tokenbf_v1(256, 2, 0)"),
        ALL_PATHS_NGRAMBF("JSONAllPaths(j)", "ngrambf_v1(3, 256, 2, 0)"),
        ALL_PATHS_TEXT("JSONAllPaths(j)", "text(tokenizer = 'splitByNonAlpha')"),
        ALL_VALUES_TEXT("JSONAllValues(j)", "text(tokenizer = 'splitByNonAlpha')");

        private final String indexExpression;
        private final String indexType;

        IndexVariant(String indexExpression, String indexType) {
            this.indexExpression = indexExpression;
            this.indexType = indexType;
        }

        String renderIndexClause() {
            return "INDEX " + INDEX_NAME + " " + indexExpression + " TYPE " + indexType + " GRANULARITY 1";
        }

        boolean isOverAllValues() {
            return this == ALL_VALUES_TEXT;
        }
    }

    enum Arm {
        DEFAULT(""),
        INDEX_IGNORED(" SETTINGS ignore_data_skipping_indices = '" + INDEX_NAME + "'");

        private final String settingsSuffix;

        Arm(String settingsSuffix) {
            this.settingsSuffix = settingsSuffix;
        }

        String getSettingsSuffix() {
            return settingsSuffix;
        }
    }

    enum PredicateKind {
        TYPED_INT_EQ,
        TYPED_STR_EQ,
        TYPED_INT_IN,
        UNTYPED_EQ,
        TYPED_STR_IS_NOT_NULL,
        UNTYPED_IS_NOT_NULL,
        PATH_EXISTS
    }

    static final class JsonPredicate {
        private final String whereSql;
        private final PredicateKind kind;
        private final long expectedCount;

        JsonPredicate(String whereSql, PredicateKind kind, long expectedCount) {
            this.whereSql = whereSql;
            this.kind = kind;
            this.expectedCount = expectedCount;
        }

        String getWhereSql() {
            return whereSql;
        }

        PredicateKind getKind() {
            return kind;
        }

        boolean isGroundTruthComputable() {
            return ClickHouseJsonSkipIndexOracle.isGroundTruthComputable(kind);
        }

        long getExpectedCount() {
            if (!isGroundTruthComputable()) {
                throw new IllegalStateException("predicate kind " + kind + " has no Java ground truth");
            }
            return expectedCount;
        }
    }

    private final ClickHouseGlobalState state;

    private final ExpectedErrors createErrors = new ExpectedErrors();
    private final ExpectedErrors readErrors = new ExpectedErrors();
    private final ExpectedErrors probeErrors = new ExpectedErrors();

    public ClickHouseJsonSkipIndexOracle(ClickHouseGlobalState state) {
        this.state = state;
        for (ExpectedErrors e : List.of(createErrors, readErrors, probeErrors)) {

            ClickHouseErrors.addSessionSettingsErrors(e);

            e.add("UNKNOWN_TABLE");
            e.add("Unknown table expression identifier");

            e.add("(MEMORY_LIMIT_EXCEEDED)");
            e.add("memory limit exceeded");

            e.add("TIMEOUT_EXCEEDED");
            e.add("Timeout exceeded");
        }

        readErrors.add("There is no supertype");
        readErrors.add("NO_COMMON_TYPE");
        readErrors.add("Cannot convert string");

        createErrors.add("allow_experimental_json_type");
        createErrors.add("Experimental JSON type");
        createErrors.add("Unknown data type");

        createErrors.add("Unknown Index type");
        createErrors.add("Unknown index type");
        createErrors.add("Unknown tokenizer");

        createErrors.add("Unexpected text index arguments");
        createErrors.add("Unknown function JSONAllPaths");
        createErrors.add("Unknown function JSONAllValues");

        createErrors.add("full-text index");
        createErrors.add("full_text_index");
        createErrors.add("SUPPORT_IS_DISABLED");

        createErrors.add("of bloom filter index");

        probeErrors.add("INDEX_NOT_USED");
    }

    @Override
    public void check() throws SQLException {
        long id = JSIDX_COUNTER.incrementAndGet();
        String table = state.getDatabaseName() + ".jsidx_" + id + "_t";
        Randomly r = state.getRandomly();

        IndexVariant variant = IndexVariant.values()[r.getInteger(0, IndexVariant.values().length)];
        int indexGranularity = 4 + r.getInteger(0, 5);
        String create = renderCreateTable(table, variant, indexGranularity);

        ClickHouseJsonDocumentGenerator docGen = new ClickHouseJsonDocumentGenerator(r);

        boolean emptyTable = Randomly.getBooleanWithSmallProbability();
        List<Document> corpus = new ArrayList<>();

        try {
            logStmt(create);
            if (!new SQLQueryAdapter(create, createErrors, true).execute(state)) {
                throw new IgnoreMeException();
            }
            if (!emptyTable) {

                int blocks = 3 + r.getInteger(0, 3);
                for (int b = 0; b < blocks; b++) {
                    int docs = 30 + r.getInteger(0, 51);
                    List<Document> block = docGen.generateDocuments(docs);
                    String insert = renderInsertBlock(table, corpus.size(), block);
                    logStmt(insert);
                    if (!new SQLQueryAdapter(insert, readErrors, true).execute(state)) {
                        throw new IgnoreMeException();
                    }
                    corpus.addAll(block);
                }
            }

            JsonPredicate predicate = generatePredicate(r, corpus, docGen.getActiveUntypedPaths());

            Arm[] arms = Arm.values();
            String[] counts = new String[arms.length];
            List<List<String>> keyLists = new ArrayList<>(arms.length);
            List<List<String>> rowImages = new ArrayList<>(arms.length);
            for (int i = 0; i < arms.length; i++) {
                counts[i] = readSingleValue(renderCountQuery(table, predicate, arms[i]));
                keyLists.add(ComparatorHelper.getResultSetFirstColumnAsString(
                        renderKeysQuery(table, predicate, arms[i]), readErrors, state));
                rowImages.add(ComparatorHelper.getResultSetFirstColumnAsString(
                        renderTypedRowImageQuery(table, predicate, arms[i]), readErrors, state));
            }

            for (int i = 1; i < arms.length; i++) {
                if (!counts[0].equals(counts[i])) {
                    throw new AssertionError(String.format(
                            "JSON skip-index count mismatch: predicate %s (kind %s, index %s): arm %s saw %s rows "
                                    + "but arm %s saw %s. DDL: %s",
                            predicate.getWhereSql(), predicate.getKind(), variant, arms[0], counts[0], arms[i],
                            counts[i], create));
                }
                if (!keyLists.get(0).equals(keyLists.get(i))) {
                    throw new AssertionError(String.format(
                            "JSON skip-index key-list mismatch: predicate %s (kind %s, index %s): arm %s keys %s vs "
                                    + "arm %s keys %s. DDL: %s",
                            predicate.getWhereSql(), predicate.getKind(), variant, arms[0],
                            truncateForMessage(keyLists.get(0)), arms[i], truncateForMessage(keyLists.get(i)),
                            create));
                }
                if (!rowImages.get(0).equals(rowImages.get(i))) {
                    throw new AssertionError(String.format(
                            "JSON skip-index typed-row mismatch: predicate %s (kind %s, index %s): arm %s rows %s vs "
                                    + "arm %s rows %s. DDL: %s",
                            predicate.getWhereSql(), predicate.getKind(), variant, arms[0],
                            truncateForMessage(rowImages.get(0)), arms[i], truncateForMessage(rowImages.get(i)),
                            create));
                }
            }

            if (predicate.isGroundTruthComputable()) {
                long expected = predicate.getExpectedCount();
                if (!String.valueOf(expected).equals(counts[0])) {
                    throw new AssertionError(String.format(
                            "JSON skip-index ground-truth mismatch: predicate %s (kind %s, index %s): Java ground "
                                    + "truth over the %d-document corpus expects %d matches but all arms agree on %s. "
                                    + "DDL: %s",
                            predicate.getWhereSql(), predicate.getKind(), variant, corpus.size(), expected, counts[0],
                            create));
                }
            }

            if (id % PROBE_EVERY == 0 && !corpus.isEmpty()) {
                String probePredicate = renderProbePredicate(variant, corpus);
                if (probePredicate != null) {
                    String probe = "SELECT toString(count()) FROM " + table + " WHERE " + probePredicate
                            + " SETTINGS force_data_skipping_indices = '" + INDEX_NAME + "'";
                    List<String> probeResult = ComparatorHelper.getResultSetFirstColumnAsString(probe, probeErrors,
                            state);
                    if (probeResult.size() == 1) {
                        INDEX_ENGAGED.incrementAndGet();
                    }
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

    static boolean isGroundTruthComputable(PredicateKind kind) {
        switch (kind) {
        case TYPED_INT_EQ:
        case TYPED_STR_EQ:
        case TYPED_INT_IN:
        case PATH_EXISTS:
            return true;
        case UNTYPED_EQ:
        case TYPED_STR_IS_NOT_NULL:
        case UNTYPED_IS_NOT_NULL:
            return false;
        default:
            throw new AssertionError(kind);
        }
    }

    static String renderCreateTable(String table, IndexVariant variant, int indexGranularity) {
        return "CREATE TABLE " + table + " (k UInt32, j JSON(" + ClickHouseJsonDocumentGenerator.TYPED_INT_PATH
                + " Int64, " + ClickHouseJsonDocumentGenerator.TYPED_STR_PATH + " String), "
                + variant.renderIndexClause() + ") ENGINE = MergeTree ORDER BY k SETTINGS index_granularity = "
                + indexGranularity;
    }

    static String renderInsertBlock(String table, int startKey, List<Document> docs) {
        StringBuilder sb = new StringBuilder("INSERT INTO ").append(table).append(" (k, j) VALUES ");
        for (int i = 0; i < docs.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append('(').append(startKey + i).append(", '").append(escapeStringLiteral(docs.get(i).render()))
                    .append("')");
        }
        return sb.toString();
    }

    static String renderCountQuery(String table, JsonPredicate predicate, Arm arm) {
        return "SELECT toString(count()) FROM " + table + " WHERE " + predicate.getWhereSql()
                + arm.getSettingsSuffix();
    }

    static String renderKeysQuery(String table, JsonPredicate predicate, Arm arm) {
        return "SELECT toString(k) FROM " + table + " WHERE " + predicate.getWhereSql() + " ORDER BY k"
                + arm.getSettingsSuffix();
    }

    static String renderTypedRowImageQuery(String table, JsonPredicate predicate, Arm arm) {
        return "SELECT concat(toString(k), '|', toString(j." + ClickHouseJsonDocumentGenerator.TYPED_INT_PATH
                + "), '|', toString(j." + ClickHouseJsonDocumentGenerator.TYPED_STR_PATH
                + "), '|', arrayStringConcat(JSONAllPaths(j), ';')) FROM " + table + " WHERE "
                + predicate.getWhereSql() + " ORDER BY k" + arm.getSettingsSuffix();
    }

    static String renderProbePredicate(IndexVariant variant, List<Document> corpus) {
        if (variant.isOverAllValues()) {
            for (Document d : corpus) {
                Leaf leaf = d.getLeaf(ClickHouseJsonDocumentGenerator.TYPED_STR_PATH);
                if (leaf != null) {
                    return "j." + ClickHouseJsonDocumentGenerator.TYPED_STR_PATH + " = '"
                            + escapeStringLiteral(leaf.getText()) + "'";
                }
            }
            return null;
        }
        for (Document d : corpus) {
            for (String path : ClickHouseJsonDocumentGenerator.UNTYPED_PATH_POOL) {
                if (d.hasPath(path)) {
                    return "has(JSONAllPaths(j), '" + escapeStringLiteral(path) + "')";
                }
            }
        }
        return null;
    }

    static JsonPredicate generatePredicate(Randomly r, List<Document> corpus, List<String> untypedPaths) {
        PredicateKind kind = PredicateKind.values()[r.getInteger(0, PredicateKind.values().length)];
        switch (kind) {
        case TYPED_INT_EQ:
            return typedIntEquals(pickTypedIntValue(r, corpus), corpus);
        case TYPED_STR_EQ:
            return typedStrEquals(pickTypedStrValue(r, corpus), corpus);
        case TYPED_INT_IN: {
            int n = 2 + r.getInteger(0, 3);
            List<Long> values = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                values.add(pickTypedIntValue(r, corpus));
            }
            return typedIntIn(values, corpus);
        }
        case UNTYPED_EQ: {

            String path = pickUntypedEqualityPath(r, untypedPaths);
            Leaf known = findKnownLeafFrom(r, corpus, path);
            if (known == null) {

                return untypedEquals(path, LeafKind.STRING, "zz_nomatch");
            }
            return untypedEquals(path, known.getKind(), known.getText());
        }
        case TYPED_STR_IS_NOT_NULL:
            return typedStrIsNotNull();
        case UNTYPED_IS_NOT_NULL:
            return untypedIsNotNull(pickUntypedPath(r, untypedPaths, false));
        case PATH_EXISTS:

            return pathExists(pickUntypedPath(r, untypedPaths, true), corpus);
        default:
            throw new AssertionError(kind);
        }
    }

    static JsonPredicate typedIntEquals(long value, List<Document> corpus) {
        return new JsonPredicate("j." + ClickHouseJsonDocumentGenerator.TYPED_INT_PATH + " = " + value,
                PredicateKind.TYPED_INT_EQ, ClickHouseJsonDocumentGenerator.countWhereTypedIntEquals(corpus, value));
    }

    static JsonPredicate typedStrEquals(String value, List<Document> corpus) {
        return new JsonPredicate(
                "j." + ClickHouseJsonDocumentGenerator.TYPED_STR_PATH + " = '" + escapeStringLiteral(value) + "'",
                PredicateKind.TYPED_STR_EQ, ClickHouseJsonDocumentGenerator.countWhereTypedStrEquals(corpus, value));
    }

    static JsonPredicate typedIntIn(List<Long> values, List<Document> corpus) {
        StringBuilder sb = new StringBuilder("j.").append(ClickHouseJsonDocumentGenerator.TYPED_INT_PATH)
                .append(" IN (");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(values.get(i));
        }
        sb.append(')');
        return new JsonPredicate(sb.toString(), PredicateKind.TYPED_INT_IN,
                ClickHouseJsonDocumentGenerator.countWhereTypedIntIn(corpus, values));
    }

    static JsonPredicate untypedEquals(String path, LeafKind kind, String leafText) {
        String rhs = kind == LeafKind.INT ? leafText : "'" + escapeStringLiteral(leafText) + "'";
        return new JsonPredicate("j." + path + " = " + rhs, PredicateKind.UNTYPED_EQ, -1);
    }

    static JsonPredicate typedStrIsNotNull() {
        return new JsonPredicate("j." + ClickHouseJsonDocumentGenerator.TYPED_STR_PATH + " IS NOT NULL",
                PredicateKind.TYPED_STR_IS_NOT_NULL, -1);
    }

    static JsonPredicate untypedIsNotNull(String path) {
        return new JsonPredicate("j." + path + " IS NOT NULL", PredicateKind.UNTYPED_IS_NOT_NULL, -1);
    }

    static JsonPredicate pathExists(String path, List<Document> corpus) {
        return new JsonPredicate("has(JSONAllPaths(j), '" + escapeStringLiteral(path) + "')",
                PredicateKind.PATH_EXISTS, ClickHouseJsonDocumentGenerator.countWherePathExists(corpus, path));
    }

    static long pickTypedIntValue(Randomly r, List<Document> corpus) {
        Leaf known = r.getInteger(0, 5) == 0 ? null
                : findKnownLeafFrom(r, corpus, ClickHouseJsonDocumentGenerator.TYPED_INT_PATH);
        if (known == null) {
            return 100000 + r.getInteger(0, 1000);
        }
        return Long.parseLong(known.getText());
    }

    static String pickTypedStrValue(Randomly r, List<Document> corpus) {
        Leaf known = r.getInteger(0, 5) == 0 ? null
                : findKnownLeafFrom(r, corpus, ClickHouseJsonDocumentGenerator.TYPED_STR_PATH);
        if (known == null) {
            return "zz_nomatch";
        }
        return known.getText();
    }

    static String pickUntypedEqualityPath(Randomly r, List<String> untypedPaths) {
        List<String> pool = new ArrayList<>(untypedPaths);
        pool.remove(ClickHouseJsonDocumentGenerator.MIXED_TYPE_PATH);
        pool.add(ClickHouseJsonDocumentGenerator.NESTED_PATH);
        return pool.get(r.getInteger(0, pool.size()));
    }

    static String pickUntypedPath(Randomly r, List<String> untypedPaths, boolean includePhantom) {
        List<String> pool = new ArrayList<>(untypedPaths);
        pool.add(ClickHouseJsonDocumentGenerator.NESTED_PATH);
        if (includePhantom) {
            pool.add(ClickHouseJsonDocumentGenerator.PHANTOM_PATH);
        }
        return pool.get(r.getInteger(0, pool.size()));
    }

    private static Leaf findKnownLeafFrom(Randomly r, List<Document> corpus, String path) {
        if (corpus.isEmpty()) {
            return null;
        }

        int start = r.getInteger(0, corpus.size());
        for (int i = 0; i < corpus.size(); i++) {
            Leaf leaf = corpus.get((start + i) % corpus.size()).getLeaf(path);
            if (leaf != null) {
                return leaf;
            }
        }
        return null;
    }

    private static String truncateForMessage(List<String> rows) {
        int limit = 50;
        if (rows.size() <= limit) {
            return rows.toString();
        }
        return rows.subList(0, limit) + "... (" + rows.size() + " total)";
    }

    private String readSingleValue(String query) throws SQLException {
        List<String> rows = ComparatorHelper.getResultSetFirstColumnAsString(query, readErrors, state);
        if (rows.size() != 1) {
            throw new IgnoreMeException();
        }
        return rows.get(0);
    }

    private void logStmt(String stmt) {
        if (state.getOptions().logEachSelect()) {
            state.getLogger().writeCurrent(stmt);
            state.getState().logStatement(stmt);
        }
    }
}
