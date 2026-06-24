package sqlancer.clickhouse.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import sqlancer.clickhouse.ClickHouseSchema;
import sqlancer.clickhouse.ClickHouseVisitor;
import sqlancer.common.schema.TableIndex;

class ClickHouseSelectArrayJoinTest {

    private static ClickHouseColumnReference column(ClickHouseSchema.ClickHouseTable table, String name) {
        ClickHouseSchema.ClickHouseColumn col = new ClickHouseSchema.ClickHouseColumn(name,
                ClickHouseSchema.ClickHouseLancerDataType.getRandom(), false, false, table);
        col.setTable(table);
        return col.asColumnReference(null);
    }

    private static ClickHouseSchema.ClickHouseTable table(String name) {
        List<TableIndex> indexes = Collections.emptyList();
        return new ClickHouseSchema.ClickHouseTable(name, Collections.emptyList(), indexes, false);
    }

    @Test
    void defaultEmptyFieldDoesNotEmitArrayJoin() {
        ClickHouseSchema.ClickHouseTable t = table("t");
        ClickHouseColumnReference x = column(t, "x");
        ClickHouseSelect select = new ClickHouseSelect();
        select.setFetchColumns(Arrays.asList(x));
        select.setFromClause(new ClickHouseTableReference(t, null));

        String rendered = ClickHouseVisitor.asString(select);
        assertFalse(rendered.contains("ARRAY JOIN"),
                "default-empty arrayJoinExprs must not emit ARRAY JOIN, got: " + rendered);
        assertFalse(rendered.contains("LEFT ARRAY JOIN"), "got: " + rendered);

        assertEquals("SELECT t.x FROM t", rendered);
    }

    @Test
    void singleExpressionRendersBetweenFromAndWhere() {
        ClickHouseSchema.ClickHouseTable t = table("t");
        ClickHouseColumnReference x = column(t, "x");
        ClickHouseColumnReference arr = column(t, "arr");
        ClickHouseSelect select = new ClickHouseSelect();
        select.setFetchColumns(Arrays.asList(x));
        select.setFromClause(new ClickHouseTableReference(t, null));
        select.setArrayJoinExprs(Arrays.asList(arr));

        String rendered = ClickHouseVisitor.asString(select);
        assertTrue(rendered.contains(" FROM t ARRAY JOIN t.arr"),
                "expected ARRAY JOIN between FROM and any further clause, got: " + rendered);
    }

    @Test
    void leftArrayJoinTogglesKeyword() {
        ClickHouseSchema.ClickHouseTable t = table("t");
        ClickHouseColumnReference x = column(t, "x");
        ClickHouseColumnReference arr = column(t, "arr");
        ClickHouseSelect select = new ClickHouseSelect();
        select.setFetchColumns(Arrays.asList(x));
        select.setFromClause(new ClickHouseTableReference(t, null));
        select.setArrayJoinExprs(Arrays.asList(arr));
        select.setArrayJoinLeft(true);

        String rendered = ClickHouseVisitor.asString(select);
        assertTrue(rendered.contains(" LEFT ARRAY JOIN "), "expected LEFT ARRAY JOIN keyword, got: " + rendered);
    }

    @Test
    void multipleExpressionsRenderCommaSeparated() {
        ClickHouseSchema.ClickHouseTable t = table("t");
        ClickHouseColumnReference x = column(t, "x");
        ClickHouseColumnReference arr1 = column(t, "arr1");
        ClickHouseColumnReference arr2 = column(t, "arr2");
        ClickHouseSelect select = new ClickHouseSelect();
        select.setFetchColumns(Arrays.asList(x));
        select.setFromClause(new ClickHouseTableReference(t, null));
        select.setArrayJoinExprs(Arrays.asList(arr1, arr2));

        String rendered = ClickHouseVisitor.asString(select);
        assertTrue(rendered.contains("ARRAY JOIN t.arr1, t.arr2"), "got: " + rendered);
    }

    @Test
    void arrayJoinPositionedBeforeJoinClauses() {
        ClickHouseSchema.ClickHouseTable t1 = table("t1");
        ClickHouseSchema.ClickHouseTable t2 = table("t2");
        ClickHouseColumnReference x = column(t1, "x");
        ClickHouseColumnReference arr = column(t1, "arr");
        ClickHouseTableReference t1Ref = new ClickHouseTableReference(t1, null);
        ClickHouseTableReference t2Ref = new ClickHouseTableReference(t2, null);
        ClickHouseSelect select = new ClickHouseSelect();
        select.setFetchColumns(Arrays.asList(x));
        select.setFromClause(t1Ref);
        select.setArrayJoinExprs(Arrays.asList(arr));
        select.setJoinClauses(Arrays.asList(new ClickHouseExpression.ClickHouseJoin(t1Ref, t2Ref,
                ClickHouseExpression.ClickHouseJoin.JoinType.CROSS)));

        String rendered = ClickHouseVisitor.asString(select);
        int arrayJoinIdx = rendered.indexOf("ARRAY JOIN");
        int joinIdx = rendered.indexOf(" JOIN t2");
        assertTrue(arrayJoinIdx >= 0 && joinIdx > arrayJoinIdx,
                "ARRAY JOIN must come before regular JOIN, got: " + rendered);
    }
}
