package sqlancer.clickhouse.oracle.window;

import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
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

public class ClickHouseWindowEquivalenceOracle implements TestOracle<ClickHouseGlobalState> {

    private final ClickHouseGlobalState state;
    private final ExpectedErrors errors;

    public ClickHouseWindowEquivalenceOracle(ClickHouseGlobalState state) {
        this.state = state;
        this.errors = ExpectedErrors.newErrors().with(ClickHouseErrors.getExpectedExpressionErrors()).build();
    }

    @Override
    public void check() throws SQLException {
        List<ClickHouseTable> tables = state.getSchema().getDatabaseTables().stream().filter(t -> !t.isView())
                .collect(Collectors.toList());
        if (tables.isEmpty()) {
            throw new IgnoreMeException();
        }
        ClickHouseTable table = Randomly.fromList(tables);
        String fq = state.getDatabaseName() + "." + table.getName();

        try (java.sql.Statement s = state.getConnection().createStatement();
                java.sql.ResultSet rs = s.executeQuery("SELECT count() FROM " + fq)) {
            if (rs.next() && rs.getLong(1) == 0) {
                throw new IgnoreMeException();
            }
        } catch (SQLException e) {
            if (sqlancer.clickhouse.ClickHouseErrors.isToleratedException(e)) {
                throw new IgnoreMeException();
            }
            throw e;
        }

        int identity = (int) Randomly.getNotCachedInteger(0, 3);
        String lhs;
        String rhs;
        switch (identity) {
        case 0:

            lhs = "SELECT count() OVER () FROM " + fq + " LIMIT 1";
            rhs = "SELECT count() FROM " + fq;
            break;
        case 1:
            List<ClickHouseColumn> numericCols = table.getColumns().stream()
                    .filter(c -> c.getType().getTypeTerm().unwrap().isNumeric()).collect(Collectors.toList());
            if (numericCols.isEmpty()) {
                throw new IgnoreMeException();
            }
            ClickHouseColumn num = Randomly.fromList(numericCols);

            lhs = "SELECT max(rn) FROM (SELECT row_number() OVER (ORDER BY " + num.getName() + ") AS rn FROM " + fq
                    + ")";
            rhs = "SELECT count() FROM " + fq;
            break;
        default:

            List<ClickHouseColumn> numericColsD = table.getColumns().stream()
                    .filter(sqlancer.clickhouse.ClickHouseTypeFilters::isExactIntegerFamily)
                    .collect(Collectors.toList());
            if (numericColsD.isEmpty()) {
                throw new IgnoreMeException();
            }
            ClickHouseColumn numD = Randomly.fromList(numericColsD);

            lhs = "SELECT sum(" + numD.getName() + ") OVER (ORDER BY " + numD.getName()
                    + " RANGE BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) FROM " + fq + " ORDER BY " + numD.getName()
                    + " DESC LIMIT 1";
            rhs = "SELECT sum(" + numD.getName() + ") FROM " + fq;
            break;
        }
        List<String> lhsResult = ComparatorHelper.getResultSetFirstColumnAsString(lhs, errors, state);
        List<String> rhsResult = ComparatorHelper.getResultSetFirstColumnAsString(rhs, errors, state);
        ComparatorHelper.assumeResultSetsAreEqual(lhsResult, rhsResult, lhs, Collections.singletonList(rhs), state,
                ComparatorHelper.ComparisonMode.ULP_TOLERANT_MULTISET);
    }
}
