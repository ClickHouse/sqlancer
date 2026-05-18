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

// Settings-Equivalence Multiset-Result oracle. Picks one optimizer-rewrite setting that should be
// result-preserving, runs the same generated SELECT once with the setting forced 0 and once forced
// 1, and asserts the two result multisets are equal. Each check() issues three SELECTs total --
// the TLPBase smoke check plus ON/OFF -- which keeps the smoke's generator-hiccup catching while
// adding 2x cost for the comparison itself.
public class ClickHouseSEMROracle extends ClickHouseTLPBase {

    public ClickHouseSEMROracle(ClickHouseGlobalState state) {
        super(state);
        ClickHouseErrors.addSessionSettingsErrors(errors);
    }

    @Override
    public void check() throws SQLException {
        super.check();
        // v1 compares the unfiltered base SELECT under ON vs OFF; predicate-induced query-shape
        // variation is a candidate v2 enhancement.
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
