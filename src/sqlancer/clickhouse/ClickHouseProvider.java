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
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.common.query.SQLQueryProvider;

@AutoService(DatabaseProvider.class)
public class ClickHouseProvider extends SQLProviderAdapter<ClickHouseGlobalState, ClickHouseOptions> {

    public ClickHouseProvider() {
        super(ClickHouseGlobalState.class, ClickHouseOptions.class);
    }

    public enum Action implements AbstractAction<ClickHouseGlobalState> {

        INSERT(ClickHouseInsertGenerator::getQuery),

        ALTER(ClickHouseAlterGenerator::getQuery),

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

            return Randomly.fromOptions(0, 0, 0, 0, 1);
        case MUTATION:

            return Randomly.fromOptions(0, 0, 0, 0, 1, 1, 1, 2);
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

            String base = super.getDatabaseName();
            String suffix = this.getOracleName();

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
        String databaseName = globalState.getDatabaseName();

        return createDatabaseClient(globalState, host, port, databaseName, clickHouseOptions);
    }

    private static void runSetupCommandsWithTolerance(sqlancer.clickhouse.transport.ClickHouseTransport transport,
            String dropDatabaseCommand, String createDatabaseCommand, String useDatabaseCommand) throws SQLException {
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

        java.util.LinkedHashMap<String, String> settings = new java.util.LinkedHashMap<>();
        settings.put("max_execution_time", "30");
        settings.put("wait_end_of_query", "1");
        settings.put("http_response_buffer_size", "104857600");

        settings.put("max_result_rows", "1000000");
        settings.put("result_overflow_mode", "throw");

        settings.put("allow_experimental_analyzer", "1");
        if (clickHouseOptions.enableLowCardinality) {
            settings.put("allow_suspicious_low_cardinality_types", "1");
        }

        sqlancer.clickhouse.transport.ClickHouseClientV2Transport transport = new sqlancer.clickhouse.transport.ClickHouseClientV2Transport(
                host, port, globalState.getOptions().getUserName(), globalState.getOptions().getPassword(), "default",
                settings, 5_000L, 60_000L);

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
