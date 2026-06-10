package sqlancer.clickhouse.ast;

/**
 * Access-control DDL: CREATE/ALTER/DROP QUOTA / SETTINGS PROFILE / ROW POLICY. Workstream 12.
 */
public class ClickHouseAccessDdlStatement extends ClickHouseDdlStatement {

    public enum Kind {
        CREATE_QUOTA, ALTER_QUOTA, DROP_QUOTA, CREATE_SETTINGS_PROFILE, ALTER_SETTINGS_PROFILE, DROP_SETTINGS_PROFILE,
        CREATE_ROW_POLICY, ALTER_ROW_POLICY, DROP_ROW_POLICY
    }

    private final Kind kind;
    private final String name;

    public ClickHouseAccessDdlStatement(Kind kind, String name, String sql) {
        super(sql);
        this.kind = kind;
        this.name = name;
    }

    public Kind getKind() {
        return kind;
    }

    public String getName() {
        return name;
    }
}
