package sqlancer.clickhouse.oracle.window;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicLong;

import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.clickhouse.ClickHouseErrors;
import sqlancer.clickhouse.ClickHouseProvider.ClickHouseGlobalState;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;

public class ClickHouseWindowFrameOracle implements TestOracle<ClickHouseGlobalState> {

    private static final AtomicLong CTR = new AtomicLong();

    private final ClickHouseGlobalState state;
    private final ExpectedErrors errors = new ExpectedErrors();

    public ClickHouseWindowFrameOracle(ClickHouseGlobalState state) {
        this.state = state;
        ClickHouseErrors.addSessionSettingsErrors(errors);
        errors.add("UNKNOWN_TABLE");
        errors.add("Unknown table expression identifier");
        errors.add("(MEMORY_LIMIT_EXCEEDED)");
        errors.add("memory limit exceeded");
        errors.add("TIMEOUT_EXCEEDED");
        errors.add("Timeout exceeded");
        errors.add("Limit for result exceeded");
        errors.add("TOO_MANY_ROWS_OR_BYTES");
        errors.add("Window frame");
        errors.add("frame");
        errors.add("lagInFrame");
        errors.add("NOT_IMPLEMENTED");
        errors.add("SYNTAX_ERROR");
    }

    @Override
    public void check() throws SQLException {
        if (!state.getClickHouseOptions().windowFrameOracle) {
            throw new IgnoreMeException();
        }
        long id = CTR.incrementAndGet();
        String table = state.getDatabaseName() + ".winf_" + id + "_t";
        Randomly r = state.getRandomly();
        String create = "CREATE TABLE " + table
                + " (p Int32, k Int64, x Int64) ENGINE = MergeTree ORDER BY (p, k)";
        try {
            logStmt(create);
            if (!new SQLQueryAdapter(create, errors, true).execute(state)) {
                throw new IgnoreMeException();
            }

            int partitions = 2 + r.getInteger(0, 4);
            int blocks = 1 + r.getInteger(0, 3);
            long[] nextKey = new long[partitions];
            int totalRows = 0;
            for (int b = 0; b < blocks; b++) {
                StringBuilder sb = new StringBuilder("INSERT INTO ").append(table).append(" (p, k, x) VALUES ");
                int rowsThisBlock = 0;
                for (int pIdx = 0; pIdx < partitions; pIdx++) {
                    int rows = 4 + r.getInteger(0, 12);
                    for (int i = 0; i < rows; i++) {
                        long k = nextKey[pIdx];
                        nextKey[pIdx] = k + 1 + r.getInteger(0, 5);
                        long x = r.getInteger(-1000, 1000);
                        if (rowsThisBlock > 0) {
                            sb.append(", ");
                        }
                        sb.append('(').append(pIdx).append(", ").append(k).append(", ").append(x).append(')');
                        rowsThisBlock++;
                        totalRows++;
                    }
                }
                if (rowsThisBlock == 0) {
                    continue;
                }
                logStmt(sb.toString());
                if (!new SQLQueryAdapter(sb.toString(), errors, true).execute(state)) {
                    throw new IgnoreMeException();
                }
            }
            if (totalRows == 0) {
                throw new IgnoreMeException();
            }

            assertFrameEquivalence(table);
        } finally {
            dropQuietly(table);
        }
    }

    private void assertFrameEquivalence(String table) throws SQLException {
        String defaultFrame = "sum(x) OVER (PARTITION BY p ORDER BY k)";
        String rangeFrame = "sum(x) OVER (PARTITION BY p ORDER BY k RANGE BETWEEN UNBOUNDED PRECEDING "
                + "AND CURRENT ROW)";
        String rowsFrame = "sum(x) OVER (PARTITION BY p ORDER BY k ROWS BETWEEN UNBOUNDED PRECEDING "
                + "AND CURRENT ROW)";
        String lagFrame = "lagInFrame(x, 1) OVER (PARTITION BY p ORDER BY k ROWS BETWEEN 1 PRECEDING "
                + "AND CURRENT ROW)";
        String prevFrame = "any(x) OVER (PARTITION BY p ORDER BY k ROWS BETWEEN 1 PRECEDING AND 1 PRECEDING)";
        String rowNumber = "row_number() OVER (PARTITION BY p ORDER BY k)";

        String query = "SELECT toString(p) AS gp, toString(k) AS gk, toString(" + defaultFrame + ") AS va, toString("
                + rangeFrame + ") AS vb, toString(" + rowsFrame + ") AS vc, toString(" + lagFrame
                + ") AS vd, isNull(" + prevFrame + ") AS ve_null, toString(" + prevFrame + ") AS ve_val, toUInt8(("
                + rowNumber + ") > 1) AS not_first FROM " + table + " ORDER BY p, k, x";

        logStmt(query);

        try (Statement s = state.getConnection().createStatement(); ResultSet rs = s.executeQuery(query)) {
            long rowIdx = 0;
            while (rs.next()) {
                String gp = rs.getString(1);
                String gk = rs.getString(2);
                String va = rs.getString(3);
                String vb = rs.getString(4);
                String vc = rs.getString(5);
                String vd = rs.getString(6);
                int eNull = rs.getInt(7);
                String veVal = rs.getString(8);
                int notFirst = rs.getInt(9);

                if (!safeEquals(va, vb)) {
                    throw mismatch("default-frame != RANGE-UNBOUNDED-PRECEDING-CURRENT", query, gp, gk, rowIdx, va, vb);
                }
                if (!safeEquals(va, vc)) {
                    throw mismatch("default-frame != ROWS-UNBOUNDED-PRECEDING-CURRENT", query, gp, gk, rowIdx, va, vc);
                }
                if (notFirst == 1) {
                    if (eNull == 1) {
                        throw mismatch("prev-row frame unexpectedly NULL on non-first row", query, gp, gk, rowIdx, vd,
                                "NULL");
                    }
                    if (!safeEquals(vd, veVal)) {
                        throw mismatch("lagInFrame(1) != any-over-[1 PRECEDING,1 PRECEDING]", query, gp, gk, rowIdx, vd,
                                veVal);
                    }
                }
                rowIdx++;
            }
        } catch (SQLException ex) {
            if (ex.getMessage() != null && errors.errorIsExpected(ex.getMessage())) {
                throw new IgnoreMeException();
            }
            throw ex;
        }
    }

    private static boolean safeEquals(String a, String b) {
        if (a == null) {
            return b == null;
        }
        return a.equals(b);
    }

    private static AssertionError mismatch(String label, String query, String p, String k, long rowIdx, String left,
            String right) {
        return new AssertionError(String.format(
                "window-frame mismatch [%s] at row %d (p=%s, k=%s):%n  Q: %s%n  left=%s%n  right=%s", label, rowIdx, p,
                k, query, left, right));
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
