package sqlancer.clickhouse.gen;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.clickhouse.data.ClickHouseDataType;

import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.clickhouse.ClickHouseProvider.ClickHouseGlobalState;
import sqlancer.clickhouse.ClickHouseSchema;
import sqlancer.clickhouse.ClickHouseSchema.ClickHouseColumn;
import sqlancer.clickhouse.ClickHouseSchema.ClickHouseLancerDataType;
import sqlancer.clickhouse.ClickHouseSchema.ClickHouseTable;
import sqlancer.clickhouse.ClickHouseType;
import sqlancer.clickhouse.ClickHouseType.Kind;
import sqlancer.clickhouse.ClickHouseType.LowCardinality;
import sqlancer.clickhouse.ClickHouseType.Nullable;
import sqlancer.clickhouse.ClickHouseType.Primitive;
import sqlancer.clickhouse.ClickHouseType.Unknown;
import sqlancer.clickhouse.ast.ClickHouseAggregate;
import sqlancer.clickhouse.ast.ClickHouseAggregate.ClickHouseAggregateFunction;
import sqlancer.clickhouse.ast.ClickHouseAggregateCombinator;
import sqlancer.clickhouse.ast.ClickHouseAliasOperation;
import sqlancer.clickhouse.ast.ClickHouseBinaryArithmeticOperation;
import sqlancer.clickhouse.ast.ClickHouseBinaryComparisonOperation;
import sqlancer.clickhouse.ast.ClickHouseBinaryFunctionOperation;
import sqlancer.clickhouse.ast.ClickHouseBinaryLogicalOperation;
import sqlancer.clickhouse.ast.ClickHouseColumnReference;
import sqlancer.clickhouse.ast.ClickHouseExpression;
import sqlancer.clickhouse.ast.ClickHouseExpression.ClickHouseJoin;
import sqlancer.clickhouse.ast.ClickHouseSelect;
import sqlancer.clickhouse.ast.ClickHouseTableReference;
import sqlancer.clickhouse.ast.ClickHouseUnaryFunctionOperation;
import sqlancer.clickhouse.ast.ClickHouseUnaryPostfixOperation;
import sqlancer.clickhouse.ast.ClickHouseUnaryPostfixOperation.ClickHouseUnaryPostfixOperator;
import sqlancer.clickhouse.ast.ClickHouseUnaryPrefixOperation;
import sqlancer.clickhouse.ast.ClickHouseUnaryPrefixOperation.ClickHouseUnaryPrefixOperator;
import sqlancer.clickhouse.ast.constant.ClickHouseCreateConstant;
import sqlancer.common.gen.NoRECGenerator;
import sqlancer.common.gen.TLPWhereGenerator;
import sqlancer.common.gen.TypedExpressionGenerator;
import sqlancer.common.schema.AbstractTables;

public class ClickHouseExpressionGenerator
        extends TypedExpressionGenerator<ClickHouseExpression, ClickHouseColumn, ClickHouseLancerDataType> implements
        NoRECGenerator<ClickHouseSelect, ClickHouseJoin, ClickHouseExpression, ClickHouseTable, ClickHouseColumn>,
        TLPWhereGenerator<ClickHouseSelect, ClickHouseJoin, ClickHouseExpression, ClickHouseTable, ClickHouseColumn> {

    private final ClickHouseGlobalState globalState;
    public boolean allowAggregateFunctions;
    private boolean allowNullLiterals;

    private List<ClickHouseTable> tables;
    private final List<ClickHouseColumnReference> columnRefs;

    public ClickHouseExpressionGenerator(ClickHouseGlobalState globalState) {
        this.globalState = globalState;
        this.columnRefs = new ArrayList<>();
    }

    public final void addColumns(List<ClickHouseColumnReference> col) {
        this.columnRefs.addAll(col);
    }

    public ClickHouseExpressionGenerator allowNullLiterals(boolean value) {
        this.allowNullLiterals = value;
        return this;
    }

    private enum ColumnLike {
        UNARY_PREFIX, BINARY_ARITHMETIC, UNARY_FUNCTION, BINARY_FUNCTION
    }

    private enum Expression {
        UNARY_PREFIX, BINARY_ARITHMETIC, UNARY_FUNCTION, BINARY_FUNCTION, BINARY_LOGICAL, BINARY_COMPARISON,
        UNARY_POSTFIX
    }

    public ClickHouseExpression generateExpressionWithColumns(List<ClickHouseColumnReference> columns,
            int remainingDepth) {
        return generateNumericExpressionWithColumns(numericColumns(columns), remainingDepth);
    }

    // Every operator in the ColumnLike family (UNARY_PREFIX=MINUS, UNARY_FUNCTION, BINARY_ARITHMETIC,
    // BINARY_FUNCTION) requires numeric operands. Feeding a String column to e.g. cos() trips
    // ILLEGAL_TYPE_OF_ARGUMENT at parse time and bumps the unsuccessful-statement count without
    // ever exercising the oracle. Filter to numeric columns up front and fall back to a numeric
    // constant when the table has only non-numeric columns.
    private ClickHouseExpression generateNumericExpressionWithColumns(List<ClickHouseColumnReference> columns,
            int remainingDepth) {
        if (columns.isEmpty() || remainingDepth <= 2 && Randomly.getBooleanWithRatherLowProbability()) {
            if (allowNullLiterals && Randomly.getBooleanWithSmallProbability()) {
                return ClickHouseCreateConstant.createNullConstant();
            }
            return generateConstant(new ClickHouseLancerDataType(ClickHouseDataType.Int32));
        }

        if (remainingDepth <= 2 || Randomly.getBooleanWithRatherLowProbability()) {
            return columns.get((int) Randomly.getNotCachedInteger(0, columns.size()));
        }

        ColumnLike expr = Randomly.fromOptions(ColumnLike.values());
        switch (expr) {
        case UNARY_PREFIX:
            return new ClickHouseUnaryPrefixOperation(generateNumericExpressionWithColumns(columns, remainingDepth - 1),
                    ClickHouseUnaryPrefixOperator.MINUS);
        case BINARY_ARITHMETIC:
            return new ClickHouseBinaryArithmeticOperation(
                    generateNumericExpressionWithColumns(columns, remainingDepth - 1),
                    generateNumericExpressionWithColumns(columns, remainingDepth - 1),
                    ClickHouseBinaryArithmeticOperation.ClickHouseBinaryArithmeticOperator.getRandom());
        case UNARY_FUNCTION:
            return new ClickHouseUnaryFunctionOperation(
                    generateNumericExpressionWithColumns(columns, remainingDepth - 1),
                    ClickHouseUnaryFunctionOperation.ClickHouseUnaryFunctionOperator.getRandom());
        case BINARY_FUNCTION:
            // intDiv/gcd/lcm require integer operands and ClickHouse promotes most math wrappers
            // (sin, cos, sqrt, log, ...) to Float64, so a recursive descent over numeric columns
            // routinely surfaces gcd(Float, Int) -- ILLEGAL_TYPE_OF_ARGUMENT. Take the integer-only
            // branch with plain column-reference leaves to keep the expression integer-typed end to
            // end; otherwise use the any-numeric sub-pool (max2/min2/pow) which tolerates Float.
            List<ClickHouseColumnReference> integers = integerColumns(columns);
            boolean useIntegerOnly = !integers.isEmpty() && Randomly.getBoolean();
            if (useIntegerOnly) {
                ClickHouseBinaryFunctionOperation.ClickHouseBinaryFunctionOperator intOp = Randomly.fromOptions(
                        ClickHouseBinaryFunctionOperation.ClickHouseBinaryFunctionOperator.INT_DIV,
                        ClickHouseBinaryFunctionOperation.ClickHouseBinaryFunctionOperator.GCD,
                        ClickHouseBinaryFunctionOperation.ClickHouseBinaryFunctionOperator.LCM);
                return new ClickHouseBinaryFunctionOperation(
                        integers.get((int) Randomly.getNotCachedInteger(0, integers.size())),
                        integers.get((int) Randomly.getNotCachedInteger(0, integers.size())), intOp);
            }
            return new ClickHouseBinaryFunctionOperation(
                    generateNumericExpressionWithColumns(columns, remainingDepth - 1),
                    generateNumericExpressionWithColumns(columns, remainingDepth - 1),
                    ClickHouseBinaryFunctionOperation.ClickHouseBinaryFunctionOperator.getRandomAnyNumeric());
        default:
            throw new AssertionError(expr);
        }
    }

    public ClickHouseExpression generateAggregateExpressionWithColumns(List<ClickHouseColumnReference> columns,
            int remainingDepth) {
        List<ClickHouseColumnReference> numeric = numericColumns(columns);
        if (Randomly.getBooleanWithRatherLowProbability()) {
            ClickHouseAggregate.ClickHouseAggregateFunction func = ClickHouseAggregate.ClickHouseAggregateFunction
                    .getRandom();
            ClickHouseExpression argExpr = generateNumericExpressionWithColumns(numeric, remainingDepth - 1);
            List<ClickHouseAggregateCombinator> chain = maybeGenerateCombinatorChain(columns, remainingDepth);
            return new ClickHouseAggregate(argExpr, func, chain);
        }
        return generateNumericExpressionWithColumns(numeric, remainingDepth);
    }

    // If --test-aggregate-combinators is on, roll a low-probability decision to attach a combinator
    // chain to the aggregate; otherwise return an empty list (plain aggregate). Chain length 1-3
    // with descending probability. Per-suffix extra args follow ClickHouse's grammar: -If takes one
    // boolean expression, -Resample takes three integer expressions, all other suffixes take none.
    // Type-level validity of the suffix on the chosen aggregate is deliberately not pre-validated
    // -- the error catalog absorbs the rejection cases.
    private List<ClickHouseAggregateCombinator> maybeGenerateCombinatorChain(List<ClickHouseColumnReference> columns,
            int remainingDepth) {
        if (!globalState.getClickHouseOptions().enableCombinators) {
            return java.util.Collections.emptyList();
        }
        if (!Randomly.getBooleanWithRatherLowProbability()) {
            return java.util.Collections.emptyList();
        }
        int length;
        double roll = Randomly.getNotCachedInteger(0, 1000) / 1000.0;
        if (roll < 0.60) {
            length = 1;
        } else if (roll < 0.90) {
            length = 2;
        } else {
            length = 3;
        }
        List<ClickHouseAggregateCombinator> chain = new ArrayList<>(length);
        // Suspend aggregate-function generation while building extra args -- nested aggregates inside
        // combinator extra args (e.g., If(sum(x) > 0)) are not what we want at this level.
        boolean savedAllow = this.allowAggregateFunctions;
        this.allowAggregateFunctions = false;
        try {
            for (int i = 0; i < length; i++) {
                ClickHouseAggregateCombinator.Suffix suffix = pickCombinatorSuffix();
                List<ClickHouseExpression> args = generateExtraArgsForSuffix(suffix, columns,
                        Math.max(1, remainingDepth - 1));
                chain.add(new ClickHouseAggregateCombinator(suffix, args));
            }
        } finally {
            this.allowAggregateFunctions = savedAllow;
        }
        return chain;
    }

    private static ClickHouseAggregateCombinator.Suffix pickCombinatorSuffix() {
        // Weights from plan Unit 4: IF and OR_NULL most common; STATE/MERGE/ARRAY less frequent.
        // Tuned empirically once the error catalog stabilises.
        int[] weights = { 30, 20, 10, 15, 5, 5, 5, 3, 3, 4 };
        ClickHouseAggregateCombinator.Suffix[] suffixes = ClickHouseAggregateCombinator.Suffix.values();
        int total = 0;
        for (int w : weights) {
            total += w;
        }
        int pick = (int) Randomly.getNotCachedInteger(0, total);
        int acc = 0;
        for (int i = 0; i < suffixes.length; i++) {
            acc += weights[i];
            if (pick < acc) {
                return suffixes[i];
            }
        }
        return suffixes[suffixes.length - 1];
    }

    private List<ClickHouseExpression> generateExtraArgsForSuffix(ClickHouseAggregateCombinator.Suffix suffix,
            List<ClickHouseColumnReference> columns, int remainingDepth) {
        switch (suffix) {
        case IF:
            // One boolean condition. Reuse the generic expression generator -- the planner will
            // coerce non-boolean expressions to UInt8 where it can; otherwise the error catalog absorbs.
            return List.of(generateExpressionWithColumns(columns, remainingDepth));
        case RESAMPLE:
            // Three integer positional args (key, from, to). Literal integers keep the surface
            // syntactically well-formed; v2 may pick column references for the `key` slot.
            return List.of(generateConstant(new ClickHouseLancerDataType(ClickHouseDataType.Int32)),
                    generateConstant(new ClickHouseLancerDataType(ClickHouseDataType.Int32)),
                    generateConstant(new ClickHouseLancerDataType(ClickHouseDataType.Int32)));
        default:
            return java.util.Collections.emptyList();
        }
    }

    // Returns the subset of `cols` whose root type is numeric (Int*/UInt*/Float*). The check uses
    // ClickHouseLancerDataType.getType(), which already unwraps Nullable and LowCardinality.
    private static List<ClickHouseColumnReference> numericColumns(List<ClickHouseColumnReference> cols) {
        return cols.stream().filter(c -> isNumeric(c.getColumn().getType().getType())).collect(Collectors.toList());
    }

    // Subset restricted to integer types -- needed for intDiv/gcd/lcm which reject Float.
    private static List<ClickHouseColumnReference> integerColumns(List<ClickHouseColumnReference> cols) {
        return cols.stream().filter(c -> isInteger(c.getColumn().getType().getType())).collect(Collectors.toList());
    }

    private static boolean isNumeric(ClickHouseDataType type) {
        return isInteger(type) || type == ClickHouseDataType.Float32 || type == ClickHouseDataType.Float64;
    }

    private static boolean isInteger(ClickHouseDataType type) {
        switch (type) {
        case Int8:
        case Int16:
        case Int32:
        case Int64:
        case Int128:
        case Int256:
        case UInt8:
        case UInt16:
        case UInt32:
        case UInt64:
        case UInt128:
        case UInt256:
            return true;
        default:
            return false;
        }
    }

    public ClickHouseExpression generateExpressionWithExpression(List<ClickHouseExpression> expression,
            int remainingDepth) {
        if (remainingDepth <= 2 || Randomly.getBooleanWithRatherLowProbability()) {
            if (Randomly.getBoolean()) {
                return expression.get((int) Randomly.getNotCachedInteger(0, expression.size()));
            } else {
                return generateConstant(null);
            }
        }

        Expression type = Randomly.fromOptions(Expression.values());
        switch (type) {
        case UNARY_PREFIX:
            return new ClickHouseUnaryPrefixOperation(generateExpressionWithExpression(expression, remainingDepth - 1),
                    ClickHouseUnaryPrefixOperation.ClickHouseUnaryPrefixOperator.getRandom());
        case UNARY_POSTFIX:
            return new ClickHouseUnaryPostfixOperation(generateExpressionWithExpression(expression, remainingDepth - 1),
                    ClickHouseUnaryPostfixOperation.ClickHouseUnaryPostfixOperator.getRandom(), false);
        case BINARY_COMPARISON:
            return new ClickHouseBinaryComparisonOperation(
                    generateExpressionWithExpression(expression, remainingDepth - 1),
                    generateExpressionWithExpression(expression, remainingDepth - 1),
                    ClickHouseBinaryComparisonOperation.ClickHouseBinaryComparisonOperator.getRandomOperator());
        case BINARY_LOGICAL:
            return new ClickHouseBinaryLogicalOperation(
                    generateExpressionWithExpression(expression, remainingDepth - 1),
                    generateExpressionWithExpression(expression, remainingDepth - 1),
                    ClickHouseBinaryLogicalOperation.ClickHouseBinaryLogicalOperator.getRandom());
        case BINARY_ARITHMETIC:
            return new ClickHouseBinaryArithmeticOperation(
                    generateExpressionWithExpression(expression, remainingDepth - 1),
                    generateExpressionWithExpression(expression, remainingDepth - 1),
                    ClickHouseBinaryArithmeticOperation.ClickHouseBinaryArithmeticOperator.getRandom());
        case UNARY_FUNCTION:
            return new ClickHouseUnaryFunctionOperation(
                    generateExpressionWithExpression(expression, remainingDepth - 1),
                    ClickHouseUnaryFunctionOperation.ClickHouseUnaryFunctionOperator.getRandom());
        case BINARY_FUNCTION:
            // Leaves here are pre-built expressions of unknown root type (typically aggregate
            // results, often Float). Restrict to any-numeric ops -- gcd/lcm/intDiv would reject Float.
            return new ClickHouseBinaryFunctionOperation(
                    generateExpressionWithExpression(expression, remainingDepth - 1),
                    generateExpressionWithExpression(expression, remainingDepth - 1),
                    ClickHouseBinaryFunctionOperation.ClickHouseBinaryFunctionOperator.getRandomAnyNumeric());
        default:
            throw new AssertionError(type);
        }
    }

    @Override
    protected ClickHouseExpression generateExpression(ClickHouseLancerDataType type, int depth) {
        if (allowAggregateFunctions && Randomly.getBooleanWithRatherLowProbability()) {
            ClickHouseLancerDataType aggType = ClickHouseLancerDataType.getRandom();
            ClickHouseExpression aggArg = generateExpression(aggType, depth + 1);
            ClickHouseAggregate.ClickHouseAggregateFunction func = ClickHouseAggregate.ClickHouseAggregateFunction
                    .getRandom();
            List<ClickHouseAggregateCombinator> chain = maybeGenerateCombinatorChain(columnRefs,
                    Math.max(1, globalState.getOptions().getMaxExpressionDepth() - depth));
            return new ClickHouseAggregate(aggArg, func, chain);
        }
        if (depth >= globalState.getOptions().getMaxExpressionDepth()
                || Randomly.getBooleanWithRatherLowProbability()) {
            return generateLeafNode(type);
        }
        Expression expr = Randomly.fromOptions(Expression.values());
        ClickHouseLancerDataType leftLeafType = ClickHouseLancerDataType.getRandom();
        // Binary operators want same-typed leaves: Int32-vs-String comparisons trip TYPE_MISMATCH
        // (Code 53) and arithmetic on String trips ILLEGAL_TYPE_OF_ARGUMENT (Code 43). The previous
        // code drew rightLeafType independently and only forced equality with low probability --
        // inverted from the right default. Keep a small chance of mixed types so cross-width Int
        // comparisons are still exercised when the type pool grows beyond {Int32, String}.
        ClickHouseLancerDataType rightLeafType = Randomly.getBooleanWithRatherLowProbability()
                ? ClickHouseLancerDataType.getRandom() : leftLeafType;

        switch (expr) {
        case UNARY_PREFIX:
            return new ClickHouseUnaryPrefixOperation(generateExpression(leftLeafType, depth + 1),
                    ClickHouseUnaryPrefixOperation.ClickHouseUnaryPrefixOperator.getRandom());
        case UNARY_POSTFIX:
            return new ClickHouseUnaryPostfixOperation(generateExpression(leftLeafType, depth + 1),
                    ClickHouseUnaryPostfixOperation.ClickHouseUnaryPostfixOperator.getRandom(), false);
        case BINARY_COMPARISON:
            return new ClickHouseBinaryComparisonOperation(generateExpression(leftLeafType, depth + 1),
                    generateExpression(rightLeafType, depth + 1),
                    ClickHouseBinaryComparisonOperation.ClickHouseBinaryComparisonOperator.getRandomOperator());
        case BINARY_LOGICAL:
            return new ClickHouseBinaryLogicalOperation(generateExpression(leftLeafType, depth + 1),
                    generateExpression(rightLeafType, depth + 1),
                    ClickHouseBinaryLogicalOperation.ClickHouseBinaryLogicalOperator.getRandom());
        case BINARY_ARITHMETIC:
            return new ClickHouseBinaryArithmeticOperation(generateExpression(leftLeafType, depth + 1),
                    generateExpression(leftLeafType, depth + 1),
                    ClickHouseBinaryArithmeticOperation.ClickHouseBinaryArithmeticOperator.getRandom());
        case UNARY_FUNCTION:
            return new ClickHouseUnaryFunctionOperation(generateExpression(leftLeafType, depth + 1),
                    ClickHouseUnaryFunctionOperation.ClickHouseUnaryFunctionOperator.getRandom());
        case BINARY_FUNCTION:
            return new ClickHouseBinaryFunctionOperation(generateExpression(leftLeafType, depth + 1),
                    generateExpression(leftLeafType, depth + 1),
                    ClickHouseBinaryFunctionOperation.ClickHouseBinaryFunctionOperator.getRandom());
        default:
            throw new AssertionError(expr);
        }
    }

    protected ClickHouseExpression.ClickHouseJoinOnClause generateJoinClause(ClickHouseTableReference leftTable,
            ClickHouseTableReference rightTable) {
        List<ClickHouseColumnReference> leftColumns = leftTable.getColumnReferences();
        List<ClickHouseColumnReference> rightColumns = rightTable.getColumnReferences();
        // Build a viable (leftKey, rightKey) pair. ClickHouse needs same-typed (or
        // implicitly-coercible) join keys -- mixed String/Int trips Code 386 NO_COMMON_TYPE and
        // complex non-column expressions trip Code 403 INVALID_JOIN_ON_EXPRESSION. First try a
        // same-type pair, then an Int<->Float coercion pair, only then fall back to any pair.
        List<ClickHouseColumnReference[]> sameType = new ArrayList<>();
        List<ClickHouseColumnReference[]> numericPairs = new ArrayList<>();
        for (ClickHouseColumnReference l : leftColumns) {
            ClickHouseDataType lt = l.getColumn().getType().getType();
            for (ClickHouseColumnReference r : rightColumns) {
                ClickHouseDataType rt = r.getColumn().getType().getType();
                if (lt == rt) {
                    sameType.add(new ClickHouseColumnReference[] { l, r });
                } else if (isNumeric(lt) && isNumeric(rt)) {
                    numericPairs.add(new ClickHouseColumnReference[] { l, r });
                }
            }
        }
        List<ClickHouseColumnReference[]> pool = !sameType.isEmpty() ? sameType
                : !numericPairs.isEmpty() ? numericPairs : null;
        if (pool == null) {
            // The two tables share no compatible key combination. Any pair we emit would trip
            // Code 386 NO_COMMON_TYPE; skip the iteration rather than burn a server roundtrip.
            throw new IgnoreMeException();
        }
        ClickHouseColumnReference[] pair = Randomly.fromList(pool);
        return new ClickHouseExpression.ClickHouseJoinOnClause(pair[0], pair[1]);
    }

    @Override
    protected ClickHouseExpression generateColumn(ClickHouseLancerDataType type) {
        if (type.getTypeTerm() instanceof Unknown) {
            throw new IgnoreMeException();
        }
        if (columnRefs.isEmpty()) {
            return generateConstant(type);
        }
        List<ClickHouseColumnReference> filteredColumns = columnRefs.stream()
                .filter(c -> c.getColumn().getType().getType().name().equals(type.getType().name()))
                .collect(Collectors.toList());
        return filteredColumns.isEmpty() ? Randomly.fromList(columnRefs) : Randomly.fromList(filteredColumns);
    }

    protected ClickHouseExpression getColumnNameFromTable(ClickHouseSchema.ClickHouseTable table) {
        if (columnRefs.isEmpty()) {
            return generateConstant(ClickHouseLancerDataType.getRandom());
        }
        List<ClickHouseColumnReference> filteredColumns = columnRefs.stream()
                .filter(c -> c.getColumn().getTable() == table).collect(Collectors.toList());
        if (filteredColumns.isEmpty()) {
            return generateConstant(ClickHouseLancerDataType.getRandom());
        }
        return Randomly.fromList(filteredColumns);
    }

    @Override
    protected ClickHouseLancerDataType getRandomType() {
        return ClickHouseLancerDataType.getRandom();
    }

    public List<ClickHouseExpression.ClickHouseJoin> getRandomJoinClauses(ClickHouseTableReference left,
            List<ClickHouseSchema.ClickHouseTable> tables) {
        List<ClickHouseExpression.ClickHouseJoin> joinStatements = new ArrayList<>();
        if (!globalState.getDbmsSpecificOptions().testJoins) {
            return joinStatements;
        }
        List<ClickHouseTableReference> leftTables = new ArrayList<>();
        leftTables.add(left);
        if (Randomly.getBoolean() && !tables.isEmpty()) {
            int nrJoinClauses = (int) Randomly.getNotCachedInteger(0, tables.size());
            for (int i = 0; i < nrJoinClauses; i++) {
                ClickHouseTableReference leftTable = leftTables
                        .get((int) Randomly.getNotCachedInteger(0, leftTables.size()));
                ClickHouseTableReference rightTable = new ClickHouseTableReference(Randomly.fromList(tables),
                        "right_" + i);
                ClickHouseExpression.ClickHouseJoinOnClause joinClause = generateJoinClause(leftTable, rightTable);
                ClickHouseExpression.ClickHouseJoin.JoinType options = Randomly
                        .fromOptions(ClickHouseExpression.ClickHouseJoin.JoinType.values());
                ClickHouseExpression.ClickHouseJoin j = new ClickHouseExpression.ClickHouseJoin(leftTable, rightTable,
                        options, joinClause);
                joinStatements.add(j);
                leftTables.add(rightTable);
            }
        }
        return joinStatements;
    }

    @Override
    protected boolean canGenerateColumnOfType(ClickHouseLancerDataType type) {
        return true;
    }

    @Override
    public ClickHouseExpression generateConstant(ClickHouseLancerDataType genType) {
        ClickHouseLancerDataType type = (genType == null) ? ClickHouseLancerDataType.getRandom(globalState) : genType;
        return generateConstantFromTerm(type.getTypeTerm());
    }

    // Dispatch constant emission on the ADT term: Nullable emits a small-probability NULL else
    // recurses; LowCardinality is transparent at the literal level; Unknown abandons the statement
    // via IgnoreMeException -- the established escape hatch for unsupported types.
    private ClickHouseExpression generateConstantFromTerm(ClickHouseType term) {
        if (term instanceof Unknown) {
            throw new IgnoreMeException();
        }
        if (term instanceof Nullable n) {
            if (Randomly.getBooleanWithSmallProbability()) {
                return ClickHouseCreateConstant.createNullConstant();
            }
            return generateConstantFromTerm(n.inner());
        }
        if (term instanceof LowCardinality lc) {
            return generateConstantFromTerm(lc.inner());
        }
        if (term instanceof Primitive p) {
            return generatePrimitiveConstant(p.kind());
        }
        throw new IgnoreMeException();
    }

    private ClickHouseExpression generatePrimitiveConstant(Kind kind) {
        switch (kind) {
        case Int8:
        case Int16:
        case Int32:
        case Int64:
        case Int128:
        case Int256:
        case UInt8:
        case UInt16:
        case UInt32:
        case UInt64:
        case UInt128:
        case UInt256:
            return ClickHouseCreateConstant.createIntConstant(kind.toClickHouseDataType(),
                    globalState.getRandomly().getInteger());
        case Float32:
            return ClickHouseCreateConstant.createFloat32Constant((float) globalState.getRandomly().getDouble());
        case Float64:
            return ClickHouseCreateConstant.createFloat64Constant(globalState.getRandomly().getDouble());
        case String:
            return ClickHouseCreateConstant.createStringConstant(globalState.getRandomly().getString());
        case Bool:
            return ClickHouseCreateConstant.createBoolean(Randomly.getBoolean());
        case UUID:
        case Date:
        case Date32:
        case IPv4:
        case IPv6:
        default:
            // v1 generator doesn't pick these kinds; if encountered (e.g. via schema reflection of a
            // pre-existing table), skip the attempt rather than fabricating a literal here.
            throw new IgnoreMeException();
        }
    }

    public ClickHouseExpression getHavingClause() {
        return generateAggregate();
    }

    public ClickHouseAggregate generateArgsForAggregate(ClickHouseDataType dataType,
            ClickHouseAggregate.ClickHouseAggregateFunction agg) {
        ClickHouseDataType type = agg.getType(dataType);
        this.allowAggregateFunctions = false;
        ClickHouseExpression arg = generateExpression(new ClickHouseLancerDataType(type));
        this.allowAggregateFunctions = true;
        List<ClickHouseAggregateCombinator> chain = maybeGenerateCombinatorChain(columnRefs, 3);
        return new ClickHouseAggregate(arg, agg, chain);
    }

    public ClickHouseExpressionGenerator allowAggregates(boolean value) {
        allowAggregateFunctions = value;
        return this;
    }

    public ClickHouseExpression generateAggregate() {
        return generateAggregateExpressionWithColumns(columnRefs, 3);
    }

    @Override
    public ClickHouseExpression generatePredicate() {
        return generateExpressionWithColumns(columnRefs, 3);
    }

    @Override
    public ClickHouseExpression negatePredicate(ClickHouseExpression predicate) {
        return new ClickHouseUnaryPrefixOperation(predicate, ClickHouseUnaryPrefixOperator.NOT);
    }

    @Override
    public ClickHouseExpression isNull(ClickHouseExpression expr) {
        return new ClickHouseUnaryPostfixOperation(expr, ClickHouseUnaryPostfixOperator.IS_NULL, false);
    }

    @Override
    public ClickHouseExpressionGenerator setTablesAndColumns(AbstractTables<ClickHouseTable, ClickHouseColumn> tables) {
        this.tables = tables.getTables();
        this.columns = tables.getColumns();
        return this;
    }

    @Override
    public ClickHouseExpression generateBooleanExpression() {
        List<ClickHouseColumnReference> columnRefs = columns.stream()
                .map(c -> c.asColumnReference(c.getTable().getName())).collect(Collectors.toList());
        return generateExpressionWithColumns(columnRefs, 5);
    }

    @Override
    public ClickHouseSelect generateSelect() {
        return new ClickHouseSelect();
    }

    @Override
    public List<ClickHouseJoin> getRandomJoinClauses() {
        List<ClickHouseExpression.ClickHouseJoin> joinStatements = new ArrayList<>();
        if (globalState.getClickHouseOptions().testJoins && Randomly.getBoolean()) {
            return joinStatements;
        }
        List<ClickHouseTableReference> leftTables = new ArrayList<>();
        leftTables.add(new ClickHouseTableReference(tables.get(0), null));
        if (Randomly.getBoolean() && !tables.isEmpty()) {
            int nrJoinClauses = (int) Randomly.getNotCachedInteger(0, tables.size());
            for (int i = 0; i < nrJoinClauses; i++) {
                ClickHouseTableReference leftTable = leftTables
                        .get((int) Randomly.getNotCachedInteger(0, leftTables.size()));
                ClickHouseTableReference rightTable = new ClickHouseTableReference(Randomly.fromList(tables),
                        "right_" + i);
                ClickHouseExpression.ClickHouseJoinOnClause joinClause = generateJoinClause(leftTable, rightTable);
                ClickHouseExpression.ClickHouseJoin.JoinType options = Randomly
                        .fromOptions(ClickHouseExpression.ClickHouseJoin.JoinType.values());
                ClickHouseExpression.ClickHouseJoin j = new ClickHouseExpression.ClickHouseJoin(leftTable, rightTable,
                        options, joinClause);
                joinStatements.add(j);
                leftTables.add(rightTable);
            }
        }
        return joinStatements;
    }

    @Override
    public List<ClickHouseExpression> getTableRefs() {
        return tables.stream().map(t -> new ClickHouseTableReference(t, null)).collect(Collectors.toList());
    }

    @Override
    public String generateOptimizedQueryString(ClickHouseSelect select, ClickHouseExpression whereCondition,
            boolean shouldUseAggregate) {
        List<ClickHouseColumn> filteredColumns = Randomly.extractNrRandomColumns(columns,
                (int) Randomly.getNotCachedInteger(1, columns.size()));
        if (shouldUseAggregate) {
            ClickHouseAggregate aggr = new ClickHouseAggregate(
                    new ClickHouseColumnReference(ClickHouseColumn.createDummy("*", null), null, null),
                    ClickHouseAggregateFunction.COUNT);
            select.setFetchColumns(List.of(aggr));
        } else {
            select.setFetchColumns(filteredColumns.stream().map(c -> c.asColumnReference(c.getTable().getName()))
                    .collect(Collectors.toList()));
        }
        select.setWhereClause(whereCondition);

        return select.asString();
    }

    @Override
    public String generateUnoptimizedQueryString(ClickHouseSelect select, ClickHouseExpression whereCondition) {
        ClickHouseExpression inner = new ClickHouseAliasOperation(whereCondition, "check");

        select.setFetchColumns(List.of(inner));
        select.setWhereClause(null);
        return "SELECT SUM(check <> 0) FROM (" + select.asString() + ") as res";
    }

    @Override
    public List<ClickHouseExpression> generateFetchColumns(boolean shouldCreateDummy) {
        if (shouldCreateDummy) {
            return List.of(new ClickHouseColumnReference(ClickHouseColumn.createDummy("*", null), null, null));
        }
        List<ClickHouseColumnReference> columnReferences = columns.stream()
                .map(c -> c.asColumnReference(c.getTable().getName())).collect(Collectors.toList());
        return IntStream.range(0, 1 + Randomly.smallNumber())
                .mapToObj(i -> generateExpressionWithColumns(columnReferences, 5)).collect(Collectors.toList());
    }
}
