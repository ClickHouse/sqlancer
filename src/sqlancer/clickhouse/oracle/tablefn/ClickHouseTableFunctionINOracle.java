package sqlancer.clickhouse.oracle.tablefn;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

import sqlancer.ComparatorHelper;
import sqlancer.Randomly;
import sqlancer.clickhouse.ClickHouseErrors;
import sqlancer.clickhouse.ClickHouseProvider.ClickHouseGlobalState;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.ExpectedErrors;

public class ClickHouseTableFunctionINOracle implements TestOracle<ClickHouseGlobalState> {

    private static final long MAX_N = 10000L;

    private static final int MIN_IN_SIZE = 2;
    private static final int MAX_IN_SIZE = 8;

    private final ClickHouseGlobalState state;
    private final ExpectedErrors errors = new ExpectedErrors();

    public ClickHouseTableFunctionINOracle(ClickHouseGlobalState state) {
        this.state = state;
        ClickHouseErrors.addExpectedExpressionErrors(errors);
    }

    @Override
    public void check() throws SQLException {
        Randomly r = state.getRandomly();
        long n = 1 + r.getLong(0, MAX_N);
        String fromClause = String.format(Locale.ROOT, "numbers(%d)", n);
        long lo = 0L;
        long hi = n - 1;

        int inSize = MIN_IN_SIZE + (int) Randomly.getNotCachedInteger(0, MAX_IN_SIZE - MIN_IN_SIZE + 1);
        List<Long> inList = generateInList(r, lo, hi, inSize);

        if (inList.size() >= 2 && Randomly.getBoolean()) {
            int dupIdx = (int) Randomly.getNotCachedInteger(0, inList.size());
            inList.add(inList.get(dupIdx));
        }

        String inListSql = renderInList(inList);
        String orChainSql = renderOrChain(inList);
        String andNotChainSql = renderAndNotChain(inList);

        boolean useNotIn = Randomly.getBoolean();
        String inQuery;
        String referenceQuery;
        if (useNotIn) {
            inQuery = "SELECT count() FROM " + fromClause + " WHERE number NOT IN (" + inListSql + ")";
            referenceQuery = "SELECT count() FROM " + fromClause + " WHERE " + andNotChainSql;
        } else {
            inQuery = "SELECT count() FROM " + fromClause + " WHERE number IN (" + inListSql + ")";
            referenceQuery = "SELECT count() FROM " + fromClause + " WHERE " + orChainSql;
        }

        List<String> inResult = ComparatorHelper.getResultSetFirstColumnAsString(inQuery, errors, state);
        List<String> referenceResult = ComparatorHelper.getResultSetFirstColumnAsString(referenceQuery, errors, state);

        ComparatorHelper.assumeResultSetsAreEqual(referenceResult, inResult, referenceQuery, List.of(inQuery), state);
    }

    private static List<Long> generateInList(Randomly r, long lo, long hi, int size) {

        List<Long> values = new ArrayList<>(size);
        HashSet<Long> seen = new HashSet<>();
        long span = Math.max(1, hi - lo);
        for (int i = 0; i < size; i++) {
            long pick;
            if (Randomly.getBoolean()) {
                pick = lo + r.getLong(0, span + 1);
            } else {

                pick = hi + 1 + r.getLong(0, 100);
            }

            if (!seen.add(pick) && Randomly.getBoolean()) {
                continue;
            }
            values.add(pick);
        }
        if (values.isEmpty()) {

            values.add(lo);
        }
        return values;
    }

    private static String renderInList(List<Long> values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(values.get(i));
        }
        return sb.toString();
    }

    private static String renderOrChain(List<Long> values) {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(" OR ");
            }
            sb.append("number = ").append(values.get(i));
        }
        sb.append(")");
        return sb.toString();
    }

    private static String renderAndNotChain(List<Long> values) {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(" AND ");
            }
            sb.append("number != ").append(values.get(i));
        }
        sb.append(")");
        return sb.toString();
    }
}
