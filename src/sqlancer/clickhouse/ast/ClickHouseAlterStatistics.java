package sqlancer.clickhouse.ast;

/**
 * ALTER TABLE ... MODIFY STATISTICS col TYPE ... / MATERIALIZE STATISTICS col [IN PARTITION p]. Workstream 11 of the
 * plan.
 */
public class ClickHouseAlterStatistics extends ClickHouseDdlStatement {

    public enum Kind {
        MODIFY_STATISTICS, MATERIALIZE_STATISTICS, DROP_STATISTICS
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
