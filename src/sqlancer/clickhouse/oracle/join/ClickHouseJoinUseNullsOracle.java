package sqlancer.clickhouse.oracle.join;

import java.sql.SQLException;
import java.util.List;
import java.util.stream.Collectors;

import sqlancer.ComparatorHelper;
import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.clickhouse.ClickHouseErrors;
import sqlancer.clickhouse.ClickHouseProvider.ClickHouseGlobalState;
import sqlancer.clickhouse.ClickHouseVisitor;
import sqlancer.clickhouse.ast.ClickHouseAliasOperation;
import sqlancer.clickhouse.ast.ClickHouseColumnReference;
import sqlancer.clickhouse.ast.ClickHouseExpression.ClickHouseJoin;
import sqlancer.clickhouse.oracle.tlp.ClickHouseTLPBase;

/**
 * {@code join_use_nulls} differential oracle (settings-coverage plan section 3).
 *
 * <p>
 * {@code join_use_nulls} changes how OUTER-join non-matches are filled: type default values ({@code 0}, {@code ''},
 * epoch) when 0 -- the ClickHouse-native default -- and SQL-standard {@code NULL} when 1. The two results are
 * <i>legitimately</i> different, so the setting cannot go into blind SEMR; but the difference is fully predicted by a
 * default-to-NULL substitution on the null-extended side, which gives a strong metamorphic relation:
 *
 * <pre>
 * SELECT ifNull(c, 0) FROM (SELECT int_col AS c FROM ... JOIN ...)   -- identical text under both settings
 * </pre>
 *
 * must return the <b>same multiset</b> under {@code join_use_nulls = 0} and {@code = 1}:
 *
 * <ul>
 * <li>Non-matched fills: a non-Nullable integer column fills {@code 0} under {@code =0} and {@code NULL -> ifNull ->
 * 0} under {@code =1} -- mapped to the same value. A Nullable column fills {@code NULL} (the type's default) under
 * <i>both</i> settings -- mapped identically too.</li>
 * <li>Genuine matched values are setting-independent and pass through {@code ifNull} untouched (real NULLs in
 * Nullable data map to 0 under both settings equally).</li>
 * <li>Row <i>cardinality</i> is setting-independent for every join type, so a size mismatch alone is also a bug
 * (several filed CH wrong-results are exactly "different row count with join_use_nulls=1").</li>
 * </ul>
 *
 * Restricted to integer-family columns: the 0-default reconciliation literal is type-uniform there, and the float
 * noise rule keeps Float/Decimal out of multiset comparisons anyway. The projected column is wrapped in a derived
 * table so the type change ({@code Int64} vs {@code Nullable(Int64)}) happens below the outer {@code ifNull} and no
 * generated expression ever computes over a NULL it did not expect.
 *
 * <p>
 * What this buys over SEMR: the setting flips the analyzer's whole JOIN output-type derivation (result types, ON-key
 * wrapping, conversion-to-inner eligibility), which is the same JoinOrderOptimizer-adjacent surface as #107073 /
 * #106426, exercised here under a semantic toggle no other oracle may touch.
 *
 * <p>
 * ANY / SEMI join shapes are skipped for the same reason as in {@link ClickHouseJoinAlgorithmOracle}: which row they
 * pick is implementation-defined, so cross-plan comparisons false-positive on them.
 */
public class ClickHouseJoinUseNullsOracle extends ClickHouseTLPBase {

    // Same server-side caps as the JoinAlgorithm sweep: generated joins can explode and the
    // comparator materialises full result sets in Java.
    private static final String CAPS = "max_result_rows = 1000000, result_overflow_mode = 'throw', "
            + "max_bytes_in_join = 268435456, max_memory_usage = 1073741824";

    public ClickHouseJoinUseNullsOracle(ClickHouseGlobalState state) {
        super(state);
        ClickHouseErrors.addSessionSettingsErrors(errors);
        errors.add("Limit for result exceeded");
        errors.add("Limit for JOIN exceeded");
        errors.add("Memory limit");
        errors.add("memory limit exceeded");
        errors.add("MEMORY_LIMIT_EXCEEDED");
    }

    @Override
    public void check() throws SQLException {
        super.check();
        if (select.getJoinClauses().isEmpty()) {
            throw new IgnoreMeException();
        }
        for (ClickHouseJoin j : select.getJoinClauses()) {
            if (!isDeterministicUnderSettingToggle(j.getType())) {
                throw new IgnoreMeException();
            }
        }
        select.setWhereClause(null);

        // Integer-family candidates from the full column set (base + joined tables). The
        // soundness argument in the class javadoc holds for ANY side, so no per-join-type
        // side-selection is needed; preferring nothing keeps the pick uniform.
        List<ClickHouseColumnReference> intCols = columns.stream().filter(c -> {
            sqlancer.clickhouse.ClickHouseType term = c.getColumn().getType().getTypeTerm().unwrap();
            if (!term.isNumeric()) {
                return false;
            }
            com.clickhouse.data.ClickHouseDataType t = c.getColumn().getType().getType();
            return t != com.clickhouse.data.ClickHouseDataType.Float32
                    && t != com.clickhouse.data.ClickHouseDataType.Float64
                    && t != com.clickhouse.data.ClickHouseDataType.Decimal;
        }).collect(Collectors.toList());

        String inner;
        String outerProjection;
        if (intCols.isEmpty()) {
            // No integer column in scope: fall back to the cardinality-only invariant. Project a
            // constant so the inner SELECT never renders a column whose type flips with the setting.
            select.setFetchColumns(List.of(new ClickHouseAliasOperation(
                    sqlancer.clickhouse.ast.constant.ClickHouseCreateConstant.createInt32Constant(1), "v0")));
            inner = ClickHouseVisitor.asString(select);
            outerProjection = "count()";
        } else {
            ClickHouseColumnReference col = Randomly.fromList(intCols);
            select.setFetchColumns(List.of(new ClickHouseAliasOperation(col, "v0")));
            inner = ClickHouseVisitor.asString(select);
            // The full multiset already encodes the cardinality (list sizes must match), so one
            // projection covers both the fill-mapping and the row-count invariants.
            outerProjection = "ifNull(v0, 0)";
        }

        String base = "SELECT " + outerProjection + " FROM (" + inner + ")";
        String qDefaults = base + " SETTINGS join_use_nulls = 0, " + CAPS;
        String qNulls = base + " SETTINGS join_use_nulls = 1, " + CAPS;
        List<String> rowsDefaults = ComparatorHelper.getResultSetFirstColumnAsString(qDefaults, errors, state);
        List<String> rowsNulls = ComparatorHelper.getResultSetFirstColumnAsString(qNulls, errors, state);
        ComparatorHelper.assumeResultSetsAreEqual(rowsDefaults, rowsNulls, qDefaults, List.of(qNulls), state);
    }

    // ANY/SEMI pick an implementation-defined matching row; comparing them across two executions
    // (let alone two analyzer type-derivations) false-positives. Same set as the JoinAlgorithm
    // oracle's isAlgorithmDeterministic.
    private static boolean isDeterministicUnderSettingToggle(ClickHouseJoin.JoinType type) {
        switch (type) {
        case INNER:
        case CROSS:
        case LEFT_OUTER:
        case RIGHT_OUTER:
        case FULL_OUTER:
        case LEFT_ANTI:
        case RIGHT_ANTI:
            return true;
        default:
            return false;
        }
    }
}
