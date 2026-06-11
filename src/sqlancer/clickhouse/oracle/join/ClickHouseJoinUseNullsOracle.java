package sqlancer.clickhouse.oracle.join;

import java.sql.SQLException;
import java.util.List;
import java.util.stream.Collectors;

import sqlancer.ComparatorHelper;
import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.clickhouse.ClickHouseErrors;
import sqlancer.clickhouse.ClickHouseProvider.ClickHouseGlobalState;
import sqlancer.clickhouse.ClickHouseTypeFilters;
import sqlancer.clickhouse.ClickHouseVisitor;
import sqlancer.clickhouse.ast.ClickHouseAliasOperation;
import sqlancer.clickhouse.ast.ClickHouseColumnReference;
import sqlancer.clickhouse.ast.constant.ClickHouseCreateConstant;
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
 * SELECT ifNull(v0, 0) FROM (SELECT int_col AS v0 FROM ... JOIN ...)   -- identical text under both settings
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
 * <li>Row <i>cardinality</i> is setting-independent for a single join, so a size mismatch alone is also a bug
 * (several filed CH wrong-results are exactly "different row count with join_use_nulls=1").</li>
 * </ul>
 *
 * Restricted to integer-family columns: the 0-default reconciliation literal is type-uniform there, and the float
 * noise rule keeps Float/Decimal out of multiset comparisons anyway. The projected column is wrapped in a derived
 * table so the type change ({@code Int64} vs {@code Nullable(Int64)}) happens below the outer {@code ifNull} and no
 * generated expression ever computes over a NULL it did not expect.
 *
 * <p>
 * <b>Single-join restriction (soundness):</b> with two or more chained joins, a later join's ON clause can reference
 * a column that an earlier OUTER/ANTI join null-extended in the intermediate stream -- and that ON predicate then
 * legitimately evaluates differently under the two settings (fill {@code 0} can equi-match a genuine 0 key, fill
 * {@code NULL} never matches; an {@code IS NULL} conjunct flips outright), changing the cardinality. That is
 * documented behavior, not a bug, so multi-join iterations are skipped rather than asserted. With exactly one join,
 * the ON clause only sees the two base inputs, which no fill has touched. (Same dropped-column-reference family as
 * the JoinReorder oracle's #107073 gate.)
 *
 * <p>
 * What this buys over SEMR: the setting flips the analyzer's whole JOIN output-type derivation (result types, ON-key
 * wrapping, conversion-to-inner eligibility), which is the same JoinOrderOptimizer-adjacent surface as #107073 /
 * #106426, exercised here under a semantic toggle no result-preserving oracle may touch.
 *
 * <p>
 * ANY / SEMI join shapes are skipped for the same reason as in {@link ClickHouseJoinAlgorithmOracle}: which row they
 * pick is implementation-defined, so cross-execution comparisons false-positive on them.
 */
public class ClickHouseJoinUseNullsOracle extends ClickHouseTLPBase {

    public ClickHouseJoinUseNullsOracle(ClickHouseGlobalState state) {
        super(state);
        ClickHouseErrors.addSessionSettingsErrors(errors);
        ClickHouseJoinAlgorithmOracle.addResourceCapErrors(errors);
    }

    @Override
    public void check() throws SQLException {
        super.check();
        // Exactly one join, of a deterministic shape -- see the class javadoc's soundness notes.
        if (select.getJoinClauses().size() != 1
                || !ClickHouseJoinAlgorithmOracle.isAlgorithmDeterministic(select.getJoinClauses().get(0).getType())) {
            throw new IgnoreMeException();
        }
        select.setWhereClause(null);

        // Integer-family candidates from the full column set (base + joined table). The soundness
        // argument in the class javadoc holds for either side, so no per-join-type side-selection
        // is needed; not preferring a side keeps the pick uniform.
        List<ClickHouseColumnReference> intCols = columns.stream()
                .filter(c -> ClickHouseTypeFilters.isExactIntegerFamily(c.getColumn()))
                .collect(Collectors.toList());

        String inner;
        String outerProjection;
        if (intCols.isEmpty()) {
            // No integer column in scope: fall back to the cardinality-only invariant. Project a
            // constant so the inner SELECT never renders a column whose type flips with the setting.
            select.setFetchColumns(
                    List.of(new ClickHouseAliasOperation(ClickHouseCreateConstant.createInt32Constant(1), "v0")));
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
        String qDefaults = base + " SETTINGS join_use_nulls = 0, " + ClickHouseJoinAlgorithmOracle.CAPS;
        String qNulls = base + " SETTINGS join_use_nulls = 1, " + ClickHouseJoinAlgorithmOracle.CAPS;
        List<String> rowsDefaults = ComparatorHelper.getResultSetFirstColumnAsString(qDefaults, errors, state);
        List<String> rowsNulls = ComparatorHelper.getResultSetFirstColumnAsString(qNulls, errors, state);
        // MULTISET: the fill-mapping invariant is about value distribution, not value set -- a
        // wrong fill that collides with a genuine value (e.g. one 7 flipping to 0 while a 0 flips
        // to 7) keeps the SET equal and only the multiset catches it.
        ComparatorHelper.assumeResultSetsAreEqual(rowsDefaults, rowsNulls, qDefaults, List.of(qNulls), state,
                ComparatorHelper.ComparisonMode.MULTISET);
    }
}
