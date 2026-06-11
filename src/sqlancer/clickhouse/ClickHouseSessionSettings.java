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
            // NB: the in-server DECLARE is enable_lazy_columns_replication; the bare name
            // "lazy_columns_replication" is not an alias and raises UNKNOWN_SETTING, which the
            // session-settings tolerance absorbs SILENTLY (2026-06-11 fix -- a bare entry here
            // meant the #94339 coverage never ran; pinned by knownBadNamesStayOutOfTheCatalogs).
            "enable_lazy_columns_replication",
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
            // Gates loading statistics objects at read time at all (26.4+, default true), while
            // allow_statistics_optimize above gates the optimizer consuming them. The dedicated
            // StatsToggle oracle owns the stats-staleness interplay SEMR cannot construct; this
            // blanket toggle adds the free cross-product coverage. 26.x plan Unit 9.
            "use_statistics",
            // Text-index LIKE evaluation via dictionary scan (26.4 PR #98149, default on).
            // Result-preserving by contract; the TextIndexLike oracle owns the dedicated
            // token-corpus differential. 26.x plan Unit 1.
            "use_text_index_like_evaluation_by_dictionary_scan",
            // Top-k dynamic threshold filter pushed into the scan (26.5 default-on, PR #99537).
            // Result must be invariant; the TopK oracle covers the dedicated ORDER BY+LIMIT shape.
            "use_top_k_dynamic_filtering",
            // minmax skip-index granule pruning against the top-k threshold (PR #104216). Sister
            // knob of the above. 26.x plan Unit 2.
            "use_skip_indexes_for_top_k",
            // Push the top-k step below a join (26.5 default-on, PR #104268). 26.x plan Unit 2.
            "query_plan_top_k_through_join",
            // ---- Bulk result-preserving additions, 2026-06-11 (tmp/ch-settings-to-test-in-sqlancer.md
            // section 2). Every name validated against Settings.cpp @ ca5c93df695; all are documented
            // optimizer-rewrite / execution-detail toggles whose 0-vs-1 flip must be multiset-invariant.
            // Grouped by optimizer subsystem so a SEMR reproducer points at a code area.
            //
            // (2A) Query-plan rewrites. query_plan_enable_optimizations is the master switch: 0-vs-1
            // diffs the fully-optimized plan against the naive plan in one shot -- the strongest single
            // metamorphic relation CH offers (NoREC-style optimized-vs-unoptimized split for free).
            "query_plan_enable_optimizations", "query_plan_merge_filters", "query_plan_split_filter",
            "query_plan_merge_expressions", "query_plan_push_down_limit", "query_plan_lift_up_union",
            "query_plan_lift_up_array_join", "query_plan_remove_redundant_distinct",
            "query_plan_remove_redundant_sorting", "query_plan_remove_unused_columns", "query_plan_read_in_order",
            "query_plan_aggregation_in_order", "query_plan_optimize_prewhere",
            "query_plan_optimize_lazy_materialization", "query_plan_execute_functions_after_sorting",
            "query_plan_enable_multithreading_after_window_functions",
            // 26.4, default false: ReplacingMergeTree FINAL lazy-read path -- FINAL-bug-adjacent.
            "query_plan_optimize_lazy_final",
            // 26.6, default true: LIMIT BY pushed into sort.
            "query_plan_push_limit_by_into_sort",
            // (2B) JOIN planning / reordering -- the JoinOrderOptimizer surface that produced #107073
            // and #106426. Toggled under the same generated-JOIN queries the JoinReorder/JoinAlgorithm
            // oracles exercise structurally.
            "query_plan_convert_outer_join_to_inner_join", "query_plan_convert_any_join_to_semi_or_anti_join",
            "query_plan_merge_filter_into_join_condition",
            "query_plan_read_in_order_through_join", "query_plan_join_shard_by_pk_ranges",
            // 26.1 default-on flip.
            "use_join_disjunctions_push_down", "use_hash_table_stats_for_join_reordering",
            "allow_general_join_planning",
            // 26.2 / 26.6 / 26.4 / 26.5 default-on additions -- newest join-planning code.
            "enable_join_runtime_filters", "enable_join_transitive_predicates",
            "enable_join_fixed_hash_table_conversion", "enable_software_prefetch_in_join",
            // (2C) Expression / aggregate rewrites. optimize_arithmetic_operations_in_aggregate_functions
            // is deliberately ABSENT: it reorders float arithmetic inside aggregates and would drown the
            // run in float-ULP false positives (see the TLPGroupBy authoring rule in CLAUDE.md).
            "optimize_multiif_to_if", "optimize_if_chain_to_multiif", "optimize_normalize_count_variants",
            "optimize_rewrite_sum_if_to_count_if", "optimize_rewrite_aggregate_function_with_if",
            "optimize_uniq_to_count", "optimize_injective_functions_in_group_by",
            "optimize_injective_functions_inside_uniq", "optimize_group_by_function_keys",
            "optimize_group_by_constant_keys", "optimize_aggregation_in_order", "optimize_distinct_in_order",
            "optimize_redundant_functions_in_order_by", "optimize_respect_aliases",
            "optimize_extract_common_expressions", "optimize_and_compare_chain",
            "optimize_rewrite_like_perfect_affix", "optimize_or_like_chain",
            "optimize_sorting_by_input_stream_properties",
            // toStartOf*/toYYYYMM preimage rewrite -- the #106419 family lives here.
            "optimize_time_filter_with_preimage",
            // 26.3-26.6 default-on additions (sum/avg/count fusion, has()/IN rewrites, ORDER BY
            // truncation after GROUP BY, GROUP BY ... LIMIT short-circuit, dictGet tuple element).
            "optimize_syntax_fuse_functions", "optimize_rewrite_array_exists_to_has", "optimize_rewrite_has_to_in",
            "optimize_truncate_order_by_after_group_by_keys", "optimize_trivial_group_by_limit_query",
            "optimize_dictget_tuple_element",
            // (2D) Index / pruning / projection. use_primary_key=0 / use_skip_indexes=0 force the
            // full-scan ground-truth path -- the same diff that caught #106262 (sqrt-NaN KeyCondition)
            // and #106124 (negative-intDiv partition pruning), now systematic.
            // use_skip_indexes_if_final is safe ONLY because use_skip_indexes_if_final_exact_mode
            // stays at its server default (1): if_final=1 + exact_mode=1 is documented-correct,
            // if_final=0 skips the index entirely. exact_mode itself is deliberately NOT listed --
            // its own doc says 0 may return approximate FINAL results ("should be disabled only if
            // approximate results ... are okay"), i.e. toggling it is result-CHANGING by contract.
            "use_primary_key", "use_skip_indexes", "use_skip_indexes_for_disjunctions", "use_skip_indexes_if_final",
            // 26.4 default-on statistics-based part pruning.
            "use_statistics_for_part_pruning",
            // 26.5 default-on: coalesce()/ifNull() folded into KeyCondition -- index correctness, high risk.
            "allow_key_condition_coalesce_rewrite", "optimize_use_projection_filtering", "optimize_append_index",
            "materialize_skip_indexes_on_insert",
            // (2E) FINAL / merge correctness (pairs with the FinalMerge oracle). A
            // defer_partition_pruning_after_final 0-vs-1 mismatch IS the #98242 regression shape.
            "defer_partition_pruning_after_final", "optimize_move_to_prewhere_if_final", "enable_vertical_final",
            // Unlike do_not_merge_across_partitions_select_final (removed from SEMR 2026-06-01, see the
            // block comment below), the automatic-decision knob applies the cross-partition skip only
            // when the safety precondition holds, so it is result-preserving by contract.
            "enable_automatic_decision_for_merging_across_partitions_for_final",
            "split_intersecting_parts_ranges_into_layers_final",
            "split_parts_ranges_into_intersecting_and_non_intersecting_final",
            // (2F) Predicate pushdown / constant folding / read-in-order. The
            // enable_optimize_predicate_expression sibling stays excluded (TLPHaving pins it); the
            // _to_final_subquery variant is independent and free to toggle.
            "enable_early_constant_folding", "enable_multiple_prewhere_read_steps",
            "enable_optimize_predicate_expression_to_final_subquery", "allow_reorder_prewhere_conditions",
            "allow_push_predicate_when_subquery_contains_with",
            // 26.6, default false: second PREWHERE promotion pass after pushdown -- brand new.
            "optimize_prewhere_after_pushdown",
            // 26.4, default false: virtual-row read-in-order optimization.
            "read_in_order_use_virtual_row", "read_in_order_use_virtual_row_per_block", "rewrite_in_to_join",
            "enable_add_distinct_to_in_subqueries", "enable_scalar_subquery_optimization");
    // Settings deliberately NOT in SEMR_SETTINGS because they are NOT result-preserving on
    // arbitrary schemas (toggling them legitimately changes the result, so SEMR would report
    // false positives), or because toggling them is a no-op:
    // - query_plan_use_logical_join_step (removed 2026-06-11): MAKE_OBSOLETE as of 26.5 ("the
    // logical join step is now always used"), so toggling it stopped doing anything. The live
    // join-plan surface is the query_plan_convert_* / enable_join_* block above.
    // - query_plan_convert_join_to_in (removed 2026-06-11, same day it was added): its own
    // catalog doc says "May cause wrong results with non-ANY JOINs (e.g. ALL JOINs which is
    // the default)" -- converting an ALL JOIN to IN collapses row multiplicity (a self-join on
    // duplicate keys returns N rows instead of N^2 per key group). The first focused smoke run
    // caught it within 15 minutes as 3 SEMR/SEMRMulti cardinality mismatches (50 vs 10 etc.),
    // confirmed by hand on head 26.6.1.634: count() 13 vs 5 on a 5-row dup-key self-join.
    // Documented result-CHANGING by contract, not a CH bug; do not re-add.
    // - apply_mutations_on_fly: with it on, SELECT applies pending ALTER DELETE/UPDATE
    // mutations virtually; with it off, SELECT reads the pre-mutation view. With a mutation
    // in flight the row sets differ -- correctly. (3 SEMR reproducers in the 8.7h run.)
    // - do_not_merge_across_partitions_select_final (removed 2026-06-01): when the partition
    // key is not a prefix of the sorting key, the same ORDER BY key spans multiple
    // partitions; with the setting ON, FINAL skips the cross-partition merge so duplicate
    // keys survive, changing the deduped row COUNT. Proven on CH 26.6.1.284 with
    // SummingMergeTree ORDER BY c0 PARTITION BY (c1+c2): SELECT ... FROM t FINAL returned 1
    // row with the setting off and 4 with it on. CH documents it as safe only when the
    // partition key is a prefix of the sort key -- a precondition SEMR's random tables don't
    // meet. Surfaced as a SEMRMulti size-mismatch (1 vs 4). NOT a CH bug.

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
            new RandomEntry("min_count_to_compile_expression", List.of("0", "3")),
            // ---- Execution-mode fuzz additions, 2026-06-11 (tmp/ch-settings-to-test-in-sqlancer.md
            // section 4). No documented result effect; each exercises a distinct aggregation / spill /
            // marshalling path, so a wrong result under any of these is a pure execution-strategy bug.
            //
            // Two-level-aggregation merge strategies; out-of-order bucket emission is deliberate
            // order-sensitivity bait for the partial-aggregate recombine path.
            new RandomEntry("distributed_aggregation_memory_efficient", List.of("0", "1")),
            new RandomEntry("enable_memory_bound_merging_of_aggregation_results", List.of("0", "1")),
            new RandomEntry("enable_producing_buckets_out_of_order_in_aggregation", List.of("0", "1")),
            new RandomEntry("enable_parallel_blocks_marshalling", List.of("0", "1")),
            new RandomEntry("enable_software_prefetch_in_aggregation", List.of("0", "1")),
            // Cross-query hash-table sizing stats; execution-only state shared between queries.
            new RandomEntry("collect_hash_table_stats_during_aggregation", List.of("0", "1")),
            new RandomEntry("collect_hash_table_stats_during_joins", List.of("0", "1")),
            // Forcing spill-to-disk at a tiny byte threshold is the cheapest way to exercise the
            // external merge/recombine paths, and the external-* defaults all moved in 26.4-26.6.
            // 0 = never spill; 1 MiB = spill on any non-trivial state.
            new RandomEntry("max_bytes_before_external_group_by", List.of("0", "1048576")),
            new RandomEntry("max_bytes_before_external_sort", List.of("0", "1048576")),
            // 26.4 addition (byte threshold) + 26.5 default-on flip (memory ratio) for hash joins.
            new RandomEntry("max_bytes_before_external_join", List.of("0", "1048576")),
            new RandomEntry("max_bytes_ratio_before_external_join", List.of("0", "0.5")),
            new RandomEntry("enable_adaptive_memory_spill_scheduler", List.of("0", "1")),
            // In-order aggregation block sizing; 64 KiB forces frequent block cuts on that path.
            new RandomEntry("aggregation_in_order_max_block_bytes", List.of("0", "65536")));
    // max_rows_to_group_by (+ group_by_overflow_mode) from the plan's section 4 is deliberately NOT
    // here: 'any' and 'break' overflow modes legitimately change results (partial / per-stream
    // truncation -- false positives in every two-query oracle), and the default 'throw' mode can
    // only ever produce an untolerated TOO_MANY_ROWS error (code 158, not in the expected-errors
    // set), i.e. worker noise with zero correctness signal. Fuzz it only inside a dedicated
    // error-tolerant oracle if ever needed.

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
