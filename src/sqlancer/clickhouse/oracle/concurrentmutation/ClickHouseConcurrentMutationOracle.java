package sqlancer.clickhouse.oracle.concurrentmutation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import sqlancer.ComparatorHelper;
import sqlancer.IgnoreMeException;
import sqlancer.MainOptions;
import sqlancer.Randomly;
import sqlancer.clickhouse.ClickHouseErrors;
import sqlancer.clickhouse.ClickHouseOptions;
import sqlancer.clickhouse.ClickHouseProvider.ClickHouseGlobalState;
import sqlancer.clickhouse.transport.ClickHouseClientV2Transport;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;

public class ClickHouseConcurrentMutationOracle implements TestOracle<ClickHouseGlobalState> {

    private static final AtomicLong CTR = new AtomicLong();
    private static final int CHURN_THREADS = 2;
    private static final int READ_SAMPLES = 40;

    private final ClickHouseGlobalState state;
    private final ExpectedErrors errors = new ExpectedErrors();

    public ClickHouseConcurrentMutationOracle(ClickHouseGlobalState state) {
        this.state = state;
        ClickHouseErrors.addExpectedExpressionErrors(errors);
        ClickHouseErrors.addSessionSettingsErrors(errors);
        errors.add("UNKNOWN_TABLE");
        errors.add("MEMORY_LIMIT_EXCEEDED");
        errors.add("memory limit exceeded");
        errors.add("TIMEOUT_EXCEEDED");
        errors.add("Timeout exceeded");
        errors.add("PART_IS_TEMPORARILY_LOCKED");
        errors.add("ABORTED");
        errors.add("Limit for result exceeded");
        errors.add("TOO_MANY_ROWS_OR_BYTES");
    }

    @Override
    public void check() throws Exception {
        if (!state.getClickHouseOptions().concurrentMutationOracle) {
            throw new IgnoreMeException();
        }
        long id = CTR.incrementAndGet();
        Randomly r = state.getRandomly();
        String table = state.getDatabaseName() + ".cm_" + id;

        String create = "CREATE TABLE " + table + " (c0 Int64, c1 Int32, c2 String) ENGINE = MergeTree ORDER BY c0";
        logStmt(create);
        if (!new SQLQueryAdapter(create, errors, true).execute(state)) {
            throw new IgnoreMeException();
        }

        List<Thread> churners = new ArrayList<>();
        List<ClickHouseClientV2Transport> transports = new ArrayList<>();
        AtomicBoolean stop = new AtomicBoolean(false);
        try {
            for (int part = 0; part < 4; part++) {
                String insert = "INSERT INTO " + table + " VALUES " + buildValues(r);
                logStmt(insert);
                if (!new SQLQueryAdapter(insert, errors, true).execute(state)) {
                    throw new IgnoreMeException();
                }
            }

            logStmt("-- concurrent churn (background connections): OPTIMIZE TABLE " + table + " FINAL; ALTER TABLE "
                    + table + " DELETE WHERE 0 SETTINGS mutations_sync = 0; SELECT count() FROM " + table);

            String readQuery = "SELECT toString(tuple(c0, c1, c2)) FROM " + table;
            List<String> baseline = ComparatorHelper.getResultSetFirstColumnAsString(readQuery, errors, state);

            for (int t = 0; t < CHURN_THREADS; t++) {
                ClickHouseClientV2Transport transport = openTransport();
                transports.add(transport);
                Thread thread = new Thread(() -> churn(transport, table, stop));
                thread.setDaemon(true);
                churners.add(thread);
                thread.start();
            }

            for (int sample = 0; sample < READ_SAMPLES; sample++) {
                List<String> rows;
                try {
                    rows = ComparatorHelper.getResultSetFirstColumnAsString(readQuery, errors, state);
                } catch (IgnoreMeException skip) {
                    continue;
                }
                ComparatorHelper.assumeResultSetsAreEqual(baseline, rows, readQuery, List.of(readQuery), state);
            }
        } finally {
            stop.set(true);
            for (Thread thread : churners) {
                try {
                    thread.join(5_000L);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            for (ClickHouseClientV2Transport transport : transports) {
                try {
                    transport.close();
                } catch (Exception ignored) {
                }
            }
            try {
                new SQLQueryAdapter("DROP TABLE IF EXISTS " + table, errors, true).execute(state);
            } catch (Exception | AssertionError ignored) {
            }
        }
    }

    private void churn(ClickHouseClientV2Transport transport, String table, AtomicBoolean stop) {
        int step = 0;
        while (!stop.get()) {
            String op;
            switch (step++ % 4) {
            case 0:
                op = "OPTIMIZE TABLE " + table + " FINAL";
                break;
            case 1:
                op = "ALTER TABLE " + table + " DELETE WHERE 0 SETTINGS mutations_sync = 0";
                break;
            case 2:
                op = "SELECT count() FROM " + table;
                break;
            default:
                op = "SELECT toString(tuple(c0, c1, c2)) FROM " + table + " WHERE c0 >= " + (step % 1000 - 500);
                break;
            }
            try {
                if (op.startsWith("SELECT")) {
                    transport.executeQuery(op);
                } else {
                    transport.executeUpdate(op);
                }
            } catch (Exception ignored) {
            }
        }
    }

    private void logStmt(String stmt) {
        if (state.getOptions().logEachSelect()) {
            state.getLogger().writeCurrent(stmt);
            state.getState().logStatement(stmt);
        }
    }

    private ClickHouseClientV2Transport openTransport() {
        MainOptions main = state.getOptions();
        ClickHouseOptions opts = state.getClickHouseOptions();
        String host = main.getHost() == null ? ClickHouseOptions.DEFAULT_HOST : main.getHost();
        int port = main.getPort() == MainOptions.NO_SET_PORT ? ClickHouseOptions.DEFAULT_PORT : main.getPort();
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("max_execution_time", "30");
        settings.put("allow_experimental_analyzer", opts.enableAnalyzer ? "1" : "0");
        return new ClickHouseClientV2Transport(host, port, main.getUserName(), main.getPassword(), "default", settings,
                5_000L, 60_000L);
    }

    private String buildValues(Randomly r) {
        int rows = 15 + r.getInteger(0, 36);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rows; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            long c0 = r.getInteger(-1000, 1000);
            int c1 = r.getInteger(-1000, 1000);
            String c2 = "k" + r.getInteger(0, 8);
            sb.append('(').append(c0).append(", ").append(c1).append(", '").append(c2).append("')");
        }
        return sb.toString();
    }
}
