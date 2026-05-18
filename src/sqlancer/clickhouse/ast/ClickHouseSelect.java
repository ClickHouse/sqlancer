package sqlancer.clickhouse.ast;

import java.util.Collections;
import java.util.List;

import sqlancer.clickhouse.ClickHouseSchema.ClickHouseColumn;
import sqlancer.clickhouse.ClickHouseSchema.ClickHouseTable;
import sqlancer.clickhouse.ClickHouseToStringVisitor;
import sqlancer.common.ast.newast.Select;

public class ClickHouseSelect extends ClickHouseExpression implements
        Select<ClickHouseExpression.ClickHouseJoin, ClickHouseExpression, ClickHouseTable, ClickHouseColumn> {

    private ClickHouseSelect.SelectType fromOptions = ClickHouseSelect.SelectType.ALL;
    private List<ClickHouseExpression> fromClauses;
    /**
     * Optional {@code PREWHERE} clause -- ClickHouse-specific, emitted before {@code WHERE} and binding only to columns
     * physically read from the base table. The split between {@code PREWHERE} and {@code WHERE} is not redundant: the
     * regression family around the query-condition cache (ClickHouse#104781) is sensitive to where each predicate
     * lives, so the generator emits this independently of {@code WHERE} rather than relying on the server's
     * {@code optimize_move_to_prewhere} rewrite.
     */
    private ClickHouseExpression prewhereClause;
    private ClickHouseExpression whereClause;
    private List<ClickHouseExpression> groupByClause = Collections.emptyList();
    private ClickHouseExpression limitClause;
    private List<ClickHouseExpression> orderByClause = Collections.emptyList();
    private ClickHouseExpression offsetClause;
    private List<ClickHouseExpression> fetchColumns = Collections.emptyList();
    private List<ClickHouseExpression.ClickHouseJoin> joinStatements = Collections.emptyList();
    private ClickHouseExpression havingClause;
    /**
     * Expressions for the optional {@code ARRAY JOIN} clause, emitted between FROM and any regular JOIN clauses per
     * ClickHouse grammar. Default empty -- the visitor emits nothing when this list is empty. Activation is blocked on
     * type-system v2 introducing an {@code Array(T)} constructor; the field exists now so the v2 work can flip
     * {@code --test-array-join} on without re-touching the select AST.
     */
    private List<ClickHouseExpression> arrayJoinExprs = Collections.emptyList();
    private boolean arrayJoinLeft;
    /**
     * If true, the rendered SELECT applies the {@code FINAL} modifier to the FROM table. Only valid for MergeTree-family
     * engines; the table generator only emits MergeTree-family tables so this is unconditionally safe in the current
     * generator. FINAL forces merge-on-read deduplication, which exercises a separate code path through
     * skip-indexes, PREWHERE, row-policy, and lazy-materialization (see #97076, #98097, #91847).
     */
    private boolean isFinal;

    public enum SelectType {
        DISTINCT, ALL;
    }

    public void setSelectType(ClickHouseSelect.SelectType fromOptions) {
        this.setFromOptions(fromOptions);
    }

    public void setFromClause(ClickHouseExpression fromList) {
        this.fromClauses = List.of(fromList);
    }

    @Override
    public List<ClickHouseExpression> getFromList() {
        return fromClauses;
    }

    public ClickHouseSelect.SelectType getFromOptions() {
        return fromOptions;
    }

    public void setFromOptions(ClickHouseSelect.SelectType fromOptions) {
        this.fromOptions = fromOptions;
    }

    @Override
    public ClickHouseExpression getWhereClause() {
        return whereClause;
    }

    @Override
    public void setWhereClause(ClickHouseExpression whereClause) {
        this.whereClause = whereClause;
    }

    public ClickHouseExpression getPrewhereClause() {
        return prewhereClause;
    }

    public void setPrewhereClause(ClickHouseExpression prewhereClause) {
        this.prewhereClause = prewhereClause;
    }

    @Override
    public void setGroupByClause(List<ClickHouseExpression> groupByClause) {
        this.groupByClause = groupByClause;
    }

    @Override
    public List<ClickHouseExpression> getGroupByClause() {
        return groupByClause;
    }

    @Override
    public void setLimitClause(ClickHouseExpression limitClause) {
        this.limitClause = limitClause;
    }

    @Override
    public ClickHouseExpression getLimitClause() {
        return limitClause;
    }

    @Override
    public List<ClickHouseExpression> getOrderByClauses() {
        return orderByClause;
    }

    @Override
    public void setOrderByClauses(List<ClickHouseExpression> orderBy) {
        this.orderByClause = orderBy;
    }

    @Override
    public void setOffsetClause(ClickHouseExpression offsetClause) {
        this.offsetClause = offsetClause;
    }

    @Override
    public ClickHouseExpression getOffsetClause() {
        return offsetClause;
    }

    @Override
    public void setFetchColumns(List<ClickHouseExpression> fetchColumns) {
        this.fetchColumns = fetchColumns;
    }

    @Override
    public List<ClickHouseExpression> getFetchColumns() {
        return fetchColumns;
    }

    @Override
    public void setJoinClauses(List<ClickHouseExpression.ClickHouseJoin> joinStatements) {
        this.joinStatements = joinStatements;
    }

    @Override
    public List<ClickHouseExpression.ClickHouseJoin> getJoinClauses() {
        return joinStatements;
    }

    @Override
    public void setHavingClause(ClickHouseExpression havingClause) {
        this.havingClause = havingClause;
    }

    @Override
    public ClickHouseExpression getHavingClause() {
        assert orderByClause != null;
        return havingClause;
    }

    @Override
    public String asString() {
        return ClickHouseToStringVisitor.asString(this);
    }

    @Override
    public void setFromList(List<ClickHouseExpression> fromList) {
        this.fromClauses = fromList;
    }

    public List<ClickHouseExpression> getArrayJoinExprs() {
        return arrayJoinExprs;
    }

    public void setArrayJoinExprs(List<ClickHouseExpression> arrayJoinExprs) {
        this.arrayJoinExprs = arrayJoinExprs == null ? Collections.emptyList() : arrayJoinExprs;
    }

    public boolean isArrayJoinLeft() {
        return arrayJoinLeft;
    }

    public void setArrayJoinLeft(boolean arrayJoinLeft) {
        this.arrayJoinLeft = arrayJoinLeft;
    }

    public boolean isFinal() {
        return isFinal;
    }

    public void setFinal(boolean isFinal) {
        this.isFinal = isFinal;
    }
}
