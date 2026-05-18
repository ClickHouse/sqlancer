package sqlancer.clickhouse.oracle.tlp;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import sqlancer.ComparatorHelper;
import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.clickhouse.ClickHouseErrors;
import sqlancer.clickhouse.ClickHouseProvider.ClickHouseGlobalState;
import sqlancer.clickhouse.ClickHouseVisitor;
import sqlancer.clickhouse.ast.ClickHouseAggregate;
import sqlancer.clickhouse.ast.ClickHouseColumnReference;

/**
 * Combinator-identity oracle. For each {@code check()} picks one named identity from
 * {@link ClickHouseCombinatorIdentities#CATALOG} that is applicable to a randomly selected
 * {@code (aggregate, value-column)} pair, builds the combinator-suffixed form and its equivalent rewrite as full
 * SELECTs over the same table, and asserts the result multisets are equal.
 *
 * <p>
 * Equivalences are version-sensitive: the {@code -OrNull} family identities run with
 * {@code aggregate_functions_null_for_empty=0} to avoid the setting double-encoding the empty-NULL semantics on top of
 * the combinator. The setting per identity is declared in the catalog itself; the oracle reads it back here.
 * </p>
 *
 * <p>
 * Non-determinism guard from {@link ClickHouseTLPSetOpOracle} applies here too: a column or condition containing
 * {@code rand} / {@code now} / etc. flips per evaluation and would synthesise false-positive divergence between the two
 * equivalent forms. The deny list lives in {@link ClickHouseTLPSetOpOracle#NON_DETERMINISTIC_IDENTIFIERS} via a
 * package-private accessor.
 * </p>
 */
public class ClickHouseTLPCombinatorOracle extends ClickHouseTLPBase {

    private static final List<String> NON_DETERMINISTIC_IDENTIFIERS = List.of("rand(", "randconstant(", "rand64(",
            "now(", "now64(", "today(", "yesterday(", "generateuuidv4(", "randomstring(", "randomfixedstring(",
            "canonicalrand(");

    public ClickHouseTLPCombinatorOracle(ClickHouseGlobalState state) {
        super(state);
        ClickHouseErrors.addExpectedExpressionErrors(errors);
        ClickHouseErrors.addCombinatorErrors(errors);
    }

    @Override
    public void check() throws SQLException {
        super.check();

        // Pick a value column for the identity. Reuse the TLPBase-resolved columns; filter to types
        // the catalog has identities for. If none match, throw IgnoreMeException.
        List<ClickHouseColumnReference> usable = columns.stream()
                .filter(c -> ClickHouseCombinatorIdentities.isColumnSuitableAsValue(c.getColumn())).toList();
        if (usable.isEmpty()) {
            throw new IgnoreMeException();
        }
        ClickHouseColumnReference picked = Randomly.fromList(usable);
        ClickHouseAggregate.ClickHouseAggregateFunction agg = ClickHouseAggregate.ClickHouseAggregateFunction
                .getRandom();
        Optional<ClickHouseCombinatorIdentities.Identity> maybe = ClickHouseCombinatorIdentities
                .pickIdentity(state.getRandomly(), agg, picked.getColumn().getType());
        if (maybe.isEmpty()) {
            throw new IgnoreMeException();
        }
        ClickHouseCombinatorIdentities.Identity identity = maybe.get();

        // Build the value SQL (the column reference) and, for IF-bearing identities, a deterministic
        // condition derived from the column. Using a column-based condition keeps the SETTINGS-pinned
        // empty-fallback semantics meaningful (some rows pass, some don't).
        String xSql = ClickHouseVisitor.asString(picked);
        String condSql = identity.needsCondition() ? "(" + xSql + " IS NOT NULL)" : null;

        // Non-determinism guard: if the generated column reference's rendering happens to contain a
        // deny-listed function call (it shouldn't, but defensively), skip.
        String xLower = xSql.toLowerCase(Locale.ROOT);
        for (String tok : NON_DETERMINISTIC_IDENTIFIERS) {
            if (xLower.contains(tok)) {
                throw new IgnoreMeException();
            }
        }

        ClickHouseCombinatorIdentities.IdentityArgs args = new ClickHouseCombinatorIdentities.IdentityArgs(xSql,
                condSql);
        String combinatorExpr = identity.combinatorForm().apply(args);
        String rewriteExpr = identity.rewriteForm().apply(args);

        String baseFrom = renderFromBlock();
        String settingsSuffix = " SETTINGS " + identity.settings();
        String combinatorQuery = "SELECT " + combinatorExpr + " " + baseFrom + settingsSuffix;
        String rewriteQuery = "SELECT " + rewriteExpr + " " + baseFrom + settingsSuffix;

        if (state.getOptions().logEachSelect()) {
            state.getLogger().writeCurrent(combinatorQuery);
            state.getLogger().writeCurrent(rewriteQuery);
        }

        List<String> combinatorResult = ComparatorHelper.getResultSetFirstColumnAsString(combinatorQuery, errors,
                state);
        List<String> rewriteResult = ComparatorHelper.getResultSetFirstColumnAsString(rewriteQuery, errors, state);
        ComparatorHelper.assumeResultSetsAreEqual(combinatorResult, rewriteResult, combinatorQuery,
                Arrays.asList(rewriteQuery), state);
    }

    // Render the FROM/JOIN block of the base select as a single string, so the same scope can be
    // reused for both the combinator form and the rewrite form. The fetch columns from the base
    // select are deliberately discarded -- the identity oracle generates its own SELECT expressions.
    private String renderFromBlock() {
        // Mutate select to render only the FROM portion, then restore. Cheaper than cloning the AST.
        List<sqlancer.clickhouse.ast.ClickHouseExpression> savedFetch = select.getFetchColumns();
        try {
            // Render the full select with a dummy column so we can take the FROM... section.
            // ClickHouseVisitor.asString(select) emits "SELECT ... FROM tbl [JOINs] [WHERE]...";
            // strip everything before "FROM" to get the from-onwards portion.
            String rendered = ClickHouseVisitor.asString(select);
            int idx = rendered.indexOf(" FROM ");
            if (idx < 0) {
                throw new IgnoreMeException();
            }
            return rendered.substring(idx + 1); // keep "FROM ..."
        } finally {
            select.setFetchColumns(savedFetch);
        }
    }
}
