package sqlancer.clickhouse.oracle.semr;

import java.sql.SQLException;
import java.util.List;

import sqlancer.ComparatorHelper;
import sqlancer.clickhouse.ClickHouseErrors;
import sqlancer.clickhouse.ClickHouseProvider.ClickHouseGlobalState;
import sqlancer.clickhouse.ClickHouseSessionSettings;
import sqlancer.clickhouse.ClickHouseSessionSettings.SemrCandidate;
import sqlancer.clickhouse.ClickHouseVisitor;
import sqlancer.clickhouse.oracle.tlp.ClickHouseTLPBase;

public class ClickHouseSEMROracle extends ClickHouseTLPBase {

    public ClickHouseSEMROracle(ClickHouseGlobalState state) {
        super(state);
        ClickHouseErrors.addSessionSettingsErrors(errors);
    }

    @Override
    public void check() throws SQLException {
        super.check();

        select.setWhereClause(null);
        SemrCandidate candidate = ClickHouseSessionSettings.pickSemrCandidate(state.getRandomly());
        String baseQuery = ClickHouseVisitor.asString(select);
        String queryOff = baseQuery + " SETTINGS " + candidate.name() + " = " + candidate.valueOff();
        String queryOn = baseQuery + " SETTINGS " + candidate.name() + " = " + candidate.valueOn();
        List<String> resultOff = ComparatorHelper.getResultSetFirstColumnAsString(queryOff, errors, state);
        List<String> resultOn = ComparatorHelper.getResultSetFirstColumnAsString(queryOn, errors, state);
        ComparatorHelper.assumeResultSetsAreEqual(resultOff, resultOn, queryOff, List.of(queryOn), state);
    }

}
