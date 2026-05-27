package sqlancer.clickhouse.ast;

/**
 * Mutations: ALTER TABLE UPDATE/DELETE and lightweight DELETE FROM. Workstream 9 of the plan.
 */
public class ClickHouseMutationStatement extends ClickHouseDdlStatement {

    public enum Kind {
        ALTER_UPDATE, ALTER_DELETE, LIGHTWEIGHT_DELETE
    }

    private final Kind kind;
    private final String table;

    public ClickHouseMutationStatement(Kind kind, String table, String sql) {
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

    public boolean isBackground() {
        return kind == Kind.ALTER_UPDATE || kind == Kind.ALTER_DELETE;
    }
}
