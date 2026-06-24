package sqlancer.clickhouse.oracle.arrayfn;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
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

public class ClickHouseArrayFunctionOracle implements TestOracle<ClickHouseGlobalState> {

    private static final AtomicLong CTR = new AtomicLong();

    private final ClickHouseGlobalState state;
    private final ExpectedErrors errors = new ExpectedErrors();

    public ClickHouseArrayFunctionOracle(ClickHouseGlobalState state) {
        this.state = state;
        ClickHouseErrors.addSessionSettingsErrors(errors);
        ClickHouseErrors.addExpectedExpressionErrors(errors);
        errors.add("UNKNOWN_TABLE");
        errors.add("Unknown table expression identifier");
        errors.add("NOT_IMPLEMENTED");
        errors.add("ILLEGAL_TYPE_OF_ARGUMENT");
        errors.add("(MEMORY_LIMIT_EXCEEDED)");
        errors.add("memory limit exceeded");
        errors.add("TIMEOUT_EXCEEDED");
        errors.add("Timeout exceeded");
        errors.add("Limit for result exceeded");
        errors.add("TOO_MANY_ROWS_OR_BYTES");
    }

    @Override
    public void check() throws SQLException {
        if (!state.getClickHouseOptions().arrayFunctionOracle) {
            throw new IgnoreMeException();
        }
        long id = CTR.incrementAndGet();
        Randomly r = state.getRandomly();
        String table = state.getDatabaseName() + ".arrfn_" + id;
        String create = "CREATE TABLE " + table
                + " (k UInt32, arr Array(Int64)) ENGINE = MergeTree ORDER BY k";

        int rows = 25 + (int) r.getInteger(0, 26);
        List<List<Long>> model = new ArrayList<>();

        try {
            logStmt(create);
            if (!new SQLQueryAdapter(create, errors, true).execute(state)) {
                throw new IgnoreMeException();
            }

            StringBuilder sb = new StringBuilder("INSERT INTO ").append(table).append(" (k, arr) VALUES ");
            for (int i = 0; i < rows; i++) {
                int len = (int) r.getInteger(0, 7);
                List<Long> arr = new ArrayList<>();
                for (int j = 0; j < len; j++) {
                    arr.add((long) r.getInteger(0, 21));
                }
                model.add(arr);
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append('(').append(i).append(", ").append(renderIntArrayLiteral(arr)).append(')');
            }
            logStmt(sb.toString());
            if (!new SQLQueryAdapter(sb.toString(), errors, true).execute(state)) {
                throw new IgnoreMeException();
            }

            long needle = r.getInteger(0, 21);
            List<Long> subElems = new ArrayList<>();
            int subLen = 1 + (int) r.getInteger(0, 3);
            for (int i = 0; i < subLen; i++) {
                subElems.add((long) r.getInteger(0, 21));
            }

            boolean anyEmpty = model.stream().anyMatch(List::isEmpty);

            List<Probe> candidates = buildCandidates(model, needle, subElems, anyEmpty);

            int probeCount = 4 + (int) r.getInteger(0, 3);
            List<Probe> selected = selectProbes(candidates, probeCount, r);

            for (Probe probe : selected) {
                assertProbe(table, probe.projection, probe.expected, probe.label);
            }
        } finally {
            dropQuietly(table);
        }
    }

    private static final class Probe {
        final String projection;
        final List<String> expected;
        final String label;

        Probe(String projection, List<String> expected, String label) {
            this.projection = projection;
            this.expected = expected;
            this.label = label;
        }
    }

    private List<Probe> buildCandidates(List<List<Long>> model, long needle, List<Long> sub, boolean anyEmpty) {
        List<Probe> all = new ArrayList<>();

        all.add(new Probe(
                "toString(has(arr, " + needle + "))",
                mapRows(model, arr -> arr.contains(needle) ? "1" : "0"),
                "has"));

        all.add(new Probe(
                "toString(indexOf(arr, " + needle + "))",
                mapRows(model, arr -> {
                    int idx = arr.indexOf(needle);
                    return String.valueOf(idx < 0 ? 0 : idx + 1);
                }),
                "indexOf"));

        all.add(new Probe(
                "toString(countEqual(arr, " + needle + "))",
                mapRows(model, arr -> String.valueOf(arr.stream().filter(x -> x == needle).count())),
                "countEqual"));

        all.add(new Probe(
                "toString(length(arr))",
                mapRows(model, arr -> String.valueOf(arr.size())),
                "length"));

        all.add(new Probe(
                "toString(empty(arr))",
                mapRows(model, arr -> arr.isEmpty() ? "1" : "0"),
                "empty"));

        all.add(new Probe(
                "toString(notEmpty(arr))",
                mapRows(model, arr -> arr.isEmpty() ? "0" : "1"),
                "notEmpty"));

        all.add(new Probe(
                "toString(arraySort(arr))",
                mapRows(model, arr -> {
                    List<Long> s = new ArrayList<>(arr);
                    Collections.sort(s);
                    return renderIntArrayText(s);
                }),
                "arraySort"));

        all.add(new Probe(
                "toString(arrayReverseSort(arr))",
                mapRows(model, arr -> {
                    List<Long> s = new ArrayList<>(arr);
                    s.sort(Collections.reverseOrder());
                    return renderIntArrayText(s);
                }),
                "arrayReverseSort"));

        all.add(new Probe(
                "toString(arrayReverse(arr))",
                mapRows(model, arr -> {
                    List<Long> rev = new ArrayList<>(arr);
                    Collections.reverse(rev);
                    return renderIntArrayText(rev);
                }),
                "arrayReverse"));

        all.add(new Probe(
                "toString(arrayDistinct(arr))",
                mapRows(model, arr -> renderIntArrayText(new ArrayList<>(new LinkedHashSet<>(arr)))),
                "arrayDistinct"));

        all.add(new Probe(
                "toString(arrayCompact(arr))",
                mapRows(model, arr -> {
                    List<Long> compact = new ArrayList<>();
                    for (Long v : arr) {
                        if (compact.isEmpty() || !compact.get(compact.size() - 1).equals(v)) {
                            compact.add(v);
                        }
                    }
                    return renderIntArrayText(compact);
                }),
                "arrayCompact"));

        all.add(new Probe(
                "toString(arrayConcat(arr, [" + needle + "]))",
                mapRows(model, arr -> {
                    List<Long> concat = new ArrayList<>(arr);
                    concat.add(needle);
                    return renderIntArrayText(concat);
                }),
                "arrayConcat"));

        all.add(new Probe(
                "toString(arrayPushBack(arr, " + needle + "))",
                mapRows(model, arr -> {
                    List<Long> pushed = new ArrayList<>(arr);
                    pushed.add(needle);
                    return renderIntArrayText(pushed);
                }),
                "arrayPushBack"));

        all.add(new Probe(
                "toString(arrayPushFront(arr, " + needle + "))",
                mapRows(model, arr -> {
                    List<Long> pushed = new ArrayList<>(arr);
                    pushed.add(0, needle);
                    return renderIntArrayText(pushed);
                }),
                "arrayPushFront"));

        all.add(new Probe(
                "toString(arraySlice(arr, 2, 3))",
                mapRows(model, arr -> {
                    if (arr.size() < 2) {
                        return renderIntArrayText(Collections.emptyList());
                    }
                    int end = Math.min(1 + 3, arr.size());
                    return renderIntArrayText(arr.subList(1, end));
                }),
                "arraySlice"));

        String subLiteral = renderIntArrayLiteral(sub);

        all.add(new Probe(
                "toString(hasAll(arr, " + subLiteral + "))",
                mapRows(model, arr -> arr.containsAll(sub) ? "1" : "0"),
                "hasAll"));

        all.add(new Probe(
                "toString(hasAny(arr, " + subLiteral + "))",
                mapRows(model, arr -> {
                    for (Long s : sub) {
                        if (arr.contains(s)) {
                            return "1";
                        }
                    }
                    return "0";
                }),
                "hasAny"));

        if (!anyEmpty) {
            all.add(new Probe(
                    "toString(arraySum(arr))",
                    mapRows(model, arr -> String.valueOf(arr.stream().mapToLong(Long::longValue).sum())),
                    "arraySum"));

            all.add(new Probe(
                    "toString(arrayMin(arr))",
                    mapRows(model, arr -> String.valueOf(Collections.min(arr))),
                    "arrayMin"));

            all.add(new Probe(
                    "toString(arrayMax(arr))",
                    mapRows(model, arr -> String.valueOf(Collections.max(arr))),
                    "arrayMax"));
        }

        return all;
    }

    private interface RowMapper {
        String map(List<Long> arr);
    }

    private List<String> mapRows(List<List<Long>> model, RowMapper mapper) {
        List<String> result = new ArrayList<>(model.size());
        for (List<Long> arr : model) {
            result.add(mapper.map(arr));
        }
        return result;
    }

    private List<Probe> selectProbes(List<Probe> candidates, int count, Randomly r) {
        List<Probe> shuffled = new ArrayList<>(candidates);
        Collections.shuffle(shuffled, new java.util.Random(r.getInteger(0, Integer.MAX_VALUE)));
        return shuffled.subList(0, Math.min(count, shuffled.size()));
    }

    private void assertProbe(String table, String projection, List<String> expected, String label) throws SQLException {
        String query = "SELECT " + projection + " FROM " + table + " ORDER BY k";
        logStmt(query);
        List<String> actual = ComparatorHelper.getResultSetFirstColumnAsString(query, errors, state);
        if (actual.size() != expected.size()) {
            throw new AssertionError(String.format(
                    "arrayfn ground-truth row-count mismatch (%s): Java expects %d rows but query returned %d. Q: %s",
                    label, expected.size(), actual.size(), query));
        }
        for (int i = 0; i < expected.size(); i++) {
            if (!expected.get(i).equals(actual.get(i))) {
                throw new AssertionError(String.format(
                        "arrayfn ground-truth value mismatch (%s) at row %d: Java expects %s but query returned %s. Q: %s",
                        label, i, expected.get(i), actual.get(i), query));
            }
        }
    }

    static String renderIntArrayLiteral(List<Long> elems) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < elems.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(elems.get(i));
        }
        return sb.append(']').toString();
    }

    static String renderIntArrayText(List<Long> elems) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < elems.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(elems.get(i));
        }
        return sb.append(']').toString();
    }

    private void dropQuietly(String table) {
        try {
            new SQLQueryAdapter("DROP TABLE IF EXISTS " + table, errors, true).execute(state);
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
