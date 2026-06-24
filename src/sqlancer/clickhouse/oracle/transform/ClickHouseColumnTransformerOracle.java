package sqlancer.clickhouse.oracle.transform;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
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

public class ClickHouseColumnTransformerOracle implements TestOracle<ClickHouseGlobalState> {

    enum Mode {
        EXCEPT,
        APPLY,
        COLUMNS_REGEX,
        DISTINCT_ON
    }

    private final ClickHouseGlobalState state;
    private final ExpectedErrors readErrors = new ExpectedErrors();

    public ClickHouseColumnTransformerOracle(ClickHouseGlobalState state) {
        this.state = state;
        ClickHouseErrors.addExpectedExpressionErrors(readErrors);
        ClickHouseErrors.addSessionSettingsErrors(readErrors);

        readErrors.add("UNKNOWN_TABLE");
        readErrors.add("Unknown table expression identifier");

        readErrors.add("(MEMORY_LIMIT_EXCEEDED)");
        readErrors.add("memory limit exceeded");

        readErrors.add("TIMEOUT_EXCEEDED");
        readErrors.add("Timeout exceeded");

        readErrors.add("Limit for result exceeded");
        readErrors.add("TOO_MANY_ROWS_OR_BYTES");

        readErrors.add("EMPTY_LIST_OF_COLUMNS_QUERIED");
        readErrors.add("No columns in result");

        readErrors.add("SYNTAX_ERROR");
        readErrors.add("Syntax error");
        readErrors.add("UNKNOWN_IDENTIFIER");
        readErrors.add("NOT_IMPLEMENTED");
    }

    @Override
    public void check() throws SQLException {
        if (!state.getClickHouseOptions().columnTransformerOracle) {
            throw new IgnoreMeException();
        }

        ClickHouseTable table = pickEligibleTable();
        List<ClickHouseColumn> physical = physicalColumns(table);
        if (physical.size() < 2) {
            throw new IgnoreMeException();
        }

        String tableQ = quote(state.getDatabaseName()) + "." + quote(table.getName());

        Mode mode = Randomly.fromOptions(Mode.values());
        switch (mode) {
        case EXCEPT:
            checkExcept(tableQ, physical);
            break;
        case APPLY:
            checkApply(tableQ, physical);
            break;
        case COLUMNS_REGEX:
            checkColumnsRegex(tableQ, physical);
            break;
        case DISTINCT_ON:
            checkDistinctOn(tableQ, physical);
            break;
        default:
            throw new AssertionError(mode);
        }
    }

    private void checkExcept(String tableQ, List<ClickHouseColumn> physical) throws SQLException {
        int idx = (int) Randomly.getNotCachedInteger(0, physical.size());
        ClickHouseColumn dropped = physical.get(idx);

        List<String> kept = new ArrayList<>();
        for (ClickHouseColumn c : physical) {
            if (!c.getName().equals(dropped.getName())) {
                kept.add(quote(c.getName()));
            }
        }
        if (kept.isEmpty()) {
            throw new IgnoreMeException();
        }

        String transformer = "* EXCEPT(" + quote(dropped.getName()) + ")";
        String explicit = String.join(", ", kept);

        assertTupleProjectionsEqual(tableQ, transformer, explicit, "EXCEPT dropped=" + dropped.getName());
    }

    private void checkApply(String tableQ, List<ClickHouseColumn> physical) throws SQLException {
        String transformer = "* APPLY(toString)";
        String explicit = physical.stream().map(c -> "toString(" + quote(c.getName()) + ")")
                .collect(Collectors.joining(", "));
        assertTupleProjectionsEqual(tableQ, transformer, explicit, "APPLY(toString)");
    }

    private void checkColumnsRegex(String tableQ, List<ClickHouseColumn> physical) throws SQLException {
        ClickHouseColumn anchor = physical.get((int) Randomly.getNotCachedInteger(0, physical.size()));
        String name = anchor.getName();
        int prefixLen = 1 + (int) Randomly.getNotCachedInteger(0, name.length());
        String prefix = name.substring(0, prefixLen);
        if (!isPlainRegexPrefix(prefix)) {
            throw new IgnoreMeException();
        }

        List<String> matching = new ArrayList<>();
        for (ClickHouseColumn c : physical) {
            if (c.getName().startsWith(prefix)) {
                matching.add(quote(c.getName()));
            }
        }
        if (matching.isEmpty()) {
            throw new IgnoreMeException();
        }

        String transformer = "COLUMNS('^" + prefix + "')";
        String explicit = String.join(", ", matching);
        assertTupleProjectionsEqual(tableQ, transformer, explicit, "COLUMNS(^" + prefix + ")");
    }

    private void checkDistinctOn(String tableQ, List<ClickHouseColumn> physical) throws SQLException {
        List<ClickHouseColumn> nonFloat = physical.stream().filter(c -> !isFloat(c.getType().getType()))
                .collect(Collectors.toList());
        if (nonFloat.isEmpty()) {
            throw new IgnoreMeException();
        }
        ClickHouseColumn key = nonFloat.get((int) Randomly.getNotCachedInteger(0, nonFloat.size()));
        String keyQ = quote(key.getName());

        String distinctOnSql = "SELECT toString(count()) FROM (SELECT DISTINCT ON (" + keyQ + ") * FROM " + tableQ + ")";
        String groupKeysSql = "SELECT toString(count()) FROM (SELECT " + keyQ + " FROM " + tableQ + " GROUP BY " + keyQ
                + ")";

        String distinctOnCount = readSingleValue(distinctOnSql);
        String groupKeysCount = readSingleValue(groupKeysSql);

        if (!distinctOnCount.equals(groupKeysCount)) {
            throw new AssertionError(String.format(
                    "column-transformer DISTINCT ON cardinality mismatch (key %s):%n  DISTINCT ON count: %s -> %s%n"
                            + "  distinct-key-group count: %s -> %s",
                    key.getName(), distinctOnSql, distinctOnCount, groupKeysSql, groupKeysCount));
        }
    }

    private void assertTupleProjectionsEqual(String tableQ, String transformerProjection, String explicitProjection,
            String label) throws SQLException {
        String transformerSql = "SELECT toString(tuple(" + transformerProjection + ")) FROM " + tableQ;
        String explicitSql = "SELECT toString(tuple(" + explicitProjection + ")) FROM " + tableQ;

        List<String> transformerRows = ComparatorHelper.getResultSetFirstColumnAsString(transformerSql, readErrors,
                state);
        List<String> explicitRows = ComparatorHelper.getResultSetFirstColumnAsString(explicitSql, readErrors, state);

        List<String> diff = ClickHouseColumnTransformerOracle.multisetDiff(transformerRows, explicitRows, 20);
        if (!diff.isEmpty()) {
            throw new AssertionError(String.format(
                    "column-transformer multiset mismatch (%s): %d transformer rows vs %d explicit rows.%n"
                            + "transformer: %s%nexplicit:    %s%nfirst differing entries: %s",
                    label, transformerRows.size(), explicitRows.size(), transformerSql, explicitSql, diff));
        }
    }

    static List<String> multisetDiff(List<String> a, List<String> b, int limit) {
        java.util.Map<String, Long> counts = new java.util.TreeMap<>();
        for (String s : a) {
            counts.merge(s == null ? "\\N" : s, 1L, Long::sum);
        }
        for (String s : b) {
            counts.merge(s == null ? "\\N" : s, -1L, Long::sum);
        }
        List<String> diff = new ArrayList<>();
        for (java.util.Map.Entry<String, Long> e : counts.entrySet()) {
            if (e.getValue() == 0) {
                continue;
            }
            if (diff.size() >= limit) {
                break;
            }
            long c = e.getValue();
            diff.add(e.getKey() + " (+" + Math.abs(c) + " " + (c > 0 ? "transformer" : "explicit") + ")");
        }
        return diff;
    }

    private ClickHouseTable pickEligibleTable() {
        List<ClickHouseTable> eligible = state.getSchema().getDatabaseTables().stream()
                .filter(ClickHouseColumnTransformerOracle::isEligibleTable).collect(Collectors.toList());
        if (eligible.isEmpty()) {
            throw new IgnoreMeException();
        }
        return eligible.get((int) Randomly.getNotCachedInteger(0, eligible.size()));
    }

    static boolean isEligibleTable(ClickHouseTable table) {
        if (table.isView()) {
            return false;
        }
        List<ClickHouseColumn> cols = table.getColumns();
        if (cols.size() < 2) {
            return false;
        }
        for (ClickHouseColumn c : cols) {
            if (c.isAlias() || c.isMaterialized()) {
                return false;
            }
            if (!isRenderable(c.getType().getType())) {
                return false;
            }
        }
        return true;
    }

    private static List<ClickHouseColumn> physicalColumns(ClickHouseTable table) {
        return table.getColumns().stream().filter(c -> !c.isAlias() && !c.isMaterialized())
                .collect(Collectors.toList());
    }

    static boolean isRenderable(ClickHouseDataType t) {
        switch (t) {
        case JSON:
        case Variant:
        case Dynamic:
        case AggregateFunction:
        case Nothing:
            return false;
        default:
            return true;
        }
    }

    static boolean isFloat(ClickHouseDataType t) {
        return t == ClickHouseDataType.Float32 || t == ClickHouseDataType.Float64;
    }

    static boolean isPlainRegexPrefix(String prefix) {
        if (prefix.isEmpty()) {
            return false;
        }
        for (int i = 0; i < prefix.length(); i++) {
            char ch = prefix.charAt(i);
            boolean ok = ch >= 'a' && ch <= 'z' || ch >= 'A' && ch <= 'Z' || ch >= '0' && ch <= '9' || ch == '_';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private String readSingleValue(String query) throws SQLException {
        List<String> rows = ComparatorHelper.getResultSetFirstColumnAsString(query, readErrors, state);
        if (rows.size() != 1) {
            throw new IgnoreMeException();
        }
        return rows.get(0);
    }

    private static String quote(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }
}
