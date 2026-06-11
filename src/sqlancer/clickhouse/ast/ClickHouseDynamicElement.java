package sqlancer.clickhouse.ast;

import sqlancer.clickhouse.ClickHouseToStringVisitor;

public class ClickHouseDynamicElement extends ClickHouseExpression {

    private final ClickHouseExpression dyn;
    private final String elementType;
    private final boolean functionForm;

    public ClickHouseDynamicElement(ClickHouseExpression dyn, String elementType, boolean functionForm) {
        this.dyn = dyn;
        this.elementType = elementType;
        this.functionForm = functionForm;
    }

    public ClickHouseExpression getDyn() {
        return dyn;
    }

    public String getElementType() {
        return elementType;
    }

    public boolean isFunctionForm() {
        return functionForm;
    }

    @Override
    public String toString() {
        return ClickHouseToStringVisitor.asString(this);
    }
}
