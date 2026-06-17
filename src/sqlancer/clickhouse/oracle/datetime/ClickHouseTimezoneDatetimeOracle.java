package sqlancer.clickhouse.oracle.datetime;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import sqlancer.ComparatorHelper;
import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.clickhouse.ClickHouseErrors;
import sqlancer.clickhouse.ClickHouseProvider.ClickHouseGlobalState;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.ExpectedErrors;

public class ClickHouseTimezoneDatetimeOracle implements TestOracle<ClickHouseGlobalState> {

    private static final String GEN_SUBQUERY =
            "(SELECT toDateTime('1990-01-01 00:00:00') + toIntervalHour(number * 37) AS d, "
            + "toDate32('1955-01-01') + toIntervalDay(number * 11) AS d32 FROM numbers(300))";

    private static final List<String> IDENTITIES = List.of(
            "toStartOfInterval(d, INTERVAL 1 MONTH) = toStartOfMonth(d)",
            "toStartOfInterval(d, INTERVAL 1 YEAR) = toStartOfYear(d)",
            "toStartOfInterval(d, INTERVAL 1 DAY) = toStartOfDay(d)",
            "toStartOfInterval(d, INTERVAL 1 HOUR) = toStartOfHour(d)",
            "dateDiff('day', d, d) = 0",
            "dateDiff('second', d, d + INTERVAL 1 HOUR) = 3600",
            "dateDiff('day', d32, d32 + toIntervalDay(5)) = 5",
            "toTimeZone(toTimeZone(d, 'UTC'), 'UTC') = toTimeZone(d, 'UTC')",
            "dateDiff('hour', d, d + toIntervalHour(5)) = 5",
            "toStartOfMonth(d) <= d AND toStartOfYear(d) <= toStartOfMonth(d)"
    );

    private final ClickHouseGlobalState state;
    private final ExpectedErrors errors = new ExpectedErrors();

    public ClickHouseTimezoneDatetimeOracle(ClickHouseGlobalState state) {
        this.state = state;
        ClickHouseErrors.addSessionSettingsErrors(errors);
        ClickHouseErrors.addExpectedExpressionErrors(errors);
        errors.add("(MEMORY_LIMIT_EXCEEDED)");
        errors.add("memory limit exceeded");
        errors.add("TIMEOUT_EXCEEDED");
        errors.add("Timeout exceeded");
        errors.add("Limit for result exceeded");
        errors.add("TOO_MANY_ROWS_OR_BYTES");
        errors.add("NOT_IMPLEMENTED");
        errors.add("ILLEGAL_TYPE_OF_ARGUMENT");
        errors.add("DECIMAL_OVERFLOW");
        errors.add("VALUE_IS_OUT_OF_RANGE_OF_DATA_TYPE");
    }

    @Override
    public void check() throws SQLException {
        if (!state.getClickHouseOptions().timezoneDatetimeOracle) {
            throw new IgnoreMeException();
        }

        List<String> candidates = new ArrayList<>(IDENTITIES);
        Collections.shuffle(candidates);
        int count = 3 + (int) Randomly.getNotCachedInteger(0, 3);
        List<String> selected = candidates.subList(0, Math.min(count, candidates.size()));

        for (String identity : selected) {
            String wrapper = "SELECT toString(countIf(NOT (" + identity + "))) FROM " + GEN_SUBQUERY;
            logStmt(wrapper);
            String result = readSingleValue(wrapper);
            if (result == null || !"0".equals(result)) {
                throw new AssertionError(String.format(
                        "timezone-datetime identity violated: [%s] — violation count: %s — query: %s",
                        identity, result, wrapper));
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
