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

/**
 * Differential oracle for {@code numbers()} when combined with relaxed-IN predicates.
 *
 * <p>
 * ClickHouse#103835: {@code KeyCondition::extractPlainRanges} builds primary-key ranges that {@code numbers},
 * {@code generate_series}, and {@code system.primes} consume as exact. When the underlying IN-set is *relaxed* --
 * deduplicated, or transformed via a partition function -- the constructed range can drift, producing wrong row counts.
 *
 * <p>
 * Invariant exercised:
 *
 * <pre>{@code
 * COUNT(SELECT * FROM numbers(N) WHERE number IN (a, b, c, ...))
 * ==
 * COUNT(SELECT * FROM numbers(N) WHERE number = a OR number = b OR number = c OR ...)
 * }</pre>
 *
 * The two forms must agree under any IN-list (including lists with duplicates -- which is the case the bug
 * preferentially hits because the duplicate triggers MergeTreeSetIndex's relaxation path). NOT IN is checked in the
 * same way against an explicit AND-of-not-equals.
 *
 * <p>
 * No schema is required -- the FROM clause is a table function, generated entirely from constants -- so this oracle is
 * orthogonal to the schema generator and runs successfully on an empty database. That makes it cheap to run alongside
 * the schema-driven oracles for amortised coverage of the table-function code path.
 *
 * <p>
 * Only {@code numbers(N)} is generated. {@code generate_series} is in the same bug-fix PR but is intentionally not
 * exercised here per project preference -- adding it would expand the surface but does not buy a distinct invariant.
 */
public class ClickHouseTableFunctionINOracle implements TestOracle<ClickHouseGlobalState> {

    /**
     * Source size for the table function. Small enough that the IN/OR comparison runs in milliseconds; large enough
     * that the IN list is a meaningful selectivity filter (most generated values fall inside [0, MAX_N)).
     */
    private static final long MAX_N = 10000L;

    /**
     * IN-list size. Bounded above so the explicit-OR form does not blow up into a multi-megabyte query string. Lower
     * bound 2 because a 1-element IN is just `=` and exercises a different code path.
     */
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
        // The bug shape is sensitive to *duplicate* IN-list entries because relaxation is what
        // triggers it. Inject a duplicate ~half the time -- the other half exercises the
        // deduplication-not-needed path so a regression in the simpler path also surfaces.
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
        // Mix of values inside [lo, hi] (selective) and outside (non-selective); the bug's range-
        // construction step is sensitive to whether the IN-list anchors line up with the source's
        // primary-key range, so both must be exercised.
        List<Long> values = new ArrayList<>(size);
        HashSet<Long> seen = new HashSet<>();
        long span = Math.max(1, hi - lo);
        for (int i = 0; i < size; i++) {
            long pick;
            if (Randomly.getBoolean()) {
                pick = lo + r.getLong(0, span + 1);
            } else {
                // out-of-range value -- exercises the empty-intersection range case
                pick = hi + 1 + r.getLong(0, 100);
            }
            // Allow natural duplicates by NOT deduplicating here; the explicit duplicate-injection
            // above adds *additional* duplicates. ~50% of natural duplicates are still pruned so
            // the IN list doesn't grow pathologically.
            if (!seen.add(pick) && Randomly.getBoolean()) {
                continue;
            }
            values.add(pick);
        }
        if (values.isEmpty()) {
            // Defensive: if everything got pruned, fall back to one in-range value so the query is
            // syntactically valid.
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
