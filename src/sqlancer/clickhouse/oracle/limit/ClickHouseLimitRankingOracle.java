package sqlancer.clickhouse.oracle.limit;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import sqlancer.ComparatorHelper;
import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.clickhouse.ClickHouseErrors;
import sqlancer.clickhouse.ClickHouseProvider.ClickHouseGlobalState;
import sqlancer.clickhouse.ClickHouseSchema.ClickHouseColumn;
import sqlancer.clickhouse.ClickHouseSchema.ClickHouseTable;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.ExpectedErrors;

public class ClickHouseLimitRankingOracle implements TestOracle<ClickHouseGlobalState> {

    enum Mode {
        OFFSET_FORM_EQUIVALENCE,
        WITH_TIES_SUPERSET,
        LIMIT_BY_CAP
    }

    private final ClickHouseGlobalState state;
    private final ExpectedErrors readErrors = new ExpectedErrors();

    public ClickHouseLimitRankingOracle(ClickHouseGlobalState state) {
        this.state = state;
        ClickHouseErrors.addSessionSettingsErrors(readErrors);

        readErrors.add("UNKNOWN_TABLE");
        readErrors.add("Unknown table expression identifier");

        readErrors.add("(MEMORY_LIMIT_EXCEEDED)");
        readErrors.add("memory limit exceeded");

        readErrors.add("TIMEOUT_EXCEEDED");
        readErrors.add("Timeout exceeded");

        readErrors.add("Limit for result exceeded");
        readErrors.add("TOO_MANY_ROWS_OR_BYTES");

        readErrors.add("WITH TIES");
        readErrors.add("LIMIT BY");
        readErrors.add("SYNTAX_ERROR");
    }

    @Override
    public void check() throws SQLException {
        if (!state.getClickHouseOptions().limitRankingOracle) {
            throw new IgnoreMeException();
        }

        List<ClickHouseTable> tables = state.getSchema().getRandomTableNonEmptyTables().getTables();
        List<ClickHouseTable> eligible = tables.stream().filter(t -> !t.isView()).collect(Collectors.toList());
        if (eligible.isEmpty()) {
            throw new IgnoreMeException();
        }
        ClickHouseTable table = Randomly.fromList(eligible);
        List<ClickHouseColumn> columns = projectableColumns(table);
        if (columns.isEmpty()) {
            throw new IgnoreMeException();
        }

        String tableQ = quote(table.getName());
        String projection = "toString(tuple(" + columns.stream().map(c -> quote(c.getName()))
                .collect(Collectors.joining(", ")) + "))";
        String totalOrder = columns.stream().map(c -> quote(c.getName()) + " ASC").collect(Collectors.joining(", "));
        String base = "SELECT " + projection + " FROM " + tableQ;

        Mode mode = Randomly.fromOptions(Mode.values());
        switch (mode) {
        case OFFSET_FORM_EQUIVALENCE:
            checkOffsetFormEquivalence(base, totalOrder);
            break;
        case WITH_TIES_SUPERSET:
            checkWithTiesSuperset(base, columns);
            break;
        case LIMIT_BY_CAP:
            checkLimitByCap(tableQ, totalOrder, columns);
            break;
        default:
            throw new AssertionError(mode);
        }
    }

    private void checkOffsetFormEquivalence(String base, String totalOrder) throws SQLException {
        long a = Randomly.getNotCachedInteger(0, 20);
        long b = 1 + Randomly.getNotCachedInteger(0, 20);
        String formA = base + " ORDER BY " + totalOrder + " LIMIT " + a + ", " + b;
        String formB = base + " ORDER BY " + totalOrder + " LIMIT " + b + " OFFSET " + a;

        List<String> rowsA = ComparatorHelper.getResultSetFirstColumnAsString(formA, readErrors, state);
        List<String> rowsB = ComparatorHelper.getResultSetFirstColumnAsString(formB, readErrors, state);

        if (!rowsA.equals(rowsB)) {
            throw new AssertionError(String.format(
                    "LimitRanking OFFSET-form mismatch: 'LIMIT a, b' and 'LIMIT b OFFSET a' disagree under identical "
                            + "total ORDER BY.%n  A: %s%n  B: %s%n  A rows (%d): %s%n  B rows (%d): %s",
                    formA, formB, rowsA.size(), truncate(rowsA), rowsB.size(), truncate(rowsB)));
        }
    }

    private void checkWithTiesSuperset(String base, List<ClickHouseColumn> columns) throws SQLException {
        String key = quote(Randomly.fromList(columns).getName());
        long n = 1 + Randomly.getNotCachedInteger(0, 20);
        String plain = base + " ORDER BY " + key + " ASC LIMIT " + n;
        String withTies = base + " ORDER BY " + key + " ASC LIMIT " + n + " WITH TIES";

        List<String> plainRows = ComparatorHelper.getResultSetFirstColumnAsString(plain, readErrors, state);
        List<String> tiesRows = ComparatorHelper.getResultSetFirstColumnAsString(withTies, readErrors, state);

        if (tiesRows.size() < plainRows.size()) {
            throw new AssertionError(String.format(
                    "LimitRanking WITH-TIES superset violation: WITH TIES returned fewer rows (%d) than plain LIMIT "
                            + "(%d) under identical ORDER BY %s.%n  plain:    %s%n  withTies: %s",
                    tiesRows.size(), plainRows.size(), key, plain, withTies));
        }
        if (!isSubMultiset(plainRows, tiesRows)) {
            throw new AssertionError(String.format(
                    "LimitRanking WITH-TIES containment violation: plain LIMIT rows are not a sub-multiset of WITH TIES "
                            + "rows under identical ORDER BY %s (rows tied on the key may legally reorder between the two "
                            + "queries, so only containment is asserted).%n  plain (%d):    %s%n  withTies (%d): %s",
                    key, plainRows.size(), truncate(plainRows), tiesRows.size(), truncate(tiesRows)));
        }
    }

    private static boolean isSubMultiset(List<String> sub, List<String> sup) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String v : sup) {
            counts.merge(v == null ? "\\N" : v, 1L, Long::sum);
        }
        for (String v : sub) {
            String k = v == null ? "\\N" : v;
            long remaining = counts.getOrDefault(k, 0L) - 1;
            if (remaining < 0) {
                return false;
            }
            counts.put(k, remaining);
        }
        return true;
    }

    private void checkLimitByCap(String tableQ, String totalOrder, List<ClickHouseColumn> columns) throws SQLException {
        String key = quote(Randomly.fromList(columns).getName());
        long n = 1 + Randomly.getNotCachedInteger(0, 5);
        String query = "SELECT toString(" + key + ") FROM " + tableQ + " ORDER BY " + totalOrder + " LIMIT " + n
                + " BY " + key;

        List<String> keyValues = ComparatorHelper.getResultSetFirstColumnAsString(query, readErrors, state);
        Map<String, Long> perKey = new LinkedHashMap<>();
        for (String v : keyValues) {
            perKey.merge(v == null ? "\\N" : v, 1L, Long::sum);
        }
        for (Map.Entry<String, Long> e : perKey.entrySet()) {
            if (e.getValue() > n) {
                throw new AssertionError(String.format(
                        "LimitRanking LIMIT-BY cap violation: key %s appears %d times but 'LIMIT %d BY %s' caps it at "
                                + "%d.%n  Q: %s",
                        e.getKey(), e.getValue(), n, key, n, query));
            }
        }
    }

    private static List<ClickHouseColumn> projectableColumns(ClickHouseTable table) {
        List<ClickHouseColumn> out = new ArrayList<>();
        for (ClickHouseColumn c : table.getColumns()) {
            if (c.isAlias() || c.isMaterialized()) {
                continue;
            }
            out.add(c);
        }
        return out;
    }

    private static String truncate(List<String> rows) {
        int limit = 50;
        if (rows.size() <= limit) {
            return rows.toString();
        }
        return rows.subList(0, limit) + "... (" + rows.size() + " total)";
    }

    private static String quote(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }
}
