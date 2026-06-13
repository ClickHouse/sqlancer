package sqlancer.clickhouse.oracle.join;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

import sqlancer.ComparatorHelper;
import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.clickhouse.ClickHouseErrors;
import sqlancer.clickhouse.ClickHouseProvider.ClickHouseGlobalState;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;

public class ClickHouseSemiJoinRewriteOracle implements TestOracle<ClickHouseGlobalState> {

    private static final AtomicLong CTR = new AtomicLong();
    private static final int DIFF_LIMIT = 20;
    private static final int KEY_SPACE = 40;

    enum Mode {
        SEMI,
        ANTI,
        ANY_CARDINALITY
    }

    private final ClickHouseGlobalState state;

    private final ExpectedErrors createErrors = new ExpectedErrors();
    private final ExpectedErrors readErrors = new ExpectedErrors();

    public ClickHouseSemiJoinRewriteOracle(ClickHouseGlobalState state) {
        this.state = state;
        for (ExpectedErrors e : List.of(createErrors, readErrors)) {
            ClickHouseErrors.addSessionSettingsErrors(e);

            e.add("UNKNOWN_TABLE");
            e.add("Unknown table expression identifier");

            e.add("(MEMORY_LIMIT_EXCEEDED)");
            e.add("memory limit exceeded");

            e.add("TIMEOUT_EXCEEDED");
            e.add("Timeout exceeded");

            e.add("Limit for result exceeded");
            e.add("TOO_MANY_ROWS_OR_BYTES");
        }
        readErrors.add("Unsupported JOIN keys");
        readErrors.add("SEMI|ANTI JOIN should have constant");
        readErrors.add("INVALID_JOIN_ON_EXPRESSION");
        readErrors.add("NOT_IMPLEMENTED");
        readErrors.add("Syntax error");
        readErrors.add("Expected one of");
    }

    @Override
    public void check() throws SQLException {
        if (!state.getClickHouseOptions().semiJoinRewriteOracle) {
            throw new IgnoreMeException();
        }
        long id = CTR.incrementAndGet();
        String tableA = state.getDatabaseName() + ".semia_" + id;
        String tableB = state.getDatabaseName() + ".semib_" + id;
        Randomly r = state.getRandomly();

        String createA = "CREATE TABLE " + tableA + " (k Int32, va Int64) ENGINE = MergeTree ORDER BY tuple()";
        String createB = "CREATE TABLE " + tableB + " (k Int32) ENGINE = MergeTree ORDER BY tuple()";

        try {
            logStmt(createA);
            if (!new SQLQueryAdapter(createA, createErrors, true).execute(state)) {
                throw new IgnoreMeException();
            }
            logStmt(createB);
            if (!new SQLQueryAdapter(createB, createErrors, true).execute(state)) {
                throw new IgnoreMeException();
            }

            insertA(tableA, r);
            insertB(tableB, r);

            Mode mode = Mode.values()[(int) Randomly.getNotCachedInteger(0, Mode.values().length)];
            switch (mode) {
            case SEMI:
                checkSemi(tableA, tableB);
                break;
            case ANTI:
                checkAnti(tableA, tableB);
                break;
            case ANY_CARDINALITY:
                checkAnyCardinality(tableA, tableB);
                break;
            default:
                throw new AssertionError(mode);
            }
        } finally {
            dropQuietly(tableA);
            dropQuietly(tableB);
        }
    }

    private void insertA(String tableA, Randomly r) throws SQLException {
        int blocks = 2 + r.getInteger(0, 3);
        long rowId = 0;
        for (int b = 0; b < blocks; b++) {
            int rows = 20 + r.getInteger(0, 41);
            StringBuilder sb = new StringBuilder("INSERT INTO ").append(tableA).append(" (k, va) VALUES ");
            for (int i = 0; i < rows; i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                long k = r.getInteger(0, KEY_SPACE);
                sb.append('(').append(k).append(", ").append(rowId).append(')');
                rowId++;
            }
            logStmt(sb.toString());
            if (!new SQLQueryAdapter(sb.toString(), readErrors, true).execute(state)) {
                throw new IgnoreMeException();
            }
        }
    }

    private void insertB(String tableB, Randomly r) throws SQLException {
        int blocks = 1 + r.getInteger(0, 3);
        for (int b = 0; b < blocks; b++) {
            int rows = 10 + r.getInteger(0, 31);
            StringBuilder sb = new StringBuilder("INSERT INTO ").append(tableB).append(" (k) VALUES ");
            for (int i = 0; i < rows; i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                long k = r.getInteger(0, KEY_SPACE / 2 + KEY_SPACE / 4);
                sb.append('(').append(k).append(')');
            }
            logStmt(sb.toString());
            if (!new SQLQueryAdapter(sb.toString(), readErrors, true).execute(state)) {
                throw new IgnoreMeException();
            }
        }
    }

    private void checkSemi(String tableA, String tableB) throws SQLException {
        String joinForm = "SELECT toString(tuple(a.k, a.va)) FROM " + tableA + " AS a LEFT SEMI JOIN " + tableB
                + " AS b ON a.k = b.k";
        String inForm = "SELECT toString(tuple(k, va)) FROM " + tableA + " WHERE k IN (SELECT k FROM " + tableB + ")";
        assertMultisetEqual(joinForm, inForm, "SEMI");
    }

    private void checkAnti(String tableA, String tableB) throws SQLException {
        String joinForm = "SELECT toString(tuple(a.k, a.va)) FROM " + tableA + " AS a LEFT ANTI JOIN " + tableB
                + " AS b ON a.k = b.k";
        String notInForm = "SELECT toString(tuple(k, va)) FROM " + tableA + " WHERE k NOT IN (SELECT k FROM " + tableB
                + ")";
        assertMultisetEqual(joinForm, notInForm, "ANTI");
    }

    private void checkAnyCardinality(String tableA, String tableB) throws SQLException {
        String joinForm = "SELECT toString(count()) FROM " + tableA + " AS a LEFT ANY JOIN " + tableB
                + " AS b ON a.k = b.k";
        String baseForm = "SELECT toString(count()) FROM " + tableA;
        String joinCount = readSingleValue(joinForm);
        String baseCount = readSingleValue(baseForm);
        if (!joinCount.equals(baseCount)) {
            throw new AssertionError(String.format(
                    "SEMI-rewrite[ANY] cardinality mismatch: LEFT ANY JOIN kept %s rows but base table has %s rows.%n"
                            + "  join:  %s%n  base:  %s",
                    joinCount, baseCount, joinForm, baseForm));
        }
    }

    private void assertMultisetEqual(String joinForm, String rewriteForm, String label) throws SQLException {
        List<String> joinRows = ComparatorHelper.getResultSetFirstColumnAsString(joinForm, readErrors, state);
        List<String> rewriteRows = ComparatorHelper.getResultSetFirstColumnAsString(rewriteForm, readErrors, state);
        List<String> diff = multisetDiff(joinRows, rewriteRows, DIFF_LIMIT);
        if (diff.isEmpty()) {
            return;
        }
        throw new AssertionError(String.format(
                "SEMI-rewrite[%s] multiset mismatch: join form returned %d rows, rewrite form returned %d rows.%n"
                        + "  join:    %s%n  rewrite: %s%n  first %d differing entries (value (+count side)): %s",
                label, joinRows.size(), rewriteRows.size(), joinForm, rewriteForm, diff.size(), diff));
    }

    static List<String> multisetDiff(List<String> joinRows, List<String> rewriteRows, int limit) {
        Map<String, Long> counts = new TreeMap<>();
        for (String s : joinRows) {
            counts.merge(s == null ? "\\N" : s, 1L, Long::sum);
        }
        for (String s : rewriteRows) {
            counts.merge(s == null ? "\\N" : s, -1L, Long::sum);
        }
        List<String> diff = new ArrayList<>();
        for (Map.Entry<String, Long> e : counts.entrySet()) {
            if (e.getValue() == 0) {
                continue;
            }
            if (diff.size() >= limit) {
                break;
            }
            long c = e.getValue();
            diff.add(e.getKey() + " (+" + Math.abs(c) + " " + (c > 0 ? "join" : "rewrite") + ")");
        }
        return diff;
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
