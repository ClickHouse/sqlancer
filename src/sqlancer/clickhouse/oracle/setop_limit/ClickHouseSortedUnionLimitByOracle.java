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

public class ClickHouseSortedUnionLimitByOracle extends ClickHouseTLPBase {

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

        ClickHouseColumnReference groupingCol = pickSimpleColumn();
        if (groupingCol == null) {
            throw new IgnoreMeException();
        }

        if (!select.getJoinClauses().isEmpty()) {
            throw new IgnoreMeException();
        }

        OuterMode mode = Randomly.fromOptions(OuterMode.values());

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

        Set<String> plainSet = new HashSet<>(plainRows);
        Set<String> sortedSet = new HashSet<>(sortedRows);
        if (!plainSet.equals(sortedSet)) {
            throw new AssertionError(String.format(Locale.ROOT,
                    "Sorted vs plain UNION ALL diverged under outer %s:%n  plain:  %s%n  sorted: %s%n"
                            + "  plain rows (%d): %s%n  sorted rows (%d): %s",
                    mode, plainQuery, sortedQuery, plainRows.size(), plainRows, sortedRows.size(), sortedRows));
        }

        if (plainRows.size() != sortedRows.size()) {
            throw new AssertionError(String.format(Locale.ROOT,
                    "Sorted vs plain UNION ALL row count mismatch under outer %s: plain=%d, sorted=%d%n  plain:  %s%n  sorted: %s",
                    mode, plainRows.size(), sortedRows.size(), plainQuery, sortedQuery));
        }
    }

    private ClickHouseColumnReference pickSimpleColumn() {

        for (ClickHouseExpression expr : select.getFetchColumns()) {
            if (expr instanceof ClickHouseColumnReference && !isFloat((ClickHouseColumnReference) expr)) {
                return (ClickHouseColumnReference) expr;
            }
        }
        if (columns == null || columns.isEmpty()) {
            return null;
        }
        List<ClickHouseColumnReference> nonFloat = new java.util.ArrayList<>();
        for (ClickHouseColumnReference c : columns) {
            if (!isFloat(c)) {
                nonFloat.add(c);
            }
        }
        if (nonFloat.isEmpty()) {
            return null;
        }
        return nonFloat.get((int) Randomly.getNotCachedInteger(0, nonFloat.size()));
    }

    private static boolean isFloat(ClickHouseColumnReference ref) {
        com.clickhouse.data.ClickHouseDataType t = ref.getColumn().getType().getType();
        return t == com.clickhouse.data.ClickHouseDataType.Float32
                || t == com.clickhouse.data.ClickHouseDataType.Float64;
    }

    private String renderArm(ClickHouseColumnReference groupingCol, ClickHouseExpression where, boolean sorted) {

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

        return ref.getColumn().getName();
    }

}
