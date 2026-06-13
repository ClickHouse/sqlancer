package sqlancer.clickhouse.oracle.altermodify;

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

public class ClickHouseAlterModifyConsistencyOracle implements TestOracle<ClickHouseGlobalState> {

    private static final AtomicLong CTR = new AtomicLong();

    enum Alter {
        WIDEN_COLUMN_TYPE,
        COLUMN_CODEC_ZSTD,
        STRING_CODEC_LZ4,
        MODIFY_TTL_FAR_FUTURE,
        MODIFY_SETTING_MERGE_TTL_TIMEOUT,
        MATERIALIZE_COLUMN
    }

    private final ClickHouseGlobalState state;

    private final ExpectedErrors createErrors = new ExpectedErrors();
    private final ExpectedErrors alterErrors = new ExpectedErrors();
    private final ExpectedErrors readErrors = new ExpectedErrors();

    public ClickHouseAlterModifyConsistencyOracle(ClickHouseGlobalState state) {
        this.state = state;
        for (ExpectedErrors e : List.of(createErrors, alterErrors, readErrors)) {
            ClickHouseErrors.addSessionSettingsErrors(e);
            ClickHouseErrors.addExpectedExpressionErrors(e);

            e.add("UNKNOWN_TABLE");
            e.add("Unknown table expression identifier");
            e.add("(MEMORY_LIMIT_EXCEEDED)");
            e.add("memory limit exceeded");
            e.add("TIMEOUT_EXCEEDED");
            e.add("Timeout exceeded");
        }

        ClickHouseErrors.addAlterErrors(alterErrors);
        alterErrors.addAll(ClickHouseErrors.getMutationErrors());
        alterErrors.add("ALTER_OF_COLUMN_IS_FORBIDDEN");
        alterErrors.add("Cannot ALTER");
        alterErrors.add("Cannot alter");
        alterErrors.add("NOT_IMPLEMENTED");
        alterErrors.add("Unknown codec family code");
        alterErrors.add("Unknown codec");
        alterErrors.add("Wrong number of arguments");
        alterErrors.add("UNKNOWN_SETTING");
        alterErrors.add("Unknown setting");
        alterErrors.add("Setting is readonly");
        alterErrors.add("readonly");
        alterErrors.add("SUPPORT_IS_DISABLED");
        alterErrors.add("experimental");
        alterErrors.add("allow_experimental");
        alterErrors.add("SYNTAX_ERROR");
    }

    @Override
    public void check() throws SQLException {
        if (!state.getClickHouseOptions().alterModifyConsistencyOracle) {
            throw new IgnoreMeException();
        }

        long id = CTR.incrementAndGet();
        String table = state.getDatabaseName() + ".amod_" + id;
        Randomly r = state.getRandomly();

        String create = "CREATE TABLE " + table + " (k Int32, c Int32, s String) ENGINE = MergeTree ORDER BY k";

        try {
            logStmt(create);
            if (!new SQLQueryAdapter(create, createErrors, true).execute(state)) {
                throw new IgnoreMeException();
            }

            int blocks = 2 + r.getInteger(0, 3);
            int nextKey = 0;
            for (int b = 0; b < blocks; b++) {
                int rows = 20 + r.getInteger(0, 41);
                StringBuilder sb = new StringBuilder("INSERT INTO ").append(table).append(" (k, c, s) VALUES ");
                for (int i = 0; i < rows; i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    int cVal = r.getInteger(Integer.MIN_VALUE / 2, Integer.MAX_VALUE / 2);
                    sb.append('(').append(nextKey).append(", ").append(cVal).append(", '")
                            .append(esc(randomString(r))).append("')");
                    nextKey++;
                }
                logStmt(sb.toString());
                if (!new SQLQueryAdapter(sb.toString(), createErrors, true).execute(state)) {
                    throw new IgnoreMeException();
                }
            }

            if (Randomly.getBoolean()) {
                String optimize = "OPTIMIZE TABLE " + table + " FINAL";
                logStmt(optimize);
                new SQLQueryAdapter(optimize, alterErrors, true).execute(state);
            }

            String snapshotQuery = "SELECT toString(tuple(k, toInt64(c), s)) FROM " + table + " ORDER BY k";
            List<String> snapshot = ComparatorHelper.getResultSetFirstColumnAsString(snapshotQuery, readErrors, state);

            Alter alter = Alter.values()[r.getInteger(0, Alter.values().length)];
            List<String> alterStmts = renderAlter(table, alter);

            for (String alterStmt : alterStmts) {
                logStmt(alterStmt);
                if (!new SQLQueryAdapter(alterStmt, alterErrors, true).execute(state)) {
                    throw new IgnoreMeException();
                }
            }

            String afterQuery = "SELECT toString(tuple(k, toInt64(c), s)) FROM " + table + " ORDER BY k";
            List<String> after = ComparatorHelper.getResultSetFirstColumnAsString(afterQuery, readErrors, state);

            if (!snapshot.equals(after)) {
                throw new AssertionError(String.format(
                        "data-preserving ALTER changed the visible row set: alter %s on %s. before %s rows, after %s "
                                + "rows. before %s vs after %s. DDL: %s ; ALTER: %s",
                        alter, table, snapshot.size(), after.size(), truncate(snapshot), truncate(after), create,
                        String.join(" ; ", alterStmts)));
            }
        } finally {
            dropQuietly(table);
        }
    }

    private List<String> renderAlter(String table, Alter alter) {
        List<String> stmts = new ArrayList<>();
        switch (alter) {
        case WIDEN_COLUMN_TYPE:
            stmts.add("ALTER TABLE " + table + " MODIFY COLUMN c Int64 SETTINGS mutations_sync = 2");
            break;
        case COLUMN_CODEC_ZSTD:
            stmts.add("ALTER TABLE " + table + " MODIFY COLUMN c CODEC(ZSTD) SETTINGS mutations_sync = 2");
            break;
        case STRING_CODEC_LZ4:
            stmts.add("ALTER TABLE " + table + " MODIFY COLUMN s CODEC(LZ4) SETTINGS mutations_sync = 2");
            break;
        case MODIFY_TTL_FAR_FUTURE:
            stmts.add("ALTER TABLE " + table + " MODIFY TTL toDateTime('2200-01-01 00:00:00') "
                    + "SETTINGS materialize_ttl_after_modify = 1, mutations_sync = 2");
            break;
        case MODIFY_SETTING_MERGE_TTL_TIMEOUT:
            stmts.add("ALTER TABLE " + table + " MODIFY SETTING merge_with_ttl_timeout = 3600");
            break;
        case MATERIALIZE_COLUMN:
            stmts.add("ALTER TABLE " + table + " MATERIALIZE COLUMN c SETTINGS mutations_sync = 2");
            break;
        default:
            throw new AssertionError(alter);
        }
        return stmts;
    }

    private static String randomString(Randomly r) {
        int len = r.getInteger(0, 8);
        StringBuilder sb = new StringBuilder();
        String alphabet = "abcdefghijklmnopqrstuvwxyz0123456789";
        for (int i = 0; i < len; i++) {
            sb.append(alphabet.charAt(r.getInteger(0, alphabet.length())));
        }
        return sb.toString();
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("'", "\\'");
    }

    private static String truncate(List<String> rows) {
        int limit = 40;
        if (rows.size() <= limit) {
            return rows.toString();
        }
        return rows.subList(0, limit) + "... (" + rows.size() + " total)";
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
