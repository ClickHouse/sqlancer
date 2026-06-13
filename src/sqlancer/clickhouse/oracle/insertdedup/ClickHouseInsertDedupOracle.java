package sqlancer.clickhouse.oracle.insertdedup;

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

public class ClickHouseInsertDedupOracle implements TestOracle<ClickHouseGlobalState> {

    private static final AtomicLong CTR = new AtomicLong();

    private final ClickHouseGlobalState state;

    private final ExpectedErrors ddlErrors = new ExpectedErrors();
    private final ExpectedErrors readErrors = new ExpectedErrors();
    private final ExpectedErrors asyncErrors = new ExpectedErrors();

    public ClickHouseInsertDedupOracle(ClickHouseGlobalState state) {
        this.state = state;
        for (ExpectedErrors e : List.of(ddlErrors, readErrors, asyncErrors)) {
            ClickHouseErrors.addSessionSettingsErrors(e);

            e.add("UNKNOWN_STORAGE");
            e.add("Unknown table engine");
            e.add("SUPPORT_IS_DISABLED");
            e.add("NOT_IMPLEMENTED");
            e.add("ILLEGAL_TYPE_OF_ARGUMENT");
            e.add("UNKNOWN_FUNCTION");
            e.add("Unknown setting");
            e.add("BAD_ARGUMENTS");
            e.add("experimental");
            e.add("allow_experimental");
            e.add("SYNTAX_ERROR");

            e.add("UNKNOWN_TABLE");
            e.add("Unknown table expression identifier");

            e.add("(MEMORY_LIMIT_EXCEEDED)");
            e.add("memory limit exceeded");

            e.add("TIMEOUT_EXCEEDED");
            e.add("Timeout exceeded");
        }
    }

    @Override
    public void check() throws SQLException {
        if (!state.getClickHouseOptions().insertDedupOracle) {
            throw new IgnoreMeException();
        }

        long id = CTR.incrementAndGet();
        String table = state.getDatabaseName() + ".dedup_" + id + "_t";
        Randomly r = state.getRandomly();

        String create = "CREATE TABLE " + table + " (k UInt32, v Int64) ENGINE = MergeTree ORDER BY k "
                + "SETTINGS non_replicated_deduplication_window = 1000";

        try {
            logStmt(create);
            if (!new SQLQueryAdapter(create, ddlErrors, true).execute(state)) {
                throw new IgnoreMeException();
            }

            int sizeB = 20 + r.getInteger(0, 41);
            List<long[]> blockB = buildBlock(r, 0, sizeB);
            String insertB = renderInsert(table, blockB);

            logStmt(insertB);
            if (!new SQLQueryAdapter(insertB, readErrors, true).execute(state)) {
                throw new IgnoreMeException();
            }
            String countAfterFirst = readSingleValue("SELECT toString(count()) FROM " + table);
            long n = parseCount(countAfterFirst);
            if (n != sizeB) {
                throw new AssertionError(String.format(
                        "insert-dedup setup mismatch: after one INSERT of %d distinct-key rows count()=%s. DDL: %s",
                        sizeB, countAfterFirst, create));
            }

            logStmt(insertB);
            if (!new SQLQueryAdapter(insertB, readErrors, true).execute(state)) {
                throw new IgnoreMeException();
            }
            String countAfterReinsert = readSingleValue("SELECT toString(count()) FROM " + table);
            if (parseCount(countAfterReinsert) != n) {
                throw new AssertionError(String.format(
                        "insert-dedup violation: re-inserting the byte-identical block (insert_deduplicate=1 default) "
                                + "should not change row count, was %d after first insert but %s after the duplicate "
                                + "insert of %d rows. DDL: %s",
                        n, countAfterReinsert, sizeB, create));
            }

            int sizeB2 = 15 + r.getInteger(0, 31);
            List<long[]> blockB2 = buildBlock(r, sizeB, sizeB2);
            String insertB2 = renderInsert(table, blockB2);

            logStmt(insertB2);
            if (!new SQLQueryAdapter(insertB2, readErrors, true).execute(state)) {
                throw new IgnoreMeException();
            }
            String countAfterDistinct = readSingleValue("SELECT toString(count()) FROM " + table);
            if (parseCount(countAfterDistinct) != n + sizeB2) {
                throw new AssertionError(String.format(
                        "insert-dedup distinct-block mismatch: inserting a distinct block of %d rows should raise the "
                                + "count from %d to %d but count() was %s. DDL: %s",
                        sizeB2, n, n + sizeB2, countAfterDistinct, create));
            }

            if (Randomly.getBoolean()) {
                runAsyncArm(table, r, n + sizeB2, create);
            }
        } finally {
            dropQuietly(table);
        }
    }

    private void runAsyncArm(String table, Randomly r, long baselineCount, String create) throws SQLException {
        String[] settings = { "SET async_insert = 1", "SET wait_for_async_insert = 1" };
        for (String setStmt : settings) {
            logStmt(setStmt);
            if (!new SQLQueryAdapter(setStmt, asyncErrors, false).execute(state)) {
                return;
            }
        }

        int sizeAsync = 18 + r.getInteger(0, 35);
        List<long[]> blockAsync = buildBlock(r, 100000, sizeAsync);
        String insertAsync = renderInsert(table, blockAsync);

        logStmt(insertAsync);
        if (!new SQLQueryAdapter(insertAsync, asyncErrors, true).execute(state)) {
            throw new IgnoreMeException();
        }
        String countAfterAsyncFirst = readSingleValue("SELECT toString(count()) FROM " + table);
        long afterFirst = parseCount(countAfterAsyncFirst);
        if (afterFirst != baselineCount + sizeAsync) {
            throw new AssertionError(String.format(
                    "insert-dedup async-arm setup mismatch: first async INSERT of %d rows should raise the count from "
                            + "%d to %d but count() was %s. DDL: %s",
                    sizeAsync, baselineCount, baselineCount + sizeAsync, countAfterAsyncFirst, create));
        }

        logStmt(insertAsync);
        if (!new SQLQueryAdapter(insertAsync, asyncErrors, true).execute(state)) {
            throw new IgnoreMeException();
        }
        String countAfterAsyncReinsert = readSingleValue("SELECT toString(count()) FROM " + table);
        if (parseCount(countAfterAsyncReinsert) != afterFirst) {
            throw new AssertionError(String.format(
                    "insert-dedup async-arm violation: re-inserting the byte-identical async block (async_insert=1, "
                            + "wait_for_async_insert=1) should not change row count, was %d but %s after the duplicate "
                            + "async insert of %d rows. DDL: %s",
                    afterFirst, countAfterAsyncReinsert, sizeAsync, create));
        }
    }

    private static List<long[]> buildBlock(Randomly r, int startKey, int rows) {
        List<long[]> block = new ArrayList<>(rows);
        for (int i = 0; i < rows; i++) {
            block.add(new long[] { startKey + i, r.getInteger(-1000000, 1000000) });
        }
        return block;
    }

    private static String renderInsert(String table, List<long[]> block) {
        StringBuilder sb = new StringBuilder("INSERT INTO ").append(table).append(" (k, v) VALUES ");
        for (int i = 0; i < block.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            long[] row = block.get(i);
            sb.append('(').append(row[0]).append(", ").append(row[1]).append(')');
        }
        return sb.toString();
    }

    private static long parseCount(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            throw new IgnoreMeException();
        }
    }

    private String readSingleValue(String query) throws SQLException {
        List<String> rows = ComparatorHelper.getResultSetFirstColumnAsString(query, readErrors, state);
        if (rows.size() != 1) {
            throw new IgnoreMeException();
        }
        return rows.get(0);
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
