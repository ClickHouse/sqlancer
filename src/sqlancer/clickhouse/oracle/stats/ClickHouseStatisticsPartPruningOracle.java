package sqlancer.clickhouse.oracle.stats;

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

public class ClickHouseStatisticsPartPruningOracle implements TestOracle<ClickHouseGlobalState> {

    private static final AtomicLong TABLE_COUNTER = new AtomicLong();

    static final String ARM_PRUNE_ON = "SETTINGS use_statistics_for_part_pruning = 1, use_skip_indexes = 0,"
            + " use_query_condition_cache = 0";
    static final String ARM_PRUNE_OFF = "SETTINGS use_statistics_for_part_pruning = 0, use_skip_indexes = 0,"
            + " use_query_condition_cache = 0";

    private static final List<long[]> PART_RANGES = List.of(new long[] { 0, 100 }, new long[] { 1000, 100 },
            new long[] { 2000, 100 });

    private final ClickHouseGlobalState state;

    private final ExpectedErrors readErrors = new ExpectedErrors();
    private final ExpectedErrors ddlErrors = new ExpectedErrors();

    public ClickHouseStatisticsPartPruningOracle(ClickHouseGlobalState state) {
        this.state = state;

        for (ExpectedErrors e : List.of(readErrors, ddlErrors)) {
            ClickHouseErrors.addSessionSettingsErrors(e);

            e.add("(MEMORY_LIMIT_EXCEEDED)");
            e.add("memory limit exceeded");

            e.add("TIMEOUT_EXCEEDED");
            e.add("Timeout exceeded");

            e.add("Limit for result exceeded");
            e.add("TOO_MANY_ROWS_OR_BYTES");
        }

        ClickHouseErrors.addStatisticsErrors(ddlErrors);
        ddlErrors.add("already contains statistics");
        ddlErrors.add("UNFINISHED");
    }

    @Override
    public void check() throws SQLException {
        if (Randomly.getBoolean()) {
            checkFreshFixtureArm();
        } else {
            checkBackfillArm();
        }
    }

    private void checkFreshFixtureArm() throws SQLException {
        long id = TABLE_COUNTER.incrementAndGet();
        String table = state.getDatabaseName() + ".spp_" + id + "_t";
        String colType = Randomly.fromOptions("Int64", "Float64", "Decimal(18,4)");
        boolean nullable = Randomly.getBoolean();
        String declType = nullable ? "Nullable(" + colType + ")" : colType;

        try {
            String create = "CREATE TABLE " + table + " (v " + declType + " STATISTICS(minmax)) ENGINE = MergeTree"
                    + " ORDER BY tuple()";
            if (!executeDdl(create)) {
                throw new IgnoreMeException();
            }

            for (long[] range : PART_RANGES) {
                if (!executeDml(renderRangeInsert(table, range[0], range[1], colType))) {
                    throw new IgnoreMeException();
                }
            }

            if (nullable) {
                if (!executeDml("INSERT INTO " + table + " VALUES (NULL)")) {
                    throw new IgnoreMeException();
                }
            }

            if ("Float64".equals(colType)) {
                if (!executeDml("INSERT INTO " + table + " VALUES (nan), (inf), (-inf)")) {
                    throw new IgnoreMeException();
                }
            }

            for (int i = 0; i < 3; i++) {
                runPredicateDifferential(table);
            }
        } finally {
            dropTable(table);
        }
    }

    private void checkBackfillArm() throws SQLException {
        long id = TABLE_COUNTER.incrementAndGet();
        String table = state.getDatabaseName() + ".spp_" + id + "_bf";

        try {
            String create = "CREATE TABLE " + table + " (v Int64) ENGINE = MergeTree ORDER BY tuple()";
            if (!executeDdl(create)) {
                throw new IgnoreMeException();
            }

            for (long[] range : PART_RANGES) {
                if (!executeDml(renderRangeInsert(table, range[0], range[1], "Int64"))) {
                    throw new IgnoreMeException();
                }
            }

            String addStats = "ALTER TABLE " + table + " ADD STATISTICS v TYPE minmax";
            if (!executeDdl(addStats)) {
                throw new IgnoreMeException();
            }

            runPredicateDifferential(table);

            String materialize = "ALTER TABLE " + table + " MATERIALIZE STATISTICS v SETTINGS mutations_sync = 2";
            if (!executeDdl(materialize)) {
                throw new IgnoreMeException();
            }

            runPredicateDifferential(table);

            if (!executeDml(renderRangeInsert(table, 3000, 100, "Int64"))) {
                throw new IgnoreMeException();
            }

            runPredicateDifferential(table);
        } finally {
            dropTable(table);
        }
    }

    private void runPredicateDifferential(String table) throws SQLException {
        String predicate = buildPredicate();
        String select = "SELECT toString(v) FROM " + table + " WHERE " + predicate;
        String selectOn = select + " " + ARM_PRUNE_ON;
        String selectOff = select + " " + ARM_PRUNE_OFF;

        logStmt(selectOn);
        List<String> onRows = ComparatorHelper.getResultSetFirstColumnAsString(selectOn, readErrors, state);
        logStmt(selectOff);
        List<String> offRows = ComparatorHelper.getResultSetFirstColumnAsString(selectOff, readErrors, state);
        ClickHouseStatsToggleOracle.assertMultisetsEqual(onRows, offRows, selectOn, selectOff);
    }

    private static String buildPredicate() {
        switch ((int) Randomly.getNotCachedInteger(0, 5)) {
        case 0:
            return "v > 150 AND v < 950";
        case 1:
            return "v >= 50 AND v <= 1050";
        case 2:
            return "v = " + Randomly.fromOptions(0L, 99L, 1000L, 1099L, 2050L);
        case 3:
            return Randomly.getBoolean() ? "v IS NULL" : "v IS NOT NULL";
        default:
            return "NOT (v > 500)";
        }
    }

    private static String renderRangeInsert(String table, long lo, long len, String colType) {
        String cast;
        switch (colType) {
        case "Int64":
            cast = "toInt64(number)";
            break;
        case "Float64":
            cast = "toFloat64(number)";
            break;
        default:
            cast = "toDecimal64(number, 4)";
            break;
        }
        return "INSERT INTO " + table + " SELECT " + cast + " FROM numbers(" + lo + ", " + len + ")";
    }

    private boolean executeDdl(String sql) throws SQLException {
        logStmt(sql);
        return new SQLQueryAdapter(sql, ddlErrors).execute(state);
    }

    private boolean executeDml(String sql) throws SQLException {
        logStmt(sql);
        return new SQLQueryAdapter(sql, readErrors).execute(state);
    }

    private void dropTable(String table) {
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
