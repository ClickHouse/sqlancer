package sqlancer;

import sqlancer.common.log.LoggableFactory;

public interface DatabaseProvider<G extends GlobalState<O, ?, C>, O extends DBMSSpecificOptions<?>, C extends SQLancerDBConnection> {

    Class<G> getGlobalStateClass();

    Class<O> getOptionClass();

    Reproducer<G> generateAndTestDatabase(G globalState) throws Exception;

    void generateAndTestDatabaseWithQueryPlanGuidance(G globalState) throws Exception;

    C createDatabase(G globalState) throws Exception;

    String getDBMSName();

    LoggableFactory getLoggableFactory();

    StateToReproduce getStateToReproduce(String databaseName);

}
