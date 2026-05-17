package sqlancer.clickhouse.gen;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
