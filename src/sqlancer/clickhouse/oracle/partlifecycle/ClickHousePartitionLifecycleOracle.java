package sqlancer.clickhouse.oracle.partlifecycle;

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

public class ClickHousePartitionLifecycleOracle implements TestOracle<ClickHouseGlobalState> {

    private static final AtomicLong CTR = new AtomicLong();

    private static final int[] PARTITION_VALUES = { 0, 1, 2, 3 };

    enum Invariant {
        DETACH_ATTACH,
        DROP_PARTITION,
        REPLACE_PARTITION,
        MOVE_PARTITION
    }

    private final ClickHouseGlobalState state;

    private final ExpectedErrors errors = new ExpectedErrors();

    public ClickHousePartitionLifecycleOracle(ClickHouseGlobalState state) {
        this.state = state;
        ClickHouseErrors.addExpectedExpressionErrors(errors);
        ClickHouseErrors.addSessionSettingsErrors(errors);
        ClickHouseErrors.addAlterErrors(errors);

        errors.add("UNKNOWN_TABLE");
        errors.add("Unknown table expression identifier");

        errors.add("NO_SUCH_DATA_PART");
        errors.add("No such data part");
        errors.add("PARTITION");
        errors.add("partition");
        errors.add("Cannot attach");
        errors.add("Cannot detach");
        errors.add("Cannot drop");
        errors.add("Cannot move");
        errors.add("Cannot replace");
        errors.add("Tables have different structure");
        errors.add("ABORTED");

        errors.add("(MEMORY_LIMIT_EXCEEDED)");
        errors.add("memory limit exceeded");

        errors.add("TIMEOUT_EXCEEDED");
        errors.add("Timeout exceeded");
    }

    @Override
    public void check() throws SQLException {
        if (!state.getClickHouseOptions().partitionLifecycleOracle) {
            throw new IgnoreMeException();
        }
        long id = CTR.incrementAndGet();
        Invariant invariant = Invariant.values()[(int) Randomly.getNotCachedInteger(0, Invariant.values().length)];
        switch (invariant) {
        case DETACH_ATTACH:
            checkDetachAttach(id);
            break;
        case DROP_PARTITION:
            checkDropPartition(id);
            break;
        case REPLACE_PARTITION:
            checkReplacePartition(id);
            break;
        case MOVE_PARTITION:
        default:
            checkMovePartition(id);
            break;
        }
    }

    private void checkDetachAttach(long id) throws SQLException {
        String table = qualified("partda_" + id);
        try {
            createAndFill(table);
            stopMerges(table);
            List<String> before = wholeTableMultiset(table);

            int p = pickPartition();
            if (!run("ALTER TABLE " + table + " DETACH PARTITION " + p)) {
                throw new IgnoreMeException();
            }
            if (!run("ALTER TABLE " + table + " ATTACH PARTITION " + p)) {
                throw new IgnoreMeException();
            }
            List<String> after = wholeTableMultiset(table);
            assertEqual(before, after, "DETACH+ATTACH PARTITION " + p + " changed the table multiset", table);
        } finally {
            startMergesQuietly(table);
            dropQuietly(table);
        }
    }

    private void checkDropPartition(long id) throws SQLException {
        String table = qualified("partdrop_" + id);
        try {
            createAndFill(table);
            stopMerges(table);
            int p = pickPartition();

            String countBeforeSurvivors = readSingleValue(
                    "SELECT toString(count()) FROM " + table + " WHERE p != " + p);
            List<String> survivorsBefore = orderedMultiset(table, "p != " + p);

            if (!run("ALTER TABLE " + table + " DROP PARTITION " + p)) {
                throw new IgnoreMeException();
            }

            String countAfter = readSingleValue("SELECT toString(count()) FROM " + table);
            if (!countBeforeSurvivors.equals(countAfter)) {
                throw new AssertionError(String.format(
                        "DROP PARTITION %d count mismatch: surviving rows before = %s but whole-table count after = %s. "
                                + "table %s",
                        p, countBeforeSurvivors, countAfter, table));
            }
            List<String> survivorsAfter = orderedMultiset(table, null);
            assertEqual(survivorsBefore, survivorsAfter, "DROP PARTITION " + p + " survivor multiset mismatch", table);
        } finally {
            startMergesQuietly(table);
            dropQuietly(table);
        }
    }

    private void checkReplacePartition(long id) throws SQLException {
        String table = qualified("partrepl_" + id);
        String copy = qualified("partcopy_" + id);
        try {
            createTable(table);
            createTable(copy);
            List<int[]> rows = buildRows();
            fill(table, rows);
            fill(copy, rows);
            stopMerges(table);
            stopMerges(copy);

            List<String> before = wholeTableMultiset(table);
            int p = pickPartition();
            if (!run("ALTER TABLE " + table + " REPLACE PARTITION " + p + " FROM " + copy)) {
                throw new IgnoreMeException();
            }
            List<String> after = wholeTableMultiset(table);
            assertEqual(before, after,
                    "REPLACE PARTITION " + p + " FROM an identical copy changed the table multiset", table);
        } finally {
            startMergesQuietly(table);
            startMergesQuietly(copy);
            dropQuietly(table);
            dropQuietly(copy);
        }
    }

    private void checkMovePartition(long id) throws SQLException {
        String src = qualified("partmvsrc_" + id);
        String dst = qualified("partmvdst_" + id);
        try {
            createTable(src);
            createTable(dst);
            fill(src, buildRows());
            stopMerges(src);
            stopMerges(dst);

            int p = pickPartition();
            String srcCountBefore = readSingleValue("SELECT toString(count()) FROM " + src);
            String dstCountBefore = readSingleValue("SELECT toString(count()) FROM " + dst);
            String partCount = readSingleValue("SELECT toString(count()) FROM " + src + " WHERE p = " + p);
            List<String> partRowsBefore = orderedMultiset(src, "p = " + p);

            if (!run("ALTER TABLE " + src + " MOVE PARTITION " + p + " TO TABLE " + dst)) {
                throw new IgnoreMeException();
            }

            long srcBefore = parse(srcCountBefore);
            long dstBefore = parse(dstCountBefore);
            long moved = parse(partCount);
            long srcAfter = parse(readSingleValue("SELECT toString(count()) FROM " + src));
            long dstAfter = parse(readSingleValue("SELECT toString(count()) FROM " + dst));

            if (srcAfter != srcBefore - moved) {
                throw new AssertionError(String.format(
                        "MOVE PARTITION %d source count mismatch: source had %d, partition |p| = %d, expected %d "
                                + "after but saw %d. table %s",
                        p, srcBefore, moved, srcBefore - moved, srcAfter, src));
            }
            if (dstAfter != dstBefore + moved) {
                throw new AssertionError(String.format(
                        "MOVE PARTITION %d destination count mismatch: dest had %d, partition |p| = %d, expected %d "
                                + "after but saw %d. table %s",
                        p, dstBefore, moved, dstBefore + moved, dstAfter, dst));
            }
            if (srcAfter + dstAfter != srcBefore + dstBefore) {
                throw new AssertionError(String.format(
                        "MOVE PARTITION %d did not conserve total rows: before src+dst = %d, after src+dst = %d. "
                                + "tables %s -> %s",
                        p, srcBefore + dstBefore, srcAfter + dstAfter, src, dst));
            }
            List<String> partRowsAfter = orderedMultiset(dst, "p = " + p);
            assertEqual(partRowsBefore, partRowsAfter,
                    "MOVE PARTITION " + p + " did not deliver the source rows verbatim to the destination", dst);
        } finally {
            startMergesQuietly(src);
            startMergesQuietly(dst);
            dropQuietly(src);
            dropQuietly(dst);
        }
    }

    private void createAndFill(String table) throws SQLException {
        createTable(table);
        fill(table, buildRows());
    }

    private void createTable(String table) throws SQLException {
        String create = "CREATE TABLE " + table
                + " (p UInt8, k UInt32, v Int64) ENGINE = MergeTree PARTITION BY p ORDER BY k";
        logStmt(create);
        if (!new SQLQueryAdapter(create, errors, true).execute(state)) {
            throw new IgnoreMeException();
        }
    }

    private List<int[]> buildRows() {
        Randomly r = state.getRandomly();
        int total = 40 + r.getInteger(0, 60);
        List<int[]> rows = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            int p = PARTITION_VALUES[r.getInteger(0, PARTITION_VALUES.length)];
            int k = r.getInteger(0, 1000);
            int v = r.getInteger(-1000, 1000);
            rows.add(new int[] { p, k, v });
        }
        for (int p : PARTITION_VALUES) {
            int k = r.getInteger(0, 1000);
            int v = r.getInteger(-1000, 1000);
            rows.add(new int[] { p, k, v });
        }
        return rows;
    }

    private void fill(String table, List<int[]> rows) throws SQLException {
        Randomly r = state.getRandomly();
        int blocks = 2 + r.getInteger(0, 3);
        int per = (rows.size() + blocks - 1) / blocks;
        for (int b = 0; b < blocks; b++) {
            int start = b * per;
            if (start >= rows.size()) {
                break;
            }
            int end = Math.min(rows.size(), start + per);
            StringBuilder sb = new StringBuilder("INSERT INTO ").append(table).append(" (p, k, v) VALUES ");
            for (int i = start; i < end; i++) {
                if (i > start) {
                    sb.append(", ");
                }
                int[] row = rows.get(i);
                sb.append('(').append(row[0]).append(", ").append(row[1]).append(", ").append(row[2]).append(')');
            }
            logStmt(sb.toString());
            if (!new SQLQueryAdapter(sb.toString(), errors, true).execute(state)) {
                throw new IgnoreMeException();
            }
        }
    }

    private void stopMerges(String table) {
        try {
            new SQLQueryAdapter("SYSTEM STOP MERGES " + table, errors, true).execute(state);
        } catch (Exception | AssertionError ignored) {

        }
    }

    private void startMergesQuietly(String table) {
        try {
            new SQLQueryAdapter("SYSTEM START MERGES " + table, errors, true).execute(state);
        } catch (Exception | AssertionError ignored) {

        }
    }

    private boolean run(String stmt) throws SQLException {
        logStmt(stmt);
        return new SQLQueryAdapter(stmt, errors, true).execute(state);
    }

    private List<String> wholeTableMultiset(String table) throws SQLException {
        return orderedMultiset(table, null);
    }

    private List<String> orderedMultiset(String table, String whereClause) throws SQLException {
        String where = whereClause == null ? "" : " WHERE " + whereClause;
        String query = "SELECT toString(tuple(p, k, v)) FROM " + table + where + " ORDER BY p, k, v";
        return ComparatorHelper.getResultSetFirstColumnAsString(query, errors, state);
    }

    private void assertEqual(List<String> before, List<String> after, String message, String table) {
        if (!before.equals(after)) {
            throw new AssertionError(String.format("%s. table %s: before (%d rows) %s vs after (%d rows) %s", message,
                    table, before.size(), truncate(before), after.size(), truncate(after)));
        }
    }

    private int pickPartition() {
        return PARTITION_VALUES[state.getRandomly().getInteger(0, PARTITION_VALUES.length)];
    }

    private long parse(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            throw new IgnoreMeException();
        }
    }

    private String readSingleValue(String query) throws SQLException {
        List<String> rows = ComparatorHelper.getResultSetFirstColumnAsString(query, errors, state);
        if (rows.size() != 1) {
            throw new IgnoreMeException();
        }
        return rows.get(0);
    }

    private String qualified(String base) {
        return state.getDatabaseName() + "." + base;
    }

    private static String truncate(List<String> rows) {
        int limit = 50;
        if (rows.size() <= limit) {
            return rows.toString();
        }
        return rows.subList(0, limit) + "... (" + rows.size() + " total)";
    }

    private void dropQuietly(String table) {
        try {
            new SQLQueryAdapter("DROP TABLE IF EXISTS " + table + " SYNC", errors, true).execute(state);
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
