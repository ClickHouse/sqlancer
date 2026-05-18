package sqlancer.clickhouse.oracle.setop_limit;

import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import sqlancer.ComparatorHelper;
import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.clickhouse.ClickHouseErrors;
import sqlancer.clickhouse.ClickHouseProvider.ClickHouseGlobalState;
import sqlancer.clickhouse.ClickHouseVisitor;
import sqlancer.clickhouse.ast.ClickHouseColumnReference;
import sqlancer.clickhouse.ast.ClickHouseExpression;
import sqlancer.clickhouse.ast.ClickHouseSelect;
import sqlancer.clickhouse.oracle.tlp.ClickHouseTLPBase;

/**
 * Catches regressions in the interaction between
 * <ol>
 * <li>{@code UNION ALL} of subqueries that carry their own {@code ORDER BY}, and</li>
 * <li>an outer post-filter that depends on row ordering -- {@code LIMIT N BY key} or {@code DISTINCT}.</li>
 * </ol>
 *
 * <p>
 * Canonical reproducer (ClickHouse#103231):
 *
 * <pre>{@code
 * SELECT g, x
 * FROM (
 *     (SELECT number % 2 AS g, number AS x FROM numbers(2) ORDER BY g, x)
 *     UNION ALL
 *     (SELECT number % 2 AS g, number + 100 AS x FROM numbers(2) ORDER BY g, x)
 * )
 * LIMIT 1 BY g;
 * }</pre>
 *
 * Should return 2 rows; the bug returned 4. The bug is in how {@code LIMIT BY} consumes the union pipeline: an
 * inside-the-subquery sort changes how groups are batched at the union join point, and the buggy code path skips the
 * group-skip step that {@code LIMIT BY} relies on.
 *
 * <p>
 * Invariant exercised here: removing the inner {@code ORDER BY} clauses must not change the row count of the outer
 * {@code LIMIT BY} or {@code DISTINCT} result. (For {@code LIMIT BY}, the distinct-key set is preserved; for
 * {@code DISTINCT}, the entire row set is preserved. Internal ordering only changes which specific row of a duplicate
 * group is kept, not whether one is kept.)
 *
 * <p>
 * We use the {@code WHERE p} / {@code WHERE NOT p} predicate split inherited from {@link ClickHouseTLPBase} to obtain
 * two distinct subqueries cheaply -- both project the same single grouping column and together cover the table modulo
 * NULLs, which guarantees a non-trivial union without needing to invent a second table.
 */
public class ClickHouseSortedUnionLimitByOracle extends ClickHouseTLPBase {

    /**
     * Outer post-filter. The bug class fires on both {@code LIMIT BY} and {@code DISTINCT}; we pick one per check rather
     * than running both (cost halves; the per-mode bug-finding probability is similar).
     */
    private enum OuterMode {
        LIMIT_BY, DISTINCT
    }

    public ClickHouseSortedUnionLimitByOracle(ClickHouseGlobalState state) {
        super(state);
        ClickHouseErrors.addExpectedExpressionErrors(errors);
        errors.addAll(ClickHouseErrors.getSetOpErrors());
    }

    @Override
    public void check() throws SQLException {
        super.check();
        // The TLP base produces a select with a single random table reference. The grouping
        // column for LIMIT BY / projection for DISTINCT must be a single, simple column reference
        // -- aggregates collapse the row set in a way that breaks the invariant, and a complex
        // expression (function calls, casts) confuses LIMIT BY column resolution at the analyzer.
        ClickHouseColumnReference groupingCol = pickSimpleColumn();
        if (groupingCol == null) {
            throw new IgnoreMeException();
        }
        // Joins compose poorly with a single-column projection inside the union (the union arms
        // would each carry a Cartesian-expanded row set) and would also expand the bug-attribution
        // surface beyond what this oracle is positioned to catch. Skip when joins are present.
        if (!select.getJoinClauses().isEmpty()) {
            throw new IgnoreMeException();
        }

        OuterMode mode = Randomly.fromOptions(OuterMode.values());

        // Build the four subquery strings: two predicate variants × {plain, sorted}.
        String leftPlain = renderArm(groupingCol, predicate, false);
        String rightPlain = renderArm(groupingCol, negatedPredicate, false);
        String leftSorted = renderArm(groupingCol, predicate, true);
        String rightSorted = renderArm(groupingCol, negatedPredicate, true);

        String colName = renderColumnName(groupingCol);
        String plainQuery = wrapOuter(leftPlain, rightPlain, colName, mode);
        String sortedQuery = wrapOuter(leftSorted, rightSorted, colName, mode);

        List<String> plainRows = ComparatorHelper.getResultSetFirstColumnAsString(plainQuery, errors, state);
        List<String> sortedRows = ComparatorHelper.getResultSetFirstColumnAsString(sortedQuery, errors, state);

        state.getState().getLocalState()
                .log(String.format("sorted-union-limit-by: mode=%s, plain_size=%d, sorted_size=%d", mode,
                        plainRows.size(), sortedRows.size()));

        // For both LIMIT BY and DISTINCT, the distinct value set on the projected column must be
        // equal between the two forms. Multiset equality is too strong for LIMIT BY because
        // duplicates within a group are intentionally collapsed by the outer; set equality is the
        // correct invariant here.
        Set<String> plainSet = new HashSet<>(plainRows);
        Set<String> sortedSet = new HashSet<>(sortedRows);
        if (!plainSet.equals(sortedSet)) {
            throw new AssertionError(String.format(Locale.ROOT,
                    "Sorted vs plain UNION ALL diverged under outer %s:%n  plain:  %s%n  sorted: %s%n"
                            + "  plain rows (%d): %s%n  sorted rows (%d): %s",
                    mode, plainQuery, sortedQuery, plainRows.size(), plainRows, sortedRows.size(), sortedRows));
        }
        // The cardinality check is the stronger invariant for LIMIT BY -- in the #103231 bug the
        // sorted form *added* rows (returned 4 instead of 2). Set equality alone would not catch a
        // pure duplication bug if the duplicated values were already present in the set; assert
        // row counts match as well so a "duplicate-row" failure surfaces independently.
        if (plainRows.size() != sortedRows.size()) {
            throw new AssertionError(String.format(Locale.ROOT,
                    "Sorted vs plain UNION ALL row count mismatch under outer %s: plain=%d, sorted=%d%n  plain:  %s%n  sorted: %s",
                    mode, plainRows.size(), sortedRows.size(), plainQuery, sortedQuery));
        }
    }

    private ClickHouseColumnReference pickSimpleColumn() {
        // Prefer a column from the SELECT's fetchColumns if any of them are bare column references;
        // otherwise fall back to the table's columns. Either way the projection must be a single
        // simple identifier so the outer LIMIT BY / DISTINCT analyses it correctly.
        for (ClickHouseExpression expr : select.getFetchColumns()) {
            if (expr instanceof ClickHouseColumnReference) {
                return (ClickHouseColumnReference) expr;
            }
        }
        if (columns == null || columns.isEmpty()) {
            return null;
        }
        return columns.get((int) Randomly.getNotCachedInteger(0, columns.size()));
    }

    private String renderArm(ClickHouseColumnReference groupingCol, ClickHouseExpression where, boolean sorted) {
        // Mutate `select` in place, render, then restore. The base class hands us a select object
        // with the table, FROM, possible PREWHERE, FINAL flag, etc.; we reuse all of that and only
        // change the projection + WHERE + ORDER BY per arm.
        List<ClickHouseExpression> savedFetch = select.getFetchColumns();
        ClickHouseExpression savedWhere = select.getWhereClause();
        List<ClickHouseExpression> savedOrderBy = select.getOrderByClauses();
        ClickHouseSelect.SelectType savedType = select.getFromOptions();
        try {
            select.setFetchColumns(List.of((ClickHouseExpression) groupingCol));
            select.setWhereClause(where);
            select.setFromOptions(ClickHouseSelect.SelectType.ALL);
            if (sorted) {
                select.setOrderByClauses(List.of((ClickHouseExpression) groupingCol));
            } else {
                select.setOrderByClauses(List.of());
            }
            return ClickHouseVisitor.asString(select);
        } finally {
            select.setFetchColumns(savedFetch);
            select.setWhereClause(savedWhere);
            select.setOrderByClauses(savedOrderBy);
            select.setFromOptions(savedType);
        }
    }

    private String wrapOuter(String leftArm, String rightArm, String colName, OuterMode mode) {
        // Each arm is parenthesised so internal ORDER BY parses as part of the arm rather than
        // attaching to the union. The outer SELECT projects only the grouping column for parity
        // with what each arm produces (single-column union arms collapse to the same column type).
        String unionBlock = "((" + leftArm + ") UNION ALL (" + rightArm + "))";
        switch (mode) {
        case LIMIT_BY:
            return "SELECT " + colName + " FROM " + unionBlock + " LIMIT 1 BY " + colName;
        case DISTINCT:
            return "SELECT DISTINCT " + colName + " FROM " + unionBlock;
        default:
            throw new AssertionError(mode);
        }
    }

    private String renderColumnName(ClickHouseColumnReference ref) {
        // Outer LIMIT BY / SELECT references the column by its short name; the union output's
        // column inherits the projection alias (or column name) from the arms. Without table
        // qualification, since the union output isn't a table.
        return ref.getColumn().getName();
    }

}
