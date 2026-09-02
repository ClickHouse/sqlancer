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
        LIMIT_BY_CAP,
        NEGATIVE_LIMIT_TAIL,
        NEGATIVE_LIMIT_BY_REVERSAL,
        NEGATIVE_WITH_TIES_SUPERSET
    }

    private final ClickHouseGlobalState state;
    private final ExpectedErrors readErrors = new ExpectedErrors();

    public ClickHouseLimitRankingOracle(ClickHouseGlobalState state) {
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

        Mode mode = pickMode();
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
        case NEGATIVE_LIMIT_TAIL:
            checkNegativeLimitTail(base, totalOrder);
            break;
        case NEGATIVE_LIMIT_BY_REVERSAL:
            checkNegativeLimitByReversal(tableQ, columns);
            break;
        case NEGATIVE_WITH_TIES_SUPERSET:
            checkNegativeWithTiesSuperset(base, columns);
            break;
        default:
            throw new AssertionError(mode);
        }
    }

    private Mode pickMode() {
        if (state.getClickHouseOptions().negativeLimitEmission) {
            return Randomly.fromOptions(Mode.values());
        }
        return Randomly.fromOptions(Mode.OFFSET_FORM_EQUIVALENCE, Mode.WITH_TIES_SUPERSET, Mode.LIMIT_BY_CAP);
    }

    private void checkNegativeLimitTail(String base, String totalOrder) throws SQLException {
        long n = 1 + Randomly.getNotCachedInteger(0, 10);
        String ascOrder = totalOrder.replace(" ASC", " ASC NULLS LAST");
        String reverseOrder = totalOrder.replace(" ASC", " DESC NULLS FIRST");
        String tail = base + " ORDER BY " + ascOrder + " LIMIT -" + n;
        String head = base + " ORDER BY " + reverseOrder + " LIMIT " + n;

        List<String> tailRows = read(tail);
        List<String> headRows = read(head);

        List<String> tailSorted = sorted(tailRows);
        List<String> headSorted = sorted(headRows);
        if (!tailSorted.equals(headSorted)) {
            throw new AssertionError(String.format(
                    "LimitRanking negative-LIMIT mismatch: 'LIMIT -%d' takes the last %d rows of the ascending total "
                            + "order, which must be the same multiset as 'LIMIT %d' over the reverse total order.%n"
                            + "  tail: %s%n  head: %s%n  tail rows (%d): %s%n  head rows (%d): %s",
                    n, n, n, tail, head, tailRows.size(), truncate(tailSorted), headRows.size(), truncate(headSorted)));
        }
    }

    private void checkNegativeLimitByReversal(String tableQ, List<ClickHouseColumn> columns) throws SQLException {
        String key = quote(Randomly.fromList(columns).getName());
        String projection = "toString(tuple(" + columns.stream().map(c -> quote(c.getName()))
                .collect(Collectors.joining(", ")) + "))";
        String ascOrder = columns.stream().map(c -> quote(c.getName()) + " ASC NULLS LAST")
                .collect(Collectors.joining(", "));
        String descOrder = columns.stream().map(c -> quote(c.getName()) + " DESC NULLS FIRST")
                .collect(Collectors.joining(", "));
        long n = 1 + Randomly.getNotCachedInteger(0, 5);

        String tail = "SELECT " + projection + " FROM " + tableQ + " ORDER BY " + ascOrder + " LIMIT -" + n + " BY "
                + key;
        String head = "SELECT " + projection + " FROM " + tableQ + " ORDER BY " + descOrder + " LIMIT " + n + " BY "
                + key;

        List<String> tailRows = sorted(read(tail));
        List<String> headRows = sorted(read(head));

        if (!tailRows.equals(headRows)) {
            throw new AssertionError(String.format(
                    "LimitRanking negative-LIMIT-BY mismatch: 'LIMIT -%d BY %s' keeps the last %d rows per key in the "
                            + "ascending total order, which must be the same multiset as 'LIMIT %d BY %s' over the "
                            + "reverse total order.%n  tail: %s%n  head: %s%n  tail rows (%d): %s%n  head rows (%d): %s",
                    n, key, n, n, key, tail, head, tailRows.size(), truncate(tailRows), headRows.size(),
                    truncate(headRows)));
        }
    }

    private void checkNegativeWithTiesSuperset(String base, List<ClickHouseColumn> columns) throws SQLException {
        String key = quote(Randomly.fromList(columns).getName());
        long n = 1 + Randomly.getNotCachedInteger(0, 20);
        String plain = base + " ORDER BY " + key + " ASC LIMIT -" + n;
        String withTies = base + " ORDER BY " + key + " ASC LIMIT -" + n + " WITH TIES";

        List<String> plainRows = read(plain);
        List<String> tiesRows = read(withTies);

        if (tiesRows.size() < plainRows.size() || !isSubMultiset(plainRows, tiesRows)) {
            throw new AssertionError(String.format(
                    "LimitRanking negative WITH-TIES containment violation: the rows of 'LIMIT -%d' must be a "
                            + "sub-multiset of 'LIMIT -%d WITH TIES' under the identical ORDER BY %s.%n"
                            + "  plain (%d):    %s%n  withTies (%d): %s",
                    n, n, key, plainRows.size(), truncate(plainRows), tiesRows.size(), truncate(tiesRows)));
        }
    }

    private static List<String> sorted(List<String> rows) {
        List<String> out = new ArrayList<>(rows.size());
        for (String r : rows) {
            out.add(r == null ? "\\N" : r);
        }
        out.sort(String::compareTo);
        return out;
    }

    private void checkOffsetFormEquivalence(String base, String totalOrder) throws SQLException {
        long a = Randomly.getNotCachedInteger(0, 20);
        long b = 1 + Randomly.getNotCachedInteger(0, 20);
        String formA = base + " ORDER BY " + totalOrder + " LIMIT " + a + ", " + b;
        String formB = base + " ORDER BY " + totalOrder + " LIMIT " + b + " OFFSET " + a;

        List<String> rowsA = read(formA);
        List<String> rowsB = read(formB);

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

        List<String> plainRows = read(plain);
        List<String> tiesRows = read(withTies);

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
        String limitByQuery = "SELECT " + key + " AS lb_key FROM " + tableQ + " ORDER BY " + totalOrder + " LIMIT " + n
                + " BY " + key;
        String query = "SELECT toString(max(cnt)) FROM (SELECT count() AS cnt FROM (" + limitByQuery + ") GROUP BY "
                + "lb_key)";

        List<String> rows = read(query);
        if (rows.size() != 1 || rows.get(0) == null) {
            throw new IgnoreMeException();
        }
        long maxPerKey;
        try {
            maxPerKey = Long.parseLong(rows.get(0).trim());
        } catch (NumberFormatException e) {
            throw new IgnoreMeException();
        }
        if (maxPerKey > n) {
            throw new AssertionError(String.format(
                    "LimitRanking LIMIT-BY cap violation: the most frequent key appears %d times but 'LIMIT %d BY %s' "
                            + "caps it at %d.%n  Q: %s",
                    maxPerKey, n, key, n, query));
        }
    }

    private List<String> read(String query) throws SQLException {
        if (state.getOptions().logEachSelect()) {
            state.getState().logStatement(query);
        }
        return ComparatorHelper.getResultSetFirstColumnAsString(query, readErrors, state);
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
