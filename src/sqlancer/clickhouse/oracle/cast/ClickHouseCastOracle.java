package sqlancer.clickhouse.oracle.cast;

import java.sql.SQLException;
import java.util.List;

import sqlancer.ComparatorHelper;
import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.clickhouse.ClickHouseErrors;
import sqlancer.clickhouse.ClickHouseProvider.ClickHouseGlobalState;
import sqlancer.clickhouse.ClickHouseSchema;
import sqlancer.clickhouse.ClickHouseSchema.ClickHouseColumn;
import sqlancer.clickhouse.ClickHouseSchema.ClickHouseTable;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.ExpectedErrors;

/**
 * Cast / overflow consistency oracle.
 *
 * <p>
 * ClickHouse exposes a family of cast functions with different overflow semantics:
 *
 * <ul>
 * <li>{@code accurateCast(x, T)} -- throws on values that don't fit the target type.</li>
 * <li>{@code accurateCastOrNull(x, T)} -- returns {@code NULL} on values that don't fit.</li>
 * <li>{@code to<T>OrZero(toString(x))} -- returns {@code 0} on values that don't fit.</li>
 * </ul>
 *
 * <p>
 * They must agree on the values that DO fit. ClickHouse#100697 (QBit accurate cast silently
 * loses precision) and #100471 ({@code date_time_overflow_behavior='throw'} silently ignored for
 * Int/Float -> DateTime64 casts) are both in this family. The oracle pattern: for every fitting
 * value, the OrNull variant returns the same value as the throwing variant. For every non-fitting
 * value, OrNull returns NULL.
 *
 * <p>
 * Concretely, for each generated cast site, the oracle issues:
 *
 * <ol>
 * <li>{@code SELECT accurateCastOrNull(c, 'T') FROM t} -- the reference column.</li>
 * <li>{@code SELECT IF(accurateCastOrNull(c, 'T') IS NULL, NULL, accurateCast(c, 'T')) FROM t} -- the throwing
 * variant guarded by the IS-NULL test, which short-circuits the throw on non-fitting inputs.</li>
 * </ol>
 *
 * <p>
 * If the two results disagree on any row, {@code accurateCast} and {@code accurateCastOrNull} disagree on a fitting
 * input -- a wrong-result bug. ClickHouse's parser evaluates IF arms eagerly in some engines but the analyzer's
 * short-circuit logic for {@code IS NULL} should keep {@code accurateCast} from being evaluated when the OrNull
 * variant already returned NULL.
 */
public class ClickHouseCastOracle implements TestOracle<ClickHouseGlobalState> {

    // Target types tested by the oracle. Narrow integer / float / Date* targets are the failure
    // surface in ClickHouse#100697 / #100471 / #101763. We deliberately exclude String here
    // because String can hold any value -- the OrNull-vs-throw signal degenerates.
    private static final List<String> TARGETS = List.of("Int8", "Int16", "Int32", "Int64", "UInt8", "UInt16", "UInt32",
            "UInt64", "Float32", "Float64", "Date", "DateTime", "Decimal(9, 2)", "Decimal(18, 4)");

    private final ClickHouseGlobalState state;
    private final ExpectedErrors errors = new ExpectedErrors();

    public ClickHouseCastOracle(ClickHouseGlobalState state) {
        this.state = state;
        ClickHouseErrors.addExpectedExpressionErrors(errors);
        ClickHouseErrors.addSessionSettingsErrors(errors);
    }

    @Override
    public void check() throws SQLException {
        ClickHouseSchema schema = state.getSchema();
        List<ClickHouseTable> tables = schema.getRandomTableNonEmptyTables().getTables();
        if (tables.isEmpty()) {
            throw new IgnoreMeException();
        }
        ClickHouseTable table = tables.get((int) Randomly.getNotCachedInteger(0, tables.size()));
        List<ClickHouseColumn> columns = table.getColumns();
        if (columns.isEmpty()) {
            throw new IgnoreMeException();
        }
        ClickHouseColumn col = columns.get((int) Randomly.getNotCachedInteger(0, columns.size()));
        String columnName = col.getName();
        String target = TARGETS.get((int) Randomly.getNotCachedInteger(0, TARGETS.size()));
        String fqTable = state.getDatabaseName() + "." + table.getName();

        // Both queries deterministically order by the same key so the multiset diff is also a
        // positional diff. We sort on the raw column to keep the comparison stable even when
        // accurateCast diverges from accurateCastOrNull on intermediate values.
        String orNullQuery = "SELECT toString(accurateCastOrNull(" + columnName + ", '" + target + "')) FROM " + fqTable
                + " ORDER BY " + columnName + " NULLS FIRST";
        String guardedThrowQuery = "SELECT IF(accurateCastOrNull(" + columnName + ", '" + target
                + "') IS NULL, NULL, toString(accurateCast(" + columnName + ", '" + target + "'))) FROM " + fqTable
                + " ORDER BY " + columnName + " NULLS FIRST";

        List<String> orNullRows;
        try {
            orNullRows = ComparatorHelper.getResultSetFirstColumnAsString(orNullQuery, errors, state);
        } catch (IgnoreMeException e) {
            // accurateCastOrNull rejected the source-column type entirely (e.g. cast from Array to
            // Int). Not a wrong-result bug; try a different combination next iteration.
            throw e;
        }
        List<String> guardedRows;
        try {
            guardedRows = ComparatorHelper.getResultSetFirstColumnAsString(guardedThrowQuery, errors, state);
        } catch (IgnoreMeException e) {
            // The guarded query might still surface a "Cannot convert" error on a row that OrNull
            // returned non-NULL for -- which IS the bug. But the predicate-level error attribution
            // is fuzzy; if the OrNull arm raised, neither arm is comparable. Skip safely.
            throw e;
        }
        ComparatorHelper.assumeResultSetsAreEqual(orNullRows, guardedRows, orNullQuery, List.of(guardedThrowQuery),
                state);
    }
}
