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

    public enum Transport {
        HTTP, CLIENT
    }

    @Parameter(names = "--transport", description = "Wire transport for ClickHouse: 'client' (default, clickhouse-java client-v2) or 'http' (raw HTTPURLConnection POST). Both request TabSeparatedWithNamesAndTypes and parse it the same way; client-v2 brings httpclient5 + connection pooling, http brings zero extra deps and bypasses Apache HC's chunked decoder. jdbc-v2 was dropped in favour of client-v2 -- see ClickHouseClientV2Transport for the rationale")
    public Transport transport = Transport.CLIENT;

    @Override
    public List<ClickHouseOracleFactory> getTestOracleFactory() {
        return oracle;
    }
}
