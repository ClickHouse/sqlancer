package sqlancer.clickhouse.ast;

import java.util.List;

import sqlancer.clickhouse.ClickHouseToStringVisitor;

public class ClickHouseJsonPath extends ClickHouseExpression {

    private final ClickHouseExpression json;
    private final List<String> path;
    private final String typeCast;

    public ClickHouseJsonPath(ClickHouseExpression json, List<String> path, String typeCast) {
        if (path == null || path.isEmpty()) {
            throw new IllegalArgumentException("JSON path must have at least one segment");
        }
        this.json = json;
        this.path = List.copyOf(path);
        this.typeCast = typeCast;
    }

    public ClickHouseExpression getJson() {
        return json;
    }

    public List<String> getPath() {
        return path;
    }

    public String getTypeCast() {
        return typeCast;
    }

    @Override
    public String toString() {
        return ClickHouseToStringVisitor.asString(this);
    }
}
