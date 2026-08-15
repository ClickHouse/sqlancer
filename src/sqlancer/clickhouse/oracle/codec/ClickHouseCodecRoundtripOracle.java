package sqlancer.clickhouse.oracle.codec;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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

public class ClickHouseCodecRoundtripOracle implements TestOracle<ClickHouseGlobalState> {

    private static final int DIFF_LIMIT = 20;
    private static final AtomicLong CODEC_COUNTER = new AtomicLong();

    private static final List<String> GENERIC_CODECS = List.of("NONE", "LZ4", "LZ4HC(6)", "ZSTD(1)", "ZSTD(6)");
    private static final List<String> INTEGRAL_CODECS = List.of("Delta(8), LZ4", "Delta(4), ZSTD(1)", "DoubleDelta, LZ4",
            "T64, LZ4", "T64, ZSTD(1)", "Gorilla, LZ4");
    private static final List<String> FLOAT_CODECS = List.of("Gorilla, LZ4", "FPC, LZ4", "ALP, LZ4", "ZSTD(3)");
    private static final List<String> DATETIME_CODECS = List.of("Delta(4), LZ4", "DoubleDelta, LZ4", "T64, LZ4");

    private static final List<String> LOSSY_FLOAT_CODECS = List.of("SZ3", "ZXC");

    private static final List<String> INT_VALUES = List.of("0", "-1", "1", "127", "-128", "2147483647", "-2147483648",
            "9223372036854775807", "-9223372036854775808", "42", "1000000");
    private static final List<String> FLOAT_VALUES = List.of("nan", "inf", "-inf", "0", "-0.0", "1.5", "-1.5", "3.14",
            "-3.14", "1e300", "-1e300", "5e-324");
    private static final List<String> STRING_VALUES = List.of("''", "'a'", "'alpha'", "'0'", "'0.0'",
            "'  spaced  '", "'zzzzzzzzzzzzzzzzzzzzzzzz'");
    private static final List<String> DATETIME_VALUES = List.of("toDateTime('1970-01-01 00:00:00')",
            "toDateTime('2000-02-29 12:00:00')", "toDateTime('2106-02-07 06:28:15')",
            "toDateTime('2026-08-15 13:37:00')");

    private final ClickHouseGlobalState state;
    private final ExpectedErrors ddlErrors = new ExpectedErrors();
    private final ExpectedErrors readErrors = new ExpectedErrors();

    public ClickHouseCodecRoundtripOracle(ClickHouseGlobalState state) {
        this.state = state;
        for (ExpectedErrors e : List.of(ddlErrors, readErrors)) {
            ClickHouseErrors.addSessionSettingsErrors(e);
            e.add("UNKNOWN_TABLE");
            e.add("Unknown table expression identifier");
            e.add("(MEMORY_LIMIT_EXCEEDED)");
            e.add("memory limit exceeded");
            e.add("TIMEOUT_EXCEEDED");
            e.add("Timeout exceeded");
            e.add("Limit for result exceeded");
            e.add("TOO_MANY_ROWS_OR_BYTES");
        }
        ddlErrors.add("UNKNOWN_CODEC");
        ddlErrors.add("Unknown codec family code");
        ddlErrors.add("ILLEGAL_SYNTAX_FOR_CODEC_TYPE");
        ddlErrors.add("ILLEGAL_CODEC_PARAMETER");
        ddlErrors.add("BAD_ARGUMENTS");
        ddlErrors.add("SUPPORT_IS_DISABLED");
        ddlErrors.add("NOT_IMPLEMENTED");
        ddlErrors.add("is not applicable");
        ddlErrors.add("Exception happened during execution of mutation");
        ddlErrors.add("UNFINISHED");
    }

    private static final class Column {
        private final String name;
        private final String type;
        private final String codec;
        private final boolean lossy;

        private Column(String name, String type, String codec, boolean lossy) {
            this.name = name;
            this.type = type;
            this.codec = codec;
            this.lossy = lossy;
        }
    }

    @Override
    public void check() throws SQLException {
        if (!state.getClickHouseOptions().codecRoundtripOracle) {
            throw new IgnoreMeException();
        }

        long id = CODEC_COUNTER.incrementAndGet();
        String db = state.getDatabaseName();
        String coded = db + ".codec_a_" + id;
        String mirror = db + ".codec_b_" + id;

        List<Column> columns = pickColumns();
        boolean anyLossy = columns.stream().anyMatch(c -> c.lossy);
        boolean adaptiveCodecs = Randomly.getBoolean();

        try {
            String createCoded = renderCreate(coded, columns, false, adaptiveCodecs);
            log(createCoded);
            if (!new SQLQueryAdapter(createCoded, ddlErrors, true).execute(state)) {
                if (!anyLossy) {
                    throw new IgnoreMeException();
                }
                columns = withLosslessFloatCodec(columns);
                anyLossy = false;
                createCoded = renderCreate(coded, columns, false, adaptiveCodecs);
                log(createCoded);
                if (!new SQLQueryAdapter(createCoded, ddlErrors, true).execute(state)) {
                    throw new IgnoreMeException();
                }
            }
            String createMirror = renderCreate(mirror, columns, true, false);
            log(createMirror);
            if (!new SQLQueryAdapter(createMirror, ddlErrors, true).execute(state)) {
                throw new IgnoreMeException();
            }

            int blocks = 2 + (int) Randomly.getNotCachedInteger(0, 3);
            long key = 0;
            for (int b = 0; b < blocks; b++) {
                int rows = 4 + (int) Randomly.getNotCachedInteger(0, 12);
                String values = renderValues(columns, key, rows);
                key += rows;
                insertBoth(coded, mirror, values);
            }

            compare(coded, mirror, columns, anyLossy, "after insert");

            optimizeFinal(coded);
            optimizeFinal(mirror);
            compare(coded, mirror, columns, anyLossy, "after OPTIMIZE FINAL");

            Column recoded = pickRecodableColumn(columns);
            if (recoded != null) {
                String newCodec = pickCodec(recoded.type);
                String alter = "ALTER TABLE " + coded + " MODIFY COLUMN " + recoded.name + " " + recoded.type
                        + " CODEC(" + newCodec + ") SETTINGS mutations_sync = 2, alter_sync = 2";
                log(alter);
                if (new SQLQueryAdapter(alter, ddlErrors, false).execute(state)) {
                    compare(coded, mirror, columns, anyLossy, "after MODIFY COLUMN CODEC(" + newCodec + ")");
                }
            }
        } finally {
            dropQuietly(coded);
            dropQuietly(mirror);
        }
    }

    private List<Column> pickColumns() {
        List<Column> columns = new ArrayList<>();
        columns.add(new Column("k", "Int64", "NONE", false));
        columns.add(new Column("i64", "Int64", pickCodec("Int64"), false));
        columns.add(new Column("s", "String", pickCodec("String"), false));
        columns.add(new Column("d", "DateTime", pickCodec("DateTime"), false));
        boolean lossy = Randomly.getBooleanWithSmallProbability();
        String floatCodec = lossy ? Randomly.fromList(LOSSY_FLOAT_CODECS) : pickCodec("Float64");
        columns.add(new Column("f64", "Float64", floatCodec, lossy));
        return columns;
    }

    private static String pickCodec(String type) {
        List<String> options = new ArrayList<>(GENERIC_CODECS);
        switch (type) {
        case "Int64":
            options.addAll(INTEGRAL_CODECS);
            break;
        case "Float64":
            options.addAll(FLOAT_CODECS);
            break;
        case "DateTime":
            options.addAll(DATETIME_CODECS);
            break;
        default:
            break;
        }
        return Randomly.fromList(options);
    }

    private static List<Column> withLosslessFloatCodec(List<Column> columns) {
        List<Column> out = new ArrayList<>();
        for (Column c : columns) {
            out.add(c.lossy ? new Column(c.name, c.type, pickCodec(c.type), false) : c);
        }
        return out;
    }

    private static Column pickRecodableColumn(List<Column> columns) {
        List<Column> candidates = new ArrayList<>();
        for (Column c : columns) {
            if (!c.lossy && !"k".equals(c.name)) {
                candidates.add(c);
            }
        }
        return candidates.isEmpty() ? null : Randomly.fromList(candidates);
    }

    private static String renderCreate(String table, List<Column> columns, boolean mirror,
            boolean adaptiveCodecSelection) {
        StringBuilder sb = new StringBuilder("CREATE TABLE ").append(table).append(" (");
        for (int i = 0; i < columns.size(); i++) {
            Column c = columns.get(i);
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(c.name).append(' ').append(c.type).append(" CODEC(").append(mirror ? "NONE" : c.codec)
                    .append(')');
        }
        sb.append(") ENGINE = MergeTree ORDER BY k SETTINGS index_granularity = 8");
        if (!mirror && adaptiveCodecSelection) {
            sb.append(", allow_experimental_adaptive_codec_selection = 1");
        }
        return sb.toString();
    }

    private static String renderValues(List<Column> columns, long startKey, int rows) {
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < rows; r++) {
            if (r > 0) {
                sb.append(", ");
            }
            sb.append('(');
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                Column c = columns.get(i);
                if ("k".equals(c.name)) {
                    sb.append(startKey + r);
                    continue;
                }
                switch (c.type) {
                case "Int64":
                    sb.append(Randomly.fromList(INT_VALUES));
                    break;
                case "Float64":
                    sb.append(Randomly.fromList(FLOAT_VALUES));
                    break;
                case "String":
                    sb.append(Randomly.fromList(STRING_VALUES));
                    break;
                default:
                    sb.append(Randomly.fromList(DATETIME_VALUES));
                    break;
                }
            }
            sb.append(')');
        }
        return sb.toString();
    }

    private void insertBoth(String coded, String mirror, String values) throws SQLException {
        for (String table : List.of(coded, mirror)) {
            String insert = "INSERT INTO " + table + " VALUES " + values;
            log(insert);
            if (!new SQLQueryAdapter(insert, ddlErrors, true).execute(state)) {
                throw new IgnoreMeException();
            }
        }
    }

    private void optimizeFinal(String table) throws SQLException {
        String sql = "OPTIMIZE TABLE " + table + " FINAL";
        log(sql);
        new SQLQueryAdapter(sql, ddlErrors, false).execute(state);
    }

    private void compare(String coded, String mirror, List<Column> columns, boolean anyLossy, String stage)
            throws SQLException {
        List<String> losslessNames = new ArrayList<>();
        for (Column c : columns) {
            if (!c.lossy) {
                losslessNames.add(c.name);
            }
        }
        String projection = "toString(tuple(" + String.join(", ", losslessNames) + "))";
        String codedSql = "SELECT " + projection + " FROM " + coded;
        String mirrorSql = "SELECT " + projection + " FROM " + mirror;
        log(codedSql);
        List<String> codedRows = ComparatorHelper.getResultSetFirstColumnAsString(codedSql, readErrors, state);
        log(mirrorSql);
        List<String> mirrorRows = ComparatorHelper.getResultSetFirstColumnAsString(mirrorSql, readErrors, state);

        List<String> diff = multisetDiff(mirrorRows, codedRows, DIFF_LIMIT);
        if (!diff.isEmpty()) {
            throw new AssertionError(String.format(
                    "codec roundtrip mismatch %s: the CODEC(NONE) mirror returned %d rows and the coded table returned "
                            + "%d rows for the same inserted data.%ncodecs: %s%nmirror: %s%ncoded:  %s%n"
                            + "first %d differing rows: %s",
                    stage, mirrorRows.size(), codedRows.size(), describeCodecs(columns), mirrorSql, codedSql,
                    diff.size(), diff));
        }

        if (anyLossy) {
            compareLossyShape(coded, mirror, columns, stage);
        }
    }

    private void compareLossyShape(String coded, String mirror, List<Column> columns, String stage)
            throws SQLException {
        for (Column c : columns) {
            if (!c.lossy) {
                continue;
            }
            String projection = "toString(tuple(count(), countIf(isNull(" + c.name + "))))";
            String codedSql = "SELECT " + projection + " FROM " + coded;
            String mirrorSql = "SELECT " + projection + " FROM " + mirror;
            log(codedSql);
            List<String> codedRows = ComparatorHelper.getResultSetFirstColumnAsString(codedSql, readErrors, state);
            log(mirrorSql);
            List<String> mirrorRows = ComparatorHelper.getResultSetFirstColumnAsString(mirrorSql, readErrors, state);
            if (!codedRows.equals(mirrorRows)) {
                throw new AssertionError(String.format(
                        "lossy codec changed row count or NULL mask %s: column %s CODEC(%s) reports %s, the "
                                + "CODEC(NONE) mirror reports %s.%ncoded:  %s%nmirror: %s",
                        stage, c.name, c.codec, codedRows, mirrorRows, codedSql, mirrorSql));
            }
        }
    }

    private static String describeCodecs(List<Column> columns) {
        Map<String, String> byName = new LinkedHashMap<>();
        for (Column c : columns) {
            byName.put(c.name, c.codec);
        }
        return byName.toString();
    }

    private void log(String sql) {
        if (state.getOptions().logEachSelect()) {
            state.getLogger().writeCurrent(sql);
            state.getState().logStatement(sql);
        }
    }

    private void dropQuietly(String table) {
        try {
            new SQLQueryAdapter("DROP TABLE IF EXISTS " + table, ddlErrors, true).execute(state);
        } catch (Exception | AssertionError ignored) {
        }
    }

    private static List<String> multisetDiff(List<String> a, List<String> b, int limit) {
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
            diff.add(e.getKey() + " (+" + Math.abs(c) + " " + (c > 0 ? "mirror" : "coded") + ")");
        }
        return diff;
    }
}
