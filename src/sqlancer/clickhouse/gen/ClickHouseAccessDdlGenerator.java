package sqlancer.clickhouse.gen;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicLong;

import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.clickhouse.ClickHouseProvider.ClickHouseGlobalState;
import sqlancer.clickhouse.ClickHouseSchema.ClickHouseTable;
import sqlancer.clickhouse.ast.ClickHouseAccessDdlStatement;

/**
 * Access-control DDL generator: CREATE/ALTER/DROP QUOTA / SETTINGS PROFILE / ROW POLICY.
 *
 * <p>Workstream 12 of the 2026-05-27 coverage expansion plan. Quota windows are pinned to
 * INTERVAL 24 HOUR (not TIME-based) so iteration cadence doesn't trip quota limits. Profiles
 * don't reference users (avoids UNKNOWN_USER on locked-down clusters). RowPolicy emission
 * mirrors the inline shape ClickHouseRowPolicyOracle uses today.
 */
public final class ClickHouseAccessDdlGenerator {

    private static final AtomicLong COUNTER = new AtomicLong();

    private ClickHouseAccessDdlGenerator() {
    }

    public static ClickHouseAccessDdlStatement createQuota(ClickHouseGlobalState state) {
        String name = "q" + COUNTER.incrementAndGet();
        // Cap quotas at MAX_INT-class limits so iteration loops can't accidentally exhaust one.
        // The window component (INTERVAL ... HOUR) is the unit at which CH evaluates the cap.
        String sql = "CREATE QUOTA " + name + " FOR INTERVAL 24 HOUR MAX queries = 2000000000, "
                + "errors = 2000000000, result_rows = 2000000000, read_rows = 2000000000";
        return new ClickHouseAccessDdlStatement(ClickHouseAccessDdlStatement.Kind.CREATE_QUOTA, name, sql);
    }

    public static ClickHouseAccessDdlStatement dropQuota(String name) {
        return new ClickHouseAccessDdlStatement(ClickHouseAccessDdlStatement.Kind.DROP_QUOTA, name,
                "DROP QUOTA IF EXISTS " + name);
    }

    public static ClickHouseAccessDdlStatement createSettingsProfile(ClickHouseGlobalState state) {
        String name = "p" + COUNTER.incrementAndGet();
        // Profile body intentionally minimal: a single inline setting that's universally accepted.
        String sql = "CREATE SETTINGS PROFILE " + name + " SETTINGS max_memory_usage = 1000000000";
        return new ClickHouseAccessDdlStatement(ClickHouseAccessDdlStatement.Kind.CREATE_SETTINGS_PROFILE, name, sql);
    }

    public static ClickHouseAccessDdlStatement dropSettingsProfile(String name) {
        return new ClickHouseAccessDdlStatement(ClickHouseAccessDdlStatement.Kind.DROP_SETTINGS_PROFILE, name,
                "DROP SETTINGS PROFILE IF EXISTS " + name);
    }

    public static ClickHouseAccessDdlStatement createRowPolicy(ClickHouseGlobalState state) {
        if (state.getSchema().getDatabaseTables().isEmpty()) {
            throw new IgnoreMeException();
        }
        ClickHouseTable table = Randomly.fromList(state.getSchema().getDatabaseTables());
        String name = "rp" + COUNTER.incrementAndGet();
        String fq = state.getDatabaseName() + "." + table.getName();
        // The USING expression is trivially-true so the policy doesn't filter the row set.
        // ClickHouseRowPolicyOracle uses a richer USING expression for its own invariant; this
        // generator emits the structurally-valid trivial form for coverage of the DDL surface.
        String sql = "CREATE ROW POLICY " + name + " ON " + fq + " USING 1 TO CURRENT_USER";
        return new ClickHouseAccessDdlStatement(ClickHouseAccessDdlStatement.Kind.CREATE_ROW_POLICY, name, sql);
    }

    public static ClickHouseAccessDdlStatement dropRowPolicy(String name, String table,
            ClickHouseGlobalState state) {
        String fq = state.getDatabaseName() + "." + table;
        return new ClickHouseAccessDdlStatement(ClickHouseAccessDdlStatement.Kind.DROP_ROW_POLICY, name,
                "DROP ROW POLICY IF EXISTS " + name + " ON " + fq);
    }

    /** Execute an access-control DDL statement; return true on success, false on tolerated error. */
    public static boolean execute(ClickHouseGlobalState state, ClickHouseAccessDdlStatement stmt) {
        try (Statement s = state.getConnection().createStatement()) {
            s.execute(stmt.getSql());
            return true;
        } catch (SQLException e) {
            return false;
        }
    }
}
