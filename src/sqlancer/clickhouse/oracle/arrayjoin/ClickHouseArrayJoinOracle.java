package sqlancer.clickhouse.oracle.arrayjoin;

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

public class ClickHouseArrayJoinOracle implements TestOracle<ClickHouseGlobalState> {

    private static final AtomicLong CTR = new AtomicLong();

    private final ClickHouseGlobalState state;

    private final ExpectedErrors errors = new ExpectedErrors();

    public ClickHouseArrayJoinOracle(ClickHouseGlobalState state) {
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
        errors.add("Cannot ARRAY JOIN");
        errors.add("ILLEGAL_TYPE_OF_ARGUMENT");
    }

    @Override
    public void check() throws SQLException {
        if (!state.getClickHouseOptions().arrayJoinOracle) {
            throw new IgnoreMeException();
        }
        long id = CTR.incrementAndGet();
        String table = state.getDatabaseName() + ".arrjoin_" + id;
        try {
            runCheck(table);
        } finally {
            dropQuietly(table);
        }
    }

    private void runCheck(String table) throws SQLException {
        String create = "CREATE TABLE " + table
                + " (k UInt32, arr Array(Int64)) ENGINE = MergeTree ORDER BY k";
        logStmt(create);
        if (!new SQLQueryAdapter(create, errors, true).execute(state)) {
            throw new IgnoreMeException();
        }

        Randomly r = state.getRandomly();
        int rows = 20 + (int) r.getInteger(0, 21);

        List<List<Long>> model = new ArrayList<>(rows);
        StringBuilder sb = new StringBuilder("INSERT INTO ").append(table).append(" (k, arr) VALUES ");
        for (int i = 0; i < rows; i++) {
            int len = (int) r.getInteger(0, 6);
            List<Long> arr = new ArrayList<>(len);
            for (int j = 0; j < len; j++) {
                arr.add((long) r.getInteger(-50, 51));
            }
            model.add(arr);
            if (i > 0) {
                sb.append(", ");
            }
            sb.append('(').append(i).append(", ").append(renderArrayLiteral(arr)).append(')');
        }
        logStmt(sb.toString());
        if (!new SQLQueryAdapter(sb.toString(), errors, true).execute(state)) {
            throw new IgnoreMeException();
        }

        long javaInnerCount = 0;
        long javaLeftCount = 0;
        long javaSum = 0;
        for (List<Long> arr : model) {
            javaInnerCount += arr.size();
            javaLeftCount += arr.isEmpty() ? 1 : arr.size();
            for (long v : arr) {
                javaSum += v;
            }
        }

        if (javaInnerCount == 0) {
            throw new IgnoreMeException();
        }

        String javaInnerStr = String.valueOf(javaInnerCount);
        String javaLeftStr = String.valueOf(javaLeftCount);
        String javaSumStr = String.valueOf(javaSum);

        String chInnerAggQuery = "SELECT toString(sum(length(arr))) FROM " + table;
        String chInnerJoinQuery = "SELECT toString(count()) FROM " + table + " ARRAY JOIN arr";
        String chLeftAggQuery = "SELECT toString(sum(if(empty(arr), 1, length(arr)))) FROM " + table;
        String chLeftJoinQuery = "SELECT toString(count()) FROM " + table + " LEFT ARRAY JOIN arr";
        String chSumAggQuery = "SELECT toString(sum(arraySum(arr))) FROM " + table;
        String chSumJoinQuery = "SELECT toString(sum(a)) FROM " + table + " ARRAY JOIN arr AS a";

        String chInnerAgg = readSingleValue(chInnerAggQuery);
        String chInnerJoin = readSingleValue(chInnerJoinQuery);
        if (!chInnerJoin.equals(chInnerAgg) || !chInnerJoin.equals(javaInnerStr)) {
            throw new AssertionError(String.format(
                    "ARRAY JOIN inner cardinality mismatch (invariant 1): "
                            + "ARRAY JOIN count=%s, sum(length(arr))=%s, Java=%s. Table: %s",
                    chInnerJoin, chInnerAgg, javaInnerStr, table));
        }

        String chLeftAgg = readSingleValue(chLeftAggQuery);
        String chLeftJoin = readSingleValue(chLeftJoinQuery);
        if (!chLeftJoin.equals(chLeftAgg) || !chLeftJoin.equals(javaLeftStr)) {
            throw new AssertionError(String.format(
                    "LEFT ARRAY JOIN cardinality mismatch (invariant 2): "
                            + "LEFT ARRAY JOIN count=%s, sum(if(empty,1,length))=%s, Java=%s. Table: %s",
                    chLeftJoin, chLeftAgg, javaLeftStr, table));
        }

        String chSumAgg = readSingleValue(chSumAggQuery);
        String chSumJoin = readSingleValue(chSumJoinQuery);
        if (!chSumJoin.equals(chSumAgg) || !chSumJoin.equals(javaSumStr)) {
            throw new AssertionError(String.format(
                    "ARRAY JOIN element sum mismatch (invariant 3): "
                            + "ARRAY JOIN sum(a)=%s, sum(arraySum(arr))=%s, Java=%s. Table: %s",
                    chSumJoin, chSumAgg, javaSumStr, table));
        }
    }

    static String renderArrayLiteral(List<Long> elems) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < elems.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(elems.get(i));
        }
        return sb.append(']').toString();
    }

    private String readSingleValue(String query) throws SQLException {
        logStmt(query);
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
