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

/**
 * Window function equivalence oracle (workstream 19). Asserts well-known equivalences between
 * window expressions and their non-window counterparts:
 *
 * <ul>
 *   <li>{@code count(*) OVER ()} (any row) == {@code count(*)} (scalar)
 *   <li>{@code sum(x) OVER (ORDER BY id ROWS UNBOUNDED PRECEDING)} at last row ==
 *       {@code sum(x)} over the full table
 *   <li>{@code max(row_number() OVER (ORDER BY id)) == count(*)}
 * </ul>
 *
 * <p>Selects one identity per check; iterations that find no usable column shape short-circuit
 * with IgnoreMeException.
 */
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

        int identity = (int) Randomly.getNotCachedInteger(0, 3);
        String lhs;
        String rhs;
        switch (identity) {
        case 0:
            // count(*) OVER () returns count(*) on every row; we take the first one via LIMIT 1.
            lhs = "SELECT count() OVER () FROM " + fq + " LIMIT 1";
            rhs = "SELECT count() FROM " + fq;
            break;
        case 1: {
            List<ClickHouseColumn> numericCols = table.getColumns().stream()
                    .filter(c -> c.getType().getTypeTerm().unwrap().isNumeric())
                    .collect(Collectors.toList());
            if (numericCols.isEmpty()) {
                throw new IgnoreMeException();
            }
            ClickHouseColumn num = Randomly.fromList(numericCols);
            // max(row_number() OVER (ORDER BY num)) == count(*)
            lhs = "SELECT max(rn) FROM (SELECT row_number() OVER (ORDER BY " + num.getName() + ") AS rn FROM "
                    + fq + ")";
            rhs = "SELECT count() FROM " + fq;
            break;
        }
        default: {
            List<ClickHouseColumn> numericCols = table.getColumns().stream()
                    .filter(c -> c.getType().getTypeTerm().unwrap().isNumeric())
                    .collect(Collectors.toList());
            if (numericCols.isEmpty()) {
                throw new IgnoreMeException();
            }
            ClickHouseColumn num = Randomly.fromList(numericCols);
            // sum(num) OVER (ORDER BY id ROWS UNBOUNDED PRECEDING) at the last row == sum(num).
            lhs = "SELECT sum(" + num.getName() + ") OVER (ORDER BY " + num.getName()
                    + " ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) FROM " + fq + " ORDER BY " + num.getName()
                    + " DESC LIMIT 1";
            rhs = "SELECT sum(" + num.getName() + ") FROM " + fq;
            break;
        }
        }
        List<String> lhsResult = ComparatorHelper.getResultSetFirstColumnAsString(lhs, errors, state);
        List<String> rhsResult = ComparatorHelper.getResultSetFirstColumnAsString(rhs, errors, state);
        ComparatorHelper.assumeResultSetsAreEqual(lhsResult, rhsResult, lhs, Collections.singletonList(rhs), state,
                ComparatorHelper.ComparisonMode.ULP_TOLERANT_MULTISET);
    }
}
