package sqlancer.common.oracle;

import java.util.ArrayList;
import java.util.List;

import sqlancer.GlobalState;
import sqlancer.IgnoreMeException;
import sqlancer.SQLancerDBConnection;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.Query;
import sqlancer.common.query.SQLancerResultSet;
import sqlancer.common.schema.AbstractRowValue;

public abstract class PivotedQuerySynthesisBase<S extends GlobalState<?, ?, C>, R extends AbstractRowValue<?, ?, ?>, E, C extends SQLancerDBConnection>
        implements TestOracle<S> {

    protected final ExpectedErrors errors = new ExpectedErrors();

    protected final List<E> rectifiedPredicates = new ArrayList<>();

    protected List<E> pivotRowExpression = new ArrayList<>();
    protected final S globalState;
    protected R pivotRow;

    protected PivotedQuerySynthesisBase(S globalState) {
        this.globalState = globalState;
    }

    @Override
    public final void check() throws Exception {
        rectifiedPredicates.clear();
        Query<C> pivotRowQuery = getRectifiedQuery();
        if (globalState.getOptions().logEachSelect()) {
            globalState.getLogger().writeCurrent(pivotRowQuery.getQueryString());
        }
        Query<C> isContainedQuery = getContainmentCheckQuery(pivotRowQuery);
        if (globalState.getOptions().logEachSelect()) {
            globalState.getLogger().writeCurrent(isContainedQuery.getQueryString());
        }
        globalState.getState().getLocalState().log(isContainedQuery.getQueryString());

        boolean pivotRowIsContained = containsRows(isContainedQuery);
        if (!pivotRowIsContained) {
            reportMissingPivotRow(pivotRowQuery);
        }
    }

    private boolean containsRows(Query<C> query) throws Exception {
        try (SQLancerResultSet result = query.executeAndGet(globalState)) {
            if (result == null) {
                throw new IgnoreMeException();
            }
            return !result.isClosed();
        }
    }

    protected void reportMissingPivotRow(Query<?> query) {
        globalState.getState().getLocalState().log("-- pivot row values:");
        String expectedPivotRowString = pivotRow.asStringGroupedByTables();
        globalState.getState().getLocalState().log(expectedPivotRowString);

        StringBuilder sb = new StringBuilder();
        if (!rectifiedPredicates.isEmpty()) {
            sb.append("--\n-- rectified predicates and their expected values:\n");
            for (E rectifiedPredicate : rectifiedPredicates) {
                sb.append("--");
                sb.append(getExpectedValues(rectifiedPredicate).replace("\n", "\n-- "));
            }
            sb.append("\n");
        }
        if (!pivotRowExpression.isEmpty()) {
            sb.append("-- pivot row expressions and their expected values:\n");
            for (E pivotRowExpression : pivotRowExpression) {
                sb.append("--");
                sb.append(getExpectedValues(pivotRowExpression).replace("\n", "\n--"));
                sb.append("\n");
            }
        }
        globalState.getState().getLocalState().log(sb.toString());
        throw new AssertionError(query);
    }

    protected abstract Query<C> getContainmentCheckQuery(Query<?> pivotRowQuery) throws Exception;

    protected abstract Query<C> getRectifiedQuery() throws Exception;

    protected abstract String getExpectedValues(E expr);

}
