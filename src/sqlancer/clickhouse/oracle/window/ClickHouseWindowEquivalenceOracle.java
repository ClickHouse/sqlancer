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
 * Window function equivalence oracle (workstream 19). Asserts well-known equivalences between window expressions and
 * their non-window counterparts:
 *
 * <ul>
 * <li>{@code count(*) OVER ()} (any row) == {@code count(*)} (scalar)
 * <li>{@code sum(x) OVER (ORDER BY id ROWS UNBOUNDED PRECEDING)} at last row == {@code sum(x)} over the full table
 * <li>{@code max(row_number() OVER (ORDER BY id)) == count(*)}
 * </ul>
 *
 * <p>
 * Selects one identity per check; iterations that find no usable column shape short-circuit with IgnoreMeException.
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

        // Empty tables break the cumulative-window-vs-aggregate invariants below:
        // sum(x) OVER (...) returns 0 rows when the input is empty, but sum(x) without OVER
        // returns 1 row (with NULL). Skip empty tables -- the invariants only hold over a
        // non-empty input.
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
            // count(*) OVER () returns count(*) on every row; we take the first one via LIMIT 1.
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
            // max(row_number() OVER (ORDER BY num)) == count(*)
            lhs = "SELECT max(rn) FROM (SELECT row_number() OVER (ORDER BY " + num.getName() + ") AS rn FROM " + fq
                    + ")";
            rhs = "SELECT count() FROM " + fq;
            break;
        default:
            // Restrict the cumulative-sum identity to INTEGER columns: float arithmetic is
            // non-associative, so sum() over the full table (parallel) and sum() OVER
            // (cumulative, ORDER-BY order) can produce ULP-different float results. The 8.7h
            // run surfaced 12 WindowEquivalence reproducers all in this float-non-associativity
            // family. Same root cause as the AggregateStateRoundtripOracle fix on workstream 5.
            List<ClickHouseColumn> numericColsD = table.getColumns().stream().filter(c -> {
                com.clickhouse.data.ClickHouseDataType t = c.getType().getType();
                return t != com.clickhouse.data.ClickHouseDataType.Float32
                        && t != com.clickhouse.data.ClickHouseDataType.Float64
                        && t != com.clickhouse.data.ClickHouseDataType.Decimal
                        && c.getType().getTypeTerm().unwrap().isNumeric();
            }).collect(Collectors.toList());
            if (numericColsD.isEmpty()) {
                throw new IgnoreMeException();
            }
            ClickHouseColumn numD = Randomly.fromList(numericColsD);
            // sum(num) OVER (ORDER BY num RANGE UNBOUNDED PRECEDING) at a max-key row == sum(num).
            // RANGE, not ROWS: with duplicate values of num, ORDER BY num DESC LIMIT 1 picks an
            // arbitrary tied row, and a ROWS frame at that row excludes an arbitrary subset of its
            // tied peers -- the identity is unsound and false-positives (2026-06-10 convergence
            // run, database10). A RANGE frame includes all peers of the current key, so at any
            // max-key row the frame covers every row regardless of tie order.
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
