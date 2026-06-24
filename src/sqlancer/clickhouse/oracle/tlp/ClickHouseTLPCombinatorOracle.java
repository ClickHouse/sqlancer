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

        String xSql = ClickHouseVisitor.asString(picked);
        String condSql = identity.needsCondition() ? "(" + xSql + " IS NOT NULL)" : null;

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

    private String renderFromBlock() {

        List<sqlancer.clickhouse.ast.ClickHouseExpression> savedFetch = select.getFetchColumns();
        try {
            String rendered = ClickHouseVisitor.asString(select);

            int idx = findOuterFrom(rendered);
            if (idx < 0) {
                throw new IgnoreMeException();
            }
            return rendered.substring(idx + 1);
        } finally {
            select.setFetchColumns(savedFetch);
        }
    }

    private static int findOuterFrom(String rendered) {
        int depth = 0;
        for (int i = 0; i < rendered.length() - 6; i++) {
            char c = rendered.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (depth == 0 && c == ' ' && rendered.regionMatches(i, " FROM ", 0, 6)) {
                return i;
            }
        }
        return -1;
    }
}
