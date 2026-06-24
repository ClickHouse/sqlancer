package sqlancer.clickhouse.oracle.engineq;

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

public class ClickHouseEngineEquivalenceOracle implements TestOracle<ClickHouseGlobalState> {

    private static final AtomicLong CTR = new AtomicLong();
    private static final int DIFF_LIMIT = 20;

    private static final String[] MIRROR_ENGINES = { "Memory", "TinyLog", "StripeLog", "Log" };

    private static final String[] COLUMN_DEFS = { "c0 Int64", "c1 Int32", "c2 String", "c3 Nullable(Int64)" };
    private static final String[] COLUMN_NAMES = { "c0", "c1", "c2", "c3" };

    private final ClickHouseGlobalState state;

    private final ExpectedErrors createErrors = new ExpectedErrors();
    private final ExpectedErrors writeErrors = new ExpectedErrors();
    private final ExpectedErrors readErrors = new ExpectedErrors();

    public ClickHouseEngineEquivalenceOracle(ClickHouseGlobalState state) {
        this.state = state;
        for (ExpectedErrors e : List.of(createErrors, writeErrors, readErrors)) {
            ClickHouseErrors.addExpectedExpressionErrors(e);
            ClickHouseErrors.addSessionSettingsErrors(e);

            e.add("UNKNOWN_TABLE");
            e.add("Unknown table expression identifier");

            e.add("UNKNOWN_STORAGE");
            e.add("Unknown table engine");
            e.add("SUPPORT_IS_DISABLED");
            e.add("NOT_IMPLEMENTED");
            e.add("experimental");

            e.add("(MEMORY_LIMIT_EXCEEDED)");
            e.add("memory limit exceeded");

            e.add("TIMEOUT_EXCEEDED");
            e.add("Timeout exceeded");

            e.add("Limit for result exceeded");
            e.add("TOO_MANY_ROWS_OR_BYTES");
        }
    }

    @Override
    public void check() throws SQLException {
        if (!state.getClickHouseOptions().engineEquivalenceOracle) {
            throw new IgnoreMeException();
        }
        long id = CTR.incrementAndGet();
        Randomly r = state.getRandomly();
        String mirrorEngine = MIRROR_ENGINES[(int) Randomly.getNotCachedInteger(0, MIRROR_ENGINES.length)];
        String baseTable = state.getDatabaseName() + ".engeq_" + id + "_mt";
        String mirrorTable = state.getDatabaseName() + ".engeq_" + id + "_mir";

        String columnList = String.join(", ", COLUMN_DEFS);
        String baseCreate = "CREATE TABLE " + baseTable + " (" + columnList + ") ENGINE = MergeTree ORDER BY tuple()";
        String mirrorCreate = "CREATE TABLE " + mirrorTable + " (" + columnList + ") ENGINE = " + mirrorEngine;

        try {
            logStmt(baseCreate);
            if (!new SQLQueryAdapter(baseCreate, createErrors, true).execute(state)) {
                throw new IgnoreMeException();
            }
            logStmt(mirrorCreate);
            if (!new SQLQueryAdapter(mirrorCreate, createErrors, true).execute(state)) {
                throw new IgnoreMeException();
            }

            String values = buildValues(r);
            String baseInsert = "INSERT INTO " + baseTable + " (" + String.join(", ", COLUMN_NAMES) + ") VALUES "
                    + values;
            String mirrorInsert = "INSERT INTO " + mirrorTable + " (" + String.join(", ", COLUMN_NAMES) + ") VALUES "
                    + values;
            logStmt(baseInsert);
            if (!new SQLQueryAdapter(baseInsert, writeErrors, true).execute(state)) {
                throw new IgnoreMeException();
            }
            logStmt(mirrorInsert);
            if (!new SQLQueryAdapter(mirrorInsert, writeErrors, true).execute(state)) {
                throw new IgnoreMeException();
            }

            String projection = "toString(tuple(" + String.join(", ", COLUMN_NAMES) + "))";
            String predicate = buildPredicate(r);
            String whereClause = predicate == null ? "" : " WHERE " + predicate;
            String baseQuery = "SELECT " + projection + " FROM " + baseTable + whereClause;
            String mirrorQuery = "SELECT " + projection + " FROM " + mirrorTable + whereClause;

            List<String> baseRows = ComparatorHelper.getResultSetFirstColumnAsString(baseQuery, readErrors, state);
            List<String> mirrorRows = ComparatorHelper.getResultSetFirstColumnAsString(mirrorQuery, readErrors, state);

            List<String> diff = multisetDiff(baseRows, mirrorRows, DIFF_LIMIT);
            if (!diff.isEmpty()) {
                throw new AssertionError(String.format(
                        "engine-equivalence multiset mismatch: MergeTree mirror returned %d rows vs %s returned %d "
                                + "rows.%nmergetree: %s%nmirror:    %s%nfirst %d differing entries "
                                + "(value (+count side)): %s",
                        baseRows.size(), mirrorEngine, mirrorRows.size(), baseQuery, mirrorQuery, diff.size(), diff));
            }
        } finally {
            dropQuietly(baseTable);
            dropQuietly(mirrorTable);
        }
    }

    private String buildValues(Randomly r) {
        int rows = 20 + r.getInteger(0, 81);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rows; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            long c0 = r.getInteger(-1000, 1000);
            int c1 = r.getInteger(-1000, 1000);
            String c2 = ClickHouseExpressionLiteral.esc(buildString(r));
            String c3 = Randomly.getBooleanWithRatherLowProbability() ? "NULL"
                    : String.valueOf(r.getInteger(-1000, 1000));
            sb.append('(').append(c0).append(", ").append(c1).append(", '").append(c2).append("', ").append(c3)
                    .append(')');
        }
        return sb.toString();
    }

    private String buildString(Randomly r) {
        int kind = r.getInteger(0, 4);
        switch (kind) {
        case 0:
            return "";
        case 1:
            return "alpha";
        case 2:
            return "beta gamma";
        default:
            return "k" + r.getInteger(0, 8);
        }
    }

    private String buildPredicate(Randomly r) {
        int kind = r.getInteger(0, 6);
        switch (kind) {
        case 0:
            return null;
        case 1:
            return "c0 > " + r.getInteger(-1000, 1000);
        case 2:
            return "c1 <= " + r.getInteger(-1000, 1000);
        case 3:
            return "c3 IS NOT NULL";
        case 4:
            return "c2 != ''";
        default:
            return "(c0 % " + (2 + r.getInteger(0, 9)) + " = 0)";
        }
    }

    static List<String> multisetDiff(List<String> a, List<String> b, int limit) {
        Map<String, Long> counts = new TreeMap<>();
        for (String s : a) {
            counts.merge(s == null ? "\\N" : s, 1L, Long::sum);
        }
        for (String s : b) {
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
            diff.add(e.getKey() + " (+" + Math.abs(c) + " " + (c > 0 ? "mergetree" : "mirror") + ")");
        }
        return diff;
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

    static final class ClickHouseExpressionLiteral {
        private ClickHouseExpressionLiteral() {
        }

        static String esc(String s) {
            return s.replace("\\", "\\\\").replace("'", "\\'");
        }
    }
}
