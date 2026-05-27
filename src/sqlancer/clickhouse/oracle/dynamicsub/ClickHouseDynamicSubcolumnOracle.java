package sqlancer.clickhouse.oracle.dynamicsub;

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
import sqlancer.clickhouse.ClickHouseType;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.ExpectedErrors;

/**
 * Dynamic subcolumn equivalence oracle (workstream 6 of the plan). Asserts:
 * <pre>
 *   dynamicElement(d, 'Int32')  ==  CAST(d AS Nullable(Int32))
 * </pre>
 * on rows where the dynamic value's runtime type can be Int32. Same shape for Int64 / String /
 * Float64. Iterations that find no Dynamic column short-circuit.
 */
public class ClickHouseDynamicSubcolumnOracle implements TestOracle<ClickHouseGlobalState> {

    private final ClickHouseGlobalState state;
    private final ExpectedErrors errors;

    public ClickHouseDynamicSubcolumnOracle(ClickHouseGlobalState state) {
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
        List<ClickHouseColumn> dynCols = table.getColumns().stream()
                .filter(c -> c.getType().getTypeTerm().unwrap() instanceof ClickHouseType.Dynamic)
                .collect(Collectors.toList());
        if (dynCols.isEmpty()) {
            throw new IgnoreMeException();
        }
        ClickHouseColumn col = Randomly.fromList(dynCols);
        String targetType = Randomly.fromOptions("Int32", "Int64", "String", "Float64");
        String fq = state.getDatabaseName() + "." + table.getName();

        String lhs = "SELECT dynamicElement(" + col.getName() + ", '" + targetType + "') FROM " + fq;
        String rhs = "SELECT CAST(" + col.getName() + " AS Nullable(" + targetType + ")) FROM " + fq;

        List<String> lhsResult = ComparatorHelper.getResultSetFirstColumnAsString(lhs, errors, state);
        List<String> rhsResult = ComparatorHelper.getResultSetFirstColumnAsString(rhs, errors, state);
        ComparatorHelper.assumeResultSetsAreEqual(lhsResult, rhsResult, lhs, Collections.singletonList(rhs), state,
                ComparatorHelper.ComparisonMode.MULTISET);
    }
}
