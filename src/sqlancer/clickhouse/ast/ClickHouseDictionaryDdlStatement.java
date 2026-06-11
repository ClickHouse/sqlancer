package sqlancer.clickhouse.ast;

public class ClickHouseDictionaryDdlStatement extends ClickHouseDdlStatement {

    public enum Kind {
        CREATE_DICTIONARY, ALTER_DICTIONARY, DROP_DICTIONARY
    }

    private final Kind kind;
    private final String dictName;

    public ClickHouseDictionaryDdlStatement(Kind kind, String dictName, String sql) {
        super(sql);
        this.kind = kind;
        this.dictName = dictName;
    }

    public Kind getKind() {
        return kind;
    }

    public String getDictName() {
        return dictName;
    }
}
