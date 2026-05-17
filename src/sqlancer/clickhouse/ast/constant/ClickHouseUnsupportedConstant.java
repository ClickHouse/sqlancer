package sqlancer.clickhouse.ast.constant;

import com.clickhouse.data.ClickHouseDataType;

import sqlancer.IgnoreMeException;
import sqlancer.clickhouse.ast.ClickHouseConstant;

/**
 * Sentinel constant returned from {@code ClickHouseCast.*} when the requested coercion is not defined for the input
 * type. The sentinel propagates through cast pipelines without crashing, and any attempt to compare, evaluate, or
 * otherwise consume it raises {@link IgnoreMeException} so the surrounding oracle abandons the statement quietly -- the
 * established CONTRIBUTING.md pattern.
 *
 * <p>
 * Detect with {@link #isUnsupported()} or {@code instanceof} -- never with {@code getDataType() ==
 * Nothing}, which is taken by {@link ClickHouseNullConstant} (legitimate NULL).
 * </p>
 */
public final class ClickHouseUnsupportedConstant extends ClickHouseConstant {

    @Override
    public String toString() {
        return "/* unsupported */ NULL";
    }

    @Override
    public boolean isNull() {
        return false;
    }

    // Distinguishes this sentinel from ClickHouseNullConstant, which also returns Nothing.
    public boolean isUnsupported() {
        return true;
    }

    @Override
    public boolean asBooleanNotNull() {
        throw new IgnoreMeException();
    }

    @Override
    public ClickHouseDataType getDataType() {
        // Re-use Nothing as a benign sentinel for any caller that only inspects the flat enum.
        // Anyone that wants to behave differently around this constant must check isUnsupported()
        // or instanceof first.
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
