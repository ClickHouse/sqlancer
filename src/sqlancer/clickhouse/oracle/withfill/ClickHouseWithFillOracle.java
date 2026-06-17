package sqlancer.clickhouse.oracle.withfill;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
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

public class ClickHouseWithFillOracle implements TestOracle<ClickHouseGlobalState> {

    private static final AtomicLong CTR = new AtomicLong();

    private final ClickHouseGlobalState state;
    private final ExpectedErrors errors = new ExpectedErrors();

    public ClickHouseWithFillOracle(ClickHouseGlobalState state) {
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
        errors.add("WITH FILL");
        errors.add("ILLEGAL_TYPE_OF_ARGUMENT");
    }

    @Override
    public void check() throws SQLException {
        if (!state.getClickHouseOptions().withFillOracle) {
            throw new IgnoreMeException();
        }
        long id = CTR.incrementAndGet();
        String table = state.getDatabaseName() + ".wfill_" + id;
        try {
            if (!execute("CREATE TABLE " + table + " (x Int64) ENGINE = MergeTree ORDER BY tuple()")) {
                throw new IgnoreMeException();
            }

            Randomly r = state.getRandomly();
            int step = 1 + (int) r.getInteger(0, 3);
            long from = r.getInteger(-20, 20);
            int n = 8 + (int) r.getInteger(0, 13);
            long to = from + (long) n * step;

            List<Long> grid = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                grid.add(from + (long) i * step);
            }

            Set<Long> subset = pickNonEmptySubset(r, grid);
            StringBuilder sb = new StringBuilder("INSERT INTO ").append(table).append(" (x) VALUES ");
            boolean first = true;
            for (long v : subset) {
                if (!first) {
                    sb.append(", ");
                }
                first = false;
                sb.append('(').append(v).append(')');
            }
            if (!execute(sb.toString())) {
                throw new IgnoreMeException();
            }

            String query = "SELECT x FROM (SELECT x FROM " + table + ") ORDER BY x WITH FILL FROM " + from + " TO " + to
                    + " STEP " + step;

            List<String> actual = ComparatorHelper.getResultSetFirstColumnAsString(query, errors, state);
            logStmt(query);

            List<String> expected = new ArrayList<>(n);
            for (long v : grid) {
                expected.add(String.valueOf(v));
            }

            if (actual.size() != expected.size()) {
                throw new AssertionError(String.format(
                        "WITH FILL row-count mismatch: expected %d rows (grid %s) but got %d rows (actual %s). "
                                + "inserted=%s step=%d from=%d to=%d. Q: %s",
                        expected.size(), expected, actual.size(), actual, subset, step, from, to, query));
            }
            for (int i = 0; i < expected.size(); i++) {
                if (!expected.get(i).equals(actual.get(i))) {
                    throw new AssertionError(String.format(
                            "WITH FILL value mismatch at position %d: expected %s but got %s. "
                                    + "full expected=%s full actual=%s inserted=%s step=%d from=%d to=%d. Q: %s",
                            i, expected.get(i), actual.get(i), expected, actual, subset, step, from, to, query));
                }
            }
        } finally {
            dropQuietly(table);
        }
    }

    private Set<Long> pickNonEmptySubset(Randomly r, List<Long> grid) {
        Set<Long> chosen = new LinkedHashSet<>();
        for (long v : grid) {
            if (r.getBoolean()) {
                chosen.add(v);
            }
        }
        if (chosen.isEmpty()) {
            chosen.add(grid.get((int) r.getInteger(0, grid.size())));
        }
        return chosen;
    }

    private boolean execute(String stmt) throws SQLException {
        logStmt(stmt);
        return new SQLQueryAdapter(stmt, errors, true).execute(state);
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
