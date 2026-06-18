package sqlancer.clickhouse.oracle.datetime;

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

public class ClickHouseExtendedDatetimeOracle implements TestOracle<ClickHouseGlobalState> {

    private static final AtomicLong EDT_COUNTER = new AtomicLong();

    private static final List<String> FUNCTIONS = List.of("toStartOfYear", "toStartOfMonth", "toStartOfQuarter",
            "toStartOfWeek", "toMonday", "toLastDayOfMonth");

    private static final List<String> COMPARATORS = List.of("<", "<=", ">", ">=", "=", "!=");

    private static final List<String> CONSTANTS = List.of("toDate('1970-06-15')", "toDate('1971-01-01')",
            "toDate('1995-06-15')", "toDate('2021-06-15')", "toDate32('1969-12-31')", "toDate32('1905-06-15')");

    private final ClickHouseGlobalState state;
    private final ExpectedErrors errors = new ExpectedErrors();

    public ClickHouseExtendedDatetimeOracle(ClickHouseGlobalState state) {
        this.state = state;

        ClickHouseErrors.addSessionSettingsErrors(errors);

        errors.add("UNKNOWN_TABLE");
        errors.add("Unknown table expression identifier");

        errors.add("(MEMORY_LIMIT_EXCEEDED)");
        errors.add("memory limit exceeded");

        errors.add("TIMEOUT_EXCEEDED");
        errors.add("Timeout exceeded");
    }

    @Override
    public void check() throws SQLException {
        long id = EDT_COUNTER.incrementAndGet();
        String t = state.getDatabaseName() + ".edt_" + id;

        boolean pre1970 = Randomly.getBoolean();
        boolean merged = Randomly.getBoolean();

        String create = "CREATE TABLE " + t + " (d Date32) ENGINE = MergeTree ORDER BY tuple()";

        int rows = 200 + (int) Randomly.getNotCachedInteger(0, 800);
        String seedA = "INSERT INTO " + t + " SELECT toDate32('1971-01-01') + toIntervalDay(number % 18000) "
                + "FROM numbers(" + rows + ")";
        String seedB = "INSERT INTO " + t + " SELECT toDate32('1980-01-01') + toIntervalDay((number * 7) % 9000) "
                + "FROM numbers(" + rows / 2 + ")";
        String seedPre = "INSERT INTO " + t + " SELECT toDate32('1905-01-01') + toIntervalDay(number * 30) "
                + "FROM numbers(9)";

        try {
            logStmt(create);
            if (!new SQLQueryAdapter(create, errors, true).execute(state)) {
                throw new IgnoreMeException();
            }
            for (String stmt : List.of(seedA, seedB)) {
                logStmt(stmt);
                if (!new SQLQueryAdapter(stmt, errors, false).execute(state)) {
                    throw new IgnoreMeException();
                }
            }
            if (pre1970) {
                logStmt(seedPre);
                if (!new SQLQueryAdapter(seedPre, errors, false).execute(state)) {
                    throw new IgnoreMeException();
                }
            }
            if (merged) {
                String optimize = "OPTIMIZE TABLE " + t + " FINAL";
                logStmt(optimize);
                if (!new SQLQueryAdapter(optimize, errors, false).execute(state)) {
                    throw new IgnoreMeException();
                }
            }

            String f = Randomly.fromList(FUNCTIONS);
            String op = Randomly.fromList(COMPARATORS);
            String constant = Randomly.fromList(CONSTANTS);

            String pred = f + "(d) " + op + " " + f + "(" + constant + ")";

            for (int v = 0; v <= 1; v++) {

                String settings = " SETTINGS enable_extended_results_for_datetime_functions = " + v;
                String filterQuery = "SELECT toString(count()) FROM " + t + " WHERE " + pred + settings;
                String rowEvalQuery = "SELECT toString(countIf(" + pred + ")) FROM " + t + settings;
                logStmt(filterQuery);
                String filterCount = readSingleValue(filterQuery);
                logStmt(rowEvalQuery);
                String rowEvalCount = readSingleValue(rowEvalQuery);
                if (!filterCount.equals(rowEvalCount)) {
                    throw new AssertionError(String.format(
                            "extended-datetime filter mismatch (setting=%d, merged=%b, pre1970=%b): WHERE-path count "
                                    + "%s != row-evaluation count %s. filter query: %s -- ground truth: %s",
                            v, merged, pre1970, filterCount, rowEvalCount, filterQuery, rowEvalQuery));
                }
            }
        } finally {
            try {
                new SQLQueryAdapter("DROP TABLE IF EXISTS " + t, errors, true).execute(state);
            } catch (Exception | AssertionError ignored) {

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

    private void logStmt(String stmt) {
        if (state.getOptions().logEachSelect()) {
            state.getLogger().writeCurrent(stmt);
            state.getState().logStatement(stmt);
        }
    }
}
