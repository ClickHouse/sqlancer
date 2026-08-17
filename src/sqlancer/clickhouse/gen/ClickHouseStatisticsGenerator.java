package sqlancer.clickhouse.gen;

import java.util.List;
import java.util.stream.Collectors;

import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.clickhouse.ClickHouseProvider.ClickHouseGlobalState;
import sqlancer.clickhouse.ClickHouseSchema.ClickHouseColumn;
import sqlancer.clickhouse.ClickHouseSchema.ClickHouseTable;
import sqlancer.clickhouse.ast.ClickHouseAlterStatistics;

public final class ClickHouseStatisticsGenerator {

    public static final List<String> KINDS = List.of("tdigest", "uniq", "countmin", "minmax", "uniq_v2", "basic");

    private ClickHouseStatisticsGenerator() {
    }

    public static String pickKinds() {
        List<String> pool = new java.util.ArrayList<>(KINDS);
        java.util.Collections.shuffle(pool, new java.util.Random(Randomly.getNotCachedInteger(0, Integer.MAX_VALUE)));
        int n = 1 + (int) Randomly.getNotCachedInteger(0, 2);
        return String.join(", ", pool.subList(0, Math.min(n, pool.size())));
    }

    public static ClickHouseAlterStatistics buildStatement(ClickHouseGlobalState state) {
        List<ClickHouseTable> tables = state.getSchema().getDatabaseTables().stream().filter(t -> !t.isView())
                .collect(Collectors.toList());
        if (tables.isEmpty()) {
            throw new IgnoreMeException();
        }
        ClickHouseTable table = Randomly.fromList(tables);
        List<ClickHouseColumn> statisticsColumns = table.getColumns().stream().filter(c -> !c.isAlias())
                .collect(Collectors.toList());
        if (statisticsColumns.isEmpty()) {
            throw new IgnoreMeException();
        }
        ClickHouseColumn col = Randomly.fromList(statisticsColumns);
        String fq = state.getDatabaseName() + "." + table.getName();

        ClickHouseAlterStatistics.Kind kind = Randomly.fromOptions(ClickHouseAlterStatistics.Kind.values());
        String sql;
        switch (kind) {
        case ADD_STATISTICS:
            sql = "ALTER TABLE " + fq + " ADD STATISTICS IF NOT EXISTS " + col.getName() + " TYPE " + pickKinds();
            break;
        case MODIFY_STATISTICS:
            sql = "ALTER TABLE " + fq + " MODIFY STATISTICS " + col.getName() + " TYPE " + pickKinds();
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
