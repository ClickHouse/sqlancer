package sqlancer.clickhouse.gen;

import java.util.List;
import java.util.stream.Collectors;

import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.clickhouse.ClickHouseProvider.ClickHouseGlobalState;
import sqlancer.clickhouse.ClickHouseSchema.ClickHouseColumn;
import sqlancer.clickhouse.ClickHouseSchema.ClickHouseTable;
import sqlancer.clickhouse.ast.ClickHouseAlterStatistics;

/**
 * Statistics ALTER generator. Emits ALTER TABLE ... MODIFY STATISTICS / MATERIALIZE STATISTICS / DROP STATISTICS.
 * Workstream 11 of the 2026-05-27 coverage expansion plan.
 */
public final class ClickHouseStatisticsGenerator {

    private static final List<String> KINDS = List.of("tdigest", "uniq", "countmin", "minmax");

    private ClickHouseStatisticsGenerator() {
    }

    public static ClickHouseAlterStatistics buildStatement(ClickHouseGlobalState state) {
        List<ClickHouseTable> tables = state.getSchema().getDatabaseTables().stream().filter(t -> !t.isView())
                .collect(Collectors.toList());
        if (tables.isEmpty()) {
            throw new IgnoreMeException();
        }
        ClickHouseTable table = Randomly.fromList(tables);
        ClickHouseColumn col = Randomly.fromList(table.getColumns());
        String fq = state.getDatabaseName() + "." + table.getName();

        ClickHouseAlterStatistics.Kind kind = Randomly.fromOptions(ClickHouseAlterStatistics.Kind.values());
        String sql;
        switch (kind) {
        case MODIFY_STATISTICS:
            String kind1 = Randomly.fromList(KINDS);
            sql = "ALTER TABLE " + fq + " MODIFY STATISTICS " + col.getName() + " TYPE " + kind1;
            break;
        case MATERIALIZE_STATISTICS:
            sql = "ALTER TABLE " + fq + " MATERIALIZE STATISTICS " + col.getName();
            break;
        case DROP_STATISTICS:
            sql = "ALTER TABLE " + fq + " DROP STATISTICS " + col.getName();
            break;
        default:
            throw new AssertionError(kind);
        }
        return new ClickHouseAlterStatistics(kind, table.getName(), col.getName(), sql);
    }
}
