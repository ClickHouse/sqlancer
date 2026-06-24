package sqlancer.clickhouse.oracle.schema;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import sqlancer.IgnoreMeException;
import sqlancer.clickhouse.ClickHouseErrors;
import sqlancer.clickhouse.ClickHouseProvider.ClickHouseGlobalState;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;

public class ClickHouseSchemaRoundtripOracle implements TestOracle<ClickHouseGlobalState> {

    private static final AtomicLong COUNTER = new AtomicLong();

    private final ClickHouseGlobalState state;
    private final ExpectedErrors errors = new ExpectedErrors();

    public ClickHouseSchemaRoundtripOracle(ClickHouseGlobalState state) {
        this.state = state;
        ClickHouseErrors.addSessionSettingsErrors(errors);
    }

    @Override
    public void check() throws SQLException {
        long id = COUNTER.incrementAndGet();
        String off = "schroundtrip_off_" + id;
        String on = "schroundtrip_on_" + id;
        String fqOff = state.getDatabaseName() + "." + off;
        String fqOn = state.getDatabaseName() + "." + on;

        String createOff = "CREATE TABLE " + fqOff + " (c0 Int32 NOT NULL, c1 String NOT NULL) ENGINE = Memory"
                + " SETTINGS data_type_default_nullable = 0";
        String createOn = "CREATE TABLE " + fqOn + " (c0 Int32 NOT NULL, c1 String NOT NULL) ENGINE = Memory"
                + " SETTINGS data_type_default_nullable = 1";
        String dropOff = "DROP TABLE IF EXISTS " + fqOff + " SYNC";
        String dropOn = "DROP TABLE IF EXISTS " + fqOn + " SYNC";

        boolean offCreated = false;
        boolean onCreated = false;
        try {
            if (state.getOptions().logEachSelect()) {

                state.getLogger().writeCurrent(createOff);
                state.getLogger().writeCurrent(createOn);
                state.getState().logStatement(createOff);
                state.getState().logStatement(createOn);
            }
            offCreated = new SQLQueryAdapter(createOff, errors, true).execute(state, false);
            onCreated = new SQLQueryAdapter(createOn, errors, true).execute(state, false);
            if (!offCreated || !onCreated) {
                throw new IgnoreMeException();
            }
            List<String> typesOff = readColumnTypes(off);
            List<String> typesOn = readColumnTypes(on);
            if (typesOff.size() != 2 || typesOn.size() != 2) {

                throw new IgnoreMeException();
            }

            for (int i = 0; i < 2; i++) {
                String tOff = typesOff.get(i);
                String tOn = typesOn.get(i);
                if (tOff.startsWith("Nullable(")) {
                    throw new AssertionError(
                            "Baseline CREATE TABLE with data_type_default_nullable=0 produced Nullable column " + i
                                    + ": " + createOff + " -> " + tOff);
                }
                if (tOn.startsWith("Nullable(")) {
                    throw new AssertionError(
                            "CREATE TABLE with data_type_default_nullable=1 silently dropped NOT NULL on column " + i
                                    + ": " + createOn + " -> " + tOn + " (vs baseline " + tOff + ")");
                }
                if (!tOff.equals(tOn)) {
                    throw new AssertionError("data_type_default_nullable=0 vs =1 produced different types on column "
                            + i + ": " + tOff + " vs " + tOn + " (queries: " + createOff + "; " + createOn + ")");
                }
            }
        } finally {
            if (offCreated) {
                try {
                    if (state.getOptions().logEachSelect()) {
                        state.getLogger().writeCurrent(dropOff);
                        state.getState().logStatement(dropOff);
                    }
                    new SQLQueryAdapter(dropOff, errors, true).execute(state, false);
                } catch (SQLException ignored) {

                }
            }
            if (onCreated) {
                try {
                    if (state.getOptions().logEachSelect()) {
                        state.getLogger().writeCurrent(dropOn);
                        state.getState().logStatement(dropOn);
                    }
                    new SQLQueryAdapter(dropOn, errors, true).execute(state, false);
                } catch (SQLException ignored) {

                }
            }
        }
    }

    private List<String> readColumnTypes(String tableName) throws SQLException {
        String sql = "SELECT type FROM system.columns WHERE database = '" + state.getDatabaseName() + "' AND table = '"
                + tableName + "' ORDER BY position";
        List<String> out = new java.util.ArrayList<>();
        try (Statement s = state.getConnection().createStatement(); ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        } catch (SQLException e) {
            if (sqlancer.clickhouse.ClickHouseErrors.isToleratedException(e)) {
                throw new sqlancer.IgnoreMeException();
            }
            throw e;
        }

        return out;
    }
}
