package sqlancer.clickhouse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import sqlancer.Randomly;

public final class ClickHouseSessionSettings {

    private ClickHouseSessionSettings() {
    }

    // Optimizer-rewrite and runtime-cache settings that SHOULD be result-preserving. The SEMR oracle
    // toggles each entry between "0" and "1" and asserts multiset equality of the base SELECT.
    // Entries hardcoded by other oracles (enable_optimize_predicate_expression and
    // aggregate_functions_null_for_empty -- see ClickHouseTLPHavingOracle.java:42 and
    // ClickHouseTLPAggregateOracle.java:42) are intentionally excluded to keep failure attribution
    // clean when SEMR composes with those oracles.
    //
    // Runtime/cache entries (use_query_condition_cache, use_skip_indexes_on_data_read,
    // use_index_for_in_with_subqueries, optimize_use_implicit_projections) are included because
    // bugs in those code paths are exactly the kind of cross-query-state issue that ClickHouse
    // issue #104781 exposed -- toggling them must not change any single-query result, and a SEMR
    // failure here is the cheapest local signal that the cache or skip-index path is poisoning
    // results.
    public static final List<String> SEMR_SETTINGS = List.of(
            // MergeTree-only: pushdown of WHERE into PREWHERE
            "optimize_move_to_prewhere",
            // skip merge sort if ORDER BY matches sorting key
            "optimize_read_in_order",
            // projection materialization at SELECT time
            "optimize_use_projections",
            // boolean-normalization rewrite of WHERE
            "convert_query_to_cnf",
            // query-plan-level filter pushdown
            "query_plan_filter_push_down",
            // query-condition cache toggle. Default on in 26.x; the regression family that issue
            // #104781 belongs to (silent under-counts after a poisoned cache entry) is exactly
            // this knob. SEMR catches the single-query manifestation; the QccCache oracle catches
            // the cross-query manifestation.
            "use_query_condition_cache",
            // read-time skip-index analysis (#81526, default on per #93407 in 26.x). Sister knob
            // to the query-condition cache -- co-implicated in the reporter's own bisect path.
            "use_skip_indexes_on_data_read",
            // build a set from an IN-with-subquery for skip-index pruning. Result must be invariant.
            "use_index_for_in_with_subqueries",
            // implicit projections (sum/count/min/max on PK prefix). Result must be invariant.
            "optimize_use_implicit_projections",
            // NULL-semantics flip in IN: documented to preserve result equivalence between
            // `x IN (..., NULL)` and `x IN (...)`. Regression #95674 -- result-affecting NULL
            // mishandling -- is exactly what SEMR is positioned to catch.
            "transform_null_in",
            // RIGHT-JOIN late column reads. Regression #94339 (wrong RIGHT JOIN result with this
            // setting on) shows the analyzer + replication interaction is exactly setting-poisoned.
            "lazy_columns_replication",
            // JIT compilation of scalar expressions. The 26.4-26.5 cluster of JIT-Decimal bugs
            // (#103809, #105054) is a hot regression area; toggling between JIT and interpreter
            // surfaces compiled-vs-interpreted divergence as a SEMR failure rather than relying on
            // workload-driven discovery. Note: also present in RANDOM_SESSION_SETTINGS for the
            // execution-mode picker, but SEMR's per-query comparison is what asserts equivalence.
            "compile_expressions", "compile_aggregate_expressions",
            // Aggregator constant-folding over GROUP BY keys. Result must be invariant; included
            // pre-emptively for the same family of analyzer-bound rewrites as #94339.
            "optimize_aggregators_of_group_by_keys",
            // Trivial count(*) -> read part rows. The optimized-trivial-count code path was the
            // home of #100794 (wrong AggregateFunction type signature with mixed integer widths).
            "optimize_trivial_count_query",
            // Distributed shard skipping; default-on. ClickHouse#92375 reports a wrong DISTINCT
            // result when this fires against a sharded table; SEMR catches the single-query
            // manifestation by toggling the flag against the same SELECT.
            "optimize_skip_unused_shards",
            // Function-to-subcolumn rewrite (length(arr) -> arr.size0 etc.). ClickHouse#85633 +
            // #101271 are the head of this family; both shipped a regression test that is
            // structurally identical to what SEMR diffs.
            "optimize_functions_to_subcolumns",
            // Regexp rewrite optimizer. ClickHouse#93434 shipped result divergence when this
            // setting flipped on; SEMR is the canonical local signal for any future regression.
            "optimize_rewrite_regexp_functions",
            // Logical join step in the new query plan. Default-on in 26.x; the LEFT ANY case
            // (ClickHouse#99431, P0 in the report) reproduces under SEMR by toggling this flag
            // against a query that contains a JOIN.
            "query_plan_use_logical_join_step",
            // Text-index pruning. ClickHouse#103812 -- wrong result when text-index direct read
            // is combined with the hint-add flag. SEMR per-query toggle pair plus the dedicated
            // text-index settings group below cover both single-flag and combined exposures.
            "query_plan_direct_read_from_text_index", "query_plan_text_index_add_hint",
            // Read-in-order buffering layer; private#35000 (parallel replicas reverse order)
            // is the most recent regression and is exactly the kind of result-affecting reordering
            // that SEMR's multiset comparison catches.
            "read_in_order_use_buffering",
            // JIT scalar sort path. Sister of compile_expressions / compile_aggregate_expressions
            // already in this list; folded in to extend JIT-pair coverage with no extra plumbing.
            "compile_sort_description",
            // Statistics-based query rewrites. The young CH statistics subsystem (24.5+) carries
            // result-divergence risk under predicate selectivity estimation; toggling the optimizer
            // opt-in exercises the rewrite path against the same SELECT. `allow_statistic_optimize`
            // is the historical typo alias (still accepted by CH HEAD) and is included so SEMR
            // covers both spellings.
            "allow_statistics_optimize", "allow_statistic_optimize",
            // apply_mutations_on_fly REMOVED from SEMR pool: it's NOT result-preserving when
            // there are pending mutations. With the setting on, SELECT applies the pending
            // mutations virtually; with it off, SELECT reads the pre-mutation view. A run with
            // an ALTER DELETE / UPDATE in flight will show DIFFERENT row sets between the
            // toggle positions -- correctly so. The plan's classification of this setting as
            // "result-preserving" was wrong. SEMR catches the divergence as a false positive.
            // The 8.7h run surfaced 3 SEMR reproducers from this single cause. NOT a CH bug.
            // FINAL behaviour differs when do_not_merge_across_partitions_select_final is on --
            // it skips merging across partitions, which can change the deduped row set. SEMR
            // toggling this against the same SELECT FINAL surfaces the cross-partition merge
            // path's invariants. Workstream 10 of the plan.
            "do_not_merge_across_partitions_select_final");

    // Execution-mode settings the random-session-settings layer may apply via
    // SET k = v at connect time. Each entry has discrete candidate values picked
    // uniformly. Optimizer-rewrite settings are deliberately NOT in this list --
    // they live in SEMR_SETTINGS only, because CERT's cardinality-monotonicity
    // invariant and CODDTest's constant-folding invariant depend on the optimizer
    // rewrite pipeline being stable across a run.
    public static final List<RandomEntry> RANDOM_SESSION_SETTINGS = List.of(
            // execution parallelism; no documented result-affecting behavior
            new RandomEntry("max_threads", List.of("1", "2", "4", "8")),
            // processing chunk size; affects throughput, not result content
            new RandomEntry("max_block_size", List.of("1024", "8192", "65536")),
            // read buffer size; pure I/O tuning
            new RandomEntry("max_read_buffer_size", List.of("1048576", "8388608")),
            // insert block size; affects part layout, not result content
            new RandomEntry("min_insert_block_size_rows", List.of("1024", "1048576")),
            // compressed block sizing; affects part layout, not result content
            new RandomEntry("min_compress_block_size", List.of("4096", "65536", "262144")),
            new RandomEntry("max_compress_block_size", List.of("65536", "1048576", "4194304")),
            // preferred read block size in bytes; pure I/O tuning
            new RandomEntry("preferred_block_size_bytes", List.of("65536", "1000000", "8388608")),
            // forces two-level GROUP BY at different cardinalities; execution strategy only
            new RandomEntry("group_by_two_level_threshold", List.of("1", "1000", "100000")),
            // JIT-compilation toggles; same compiled-output semantics, different code path
            new RandomEntry("compile_expressions", List.of("0", "1")),
            new RandomEntry("compile_aggregate_expressions", List.of("0", "1")),
            new RandomEntry("compile_sort_description", List.of("0", "1")),
            // JIT trigger threshold; 0 forces always-JIT, 3 is default
            new RandomEntry("min_count_to_compile_expression", List.of("0", "3")));

    // Settings already controlled by ClickHouseOptions CLI flags. Both pickers
    // filter against this set defensively even though the lists above already
    // exclude these names -- the defense net protects against future drift in
    // either the options or the catalog.
    public static final Set<String> MANAGED_BY_OPTIONS = Set.of(
            // --analyzer
            "allow_experimental_analyzer",
            // --test-lowcardinality-types
            "allow_suspicious_low_cardinality_types",
            // Pinned 0 server-side (.claude/clickhouse-config/async_insert_off.xml). async_insert
            // defers INSERT commit, breaking the SELECT-after-INSERT snapshot invariant every
            // oracle assumes. If any future addition lands this name in RANDOM_SESSION_SETTINGS
            // by accident, the filter in pickRandomProfile() will drop it on the floor.
            "async_insert");

    public record RandomEntry(String name, List<String> candidateValues) {
    }

    public record SemrCandidate(String name, String valueOff, String valueOn) {
    }

    public static SemrCandidate pickSemrCandidate(Randomly r) {
        if (SEMR_SETTINGS.isEmpty()) {
            throw new IllegalStateException("SEMR_SETTINGS is empty -- configuration bug");
        }
        String name = Randomly.fromList(SEMR_SETTINGS);
        return new SemrCandidate(name, "0", "1");
    }

    // Pick `arity` distinct SEMR settings to toggle together. The multi-SEMR oracle uses this to
    // build a (k-bit) corner of the optimizer-setting hypercube and diff it against the all-zero
    // corner. Arity 1 degenerates to the single-setting oracle; arity >= 2 catches optimizer-pass
    // interactions that the 1-flag SEMR cannot reach (e.g., analyzer x subcolumn rewrite x
    // filter-pushdown, which is the #100029 / #93483 cluster).
    public static List<SemrCandidate> pickSemrCandidates(Randomly r, int arity) {
        if (arity <= 0) {
            throw new IllegalArgumentException("SEMR arity must be >= 1; got " + arity);
        }
        if (SEMR_SETTINGS.isEmpty()) {
            throw new IllegalStateException("SEMR_SETTINGS is empty -- configuration bug");
        }
        int n = Math.min(arity, SEMR_SETTINGS.size());
        // Randomly.extractNrRandomColumns is the in-tree helper for sample-without-replacement; it
        // takes any List and a count and returns a fresh List of distinct elements.
        List<String> picked = Randomly.extractNrRandomColumns(SEMR_SETTINGS, n);
        return picked.stream().map(name -> new SemrCandidate(name, "0", "1")).toList();
    }

    public static LinkedHashMap<String, String> pickRandomProfile(Randomly r, int budget) {
        if (budget < 0) {
            throw new IllegalArgumentException(
                    "--random-session-settings-budget must be >= 0 (0 means unbounded); got " + budget);
        }
        List<RandomEntry> eligible = RANDOM_SESSION_SETTINGS.stream()
                .filter(e -> !MANAGED_BY_OPTIONS.contains(e.name())).toList();
        int max = budget == 0 ? eligible.size() : Math.min(budget, eligible.size());
        int count = max == 0 ? 0 : (int) Randomly.getNotCachedInteger(0, max + 1);
        List<RandomEntry> chosen = Randomly.extractNrRandomColumns(eligible, count);
        LinkedHashMap<String, String> profile = new LinkedHashMap<>();
        for (RandomEntry entry : chosen) {
            profile.put(entry.name(), Randomly.fromList(entry.candidateValues()));
        }
        return profile;
    }

}
