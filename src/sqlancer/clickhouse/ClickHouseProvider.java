package sqlancer.clickhouse;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import com.google.auto.service.AutoService;

import sqlancer.AbstractAction;
import sqlancer.DatabaseProvider;
import sqlancer.IgnoreMeException;
import sqlancer.MainOptions;
import sqlancer.Randomly;
import sqlancer.SQLConnection;
import sqlancer.SQLGlobalState;
import sqlancer.SQLProviderAdapter;
import sqlancer.StatementExecutor;
import sqlancer.clickhouse.ClickHouseProvider.ClickHouseGlobalState;
import sqlancer.clickhouse.gen.ClickHouseAlterGenerator;
import sqlancer.clickhouse.gen.ClickHouseCommon;
import sqlancer.clickhouse.gen.ClickHouseInsertGenerator;
import sqlancer.clickhouse.gen.ClickHouseMutationGenerator;
import sqlancer.clickhouse.gen.ClickHouseTableGenerator;
import sqlancer.clickhouse.oracle.ClickHouseOptimizingOracle;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.common.query.SQLQueryProvider;

@AutoService(DatabaseProvider.class)
public class ClickHouseProvider extends SQLProviderAdapter<ClickHouseGlobalState, ClickHouseOptions> {

    public ClickHouseProvider() {
        super(ClickHouseGlobalState.class, ClickHouseOptions.class);
    }

    public enum Action implements AbstractAction<ClickHouseGlobalState> {

        INSERT(ClickHouseInsertGenerator::getQuery),
        // Schema-mutating actions run during database setup (between CREATE TABLE and the oracle
        // loop). Each emits a SQLQueryAdapter with couldAffectSchema=true so the in-memory schema
        // is refreshed before oracle iteration. Probability is gated via mapActions returning a
        // small count.
        ALTER(ClickHouseAlterGenerator::getQuery),
        // Row-mutating actions: ALTER UPDATE/DELETE (background, async) + lightweight DELETE
        // FROM (synchronous, mark-only). Mutation × projection × MV × lightweight-delete is the
        // single highest historical bug density in CH; gate at very low probability so each run
        // exercises the surface without dominating the statement pool.
        MUTATION(ClickHouseMutationGenerator::getQuery);

        private final SQLQueryProvider<ClickHouseGlobalState> sqlQueryProvider;

        Action(SQLQueryProvider<ClickHouseGlobalState> sqlQueryProvider) {
            this.sqlQueryProvider = sqlQueryProvider;
        }

        @Override
        public SQLQueryAdapter getQuery(ClickHouseGlobalState state) throws Exception {
            return sqlQueryProvider.getQuery(state);
        }
    }

    private static int mapActions(ClickHouseGlobalState globalState, Action a) {
        Randomly r = globalState.getRandomly();
        switch (a) {
        case INSERT:
            return r.getInteger(0, globalState.getOptions().getMaxNumberInserts());
        case ALTER:
            // 0 or 1 ALTER per database setup, biased toward zero so the schema changes
            // occasionally without dominating the per-database statement budget.
            return Randomly.fromOptions(0, 0, 0, 0, 1);
        case MUTATION:
            // 0 or 1 mutation per database setup. Mutations have a non-trivial barrier cost
            // (poll system.mutations until is_done=1) and over-emission inflates wall clock.
            return Randomly.fromOptions(0, 0, 0, 0, 0, 1);
        default:
            throw new AssertionError(a);
        }
    }

    public static class ClickHouseGlobalState extends SQLGlobalState<ClickHouseOptions, ClickHouseSchema> {

        private ClickHouseOptions clickHouseOptions;

        public void setClickHouseOptions(ClickHouseOptions clickHouseOptions) {
            this.clickHouseOptions = clickHouseOptions;
        }

        public ClickHouseOptions getClickHouseOptions() {
            return this.clickHouseOptions;
        }

        public String getOracleName() {
            return String.join("_",
                    this.clickHouseOptions.oracle.stream().map(o -> o.toString()).collect(Collectors.toList()));
        }

        @Override
        public String getDatabaseName() {
            // ClickHouse stores per-database metadata as `<dbname>.sql` on the local disk. On ext4
            // the filename limit is 255 bytes, and "database<N>" + a 25-oracle suffix overflows
            // it. ENAMETOOLONG surfaces as Code: 458 "Cannot unlink file ..." on every DROP /
            // CREATE DATABASE attempt and the worker dies. We keep the human-readable oracle
            // suffix when it fits and substitute a stable short hash when it doesn't, so a single
            // composite-oracle run still has a recognisable database name while runs with many
            // oracles do not crash.
            String base = super.getDatabaseName();
            String suffix = this.getOracleName();
            // Conservative budget: 255 byte ext4 limit minus 20 bytes for ".sql.tmp" + a small
            // safety margin against any future on-disk decorations ClickHouse adds.
            int maxSuffix = 200 - base.length();
            if (suffix.length() <= maxSuffix) {
                return base + suffix;
            }
            return base + "o" + Integer.toHexString(suffix.hashCode());
        }

        @Override
        protected ClickHouseSchema readSchema() throws SQLException {
            return ClickHouseSchema.fromConnection(getConnection(), getDatabaseName());
        }
    }

    @Override
    public void generateDatabase(ClickHouseGlobalState globalState) throws Exception {
        for (int i = 0; i < Randomly.fromOptions(1, 2, 3, 4, 5); i++) {
            boolean success;
            do {
                String tableName = ClickHouseCommon.createTableName(i);
                SQLQueryAdapter qt = ClickHouseTableGenerator.createTableStatement(tableName, globalState);
                success = globalState.executeStatement(qt);
            } while (!success);
        }

        // TODO: add more Actions to populate table
        StatementExecutor<ClickHouseGlobalState, Action> se = new StatementExecutor<>(globalState, Action.values(),
                ClickHouseProvider::mapActions, (q) -> {
                    if (globalState.getSchema().getDatabaseTables().isEmpty()) {
                        throw new IgnoreMeException();
                    }
                });
        se.executeStatements();
    }

    // Wrap whatever oracle the parent built (single or composite) with the dedupe-engine
    // OPTIMIZE TABLE ... FINAL pre-flight. See ClickHouseOptimizingOracle for why.
    //
    // Disabled while ClickHouseTableGenerator is pinned to plain MergeTree (no dedupe engines, no
    // merge race to drain -- supportsFinal() returns false for every table the generator emits,
    // so the wrapper's optimize loop would be a no-op anyway). Re-enable by uncommenting once
    // ReplacingMergeTree / SummingMergeTree are re-introduced into the engine pool.
    // @Override
    // protected TestOracle<ClickHouseGlobalState> getTestOracle(ClickHouseGlobalState globalState) throws Exception {
    //     return new ClickHouseOptimizingOracle(globalState, super.getTestOracle(globalState));
    // }

    @Override
    public SQLConnection createDatabase(ClickHouseGlobalState globalState) throws SQLException {
        String host = globalState.getOptions().getHost();
        int port = globalState.getOptions().getPort();
        if (host == null) {
            host = ClickHouseOptions.DEFAULT_HOST;
        }
        if (port == MainOptions.NO_SET_PORT) {
            port = ClickHouseOptions.DEFAULT_PORT;
        }

        ClickHouseOptions clickHouseOptions = globalState.getDbmsSpecificOptions();
        globalState.setClickHouseOptions(clickHouseOptions);
        String databaseName = globalState.getDatabaseName();

        return createDatabaseClient(globalState, host, port, databaseName, clickHouseOptions);
    }

    // Per-database setup runs DROP/CREATE/USE through the same transport that subsequent oracle
    // queries use. When CH is under memory pressure (e.g. sustained 8-thread fuzzing pushes RSS
    // past max_server_memory_usage), even these trivial DDLs return Code 241 MEMORY_LIMIT_EXCEEDED.
    // Without this tolerance the exception escapes through Main$DBMSExecutor.run as an
    // AssertionError, writing a misleading "bug" reproducer per failed worker -- 2150 of the
    // 2164 reproducers in the 2026-05-25 8h run came from this exact escape path. Tolerated
    // errors here mean "iteration uninformative; skip", not "ClickHouse misbehaved".
    private static void runSetupCommandsWithTolerance(sqlancer.clickhouse.transport.ClickHouseTransport transport,
            String dropDatabaseCommand, String createDatabaseCommand, String useDatabaseCommand)
            throws SQLException {
        try {
            transport.executeUpdate(dropDatabaseCommand);
            transport.executeUpdate(createDatabaseCommand);
            transport.executeUpdate(useDatabaseCommand);
        } catch (SQLException e) {
            sqlancer.common.query.ExpectedErrors tolerated = sqlancer.common.query.ExpectedErrors.newErrors()
                    .with(ClickHouseErrors.getExpectedExpressionErrors()).build();
            if (tolerated.errorIsExpected(e.getMessage())) {
                throw new IgnoreMeException();
            }
            throw e;
        }
    }

    private SQLConnection createDatabaseClient(ClickHouseGlobalState globalState, String host, int port,
            String databaseName, ClickHouseOptions clickHouseOptions) throws SQLException {
        // client-v2 path: the transport owns the HTTP connection pool and applies server-side
        // settings (max_execution_time, wait_end_of_query, http_response_buffer_size, analyzer
        // flag, suspicious-LowCardinality flag) as `QuerySettings.serverSetting` per request. This
        // is functionally equivalent to the URL-parameter pinning the deleted JDBC path used; the
        // difference is that pooled connections all carry the same settings without needing a
        // session-scoped SET.
        //
        // `wait_end_of_query=1` + `http_response_buffer_size=100MB` force the server to fully
        // buffer the response before flushing, so mid-stream execution errors (e.g. an ILLEGAL_
        // DIVISION partway through a streaming result) surface as a clean HTTP error with a
        // parseable body instead of a torn-down chunked transport that historically produced
        // `Premature end of chunk coded message body` close-time noise.
        java.util.LinkedHashMap<String, String> settings = new java.util.LinkedHashMap<>();
        settings.put("max_execution_time", "30");
        settings.put("wait_end_of_query", "1");
        settings.put("http_response_buffer_size", "104857600");
        // Universal result-row cap. Without it, any oracle path that funnels through
        // ComparatorHelper.getResultSetFirstColumnAsString -- TLPBase variants, JoinAlgorithm,
        // SchemaRoundtrip's sister-table reads -- materialises the full server result into Java
        // strings and OOMs the JVM on cartesian / many-to-many shapes. 1M rows is comfortably
        // above the cardinality any non-buggy oracle iteration needs (oracles operate on small
        // seeded tables) and tripping the cap surfaces as a tolerated "Limit for result exceeded"
        // error (TOO_MANY_ROWS_OR_BYTES). The matching tolerance lives in ClickHouseErrors.
        settings.put("max_result_rows", "1000000");
        settings.put("result_overflow_mode", "throw");
        if (clickHouseOptions.enableAnalyzer) {
            settings.put("allow_experimental_analyzer", "1");
        }
        if (clickHouseOptions.enableLowCardinality) {
            settings.put("allow_suspicious_low_cardinality_types", "1");
        }
        // First create against the `default` database, then switch the transport's database
        // pointer so subsequent oracle queries land in the freshly-created schema.
        sqlancer.clickhouse.transport.ClickHouseClientV2Transport transport = new sqlancer.clickhouse.transport.ClickHouseClientV2Transport(
                host, port, globalState.getOptions().getUserName(), globalState.getOptions().getPassword(),
                "default", settings, 5_000L, 60_000L);
        // `DROP DATABASE ... SYNC` forces the Atomic engine to detach metadata synchronously
        // (instead of the default async hex-rename cleanup); paired with the immediately-following
        // CREATE on the same transport this avoids the pre-2026 race-avoidance Thread.sleep(1000)
        // pair the original 2020 module rewrite needed.
        String dropDatabaseCommand = "DROP DATABASE IF EXISTS " + databaseName + " SYNC";
        String createDatabaseCommand = "CREATE DATABASE IF NOT EXISTS " + databaseName;
        String useDatabaseCommand = "USE " + databaseName;
        globalState.getState().logStatement(dropDatabaseCommand);
        globalState.getState().logStatement(createDatabaseCommand);
        globalState.getState().logStatement(useDatabaseCommand);
        runSetupCommandsWithTolerance(transport, dropDatabaseCommand, createDatabaseCommand, useDatabaseCommand);
        Connection con = new sqlancer.clickhouse.transport.ClickHouseTransportConnection(transport);
        if (clickHouseOptions.randomSessionSettings) {
            applyRandomSessionSettings(globalState, clickHouseOptions, con);
        }
        return new SQLConnection(con);
    }

    private static void applyRandomSessionSettings(ClickHouseGlobalState globalState,
            ClickHouseOptions clickHouseOptions, Connection con) throws SQLException {
        LinkedHashMap<String, String> profile = ClickHouseSessionSettings.pickRandomProfile(globalState.getRandomly(),
                clickHouseOptions.randomSessionSettingsBudget);
        int attempted = 0;
        int accepted = 0;
        for (Map.Entry<String, String> entry : profile.entrySet()) {
            String stmt = "SET " + entry.getKey() + " = " + entry.getValue();
            globalState.getState().logStatement(stmt);
            attempted++;
            try (Statement s = con.createStatement()) {
                s.execute(stmt);
                accepted++;
            } catch (SQLException e) {
                // Absorb catalog-drift errors -- unknown setting / bad value -- so a stale catalog
                // surfaces in the M/N summary rather than as an oracle failure. Genuine connection
                // problems propagate.
                String msg = e.getMessage();
                if (msg == null || !isExpectedSessionSettingError(msg)) {
                    throw e;
                }
            }
        }
        globalState.getState()
                .logStatement(String.format("-- session-settings applied: %d of %d", accepted, attempted));
    }

    private static boolean isExpectedSessionSettingError(String msg) {
        for (String pattern : ClickHouseErrors.getSessionSettingsErrors()) {
            if (msg.contains(pattern)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String getDBMSName() {
        return "clickhouse";
    }
}
