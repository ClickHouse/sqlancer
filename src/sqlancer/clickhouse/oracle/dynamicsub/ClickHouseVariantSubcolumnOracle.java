package sqlancer.clickhouse.oracle.dynamicsub;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
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

public class ClickHouseVariantSubcolumnOracle implements TestOracle<ClickHouseGlobalState> {

    private static final AtomicLong CTR = new AtomicLong();

    enum Mode {
        VARIANT,
        JSON
    }

    private static final class Row {
        final long k;
        final boolean isInt;
        final long intValue;
        final String stringValue;

        private Row(long k, boolean isInt, long intValue, String stringValue) {
            this.k = k;
            this.isInt = isInt;
            this.intValue = intValue;
            this.stringValue = stringValue;
        }

        static Row ofInt(long k, long v) {
            return new Row(k, true, v, null);
        }

        static Row ofString(long k, String v) {
            return new Row(k, false, 0, v);
        }
    }

    private final ClickHouseGlobalState state;

    private final ExpectedErrors createErrors = new ExpectedErrors();
    private final ExpectedErrors readErrors = new ExpectedErrors();

    public ClickHouseVariantSubcolumnOracle(ClickHouseGlobalState state) {
        this.state = state;
        for (ExpectedErrors e : List.of(createErrors, readErrors)) {
            ClickHouseErrors.addSessionSettingsErrors(e);
            ClickHouseErrors.addExpectedExpressionErrors(e);

            e.add("UNKNOWN_TYPE");
            e.add("Unknown data type");
            e.add("SUPPORT_IS_DISABLED");
            e.add("NOT_IMPLEMENTED");
            e.add("ILLEGAL_TYPE_OF_ARGUMENT");
            e.add("experimental");
            e.add("allow_experimental");

            e.add("UNKNOWN_TABLE");
            e.add("Unknown table expression identifier");

            e.add("(MEMORY_LIMIT_EXCEEDED)");
            e.add("memory limit exceeded");

            e.add("(TIMEOUT_EXCEEDED)");
            e.add("Timeout exceeded");
        }
    }

    @Override
    public void check() throws SQLException {
        if (!state.getClickHouseOptions().variantSubcolumnOracle) {
            throw new IgnoreMeException();
        }
        enableExperimentalTypes();
        long id = CTR.incrementAndGet();
        Mode mode = Mode.values()[(int) Randomly.getNotCachedInteger(0, Mode.values().length)];
        if (mode == Mode.VARIANT) {
            checkVariant(id);
        } else {
            checkJson(id);
        }
    }

    private void enableExperimentalTypes() {
        for (String s : List.of("SET allow_experimental_variant_type = 1",
                "SET allow_experimental_dynamic_type = 1", "SET allow_experimental_json_type = 1",
                "SET use_variant_as_common_type = 1")) {
            try {
                logStmt(s);
                new SQLQueryAdapter(s, createErrors, true).execute(state);
            } catch (Exception | AssertionError ignored) {

            }
        }
    }

    private void checkVariant(long id) throws SQLException {
        String table = state.getDatabaseName() + ".var_" + id + "_t";
        Randomly r = state.getRandomly();
        String create = "CREATE TABLE " + table + " (k UInt32, v Variant(Int64, String)) ENGINE = MergeTree ORDER BY k";

        List<Row> corpus = new ArrayList<>();
        try {
            logStmt(create);
            if (!new SQLQueryAdapter(create, createErrors, true).execute(state)) {
                throw new IgnoreMeException();
            }
            int rows = 30 + r.getInteger(0, 41);
            StringBuilder sb = new StringBuilder("INSERT INTO ").append(table).append(" (k, v) VALUES ");
            for (int i = 0; i < rows; i++) {
                boolean asInt = Randomly.getBoolean();
                if (i > 0) {
                    sb.append(", ");
                }
                if (asInt) {
                    long v = r.getInteger(-1_000_000, 1_000_000);
                    corpus.add(Row.ofInt(i, v));
                    sb.append('(').append(i).append(", CAST(").append(v).append(" AS Int64))");
                } else {
                    String v = asciiWord(r);
                    corpus.add(Row.ofString(i, v));
                    sb.append('(').append(i).append(", CAST('").append(esc(v)).append("' AS String))");
                }
            }
            logStmt(sb.toString());
            if (!new SQLQueryAdapter(sb.toString(), readErrors, true).execute(state)) {
                throw new IgnoreMeException();
            }

            recordRawVariantProbe(table);

            String intSubcol = pickVariantSubcolumnSyntax("Int64");
            String stringSubcol = pickVariantSubcolumnSyntax("String");

            List<String> intExpected = new ArrayList<>();
            List<String> stringExpected = new ArrayList<>();
            List<String> typeExpected = new ArrayList<>();
            for (Row row : corpus) {
                intExpected.add(row.isInt ? String.valueOf(row.intValue) : "\\N");
                stringExpected.add(row.isInt ? "\\N" : row.stringValue);
                typeExpected.add(row.isInt ? "Int64" : "String");
            }

            String intQuery = "SELECT toString(" + intSubcol + ") FROM " + table + " ORDER BY k";
            String stringQuery = "SELECT toString(" + stringSubcol + ") FROM " + table + " ORDER BY k";
            String typeQuery = "SELECT toString(variantType(v)) FROM " + table + " ORDER BY k";

            assertColumnEquals(intQuery, intExpected, "Variant.Int64 subcolumn", create);
            assertColumnEquals(stringQuery, stringExpected, "Variant.String subcolumn", create);
            assertColumnEquals(typeQuery, typeExpected, "variantType(v)", create);
        } finally {
            dropQuietly(table);
        }
    }

    private void checkJson(long id) throws SQLException {
        String table = state.getDatabaseName() + ".json_" + id + "_t";
        Randomly r = state.getRandomly();
        String create = "CREATE TABLE " + table + " (k UInt32, j JSON) ENGINE = MergeTree ORDER BY k";

        List<Long> values = new ArrayList<>();
        try {
            logStmt(create);
            if (!new SQLQueryAdapter(create, createErrors, true).execute(state)) {
                throw new IgnoreMeException();
            }
            int rows = 30 + r.getInteger(0, 41);
            StringBuilder sb = new StringBuilder("INSERT INTO ").append(table).append(" (k, j) VALUES ");
            for (int i = 0; i < rows; i++) {
                long v = r.getInteger(-1_000_000, 1_000_000);
                values.add(v);
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append('(').append(i).append(", '{\"a\": ").append(v).append("}')");
            }
            logStmt(sb.toString());
            if (!new SQLQueryAdapter(sb.toString(), readErrors, true).execute(state)) {
                throw new IgnoreMeException();
            }

            recordRawJsonProbe(table);

            List<String> expected = new ArrayList<>();
            for (long v : values) {
                expected.add(String.valueOf(v));
            }
            String query = "SELECT toString(toInt64(j.a)) FROM " + table + " ORDER BY k";
            assertColumnEquals(query, expected, "JSON j.a subcolumn", create);
        } finally {
            dropQuietly(table);
        }
    }

    private void recordRawVariantProbe(String table) {
        recordRawProbe("SELECT v FROM " + table + " ORDER BY k LIMIT 1", "raw Variant projection");
    }

    private void recordRawJsonProbe(String table) {
        recordRawProbe("SELECT j FROM " + table + " ORDER BY k LIMIT 1", "raw JSON projection");
    }

    private void recordRawProbe(String query, String label) {
        boolean decoded;
        String detail;
        try (Statement s = state.getConnection().createStatement(); ResultSet rs = s.executeQuery(query)) {
            if (rs.next()) {
                rs.getString(1);
            }
            decoded = true;
            detail = "ok";
        } catch (Throwable t) {
            decoded = false;
            detail = t.getClass().getSimpleName();
        }
        logStmt("-- reader-probe " + label + ": decoded=" + decoded + " (" + detail + ")");
    }

    private String pickVariantSubcolumnSyntax(String typeName) {
        if (Randomly.getBoolean()) {
            return "v." + typeName;
        }
        return "variantElement(v, '" + typeName + "')";
    }

    private void assertColumnEquals(String query, List<String> expected, String label, String create)
            throws SQLException {
        List<String> actual = ComparatorHelper.getResultSetFirstColumnAsString(query, readErrors, state);
        if (actual.size() != expected.size()) {
            throw new IgnoreMeException();
        }
        for (int i = 0; i < expected.size(); i++) {
            String a = actual.get(i);
            String e = expected.get(i);
            boolean aNull = a == null || "\\N".equals(a);
            boolean eNull = e == null || "\\N".equals(e);
            if (aNull != eNull || !aNull && !a.equals(e)) {
                throw new AssertionError(String.format(
                        "variant/json subcolumn roundtrip mismatch (%s) at row %d: Java expects %s but read %s. Q: %s. DDL: %s",
                        label, i, eNull ? "NULL" : e, aNull ? "NULL" : a, query, create));
            }
        }
    }

    private static String asciiWord(Randomly r) {
        int len = 1 + r.getInteger(0, 7);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            sb.append((char) ('a' + r.getInteger(0, 26)));
        }
        return sb.toString();
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("'", "\\'");
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
