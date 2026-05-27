package sqlancer.clickhouse.ast;

import sqlancer.clickhouse.ClickHouseToStringVisitor;

/**
 * Dynamic element access. Renders as {@code dyn.Int32} or {@code dynamicElement(dyn, 'Int32')}.
 * Workstream 6.
 */
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
