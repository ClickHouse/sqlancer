package sqlancer.clickhouse;

import java.sql.Connection;
import java.sql.DriverManager;
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
import sqlancer.clickhouse.gen.ClickHouseCommon;
import sqlancer.clickhouse.gen.ClickHouseInsertGenerator;
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

        INSERT(ClickHouseInsertGenerator::getQuery);

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
    @Override
    protected TestOracle<ClickHouseGlobalState> getTestOracle(ClickHouseGlobalState globalState) throws Exception {
        return new ClickHouseOptimizingOracle(globalState, super.getTestOracle(globalState));
    }

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

        if (clickHouseOptions.transport == ClickHouseOptions.Transport.HTTP) {
            return createDatabaseHttp(globalState, host, port, databaseName, clickHouseOptions);
        }
        return createDatabaseJdbc(globalState, host, port, databaseName, clickHouseOptions);
    }

    private SQLConnection createDatabaseHttp(ClickHouseGlobalState globalState, String host, int port,
            String databaseName, ClickHouseOptions clickHouseOptions) throws SQLException {
        // HTTP path: every statement is a POST to /?database=... with the SQL as the body. Same
        // settings the JDBC URL carried (max_execution_time=30, wait_end_of_query=1,
        // http_response_buffer_size=100MB) ride as query-string parameters; analyser/LowCardinality
        // flags ride alongside. No clickhouse-jdbc anywhere in the path -- avoids the chunked-decoder
        // and UInt64-overflow failures we triaged.
        java.util.LinkedHashMap<String, String> settings = new java.util.LinkedHashMap<>();
        settings.put("max_execution_time", "30");
        settings.put("wait_end_of_query", "1");
        settings.put("http_response_buffer_size", "104857600");
        if (clickHouseOptions.enableAnalyzer) {
            settings.put("allow_experimental_analyzer", "1");
        }
        if (clickHouseOptions.enableLowCardinality) {
            settings.put("allow_suspicious_low_cardinality_types", "1");
        }
        // First create against the `default` database, then switch the transport's database
        // pointer so subsequent oracle queries land in the freshly-created schema.
        sqlancer.clickhouse.transport.ClickHouseHttpTransport transport = new sqlancer.clickhouse.transport.ClickHouseHttpTransport(
                host, port, globalState.getOptions().getUserName(), globalState.getOptions().getPassword(),
                "default", settings, 5_000, 60_000);
        String dropDatabaseCommand = "DROP DATABASE IF EXISTS " + databaseName + " SYNC";
        String createDatabaseCommand = "CREATE DATABASE IF NOT EXISTS " + databaseName;
        String useDatabaseCommand = "USE " + databaseName;
        globalState.getState().logStatement(dropDatabaseCommand);
        globalState.getState().logStatement(createDatabaseCommand);
        globalState.getState().logStatement(useDatabaseCommand);
        transport.executeUpdate(dropDatabaseCommand);
        transport.executeUpdate(createDatabaseCommand);
        transport.executeUpdate(useDatabaseCommand);
        Connection con = new sqlancer.clickhouse.transport.ClickHouseTransportConnection(transport);
        if (clickHouseOptions.randomSessionSettings) {
            applyRandomSessionSettings(globalState, clickHouseOptions, con);
        }
        return new SQLConnection(con);
    }

    private SQLConnection createDatabaseJdbc(ClickHouseGlobalState globalState, String host, int port,
            String databaseName, ClickHouseOptions clickHouseOptions) throws SQLException {
        String url = String.format("jdbc:clickhouse://%s:%d/%s", host, port, "default");
        Connection con = DriverManager.getConnection(url, globalState.getOptions().getUserName(),
                globalState.getOptions().getPassword());
        // The `SYNC` modifier on DROP DATABASE forces ClickHouse to fully detach metadata
        // and wait for the dropped engine to finish cleanup before returning, instead of the
        // default Atomic-engine behaviour of renaming the data directory to a hex name and
        // cleaning up asynchronously. Combined with the immediately-following CREATE on the
        // same connection, this removes the need for the two `Thread.sleep(1000)` calls that
        // the original 2020 module rewrite used as a race-avoidance heuristic.
        // Measured cost of the old sleeps in the 2026-05-19 baseline: ~84 thread-seconds out of
        // 6×180s = 1080s total thread budget (~8%); every freshly-rolled database paid 2 seconds
        // of pure wallclock latency before the first INSERT could run.
        String dropDatabaseCommand = "DROP DATABASE IF EXISTS " + databaseName + " SYNC";
        globalState.getState().logStatement(dropDatabaseCommand);
        String createDatabaseCommand = "CREATE DATABASE IF NOT EXISTS " + databaseName;
        globalState.getState().logStatement(createDatabaseCommand);
        String useDatabaseCommand = "USE " + databaseName; // Noop. To reproduce easier.
        globalState.getState().logStatement(useDatabaseCommand);
        try (Statement s = con.createStatement()) {
            s.execute(dropDatabaseCommand);
        }
        try (Statement s = con.createStatement()) {
            s.execute(createDatabaseCommand);
        }
        con.close();
        // Server-level ClickHouse settings are passed via the `clickhouse_setting_<name>` prefix.
        // (Prior to clickhouse-jdbc 0.9.8 unknown URL params were silently forwarded; 0.9.8 rejects
        // anything that isn't either a driver config key or prefixed as a server setting.)
        //
        // - allow_suspicious_low_cardinality_types=1: enables LowCardinality wrappers around
        // numeric/Date inner types that ClickHouse otherwise rejects as
        // SUSPICIOUS_TYPE_FOR_LOW_CARDINALITY. The v1 type-system foundation deliberately
        // exercises this combination.
        // - allow_experimental_analyzer=1: opt in to the new ClickHouse analyzer.
        // - max_execution_time=120: caps server-side query execution at 120s; without this,
        // occasional heavyweight random queries hit the 300s socket_timeout and produce
        // ambiguous client-side timeout exceptions (3 observed in the 2026-05-18 baseline).
        // The cap surfaces as a clean TIMEOUT_EXCEEDED error that ClickHouseErrors absorbs.
        // (The clickhouse_setting_* parameters that used to be appended to the URL here are now
        // applied via SET commands below, after the connection is established. The driver's
        // per-query URI builder otherwise re-applied them on every request, contributing ~700
        // execution-sample frames in the iter-8 profile -- moving them to SET keeps the
        // connection URL short and the server still sees session-scoped settings for the life
        // of the connection.)
        // Response compression goes through HTTP `Content-Encoding`, not ClickHouse's native
        // protocol framing. clickhouse-jdbc 0.9.6/0.9.8 share a defect in their native-protocol
        // LZ4 decoder (`ClickHouseLZ4InputStream` + Apache HC `ChunkedInputStream` interaction) —
        // verified byte-identical between the two versions — surfacing as
        // `MalformedChunkCodingException: CRLF expected at end of chunk`, wrapped at the JDBC
        // layer as `SQLException: Failed to read value for column …` (observed 16 times in the
        // 2026-05-18 15-min baseline, 0.33% per-query rate). `client.use_http_compression=true`
        // moves response decoding off that buggy class entirely: the driver advertises
        // `Accept-Encoding: lz4`, the server responds with `Content-Encoding: lz4`, and the body
        // is decoded by Apache Commons Compress's lz4-framed decoder (via `CompressedEntity`) —
        // a separate, sound implementation. The driver also auto-appends
        // `enable_http_compression=1` to every request URL when this mode is on, so we don't
        // need a clickhouse_setting_* opt-in.
        //
        // http_response_buffer_size raised to 100 MB forces ClickHouse to buffer the full response
        // server-side for SQLancer-sized queries, so mid-stream execution errors (e.g., a row
        // triggers ILLEGAL_DIVISION
        // partway through a streamed RowBinary result) surface as a clean HTTP 500 with a
        // parseable error body instead of a chunked transport that gets prematurely closed.
        //
        // Without this, the server commits to `HTTP/1.1 200 + Transfer-Encoding: chunked` before
        // knowing if the query will error; on a mid-stream error it writes plain-text
        // "(ILLEGAL_DIVISION) ..." into the already-binary body and closes the connection.
        // clickhouse-jdbc 0.9.8's BinaryStreamReader then hits EOF on `readDoubleLE` / `readIntLE`
        // and surfaces as `SQLException: Failed to read value for column ...` with
        // `ConnectionClosedException: Premature end of chunk coded message body` underneath.
        // Baseline 2026-05-18 burn-in: ~10% of SetOpTLP queries and ~same on TLPWhere hit this.
        //
        // `wait_end_of_query=1` alone is NOT sufficient -- it only takes effect when the response
        // fits the *default* http_response_buffer_size (a few MB). For larger results the server
        // starts streaming anyway. 100 MB is the chosen size: covers every SQLancer-generated
        // result observed so far (bounded by --max-num-inserts and single-column fetchColumn
        // constraint), without giving the server license to allocate gigabytes per query under
        // concurrency. Cost: memory proportional to result size up to the cap, but typical actual
        // usage is tiny (<1 MB) so the cap rarely binds.
        // Settings that affect the HTTP transport must remain on the connection URL:
        // * `wait_end_of_query=1` is HTTP-protocol-only (SET returns UNKNOWN_SETTING).
        // * `http_response_buffer_size` is taken at the moment the server commits to a chunked
        // HTTP response; SETting it later doesn't retroactively change buffering for the
        // current request, leaving us back at the `Premature end of chunk coded message body`
        // tear-down the original workaround was designed to avoid (observed 7 times in the
        // first iter-9 attempt before the param was returned to the URL).
        // Other clickhouse_setting_* params are session-scoped and applied via SET below.
        // max_execution_time on the URL (not as a session SET) so it is part of every per-request
        // URI the driver builds. Empirically, the prior SET-only placement let some heavy random
        // queries reach the 300 s JDBC socket_timeout instead of the server-side 30 s cap (4
        // SocketTimeoutException + 2 DataTransferException observed in the 2026-05-19 48-min run).
        // URL-side application costs a few bytes of URI per request but guarantees the cap is
        // attached before the server starts streaming.
        con = DriverManager.getConnection(
                String.format("jdbc:clickhouse://%s:%d/%s?socket_timeout=60000"
                        + "&compress=true&client.use_http_compression=true"
                        + "&clickhouse_setting_http_response_buffer_size=104857600&clickhouse_setting_wait_end_of_query=1"
                        + "&clickhouse_setting_max_execution_time=30",
                        host, port, databaseName),
                globalState.getOptions().getUserName(), globalState.getOptions().getPassword());
        applyConnectionLevelSettings(con, clickHouseOptions);
        if (clickHouseOptions.randomSessionSettings) {
            applyRandomSessionSettings(globalState, clickHouseOptions, con);
        }
        return new SQLConnection(con);
    }

    private static void applyConnectionLevelSettings(Connection con, ClickHouseOptions clickHouseOptions)
            throws SQLException {
        // These settings used to live as `clickhouse_setting_*` parameters on the JDBC URL. The
        // 0.9.8 driver re-applied them on every per-query request URI build, costing ~25% of CPU
        // in URI parsing. Setting them once per session via SET keeps the per-request URI to the
        // bare endpoint (`/?database=...`).
        try (Statement s = con.createStatement()) {
            // Cap server-side query execution at 30 s. The old cap of 120 s was set when JOINs
            // were less common and Cartesian-product SELECTs were rare; the W3 JOIN-shape work
            // emits multi-table FROMs ("SELECT * FROM t1, t2, t3") regularly, and at 120 s those
            // queries can monopolise a thread for the full 2 min reading a huge result set.
            // 30 s preserves the "clean TIMEOUT_EXCEEDED rather than ambiguous socket_timeout"
            // property of the original cap while keeping the per-thread blockage bounded.
            s.execute("SET max_execution_time = 30");
            if (clickHouseOptions.enableAnalyzer) {
                s.execute("SET allow_experimental_analyzer = 1");
            }
            if (clickHouseOptions.enableLowCardinality) {
                s.execute("SET allow_suspicious_low_cardinality_types = 1");
            }
        }
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
