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

    @Parameter(names = "--eet-26x-modes", description = "Enable the 26.x EET modes (COMPOUND_INTERVAL, OVERLAY_EQUIV, OVERLAY_SPLICE, NATURAL_SORT_KEY). EET is in ALL_ORACLES, so these stay off by default until their convergence run passes; when off, pickMode() never returns them.", arity = 1)
    public boolean eet26xModes = false;

    @Parameter(names = "--variant-where-emission", description = "Emit Variant-typed predicate fragments in WHERE context (26.1 Variant-in-all-functions surface, PR #90900 + use_variant_as_common_type default-on, PR #90677). WHERE-only by design: the client-v2 RowBinary reader cannot decode a projected Variant column (R4), so the fragments are self-contained Boolean expressions and never reach a fetch column. Stays off by default until one clean convergence run (plan 2026-06-10-002, Unit 10).", arity = 1)
    public boolean variantWhereEmission = false;

    @Override
    public List<ClickHouseOracleFactory> getTestOracleFactory() {
        return oracle;
    }
}
