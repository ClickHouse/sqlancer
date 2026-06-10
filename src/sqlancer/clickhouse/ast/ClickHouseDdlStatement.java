package sqlancer.clickhouse.ast;

/**
 * Marker base class for DDL statements that aren't part of the SELECT expression tree. Concrete subclasses
 * (ClickHouseAlterColumnStatement, ClickHouseAlterMutation, ClickHouseLightweightDelete, ClickHouseAlterStatistics,
 * ClickHouseCreateQuota, ClickHouseCreateSettingsProfile, ClickHouseCreateRowPolicy, ClickHouseCreateDictionary,
 * ClickHouseDropDictionary, ClickHouseAlterDictionary) carry a pre-rendered SQL fragment.
 *
 * <p>
 * Why pre-rendered text rather than full structured ASTs: DDL emission is fire-and-forget -- we never re-derive or
 * transform these statements. A pre-rendered text avoids the boilerplate of a per-statement AST + visitor case for
 * nodes that aren't actually being analyzed. Workstreams 8 / 9 / 11 / 12 / 14 of the 2026-05-27 coverage expansion
 * plan.
 */
public abstract class ClickHouseDdlStatement extends ClickHouseExpression {

    private final String sql;

    protected ClickHouseDdlStatement(String sql) {
        this.sql = sql;
    }

    public String getSql() {
        return sql;
    }

    @Override
    public String toString() {
        return sql;
    }
}
