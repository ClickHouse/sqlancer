package sqlancer;

import sqlancer.common.query.Query;
import sqlancer.common.schema.AbstractSchema;

public abstract class SQLGlobalState<O extends DBMSSpecificOptions<?>, S extends AbstractSchema<?, ?>>
        extends GlobalState<O, S, SQLConnection> {

    @Override
    protected void executeEpilogue(Query<?> q, boolean success, ExecutionTimer timer) throws Exception {
        boolean logExecutionTime = getOptions().logExecutionTime();
        if (success && getOptions().printSucceedingStatements()) {
            System.out.println(q.getQueryString());
        }
        if (logExecutionTime) {
            getLogger().writeCurrent(" -- " + timer.end().asString());
        }
        if (q.couldAffectSchema()) {
            updateSchema();
        }
    }
}
