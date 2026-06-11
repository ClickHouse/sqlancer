package sqlancer;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.common.query.SQLancerResultSet;

public final class ComparatorHelper {

    public enum ComparisonMode {

        SET,

        MULTISET,

        ULP_TOLERANT_MULTISET
    }

    private ComparatorHelper() {
    }

    private static String trimTrailingDotZeros(String s) {
        int len = s.length();
        if (len < 2 || s.charAt(len - 1) != '0') {
            return s;
        }
        int i = len - 1;
        while (i > 0 && s.charAt(i) == '0') {
            i--;
        }
        if (s.charAt(i) != '.') {
            return s;
        }
        return s.substring(0, i);
    }

    public static boolean isEqualDouble(String first, String second) {
        try {
            double val = Double.parseDouble(first);
            double secVal = Double.parseDouble(second);
            return equals(val, secVal);
        } catch (Exception e) {
            return false;
        }
    }

    static boolean equals(double a, double b) {
        if (a == b) {
            return true;
        }

        return Math.abs(a - b) < 0.001 * Math.max(Math.abs(a), Math.abs(b)) + 0.001;
    }

    public static List<String> getResultSetFirstColumnAsString(String queryString, ExpectedErrors errors,
            SQLGlobalState<?, ?> state) throws SQLException {
        if (state.getOptions().logEachSelect()) {

            state.getLogger().writeCurrent(queryString);
            try {
                state.getLogger().getCurrentFileWriter().flush();
            } catch (IOException e) {

                e.printStackTrace();
            }
        }
        boolean canonicalizeString = state.getOptions().canonicalizeSqlString();
        SQLQueryAdapter q = new SQLQueryAdapter(queryString, errors, true, canonicalizeString);
        List<String> resultSet = new ArrayList<>();
        SQLancerResultSet result = null;
        try {
            result = q.executeAndGet(state);
            if (result == null) {
                throw new IgnoreMeException();
            }
            while (result.next()) {
                String resultTemp = result.getString(1);
                if (resultTemp != null) {

                    resultTemp = trimTrailingDotZeros(resultTemp);
                }
                resultSet.add(resultTemp);
            }
        } catch (Exception e) {
            if (e instanceof IgnoreMeException) {
                throw e;
            }

            Throwable current = e;
            while (current != null) {
                if (current.getMessage() != null && errors.errorIsExpected(current.getMessage())) {
                    throw new IgnoreMeException();
                }
                current = current.getCause();
            }
            throw new AssertionError(queryString, e);
        } finally {
            if (result != null && !result.isClosed()) {
                result.close();
            }
        }
        return resultSet;
    }

    public static void assumeResultSetsAreEqual(List<String> resultSet, List<String> secondResultSet,
            String originalQueryString, List<String> combinedString, SQLGlobalState<?, ?> state) {
        assumeResultSetsAreEqual(resultSet, secondResultSet, originalQueryString, combinedString, state,
                ComparisonMode.SET);
    }

    public static void assumeResultSetsAreEqual(List<String> resultSet, List<String> secondResultSet,
            String originalQueryString, List<String> combinedString, SQLGlobalState<?, ?> state, ComparisonMode mode) {
        if (resultSet.size() != secondResultSet.size()) {
            String queryFormatString = "-- %s;" + System.lineSeparator() + "-- cardinality: %d"
                    + System.lineSeparator();
            String firstQueryString = String.format(queryFormatString, originalQueryString, resultSet.size());
            String combinedQueryString = String.join(";", combinedString);
            String secondQueryString = String.format(queryFormatString, combinedQueryString, secondResultSet.size());
            state.getState().getLocalState()
                    .log(String.format("%s" + System.lineSeparator() + "%s", firstQueryString, secondQueryString));
            String assertionMessage = String.format(
                    "The size of the result sets mismatch (%d and %d)!" + System.lineSeparator()
                            + "First query: \"%s\", whose cardinality is: %d" + System.lineSeparator()
                            + "Second query:\"%s\", whose cardinality is: %d",
                    resultSet.size(), secondResultSet.size(), originalQueryString, resultSet.size(),
                    combinedQueryString, secondResultSet.size());
            throw new AssertionError(assertionMessage);
        }

        if (state.getOptions().validateResultSizeOnly()) {
            return;
        }

        boolean contentMatches;
        switch (mode) {
        case MULTISET:
            contentMatches = multisetsEqual(resultSet, secondResultSet)
                    || multisetsEqual(canonicalizeFloatsList(resultSet), canonicalizeFloatsList(secondResultSet))
                    || floatTolerantMultisetsEqual(resultSet, secondResultSet);
            break;
        case ULP_TOLERANT_MULTISET:
            contentMatches = multisetsEqual(canonicalizeFloatsList(resultSet), canonicalizeFloatsList(secondResultSet))
                    || floatTolerantMultisetsEqual(resultSet, secondResultSet);
            break;
        case SET:
        default:
            Set<String> firstHashSet = new HashSet<>(resultSet);
            Set<String> secondHashSet = new HashSet<>(secondResultSet);
            contentMatches = firstHashSet.equals(secondHashSet)
                    || canonicalizeFloats(resultSet).equals(canonicalizeFloats(secondResultSet))
                    || floatTolerantMultisetsEqual(new ArrayList<>(firstHashSet), new ArrayList<>(secondHashSet));
            break;
        }

        if (!contentMatches) {
            Set<String> firstResultSetMisses = new HashSet<>(resultSet);
            firstResultSetMisses.removeAll(secondResultSet);
            Set<String> secondResultSetMisses = new HashSet<>(secondResultSet);
            secondResultSetMisses.removeAll(resultSet);

            String queryFormatString = "-- Query: \"%s\"; It misses: \"%s\"";
            String firstQueryString = String.format(queryFormatString, originalQueryString, firstResultSetMisses);
            String secondQueryString = String.format(queryFormatString, String.join(";", combinedString),
                    secondResultSetMisses);
            state.getState().getLocalState()
                    .log(String.format("%s" + System.lineSeparator() + "%s", firstQueryString, secondQueryString));
            String assertionMessage = String.format("The content of the result sets mismatch!" + System.lineSeparator()
                    + "First query : \"%s\"" + System.lineSeparator() + "Second query: \"%s\"", originalQueryString,
                    secondQueryString);
            throw new AssertionError(assertionMessage);
        }
    }

    private static final double FLOAT_REL_TOLERANCE = 1e-9;
    private static final double FLOAT_ABS_TOLERANCE = 1e-9;

    private static boolean floatsWithinTolerance(double a, double b) {
        if (a == b) {
            return true;
        }
        double diff = Math.abs(a - b);
        return diff <= FLOAT_REL_TOLERANCE * Math.max(Math.abs(a), Math.abs(b)) + FLOAT_ABS_TOLERANCE;
    }

    static boolean floatTolerantMultisetsEqual(List<String> a, List<String> b) {
        if (a.size() != b.size()) {
            return false;
        }
        List<Double> numA = new ArrayList<>();
        List<Double> numB = new ArrayList<>();
        List<String> otherA = new ArrayList<>();
        List<String> otherB = new ArrayList<>();
        partitionFiniteDoubles(a, numA, otherA);
        partitionFiniteDoubles(b, numB, otherB);
        if (numA.size() != numB.size() || !multisetsEqual(otherA, otherB)) {
            return false;
        }
        Collections.sort(numA);
        Collections.sort(numB);
        for (int i = 0; i < numA.size(); i++) {
            if (!floatsWithinTolerance(numA.get(i), numB.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static void partitionFiniteDoubles(List<String> values, List<Double> numeric, List<String> other) {
        for (String v : values) {
            Double d = parseFiniteDouble(v);
            if (d == null) {
                other.add(v);
            } else {
                numeric.add(d);
            }
        }
    }

    private static Double parseFiniteDouble(String v) {
        if (v == null) {
            return null;
        }
        boolean hasDigit = false;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c >= '0' && c <= '9') {
                hasDigit = true;
                break;
            }
        }
        if (!hasDigit) {
            return null;
        }
        try {
            double d = Double.parseDouble(v);
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                return null;
            }
            return d;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean multisetsEqual(List<String> a, List<String> b) {
        if (a.size() != b.size()) {
            return false;
        }
        Map<String, Integer> counts = new HashMap<>(a.size() * 2);
        for (String v : a) {
            counts.merge(v, 1, Integer::sum);
        }
        for (String v : b) {
            Integer c = counts.get(v);
            if (c == null) {
                return false;
            }
            if (c == 1) {
                counts.remove(v);
            } else {
                counts.put(v, c - 1);
            }
        }
        return counts.isEmpty();
    }

    private static List<String> canonicalizeFloatsList(List<String> values) {
        List<String> out = new ArrayList<>(values.size());
        for (String v : values) {
            out.add(normalizeFloatString(v));
        }
        return out;
    }

    public static void assumeResultSetsAreEqual(List<String> resultSet, List<String> secondResultSet,
            String originalQueryString, List<String> combinedString, SQLGlobalState<?, ?> state,
            UnaryOperator<String> canonicalizationRule) {

        List<String> canonicalizedResultSet = resultSet.stream().map(canonicalizationRule).collect(Collectors.toList());
        List<String> canonicalizedSecondResultSet = secondResultSet.stream().map(canonicalizationRule)
                .collect(Collectors.toList());
        assumeResultSetsAreEqual(canonicalizedResultSet, canonicalizedSecondResultSet, originalQueryString,
                combinedString, state);
    }

    public static List<String> getCombinedResultSet(String firstQueryString, String secondQueryString,
            String thirdQueryString, List<String> combinedString, boolean asUnion, SQLGlobalState<?, ?> state,
            ExpectedErrors errors) throws SQLException {
        List<String> secondResultSet;
        if (asUnion) {
            String unionString = firstQueryString + " UNION ALL " + secondQueryString + " UNION ALL "
                    + thirdQueryString;
            combinedString.add(unionString);
            secondResultSet = getResultSetFirstColumnAsString(unionString, errors, state);
        } else {
            secondResultSet = new ArrayList<>();
            secondResultSet.addAll(getResultSetFirstColumnAsString(firstQueryString, errors, state));
            secondResultSet.addAll(getResultSetFirstColumnAsString(secondQueryString, errors, state));
            secondResultSet.addAll(getResultSetFirstColumnAsString(thirdQueryString, errors, state));
            combinedString.add(firstQueryString);
            combinedString.add(secondQueryString);
            combinedString.add(thirdQueryString);
        }
        return secondResultSet;
    }

    public static List<String> getCombinedResultSetNoDuplicates(String firstQueryString, String secondQueryString,
            String thirdQueryString, List<String> combinedString, boolean asUnion, SQLGlobalState<?, ?> state,
            ExpectedErrors errors) throws SQLException {
        String unionString;
        if (asUnion) {
            unionString = firstQueryString + " UNION " + secondQueryString + " UNION " + thirdQueryString;
        } else {
            unionString = "SELECT DISTINCT * FROM (" + firstQueryString + " UNION ALL " + secondQueryString
                    + " UNION ALL " + thirdQueryString + ")";
        }
        List<String> secondResultSet;
        combinedString.add(unionString);
        secondResultSet = getResultSetFirstColumnAsString(unionString, errors, state);
        return secondResultSet;
    }

    private static Set<String> canonicalizeFloats(List<String> values) {
        Set<String> out = new HashSet<>(values.size() * 2);
        for (String v : values) {
            out.add(normalizeFloatString(v));
        }
        return out;
    }

    private static String normalizeFloatString(String v) {
        if (v == null) {
            return null;
        }
        boolean hasDigit = false;
        boolean hasFractionMarker = false;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c >= '0' && c <= '9') {
                hasDigit = true;
            } else if (c == '.' || c == 'e' || c == 'E') {
                hasFractionMarker = true;
            }
        }
        if (!hasDigit || !hasFractionMarker) {
            return v;
        }
        try {
            double d = Double.parseDouble(v);
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                return v;
            }
            return Double.toString(d);
        } catch (NumberFormatException e) {
            return v;
        }
    }

    public static String canonicalizeResultValue(String value) {
        if (value == null) {
            return value;
        }

        switch (value) {
        case "-0.0":
            return "0.0";
        case "-0":
            return "0";
        default:
        }

        return value;
    }

}
