package sqlancer.clickhouse.oracle.bitfn;

import java.sql.SQLException;
import java.util.ArrayList;
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

public class ClickHouseBitFunctionOracle implements TestOracle<ClickHouseGlobalState> {

    private static final AtomicLong CTR = new AtomicLong();

    private final ClickHouseGlobalState state;
    private final ExpectedErrors errors = new ExpectedErrors();

    public ClickHouseBitFunctionOracle(ClickHouseGlobalState state) {
        this.state = state;
        ClickHouseErrors.addSessionSettingsErrors(errors);
        ClickHouseErrors.addExpectedExpressionErrors(errors);
        errors.add("UNKNOWN_TABLE");
        errors.add("(MEMORY_LIMIT_EXCEEDED)");
        errors.add("memory limit exceeded");
        errors.add("TIMEOUT_EXCEEDED");
        errors.add("Timeout exceeded");
        errors.add("Limit for result exceeded");
        errors.add("TOO_MANY_ROWS_OR_BYTES");
        errors.add("NOT_IMPLEMENTED");
        errors.add("ILLEGAL_TYPE_OF_ARGUMENT");
        errors.add("Unknown function");
    }

    @Override
    public void check() throws SQLException {
        if (!state.getClickHouseOptions().bitFunctionOracle) {
            throw new IgnoreMeException();
        }
        long id = CTR.incrementAndGet();
        String table = state.getDatabaseName() + ".bitfn_" + id;
        if (Randomly.getBoolean()) {
            checkScalar(table);
        } else {
            checkBitmap(table);
        }
    }

    private void checkScalar(String table) throws SQLException {
        String create = "CREATE TABLE " + table
                + " (k UInt32, a UInt64, b UInt64, s UInt8) ENGINE = MergeTree ORDER BY k";
        try {
            logStmt(create);
            if (!new SQLQueryAdapter(create, errors, true).execute(state)) {
                throw new IgnoreMeException();
            }

            Randomly r = state.getRandomly();
            int rows = 20 + r.getInteger(0, 21);
            long[] aVals = new long[rows];
            long[] bVals = new long[rows];
            int[] sVals = new int[rows];

            StringBuilder sb = new StringBuilder("INSERT INTO ").append(table).append(" (k, a, b, s) VALUES ");
            for (int i = 0; i < rows; i++) {
                long a = r.getInteger();
                long b = r.getInteger();
                int s = r.getInteger(0, 64);
                aVals[i] = a;
                bVals[i] = b;
                sVals[i] = s;
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append('(').append(i).append(", ")
                        .append(Long.toUnsignedString(a)).append(", ")
                        .append(Long.toUnsignedString(b)).append(", ")
                        .append(s).append(')');
            }
            logStmt(sb.toString());
            if (!new SQLQueryAdapter(sb.toString(), errors, true).execute(state)) {
                throw new IgnoreMeException();
            }

            List<String> expAnd = new ArrayList<>(rows);
            List<String> expOr = new ArrayList<>(rows);
            List<String> expXor = new ArrayList<>(rows);
            List<String> expNot = new ArrayList<>(rows);
            List<String> expShl = new ArrayList<>(rows);
            List<String> expShr = new ArrayList<>(rows);
            List<String> expCount = new ArrayList<>(rows);
            List<String> expTest = new ArrayList<>(rows);

            for (int i = 0; i < rows; i++) {
                long a = aVals[i];
                long b = bVals[i];
                int s = sVals[i];
                expAnd.add(Long.toUnsignedString(a & b));
                expOr.add(Long.toUnsignedString(a | b));
                expXor.add(Long.toUnsignedString(a ^ b));
                expNot.add(Long.toUnsignedString(~a));
                expShl.add(Long.toUnsignedString(a << s));
                expShr.add(Long.toUnsignedString(a >>> s));
                expCount.add(Long.toString(Long.bitCount(a)));
                expTest.add(Long.toString((a >>> s) & 1L));
            }

            assertProbe(table, "toString(bitAnd(a, b))", expAnd, "bitAnd");
            assertProbe(table, "toString(bitOr(a, b))", expOr, "bitOr");
            assertProbe(table, "toString(bitXor(a, b))", expXor, "bitXor");
            assertProbe(table, "toString(bitNot(a))", expNot, "bitNot");
            assertProbe(table, "toString(bitShiftLeft(a, s))", expShl, "bitShiftLeft");
            assertProbe(table, "toString(bitShiftRight(a, s))", expShr, "bitShiftRight");
            assertProbe(table, "toString(bitCount(a))", expCount, "bitCount");
            assertProbe(table, "toString(bitTest(a, s))", expTest, "bitTest");
        } finally {
            dropQuietly(table);
        }
    }

    private void checkBitmap(String table) throws SQLException {
        String create = "CREATE TABLE " + table
                + " (k UInt32, g UInt8, a UInt32) ENGINE = MergeTree ORDER BY k";
        try {
            logStmt(create);
            if (!new SQLQueryAdapter(create, errors, true).execute(state)) {
                throw new IgnoreMeException();
            }

            Randomly r = state.getRandomly();
            int rows = 30 + r.getInteger(0, 31);
            int[] gVals = new int[rows];
            int[] aVals = new int[rows];

            Set<Integer> group0 = new HashSet<>();
            Set<Integer> group1 = new HashSet<>();
            boolean hasGroup0 = false;
            boolean hasGroup1 = false;

            StringBuilder sb = new StringBuilder("INSERT INTO ").append(table).append(" (k, g, a) VALUES ");
            for (int i = 0; i < rows; i++) {
                int g = r.getInteger(0, 2);
                int a = r.getInteger(0, 31);
                gVals[i] = g;
                aVals[i] = a;
                if (g == 0) {
                    group0.add(a);
                    hasGroup0 = true;
                } else {
                    group1.add(a);
                    hasGroup1 = true;
                }
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append('(').append(i).append(", ").append(g).append(", ").append(a).append(')');
            }

            if (!hasGroup0 || !hasGroup1) {
                throw new IgnoreMeException();
            }

            logStmt(sb.toString());
            if (!new SQLQueryAdapter(sb.toString(), errors, true).execute(state)) {
                throw new IgnoreMeException();
            }

            Set<Integer> allDistinct = new HashSet<>();
            for (int a : aVals) {
                allDistinct.add(a);
            }
            String expectedCardinality = Integer.toString(allDistinct.size());

            String cardQuery = "SELECT toString(bitmapCardinality(bitmapBuild(groupArray(a)))) FROM " + table;
            String uniqQuery = "SELECT toString(uniqExact(a)) FROM " + table;
            logStmt(cardQuery);
            String actualCard = readSingleValue(cardQuery);
            logStmt(uniqQuery);
            String actualUniq = readSingleValue(uniqQuery);

            if (!actualCard.equals(actualUniq)) {
                throw new AssertionError(String.format(
                        "bitmapCardinality != uniqExact on %s: bitmapCardinality=%s uniqExact=%s. Java expected=%s",
                        table, actualCard, actualUniq, expectedCardinality));
            }
            if (!actualCard.equals(expectedCardinality)) {
                throw new AssertionError(String.format(
                        "bitmapCardinality != Java distinct count on %s: CH=%s Java=%s",
                        table, actualCard, expectedCardinality));
            }

            Set<Integer> intersection = new HashSet<>(group0);
            intersection.retainAll(group1);
            String expectedIntersect = Integer.toString(intersection.size());

            String andCardQuery = "SELECT toString(bitmapAndCardinality("
                    + "bitmapBuild(groupArrayIf(a, g = 0)), "
                    + "bitmapBuild(groupArrayIf(a, g = 1)))) FROM " + table;
            logStmt(andCardQuery);
            String actualAndCard = readSingleValue(andCardQuery);

            if (!actualAndCard.equals(expectedIntersect)) {
                throw new AssertionError(String.format(
                        "bitmapAndCardinality != Java intersection size on %s: CH=%s Java=%s. "
                                + "group0 distinct=%s group1 distinct=%s",
                        table, actualAndCard, expectedIntersect, group0, group1));
            }
        } finally {
            dropQuietly(table);
        }
    }

    private void assertProbe(String table, String projection, List<String> expected, String label) throws SQLException {
        String query = "SELECT " + projection + " FROM " + table + " ORDER BY k";
        logStmt(query);
        List<String> actual = ComparatorHelper.getResultSetFirstColumnAsString(query, errors, state);
        if (actual.size() != expected.size()) {
            throw new AssertionError(String.format(
                    "bit-function ground-truth row-count mismatch (%s): Java expects %d rows but query returned %d. Q: %s",
                    label, expected.size(), actual.size(), query));
        }
        for (int i = 0; i < expected.size(); i++) {
            if (!expected.get(i).equals(actual.get(i))) {
                throw new AssertionError(String.format(
                        "bit-function ground-truth value mismatch (%s) at row %d: Java expects %s but query returned %s. Q: %s",
                        label, i, expected.get(i), actual.get(i), query));
            }
        }
    }

    private String readSingleValue(String query) throws SQLException {
        List<String> rows = ComparatorHelper.getResultSetFirstColumnAsString(query, errors, state);
        if (rows.size() != 1) {
            throw new IgnoreMeException();
        }
        return rows.get(0);
    }

    private void dropQuietly(String table) {
        try {
            new SQLQueryAdapter("DROP TABLE IF EXISTS " + table, errors, true).execute(state);
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
