package sqlancer.clickhouse.oracle.join;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import sqlancer.ComparatorHelper;
import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.clickhouse.ClickHouseErrors;
import sqlancer.clickhouse.ClickHouseProvider.ClickHouseGlobalState;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;

public class ClickHouseAsofJoinOracle implements TestOracle<ClickHouseGlobalState> {

    private static final AtomicLong CTR = new AtomicLong();
    private static final int DIFF_LIMIT = 20;
    private static final int KEY_SPACE = 4;
    private static final int TS_MIN = 0;
    private static final int TS_MAX = 40;

    private static final class Row {
        final int k;
        final long ts;
        final long val;

        Row(int k, long ts, long val) {
            this.k = k;
            this.ts = ts;
            this.val = val;
        }
    }

    private final ClickHouseGlobalState state;

    private final ExpectedErrors ddlErrors = new ExpectedErrors();
    private final ExpectedErrors readErrors = new ExpectedErrors();

    public ClickHouseAsofJoinOracle(ClickHouseGlobalState state) {
        this.state = state;
        for (ExpectedErrors e : List.of(ddlErrors, readErrors)) {
            ClickHouseErrors.addSessionSettingsErrors(e);
            ClickHouseErrors.addExpectedExpressionErrors(e);

            e.add("UNKNOWN_TABLE");
            e.add("(MEMORY_LIMIT_EXCEEDED)");
            e.add("memory limit exceeded");
            e.add("TIMEOUT_EXCEEDED");
            e.add("Timeout exceeded");
            e.add("Limit for result exceeded");
            e.add("TOO_MANY_ROWS_OR_BYTES");
            e.add("NOT_IMPLEMENTED");
            e.add("Unsupported JOIN");
            e.add("INVALID_JOIN_ON_EXPRESSION");
            e.add("ILLEGAL_TYPE_OF_ARGUMENT");
            e.add("ASOF");
        }
    }

    @Override
    public void check() throws SQLException {
        if (!state.getClickHouseOptions().asofJoinOracle) {
            throw new IgnoreMeException();
        }
        long id = CTR.incrementAndGet();
        Randomly r = state.getRandomly();
        String left = state.getDatabaseName() + ".asof_l_" + id;
        String right = state.getDatabaseName() + ".asof_r_" + id;
        try {
            if (!execute("CREATE TABLE " + left + " (k Int32, ts Int64) ENGINE = MergeTree ORDER BY tuple()",
                    ddlErrors)) {
                throw new IgnoreMeException();
            }
            if (!execute("CREATE TABLE " + right + " (k Int32, ts Int64, val Int64) ENGINE = MergeTree ORDER BY tuple()",
                    ddlErrors)) {
                throw new IgnoreMeException();
            }

            List<Row> rightRows = buildRightRows(r);
            if (rightRows.isEmpty()) {
                throw new IgnoreMeException();
            }
            if (!execute(renderRightInsert(right, rightRows), readErrors)) {
                throw new IgnoreMeException();
            }

            List<Row> leftRows = buildLeftRows(r);
            if (leftRows.isEmpty()) {
                throw new IgnoreMeException();
            }
            if (!execute(renderLeftInsert(left, leftRows), readErrors)) {
                throw new IgnoreMeException();
            }

            leftRows.sort(Comparator.<Row>comparingInt(row -> row.k).thenComparingLong(row -> row.ts));

            List<String> expected = new ArrayList<>(leftRows.size());
            for (Row l : leftRows) {
                Row best = null;
                for (Row rr : rightRows) {
                    if (rr.k == l.k && rr.ts <= l.ts) {
                        if (best == null || rr.ts > best.ts) {
                            best = rr;
                        }
                    }
                }
                expected.add(best == null ? null : String.valueOf(best.val));
            }

            String query = "SELECT toString(R.val) FROM " + left + " AS L ASOF LEFT JOIN " + right
                    + " AS R ON L.k = R.k AND L.ts >= R.ts ORDER BY L.k, L.ts SETTINGS join_use_nulls = 1";
            logStmt(query);
            List<String> actual = ComparatorHelper.getResultSetFirstColumnAsString(query, readErrors, state);

            if (actual.size() != expected.size()) {
                throw new AssertionError(String.format(
                        "ASOF LEFT JOIN row-count mismatch: Java expects %d rows but query returned %d. Q: %s%nL: %s%nR: %s",
                        expected.size(), actual.size(), query, dumpLeft(leftRows), dumpRight(rightRows)));
            }
            for (int i = 0; i < expected.size(); i++) {
                String exp = expected.get(i);
                String act = actual.get(i);
                boolean equal = exp == null ? act == null : exp.equals(act);
                if (!equal) {
                    throw new AssertionError(String.format(
                            "ASOF LEFT JOIN value mismatch at row %d: expected %s but query returned %s. Q: %s%nL: %s%nR: %s",
                            i, exp == null ? "\\N" : exp, act == null ? "\\N" : act, query, dumpLeft(leftRows),
                            dumpRight(rightRows)));
                }
            }
        } finally {
            dropQuietly(left);
            dropQuietly(right);
        }
    }

    private List<Row> buildRightRows(Randomly r) {
        int target = 12 + (int) r.getInteger(0, 14);
        Set<Long> seen = new HashSet<>();
        List<Row> rows = new ArrayList<>();
        int attempts = 0;
        while (rows.size() < target && attempts < target * 6) {
            attempts++;
            int k = (int) r.getInteger(0, KEY_SPACE);
            long ts = r.getInteger(TS_MIN, TS_MAX);
            long pair = ((long) k << 40) | ts;
            if (!seen.add(pair)) {
                continue;
            }
            long val = r.getInteger(0, 100);
            rows.add(new Row(k, ts, val));
        }
        return rows;
    }

    private List<Row> buildLeftRows(Randomly r) {
        int target = 15 + (int) r.getInteger(0, 16);
        Set<Long> seen = new HashSet<>();
        List<Row> rows = new ArrayList<>();
        int attempts = 0;
        while (rows.size() < target && attempts < target * 6) {
            attempts++;
            int k = (int) r.getInteger(0, KEY_SPACE);
            long ts = r.getInteger(TS_MIN, TS_MAX);
            long pair = ((long) k << 40) | ts;
            if (!seen.add(pair)) {
                continue;
            }
            rows.add(new Row(k, ts, 0));
        }
        return rows;
    }

    static String renderRightInsert(String table, List<Row> rows) {
        StringBuilder sb = new StringBuilder("INSERT INTO ").append(table).append(" (k, ts, val) VALUES ");
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            Row row = rows.get(i);
            sb.append('(').append(row.k).append(", ").append(row.ts).append(", ").append(row.val).append(')');
        }
        return sb.toString();
    }

    static String renderLeftInsert(String table, List<Row> rows) {
        StringBuilder sb = new StringBuilder("INSERT INTO ").append(table).append(" (k, ts) VALUES ");
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            Row row = rows.get(i);
            sb.append('(').append(row.k).append(", ").append(row.ts).append(')');
        }
        return sb.toString();
    }

    static String dumpLeft(List<Row> rows) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            Row row = rows.get(i);
            sb.append("(k=").append(row.k).append(",ts=").append(row.ts).append(')');
        }
        return sb.append(']').toString();
    }

    static String dumpRight(List<Row> rows) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            Row row = rows.get(i);
            sb.append("(k=").append(row.k).append(",ts=").append(row.ts).append(",val=").append(row.val).append(')');
        }
        return sb.append(']').toString();
    }

    private boolean execute(String stmt, ExpectedErrors errors) throws SQLException {
        logStmt(stmt);
        return new SQLQueryAdapter(stmt, errors, true).execute(state);
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
