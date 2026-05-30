package sqlancer.clickhouse.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.clickhouse.data.ClickHouseDataType;

import org.junit.jupiter.api.Test;

import sqlancer.clickhouse.ClickHouseSchema.ClickHouseColumn;
import sqlancer.clickhouse.ClickHouseSchema.ClickHouseLancerDataType;
import sqlancer.clickhouse.ast.ClickHouseBinaryArithmeticOperation;
import sqlancer.clickhouse.ast.ClickHouseBinaryArithmeticOperation.ClickHouseBinaryArithmeticOperator;
import sqlancer.clickhouse.ast.ClickHouseColumnReference;
import sqlancer.clickhouse.ast.ClickHouseExpression;
import sqlancer.clickhouse.ast.constant.ClickHouseCreateConstant;

class ClickHouseTableGeneratorTest {

    private static ClickHouseColumnReference column(ClickHouseDataType type) {
        return new ClickHouseColumnReference(
                new ClickHouseColumn("c", new ClickHouseLancerDataType(type), false, false, null), null, null);
    }

    private static ClickHouseColumn col(ClickHouseDataType type) {
        return new ClickHouseColumn("c", new ClickHouseLancerDataType(type), false, false, null);
    }

    @Test
    void orderByRejectsPureConstants() {
        ClickHouseExpression onlyConstants = new ClickHouseBinaryArithmeticOperation(
                ClickHouseCreateConstant.createInt32Constant(1L), ClickHouseCreateConstant.createInt32Constant(2L),
                ClickHouseBinaryArithmeticOperator.ADD);
        assertFalse(ClickHouseTableGenerator.isValidOrderBy(onlyConstants));
    }

    @Test
    void orderByAcceptsColumnExpression() {
        ClickHouseExpression colExpr = new ClickHouseBinaryArithmeticOperation(column(ClickHouseDataType.Int32),
                ClickHouseCreateConstant.createInt32Constant(2L), ClickHouseBinaryArithmeticOperator.ADD);
        assertTrue(ClickHouseTableGenerator.isValidOrderBy(colExpr));
    }

    @Test
    void partitionByRejectsFloatColumn() {
        ClickHouseExpression floatExpr = column(ClickHouseDataType.Float64);
        assertFalse(ClickHouseTableGenerator.isValidPartitionBy(floatExpr));
    }

    @Test
    void partitionByAcceptsIntColumn() {
        ClickHouseExpression intExpr = column(ClickHouseDataType.Int32);
        assertTrue(ClickHouseTableGenerator.isValidPartitionBy(intExpr));
    }

    @Test
    void partitionByRejectsExpressionContainingFloatColumn() {
        ClickHouseExpression mixed = new ClickHouseBinaryArithmeticOperation(column(ClickHouseDataType.Int32),
                column(ClickHouseDataType.Float32), ClickHouseBinaryArithmeticOperator.ADD);
        assertFalse(ClickHouseTableGenerator.isValidPartitionBy(mixed));
    }

    @Test
    void sampleByRequiresColumn() {
        assertFalse(ClickHouseTableGenerator.isValidSampleBy(ClickHouseCreateConstant.createInt32Constant(1L)));
        assertTrue(ClickHouseTableGenerator.isValidSampleBy(column(ClickHouseDataType.Int32)));
    }

    // Unit 1.3: PRIMARY-KEY-prefix helpers.

    @Test
    void pickDistinctReturnsRequestedCountDistinctInPool() {
        List<String> src = Arrays.asList("a", "b", "c", "d", "e");
        for (int trial = 0; trial < 500; trial++) {
            List<String> got = ClickHouseTableGenerator.pickDistinct(src, 3);
            assertEquals(3, got.size());
            Set<String> unique = new HashSet<>(got);
            assertEquals(got.size(), unique.size(), () -> "pickDistinct returned duplicates: " + got);
            assertTrue(src.containsAll(got), () -> "pickDistinct returned out-of-pool element: " + got);
        }
    }

    @Test
    void pickDistinctCapsAtPoolSizeAndDoesNotMutateSource() {
        List<String> src = Arrays.asList("a", "b");
        List<String> got = ClickHouseTableGenerator.pickDistinct(src, 5);
        assertEquals(2, got.size());
        assertEquals(2, new HashSet<>(got).size());
        assertEquals(Arrays.asList("a", "b"), src);
    }

    @Test
    void isBareKeyColumnAcceptsScalarsRejectsComposite() {
        assertTrue(ClickHouseTableGenerator.isBareKeyColumn(col(ClickHouseDataType.Int32)));
        assertTrue(ClickHouseTableGenerator.isBareKeyColumn(col(ClickHouseDataType.String)));
        assertTrue(ClickHouseTableGenerator.isBareKeyColumn(col(ClickHouseDataType.Date32)));
        assertTrue(ClickHouseTableGenerator.isBareKeyColumn(col(ClickHouseDataType.UUID)));
        // Array maps to no Kind -> Unknown type term -> not a usable bare key.
        assertFalse(ClickHouseTableGenerator.isBareKeyColumn(col(ClickHouseDataType.Array)));
    }

    // Unit 2.1: CollapsingMergeTree sign column must be exactly Int8.

    @Test
    void isValidSignAcceptsOnlyInt8() {
        assertTrue(ClickHouseTableGenerator.isValidSign(col(ClickHouseDataType.Int8)));
        assertFalse(ClickHouseTableGenerator.isValidSign(col(ClickHouseDataType.Int16)));
        assertFalse(ClickHouseTableGenerator.isValidSign(col(ClickHouseDataType.UInt8)));
        assertFalse(ClickHouseTableGenerator.isValidSign(col(ClickHouseDataType.Int32)));
    }
}
