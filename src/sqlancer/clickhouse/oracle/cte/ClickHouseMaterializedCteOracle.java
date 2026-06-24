package sqlancer.clickhouse.oracle.cte;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import com.clickhouse.data.ClickHouseDataType;

import sqlancer.ComparatorHelper;
import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.clickhouse.ClickHouseErrors;
import sqlancer.clickhouse.ClickHouseProvider.ClickHouseGlobalState;
import sqlancer.clickhouse.ClickHouseSchema;
import sqlancer.clickhouse.ClickHouseSchema.ClickHouseColumn;
import sqlancer.clickhouse.ClickHouseSchema.ClickHouseTable;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.ExpectedErrors;

public class ClickHouseMaterializedCteOracle implements TestOracle<ClickHouseGlobalState> {

    static final String GATE_SETTING = "enable_materialized_cte";
    static final String PROBE_QUERY = "SELECT 1 SETTINGS " + GATE_SETTING + " = 1";

    private static final int UNPROBED = 0;
    private static final int ENABLED = 1;
    private static final int DISABLED = 2;
    private static volatile int probeState = UNPROBED;

    enum BodyShape {
        GROUP_COUNT,
        DISTINCT_FILTER,
        PLAIN_FILTER
    }

    enum OuterShape {
        SINGLE_REF,
        SELF_JOIN,
        SCALAR_SUBQUERY,
        UNION_ALL,
        CHAINED
    }

    private final ClickHouseGlobalState state;
    private final ExpectedErrors errors = new ExpectedErrors();

    public ClickHouseMaterializedCteOracle(ClickHouseGlobalState state) {
        this.state = state;

        ClickHouseErrors.addSessionSettingsErrors(errors);

        errors.add("UNKNOWN_TABLE");
        errors.add("Unknown table expression identifier");

        errors.add("(MEMORY_LIMIT_EXCEEDED)");
        errors.add("memory limit exceeded");
        errors.add("Limit for result exceeded");
        errors.add("TOO_MANY_ROWS_OR_BYTES");

        errors.add("TIMEOUT_EXCEEDED");
        errors.add("Timeout exceeded");
    }

    @Override
    public void check() throws SQLException {
        probeGate();

        ClickHouseSchema schema = state.getSchema();
        List<ClickHouseTable> tables = schema.getRandomTableNonEmptyTables().getTables();
        if (tables.isEmpty()) {
            throw new IgnoreMeException();
        }
        ClickHouseTable table = tables.get((int) Randomly.getNotCachedInteger(0, tables.size()));
        if (table.isView()) {
            throw new IgnoreMeException();
        }
        List<ClickHouseColumn> intCols = table.getColumns().stream()
                .filter(c -> isExactInteger(c.getType().getType())).collect(Collectors.toList());
        if (intCols.isEmpty()) {
            throw new IgnoreMeException();
        }
        ClickHouseColumn col = Randomly.fromList(intCols);

        BodyShape body = Randomly.fromOptions(BodyShape.values());
        OuterShape outer = Randomly.fromOptions(OuterShape.values());
        int k = (int) Randomly.getNotCachedInteger(2, 8);
        long m = body == BodyShape.DISTINCT_FILTER ? Randomly.getNotCachedInteger(-100, 101)
                : Randomly.getNotCachedInteger(0, k);

        String materializedStmt = renderStatement(outer, body, table.getName(), col.getName(), k, m, true);
        String inlinedStmt = renderStatement(outer, body, table.getName(), col.getName(), k, m, false);

        List<String> materializedRows = ComparatorHelper.getResultSetFirstColumnAsString(materializedStmt, errors,
                state);
        List<String> inlinedRows = ComparatorHelper.getResultSetFirstColumnAsString(inlinedStmt, errors, state);

        List<String> matSorted = sortedNonNull(materializedRows);
        List<String> inlSorted = sortedNonNull(inlinedRows);
        if (!matSorted.equals(inlSorted)) {
            List<String> diff = boundedDiff(matSorted, inlSorted, 20);
            throw new AssertionError(String.format(
                    "materialized-CTE divergence: %d rows (materialized) vs %d rows (inlined).%n"
                            + "  materialized: %s%n  inlined: %s%n  first differing values (max 20): %s",
                    matSorted.size(), inlSorted.size(), materializedStmt, inlinedStmt, diff));
        }
    }

    private void probeGate() throws SQLException {
        int s = probeState;
        if (s == ENABLED) {
            return;
        }
        if (s == DISABLED) {
            throw new IgnoreMeException();
        }

        ExpectedErrors probeErrors = new ExpectedErrors();
        ClickHouseErrors.addSessionSettingsErrors(probeErrors);
        try {
            ComparatorHelper.getResultSetFirstColumnAsString(PROBE_QUERY, probeErrors, state);
            probeState = ENABLED;
        } catch (IgnoreMeException e) {

            probeState = DISABLED;
            throw e;
        }
    }

    static String renderBody(BodyShape shape, String tableName, String columnName, long k, long m) {
        String col = "`" + columnName + "`";
        switch (shape) {
        case GROUP_COUNT:
            return "SELECT " + col + " AS c, count() AS n FROM " + tableName + " WHERE " + col + " % " + k + " = " + m
                    + " GROUP BY " + col;
        case DISTINCT_FILTER:
            return "SELECT DISTINCT " + col + " AS c FROM " + tableName + " WHERE " + col + " > " + m;
        case PLAIN_FILTER:
            return "SELECT " + col + " AS c FROM " + tableName + " WHERE " + col + " % " + k + " = " + m;
        default:
            throw new AssertionError(shape);
        }
    }

    static String renderOuter(OuterShape shape, BodyShape body) {

        boolean hasN = body == BodyShape.GROUP_COUNT;
        switch (shape) {
        case SINGLE_REF:
            return "SELECT toString(tuple(" + (hasN ? "c, n" : "c") + ")) FROM mcte_x";
        case SELF_JOIN:
            return "SELECT toString(tuple(" + (hasN ? "x1.c, x1.n, x2.c, x2.n" : "x1.c, x2.c")
                    + ")) FROM mcte_x AS x1 JOIN mcte_x AS x2 ON x1.c = x2.c";
        case SCALAR_SUBQUERY:

            return "SELECT toString(tuple(" + (hasN ? "c, n, " : "c, ")
                    + "(SELECT max(c) FROM mcte_x))) FROM mcte_x";
        case UNION_ALL: {

            String branch = "SELECT toString(tuple(" + (hasN ? "c, n" : "c") + ")) AS r FROM mcte_x";
            return "SELECT r FROM (" + branch + " UNION ALL " + branch + ")";
        }
        case CHAINED:

            return "SELECT toString(tuple(" + (hasN ? "b1.c, a1.n" : "b1.c, a1.c")
                    + ")) FROM mcte_b AS b1 JOIN mcte_a AS a1 ON b1.c = a1.c";
        default:
            throw new AssertionError(shape);
        }
    }

    static String renderStatement(OuterShape shape, BodyShape body, String tableName, String columnName, long k,
            long m, boolean materialized) {
        String as = materialized ? " AS MATERIALIZED (" : " AS (";
        String bodySql = renderBody(body, tableName, columnName, k, m);
        StringBuilder sb = new StringBuilder("WITH ");
        if (shape == OuterShape.CHAINED) {
            sb.append("mcte_a").append(as).append(bodySql).append("), ");

            sb.append("mcte_b").append(as).append("SELECT c FROM mcte_a WHERE c % 2 = 0").append(")");
        } else {
            sb.append("mcte_x").append(as).append(bodySql).append(")");
        }
        sb.append(' ').append(renderOuter(shape, body));
        if (materialized) {
            sb.append(" SETTINGS ").append(GATE_SETTING).append(" = 1");
        }
        return sb.toString();
    }

    private static List<String> sortedNonNull(List<String> rows) {
        List<String> sorted = new ArrayList<>(rows.size());
        for (String r : rows) {
            sorted.add(r == null ? "\\N" : r);
        }
        Collections.sort(sorted);
        return sorted;
    }

    static List<String> boundedDiff(List<String> sortedMaterialized, List<String> sortedInlined, int limit) {
        List<String> diffs = new ArrayList<>();
        int i = 0;
        int j = 0;
        while ((i < sortedMaterialized.size() || j < sortedInlined.size()) && diffs.size() < limit) {
            if (i >= sortedMaterialized.size()) {
                diffs.add("inlined-only: " + sortedInlined.get(j++));
            } else if (j >= sortedInlined.size()) {
                diffs.add("materialized-only: " + sortedMaterialized.get(i++));
            } else {
                int cmp = sortedMaterialized.get(i).compareTo(sortedInlined.get(j));
                if (cmp == 0) {
                    i++;
                    j++;
                } else if (cmp < 0) {
                    diffs.add("materialized-only: " + sortedMaterialized.get(i++));
                } else {
                    diffs.add("inlined-only: " + sortedInlined.get(j++));
                }
            }
        }
        return diffs;
    }

    private static boolean isExactInteger(ClickHouseDataType t) {
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

}
