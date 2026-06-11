package sqlancer.clickhouse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import sqlancer.Randomly;

public final class ClickHouseSessionSettings {

    private ClickHouseSessionSettings() {
    }

    public static final List<String> SEMR_SETTINGS = List.of(

            "optimize_move_to_prewhere",

            "optimize_read_in_order",

            "optimize_use_projections",

            "convert_query_to_cnf",

            "query_plan_filter_push_down",

            "use_query_condition_cache",

            "use_skip_indexes_on_data_read",

            "use_index_for_in_with_subqueries",

            "optimize_use_implicit_projections",

            "transform_null_in",

            "enable_lazy_columns_replication",

            "compile_expressions", "compile_aggregate_expressions",

            "optimize_aggregators_of_group_by_keys",

            "optimize_trivial_count_query",

            "optimize_skip_unused_shards",

            "optimize_functions_to_subcolumns",

            "optimize_rewrite_regexp_functions",

            "query_plan_direct_read_from_text_index", "query_plan_text_index_add_hint",

            "read_in_order_use_buffering",

            "compile_sort_description",

            "allow_statistics_optimize", "allow_statistic_optimize",

            "use_statistics",

            "use_text_index_like_evaluation_by_dictionary_scan",

            "use_top_k_dynamic_filtering",

            "use_skip_indexes_for_top_k",

            "query_plan_top_k_through_join",

            "query_plan_enable_optimizations", "query_plan_merge_filters", "query_plan_split_filter",
            "query_plan_merge_expressions", "query_plan_push_down_limit", "query_plan_lift_up_union",
            "query_plan_lift_up_array_join", "query_plan_remove_redundant_distinct",
            "query_plan_remove_redundant_sorting", "query_plan_remove_unused_columns", "query_plan_read_in_order",
            "query_plan_aggregation_in_order", "query_plan_optimize_prewhere",
            "query_plan_optimize_lazy_materialization", "query_plan_execute_functions_after_sorting",
            "query_plan_enable_multithreading_after_window_functions",

            "query_plan_optimize_lazy_final",

            "query_plan_push_limit_by_into_sort",

            "query_plan_convert_outer_join_to_inner_join", "query_plan_convert_any_join_to_semi_or_anti_join",
            "query_plan_merge_filter_into_join_condition",
            "query_plan_read_in_order_through_join", "query_plan_join_shard_by_pk_ranges",

            "use_join_disjunctions_push_down", "use_hash_table_stats_for_join_reordering",
            "allow_general_join_planning",

            "enable_join_runtime_filters", "enable_join_transitive_predicates",
            "enable_join_fixed_hash_table_conversion", "enable_software_prefetch_in_join",

            "optimize_multiif_to_if", "optimize_if_chain_to_multiif", "optimize_normalize_count_variants",
            "optimize_rewrite_sum_if_to_count_if", "optimize_rewrite_aggregate_function_with_if",
            "optimize_uniq_to_count", "optimize_injective_functions_in_group_by",
            "optimize_injective_functions_inside_uniq", "optimize_group_by_function_keys",
            "optimize_group_by_constant_keys", "optimize_aggregation_in_order", "optimize_distinct_in_order",
            "optimize_redundant_functions_in_order_by", "optimize_respect_aliases",
            "optimize_extract_common_expressions", "optimize_and_compare_chain",
            "optimize_rewrite_like_perfect_affix", "optimize_or_like_chain",
            "optimize_sorting_by_input_stream_properties",

            "optimize_time_filter_with_preimage",

            "optimize_syntax_fuse_functions", "optimize_rewrite_array_exists_to_has", "optimize_rewrite_has_to_in",
            "optimize_truncate_order_by_after_group_by_keys", "optimize_trivial_group_by_limit_query",
            "optimize_dictget_tuple_element",

            "use_primary_key", "use_skip_indexes", "use_skip_indexes_for_disjunctions", "use_skip_indexes_if_final",

            "use_statistics_for_part_pruning",

            "allow_key_condition_coalesce_rewrite", "optimize_use_projection_filtering", "optimize_append_index",
            "materialize_skip_indexes_on_insert",

            "defer_partition_pruning_after_final", "optimize_move_to_prewhere_if_final", "enable_vertical_final",

            "enable_automatic_decision_for_merging_across_partitions_for_final",
            "split_intersecting_parts_ranges_into_layers_final",
            "split_parts_ranges_into_intersecting_and_non_intersecting_final",

            "enable_early_constant_folding", "enable_multiple_prewhere_read_steps",
            "enable_optimize_predicate_expression_to_final_subquery", "allow_reorder_prewhere_conditions",
            "allow_push_predicate_when_subquery_contains_with",

            "optimize_prewhere_after_pushdown",

            "read_in_order_use_virtual_row", "read_in_order_use_virtual_row_per_block", "rewrite_in_to_join",
            "enable_add_distinct_to_in_subqueries", "enable_scalar_subquery_optimization");

    public static final List<RandomEntry> RANDOM_SESSION_SETTINGS = List.of(

            new RandomEntry("max_threads", List.of("1", "2", "4", "8")),

            new RandomEntry("max_block_size", List.of("1024", "8192", "65536")),

            new RandomEntry("max_read_buffer_size", List.of("1048576", "8388608")),

            new RandomEntry("min_insert_block_size_rows", List.of("1024", "1048576")),

            new RandomEntry("min_compress_block_size", List.of("4096", "65536", "262144")),
            new RandomEntry("max_compress_block_size", List.of("65536", "1048576", "4194304")),

            new RandomEntry("preferred_block_size_bytes", List.of("65536", "1000000", "8388608")),

            new RandomEntry("group_by_two_level_threshold", List.of("1", "1000", "100000")),

            new RandomEntry("compile_expressions", List.of("0", "1")),
            new RandomEntry("compile_aggregate_expressions", List.of("0", "1")),
            new RandomEntry("compile_sort_description", List.of("0", "1")),

            new RandomEntry("min_count_to_compile_expression", List.of("0", "3")),

            new RandomEntry("distributed_aggregation_memory_efficient", List.of("0", "1")),
            new RandomEntry("enable_memory_bound_merging_of_aggregation_results", List.of("0", "1")),
            new RandomEntry("enable_producing_buckets_out_of_order_in_aggregation", List.of("0", "1")),
            new RandomEntry("enable_parallel_blocks_marshalling", List.of("0", "1")),
            new RandomEntry("enable_software_prefetch_in_aggregation", List.of("0", "1")),

            new RandomEntry("collect_hash_table_stats_during_aggregation", List.of("0", "1")),
            new RandomEntry("collect_hash_table_stats_during_joins", List.of("0", "1")),

            new RandomEntry("max_bytes_before_external_group_by", List.of("0", "1048576")),
            new RandomEntry("max_bytes_before_external_sort", List.of("0", "1048576")),

            new RandomEntry("max_bytes_before_external_join", List.of("0", "1048576")),
            new RandomEntry("max_bytes_ratio_before_external_join", List.of("0", "0.5")),
            new RandomEntry("enable_adaptive_memory_spill_scheduler", List.of("0", "1")),

            new RandomEntry("aggregation_in_order_max_block_bytes", List.of("0", "65536")));

    public static final Set<String> MANAGED_BY_OPTIONS = Set.of(

            "allow_experimental_analyzer",

            "allow_suspicious_low_cardinality_types",

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

    public static List<SemrCandidate> pickSemrCandidates(Randomly r, int arity) {
        if (arity <= 0) {
            throw new IllegalArgumentException("SEMR arity must be >= 1; got " + arity);
        }
        if (SEMR_SETTINGS.isEmpty()) {
            throw new IllegalStateException("SEMR_SETTINGS is empty -- configuration bug");
        }
        int n = Math.min(arity, SEMR_SETTINGS.size());

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
