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

public class ClickHouseTopKOracle implements TestOracle<ClickHouseGlobalState> {

    static final String OFF_SETTINGS = " SETTINGS use_top_k_dynamic_filtering = 0, use_skip_indexes_for_top_k = 0,"
            + " query_plan_top_k_through_join = 0";

    static final String VAR_LENGTH_OPT_IN_SETTINGS = " SETTINGS use_top_k_dynamic_filtering_for_variable_length_types"
            + " = 1";

    enum NullsOrder {
        DEFAULT, FIRST, LAST
    }

    record SortKey(String column, boolean ascending, NullsOrder nullsOrder) {
    }

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

        long offset = Randomly.getBooleanWithRatherLowProbability() ? Randomly.getNotCachedInteger(0, 6) : -1;
        String joinClause = maybeRenderJoinClause(table, plainTables);

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

    private static long pickLimit() {
        int roll = (int) Randomly.getNotCachedInteger(0, 100);
        if (roll < 10) {
            return 0;
        }
        if (roll < 25) {
            return 1;
        }
        if (roll < 90) {
            return 2 + Randomly.getNotCachedInteger(0, 9);
        }
        return 1_000_000;
    }

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

    static boolean isVarLengthKey(ClickHouseLancerDataType type) {
        ClickHouseDataType root = type.getType();
        return root == ClickHouseDataType.String || root == ClickHouseDataType.FixedString;
    }

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
