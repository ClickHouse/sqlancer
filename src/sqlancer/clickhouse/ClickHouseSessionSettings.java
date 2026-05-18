package sqlancer.clickhouse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import sqlancer.Randomly;

public final class ClickHouseSessionSettings {

    private ClickHouseSessionSettings() {
    }

    // Optimizer-rewrite settings that SHOULD be result-preserving. The SEMR oracle
    // toggles each entry between "0" and "1" and asserts multiset equality of the
    // base SELECT. Entries hardcoded by other oracles (enable_optimize_predicate_expression
    // and aggregate_functions_null_for_empty -- see ClickHouseTLPHavingOracle.java:42
    // and ClickHouseTLPAggregateOracle.java:42) are intentionally excluded to keep
    // failure attribution clean when SEMR composes with those oracles.
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
            "query_plan_filter_push_down");

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
            "allow_suspicious_low_cardinality_types");

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
