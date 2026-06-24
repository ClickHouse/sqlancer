package sqlancer.clickhouse.oracle.join;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
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

public class ClickHousePasteJoinOracle implements TestOracle<ClickHouseGlobalState> {

    private static final AtomicLong CTR = new AtomicLong();
    private static final int ID_SPACE = 200;

    private final ClickHouseGlobalState state;

    private final ExpectedErrors createErrors = new ExpectedErrors();
    private final ExpectedErrors readErrors = new ExpectedErrors();

    public ClickHousePasteJoinOracle(ClickHouseGlobalState state) {
        this.state = state;
        for (ExpectedErrors e : List.of(createErrors, readErrors)) {
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
            e.add("Unsupported");
            e.add("PASTE");
            e.add("AMBIGUOUS_COLUMN_NAME");
        }
    }

    @Override
    public void check() throws SQLException {
        if (!state.getClickHouseOptions().pasteJoinOracle) {
            throw new IgnoreMeException();
        }
        long id = CTR.incrementAndGet();
        Randomly r = state.getRandomly();
        String tableA = state.getDatabaseName() + ".paste_a_" + id;
        String tableB = state.getDatabaseName() + ".paste_b_" + id;

        TreeMap<Long, Long> rowsA = new TreeMap<>();
        TreeMap<Long, Long> rowsB = new TreeMap<>();

        try {
            if (!execute("CREATE TABLE " + tableA + " (id Int64, x Int64) ENGINE = MergeTree ORDER BY tuple()",
                    createErrors)) {
                throw new IgnoreMeException();
            }
            if (!execute("CREATE TABLE " + tableB + " (id Int64, y Int64) ENGINE = MergeTree ORDER BY tuple()",
                    createErrors)) {
                throw new IgnoreMeException();
            }

            fillUnique(r, rowsA);
            fillUnique(r, rowsB);
            if (rowsA.isEmpty() || rowsB.isEmpty()) {
                throw new IgnoreMeException();
            }

            if (!execute("INSERT INTO " + tableA + " (id, x) VALUES " + renderValues(rowsA), readErrors)) {
                throw new IgnoreMeException();
            }
            if (!execute("INSERT INTO " + tableB + " (id, y) VALUES " + renderValues(rowsB), readErrors)) {
                throw new IgnoreMeException();
            }

            String query = "SELECT concat(toString(x), ',', toString(y)) FROM (SELECT x FROM " + tableA
                    + " ORDER BY id) AS a PASTE JOIN (SELECT y FROM " + tableB + " ORDER BY id) AS b";

            List<Long> orderedX = new ArrayList<>(rowsA.values());
            List<Long> orderedY = new ArrayList<>(rowsB.values());
            int n = Math.min(orderedX.size(), orderedY.size());
            List<String> expected = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                expected.add(orderedX.get(i) + "," + orderedY.get(i));
            }

            logStmt(query);
            List<String> actual = ComparatorHelper.getResultSetFirstColumnAsString(query, readErrors, state);

            if (actual.size() != expected.size()) {
                throw new AssertionError(String.format(
                        "PASTE JOIN row-count mismatch: Java zip expects %d rows but query returned %d.%n"
                                + "Q: %s%nordered A (x by id): %s%nordered B (y by id): %s%nexpected: %s%nactual: %s",
                        expected.size(), actual.size(), query, orderedX, orderedY, expected, actual));
            }
            for (int i = 0; i < expected.size(); i++) {
                if (!expected.get(i).equals(actual.get(i))) {
                    throw new AssertionError(String.format(
                            "PASTE JOIN positional mismatch at index %d: expected %s but query returned %s.%n"
                                    + "Q: %s%nordered A (x by id): %s%nordered B (y by id): %s",
                            i, expected.get(i), actual.get(i), query, orderedX, orderedY));
                }
            }
        } finally {
            dropQuietly(tableA);
            dropQuietly(tableB);
        }
    }

    private void fillUnique(Randomly r, TreeMap<Long, Long> rows) {
        int target = 10 + r.getInteger(0, 21);
        int emitted = 0;
        while (rows.size() < target && emitted < target * 4) {
            emitted++;
            long key = r.getInteger(0, ID_SPACE);
            if (rows.containsKey(key)) {
                continue;
            }
            rows.put(key, (long) r.getInteger(-50, 50));
        }
    }

    private String renderValues(TreeMap<Long, Long> rows) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (TreeMap.Entry<Long, Long> e : rows.entrySet()) {
            if (!first) {
                sb.append(", ");
            }
            first = false;
            sb.append('(').append(e.getKey()).append(", ").append(e.getValue()).append(')');
        }
        return sb.toString();
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
