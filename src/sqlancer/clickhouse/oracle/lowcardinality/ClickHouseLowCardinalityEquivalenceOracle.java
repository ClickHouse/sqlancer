package sqlancer.clickhouse.oracle.lowcardinality;

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

public class ClickHouseLowCardinalityEquivalenceOracle implements TestOracle<ClickHouseGlobalState> {

    private static final AtomicLong CTR = new AtomicLong();

    private static final String[] PLAIN = { "c_i", "c_s", "c_n", "c_f" };
    private static final String[] LOW = { "l_i", "l_s", "l_n", "l_f" };

    private static final String[] FOUR_CHARS = { "aaaa", "bbbb", "cccc", "k000", "k001", "zzzz" };

    private final ClickHouseGlobalState state;
    private final ExpectedErrors errors = new ExpectedErrors();

    public ClickHouseLowCardinalityEquivalenceOracle(ClickHouseGlobalState state) {
        this.state = state;
        ClickHouseErrors.addExpectedExpressionErrors(errors);
        ClickHouseErrors.addSessionSettingsErrors(errors);
        errors.add("UNKNOWN_TABLE");
        errors.add("MEMORY_LIMIT_EXCEEDED");
        errors.add("memory limit exceeded");
        errors.add("TIMEOUT_EXCEEDED");
        errors.add("Limit for result exceeded");
        errors.add("TOO_MANY_ROWS_OR_BYTES");
    }

    @Override
    public void check() throws SQLException {
        if (!state.getClickHouseOptions().lowCardinalityEquivalenceOracle) {
            throw new IgnoreMeException();
        }
        long id = CTR.incrementAndGet();
        Randomly r = state.getRandomly();
        String table = state.getDatabaseName() + ".lceq_" + id;

        String create = "CREATE TABLE " + table + " (" + "c_i Int32, l_i LowCardinality(Int32), "
                + "c_s String, l_s LowCardinality(String), " + "c_n Nullable(Int32), l_n LowCardinality(Nullable(Int32)), "
                + "c_f FixedString(4), l_f LowCardinality(FixedString(4))" + ") ENGINE = MergeTree ORDER BY tuple()";

        try {
            logStmt(create);
            if (!new SQLQueryAdapter(create, errors, true).execute(state)) {
                throw new IgnoreMeException();
            }
            String values = buildValues(r);
            String insert = "INSERT INTO " + table + " VALUES " + values;
            logStmt(insert);
            if (!new SQLQueryAdapter(insert, errors, true).execute(state)) {
                throw new IgnoreMeException();
            }

            String where = buildPredicate(r);
            String whereA = where == null ? "" : " WHERE " + render(where, PLAIN);
            String whereB = where == null ? "" : " WHERE " + render(where, LOW);

            int arm = r.getInteger(0, 4);
            String projTemplate;
            String tail = "";
            switch (arm) {
            case 0:
                projTemplate = "toString(tuple({0}, {1}, {2}, {3}))";
                break;
            case 1:
                projTemplate = "concat(toString({0}), '@', toString(count()))";
                tail = " GROUP BY {0}";
                break;
            case 2:
                projTemplate = "toString(uniqExact({0}))";
                break;
            default:
                projTemplate = "concat(toString({0}), '#', toString({1}), '@', toString(count()))";
                tail = " GROUP BY {0}, {1}";
                break;
            }

            String queryA = "SELECT " + render(projTemplate, PLAIN) + " FROM " + table + whereA + render(tail, PLAIN);
            String queryB = "SELECT " + render(projTemplate, LOW) + " FROM " + table + whereB + render(tail, LOW);

            List<String> rowsA = ComparatorHelper.getResultSetFirstColumnAsString(queryA, errors, state);
            List<String> rowsB = ComparatorHelper.getResultSetFirstColumnAsString(queryB, errors, state);
            ComparatorHelper.assumeResultSetsAreEqual(rowsA, rowsB, queryA, List.of(queryB), state);
        } finally {
            dropQuietly(table);
        }
    }

    private String render(String template, String[] cols) {
        String out = template;
        for (int i = 0; i < cols.length; i++) {
            out = out.replace("{" + i + "}", cols[i]);
        }
        return out;
    }

    private String buildValues(Randomly r) {
        int rows = 20 + r.getInteger(0, 61);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rows; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            int vi = r.getInteger(-100, 100);
            String vs = "'k" + r.getInteger(0, 8) + "'";
            String vn = Randomly.getBooleanWithRatherLowProbability() ? "NULL" : String.valueOf(r.getInteger(-100, 100));
            String vf = "'" + FOUR_CHARS[r.getInteger(0, FOUR_CHARS.length)] + "'";
            sb.append('(').append(vi).append(", ").append(vi).append(", ").append(vs).append(", ").append(vs)
                    .append(", ").append(vn).append(", ").append(vn).append(", ").append(vf).append(", ").append(vf)
                    .append(')');
        }
        return sb.toString();
    }

    private String buildPredicate(Randomly r) {
        int kind = r.getInteger(0, 6);
        switch (kind) {
        case 0:
            return null;
        case 1:
            return "{0} > " + r.getInteger(-100, 100);
        case 2:
            return "{1} != 'k3'";
        case 3:
            return "{2} IS NOT NULL";
        case 4:
            return "{3} = '" + FOUR_CHARS[r.getInteger(0, FOUR_CHARS.length)] + "'";
        default:
            return "({0} % " + (2 + r.getInteger(0, 7)) + " = 0)";
        }
    }

    private void logStmt(String stmt) {
        if (state.getOptions().logEachSelect()) {
            state.getLogger().writeCurrent(stmt);
            state.getState().logStatement(stmt);
        }
    }

    private void dropQuietly(String table) {
        try {
            new SQLQueryAdapter("DROP TABLE IF EXISTS " + table, errors, true).execute(state);
        } catch (Exception | AssertionError ignored) {
        }
    }
}
