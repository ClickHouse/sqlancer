package sqlancer.clickhouse.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import sqlancer.clickhouse.ClickHouseSchema;
import sqlancer.clickhouse.ClickHouseToStringVisitor;
import sqlancer.clickhouse.ClickHouseVisitor;
import sqlancer.clickhouse.ast.constant.ClickHouseInt8Constant;
import sqlancer.common.schema.TableIndex;

class ClickHouseSetOperationTest {

    private static ClickHouseSelect simpleSelectOver(String tableName, String colName) {
        List<TableIndex> indexes = Collections.emptyList();
        ClickHouseSchema.ClickHouseTable table = new ClickHouseSchema.ClickHouseTable(tableName,
                Collections.emptyList(), indexes, false);
        ClickHouseTableReference tableRef = new ClickHouseTableReference(table, null);
        ClickHouseSchema.ClickHouseColumn col = new ClickHouseSchema.ClickHouseColumn(colName,
                ClickHouseSchema.ClickHouseLancerDataType.getRandom(), false, false, table);
        col.setTable(table);
        ClickHouseColumnReference colRef = col.asColumnReference(null);
        ClickHouseSelect select = new ClickHouseSelect();
        select.setFetchColumns(Arrays.asList(colRef));
        select.setFromClause(tableRef);
        return select;
    }

    @Test
    void rendersUnionAllAtTopLevelWithoutOuterParens() {
        ClickHouseSelect left = simpleSelectOver("t1", "a");
        ClickHouseSelect right = simpleSelectOver("t2", "b");
        ClickHouseSetOperation setOp = new ClickHouseSetOperation(left, ClickHouseSetOperation.SetOpKind.UNION_ALL,
                right);

        String rendered = ClickHouseVisitor.asString(setOp);

        assertTrue(rendered.contains("UNION ALL"), "expected UNION ALL keyword, got: " + rendered);
        assertTrue(rendered.startsWith("SELECT"), "expected to start with SELECT, got: " + rendered);
        assertFalse(rendered.startsWith("("), "expected no outer parens, got: " + rendered);
    }

    @Test
    void rendersEachSetOpKindWithItsExplicitKeyword() {
        for (ClickHouseSetOperation.SetOpKind kind : ClickHouseSetOperation.SetOpKind.values()) {
            ClickHouseSelect left = simpleSelectOver("t1", "a");
            ClickHouseSelect right = simpleSelectOver("t2", "b");
            String rendered = ClickHouseVisitor.asString(new ClickHouseSetOperation(left, kind, right));
            assertTrue(rendered.contains(kind.getKeyword()), "kind " + kind + " should render its explicit keyword '"
                    + kind.getKeyword() + "', got: " + rendered);
        }
    }

    @Test
    void explicitAllAndDistinctVariantsAreDistinct() {
        ClickHouseSelect left = simpleSelectOver("t1", "a");
        ClickHouseSelect right = simpleSelectOver("t2", "b");
        String intersectAll = ClickHouseVisitor
                .asString(new ClickHouseSetOperation(left, ClickHouseSetOperation.SetOpKind.INTERSECT_ALL, right));
        String intersectDistinct = ClickHouseVisitor
                .asString(new ClickHouseSetOperation(left, ClickHouseSetOperation.SetOpKind.INTERSECT_DISTINCT, right));
        String exceptAll = ClickHouseVisitor
                .asString(new ClickHouseSetOperation(left, ClickHouseSetOperation.SetOpKind.EXCEPT_ALL, right));
        String exceptDistinct = ClickHouseVisitor
                .asString(new ClickHouseSetOperation(left, ClickHouseSetOperation.SetOpKind.EXCEPT_DISTINCT, right));

        assertTrue(intersectAll.contains("INTERSECT ALL"));
        assertTrue(intersectDistinct.contains("INTERSECT DISTINCT"));
        assertTrue(exceptAll.contains("EXCEPT ALL"));
        assertTrue(exceptDistinct.contains("EXCEPT DISTINCT"));
    }

    @Test
    void nestedSetOpsRenderInnerParens() {
        ClickHouseSelect a = simpleSelectOver("t1", "a");
        ClickHouseSelect b = simpleSelectOver("t2", "b");
        ClickHouseSelect c = simpleSelectOver("t3", "c");

        ClickHouseSetOperation inner = new ClickHouseSetOperation(a, ClickHouseSetOperation.SetOpKind.UNION_ALL, b);
        ClickHouseSetOperation outer = new ClickHouseSetOperation(inner, ClickHouseSetOperation.SetOpKind.INTERSECT_ALL,
                c);

        String rendered = ClickHouseVisitor.asString(outer);

        assertTrue(rendered.startsWith("("),
                "nested left set-op should be wrapped in parens to preserve grouping, got: " + rendered);
        assertTrue(rendered.contains("UNION ALL"));
        assertTrue(rendered.contains(") INTERSECT ALL "));
    }

    @Test
    void selectWithSetOperationInFromClause() {
        ClickHouseSelect leftInner = simpleSelectOver("t1", "a");
        ClickHouseSelect rightInner = simpleSelectOver("t2", "b");
        ClickHouseSetOperation setOpFrom = new ClickHouseSetOperation(leftInner,
                ClickHouseSetOperation.SetOpKind.UNION_ALL, rightInner);

        ClickHouseSelect outerSelect = new ClickHouseSelect();
        outerSelect.setFetchColumns(Arrays.asList(new ClickHouseInt8Constant(1)));
        outerSelect.setFromClause(setOpFrom);

        String rendered = ClickHouseVisitor.asString(outerSelect);

        assertTrue(rendered.startsWith("SELECT 1 FROM "), "outer select should start as expected, got: " + rendered);
        assertTrue(rendered.contains("UNION ALL"));
        // The set-op embedded in FROM is visited via the dispatcher which wraps with parens.
        assertTrue(rendered.contains("(SELECT"), "set-op embedded in FROM should be parenthesised, got: " + rendered);
    }

    @Test
    void visitorDispatchHandlesSetOperationWithoutAssertionError() {
        ClickHouseSelect left = simpleSelectOver("t1", "a");
        ClickHouseSelect right = simpleSelectOver("t2", "b");
        ClickHouseSetOperation setOp = new ClickHouseSetOperation(left, ClickHouseSetOperation.SetOpKind.UNION_ALL,
                right);

        ClickHouseToStringVisitor visitor = new ClickHouseToStringVisitor();
        // Should dispatch to visit(ClickHouseSetOperation, true), not throw AssertionError.
        visitor.visit((ClickHouseExpression) setOp);
        String rendered = visitor.get();

        assertTrue(rendered.startsWith("("),
                "dispatcher should treat a set-op as a nested expression and add parens, got: " + rendered);
        assertTrue(rendered.contains("UNION ALL"));
    }

    @Test
    void getterAccessorsReturnConstructorValues() {
        ClickHouseSelect left = simpleSelectOver("t1", "a");
        ClickHouseSelect right = simpleSelectOver("t2", "b");
        ClickHouseSetOperation setOp = new ClickHouseSetOperation(left, ClickHouseSetOperation.SetOpKind.UNION_DISTINCT,
                right);

        assertEquals(left, setOp.getLeft());
        assertEquals(right, setOp.getRight());
        assertEquals(ClickHouseSetOperation.SetOpKind.UNION_DISTINCT, setOp.getOp());
        assertNotEquals("UNION ALL", setOp.getOp().getKeyword());
    }
}
