package sqlancer.clickhouse.ast;

import sqlancer.clickhouse.ClickHouseToStringVisitor;

/**
 * Variant element access. Renders as {@code v.Int32} (subcolumn dot form) or
 * {@code variantElement(v, 'Int32')} (function form), selected via the {@link #isFunctionForm}
 * field. The function form is the one ClickHouse documents as the primary read path.
 *
 * <p>Workstream 6.
 */
public class ClickHouseVariantElement extends ClickHouseExpression {

    private final ClickHouseExpression variant;
    private final String elementType;
    private final boolean functionForm;

    public ClickHouseVariantElement(ClickHouseExpression variant, String elementType, boolean functionForm) {
        this.variant = variant;
        this.elementType = elementType;
        this.functionForm = functionForm;
    }

    public ClickHouseExpression getVariant() {
        return variant;
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
