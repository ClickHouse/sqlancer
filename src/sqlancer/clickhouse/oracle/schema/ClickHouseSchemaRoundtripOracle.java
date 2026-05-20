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

/**
 * Schema-roundtrip oracle for the {@code data_type_default_nullable} regression family.
 *
 * <p>
 * ClickHouse#97287 (public) and private#53340 reported the same bug shape: when the connection has
 * {@code data_type_default_nullable=1}, an explicit {@code NOT NULL} modifier on a column was silently dropped, so a
 * column declared {@code c0 Int32 NOT NULL} was created as {@code Nullable(Int32)}. Single-query oracles cannot catch
 * this because no query is wrong -- the database itself is wrong.
 *
 * <p>
 * Per iteration: create two tables side-by-side, both declaring {@code c0 Int32 NOT NULL}. One CREATE runs under
 * {@code SETTINGS data_type_default_nullable = 0} (the conservative baseline) and the other under
 * {@code data_type_default_nullable = 1}. Read the resulting column type back from {@code system.columns}. Assert that
 * neither table's column is {@code Nullable(Int32)}. If the bug fires, the {@code _on} table's column type starts with
 * {@code Nullable(}.
 *
 * <p>
 * Both tables are dropped before the next iteration. The oracle is structurally independent of the random table
 * generator -- it does NOT pick a random pre-existing table -- so the failure attribution is precise: any failure here
 * is an assertion about the server's interpretation of {@code NOT NULL}, not about any oracle's random query.
 */
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
        // Use Memory engine: no ORDER BY / PRIMARY KEY required, no partitioning surface to
        // perturb the result. The bug being chased is a DDL-time rewrite, not a runtime query
        // optimisation, so the engine choice is irrelevant beyond minimising setup noise.
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
            }
            offCreated = new SQLQueryAdapter(createOff, errors, true).execute(state, false);
            onCreated = new SQLQueryAdapter(createOn, errors, true).execute(state, false);
            if (!offCreated || !onCreated) {
                throw new IgnoreMeException();
            }
            List<String> typesOff = readColumnTypes(off);
            List<String> typesOn = readColumnTypes(on);
            if (typesOff.size() != 2 || typesOn.size() != 2) {
                // system.columns didn't return both columns -- catalog drift; skip.
                throw new IgnoreMeException();
            }
            // The baseline must match too (any Nullable here is a deeper bug, but it's not the one
            // we're hunting). Assert both halves.
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
                    }
                    new SQLQueryAdapter(dropOff, errors, true).execute(state, false);
                } catch (SQLException ignored) {
                    // best-effort
                }
            }
            if (onCreated) {
                try {
                    if (state.getOptions().logEachSelect()) {
                        state.getLogger().writeCurrent(dropOn);
                    }
                    new SQLQueryAdapter(dropOn, errors, true).execute(state, false);
                } catch (SQLException ignored) {
                    // best-effort
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
        }
        // Defensive: a future ClickHouse rename of the schema view would make the list empty,
        // which the caller treats as "iteration uninformative" via IgnoreMeException -- no false
        // positive.
        return out;
    }
}
