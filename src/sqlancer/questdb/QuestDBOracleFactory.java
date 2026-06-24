package sqlancer.questdb;

import java.sql.SQLException;

import sqlancer.OracleFactory;
import sqlancer.common.oracle.TestOracle;
import sqlancer.questdb.test.QuestDBQueryPartitioningWhereTester;

public enum QuestDBOracleFactory implements OracleFactory<QuestDBProvider.QuestDBGlobalState> {

    WHERE {
        @Override
        public TestOracle<QuestDBProvider.QuestDBGlobalState> create(QuestDBProvider.QuestDBGlobalState globalState)
                throws SQLException {
            return new QuestDBQueryPartitioningWhereTester(globalState);
        }
    }
}
