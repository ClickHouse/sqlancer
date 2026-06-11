package sqlancer.clickhouse.oracle.semr;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import sqlancer.ComparatorHelper;
import sqlancer.clickhouse.ClickHouseErrors;
import sqlancer.clickhouse.ClickHouseProvider.ClickHouseGlobalState;
import sqlancer.clickhouse.ClickHouseSessionSettings;
import sqlancer.clickhouse.ClickHouseSessionSettings.SemrCandidate;
import sqlancer.clickhouse.ClickHouseVisitor;
import sqlancer.clickhouse.oracle.tlp.ClickHouseTLPBase;

public class ClickHouseSEMRMultiOracle extends ClickHouseTLPBase {

    private final int arity;

    public ClickHouseSEMRMultiOracle(ClickHouseGlobalState state) {
        super(state);
        ClickHouseErrors.addSessionSettingsErrors(errors);
        this.arity = state.getClickHouseOptions().semrArity;
    }

    @Override
    public void check() throws SQLException {
        super.check();
        select.setWhereClause(null);
        int k = Math.max(2, arity);
        List<SemrCandidate> picks = ClickHouseSessionSettings.pickSemrCandidates(state.getRandomly(), k);
        String baseQuery = ClickHouseVisitor.asString(select);

        String queryOff = baseQuery + " SETTINGS " + renderProfile(picks, false);
        String queryOn = baseQuery + " SETTINGS " + renderProfile(picks, true);
        List<String> resultOff = ComparatorHelper.getResultSetFirstColumnAsString(queryOff, errors, state);
        List<String> resultOn = ComparatorHelper.getResultSetFirstColumnAsString(queryOn, errors, state);
        ComparatorHelper.assumeResultSetsAreEqual(resultOff, resultOn, queryOff, List.of(queryOn), state);
    }

    private static String renderProfile(List<SemrCandidate> picks, boolean on) {
        List<String> parts = new ArrayList<>(picks.size());
        for (SemrCandidate p : picks) {
            parts.add(p.name() + " = " + (on ? p.valueOn() : p.valueOff()));
        }
        return String.join(", ", parts);
    }
}
