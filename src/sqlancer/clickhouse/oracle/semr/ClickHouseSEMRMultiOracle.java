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

/**
 * Multi-setting Settings-Equivalence Multiset-Result oracle.
 *
 * <p>
 * Picks 2-3 optimizer-rewrite settings (sampled without replacement from {@code SEMR_SETTINGS}) and runs the same base
 * SELECT under two corners of the resulting hypercube: all-zero and all-one. Asserts the two multisets agree.
 *
 * <p>
 * Rationale: the single-setting {@link ClickHouseSEMROracle} catches per-setting regressions but cannot reach bugs
 * triggered by pass interactions -- e.g. ClickHouse#100029 needs the SEMI/ANTI rewrite *and* the IN-subquery readiness
 * path to be in their "new" mode simultaneously. Iterating over hypercube corners is the cheapest way to surface those
 * without writing per-bug oracles.
 *
 * <p>
 * Sampling all-zero and all-one (rather than two random corners) keeps the failure attribution clean: when the diff
 * fails, the operator's first question is "which subset of these settings is responsible", and binary-bisecting between
 * those two corners reaches the answer in log(k) steps with no further oracle work.
 */
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
        // Hypercube corners: all-zero and all-one. A single failing pair points the bisect at a
        // specific subset of the picked settings; randomising the corners would only obscure that.
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
