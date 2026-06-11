package sqlancer.clickhouse.ast.constant;

import com.clickhouse.data.ClickHouseDataType;

import sqlancer.IgnoreMeException;
import sqlancer.clickhouse.ast.ClickHouseConstant;

public final class ClickHouseUnsupportedConstant extends ClickHouseConstant {

    @Override
    public String toString() {
        return "/* unsupported */ NULL";
    }

    @Override
    public boolean isNull() {
        return false;
    }

    public boolean isUnsupported() {
        return true;
    }

    @Override
    public boolean asBooleanNotNull() {
        throw new IgnoreMeException();
    }

    @Override
    public ClickHouseDataType getDataType() {

        return ClickHouseDataType.Nothing;
    }

    @Override
    public boolean compareInternal(Object val) {
        throw new IgnoreMeException();
    }

    @Override
    public ClickHouseConstant applyEquals(ClickHouseConstant right) {
        throw new IgnoreMeException();
    }

    @Override
    public ClickHouseConstant applyLess(ClickHouseConstant right) {
        throw new IgnoreMeException();
    }

    @Override
    public Object getValue() {
        throw new IgnoreMeException();
    }

    @Override
    public ClickHouseConstant cast(ClickHouseDataType type) {
        throw new IgnoreMeException();
    }
}
