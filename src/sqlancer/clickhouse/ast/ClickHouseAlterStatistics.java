package sqlancer.clickhouse.ast;

public class ClickHouseAlterStatistics extends ClickHouseDdlStatement {

    public enum Kind {
        ADD_STATISTICS, MODIFY_STATISTICS, MATERIALIZE_STATISTICS, DROP_STATISTICS
    }

    private final Kind kind;
    private final String table;
    private final String column;

    public ClickHouseAlterStatistics(Kind kind, String table, String column, String sql) {
        super(sql);
        this.kind = kind;
        this.table = table;
        this.column = column;
    }

    public Kind getKind() {
        return kind;
    }

    public String getTable() {
        return table;
    }

    public String getColumn() {
        return column;
    }
}
