package sqlancer.clickhouse.oracle.tlp;

import static java.lang.Math.min;
import static java.util.stream.IntStream.range;

import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import sqlancer.ComparatorHelper;
import sqlancer.Randomly;
import sqlancer.clickhouse.ClickHouseErrors;
import sqlancer.clickhouse.ClickHouseProvider.ClickHouseGlobalState;
import sqlancer.clickhouse.ClickHouseSchema;
import sqlancer.clickhouse.ClickHouseSchema.ClickHouseTable;
import sqlancer.clickhouse.ClickHouseVisitor;
import sqlancer.clickhouse.ast.ClickHouseColumnReference;
import sqlancer.clickhouse.ast.ClickHouseExpression;
import sqlancer.clickhouse.ast.ClickHouseExpression.ClickHouseJoin;
import sqlancer.clickhouse.ast.ClickHouseSelect;
import sqlancer.clickhouse.ast.ClickHouseTableReference;
import sqlancer.clickhouse.gen.ClickHouseExpressionGenerator;
import sqlancer.common.gen.ExpressionGenerator;
import sqlancer.common.oracle.TernaryLogicPartitioningOracleBase;
import sqlancer.common.oracle.TestOracle;

public class ClickHouseTLPBase extends TernaryLogicPartitioningOracleBase<ClickHouseExpression, ClickHouseGlobalState>
        implements TestOracle<ClickHouseGlobalState> {

    protected ClickHouseSchema schema;
    protected List<ClickHouseColumnReference> columns;
    protected ClickHouseExpressionGenerator gen;
    protected ClickHouseSelect select;

    public ClickHouseTLPBase(ClickHouseGlobalState state) {
        super(state);
        ClickHouseErrors.addExpectedExpressionErrors(errors);
    }

    @Override
    public void check() throws SQLException {
        gen = new ClickHouseExpressionGenerator(state);
        schema = state.getSchema();
        select = new ClickHouseSelect();
        List<ClickHouseTable> tables = schema.getRandomTableNonEmptyTables().getTables();
        ClickHouseTableReference table = new ClickHouseTableReference(
                tables.get((int) Randomly.getNotCachedInteger(0, tables.size())),
                Randomly.getBoolean() ? "left" : null);
        select.setFromClause(table);
        columns = table.getColumnReferences();

        if (state.getClickHouseOptions().testJoins && Randomly.getBoolean()) {
            List<ClickHouseJoin> joinStatements = gen.getRandomJoinClauses(table, tables);
            columns.addAll(joinStatements.stream().flatMap(j -> j.getRightTable().getColumnReferences().stream())
                    .collect(Collectors.toList()));
            select.setJoinClauses(joinStatements);
        }

        if (state.getClickHouseOptions().enableArrayJoin && select.getJoinClauses().isEmpty()
                && Randomly.getBooleanWithRatherLowProbability()) {
            List<ClickHouseColumnReference> arrayCols = table.getColumnReferences().stream().filter(
                    c -> c.getColumn().getType().getTypeTerm() instanceof sqlancer.clickhouse.ClickHouseType.Array)
                    .collect(Collectors.toList());
            if (!arrayCols.isEmpty()) {
                ClickHouseColumnReference arrayCol = Randomly.fromList(arrayCols);
                select.setArrayJoinExprs(List.of(arrayCol));
                select.setArrayJoinLeft(Randomly.getBoolean());
            }
        }
        gen.addColumns(columns);
        int small = Randomly.smallNumber();
        List<ClickHouseExpression> from = range(0, 1 + small)
                .mapToObj(i -> gen.generateExpressionWithColumns(columns, 5)).collect(Collectors.toList());

        if (Randomly.getBooleanWithRatherLowProbability()) {
            ClickHouseExpression composite = gen.generateCompositeAccess(columns);
            if (composite != null) {
                from = new java.util.ArrayList<>(from);
                from.add(composite);
            }
        }
        if (Randomly.getBooleanWithRatherLowProbability()) {
            ClickHouseExpression geo = gen.generateGeoCall(columns);
            if (geo != null) {
                from = new java.util.ArrayList<>(from);
                from.add(geo);
            }
        }

        if (Randomly.getBoolean() && Randomly.getBooleanWithRatherLowProbability()) {
            ClickHouseExpression hof = gen.generateHigherOrderArrayCall(columns);
            if (hof != null) {
                from = new java.util.ArrayList<>(from);
                from.add(hof);
            }
        }

        if (Randomly.getBooleanWithRatherLowProbability()) {
            ClickHouseExpression dt = gen.generateDateIntervalArith(columns);
            if (dt != null) {
                from = new java.util.ArrayList<>(from);
                from.add(dt);
            }
        }

        if (Randomly.getBooleanWithRatherLowProbability()) {
            ClickHouseExpression mif = gen.generateMultiIf(columns);
            if (mif != null) {
                from = new java.util.ArrayList<>(from);
                from.add(mif);
            }
        }

        if (Randomly.getBooleanWithRatherLowProbability()) {
            ClickHouseExpression sc = gen.generateStringCall(columns);
            if (sc != null) {
                from = new java.util.ArrayList<>(from);
                from.add(sc);
            }
        }

        if (Randomly.getBooleanWithRatherLowProbability()) {
            ClickHouseExpression dtx = gen.generateDateTransform(columns);
            if (dtx != null) {
                from = new java.util.ArrayList<>(from);
                from.add(dtx);
            }
        }

        if (Randomly.getBooleanWithRatherLowProbability()) {
            ClickHouseExpression sq = gen.generateScalarSubquery();
            if (sq != null) {
                from = new java.util.ArrayList<>(from);
                from.add(sq);
            }
        }
        select.setFetchColumns(from);
        select.setWhereClause(null);

        if (select.getJoinClauses().isEmpty() && !table.getTable().isView()
                && Randomly.getBooleanWithRatherLowProbability()) {
            select.setPrewhereClause(gen.generateExpressionWithColumns(columns, 3));
        }

        if (select.getJoinClauses().isEmpty() && table.getTable().supportsFinal()
                && Randomly.getBooleanWithRatherLowProbability()) {
            select.setFinal(true);
        }

        if (Randomly.getBooleanWithRatherLowProbability()) {
            int cteCount = 1 + (int) Randomly.getNotCachedInteger(0, 3);
            java.util.List<ClickHouseExpression> withList = new java.util.ArrayList<>();
            for (int i = 0; i < cteCount; i++) {
                ClickHouseExpression body = gen.generateExpressionWithColumns(columns, 3);
                String alias = "cte" + i;
                withList.add(new sqlancer.clickhouse.ast.ClickHouseAliasOperation(body, alias));
            }
            select.setWithClauses(withList);
        }
        initializeTernaryPredicateVariants();

        String query = ClickHouseVisitor.asString(select);
        ComparatorHelper.getResultSetFirstColumnAsString(query, errors, state);
    }

    List<ClickHouseExpression> generateFetchColumns(List<ClickHouseColumnReference> columns) {
        List<ClickHouseColumnReference> list = Randomly.extractNrRandomColumns(columns,
                min(1 + Randomly.smallNumber(), columns.size()));
        return list.stream().map(c -> (ClickHouseExpression) c).collect(Collectors.toList());
    }

    @Override
    protected ExpressionGenerator<ClickHouseExpression> getGen() {
        return gen;
    }

    protected boolean leadingResultsNonFinite(List<String> resultSet, List<String> secondResultSet) {
        return resultSet.stream().anyMatch(ClickHouseTLPBase::isNonFiniteToken)
                || secondResultSet.stream().anyMatch(ClickHouseTLPBase::isNonFiniteToken);
    }

    protected boolean projectionMayBeNonFinite(List<String> resultSet, List<String> secondResultSet,
            String originalQueryString) {
        if (leadingResultsNonFinite(resultSet, secondResultSet)) {
            return true;
        }
        List<ClickHouseExpression> fetch = select.getFetchColumns();
        return fetch.size() > 1 && probeAllColumnsNonFinite(originalQueryString, fetch);
    }

    private boolean probeAllColumnsNonFinite(String originalQueryString, List<ClickHouseExpression> fetch) {
        int fromIdx = findOuterFrom(originalQueryString);
        if (fromIdx < 0) {
            return false;
        }
        String fromTail = originalQueryString.substring(fromIdx + 1);
        StringBuilder cond = new StringBuilder();
        for (int i = 0; i < fetch.size(); i++) {
            if (i > 0) {
                cond.append(" OR ");
            }
            String e = ClickHouseVisitor.asString(fetch.get(i));
            cond.append("isNaN(toFloat64OrZero(toString(").append(e)
                    .append("))) OR isInfinite(toFloat64OrZero(toString(").append(e).append(")))");
        }
        String probe = "SELECT max(" + cond + ") " + fromTail;
        try {
            return ComparatorHelper.getResultSetFirstColumnAsString(probe, errors, state).stream()
                    .anyMatch("1"::equals);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isNonFiniteToken(String v) {
        if (v == null) {
            return false;
        }
        String s = v.trim();
        if (!s.isEmpty() && (s.charAt(0) == '+' || s.charAt(0) == '-')) {
            s = s.substring(1);
        }
        s = s.toLowerCase(Locale.ROOT);
        return s.equals("nan") || s.equals("inf") || s.equals("infinity");
    }

    private static int findOuterFrom(String rendered) {
        int depth = 0;
        for (int i = 0; i < rendered.length() - 6; i++) {
            char c = rendered.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (depth == 0 && c == ' ' && rendered.regionMatches(i, " FROM ", 0, 6)) {
                return i;
            }
        }
        return -1;
    }

}
