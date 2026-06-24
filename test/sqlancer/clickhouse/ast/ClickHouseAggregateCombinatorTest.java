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
import sqlancer.clickhouse.ClickHouseVisitor;
import sqlancer.clickhouse.ast.constant.ClickHouseInt8Constant;
import sqlancer.common.schema.TableIndex;

class ClickHouseAggregateCombinatorTest {

    private static ClickHouseColumnReference column(String tableName, String colName) {
        List<TableIndex> indexes = Collections.emptyList();
        ClickHouseSchema.ClickHouseTable table = new ClickHouseSchema.ClickHouseTable(tableName,
                Collections.emptyList(), indexes, false);
        ClickHouseSchema.ClickHouseColumn col = new ClickHouseSchema.ClickHouseColumn(colName,
                ClickHouseSchema.ClickHouseLancerDataType.getRandom(), false, false, table);
        col.setTable(table);
        return col.asColumnReference(null);
    }

    private static ClickHouseSelect wrap(ClickHouseExpression aggregate) {
        ClickHouseSelect select = new ClickHouseSelect();
        select.setFetchColumns(Arrays.asList(aggregate));
        return select;
    }

    @Test
    void plainAggregateRendersBackwardCompatibly() {
        ClickHouseAggregate sumX = new ClickHouseAggregate(column("t", "x"),
                ClickHouseAggregate.ClickHouseAggregateFunction.SUM);
        String rendered = ClickHouseVisitor.asString(wrap(sumX));
        assertEquals("SELECT SUM(t.x)", rendered);
    }

    @Test
    void sumIfChainRenders() {
        ClickHouseAggregate sumIf = new ClickHouseAggregate(column("t", "x"),
                ClickHouseAggregate.ClickHouseAggregateFunction.SUM,
                Arrays.asList(new ClickHouseAggregateCombinator(ClickHouseAggregateCombinator.Suffix.IF,
                        Arrays.asList(column("t", "c")))));
        String rendered = ClickHouseVisitor.asString(wrap(sumIf));
        assertEquals("SELECT sumIf(t.x, t.c)", rendered);
    }

    @Test
    void countIfModelsConditionInExprField() {

        ClickHouseAggregate countIf = new ClickHouseAggregate(column("t", "c"),
                ClickHouseAggregate.ClickHouseAggregateFunction.COUNT,
                Arrays.asList(new ClickHouseAggregateCombinator(ClickHouseAggregateCombinator.Suffix.IF,
                        Collections.emptyList())));
        String rendered = ClickHouseVisitor.asString(wrap(countIf));
        assertEquals("SELECT countIf(t.c)", rendered);
    }

    @Test
    void chainOrderIsPreserved() {
        ClickHouseColumnReference x = column("t", "x");
        ClickHouseColumnReference c = column("t", "c");
        ClickHouseAggregate ifThenArray = new ClickHouseAggregate(x,
                ClickHouseAggregate.ClickHouseAggregateFunction.SUM,
                Arrays.asList(new ClickHouseAggregateCombinator(ClickHouseAggregateCombinator.Suffix.IF, List.of(c)),
                        new ClickHouseAggregateCombinator(ClickHouseAggregateCombinator.Suffix.ARRAY)));
        ClickHouseAggregate arrayThenIf = new ClickHouseAggregate(x,
                ClickHouseAggregate.ClickHouseAggregateFunction.SUM,
                Arrays.asList(new ClickHouseAggregateCombinator(ClickHouseAggregateCombinator.Suffix.ARRAY),
                        new ClickHouseAggregateCombinator(ClickHouseAggregateCombinator.Suffix.IF, List.of(c))));

        String renderedA = ClickHouseVisitor.asString(wrap(ifThenArray));
        String renderedB = ClickHouseVisitor.asString(wrap(arrayThenIf));

        assertTrue(renderedA.contains("sumIfArray("), "expected 'sumIfArray(' in " + renderedA);
        assertTrue(renderedB.contains("sumArrayIf("), "expected 'sumArrayIf(' in " + renderedB);
        assertNotEquals(renderedA, renderedB);
    }

    @Test
    void threeDeepChainRenders() {
        ClickHouseColumnReference x = column("t", "x");
        ClickHouseColumnReference c = column("t", "c");
        ClickHouseAggregate agg = new ClickHouseAggregate(x, ClickHouseAggregate.ClickHouseAggregateFunction.SUM,
                Arrays.asList(new ClickHouseAggregateCombinator(ClickHouseAggregateCombinator.Suffix.DISTINCT),
                        new ClickHouseAggregateCombinator(ClickHouseAggregateCombinator.Suffix.IF, List.of(c)),
                        new ClickHouseAggregateCombinator(ClickHouseAggregateCombinator.Suffix.ARRAY)));
        String rendered = ClickHouseVisitor.asString(wrap(agg));
        assertTrue(rendered.contains("sumDistinctIfArray(t.x, t.c)"), "got: " + rendered);
    }

    @Test
    void resampleEmitsThreePositionalArgs() {
        ClickHouseColumnReference x = column("t", "x");
        ClickHouseConstant key = new ClickHouseInt8Constant(1);
        ClickHouseConstant from = new ClickHouseInt8Constant(0);
        ClickHouseConstant to = new ClickHouseInt8Constant(10);
        ClickHouseAggregate agg = new ClickHouseAggregate(x, ClickHouseAggregate.ClickHouseAggregateFunction.SUM,
                Arrays.asList(new ClickHouseAggregateCombinator(ClickHouseAggregateCombinator.Suffix.RESAMPLE,
                        Arrays.asList(key, from, to))));
        String rendered = ClickHouseVisitor.asString(wrap(agg));
        assertEquals("SELECT sumResample(t.x, 1, 0, 10)", rendered);
    }

    @Test
    void chainAccessorReturnsConstructorList() {
        ClickHouseAggregate agg = new ClickHouseAggregate(column("t", "x"),
                ClickHouseAggregate.ClickHouseAggregateFunction.SUM,
                Arrays.asList(new ClickHouseAggregateCombinator(ClickHouseAggregateCombinator.Suffix.IF,
                        Arrays.asList(column("t", "c")))));
        assertEquals(1, agg.getChain().size());
        assertEquals(ClickHouseAggregateCombinator.Suffix.IF, agg.getChain().get(0).getSuffix());
        assertEquals(1, agg.getChain().get(0).getExtraArgs().size());
    }

    @Test
    void plainConstructorYieldsEmptyChain() {
        ClickHouseAggregate plain = new ClickHouseAggregate(column("t", "x"),
                ClickHouseAggregate.ClickHouseAggregateFunction.MAX);
        assertTrue(plain.getChain().isEmpty());
        assertFalse(ClickHouseVisitor.asString(wrap(plain)).toLowerCase().contains("if("));
    }

    @Test
    void suffixRequiredArgCountMatchesGrammar() {

        assertEquals(1, ClickHouseAggregateCombinator.Suffix.IF.getRequiredArgCount());
        assertEquals(3, ClickHouseAggregateCombinator.Suffix.RESAMPLE.getRequiredArgCount());
        for (ClickHouseAggregateCombinator.Suffix s : ClickHouseAggregateCombinator.Suffix.values()) {
            if (s != ClickHouseAggregateCombinator.Suffix.IF && s != ClickHouseAggregateCombinator.Suffix.RESAMPLE) {
                assertEquals(0, s.getRequiredArgCount(), "expected zero extra args for suffix " + s);
            }
        }
    }
}
