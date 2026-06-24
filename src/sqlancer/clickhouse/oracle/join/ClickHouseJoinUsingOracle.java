package sqlancer.clickhouse.oracle.join;

import java.sql.SQLException;
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

public class ClickHouseJoinUsingOracle implements TestOracle<ClickHouseGlobalState> {

    private static final AtomicLong CTR = new AtomicLong();

    enum Arm {
        INNER,
        LEFT,
        CHAIN
    }

    private final ClickHouseGlobalState state;
    private final ExpectedErrors ddlErrors = new ExpectedErrors();
    private final ExpectedErrors readErrors = new ExpectedErrors();

    public ClickHouseJoinUsingOracle(ClickHouseGlobalState state) {
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
            e.add("AMBIGUOUS_COLUMN_NAME");
            e.add("ILLEGAL_TYPE_OF_ARGUMENT");
        }
    }

    @Override
    public void check() throws SQLException {
        if (!state.getClickHouseOptions().joinUsingOracle) {
            throw new IgnoreMeException();
        }
        long id = CTR.incrementAndGet();
        String tableA = state.getDatabaseName() + ".using_a_" + id;
        String tableB = state.getDatabaseName() + ".using_b_" + id;
        String tableC = state.getDatabaseName() + ".using_c_" + id;
        try {
            if (!execute("CREATE TABLE " + tableA + " (k Int32, x Int64) ENGINE = MergeTree ORDER BY tuple()", ddlErrors)
                    || !execute("CREATE TABLE " + tableB + " (k Int32, y Int64) ENGINE = MergeTree ORDER BY tuple()",
                            ddlErrors)
                    || !execute("CREATE TABLE " + tableC + " (k Int32, z Int64) ENGINE = MergeTree ORDER BY tuple()",
                            ddlErrors)) {
                throw new IgnoreMeException();
            }
            Randomly r = state.getRandomly();
            if (!execute("INSERT INTO " + tableA + " VALUES " + renderKV(r), readErrors)
                    || !execute("INSERT INTO " + tableB + " VALUES " + renderKV(r), readErrors)
                    || !execute("INSERT INTO " + tableC + " VALUES " + renderKV(r), readErrors)) {
                throw new IgnoreMeException();
            }
            Arm arm = Randomly.fromOptions(Arm.values());
            switch (arm) {
            case INNER:
                checkInner(tableA, tableB);
                break;
            case LEFT:
                checkLeft(tableA, tableB);
                break;
            case CHAIN:
                checkChain(tableA, tableB, tableC);
                break;
            }
        } finally {
            dropQuietly(tableA);
            dropQuietly(tableB);
            dropQuietly(tableC);
        }
    }

    private void checkInner(String a, String b) throws SQLException {
        String usingQ = "SELECT toString(arraySort(groupArray((k, x, y)))) FROM " + a
                + " AS a INNER JOIN " + b + " AS b USING(k)";
        String onQ = "SELECT toString(arraySort(groupArray((a.k, x, y)))) FROM " + a
                + " AS a INNER JOIN " + b + " AS b ON a.k = b.k";
        String usingVal = readSingleValue(usingQ);
        String onVal = readSingleValue(onQ);
        if (!usingVal.equals(onVal)) {
            throw new AssertionError(String.format(
                    "JOIN USING(k) vs ON a.k=b.k INNER mismatch:%n  USING: %s -> %s%n  ON:    %s -> %s",
                    usingQ, usingVal, onQ, onVal));
        }
    }

    private void checkLeft(String a, String b) throws SQLException {
        String usingQ = "SELECT toString(arraySort(groupArray((k, x, y)))) FROM " + a
                + " AS a LEFT JOIN " + b + " AS b USING(k)";
        String onQ = "SELECT toString(arraySort(groupArray((a.k, x, y)))) FROM " + a
                + " AS a LEFT JOIN " + b + " AS b ON a.k = b.k";
        String usingVal = readSingleValue(usingQ);
        String onVal = readSingleValue(onQ);
        if (!usingVal.equals(onVal)) {
            throw new AssertionError(String.format(
                    "JOIN USING(k) vs ON a.k=b.k LEFT mismatch:%n  USING: %s -> %s%n  ON:    %s -> %s",
                    usingQ, usingVal, onQ, onVal));
        }
    }

    private void checkChain(String a, String b, String c) throws SQLException {
        String usingQ = "SELECT toString(tuple(sum(x), sum(y), sum(z), count())) FROM " + a
                + " AS a JOIN " + b + " AS b USING(k) JOIN " + c + " AS c USING(k)";
        String onQ = "SELECT toString(tuple(sum(x), sum(y), sum(z), count())) FROM " + a
                + " AS a JOIN " + b + " AS b ON a.k = b.k JOIN " + c + " AS c ON a.k = c.k";
        String usingVal = readSingleValue(usingQ);
        String onVal = readSingleValue(onQ);
        if (!usingVal.equals(onVal)) {
            throw new AssertionError(String.format(
                    "3-table JOIN USING(k) vs ON chain mismatch:%n  USING: %s -> %s%n  ON:    %s -> %s",
                    usingQ, usingVal, onQ, onVal));
        }
    }

    private static String renderKV(Randomly r) {
        int rows = 10 + (int) r.getInteger(0, 16);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rows; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            int k = (int) r.getInteger(0, 11);
            long v = r.getInteger(-1000, 1000);
            sb.append('(').append(k).append(", ").append(v).append(')');
        }
        return sb.toString();
    }

    private boolean execute(String stmt, ExpectedErrors errors) throws SQLException {
        logStmt(stmt);
        return new SQLQueryAdapter(stmt, errors, true).execute(state);
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
