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
            return super.getDatabaseName() + this.getOracleName();
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
        String url = String.format("jdbc:clickhouse://%s:%d/%s", host, port, "default");
        String databaseName = globalState.getDatabaseName();
        Connection con = DriverManager.getConnection(url, globalState.getOptions().getUserName(),
                globalState.getOptions().getPassword());
        String dropDatabaseCommand = "DROP DATABASE IF EXISTS " + databaseName;
        globalState.getState().logStatement(dropDatabaseCommand);
        String createDatabaseCommand = "CREATE DATABASE IF NOT EXISTS " + databaseName;
        globalState.getState().logStatement(createDatabaseCommand);
        String useDatabaseCommand = "USE " + databaseName; // Noop. To reproduce easier.
        globalState.getState().logStatement(useDatabaseCommand);
        try (Statement s = con.createStatement()) {
            s.execute(dropDatabaseCommand);
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        try (Statement s = con.createStatement()) {
            s.execute(createDatabaseCommand);
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            e.printStackTrace();
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
        String lcExtra = clickHouseOptions.enableLowCardinality
                ? "&clickhouse_setting_allow_suspicious_low_cardinality_types=1" : "";
        String analyzerExtra = clickHouseOptions.enableAnalyzer ? "&clickhouse_setting_allow_experimental_analyzer=1"
                : "";
        // compress=false disables LZ4 response compression. clickhouse-jdbc 0.9.6/0.9.8 share a
        // defect in their LZ4-over-chunked-HTTP decoder (ClickHouseLZ4InputStream + Apache HC
        // ChunkedInputStream interaction) — verified byte-identical between the two versions —
        // surfacing as `MalformedChunkCodingException: CRLF expected at end of chunk`, wrapped
        // at the JDBC layer as `SQLException: Failed to read value for column …`. Observed 16
        // times in the 2026-05-18 15-min baseline (0.33% per-query rate). With compression off
        // the buggy code path is bypassed entirely (the response stream is the raw chunked HTTP
        // body, no LZ4 frame parsing). Cost: responses ~3x larger on the wire, but SQLancer's
        // queries are small and the connection is loopback, so net throughput is unaffected.
        con = DriverManager.getConnection(
                String.format(
                        "jdbc:clickhouse://%s:%d/%s?socket_timeout=300000&compress=false"
                                + "&clickhouse_setting_max_execution_time=120%s%s",
                        host, port, databaseName, analyzerExtra, lcExtra),
                globalState.getOptions().getUserName(), globalState.getOptions().getPassword());
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
