package sqlancer.clickhouse.oracle.coalesce;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import sqlancer.ComparatorHelper;
import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.clickhouse.ClickHouseErrors;
import sqlancer.clickhouse.ClickHouseProvider.ClickHouseGlobalState;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;

public class ClickHouseCoalescingFinalOracle implements TestOracle<ClickHouseGlobalState> {

    private static final AtomicLong TABLE_COUNTER = new AtomicLong();
    private static final AtomicLong SEQ_COUNTER = new AtomicLong();

    private final ClickHouseGlobalState state;

    private final ExpectedErrors createErrors = new ExpectedErrors();
    private final ExpectedErrors readErrors = new ExpectedErrors();

    public ClickHouseCoalescingFinalOracle(ClickHouseGlobalState state) {
        this.state = state;
        for (ExpectedErrors e : List.of(createErrors, readErrors)) {
            ClickHouseErrors.addSessionSettingsErrors(e);

            e.add("UNKNOWN_STORAGE");
            e.add("Unknown table engine");
            e.add("SUPPORT_IS_DISABLED");
            e.add("NOT_IMPLEMENTED");
            e.add("experimental");
            e.add("allow_experimental");

            e.add("UNKNOWN_TABLE");
            e.add("Unknown table expression identifier");

            e.add("(MEMORY_LIMIT_EXCEEDED)");
            e.add("memory limit exceeded");

            e.add("TIMEOUT_EXCEEDED");
            e.add("Timeout exceeded");

            e.add("Limit for result exceeded");
            e.add("TOO_MANY_ROWS_OR_BYTES");
        }
    }

    @Override
    public void check() throws SQLException {
        if (!state.getClickHouseOptions().coalescingFinalOracle) {
            throw new IgnoreMeException();
        }

        long id = TABLE_COUNTER.incrementAndGet();
        String table = state.getDatabaseName() + ".coal_" + id;
        Randomly r = state.getRandomly();

        String create = "CREATE TABLE " + table
                + " (k Int32, v1 Nullable(Int64), v2 Nullable(Int64), seq UInt64) "
                + "ENGINE = CoalescingMergeTree ORDER BY k";

        try {
            logStmt(create);
            if (!new SQLQueryAdapter(create, createErrors, true).execute(state)) {
                throw new IgnoreMeException();
            }

            int keyCount = 3 + r.getInteger(0, 5);
            int blocks = 3 + r.getInteger(0, 3);

            boolean[] v1NonNull = new boolean[keyCount];
            boolean[] v2NonNull = new boolean[keyCount];

            for (int b = 0; b < blocks; b++) {
                int rows = 4 + r.getInteger(0, 9);
                StringBuilder sb = new StringBuilder("INSERT INTO ").append(table)
                        .append(" (k, v1, v2, seq) VALUES ");
                for (int i = 0; i < rows; i++) {
                    int k = r.getInteger(0, keyCount);
                    long seq = SEQ_COUNTER.incrementAndGet();
                    String v1 = nullableInt(r);
                    String v2 = nullableInt(r);
                    if (!"NULL".equals(v1)) {
                        v1NonNull[k] = true;
                    }
                    if (!"NULL".equals(v2)) {
                        v2NonNull[k] = true;
                    }
                    if (i > 0) {
                        sb.append(", ");
                    }
                    sb.append('(').append(k).append(", ").append(v1).append(", ").append(v2).append(", ")
                            .append(seq).append(')');
                }
                logStmt(sb.toString());
                if (!new SQLQueryAdapter(sb.toString(), readErrors, true).execute(state)) {
                    throw new IgnoreMeException();
                }
            }

            for (int k = 0; k < keyCount; k++) {
                if (!v1NonNull[k] || !v2NonNull[k]) {
                    long seq = SEQ_COUNTER.incrementAndGet();
                    String fill = "INSERT INTO " + table + " (k, v1, v2, seq) VALUES (" + k + ", "
                            + r.getInteger(0, 1000000) + ", " + r.getInteger(0, 1000000) + ", " + seq + ")";
                    logStmt(fill);
                    if (!new SQLQueryAdapter(fill, readErrors, true).execute(state)) {
                        throw new IgnoreMeException();
                    }
                }
            }

            String optimize = "OPTIMIZE TABLE " + table + " FINAL";
            logStmt(optimize);
            if (!new SQLQueryAdapter(optimize, readErrors, true).execute(state)) {
                throw new IgnoreMeException();
            }

            String groundTruth = "SELECT toString(tuple(k, argMaxIf(v1, seq, isNotNull(v1)), "
                    + "argMaxIf(v2, seq, isNotNull(v2)))) FROM " + table + " GROUP BY k ORDER BY k";
            String finalRead = "SELECT toString(tuple(k, v1, v2)) FROM " + table + " FINAL ORDER BY k";

            List<String> gtRows = ComparatorHelper.getResultSetFirstColumnAsString(groundTruth, readErrors, state);
            List<String> finRows = ComparatorHelper.getResultSetFirstColumnAsString(finalRead, readErrors, state);

            if (!finRows.equals(gtRows)) {
                throw new AssertionError(String.format(
                        "CoalescingMergeTree FINAL last-non-null mismatch: FINAL read %s vs argMaxIf ground truth %s. "
                                + "DDL: %s ; FINAL: %s ; GT: %s",
                        truncate(finRows), truncate(gtRows), create, finalRead, groundTruth));
            }
        } finally {
            dropQuietly(table);
        }
    }

    private static String nullableInt(Randomly r) {
        if (r.getInteger(0, 100) < 40) {
            return "NULL";
        }
        return Long.toString(r.getInteger(-1000000, 1000000));
    }

    private static String truncate(List<String> rows) {
        int limit = 50;
        if (rows.size() <= limit) {
            return rows.toString();
        }
        return new ArrayList<>(rows.subList(0, limit)) + "... (" + rows.size() + " total)";
    }

    private void dropQuietly(String table) {
        try {
            new SQLQueryAdapter("DROP TABLE IF EXISTS " + table, readErrors, true).execute(state);
        } catch (Exception | AssertionError ignored) {

        }
    }

    private void logStmt(String stmt) {
        if (state.getOptions().logEachSelect()) {
            state.getLogger().writeCurrent(stmt);
            state.getState().logStatement(stmt);
        }
    }
}
