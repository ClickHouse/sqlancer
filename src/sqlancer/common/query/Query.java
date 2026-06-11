package sqlancer.common.query;

import sqlancer.GlobalState;
import sqlancer.SQLancerDBConnection;
import sqlancer.common.log.Loggable;

public abstract class Query<C extends SQLancerDBConnection> implements Loggable {
    private static final long serialVersionUID = 1L;

    public abstract String getQueryString();

    public abstract String getUnterminatedQueryString();

    public abstract boolean couldAffectSchema();

    public abstract <G extends GlobalState<?, ?, C>> boolean execute(G globalState, String... fills) throws Exception;

    public abstract ExpectedErrors getExpectedErrors();

    @Override
    public String toString() {
        return getQueryString();
    }

    public <G extends GlobalState<?, ?, C>> SQLancerResultSet executeAndGet(G globalState, String... fills)
            throws Exception {
        throw new AssertionError();
    }

    public <G extends GlobalState<?, ?, C>> boolean executeLogged(G globalState) throws Exception {
        logQueryString(globalState);
        return execute(globalState);
    }

    public <G extends GlobalState<?, ?, C>> SQLancerResultSet executeAndGetLogged(G globalState) throws Exception {
        logQueryString(globalState);
        return executeAndGet(globalState);
    }

    private <G extends GlobalState<?, ?, C>> void logQueryString(G globalState) {
        if (globalState.getOptions().logEachSelect()) {
            globalState.getLogger().writeCurrent(getQueryString());
        }
    }

}
