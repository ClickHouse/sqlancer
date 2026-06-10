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

/**
 * JSON skip-index on/off oracle: exercises the 26.4 JSON skip indexes -- bloom_filter / tokenbf_v1 / ngrambf_v1 /
 * text over {@code JSONAllPaths(json)} (PR #98886) and {@code JSONAllValues(json)} under a text index (PR #100730,
 * returns {@code Array(String)} of leaf values; auto-used for subcolumn predicates). The bug class is
 * <b>false-negative granule skipping on JSON path predicates</b>: the index wrongly skips a granule whose documents
 * match -- in particular around PR #98886's skip-avoidance rule for documents where the predicated path is
 * <b>absent</b> (absent path reads as default/NULL; the index must not skip those granules). A pure two-indexed-paths
 * comparison cannot see this, so the oracle compares the indexed path against the same query with the index disabled.
 *
 * <p>
 * Self-contained (Shape C, MutationAnalyzer template; arm machinery mirrors the Unit 1 text-index oracle): each
 * {@code check()} creates a private AtomicLong-suffixed plain-MergeTree table
 * {@code <db>.jsidx_<id>_t (k UInt32, j JSON(p_int Int64, p_str String))} with ONE index variant from the matrix
 * ({@code INDEX jx JSONAllPaths(j) TYPE bloom_filter(0.01) | tokenbf_v1(256, 2, 0) | ngrambf_v1(3, 256, 2, 0) |
 * text(tokenizer = 'splitByNonAlpha')}, or {@code INDEX jx JSONAllValues(j) TYPE text(tokenizer = 'splitByNonAlpha')},
 * all GRANULARITY 1), a deliberately small {@code index_granularity} (4-8), and 3-5 separate INSERT blocks of 30-80
 * documents from {@link ClickHouseJsonDocumentGenerator} so multiple parts and granules exist and granule skipping is
 * observable. The generator's corpus is kept in Java, so expected counts are computable for the predicate kinds whose
 * semantics are unambiguous (see below).
 *
 * <p>
 * Per iteration ONE predicate is drawn: typed-path equality ({@code j.p_int = <known leaf>} /
 * {@code j.p_str = '<known leaf>'}), an IN-list on the typed Int64 path (pairs with bloom_filter), untyped-path
 * equality ({@code j.u1 = '<known leaf>'} -- untyped access yields Dynamic, fine in WHERE, never projected),
 * {@code IS NOT NULL} on the typed String path or an untyped path, or path existence
 * {@code has(JSONAllPaths(j), '<path>')} over paths present in 0 / some / all rows (phantom path, optional untyped
 * paths, nested {@code n.a.b.c}). Ground truth is asserted for typed equality, typed IN, and untyped path-existence
 * ({@link #isGroundTruthComputable(PredicateKind)} -- a structural property of the kind, never hand-set per
 * predicate); untyped equality and IS-NOT-NULL are arms-agreement-only (Dynamic comparison semantics and
 * absent-typed-path NULL-vs-default semantics are deliberately not modelled in Java).
 *
 * <p>
 * <b>Read envelope (R4):</b> every projection is {@code count()}, the key column {@code k}, the declared typed
 * subcolumns {@code j.p_int} / {@code j.p_str} (concrete Int64/String), or {@code JSONAllPaths(j)} collapsed through
 * {@code arrayStringConcat} (the function itself returns the decodable {@code Array(String)}). Raw {@code j} is never
 * projected and untyped paths are never projected (both read as types the client-v2 RowBinary reader cannot decode --
 * JSON/Dynamic) -- which is exactly why this oracle needs <b>no new reader capabilities</b> and does not wait on
 * roadmap Unit 4.0.
 *
 * <p>
 * Two comparison arms, each issued as {@code SELECT count()}, {@code SELECT k ... ORDER BY k}, and a typed-subcolumn
 * row image {@code SELECT concat(k, '|', j.p_int, '|', j.p_str, '|', JSONAllPaths...) ... ORDER BY k}:
 * <ol>
 * <li>default settings (index eligible),</li>
 * <li>{@code SETTINGS ignore_data_skipping_indices = 'jx'} (index off -- same data, same parts, no DDL churn).</li>
 * </ol>
 * Arms must agree (string-compared count, positionally-compared ordered lists -- both ends are {@code ORDER BY k}
 * over a non-nullable key, so no Java-side sorting happens). Where ground truth applies, the count must also equal
 * the Java-computed expectation (the all-arms-wrong class a pure differential cannot see).
 *
 * <p>
 * <b>Vacuity guard:</b> every ~10th iteration runs ONE extra known-selective count query with
 * {@code SETTINGS force_data_skipping_indices = 'jx'}: per correction #2 of the 26.x plan, that setting does not
 * force anything -- it <b>fails with INDEX_NOT_USED</b> when the named index did not participate. A failed probe is
 * "feature not engaged" (IgnoreMe, counter untouched); a successful probe bumps the lifetime engagement counter
 * ({@link #getIndexEngagedCount()}) that the convergence run inspects. INDEX_NOT_USED is tolerated ONLY on the
 * probe's ExpectedErrors -- never on the comparison arms and never globally.
 *
 * <p>
 * Error layering (MutationAnalyzer/Unit 1 precedent): one narrow read set for INSERT/SELECT/DROP (session settings on
 * older builds, drop/recreate races, load-shed memory/timeout); CREATE additionally tolerates the JSON-feature and
 * index-acceptance gating families (each string commented; the exact accepted index/expression combos are
 * probe-pending against head, so these CREATE-only tolerances are reviewed for tightening after the first convergence
 * run) -- a tolerated CREATE/INSERT failure abandons the iteration via {@link IgnoreMeException}. Index/JSON gating
 * is <b>never</b> tolerated at SELECT: an arm unexpectedly erroring is a finding. Tables are dropped in
 * {@code finally} catching {@code Exception | AssertionError}.
 */
public class ClickHouseJsonSkipIndexOracle implements TestOracle<ClickHouseGlobalState> {

    private static final AtomicLong JSIDX_COUNTER = new AtomicLong();
    // Lifetime count of successful force_data_skipping_indices probes: proves the JSON skip index
    // actually participates in granule filtering at least sometimes (anti-vacuity evidence for the
    // convergence run). Deliberately NOT incremented when the probe fails with INDEX_NOT_USED.
    private static final AtomicLong INDEX_ENGAGED = new AtomicLong();
    // Run the vacuity probe on every PROBE_EVERY-th iteration, not per predicate (plan Unit 7).
    private static final long PROBE_EVERY = 10;

    static final String INDEX_NAME = "jx";

    /**
     * The per-iteration index matrix (plan Unit 7): four index types over {@code JSONAllPaths(j)} plus the
     * {@code JSONAllValues(j)} text index. Which combos head accepts is probe-pending -- rejections are tolerated
     * narrowly at CREATE and IgnoreMe-skip the iteration.
     */
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
        DEFAULT(""), // (a) index eligible
        INDEX_IGNORED(" SETTINGS ignore_data_skipping_indices = '" + INDEX_NAME + "'"); // (b)

        private final String settingsSuffix;

        Arm(String settingsSuffix) {
            this.settingsSuffix = settingsSuffix;
        }

        String getSettingsSuffix() {
            return settingsSuffix;
        }
    }

    enum PredicateKind {
        TYPED_INT_EQ, // j.p_int = <v>: exact ground truth
        TYPED_STR_EQ, // j.p_str = '<v>': exact ground truth
        TYPED_INT_IN, // j.p_int IN (...): exact ground truth; pairs with bloom_filter
        UNTYPED_EQ, // j.u<i> = <v>: Dynamic comparison, agreement-only, WHERE-side only
        TYPED_STR_IS_NOT_NULL, // absent typed path: NULL-vs-default semantics unmodelled, agreement-only
        UNTYPED_IS_NOT_NULL, // agreement-only
        PATH_EXISTS // has(JSONAllPaths(j), '<path>') on untyped paths: exact ground truth
    }

    /**
     * One generated WHERE predicate. Ground-truth computability is a structural property of the kind
     * ({@link #isGroundTruthComputable(PredicateKind)}), never hand-set, so a future kind cannot accidentally claim a
     * ground truth it does not have; {@code expectedCount} is meaningful only when the kind is computable.
     */
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
    // Narrow tolerance, MutationAnalyzer layering:
    //   createErrors -- CREATE TABLE only: readErrors plus the JSON-type and index-acceptance
    //                   gating families below. A tolerated CREATE failure means "feature absent /
    //                   combo rejected on this build" -> IgnoreMe, never a reproducer.
    //   readErrors   -- INSERT / SELECT / DROP. Every statement is hand-built static SQL over two
    //                   columns; generic analyzer noise can only mean a server bug and must
    //                   surface. Index/JSON gating is deliberately NOT here: an arm erroring with
    //                   an index message is exactly the bug-shaped signal this oracle exists for.
    //   probeErrors  -- the force_data_skipping_indices vacuity probe ONLY: readErrors plus
    //                   INDEX_NOT_USED.
    private final ExpectedErrors createErrors = new ExpectedErrors();
    private final ExpectedErrors readErrors = new ExpectedErrors();
    private final ExpectedErrors probeErrors = new ExpectedErrors();

    public ClickHouseJsonSkipIndexOracle(ClickHouseGlobalState state) {
        this.state = state;
        for (ExpectedErrors e : List.of(createErrors, readErrors, probeErrors)) {
            // Unknown setting names on older builds: keeps arm (b)'s ignore_data_skipping_indices
            // and the probe's force_data_skipping_indices runnable against older images -- the
            // iteration degrades to IgnoreMe instead of a false reproducer.
            ClickHouseErrors.addSessionSettingsErrors(e);
            // Per-thread database drop/recreate race (MutationAnalyzer/MV precedent): reads hitting
            // a dropped namespace are not JSON-index bugs.
            e.add("UNKNOWN_TABLE");
            e.add("Unknown table expression identifier");
            // Code 241 load-shedding under the squeezed dev-vm container cap (-m=6g): any statement
            // -- including the finally-DROP -- can be rejected at the cgroup limit. Environment
            // artifact, not an index bug.
            e.add("(MEMORY_LIMIT_EXCEEDED)");
            e.add("memory limit exceeded");
            // Benign load-shed timeout on a squeezed server: no deadlock class hides behind a
            // timeout here (Unit 1 precedent), so it is tolerated on every statement rather than
            // producing misleading SELECT reproducers.
            e.add("TIMEOUT_EXCEEDED");
            e.add("Timeout exceeded");
        }
        // --- CREATE-only gating, each string narrow and message-anchored. The exact set of index
        // type x JSONAllPaths/JSONAllValues combos head accepts is probe-pending; every string here
        // is reviewed for tightening (or removal) after the first convergence run. ---
        // JSON type gating on older images: pre-26 builds reject the type outright ("Unknown data
        // type family: JSON"), 24.x/25.x builds gate it behind the experimental flag and name it in
        // the SUPPORT_IS_DISABLED-class message.
        createErrors.add("allow_experimental_json_type");
        createErrors.add("Experimental JSON type");
        createErrors.add("Unknown data type");
        // Skip-index acceptance gating: builds that predate the `text` index type / the tokenizer
        // named-arg grammar / index expressions over JSONAllPaths-JSONAllValues (both casings of
        // the MergeTreeIndexFactory rejection kept until the dev-vm smoke pins one).
        createErrors.add("Unknown Index type");
        createErrors.add("Unknown index type");
        createErrors.add("Unknown tokenizer");
        createErrors.add("Unknown function JSONAllPaths");
        createErrors.add("Unknown function JSONAllValues");
        // Experimental/beta gating family for the text-index variants (enable_full_text_index
        // defaults to true on head; older builds reject with a SUPPORT_IS_DISABLED-class message
        // naming the full-text index feature).
        createErrors.add("full-text index");
        createErrors.add("full_text_index");
        createErrors.add("SUPPORT_IS_DISABLED");
        // bloom_filter/tokenbf/ngrambf type-acceptance rejection over an Array(String) index
        // expression on builds without PR #98886 ("Unexpected type ... of bloom filter index"
        // family; probe-pending exact wording).
        createErrors.add("of bloom filter index");
        // Probe-only: force_data_skipping_indices FAILS the query with INDEX_NOT_USED when the
        // named index did not participate (plan correction #2). On the probe that means "feature
        // not engaged" (IgnoreMe); on any comparison arm it would be a finding, so it stays off
        // readErrors and off the global list.
        probeErrors.add("INDEX_NOT_USED");
    }

    @Override
    public void check() throws SQLException {
        long id = JSIDX_COUNTER.incrementAndGet();
        String table = state.getDatabaseName() + ".jsidx_" + id + "_t";
        Randomly r = state.getRandomly();

        IndexVariant variant = IndexVariant.values()[r.getInteger(0, IndexVariant.values().length)];
        int indexGranularity = 4 + r.getInteger(0, 5); // 4..8: small so several granules exist
        String create = renderCreateTable(table, variant, indexGranularity);

        ClickHouseJsonDocumentGenerator docGen = new ClickHouseJsonDocumentGenerator(r);
        // Small-probability empty-table edge: all arms must agree on 0 matches with zero parts.
        boolean emptyTable = Randomly.getBooleanWithSmallProbability();
        List<Document> corpus = new ArrayList<>();

        try {
            logStmt(create);
            if (!new SQLQueryAdapter(create, createErrors, true).execute(state)) {
                throw new IgnoreMeException();
            }
            if (!emptyTable) {
                // 3-5 separate INSERT blocks of 30-80 documents each: multiple parts + multiple
                // granules per part (index_granularity 4-8), so granule skipping is observable.
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

            // Ground truth: the generator built the documents, so the expected count is exact for
            // typed equality / typed IN / untyped path existence; a mismatch against the DEFAULT
            // arm means even the agreed-on answer is wrong (the all-arms-wrong class a pure
            // differential cannot see).
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

            // Vacuity guard, every ~10th iteration: prove the index can engage at all.
            // INDEX_NOT_USED is tolerated on this probe only -> IgnoreMe without touching the
            // engagement counter.
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
                // Best effort, MutationAnalyzer precedent: SQLQueryAdapter.execute() throws an
                // AssertionError (not an exception) on an untolerated error, and a DROP failure
                // must neither mask the real finding propagating out of the try block nor write a
                // misleading DROP reproducer. The disk-cleanup script reaps orphans.
            }
        }
    }

    /** Lifetime count of successful vacuity probes; the convergence run asserts this is &gt; 0. */
    static long getIndexEngagedCount() {
        return INDEX_ENGAGED.get();
    }

    // --- static helpers, package-private for the DB-free unit tests ---

    // Escapes a fragment for embedding in a single-quoted ClickHouse string literal. Leaf values
    // and paths are alphanumeric(+dots) by construction, but the helper keeps the invariant
    // explicit. Backslash first, then quote.
    static String escapeStringLiteral(String s) {
        return s.replace("\\", "\\\\").replace("'", "\\'");
    }

    // Ground truth is a structural property of the predicate kind: typed-path equality/IN compare
    // concrete Int64/String subcolumns against generator-known leaves (sound under both NULL and
    // type-default semantics for absent paths, because generated leaves never equal the defaults
    // -- see the generator's default-sentinel guarantee); PATH_EXISTS is modelled by the corpus map
    // (untyped paths only). UNTYPED_EQ (Dynamic comparison) and the IS NOT NULL kinds
    // (NULL-vs-default for absent paths) are deliberately NOT modelled in Java.
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

    // Typed-subcolumn row image: key + both declared typed paths + the JSONAllPaths list, collapsed
    // into one String per row. Stays strictly inside the R4 read envelope -- j.p_int/j.p_str are
    // concrete Int64/String subcolumns, JSONAllPaths(j) is Array(String) (collapsed server-side via
    // arrayStringConcat so the projected column is a plain String); raw `j` and untyped paths are
    // never projected.
    static String renderTypedRowImageQuery(String table, JsonPredicate predicate, Arm arm) {
        return "SELECT concat(toString(k), '|', toString(j." + ClickHouseJsonDocumentGenerator.TYPED_INT_PATH
                + "), '|', toString(j." + ClickHouseJsonDocumentGenerator.TYPED_STR_PATH
                + "), '|', arrayStringConcat(JSONAllPaths(j), ';')) FROM " + table + " WHERE "
                + predicate.getWhereSql() + " ORDER BY k" + arm.getSettingsSuffix();
    }

    // Known-selective probe predicate for the vacuity guard, matched to what the index variant
    // plausibly accelerates (probe-pending: PR #100730 documents JSONAllValues text indexes as
    // auto-used for subcolumn predicates; JSONAllPaths indexes serve path-existence). Returns null
    // when the corpus has no usable document (e.g. all-empty corpus) -- the caller then skips the
    // probe for this iteration.
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
            int n = 2 + r.getInteger(0, 3); // 2-4 values, mixing known and non-occurring
            List<Long> values = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                values.add(pickTypedIntValue(r, corpus));
            }
            return typedIntIn(values, corpus);
        }
        case UNTYPED_EQ: {
            String path = pickUntypedPath(r, untypedPaths, false);
            Leaf known = findKnownLeafFrom(r, corpus, path);
            if (known == null) {
                // No document carries the path: a literal that matches nothing (still well-typed).
                return untypedEquals(path, LeafKind.STRING, "zz_nomatch");
            }
            return untypedEquals(path, known.getKind(), known.getText());
        }
        case TYPED_STR_IS_NOT_NULL:
            return typedStrIsNotNull();
        case UNTYPED_IS_NOT_NULL:
            return untypedIsNotNull(pickUntypedPath(r, untypedPaths, false));
        case PATH_EXISTS:
            // 0 / some / all selectivity comes from the path choice: the phantom path matches 0
            // rows, optional untyped paths match some, and frequent untyped paths approach all.
            return pathExists(pickUntypedPath(r, untypedPaths, true), corpus);
        default:
            throw new AssertionError(kind);
        }
    }

    // --- predicate factories (package-private for tests) ---

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

    // Untyped-path access yields Dynamic: legal in WHERE, never projected; comparison semantics are
    // deliberately unmodelled (agreement-only).
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

    // --- value/path pickers ---

    // 4/5 a leaf that actually occurs in the corpus (predicates should usually match something),
    // else a value the generator can never produce (zero-match edge; never the Int64 default 0).
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
            return "zz_nomatch"; // never generated; non-empty so the default-sentinel guarantee holds
        }
        return known.getText();
    }

    // Existence predicates additionally draw the nested path and the never-emitted phantom path
    // (the 0-rows end of the 0/some/all selectivity span).
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
        // Random starting point, first document carrying the path from there (cheap, deterministic
        // given the seed).
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
