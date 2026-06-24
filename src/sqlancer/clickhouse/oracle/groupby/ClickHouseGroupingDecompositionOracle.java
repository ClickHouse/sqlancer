package sqlancer.clickhouse.oracle.groupby;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import com.clickhouse.data.ClickHouseDataType;

import sqlancer.ComparatorHelper;
import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.clickhouse.ClickHouseErrors;
import sqlancer.clickhouse.ClickHouseProvider.ClickHouseGlobalState;
import sqlancer.clickhouse.ClickHouseSchema.ClickHouseColumn;
import sqlancer.clickhouse.ClickHouseSchema.ClickHouseTable;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.ExpectedErrors;

public class ClickHouseGroupingDecompositionOracle implements TestOracle<ClickHouseGlobalState> {

    private final ClickHouseGlobalState state;
    private final ExpectedErrors readErrors = new ExpectedErrors();

    public ClickHouseGroupingDecompositionOracle(ClickHouseGlobalState state) {
        this.state = state;
        ClickHouseErrors.addExpectedExpressionErrors(readErrors);
        ClickHouseErrors.addSessionSettingsErrors(readErrors);
        readErrors.add("UNKNOWN_TABLE");
        readErrors.add("Unknown table expression identifier");
        readErrors.add("UNKNOWN_IDENTIFIER");
        readErrors.add("Missing columns");
        readErrors.add("(MEMORY_LIMIT_EXCEEDED)");
        readErrors.add("memory limit exceeded");
        readErrors.add("TIMEOUT_EXCEEDED");
        readErrors.add("Timeout exceeded");
        readErrors.add("Limit for result exceeded");
        readErrors.add("TOO_MANY_ROWS_OR_BYTES");
        readErrors.add("ILLEGAL_AGGREGATION");
        readErrors.add("NOT_AN_AGGREGATE");
        readErrors.add("Aggregate function GROUPING");
        readErrors.add("Unknown function GROUPING");
    }

    @Override
    public void check() throws SQLException {
        if (!state.getClickHouseOptions().groupingDecompositionOracle) {
            throw new IgnoreMeException();
        }
        List<ClickHouseTable> tables = state.getSchema().getRandomTableNonEmptyTables().getTables();
        if (tables.isEmpty()) {
            throw new IgnoreMeException();
        }
        ClickHouseTable table = Randomly.fromList(tables);
        if (table.isView()) {
            throw new IgnoreMeException();
        }
        List<ClickHouseColumn> eligible = table.getColumns().stream()
                .filter(c -> !c.isAlias() && !c.isMaterialized())
                .filter(c -> isNonFloatScalarKey(c.getType().getType())).collect(Collectors.toList());
        if (eligible.isEmpty()) {
            throw new IgnoreMeException();
        }

        String tableQ = quote(table.getName());
        ClickHouseColumn keyColumn = Randomly.fromList(eligible);
        String keyRef = quote(keyColumn.getName());
        String label = "key=" + keyColumn.getName() + " table=" + table.getName();

        checkRollupDetailEqualsPlain(tableQ, keyRef, label);
        checkRollupSuperAggregateEqualsGrandTotal(tableQ, keyRef, label);
        checkPerGroupCountSumEqualsGrandTotal(tableQ, keyRef, label);
    }

    private void checkRollupDetailEqualsPlain(String tableQ, String keyRef, String label) throws SQLException {
        String plain = "SELECT toString(tuple(" + keyRef + ", count())) FROM " + tableQ + " GROUP BY " + keyRef;
        String rollupDetail = "SELECT toString(tuple(" + keyRef + ", count())) FROM " + tableQ + " GROUP BY " + keyRef
                + " WITH ROLLUP HAVING GROUPING(" + keyRef + ") = 0";

        List<String> plainRows = ComparatorHelper.getResultSetFirstColumnAsString(plain, readErrors, state);
        List<String> rollupRows = ComparatorHelper.getResultSetFirstColumnAsString(rollupDetail, readErrors, state);
        List<String> diff = multisetDiff(plainRows, rollupRows);
        if (!diff.isEmpty()) {
            throw new AssertionError(String.format(
                    "grouping-decomposition ROLLUP-detail vs plain multiset mismatch [%s]:%n  plain (%d rows): %s%n  "
                            + "rollup-detail (%d rows): %s%n  first differing entries: %s",
                    label, plainRows.size(), plain, rollupRows.size(), rollupDetail, diff));
        }
    }

    static List<String> multisetDiff(List<String> left, List<String> right) {
        Map<String, Long> counts = new TreeMap<>();
        for (String s : left) {
            counts.merge(s == null ? "\\N" : s, 1L, Long::sum);
        }
        for (String s : right) {
            counts.merge(s == null ? "\\N" : s, -1L, Long::sum);
        }
        List<String> diff = new ArrayList<>();
        for (Map.Entry<String, Long> e : counts.entrySet()) {
            if (e.getValue() == 0) {
                continue;
            }
            if (diff.size() >= 20) {
                break;
            }
            long c = e.getValue();
            diff.add(e.getKey() + " (+" + Math.abs(c) + " " + (c > 0 ? "plain" : "rollup") + ")");
        }
        return diff;
    }

    private void checkRollupSuperAggregateEqualsGrandTotal(String tableQ, String keyRef, String label)
            throws SQLException {
        String rollupSuper = "SELECT toString(count()) FROM " + tableQ + " GROUP BY " + keyRef
                + " WITH ROLLUP HAVING GROUPING(" + keyRef + ") = 1";
        String grand = "SELECT toString(count()) FROM " + tableQ;

        String superValue = readSingleValue(rollupSuper);
        String grandValue = readSingleValue(grand);
        if (!grandValue.equals(superValue)) {
            throw new AssertionError(String.format(
                    "grouping-decomposition ROLLUP super-aggregate mismatch [%s]:%n  super: %s -> %s%n  grand: %s -> %s",
                    label, rollupSuper, superValue, grand, grandValue));
        }
    }

    private void checkPerGroupCountSumEqualsGrandTotal(String tableQ, String keyRef, String label)
            throws SQLException {
        String perGroupSum = "SELECT toString(sum(g)) FROM (SELECT count() AS g FROM " + tableQ + " GROUP BY " + keyRef
                + ")";
        String grand = "SELECT toString(count()) FROM " + tableQ;

        String sumValue = readSingleValue(perGroupSum);
        String grandValue = readSingleValue(grand);
        if (!grandValue.equals(sumValue)) {
            throw new AssertionError(String.format(
                    "grouping-decomposition per-group-count-sum mismatch [%s]:%n  sum:   %s -> %s%n  grand: %s -> %s",
                    label, perGroupSum, sumValue, grand, grandValue));
        }
    }

    private String readSingleValue(String query) throws SQLException {
        List<String> rows = ComparatorHelper.getResultSetFirstColumnAsString(query, readErrors, state);
        if (rows.size() != 1) {
            throw new IgnoreMeException();
        }
        return rows.get(0);
    }

    static boolean isNonFloatScalarKey(ClickHouseDataType t) {
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
        case String:
        case FixedString:
        case Date:
        case Date32:
        case DateTime:
        case DateTime32:
        case DateTime64:
        case UUID:
            return true;
        default:
            return false;
        }
    }

    static String quote(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }
}
