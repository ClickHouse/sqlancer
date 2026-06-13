package sqlancer.clickhouse.oracle.tokenbf;

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

public class ClickHouseTokenBfOracle implements TestOracle<ClickHouseGlobalState> {

    private static final AtomicLong CTR = new AtomicLong();

    static final List<String> VOCAB = List.of("alpha", "bravo", "charlie", "delta", "echo4", "foxtrot", "golf42",
            "hotel", "india", "juliet", "kilo9", "lima77", "mike", "november", "oscar", "papa8");

    enum Probe {
        HAS_TOKEN,
        EQUALITY,
        IN_SET
    }

    private final ClickHouseGlobalState state;

    private final ExpectedErrors createErrors = new ExpectedErrors();
    private final ExpectedErrors readErrors = new ExpectedErrors();

    public ClickHouseTokenBfOracle(ClickHouseGlobalState state) {
        this.state = state;
        for (ExpectedErrors e : List.of(createErrors, readErrors)) {
            ClickHouseErrors.addExpectedExpressionErrors(e);
            ClickHouseErrors.addSessionSettingsErrors(e);
            ClickHouseErrors.addTextIndexErrors(e);

            e.add("UNKNOWN_STORAGE");
            e.add("Unknown table engine");
            e.add("SUPPORT_IS_DISABLED");
            e.add("NOT_IMPLEMENTED");
            e.add("ILLEGAL_TYPE_OF_ARGUMENT");
            e.add("UNKNOWN_FUNCTION");
            e.add("Unknown function");
            e.add("Unknown setting");
            e.add("BAD_ARGUMENTS");
            e.add("SYNTAX_ERROR");
            e.add("Syntax error");
            e.add("experimental");
            e.add("allow_experimental");
            e.add("of bloom filter index");
            e.add("Unknown Index type");
            e.add("Unknown index type");

            e.add("UNKNOWN_TABLE");
            e.add("Unknown table expression identifier");

            e.add("(MEMORY_LIMIT_EXCEEDED)");
            e.add("memory limit exceeded");

            e.add("TIMEOUT_EXCEEDED");
            e.add("Timeout exceeded");
        }
        readErrors.add("INDEX_NOT_USED");
    }

    @Override
    public void check() throws SQLException {
        if (!state.getClickHouseOptions().tokenBfOracle) {
            throw new IgnoreMeException();
        }
        long id = CTR.incrementAndGet();
        String table = state.getDatabaseName() + ".tbf_" + id + "_t";
        Randomly r = state.getRandomly();

        int sizeBytes = Randomly.fromOptions(256, 512, 1024);
        int numHashes = Randomly.fromOptions(2, 3);
        String create = "CREATE TABLE " + table + " (id UInt32, s String, INDEX idx s TYPE tokenbf_v1(" + sizeBytes
                + ", " + numHashes + ", 0) GRANULARITY 1) ENGINE = MergeTree ORDER BY id";

        try {
            logStmt(create);
            if (!new SQLQueryAdapter(create, createErrors, true).execute(state)) {
                throw new IgnoreMeException();
            }

            int nextKey = 0;
            int blocks = 3 + r.getInteger(0, 3);
            for (int b = 0; b < blocks; b++) {
                int rows = 20 + r.getInteger(0, 31);
                StringBuilder sb = new StringBuilder("INSERT INTO ").append(table).append(" (id, s) VALUES ");
                for (int i = 0; i < rows; i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    sb.append('(').append(nextKey++).append(", '").append(esc(buildRow(r))).append("')");
                }
                logStmt(sb.toString());
                if (!new SQLQueryAdapter(sb.toString(), readErrors, true).execute(state)) {
                    throw new IgnoreMeException();
                }
            }

            Probe probe = Probe.values()[r.getInteger(0, Probe.values().length)];
            String predicate;
            String label;
            switch (probe) {
            case EQUALITY:
                predicate = "s = '" + esc(VOCAB.get(r.getInteger(0, VOCAB.size()))) + "'";
                label = "equality";
                break;
            case IN_SET:
                String c1 = VOCAB.get(r.getInteger(0, VOCAB.size()));
                String c2 = VOCAB.get(r.getInteger(0, VOCAB.size()));
                predicate = "s IN ('" + esc(c1) + "', '" + esc(c2) + "')";
                label = "in-set";
                break;
            default:
                predicate = "hasToken(s, '" + esc(VOCAB.get(r.getInteger(0, VOCAB.size()))) + "')";
                label = "hasToken";
                break;
            }

            String indexQuery = "SELECT toString(arraySort(groupArray(id))) FROM (SELECT id FROM " + table + " WHERE "
                    + predicate + ")";
            String scanQuery = indexQuery + " SETTINGS use_skip_indexes = 0";

            String indexKeys = readSingleValue(indexQuery);
            String scanKeys = readSingleValue(scanQuery);

            if (!indexKeys.equals(scanKeys)) {
                throw new AssertionError(String.format(
                        "tokenbf_v1 skip-index id-set mismatch (%s): predicate %s: index path %s vs scan path %s. "
                                + "A bloom filter must never drop a matching row. DDL: %s",
                        label, predicate, indexKeys, scanKeys, create));
            }
        } finally {
            dropQuietly(table);
        }
    }

    private static String buildRow(Randomly r) {
        int tokens = 2 + r.getInteger(0, 3);
        StringBuilder sb = new StringBuilder();
        for (int t = 0; t < tokens; t++) {
            if (t > 0) {
                sb.append(' ');
            }
            sb.append(VOCAB.get(r.getInteger(0, VOCAB.size())));
        }
        return sb.toString();
    }

    static String esc(String s) {
        return s.replace("\\", "\\\\").replace("'", "\\'");
    }

    private String readSingleValue(String query) throws SQLException {
        List<String> rows = new ArrayList<>(ComparatorHelper.getResultSetFirstColumnAsString(query, readErrors, state));
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
