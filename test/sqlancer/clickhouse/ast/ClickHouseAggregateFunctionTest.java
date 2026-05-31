package sqlancer.clickhouse.ast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import sqlancer.clickhouse.ClickHouseSchema;
import sqlancer.clickhouse.ClickHouseVisitor;
import sqlancer.clickhouse.ast.ClickHouseAggregate.ClickHouseAggregateFunction;
import sqlancer.common.schema.TableIndex;

/**
 * Unit 3.1 coverage: the widened aggregate-function catalog renders correctly (single- and two-argument forms) and the
 * random-draw helpers preserve the soundness contract -- {@code getRandom()} stays restricted to the historical five,
 * and {@code getRandomScalar()} only ever returns single-argument, multiset-safe functions.
 */
class ClickHouseAggregateFunctionTest {

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
    void newSingleArgAggregatesRenderWithCamelCaseNames() {
        assertEquals("SELECT uniqExact(t.x)",
                ClickHouseVisitor.asString(wrap(new ClickHouseAggregate(column("t", "x"),
                        ClickHouseAggregateFunction.UNIQ_EXACT))));
        assertEquals("SELECT quantileExact(t.x)",
                ClickHouseVisitor.asString(wrap(new ClickHouseAggregate(column("t", "x"),
                        ClickHouseAggregateFunction.QUANTILE_EXACT))));
        assertEquals("SELECT groupBitAnd(t.x)",
                ClickHouseVisitor.asString(wrap(new ClickHouseAggregate(column("t", "x"),
                        ClickHouseAggregateFunction.GROUP_BIT_AND))));
        assertEquals("SELECT groupBitXor(t.x)",
                ClickHouseVisitor.asString(wrap(new ClickHouseAggregate(column("t", "x"),
                        ClickHouseAggregateFunction.GROUP_BIT_XOR))));
    }

    @Test
    void baseAggregatesStillRenderUpperCase() {
        assertEquals("SELECT SUM(t.x)", ClickHouseVisitor
                .asString(wrap(new ClickHouseAggregate(column("t", "x"), ClickHouseAggregateFunction.SUM))));
        assertEquals("SELECT COUNT(t.x)", ClickHouseVisitor
                .asString(wrap(new ClickHouseAggregate(column("t", "x"), ClickHouseAggregateFunction.COUNT))));
    }

    @Test
    void twoArgArgMinArgMaxRenderBothValueArgs() {
        ClickHouseAggregate argMin = new ClickHouseAggregate(column("t", "v"), ClickHouseAggregateFunction.ARG_MIN,
                Arrays.asList((ClickHouseExpression) column("t", "k")), Collections.emptyList());
        assertEquals("SELECT argMin(t.v, t.k)", ClickHouseVisitor.asString(wrap(argMin)));

        ClickHouseAggregate argMax = new ClickHouseAggregate(column("t", "v"), ClickHouseAggregateFunction.ARG_MAX,
                Arrays.asList((ClickHouseExpression) column("t", "k")), Collections.emptyList());
        assertEquals("SELECT argMax(t.v, t.k)", ClickHouseVisitor.asString(wrap(argMax)));
    }

    @Test
    void chainedCombinatorLowerCasesBaseAndKeepsSuffix() {
        ClickHouseAggregate uniqExactIf = new ClickHouseAggregate(column("t", "x"),
                ClickHouseAggregateFunction.UNIQ_EXACT,
                Arrays.asList(new ClickHouseAggregateCombinator(ClickHouseAggregateCombinator.Suffix.IF,
                        Arrays.asList(column("t", "c")))));
        assertEquals("SELECT uniqexactIf(t.x, t.c)", ClickHouseVisitor.asString(wrap(uniqExactIf)));
    }

    @Test
    void getRandomStaysRestrictedToHistoricalFive() {
        List<ClickHouseAggregateFunction> base = Arrays.asList(ClickHouseAggregateFunction.AVG,
                ClickHouseAggregateFunction.COUNT, ClickHouseAggregateFunction.MAX, ClickHouseAggregateFunction.MIN,
                ClickHouseAggregateFunction.SUM);
        for (int i = 0; i < 500; i++) {
            assertTrue(base.contains(ClickHouseAggregateFunction.getRandom()));
        }
    }

    @Test
    void getRandomScalarIsAlwaysSingleArgAndMultisetSafe() {
        for (int i = 0; i < 500; i++) {
            ClickHouseAggregateFunction f = ClickHouseAggregateFunction.getRandomScalar();
            assertEquals(1, f.getNumValueArgs());
            assertTrue(f.isMultisetSafe());
        }
    }
}
