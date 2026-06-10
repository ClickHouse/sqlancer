package sqlancer.clickhouse.oracle.topk;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import com.clickhouse.data.ClickHouseDataType;

import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.clickhouse.ClickHouseErrors;
import sqlancer.clickhouse.ClickHouseProvider.ClickHouseGlobalState;
import sqlancer.clickhouse.ClickHouseSchema.ClickHouseColumn;
import sqlancer.clickhouse.ClickHouseSchema.ClickHouseLancerDataType;
import sqlancer.clickhouse.ClickHouseSchema.ClickHouseTable;
import sqlancer.clickhouse.ClickHouseType;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.ExpectedErrors;

/**
 * Top-k dynamic-filtering differential oracle (plan Unit 2).
 *
 * <p>
 * Targets the 26.5 default-on top-k read pipeline: {@code use_top_k_dynamic_filtering} (PR #99537, threshold filter
 * derived from the current top-N heap is pushed into the scan), {@code use_skip_indexes_for_top_k} (PR #104216, minmax
 * skip indexes prune granules against that threshold) and {@code query_plan_top_k_through_join} (PR #104268, the top-k
 * step is pushed below a join). The bug class is missing or extra rows at the {@code ORDER BY ... LIMIT N} boundary: a
 * too-tight dynamic threshold (or a granule wrongly pruned against it) silently drops rows that belong in the top N,
 * and an off-by-one threshold update lets rows in that should have been cut. {@code
 * use_top_k_dynamic_filtering_for_variable_length_types} (default false -- the opt-in path that had the regression) is
 * exercised whenever every chosen sort key is string-shaped.
 *
 * <p>
 * Differential: the same {@code SELECT k1..kn FROM t [LEFT JOIN u ON ...] ORDER BY k1..kn LIMIT N [OFFSET M]} runs once
 * with the feature defaults (or the var-length opt-in) and once with all three top-k toggles forced off; the two
 * ordered row lists must be identical.
 *
 * <p>
 * <b>Soundness rule</b>: the projection is <i>exactly</i> the ORDER BY columns, in ORDER BY order. {@code ORDER BY
 * ... LIMIT} is non-deterministic under ties in non-key columns, but the ordered list of the sort-key tuples themselves
 * is deterministic: tied rows straddling the LIMIT boundary have identical key tuples, so whichever physical rows
 * ClickHouse picks, the rendered lists are equal. Comparison is positional (never a Java-side sort -- the known
 * {@code ComparableTimSort} NPE family on SQL NULLs) with NULL cells carried as an explicit flag captured from
 * {@code ResultSet.wasNull()}, so a SQL NULL can never collide with a column whose value is the literal string
 * {@code "NULL"}. Float sort keys are excluded by type (NaN ordering + float-render noise); the oracle is restricted to
 * plain-MergeTree fleet tables because background merges on the dedupe engines change visible rows between the two
 * arms (the documented 2026-05-20 false-positive class -- {@code mutations_sync} covers mutations, not merges).
 */
public class ClickHouseTopKOracle implements TestOracle<ClickHouseGlobalState> {

    /**
     * Arm (2): every top-k optimization forced off. The var-length opt-in is left at its default (false) here -- with
     * the main toggle off it is dead anyway, and keeping the suffix minimal keeps reproducers readable.
     */
    static final String OFF_SETTINGS = " SETTINGS use_top_k_dynamic_filtering = 0, use_skip_indexes_for_top_k = 0,"
            + " query_plan_top_k_through_join = 0";

    /**
     * Arm (1) when ALL chosen sort keys are string-shaped: defaults plus the off-by-default var-length path (the one
     * that had the regression). For non-string key sets arm (1) carries no SETTINGS clause at all (pure defaults).
     */
    static final String VAR_LENGTH_OPT_IN_SETTINGS = " SETTINGS use_top_k_dynamic_filtering_for_variable_length_types"
            + " = 1";

    enum NullsOrder {
        DEFAULT, FIRST, LAST
    }

    /** One ORDER BY key: column name (unquoted), direction, and an optional explicit NULLS placement. */
    record SortKey(String column, boolean ascending, NullsOrder nullsOrder) {
    }

    /**
     * One rendered result cell. SQL NULL is carried as {@code isNull=true, value=null} (captured from
     * {@code ResultSet.wasNull()}), structurally distinct from a non-null cell whose text happens to be the literal
     * string {@code "NULL"} -- record equality compares the flag first, so the sentinel cannot collide.
     */
    record Cell(boolean isNull, String value) {
        static final Cell NULL = new Cell(true, null);

        static Cell of(String value) {
            return new Cell(false, value);
        }
    }

    private final ClickHouseGlobalState state;
    private final ExpectedErrors errors = new ExpectedErrors();

    public ClickHouseTopKOracle(ClickHouseGlobalState state) {
        this.state = state;
        // Broad read set (ProjectionToggle/KeyCondition precedent): the SELECTs are hand-built but
        // column names/types come from generated fleet schemas, so generator-shaped rejections are
        // noise here, not findings. Session-settings errors keep the suite runnable on pre-26.5
        // images where the top-k setting names do not exist (UNKNOWN_SETTING -> IgnoreMe).
        ClickHouseErrors.addExpectedExpressionErrors(errors);
        ClickHouseErrors.addSessionSettingsErrors(errors);
    }

    @Override
    public void check() throws SQLException {
        List<ClickHouseTable> plainTables = state.getSchema().getRandomTableNonEmptyTables().getTables().stream()
                .filter(t -> !t.isView() && "MergeTree".equals(t.getEngine())).collect(Collectors.toList());
        if (plainTables.isEmpty()) {
            throw new IgnoreMeException();
        }
        ClickHouseTable table = Randomly.fromList(plainTables);
        List<ClickHouseColumn> eligible = table.getColumns().stream().filter(c -> isEligibleSortKey(c.getType()))
                .collect(Collectors.toList());
        if (eligible.isEmpty()) {
            throw new IgnoreMeException();
        }

        int nrKeys = 1 + (int) Randomly.getNotCachedInteger(0, Math.min(3, eligible.size()));
        List<ClickHouseColumn> keyColumns = Randomly.nonEmptySubset(eligible, nrKeys);
        List<SortKey> sortKeys = new ArrayList<>(keyColumns.size());
        for (ClickHouseColumn c : keyColumns) {
            NullsOrder nullsOrder = NullsOrder.DEFAULT;
            if (isNullableKey(c.getType()) && Randomly.getBoolean()) {
                nullsOrder = Randomly.fromOptions(NullsOrder.FIRST, NullsOrder.LAST);
            }
            sortKeys.add(new SortKey(c.getName(), Randomly.getBoolean(), nullsOrder));
        }

        long limit = pickLimit();
        // Small OFFSET occasionally: shifts which side of the dynamic threshold the returned window
        // sits on without changing the soundness argument (the key-tuple list stays deterministic).
        long offset = Randomly.getBooleanWithRatherLowProbability() ? Randomly.getNotCachedInteger(0, 6) : -1;
        String joinClause = maybeRenderJoinClause(table, plainTables);

        // The var-length opt-in arm only fires when EVERY key is string-shaped: a single fixed-width
        // key already gives the default pipeline a filterable prefix, which is the default-on path
        // arm (1) covers without any suffix.
        boolean allVarLength = keyColumns.stream().allMatch(c -> isVarLengthKey(c.getType()));
        String onSql = renderQuery(table.getName(), joinClause, sortKeys, limit, offset,
                allVarLength ? VAR_LENGTH_OPT_IN_SETTINGS : "");
        String offSql = renderQuery(table.getName(), joinClause, sortKeys, limit, offset, OFF_SETTINGS);

        logStmt(onSql);
        List<List<Cell>> onRows = collectRows(onSql);
        logStmt(offSql);
        List<List<Cell>> offRows = collectRows(offSql);

        int divergence = firstDivergence(onRows, offRows);
        if (divergence != -1) {
            throw new AssertionError(String.format(
                    "top-k arm divergence at row %d:%n  on  (%d rows): %s%n  off (%d rows): %s%n"
                            + "  on-row : %s%n  off-row: %s",
                    divergence, onRows.size(), onSql, offRows.size(), offSql, renderRowAt(onRows, divergence),
                    renderRowAt(offRows, divergence)));
        }
    }

    // LIMIT pool per the plan: 0 (the degenerate boundary), 1 (heap of one), a small window (the
    // common top-N shape where threshold updates are most frequent), and occasionally a value at or
    // above any fleet table's row count (the "LIMIT swallows everything" boundary -- safe under the
    // universal 1M result cap).
    private static long pickLimit() {
        int roll = (int) Randomly.getNotCachedInteger(0, 100);
        if (roll < 10) {
            return 0;
        }
        if (roll < 25) {
            return 1;
        }
        if (roll < 90) {
            return 2 + Randomly.getNotCachedInteger(0, 9); // 2..10
        }
        return 1_000_000;
    }

    // ~25%: wrap the FROM in a LEFT JOIN on an integer-ish column pair so query_plan_top_k_through_join
    // has something to push through. Null (no wrap) when the roll misses or no eligible pair exists.
    // The projection stays left-table ORDER BY keys only, so join-induced row multiplication keeps the
    // key-tuple list deterministic (multiplied rows carry identical key tuples).
    private static String maybeRenderJoinClause(ClickHouseTable left, List<ClickHouseTable> plainTables) {
        if (Randomly.getNotCachedInteger(0, 100) >= 25) {
            return null;
        }
        List<ClickHouseColumn> leftInts = integerColumns(left);
        if (leftInts.isEmpty()) {
            return null;
        }
        List<ClickHouseTable> rightCandidates = plainTables.stream()
                .filter(t -> !t.getName().equals(left.getName()) && !integerColumns(t).isEmpty())
                .collect(Collectors.toList());
        if (rightCandidates.isEmpty()) {
            return null;
        }
        ClickHouseTable right = Randomly.fromList(rightCandidates);
        ClickHouseColumn leftCol = Randomly.fromList(leftInts);
        ClickHouseColumn rightCol = Randomly.fromList(integerColumns(right));
        return renderLeftJoin(left.getName(), leftCol.getName(), right.getName(), rightCol.getName());
    }

    private static List<ClickHouseColumn> integerColumns(ClickHouseTable table) {
        return table.getColumns().stream().filter(c -> isExactInteger(c.getType().getType()))
                .collect(Collectors.toList());
    }

    // ----- Static rendering / classification / comparison helpers (unit-tested DB-free) -----

    /**
     * Render one arm. Projection is exactly the sort keys, in order (the soundness rule). When {@code joinClause} is
     * non-null every column reference is table-qualified (fleet tables share the c0/c1/... naming, so unqualified
     * references would be ambiguous under a join). {@code offset < 0} means no OFFSET clause; {@code settingsSuffix} is
     * appended verbatim (empty for pure defaults).
     */
    static String renderQuery(String tableName, String joinClause, List<SortKey> sortKeys, long limit, long offset,
            String settingsSuffix) {
        String qualifier = joinClause == null ? "" : tableName + ".";
        String projection = sortKeys.stream().map(k -> qualifier + quote(k.column()))
                .collect(Collectors.joining(", "));
        String orderBy = sortKeys.stream().map(k -> {
            StringBuilder sb = new StringBuilder(qualifier).append(quote(k.column()));
            sb.append(k.ascending() ? " ASC" : " DESC");
            if (k.nullsOrder() == NullsOrder.FIRST) {
                sb.append(" NULLS FIRST");
            } else if (k.nullsOrder() == NullsOrder.LAST) {
                sb.append(" NULLS LAST");
            }
            return sb.toString();
        }).collect(Collectors.joining(", "));
        StringBuilder sb = new StringBuilder("SELECT ").append(projection).append(" FROM ").append(tableName);
        if (joinClause != null) {
            sb.append(' ').append(joinClause);
        }
        sb.append(" ORDER BY ").append(orderBy).append(" LIMIT ").append(limit);
        if (offset >= 0) {
            sb.append(" OFFSET ").append(offset);
        }
        sb.append(settingsSuffix);
        return sb.toString();
    }

    static String renderLeftJoin(String leftTable, String leftColumn, String rightTable, String rightColumn) {
        return "LEFT JOIN " + rightTable + " ON " + leftTable + "." + quote(leftColumn) + " = " + rightTable + "."
                + quote(rightColumn);
    }

    static String quote(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }

    /**
     * A type usable as a sort key for this oracle: a fixed-render scalar whose ordering is total and whose textual
     * rendering is stable across plans. Nullable/LowCardinality wrappers are transparent ({@code getType()} returns
     * the root). Floats are excluded (NaN ordering + render noise); Decimal, UUID, Enum, Bool, Time and every
     * composite/exotic type stay out -- the plan scopes the key pool to the Int/UInt widths, Date, Date32, DateTime,
     * DateTime64, String and FixedString.
     */
    static boolean isEligibleSortKey(ClickHouseLancerDataType type) {
        ClickHouseDataType root = type.getType();
        if (isExactInteger(root)) {
            return true;
        }
        switch (root) {
        case Date:
        case Date32:
        case DateTime:
        case DateTime64:
        case String:
        case FixedString:
            return true;
        default:
            return false;
        }
    }

    /**
     * String-shaped keys routed through the variable-length top-k path ({@code String}, {@code FixedString},
     * {@code LowCardinality(String)} and Nullable wrappers -- {@code getType()} unwraps the wrappers).
     */
    static boolean isVarLengthKey(ClickHouseLancerDataType type) {
        ClickHouseDataType root = type.getType();
        return root == ClickHouseDataType.String || root == ClickHouseDataType.FixedString;
    }

    /** True when the column can hold SQL NULL, i.e. a Nullable wrapper appears anywhere in the wrapper chain. */
    static boolean isNullableKey(ClickHouseLancerDataType type) {
        return containsNullable(type.getTypeTerm());
    }

    private static boolean containsNullable(ClickHouseType term) {
        if (term instanceof ClickHouseType.Nullable) {
            return true;
        }
        if (term instanceof ClickHouseType.LowCardinality lc) {
            return containsNullable(lc.inner());
        }
        return false;
    }

    static boolean isExactInteger(ClickHouseDataType t) {
        switch (t) {
        case Int8:
        case Int16:
        case Int32:
        case Int64:
        case Int128:
        case Int256:
        case UInt8:
        case UInt16:
        case UInt32:
        case UInt64:
        case UInt128:
        case UInt256:
            return true;
        default:
            return false;
        }
    }

    /**
     * Positional comparison of two ordered row lists. Returns -1 when equal; otherwise the index of the first
     * divergent row -- which is {@code min(size)} when one list is a strict prefix of the other (the
     * missing/extra-rows-at-the-boundary shape this oracle exists to catch). Never sorts.
     */
    static int firstDivergence(List<List<Cell>> a, List<List<Cell>> b) {
        int common = Math.min(a.size(), b.size());
        for (int i = 0; i < common; i++) {
            if (!a.get(i).equals(b.get(i))) {
                return i;
            }
        }
        return a.size() == b.size() ? -1 : common;
    }

    static String renderRowAt(List<List<Cell>> rows, int idx) {
        if (idx >= rows.size()) {
            return "<absent>";
        }
        return rows.get(idx).stream().map(c -> c.isNull() ? "NULL" : c.value()).collect(Collectors.joining("|"));
    }

    // ----- Execution plumbing -----

    // Read one arm into an ordered list of Cell rows (EET's collectRows shape, WITHOUT the Java-side
    // sort -- the compare is positional by design). A tolerated failure -> IgnoreMeException, so a
    // partial pair is never compared.
    private List<List<Cell>> collectRows(String query) throws SQLException {
        List<List<Cell>> rows = new ArrayList<>();
        try (Statement s = state.getConnection().createStatement(); ResultSet rs = s.executeQuery(query)) {
            int columnCount = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                List<Cell> row = new ArrayList<>(columnCount);
                for (int i = 1; i <= columnCount; i++) {
                    String v = rs.getString(i);
                    row.add(rs.wasNull() ? Cell.NULL : Cell.of(v));
                }
                rows.add(row);
            }
        } catch (SQLException ex) {
            if (ex.getMessage() != null && errors.errorIsExpected(ex.getMessage())) {
                throw new IgnoreMeException();
            }
            throw ex;
        }
        return rows;
    }

    private void logStmt(String stmt) {
        if (state.getOptions().logEachSelect()) {
            state.getLogger().writeCurrent(stmt);
            state.getState().logStatement(stmt);
        }
    }
}
