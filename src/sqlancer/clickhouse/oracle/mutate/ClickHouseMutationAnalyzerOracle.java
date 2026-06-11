package sqlancer.clickhouse.oracle.mutate;

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

public class ClickHouseMutationAnalyzerOracle implements TestOracle<ClickHouseGlobalState> {

    private static final AtomicLong MUTAN_COUNTER = new AtomicLong();
    private static final String SENTINEL = "424242";

    private enum Kind {
        ALTER_UPDATE, ALTER_DELETE, LIGHTWEIGHT_UPDATE, LIGHTWEIGHT_DELETE, MATERIALIZE_COLUMN
    }

    private enum WhereShape {
        JOINED_DERIVED,
        SELF_REFERENCE,
        PLAIN_IN,
        ALIAS_COLUMN,
        VIRTUAL_COLUMN
    }

    private final ClickHouseGlobalState state;

    private final ExpectedErrors readErrors = new ExpectedErrors();
    private final ExpectedErrors mutationErrors = new ExpectedErrors();

    public ClickHouseMutationAnalyzerOracle(ClickHouseGlobalState state) {
        this.state = state;

        for (ExpectedErrors e : List.of(readErrors, mutationErrors)) {
            ClickHouseErrors.addSessionSettingsErrors(e);

            e.add("Mutations are not supported by");

            e.add("Lightweight update");
            e.add("lightweight update");
            e.add("allow_experimental_lightweight_update");
            e.add("SUPPORT_IS_DISABLED");
            e.add("is not supported for lightweight");
            e.add("Lightweight updates are not supported");

            e.add("UNKNOWN_TABLE");
            e.add("Unknown table expression identifier");

            e.add("(MEMORY_LIMIT_EXCEEDED)");
            e.add("memory limit exceeded");
        }

        readErrors.add("TIMEOUT_EXCEEDED");
        readErrors.add("Timeout exceeded");

        for (String pin : ClickHouseErrors.getKnownOpenMutationAnalyzerBugs()) {
            mutationErrors.add(pin);
        }
    }

    @Override
    public void check() throws SQLException {
        long id = MUTAN_COUNTER.incrementAndGet();
        String db = state.getDatabaseName();
        String tA = db + ".mutan_" + id + "_a";
        String tB = db + ".mutan_" + id + "_b";
        String tEdges = db + ".mutan_" + id + "_edges";

        Kind kind = Randomly.fromOptions(Kind.values());

        boolean memoryEngine = (kind == Kind.ALTER_UPDATE || kind == Kind.ALTER_DELETE)
                && Randomly.getBooleanWithSmallProbability();

        boolean patchEnabled = !memoryEngine && Randomly.getBoolean();
        boolean validateMutationQuery = Randomly.getBoolean();

        String engineClause = memoryEngine ? " ENGINE = Memory"
                : " ENGINE = MergeTree ORDER BY k"
                        + (patchEnabled ? " SETTINGS enable_block_number_column = 1, enable_block_offset_column = 1"
                                : "");
        String createA = "CREATE TABLE " + tA + " (k Int32, v Int64, marker Int64, al Int64 ALIAS (v + 7), "
                + "mz Int64 MATERIALIZED (v * 3 + 1), df Int64 DEFAULT 42)" + engineClause;
        String createB = "CREATE TABLE " + tB + " (k Int32, v Int64) ENGINE = MergeTree ORDER BY k";
        String createEdges = "CREATE TABLE " + tEdges + " (k Int32, v Int64) ENGINE = MergeTree ORDER BY k";

        int rowsA = 40 + (int) Randomly.getNotCachedInteger(0, 40);
        String seedA = "INSERT INTO " + tA + " (k, v, marker) SELECT toInt32(number), toInt64(number % 13), "
                + "toInt64(-1) FROM numbers(" + rowsA + ")";

        String seedB = "INSERT INTO " + tB + " SELECT toInt32(number * 2), toInt64(number % 7) FROM numbers(25)";
        String seedEdges = "INSERT INTO " + tEdges + " SELECT toInt32(number * 3), toInt64(number % 5) FROM numbers(20)";

        try {
            for (String stmt : List.of(createA, createB, createEdges)) {
                logStmt(stmt);
                if (!new SQLQueryAdapter(stmt, readErrors, true).execute(state)) {
                    throw new IgnoreMeException();
                }
            }
            for (String stmt : List.of(seedA, seedB, seedEdges)) {
                logStmt(stmt);
                if (!new SQLQueryAdapter(stmt, readErrors, true).execute(state)) {
                    throw new IgnoreMeException();
                }
            }

            if (kind == Kind.MATERIALIZE_COLUMN) {
                checkMaterializeColumn(tA, validateMutationQuery);
                return;
            }

            WhereShape shape = pickShape(memoryEngine);
            String pred = renderPredicate(shape, tA, tB, tEdges, patchEnabled);

            boolean assertConsistency = shape != WhereShape.VIRTUAL_COLUMN;

            String expected = null;
            String countBefore = null;
            if (assertConsistency) {
                String preCount = "SELECT toString(count()) FROM " + tA + " WHERE " + pred;
                logStmt(preCount);
                expected = readSingleValue(preCount);
                if (kind == Kind.ALTER_DELETE || kind == Kind.LIGHTWEIGHT_DELETE) {
                    String total = "SELECT toString(count()) FROM " + tA;
                    logStmt(total);
                    countBefore = readSingleValue(total);
                }
            }

            String mutation = renderMutation(kind, tA, pred, validateMutationQuery);
            logStmt(mutation);
            if (!new SQLQueryAdapter(mutation, mutationErrors, false).execute(state)) {

                throw new IgnoreMeException();
            }

            if (!assertConsistency) {
                return;
            }
            if (kind == Kind.ALTER_UPDATE || kind == Kind.LIGHTWEIGHT_UPDATE) {
                String actualQuery = "SELECT toString(countIf(marker = " + SENTINEL + ")) FROM " + tA;
                logStmt(actualQuery);
                String actual = readSingleValue(actualQuery);
                if (!expected.equals(actual)) {
                    throw new AssertionError(String.format(
                            "mutation-analyzer affected-rows mismatch: predicate matched %s rows pre-mutation but "
                                    + "%s rows carry the sentinel after. mutation: %s -- pre-count WHERE: %s",
                            expected, actual, mutation, pred));
                }
            } else {
                String total = "SELECT toString(count()) FROM " + tA;
                logStmt(total);
                String countAfter = readSingleValue(total);
                long deleted = Long.parseLong(countBefore) - Long.parseLong(countAfter);
                if (deleted != Long.parseLong(expected)) {
                    throw new AssertionError(String.format(
                            "mutation-analyzer affected-rows mismatch: predicate matched %s rows pre-mutation but "
                                    + "the DELETE removed %d (count %s -> %s). mutation: %s -- pre-count WHERE: %s",
                            expected, deleted, countBefore, countAfter, mutation, pred));
                }
            }
        } finally {
            for (String t : List.of(tA, tB, tEdges)) {
                try {
                    new SQLQueryAdapter("DROP TABLE IF EXISTS " + t, readErrors, true).execute(state);
                } catch (Exception | AssertionError ignored) {

                }
            }
        }
    }

    private static WhereShape pickShape(boolean memoryEngine) {
        if (memoryEngine) {

            return Randomly.fromOptions(WhereShape.JOINED_DERIVED, WhereShape.PLAIN_IN);
        }
        return Randomly.fromOptions(WhereShape.values());
    }

    private static String renderPredicate(WhereShape shape, String tA, String tB, String tEdges,
            boolean patchEnabled) {
        String in = Randomly.getBoolean() ? " IN " : " NOT IN ";
        switch (shape) {
        case JOINED_DERIVED:

            return "k" + in + "(SELECT a.k FROM (SELECT k FROM " + tB + ") AS a JOIN " + tEdges
                    + " AS e ON e.k = a.k JOIN (SELECT k FROM " + tEdges + ") AS b ON b.k = e.k)";
        case SELF_REFERENCE:

            return "k" + in + "(SELECT k FROM " + tA + " WHERE v >= " + Randomly.getNotCachedInteger(0, 13) + ")";
        case PLAIN_IN:
            return "k" + in + "(SELECT k FROM " + tB + " WHERE v % 3 = " + Randomly.getNotCachedInteger(0, 3) + ")";
        case ALIAS_COLUMN: {

            String op = Randomly.fromOptions(">=", "<", "!=");
            int bound = "<".equals(op) ? 8 + (int) Randomly.getNotCachedInteger(0, 12)
                    : 7 + (int) Randomly.getNotCachedInteger(0, 13);
            return "al " + op + " " + bound;
        }
        case VIRTUAL_COLUMN:
            if (patchEnabled && Randomly.getBoolean()) {
                return "_block_number % 2 = 0";
            }
            return "_part_offset % 2 = " + Randomly.getNotCachedInteger(0, 2);
        default:
            throw new AssertionError(shape);
        }
    }

    private static String renderMutation(Kind kind, String tA, String pred, boolean validate) {
        String validateSetting = "validate_mutation_query = " + (validate ? 1 : 0);
        switch (kind) {
        case ALTER_UPDATE:
            return "ALTER TABLE " + tA + " UPDATE marker = " + SENTINEL + " WHERE " + pred
                    + " SETTINGS mutations_sync = 1, " + validateSetting;
        case ALTER_DELETE:
            return "ALTER TABLE " + tA + " DELETE WHERE " + pred + " SETTINGS mutations_sync = 1, " + validateSetting;
        case LIGHTWEIGHT_UPDATE:

            return "UPDATE " + tA + " SET marker = " + SENTINEL + " WHERE " + pred
                    + " SETTINGS enable_lightweight_update = 1, " + validateSetting;
        case LIGHTWEIGHT_DELETE:

            return "DELETE FROM " + tA + " WHERE " + pred + " SETTINGS lightweight_deletes_sync = 2, "
                    + validateSetting;
        default:
            throw new AssertionError(kind);
        }
    }

    private void checkMaterializeColumn(String tA, boolean validate) throws SQLException {

        boolean constantDefault = Randomly.getBoolean();
        String col = constantDefault ? "df" : "mz";
        String mutation = "ALTER TABLE " + tA + " MATERIALIZE COLUMN " + col + " SETTINGS mutations_sync = 1, "
                + "validate_mutation_query = " + (validate ? 1 : 0);
        logStmt(mutation);
        if (!new SQLQueryAdapter(mutation, mutationErrors, false).execute(state)) {
            throw new IgnoreMeException();
        }

        String mismatchQuery = constantDefault ? "SELECT toString(countIf(df != 42)) FROM " + tA
                : "SELECT toString(countIf(mz != (v * 3 + 1))) FROM " + tA;
        logStmt(mismatchQuery);
        String mismatches = readSingleValue(mismatchQuery);
        if (!"0".equals(mismatches)) {
            throw new AssertionError(String.format(
                    "mutation-analyzer MATERIALIZE COLUMN mismatch: %s rows diverge from the column expression "
                            + "after %s (checked via %s)",
                    mismatches, mutation, mismatchQuery));
        }
    }

    private String readSingleValue(String query) throws SQLException {
        List<String> rows = ComparatorHelper.getResultSetFirstColumnAsString(query, readErrors, state);
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
