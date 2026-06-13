package sqlancer.clickhouse;

import java.util.Arrays;
import java.util.List;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;

import sqlancer.DBMSSpecificOptions;

@Parameters(separators = "=", commandDescription = "ClickHouse (default port: " + ClickHouseOptions.DEFAULT_PORT
        + ", default host: " + ClickHouseOptions.DEFAULT_HOST + ")")
public class ClickHouseOptions implements DBMSSpecificOptions<ClickHouseOracleFactory> {
    public static final String DEFAULT_HOST = "localhost";
    public static final int DEFAULT_PORT = 8123;

    @Parameter(names = "--oracle")
    public List<ClickHouseOracleFactory> oracle = Arrays.asList(ClickHouseOracleFactory.TLPWhere);

    @Parameter(names = { "--test-joins" }, description = "Allow the generation of JOIN clauses", arity = 1)
    public boolean testJoins = true;

    @Parameter(names = { "--analyzer" }, description = "Enable analyzer in ClickHouse", arity = 1)
    public boolean enableAnalyzer = true;

    @Parameter(names = "--test-nullable-types", description = "Wrap a small fraction of generated column types in Nullable", arity = 1)
    public boolean enableNullable = true;

    @Parameter(names = "--test-lowcardinality-types", description = "Wrap a small fraction of generated column types in LowCardinality", arity = 1)
    public boolean enableLowCardinality = true;

    @Parameter(names = "--random-session-settings", description = "Apply a random subset of curated ClickHouse settings via SET on the per-database connection", arity = 1)
    public boolean randomSessionSettings = false;

    @Parameter(names = "--random-session-settings-budget", description = "Cap on the number of randomized session settings per database (0 = unbounded)")
    public int randomSessionSettingsBudget = 5;

    @Parameter(names = "--test-set-op-tlp", description = "Enable the set-operation TLP oracle (UNION ALL / UNION DISTINCT / INTERSECT / EXCEPT invariants)", arity = 1)
    public boolean enableSetOpTLP = false;

    @Parameter(names = "--test-aggregate-combinators", description = "Allow the expression generator to emit aggregate-combinator chains (sumIf, countIfArray, etc.)", arity = 1)
    public boolean enableCombinators = false;

    @Parameter(names = "--test-combinator-tlp", description = "Enable the combinator-identity oracle (sumIf/countIf/avgOrNull/... algebraic identities)", arity = 1)
    public boolean enableCombinatorTLP = false;

    @Parameter(names = "--test-array-join", description = "Enable ARRAY JOIN structural emission (no-op until Array column generation lands in type-system v2)", arity = 1)
    public boolean enableArrayJoin = false;

    @Parameter(names = "--semr-arity", description = "Number of SEMR settings to toggle together per query for the SEMRMulti oracle (>= 2)")
    public int semrArity = 2;

    @Parameter(names = "--tlp-groupby-strict", description = "Use UNION ALL (no outer canonicalisation) for TLPGroupBy. Surfaces partition-multiplicity false positives by design; default (off) collapses them via UNION DISTINCT.", arity = 1)
    public boolean tlpGroupByStrict = false;

    @Parameter(names = "--eet-26x-modes", description = "Enable the 26.x EET modes (COMPOUND_INTERVAL, OVERLAY_EQUIV, OVERLAY_SPLICE, NATURAL_SORT_KEY). Default-on since the 2026-06-10 convergence run (3h, 1.09M queries, 0 false positives from these modes); when off, pickMode() never returns them.", arity = 1)
    public boolean eet26xModes = true;

    @Parameter(names = "--variant-where-emission", description = "Emit Variant-typed predicate fragments in WHERE context (26.1 Variant-in-all-functions surface, PR #90900 + use_variant_as_common_type default-on, PR #90677). WHERE-only by design: the client-v2 RowBinary reader cannot decode a projected Variant column (R4), so the fragments are self-contained Boolean expressions and never reach a fetch column. Default-on since the 2026-06-10 convergence run (0 reader deaths, 0 false positives; the toInt64 constant-fallback wrap is load-bearing).", arity = 1)
    public boolean variantWhereEmission = true;

    @Parameter(names = "--text-search-predicate-emission", description = "Emit full-text-search predicates (hasToken/hasAllTokens/hasAnyTokens/startsWith/endsWith/multiSearchAny) over plain String columns in general WHERE context, from a fixed token vocabulary. Lets the whole oracle fleet (TLPWhere/NoREC/CODDTest/...) incidentally differential-test text-indexed columns against full scans. Restricted to startsWith/endsWith/multiSearchAny (the functions proven index==scan-equivalent across all tokenizers); hasToken/hasAllTokens/hasAnyTokens are NOT emitted here because they diverge index-vs-scan on array/ngrams/preprocessor (ClickHouse#107186), which the dedicated TextIndexDirectRead oracle targets instead.", arity = 1)
    public boolean textSearchPredicateEmission = true;

    @Parameter(names = "--join-reorder-allow-dropped-key-ref", description = "Let the JoinReorder oracle build ON clauses that reference a key column dropped by a preceding SEMI/ANTI join. Default false, PERMANENTLY: ClickHouse#107073 was closed by the optimizer team as by-design non-determinism -- columns read from the eliminated side of a SEMI/ANTI join are ANY-like (filled from whichever matching row arrives first), so any plan change or physical row-order change legally flips the result and a differential oracle comparing such queries is unsound. The restriction is therefore a soundness rule, not a temporary known-bug pin. Set true only to demonstrate the documented non-determinism.", arity = 1)
    public boolean joinReorderAllowDroppedKeyRef = false;

    @Parameter(names = "--extended-datetime-known-overflow-arm", description = "Let the ExtendedDatetime oracle run its setting=0 (narrowing) arm against a merge-formed part with pre-1970 Date32 values. Default false: that exact combination is the known-open ClickHouse#106419 (toStartOf* filter returns 0 rows after a merge; monotonic-filter range poisoned by Date32->Date narrowing) and would re-fire on every run. When false the oracle still tests the non-merged pre-1970, merged post-1970, and the whole setting=1 surface. Set true to re-confirm #106419; remove the gate once it is fixed on head.", arity = 1)
    public boolean extendedDatetimeKnownOverflowArm = false;

    @Parameter(names = "--prewhere-equivalence-oracle", description = "PrewhereEquivalence oracle: WHERE == PREWHERE == WHERE+optimize_move_to_prewhere=0 over a MergeTree table read (multiset compare).", arity = 1)
    public boolean prewhereEquivalenceOracle = true;

    @Parameter(names = "--read-in-order-toggle-oracle", description = "ReadInOrderToggle oracle: optimize_read_in_order / optimize_aggregation_in_order / read_in_order_use_buffering all-on vs all-off must not change an ORDER BY LIMIT or non-float GROUP BY result.", arity = 1)
    public boolean readInOrderToggleOracle = true;

    @Parameter(names = "--count-optimization-oracle", description = "CountOptimization oracle: optimize_trivial_count_query / optimize_use_implicit_projections / optimize_use_projections on vs off (integer-exact count compares + countIf cross-check + GROUP-BY-key count; hardens #106573/#106125).", arity = 1)
    public boolean countOptimizationOracle = true;

    @Parameter(names = "--lazy-materialization-toggle-oracle", description = "LazyMaterializationToggle oracle: query_plan_optimize_lazy_materialization on vs off must not change an ORDER BY LIMIT read with heavy projections (positional compare).", arity = 1)
    public boolean lazyMaterializationToggleOracle = true;

    @Parameter(names = "--replacing-dedup-oracle", description = "ReplacingDedup oracle: ReplacingMergeTree(ver) FINAL == argMax(val, ver) GROUP BY key over a private merge-formed fixture with globally-unique versions.", arity = 1)
    public boolean replacingDedupOracle = true;

    @Parameter(names = "--quantile-consistency-oracle", description = "QuantileConsistency oracle: single-snapshot quantileExact==medianExact, quantilesExact[1]==quantileExact, monotone-in-level and Low<=Exact<=High over an integer column.", arity = 1)
    public boolean quantileConsistencyOracle = true;

    @Parameter(names = "--uniq-exactness-oracle", description = "UniqExactness oracle: uniqExact(c) == count(DISTINCT c) == length(groupUniqArray(c)) over integer/String columns (single snapshot).", arity = 1)
    public boolean uniqExactnessOracle = true;

    @Parameter(names = "--arg-extremum-oracle", description = "ArgExtremum oracle: argMax(v,k) / arraySort(groupArray(v)) / groupArraySorted(n)(v) against a Java ground truth over a private unique-key fixture.", arity = 1)
    public boolean argExtremumOracle = true;

    @Parameter(names = "--materialized-column-oracle", description = "MaterializedColumn oracle: each MATERIALIZED/ALIAS column's stored value == its defining expression recomputed in the same query (single-snapshot two-column compare).", arity = 1)
    public boolean materializedColumnOracle = true;

    @Override
    public List<ClickHouseOracleFactory> getTestOracleFactory() {
        return oracle;
    }
}
