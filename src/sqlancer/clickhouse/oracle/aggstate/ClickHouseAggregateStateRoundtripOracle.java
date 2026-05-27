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

/**
 * AggregateFunction-state round-trip oracle. Asserts the algebraic identity:
 * <pre>
 *   finalizeAggregation(arrayReduce('sumState', groupArray(c))) == sum(c)
 * </pre>
 * for every numeric column {@code c} on a generated table. The identity holds for every
 * associative-commutative aggregate that has matching -State / -Merge / final form on the same
 * value type. ClickHouse's aggregate-state binary encoding is version-sensitive, so a divergence
 * across CH versions surfaces as an oracle failure here.
 *
 * <p>Workstream 5 of the 2026-05-27 coverage expansion plan. The oracle is registered but most
 * iterations will short-circuit (no AggregateFunction columns yet emitted by the type picker --
 * the type record exists but the picker doesn't yet construct it with a sensible -State arg
 * triple). The oracle is in place so when picker emission lands in a follow-up, no further oracle
 * plumbing is needed.
 */
public class ClickHouseAggregateStateRoundtripOracle implements TestOracle<ClickHouseGlobalState> {

    private final ClickHouseGlobalState state;
    private final ExpectedErrors errors;

    public ClickHouseAggregateStateRoundtripOracle(ClickHouseGlobalState state) {
        this.state = state;
        this.errors = ExpectedErrors.newErrors().with(ClickHouseErrors.getExpectedExpressionErrors()).build();
    }

    @Override
    public void check() throws SQLException {
        List<ClickHouseTable> tables = state.getSchema().getDatabaseTables().stream()
                .filter(t -> !t.isView()).collect(Collectors.toList());
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
        ClickHouseColumn col = Randomly.fromList(numericCols);
        String fqTable = state.getDatabaseName() + "." + table.getName();

        String aggName = Randomly.fromOptions("sum", "min", "max", "count");

        String lhsQuery = "SELECT " + aggName + "(" + col.getName() + ") FROM " + fqTable;
        String rhsQuery = "SELECT finalizeAggregation(arrayReduce('" + aggName + "State', groupArray("
                + col.getName() + "))) FROM " + fqTable;

        List<String> lhs = ComparatorHelper.getResultSetFirstColumnAsString(lhsQuery, errors, state);
        List<String> rhs = ComparatorHelper.getResultSetFirstColumnAsString(rhsQuery, errors, state);

        ComparatorHelper.assumeResultSetsAreEqual(lhs, rhs, lhsQuery, java.util.Collections.singletonList(rhsQuery),
                state, ComparatorHelper.ComparisonMode.ULP_TOLERANT_MULTISET);
    }
}
