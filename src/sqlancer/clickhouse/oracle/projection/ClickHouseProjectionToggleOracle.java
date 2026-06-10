package sqlancer.clickhouse.oracle.projection;

import java.sql.SQLException;
import java.util.List;
import java.util.stream.Collectors;

import com.clickhouse.data.ClickHouseDataType;

import sqlancer.ComparatorHelper;
import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.clickhouse.ClickHouseErrors;
import sqlancer.clickhouse.ClickHouseProvider.ClickHouseGlobalState;
import sqlancer.clickhouse.ClickHouseSchema;
import sqlancer.clickhouse.ClickHouseSchema.ClickHouseColumn;
import sqlancer.clickhouse.ClickHouseSchema.ClickHouseTable;
import sqlancer.clickhouse.ClickHouseToStringVisitor;
import sqlancer.clickhouse.ast.ClickHouseColumnReference;
import sqlancer.clickhouse.ast.ClickHouseExpression;
import sqlancer.clickhouse.gen.ClickHouseExpressionGenerator;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.ExpectedErrors;

/**
 * Companion toggle-oracle for Unit 2.2 (ALTER ADD/MATERIALIZE PROJECTION).
 *
 * <p>
 * Projection use is a pure read-time optimization: a projection-matching aggregate query must return the byte-for-byte
 * identical result whether the optimizer is allowed to serve it from a projection
 * ({@code optimize_use_projections = 1}) or forced to scan the base table ({@code optimize_use_projections = 0}). This
 * is the invariant behind the #103052 / #88350 projection wrong-result family: a partially-materialized or stale
 * projection that serves a different (wrong) result than the base scan.
 *
 * <p>
 * The oracle runs an aggregate query -- a {@code GROUP BY} over a scalar key projecting {@code count()} plus one
 * numeric aggregate, or a bare {@code count()} -- twice, once under each setting, and asserts the two result multisets
 * are equal. The invariant holds on ANY table (projection or not), so the oracle does not need to know which tables
 * carry projections; the ADD PROJECTION emission from Unit 2.2 makes the projection-serving path actually fire some
 * fraction of the time.
 *
 * <p>
 * Soundness note: the result is collapsed into a single concatenated string column per row so the standard first-column
 * comparator can diff full rows. The concatenation flows through both runs identically, so even pathological renderings
 * (NULL group keys, Float formatting) cannot produce a false positive -- only a genuine projection-vs-base divergence
 * can.
 */
public class ClickHouseProjectionToggleOracle implements TestOracle<ClickHouseGlobalState> {

    private final ClickHouseGlobalState state;
    private final ExpectedErrors errors = new ExpectedErrors();

    public ClickHouseProjectionToggleOracle(ClickHouseGlobalState state) {
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
        if (table.isView()) {
            throw new IgnoreMeException();
        }
        if (table.getColumns().isEmpty()) {
            throw new IgnoreMeException();
        }
        List<ClickHouseColumnReference> columns = table.getColumns().stream()
                .map(c -> c.asColumnReference(table.getName())).collect(Collectors.toList());

        ClickHouseExpressionGenerator gen = new ClickHouseExpressionGenerator(state).allowAggregates(false);
        gen.addColumns(columns);

        // Optional WHERE predicate -- exercises projection use under a filter (the shape that
        // matters for partial-aggregate projections).
        String whereClause = "";
        if (Randomly.getBoolean()) {
            ClickHouseExpression predicate = gen.generatePredicate();
            whereClause = " WHERE " + ClickHouseToStringVisitor.asString(predicate);
        }

        // Projection: either a bare count() (always valid) or a GROUP BY over a scalar key with
        // count() + one integer aggregate. The whole row is concatenated into a single String so a
        // first-column comparator can diff it.
        //
        // IMPORTANT -- only EXACT, order-insensitive aggregates and non-float group keys are used:
        // * sum over a Float column is order-sensitive: the projection-maintained partial sums and
        // the full-rescan sum round differently (the #99109 sum(Float64) GROUP BY family), which
        // is legitimate non-determinism, not a projection bug. Restrict sum to integer columns;
        // avg is dropped entirely (float division). min/max are exact on any numeric but we keep
        // the aggregate pool integer-only for simplicity. count() is always exact.
        // * a Float group KEY groups NaN / +0.0 / -0.0 non-deterministically across the two paths,
        // so float types are excluded from the group-key set as well.
        List<ClickHouseColumn> scalarKeys = table.getColumns().stream()
                .filter(c -> isScalarGroupKey(c.getType().getType())).collect(Collectors.toList());
        List<ClickHouseColumn> intCols = table.getColumns().stream().filter(c -> isExactInteger(c.getType().getType()))
                .collect(Collectors.toList());

        String projection;
        String groupBy = "";
        if (scalarKeys.isEmpty() || Randomly.getBooleanWithRatherLowProbability()) {
            projection = "toString(count())";
            if (!intCols.isEmpty()) {
                ClickHouseColumn agg = Randomly.fromList(intCols);
                String fn = Randomly.fromOptions("sum", "min", "max", "count");
                projection = "concat(toString(count()), '#', toString(" + fn + "(`" + agg.getName() + "`)))";
            }
        } else {
            ClickHouseColumn key = Randomly.fromList(scalarKeys);
            String keyName = "`" + key.getName() + "`";
            String aggExpr;
            if (intCols.isEmpty()) {
                aggExpr = "toString(count())";
            } else {
                ClickHouseColumn agg = Randomly.fromList(intCols);
                String fn = Randomly.fromOptions("sum", "min", "max", "count");
                aggExpr = "concat(toString(count()), '#', toString(" + fn + "(`" + agg.getName() + "`)))";
            }
            projection = "concat(toString(" + keyName + "), '@', " + aggExpr + ")";
            groupBy = " GROUP BY " + keyName;
        }

        String base = "SELECT " + projection + " FROM " + table.getName() + whereClause + groupBy;
        String withProjections = base + " SETTINGS optimize_use_projections = 1";
        String withoutProjections = base + " SETTINGS optimize_use_projections = 0";

        List<String> onRows = ComparatorHelper.getResultSetFirstColumnAsString(withProjections, errors, state);
        List<String> offRows = ComparatorHelper.getResultSetFirstColumnAsString(withoutProjections, errors, state);
        ComparatorHelper.assumeResultSetsAreEqual(onRows, offRows, withProjections, List.of(withoutProjections), state);
    }

    // A scalar type usable as a GROUP BY key with DETERMINISTIC grouping across the projection vs
    // full-scan paths. Float types are deliberately excluded (NaN / +0.0 / -0.0 group unstably).
    // getType() already unwraps Nullable / LowCardinality.
    private static boolean isScalarGroupKey(ClickHouseDataType t) {
        return isExactInteger(t) || t == ClickHouseDataType.String || t == ClickHouseDataType.FixedString
                || t == ClickHouseDataType.Date || t == ClickHouseDataType.Date32 || t == ClickHouseDataType.DateTime
                || t == ClickHouseDataType.DateTime64 || t == ClickHouseDataType.UUID;
    }

    // Integer types only: sum/min/max over these are exact and order-insensitive, so the two
    // settings must agree byte-for-byte. Float is excluded (order-sensitive rounding).
    private static boolean isExactInteger(ClickHouseDataType t) {
        switch (t) {
        case Int8:
        case Int16:
        case Int32:
        case Int64:
        case Int128:
        case Int256:
        case UInt8:
        case UInt16:
        case UInt32:
        case UInt64:
        case UInt128:
        case UInt256:
            return true;
        default:
            return false;
        }
    }

}
