package sqlancer.clickhouse;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;

import sqlancer.clickhouse.ClickHouseProvider.ClickHouseGlobalState;

public final class ClickHouseFailureDiagnostics {

    private static final int MAX_LOGGED_ROWS = 40;
    private static final int MAX_LOGGED_ROW_LENGTH = 400;

    private ClickHouseFailureDiagnostics() {
    }

    public static void logQueryPlan(ClickHouseGlobalState state, String label, String query) {
        logRows(state, label + " EXPLAIN indexes", "EXPLAIN indexes = 1 " + query);
        logRows(state, label + " EXPLAIN ESTIMATE", "EXPLAIN ESTIMATE " + query);
    }

    public static void logTableState(ClickHouseGlobalState state, String tableName) {
        String literal = asStringLiteral(tableName);
        logRows(state, "schema of " + tableName, "SHOW CREATE TABLE " + asIdentifier(tableName));
        logRows(state, "parts of " + tableName,
                "SELECT name, rows, level, min_block_number, max_block_number FROM system.parts"
                        + " WHERE database = currentDatabase() AND table = " + literal + " AND active ORDER BY name");
        logRows(state, "mutations of " + tableName,
                "SELECT mutation_id, is_done, substring(command, 1, 120) AS command,"
                        + " substring(latest_fail_reason, 1, 120) AS latest_fail_reason FROM system.mutations"
                        + " WHERE database = currentDatabase() AND table = " + literal + " ORDER BY mutation_id");
    }

    private static void logRows(ClickHouseGlobalState state, String label, String query) {
        StringBuilder sb = new StringBuilder();
        sb.append("-- diagnostics: ").append(label);
        try (Statement s = state.getConnection().createStatement(); ResultSet rs = s.executeQuery(query)) {
            ResultSetMetaData meta = rs.getMetaData();
            int columns = meta.getColumnCount();
            int printed = 0;
            while (rs.next()) {
                if (printed == MAX_LOGGED_ROWS) {
                    sb.append(System.lineSeparator()).append("--   (truncated at ").append(MAX_LOGGED_ROWS)
                            .append(" rows)");
                    break;
                }
                StringBuilder row = new StringBuilder();
                for (int i = 1; i <= columns; i++) {
                    if (i > 1) {
                        row.append(" | ");
                    }
                    String value = rs.getString(i);
                    row.append(value == null ? "NULL" : value);
                }
                sb.append(System.lineSeparator()).append("--   ").append(truncate(row.toString()));
                printed++;
            }
            if (printed == 0) {
                sb.append(System.lineSeparator()).append("--   (no rows)");
            }
        } catch (Exception e) {
            sb.append(System.lineSeparator()).append("--   (unavailable: ")
                    .append(truncate(String.valueOf(e.getMessage()))).append(")");
        }
        try {
            state.getState().getLocalState().log(sb.toString());
        } catch (Exception ignored) {
        }
    }

    private static String truncate(String value) {
        String flattened = value.replace('\n', ' ').replace('\r', ' ');
        if (flattened.length() <= MAX_LOGGED_ROW_LENGTH) {
            return flattened;
        }
        return flattened.substring(0, MAX_LOGGED_ROW_LENGTH) + "...";
    }

    private static String asIdentifier(String name) {
        return "`" + name.replace("`", "``") + "`";
    }

    private static String asStringLiteral(String name) {
        return "'" + name.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }
}
