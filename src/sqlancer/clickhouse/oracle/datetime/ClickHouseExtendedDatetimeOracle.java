package sqlancer.clickhouse.oracle.datetime;

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

/**
 * Extended-datetime-results oracle: targets the {@code enable_extended_results_for_datetime_functions} surface --
 * {@code toStartOfYear} / {@code toStartOfMonth} / {@code toStartOfQuarter} / {@code toStartOfWeek} / {@code toMonday}
 * / {@code toLastDayOfMonth} return {@code Date} when the setting is 0 (narrowing a {@code Date32} argument, with
 * saturation for pre-1970 values) and {@code Date32} when it is 1.
 *
 * <p>
 * This is directly the ClickHouse#106419 family: {@code WHERE toStartOfYear(date32_col) &lt; const} returns 0 rows
 * after a merge when the column has pre-1970 values, because the Date32-to-Date narrowing overflows inside the
 * monotonic-filter (KeyCondition / part-minmax) range analysis while the row-level evaluation stays consistent.
 *
 * <p>
 * The sound metamorphic relation here is <b>within one setting value</b>, never across the two: under {@code =1} the
 * functions legitimately return different values for pre-1970 input than under {@code =0} (saturated vs exact), so a
 * 0-vs-1 differential would report documented behavior as a bug. Instead, per arm {@code v in {0, 1}}:
 *
 * <pre>
 * SELECT count() FROM t WHERE f(d) op g  SETTINGS enable_extended_results_for_datetime_functions = v
 * ==
 * SELECT countIf(f(d) op g) FROM t       SETTINGS enable_extended_results_for_datetime_functions = v
 * </pre>
 *
 * The left side routes the predicate through index analysis / filter pushdown / preimage rewrite
 * ({@code optimize_time_filter_with_preimage} lives exactly here); the right side is the row-evaluation ground truth.
 * Any divergence is a wrong-result bug in the filter path.
 *
 * <p>
 * Table construction mirrors the #106419 reproducer: a private Date32 table (ORDER BY tuple(), so the surface is the
 * part-minmax analysis, not a primary key), multi-INSERT part history, an optional pre-1970 outlier part, and an
 * optional {@code OPTIMIZE TABLE ... FINAL} to form a merged part spanning both ranges.
 *
 * <p>
 * <b>Known-open-bug gate</b> (JoinReorder precedent): the exact filed combination -- merge-formed part + pre-1970
 * values + setting {@code = 0} -- still reproduces #106419 on head, so with
 * {@code --extended-datetime-known-overflow-arm} false (the default) that one arm is skipped and everything else
 * (non-merged pre-1970, merged post-1970-only, and the whole {@code = 1} surface) keeps running. Because a
 * <i>background</i> merge would form the same merge-formed part behind the gate's back, the non-merged pre-1970 arm
 * additionally freezes its part topology with {@code SYSTEM STOP MERGES} on the private table. Set the flag true to
 * re-confirm the filed bug; remove the gate when #106419 is fixed on head.
 */
public class ClickHouseExtendedDatetimeOracle implements TestOracle<ClickHouseGlobalState> {

    private static final AtomicLong EDT_COUNTER = new AtomicLong();

    // Functions whose return type the setting widens (Date -> Date32 for Date32 arguments). All are
    // monotonic date transforms, which is what routes them through the KeyCondition range analysis.
    private static final List<String> FUNCTIONS = List.of("toStartOfYear", "toStartOfMonth", "toStartOfQuarter",
            "toStartOfWeek", "toMonday", "toLastDayOfMonth");

    private static final List<String> COMPARATORS = List.of("<", "<=", ">", ">=", "=", "!=");

    // Comparison constants. Rendered expressions, mixed Date / Date32 so the comparison's common
    // type varies; pre-1970 constants must be Date32 (Date cannot hold them). The pool brackets the
    // 1970 epoch boundary, the middle of the post-1970 seed range, and the seed range's far end.
    private static final List<String> CONSTANTS = List.of("toDate('1970-06-15')", "toDate('1971-01-01')",
            "toDate('1995-06-15')", "toDate('2021-06-15')", "toDate32('1969-12-31')", "toDate32('1905-06-15')");

    private final ClickHouseGlobalState state;
    private final ExpectedErrors errors = new ExpectedErrors();

    public ClickHouseExtendedDatetimeOracle(ClickHouseGlobalState state) {
        this.state = state;
        // Narrow tolerance (PatchPartConsistency / MutationAnalyzer precedent): every statement is
        // hand-built static SQL over a private table, so expression-shaped errors can only mean a
        // server bug and must surface.
        ClickHouseErrors.addSessionSettingsErrors(errors);
        // Per-thread database drop/recreate race; not a datetime bug.
        errors.add("UNKNOWN_TABLE");
        errors.add("Unknown table expression identifier");
        // Code 241 load-shedding under a squeezed container cap; environment artifact.
        errors.add("(MEMORY_LIMIT_EXCEEDED)");
        errors.add("memory limit exceeded");
        // Benign load-shed timeout on a count probe; not a datetime bug.
        errors.add("TIMEOUT_EXCEEDED");
        errors.add("Timeout exceeded");
    }

    @Override
    public void check() throws SQLException {
        long id = EDT_COUNTER.incrementAndGet();
        String t = state.getDatabaseName() + ".edt_" + id;

        boolean pre1970 = Randomly.getBoolean();
        boolean merged = Randomly.getBoolean();

        String create = "CREATE TABLE " + t + " (d Date32) ENGINE = MergeTree ORDER BY tuple()";
        // Two post-1970 INSERTs guarantee a multi-part history even without the pre-1970 part, so
        // the merged arm always has something to merge (the #106419 class needs a merge-formed part
        // whose minmax spans ranges no single INSERT produced).
        int rows = 200 + (int) Randomly.getNotCachedInteger(0, 800);
        String seedA = "INSERT INTO " + t + " SELECT toDate32('1971-01-01') + toIntervalDay(number % 18000) "
                + "FROM numbers(" + rows + ")";
        String seedB = "INSERT INTO " + t + " SELECT toDate32('1980-01-01') + toIntervalDay((number * 7) % 9000) "
                + "FROM numbers(" + rows / 2 + ")";
        String seedPre = "INSERT INTO " + t + " SELECT toDate32('1905-01-01') + toIntervalDay(number * 30) "
                + "FROM numbers(9)";

        boolean gateActive = pre1970 && !merged && !state.getClickHouseOptions().extendedDatetimeKnownOverflowArm;

        try {
            logStmt(create);
            if (!new SQLQueryAdapter(create, errors, true).execute(state)) {
                throw new IgnoreMeException();
            }
            if (gateActive) {
                // The #106419 gate keys on `merged`, but a BACKGROUND merge would form the same
                // merge-formed part regardless and re-fire the filed bug through the supposedly
                // ungated pre-1970/non-merged/setting=0 arm. Freeze the part topology for the
                // table's whole lifetime (the stop dies with the DROP). Issued before the INSERTs
                // so no merge can sneak in between seeding and the count pair.
                String stopMerges = "SYSTEM STOP MERGES " + t;
                logStmt(stopMerges);
                if (!new SQLQueryAdapter(stopMerges, errors, false).execute(state)) {
                    throw new IgnoreMeException();
                }
            }
            for (String stmt : List.of(seedA, seedB)) {
                logStmt(stmt);
                if (!new SQLQueryAdapter(stmt, errors, false).execute(state)) {
                    throw new IgnoreMeException();
                }
            }
            if (pre1970) {
                logStmt(seedPre);
                if (!new SQLQueryAdapter(seedPre, errors, false).execute(state)) {
                    throw new IgnoreMeException();
                }
            }
            if (merged) {
                String optimize = "OPTIMIZE TABLE " + t + " FINAL";
                logStmt(optimize);
                if (!new SQLQueryAdapter(optimize, errors, false).execute(state)) {
                    throw new IgnoreMeException();
                }
            }

            String f = Randomly.fromList(FUNCTIONS);
            String op = Randomly.fromList(COMPARATORS);
            String constant = Randomly.fromList(CONSTANTS);
            // Compare against the same transform of the constant: f(d) < f(const) is the natural
            // user-query shape (#106419's reproducer) and keeps both sides in the function's image.
            String pred = f + "(d) " + op + " " + f + "(" + constant + ")";

            for (int v = 0; v <= 1; v++) {
                // Known-open #106419: merged part + pre-1970 values + narrowing (=0) arm. Gated
                // until the fix lands on head; see the class javadoc and the CLI flag description.
                if (v == 0 && merged && pre1970
                        && !state.getClickHouseOptions().extendedDatetimeKnownOverflowArm) {
                    continue;
                }
                String settings = " SETTINGS enable_extended_results_for_datetime_functions = " + v;
                String filterQuery = "SELECT toString(count()) FROM " + t + " WHERE " + pred + settings;
                String rowEvalQuery = "SELECT toString(countIf(" + pred + ")) FROM " + t + settings;
                logStmt(filterQuery);
                String filterCount = readSingleValue(filterQuery);
                logStmt(rowEvalQuery);
                String rowEvalCount = readSingleValue(rowEvalQuery);
                if (!filterCount.equals(rowEvalCount)) {
                    throw new AssertionError(String.format(
                            "extended-datetime filter mismatch (setting=%d, merged=%b, pre1970=%b): WHERE-path count "
                                    + "%s != row-evaluation count %s. filter query: %s -- ground truth: %s",
                            v, merged, pre1970, filterCount, rowEvalCount, filterQuery, rowEvalQuery));
                }
            }
        } finally {
            try {
                new SQLQueryAdapter("DROP TABLE IF EXISTS " + t, errors, true).execute(state);
            } catch (Exception | AssertionError ignored) {
                // Best effort; see the MutationAnalyzer cleanup note for why AssertionError too.
            }
        }
    }

    private String readSingleValue(String query) throws SQLException {
        List<String> rows = ComparatorHelper.getResultSetFirstColumnAsString(query, errors, state);
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
