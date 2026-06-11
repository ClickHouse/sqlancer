package sqlancer.clickhouse.ast;

public class ClickHouseAlterColumnStatement extends ClickHouseDdlStatement {

    public enum Kind {
        ADD_COLUMN, DROP_COLUMN, MODIFY_COLUMN, RENAME_COLUMN, RENAME_TABLE, COMMENT_COLUMN
    }

    private final Kind kind;
    private final String table;

    public ClickHouseAlterColumnStatement(Kind kind, String table, String sql) {
        super(sql);
        this.kind = kind;
        this.table = table;
    }

    public Kind getKind() {
        return kind;
    }

    public String getTable() {
        return table;
    }
}
