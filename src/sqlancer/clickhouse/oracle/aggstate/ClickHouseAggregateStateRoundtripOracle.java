package sqlancer.clickhouse.oracle.aggstate;

import java.sql.SQLException;
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

public class ClickHouseAggregateStateRoundtripOracle implements TestOracle<ClickHouseGlobalState> {

    private final ClickHouseGlobalState state;
    private final ExpectedErrors errors;

    public ClickHouseAggregateStateRoundtripOracle(ClickHouseGlobalState state) {
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
        List<ClickHouseColumn> numericCols = table.getColumns().stream().filter(c -> {
            sqlancer.clickhouse.ClickHouseType term = c.getType().getTypeTerm().unwrap();
            return term.isNumeric();
        }).collect(Collectors.toList());
        if (numericCols.isEmpty()) {
            throw new IgnoreMeException();
        }

        String aggName = Randomly.fromOptions("min", "max", "count", "sum");
        if (aggName.equals("sum")) {
            List<ClickHouseColumn> intCols = numericCols.stream()
                    .filter(sqlancer.clickhouse.ClickHouseTypeFilters::isExactIntegerFamily)
                    .collect(Collectors.toList());
            if (intCols.isEmpty()) {
                throw new IgnoreMeException();
            }
            numericCols = intCols;
        }
        ClickHouseColumn col = Randomly.fromList(numericCols);
        String fqTable = state.getDatabaseName() + "." + table.getName();

        String lhsQuery = "SELECT " + aggName + "(" + col.getName() + ") FROM " + fqTable;
        String rhsQuery = "SELECT finalizeAggregation(arrayReduce('" + aggName + "State', groupArray(" + col.getName()
                + "))) FROM " + fqTable;

        List<String> lhs = ComparatorHelper.getResultSetFirstColumnAsString(lhsQuery, errors, state);
        List<String> rhs = ComparatorHelper.getResultSetFirstColumnAsString(rhsQuery, errors, state);

        if (lhs.isEmpty() || lhs.contains(null) || rhs.contains(null)) {
            throw new IgnoreMeException();
        }

        ComparatorHelper.assumeResultSetsAreEqual(lhs, rhs, lhsQuery, java.util.Collections.singletonList(rhsQuery),
                state, ComparatorHelper.ComparisonMode.ULP_TOLERANT_MULTISET);
    }
}
