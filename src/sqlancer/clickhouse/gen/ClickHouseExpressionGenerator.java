package sqlancer.clickhouse.gen;

import java.math.BigInteger;
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
import sqlancer.clickhouse.ClickHouseToStringVisitor;
import sqlancer.clickhouse.ClickHouseType;
import sqlancer.clickhouse.ClickHouseType.Array;
import sqlancer.clickhouse.ClickHouseType.DateTime64Type;
import sqlancer.clickhouse.ClickHouseType.Decimal;
import sqlancer.clickhouse.ClickHouseType.FixedString;
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
import sqlancer.clickhouse.ast.ClickHouseCastOperation;
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

    /**
     * Generate one access expression over a composite-typed column from {@code columns}:
     * tuple positional access (tup.1), map key access (m['k']), JSON path (j.a or j.a.^Int64),
     * Variant element (variantElement(v, 'Int32')), Dynamic element (dynamicElement(d, 'Int32')).
     * Returns null if no composite columns are in scope.
     *
     * <p>Workstreams 2 / 6 of the 2026-05-27 coverage expansion plan.
     */
    public ClickHouseExpression generateCompositeAccess(List<ClickHouseColumnReference> columns) {
        List<ClickHouseColumnReference> candidates = new java.util.ArrayList<>();
        for (ClickHouseColumnReference c : columns) {
            sqlancer.clickhouse.ClickHouseType t = c.getColumn().getType().getTypeTerm().unwrap();
            if (t instanceof sqlancer.clickhouse.ClickHouseType.Tuple
                    || t instanceof sqlancer.clickhouse.ClickHouseType.Map
                    || t instanceof sqlancer.clickhouse.ClickHouseType.JSON
                    || t instanceof sqlancer.clickhouse.ClickHouseType.Variant
                    || t instanceof sqlancer.clickhouse.ClickHouseType.Dynamic) {
                candidates.add(c);
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        ClickHouseColumnReference col = Randomly.fromList(candidates);
        sqlancer.clickhouse.ClickHouseType t = col.getColumn().getType().getTypeTerm().unwrap();
        if (t instanceof sqlancer.clickhouse.ClickHouseType.Tuple tup) {
            int idx = 1 + (int) Randomly.getNotCachedInteger(0, tup.elements().size());
            return new sqlancer.clickhouse.ast.ClickHouseTupleAccess(col, idx);
        }
        if (t instanceof sqlancer.clickhouse.ClickHouseType.Map m) {
            ClickHouseExpression keyExpr = generateConstantFromTerm(m.keyType());
            return new sqlancer.clickhouse.ast.ClickHouseMapAccess(col, keyExpr);
        }
        if (t instanceof sqlancer.clickhouse.ClickHouseType.JSON) {
            // Pick 1-2 path segments; the inserted JSON literal uses keys 'a' and 'b' so emit
            // those names. Optional type cast suffix selected at random.
            java.util.List<String> path = Randomly.fromOptions(
                    java.util.List.of("a"),
                    java.util.List.of("b"));
            String typeCast = Randomly.getBoolean() ? null : Randomly.fromOptions("Int64", "String");
            return new sqlancer.clickhouse.ast.ClickHouseJsonPath(col, path, typeCast);
        }
        if (t instanceof sqlancer.clickhouse.ClickHouseType.Variant v) {
            sqlancer.clickhouse.ClickHouseType alt = Randomly.fromList(v.alternatives());
            String typeName = alt.toString();
            return new sqlancer.clickhouse.ast.ClickHouseVariantElement(col, typeName, Randomly.getBoolean());
        }
        if (t instanceof sqlancer.clickhouse.ClickHouseType.Dynamic) {
            String typeName = Randomly.fromOptions("Int32", "String", "Int64", "Float64");
            return new sqlancer.clickhouse.ast.ClickHouseDynamicElement(col, typeName, Randomly.getBoolean());
        }
        return null;
    }

    /**
     * Generate a geo function call over a Point or Polygon column. Returns null if no geo column
     * is in scope. Workstream 4.
     */
    public ClickHouseExpression generateGeoCall(List<ClickHouseColumnReference> columns) {
        List<ClickHouseColumnReference> candidates = new java.util.ArrayList<>();
        for (ClickHouseColumnReference c : columns) {
            sqlancer.clickhouse.ClickHouseType t = c.getColumn().getType().getTypeTerm().unwrap();
            if (t instanceof sqlancer.clickhouse.ClickHouseType.Point
                    || t instanceof sqlancer.clickhouse.ClickHouseType.Polygon
                    || t instanceof sqlancer.clickhouse.ClickHouseType.Ring
                    || t instanceof sqlancer.clickhouse.ClickHouseType.MultiPolygon) {
                candidates.add(c);
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        ClickHouseColumnReference col = Randomly.fromList(candidates);
        sqlancer.clickhouse.ClickHouseType t = col.getColumn().getType().getTypeTerm().unwrap();
        ClickHouseGeoFunction fn = ClickHouseGeoFunction.pickFor(t, globalState.getRandomly());
        if (fn == null) {
            return null;
        }
        // Synthesise a second argument when needed.
        StringBuilder sb = new StringBuilder(fn.getName()).append("(").append(ClickHouseToStringVisitor.asString(col));
        if (fn.getShape() == ClickHouseGeoFunction.ArgShape.POINT_POLYGON) {
            // pointInPolygon(point, polygon). The polygon literal is a constant.
            sb.append(", ").append("[[(0.0, 0.0), (10.0, 0.0), (10.0, 10.0), (0.0, 10.0)]]");
        } else if (fn.getShape() == ClickHouseGeoFunction.ArgShape.POLYGON_POLYGON) {
            sb.append(", ").append("[[(0.0, 0.0), (10.0, 0.0), (10.0, 10.0), (0.0, 10.0)]]");
        }
        sb.append(")");
        return new sqlancer.clickhouse.ast.ClickHouseRawText(sb.toString());
    }

    /**
     * Date / DateTime + Interval arithmetic. Workstream 3 of the 2026-05-27 plan.
     *
     * <p>Returns one of:
     * <ul>
     *   <li>{@code dateCol + INTERVAL N DAY}
     *   <li>{@code dateCol - INTERVAL N HOUR}
     *   <li>{@code dateAdd(YEAR, N, dateCol)} / {@code dateSub(...)} -- the function-form alias
     * </ul>
     * or null when no Date / DateTime column is in scope.
     */
    public ClickHouseExpression generateDateIntervalArith(List<ClickHouseColumnReference> columns) {
        List<ClickHouseColumnReference> dateCols = new java.util.ArrayList<>();
        for (ClickHouseColumnReference c : columns) {
            com.clickhouse.data.ClickHouseDataType t = c.getColumn().getType().getType();
            if (t == com.clickhouse.data.ClickHouseDataType.Date || t == com.clickhouse.data.ClickHouseDataType.Date32
                    || t == com.clickhouse.data.ClickHouseDataType.DateTime
                    || t == com.clickhouse.data.ClickHouseDataType.DateTime64) {
                dateCols.add(c);
            }
        }
        if (dateCols.isEmpty()) {
            return null;
        }
        ClickHouseColumnReference col = Randomly.fromList(dateCols);
        String unit = Randomly.fromOptions("SECOND", "MINUTE", "HOUR", "DAY", "WEEK", "MONTH", "QUARTER", "YEAR");
        int n = 1 + (int) Randomly.getNotCachedInteger(0, 365);
        String sign = Randomly.getBoolean() ? "+" : "-";
        boolean functionForm = Randomly.getBoolean();
        String sql;
        if (functionForm) {
            String fn = sign.equals("+") ? "dateAdd" : "dateSub";
            sql = fn + "(" + unit + ", " + n + ", " + ClickHouseToStringVisitor.asString(col) + ")";
        } else {
            sql = "(" + ClickHouseToStringVisitor.asString(col) + " " + sign + " INTERVAL " + n + " " + unit + ")";
        }
        return new sqlancer.clickhouse.ast.ClickHouseRawText(sql);
    }

    /**
     * Scalar subquery: a self-contained {@code (SELECT ...)} renderable as an expression.
     * Workstream 16. Returns null if there are no tables to read from.
     *
     * <p>Two families are emitted at random:
     * <ul>
     *   <li>aggregate-over-table forms {@code (SELECT count()|min(1)|max(1) FROM t)} -- the
     *       original Workstream-16 shape, always single-row and type-stable;
     *   <li>value-returning ordered-LIMIT-1 forms
     *       {@code (SELECT <col> FROM <db>.<t> [WHERE <pred>] ORDER BY <col> LIMIT 1)} -- a single
     *       column value of a real numeric/string column. This second family is exactly the shape
     *       behind #106082 / #106083 (filed 2026-05-24 on v26.5.1.882): a
     *       {@code WHERE col = (SELECT c0 FROM t ORDER BY <key> LIMIT 1)} that drops the only
     *       matching row. The original aggregate-only generator never produced an
     *       ORDER BY ... LIMIT 1 single-column value, so the bug class was unreachable.
     * </ul>
     */
    public ClickHouseExpression generateScalarSubquery() {
        java.util.List<sqlancer.clickhouse.ClickHouseSchema.ClickHouseTable> tables = globalState.getSchema()
                .getDatabaseTables();
        if (tables.isEmpty()) {
            return null;
        }
        sqlancer.clickhouse.ClickHouseSchema.ClickHouseTable t = Randomly.fromList(tables);
        String qualified = globalState.getDatabaseName() + "." + t.getName();
        // Roughly half the time keep the aggregate form (always single-row, always well-typed);
        // otherwise try the value-returning ORDER BY ... LIMIT 1 form when the table has a
        // numeric / String column we can both project and order by. Fall back to the aggregate
        // form when no such column exists so the method never returns null for a usable table.
        if (Randomly.getBoolean()) {
            String agg = Randomly.fromOptions("count()", "min(1)", "max(1)");
            return new sqlancer.clickhouse.ast.ClickHouseRawText("(SELECT " + agg + " FROM " + qualified + ")");
        }
        java.util.List<ClickHouseColumn> valueCols = t.getColumns().stream()
                .filter(c -> isNumeric(c.getType().getType())
                        || c.getType().getType() == com.clickhouse.data.ClickHouseDataType.String)
                .collect(Collectors.toList());
        if (valueCols.isEmpty()) {
            String agg = Randomly.fromOptions("count()", "min(1)", "max(1)");
            return new sqlancer.clickhouse.ast.ClickHouseRawText("(SELECT " + agg + " FROM " + qualified + ")");
        }
        ClickHouseColumn col = Randomly.fromList(valueCols);
        String colName = col.getName();
        StringBuilder sb = new StringBuilder("(SELECT ").append(colName).append(" FROM ").append(qualified);
        // Optionally constrain the inner scan with a WHERE on the projected column itself. The
        // predicate is a simple bound against a constant of the column's own type so the inner
        // SELECT stays well-typed; the LIMIT 1 then surfaces the boundary row that the bug drops.
        if (Randomly.getBoolean()) {
            ClickHouseExpression bound = generateConstantFromTerm(col.getType().getTypeTerm());
            String op = Randomly.fromOptions(">=", "<=", "!=");
            sb.append(" WHERE ").append(colName).append(" ").append(op).append(" ")
                    .append(ClickHouseToStringVisitor.asString(bound));
        }
        String dir = Randomly.getBoolean() ? " DESC" : "";
        sb.append(" ORDER BY ").append(colName).append(dir).append(" LIMIT 1)");
        return new sqlancer.clickhouse.ast.ClickHouseRawText(sb.toString());
    }

    /**
     * Higher-order function call over an Array column with a synthesised lambda body.
     * arrayMap / arrayFilter / arrayCount / arrayExists / arrayAll / arraySort / arrayFirst /
     * arrayLast / arrayFold / arrayMin / arrayMax / arraySum / arrayAvg. Workstream 22.
     *
     * <p>Returns null if no Array(T) column is in scope.
     */
    public ClickHouseExpression generateHigherOrderArrayCall(List<ClickHouseColumnReference> columns) {
        List<ClickHouseColumnReference> arrayCols = new java.util.ArrayList<>();
        for (ClickHouseColumnReference c : columns) {
            sqlancer.clickhouse.ClickHouseType t = c.getColumn().getType().getTypeTerm().unwrap();
            if (t instanceof sqlancer.clickhouse.ClickHouseType.Array) {
                arrayCols.add(c);
            }
        }
        if (arrayCols.isEmpty()) {
            return null;
        }
        ClickHouseColumnReference arrCol = Randomly.fromList(arrayCols);
        // Synthesise a depth-1 lambda body over the lambda parameter `x`. The body is a numeric
        // op for arithmetic higher-orders (arrayMap, arrayFilter, etc.) -- in practice CH
        // accepts any well-typed body, so the bare-x identity body works for arrayMap and
        // arrayFilter alike. lambdaParamType propagation through Nullable is deferred.
        String fnName = Randomly.fromOptions("arrayMap", "arrayFilter", "arrayCount", "arrayExists",
                "arrayAll", "arrayFirst", "arrayLast", "arraySort", "arrayMin", "arrayMax", "arraySum");
        // Body: half the time bare x, half the time x + 1 (arithmetic for numeric inner types).
        sqlancer.clickhouse.ast.ClickHouseExpression body;
        if (Randomly.getBoolean()) {
            body = new sqlancer.clickhouse.ast.ClickHouseRawText("x");
        } else {
            body = new sqlancer.clickhouse.ast.ClickHouseRawText("x + 1");
        }
        sqlancer.clickhouse.ast.ClickHouseLambda lambda = new sqlancer.clickhouse.ast.ClickHouseLambda(
                List.of("x"), body);
        StringBuilder sb = new StringBuilder(fnName).append("(");
        sb.append(ClickHouseToStringVisitor.asString(lambda));
        sb.append(", ");
        sb.append(ClickHouseToStringVisitor.asString(arrCol));
        sb.append(")");
        return new sqlancer.clickhouse.ast.ClickHouseRawText(sb.toString());
    }

    /**
     * Emit a window-function expression of the form {@code func() OVER (PARTITION BY ... ORDER BY ...)}
     * over the in-scope columns. Workstream 19.
     */
    public ClickHouseExpression generateWindowCall(List<ClickHouseColumnReference> columns) {
        if (columns.isEmpty()) {
            return null;
        }
        // Exclude NTH_VALUE / LAG / LEAD: they require a 2nd argument (position / offset) which
        // the single-argument AST shape doesn't carry. nth_value(col) without the 2nd arg is
        // rejected by CH with NUMBER_OF_ARGUMENTS_DOESNT_MATCH (the smoke #7 51-of-53 family).
        sqlancer.clickhouse.ast.ClickHouseWindowFunction.Kind kind = Randomly.fromOptions(
                sqlancer.clickhouse.ast.ClickHouseWindowFunction.Kind.ROW_NUMBER,
                sqlancer.clickhouse.ast.ClickHouseWindowFunction.Kind.RANK,
                sqlancer.clickhouse.ast.ClickHouseWindowFunction.Kind.DENSE_RANK,
                sqlancer.clickhouse.ast.ClickHouseWindowFunction.Kind.PERCENT_RANK,
                sqlancer.clickhouse.ast.ClickHouseWindowFunction.Kind.CUME_DIST,
                sqlancer.clickhouse.ast.ClickHouseWindowFunction.Kind.FIRST_VALUE,
                sqlancer.clickhouse.ast.ClickHouseWindowFunction.Kind.LAST_VALUE,
                sqlancer.clickhouse.ast.ClickHouseWindowFunction.Kind.SUM,
                sqlancer.clickhouse.ast.ClickHouseWindowFunction.Kind.COUNT,
                sqlancer.clickhouse.ast.ClickHouseWindowFunction.Kind.MIN,
                sqlancer.clickhouse.ast.ClickHouseWindowFunction.Kind.MAX,
                sqlancer.clickhouse.ast.ClickHouseWindowFunction.Kind.AVG);
        ClickHouseExpression argument = null;
        switch (kind) {
        case SUM: case COUNT: case MIN: case MAX: case AVG:
        case FIRST_VALUE: case LAST_VALUE: {
            List<ClickHouseColumnReference> numeric = numericColumns(columns);
            if (numeric.isEmpty()) {
                return null;
            }
            argument = numeric.get((int) Randomly.getNotCachedInteger(0, numeric.size()));
            break;
        }
        default:
            break;
        }
        // Partition/order keys: at most 1 each, drawn from the column set.
        List<ClickHouseExpression> partitionBy = new java.util.ArrayList<>();
        if (Randomly.getBoolean()) {
            partitionBy.add(columns.get((int) Randomly.getNotCachedInteger(0, columns.size())));
        }
        List<ClickHouseExpression> orderBy = new java.util.ArrayList<>();
        orderBy.add(columns.get((int) Randomly.getNotCachedInteger(0, columns.size())));
        return new sqlancer.clickhouse.ast.ClickHouseWindowFunction(kind, argument, partitionBy, orderBy);
    }

    /**
     * dictGet over a dictionary name and key column. Workstream 14. The dictionary's column
     * shape isn't visible to the generator, so the emitted dictGet uses a generic 'col' value
     * field name -- the oracle paths that need a specific shape construct dictGet inline.
     */
    public ClickHouseExpression generateDictGet(String dictName, ClickHouseColumnReference keyCol) {
        String sql = "dictGet('" + dictName + "', 'col', toUInt64(" + ClickHouseToStringVisitor.asString(keyCol)
                + "))";
        return new sqlancer.clickhouse.ast.ClickHouseRawText(sql);
    }

    /**
     * True iff `type` cannot appear in a scalar context without a subcolumn-access wrapper.
     * JSON/Variant/Dynamic columns must be projected via a path/element accessor before being
     * compared, arithmetised, or aggregated. Workstream 6.
     */
    public static boolean requiresSubcolumnAccess(sqlancer.clickhouse.ClickHouseType type) {
        sqlancer.clickhouse.ClickHouseType u = type.unwrap();
        return u instanceof sqlancer.clickhouse.ClickHouseType.JSON
                || u instanceof sqlancer.clickhouse.ClickHouseType.Variant
                || u instanceof sqlancer.clickhouse.ClickHouseType.Dynamic
                || u instanceof sqlancer.clickhouse.ClickHouseType.Nested;
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

    // GAP 4: rich JOIN ON clauses. #105716 (RIGHT OUTER JOIN) and #105717 (INNER JOIN) wrongly keep
    // a default-value row when the ON clause has the shape `ON (a = b AND a IS NULL)` -- an equality
    // AND-ed with an IS NULL on a join-key column. The plain `leftcol = rightcol` clause never
    // reaches that surface. This helper takes the equality clause built by generateJoinClause and,
    // with moderate probability, AND-conjoins an `<col> IS NULL` / `<col> IS NOT NULL` predicate on
    // one of the two join-key columns.
    //
    // Rendering note: ClickHouseJoinOnClause `implements BinaryOperation`, so the to-string visitor
    // always dispatches it through the base BinaryOperation path (`((left)=(right))`) -- there is no
    // visitor hook to append a trailing conjunct. The faithful, self-contained surface is therefore
    // a pre-rendered ClickHouseRawText carrying the full `(a = b) AND (a IS [NOT] NULL)` expression,
    // following the generateScalarSubquery / dateInterval precedent for arbitrary boolean fragments.
    // The join carries this via its arbitrary-expression onClause (set through ClickHouseJoin's
    // setOnClause), leaving the plain-equality ClickHouseJoinOnClause unchanged and dominant.
    //
    // IMPORTANT: this only ADDS the IS NULL conjunct capability when explicitly requested by the
    // caller; the JoinAlgorithm / TLP oracles that rely on deterministic equality-only join shapes
    // keep passing the bare ClickHouseJoinOnClause unchanged.
    private ClickHouseExpression maybeEnrichJoinOnClause(ClickHouseExpression.ClickHouseJoinOnClause equality,
            ClickHouseExpression.ClickHouseJoin.JoinType joinType) {
        // The default-value-vs-null bug lives in the OUTER family (RIGHT/LEFT/FULL OUTER); bias the
        // enrichment heavily toward those and only rarely touch other deterministic shapes so plain
        // equality stays the dominant case everywhere.
        boolean outer = joinType == ClickHouseExpression.ClickHouseJoin.JoinType.RIGHT_OUTER
                || joinType == ClickHouseExpression.ClickHouseJoin.JoinType.LEFT_OUTER
                || joinType == ClickHouseExpression.ClickHouseJoin.JoinType.FULL_OUTER;
        boolean enrich = outer ? Randomly.getBoolean() : Randomly.getBooleanWithSmallProbability();
        if (!enrich) {
            return equality;
        }
        // Pick which side's key column carries the IS NULL predicate. Both keys are real column
        // references with a table alias, so asString renders them as `alias.colname`.
        ClickHouseExpression keyCol = Randomly.getBoolean() ? equality.getLeft() : equality.getRight();
        String nullOp = Randomly.getBoolean() ? "IS NULL" : "IS NOT NULL";
        String sql = "(" + ClickHouseToStringVisitor.asString(equality.getLeft()) + " = "
                + ClickHouseToStringVisitor.asString(equality.getRight()) + ") AND ("
                + ClickHouseToStringVisitor.asString(keyCol) + " " + nullOp + ")";
        return new sqlancer.clickhouse.ast.ClickHouseRawText(sql);
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
                // ANY / SEMI joins are non-deterministic across algorithms and break TLP /
                // NoREC / SEMR multiset equality. Restrict the random pick to deterministic
                // shapes; the dedicated JoinAlgorithm oracle filters non-deterministic shapes a
                // second time at oracle level.
                ClickHouseExpression.ClickHouseJoin.JoinType options = Randomly.fromList(DETERMINISTIC_JOIN_TYPES);
                ClickHouseExpression.ClickHouseJoin j = new ClickHouseExpression.ClickHouseJoin(leftTable, rightTable,
                        options, joinClause);
                // GAP 4: optionally enrich the ON clause with an `AND <key> IS [NOT] NULL` conjunct
                // (preferred on the OUTER family where #105716 / #105717 live). CROSS has no ON
                // clause (joinClause is non-null only for the non-CROSS shapes here), so skip the
                // enrichment when the equality clause is absent.
                if (joinClause != null) {
                    j.setOnClause(maybeEnrichJoinOnClause(joinClause, options));
                }
                joinStatements.add(j);
                leftTables.add(rightTable);
            }
        }
        return joinStatements;
    }

    // JOIN types whose result multiset is determined uniquely by the inputs + ON clause. ANY,
    // SEMI (added in W3 of the wrong-result push) are deliberately excluded -- their per-row
    // choice is implementation-defined and would produce false-positive multiset diffs in TLP /
    // NoREC / SEMR. JoinAlgorithmOracle gates these out a second time at oracle level.
    private static final List<ClickHouseExpression.ClickHouseJoin.JoinType> DETERMINISTIC_JOIN_TYPES = List.of(
            ClickHouseExpression.ClickHouseJoin.JoinType.INNER, ClickHouseExpression.ClickHouseJoin.JoinType.CROSS,
            ClickHouseExpression.ClickHouseJoin.JoinType.LEFT_OUTER,
            ClickHouseExpression.ClickHouseJoin.JoinType.RIGHT_OUTER,
            ClickHouseExpression.ClickHouseJoin.JoinType.FULL_OUTER,
            ClickHouseExpression.ClickHouseJoin.JoinType.LEFT_ANTI,
            ClickHouseExpression.ClickHouseJoin.JoinType.RIGHT_ANTI);

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
    // via IgnoreMeException -- the established escape hatch for unsupported types. Parameterised
    // primitives (FixedString / Decimal / DateTime64) and Array(T) are emitted as a string literal
    // wrapped in CAST(... AS T) so the value is well-typed without needing a per-type Constant
    // subclass.
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
        if (term instanceof FixedString fs) {
            // Emit `CAST('...' AS FixedString(N))`. Right-pad/truncate to N so the value is exactly
            // the column width; ClickHouse pads with NUL on insert but the generator avoids relying
            // on that and produces the canonical form.
            String s = globalState.getRandomly().getString();
            if (s.length() > fs.length()) {
                s = s.substring(0, fs.length());
            } else if (s.length() < fs.length()) {
                StringBuilder pad = new StringBuilder(s);
                while (pad.length() < fs.length()) {
                    pad.append(' ');
                }
                s = pad.toString();
            }
            return new ClickHouseCastOperation(ClickHouseCreateConstant.createStringConstant(s),
                    new ClickHouseLancerDataType(term));
        }
        if (term instanceof Decimal d) {
            // Clamp the value so the textual integer fits within the Decimal's (P - S) integer
            // digits. ClickHouse rejects over-magnitude values with ARGUMENT_OUT_OF_BOUND ("Too
            // many digits") before applying the scale, so a bare random long routinely overflows
            // small Decimal(4, 1) columns. Compute the max integer-part magnitude as 10^(P-S) - 1.
            int integerDigits = Math.max(1, d.precision() - d.scale());
            long bound = 1;
            for (int i = 0; i < integerDigits && bound < Long.MAX_VALUE / 10; i++) {
                bound *= 10;
            }
            long raw = globalState.getRandomly().getInteger();
            long v = raw % bound;
            return new ClickHouseCastOperation(ClickHouseCreateConstant.createStringConstant(Long.toString(v)),
                    new ClickHouseLancerDataType(term));
        }
        if (term instanceof DateTime64Type) {
            return new ClickHouseCastOperation(ClickHouseCreateConstant.createStringConstant(randomDateTimeLiteral()),
                    new ClickHouseLancerDataType(term));
        }
        if (term instanceof sqlancer.clickhouse.ClickHouseType.Time) {
            // 'HH:MM:SS' string literal cast to Time. Hour bounded to [0, 23], minute and second
            // to [0, 59]. ClickHouse Time stores seconds-since-midnight; the cast accepts the
            // canonical text form.
            int h = (int) Randomly.getNotCachedInteger(0, 24);
            int m = (int) Randomly.getNotCachedInteger(0, 60);
            int s = (int) Randomly.getNotCachedInteger(0, 60);
            String literal = String.format("%02d:%02d:%02d", h, m, s);
            return new ClickHouseCastOperation(ClickHouseCreateConstant.createStringConstant(literal),
                    new ClickHouseLancerDataType(term));
        }
        if (term instanceof sqlancer.clickhouse.ClickHouseType.Time64 t64) {
            // Same as Time but with a fractional seconds suffix of width = precision. ClickHouse
            // truncates / zero-pads silently if the precision differs from the column's declared
            // value, but we render the canonical form for cleaner replays.
            int h = (int) Randomly.getNotCachedInteger(0, 24);
            int m = (int) Randomly.getNotCachedInteger(0, 60);
            int s = (int) Randomly.getNotCachedInteger(0, 60);
            String base = String.format("%02d:%02d:%02d", h, m, s);
            String literal = base;
            if (t64.precision() > 0) {
                StringBuilder frac = new StringBuilder(".");
                for (int i = 0; i < t64.precision(); i++) {
                    frac.append((int) Randomly.getNotCachedInteger(0, 10));
                }
                literal = base + frac;
            }
            return new ClickHouseCastOperation(ClickHouseCreateConstant.createStringConstant(literal),
                    new ClickHouseLancerDataType(term));
        }
        if (term instanceof Array a) {
            // Emit a small literal array of inner-typed values via the bracket syntax. The inner
            // constants are themselves rendered through this method so wrappers nest correctly.
            int n = (int) Randomly.getNotCachedInteger(0, 4);
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < n; i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(ClickHouseToStringVisitor.asString(generateConstantFromTerm(a.inner())));
            }
            sb.append("]");
            return new ClickHouseCastOperation(ClickHouseCreateConstant.createStringConstant(sb.toString()),
                    new ClickHouseLancerDataType(term));
        }
        if (term instanceof Primitive p) {
            return generatePrimitiveConstant(p.kind());
        }
        if (term instanceof sqlancer.clickhouse.ClickHouseType.Enum en) {
            // Pick one of the enum entries and emit its quoted name. ClickHouse coerces the bare
            // string literal into the enum's domain at INSERT time. Out-of-domain rejection is
            // structurally impossible since we pick from the entry list.
            sqlancer.clickhouse.ClickHouseType.EnumEntry entry = Randomly.fromList(en.entries());
            return ClickHouseCreateConstant.createStringConstant(entry.name());
        }
        if (term instanceof sqlancer.clickhouse.ClickHouseType.Tuple t) {
            // Emit a parenthesised, comma-separated list of element constants. Wrap in a CAST so
            // the resulting expression is unambiguously a Tuple of the declared shape -- without
            // the cast, ClickHouse infers a fresh anonymous tuple type whose element types may
            // not match the column's declared element types (mismatch then rejected at INSERT).
            StringBuilder sb = new StringBuilder("(");
            for (int i = 0; i < t.elements().size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(ClickHouseToStringVisitor.asString(generateConstantFromTerm(t.elements().get(i))));
            }
            sb.append(")");
            return new ClickHouseCastOperation(ClickHouseCreateConstant.createStringConstant(sb.toString()),
                    new ClickHouseLancerDataType(term));
        }
        if (term instanceof sqlancer.clickhouse.ClickHouseType.Map m) {
            // map(k1, v1, k2, v2, ...) ClickHouse function form. Emit as a raw SQL fragment;
            // wrapping in CAST('map(...)' AS Map(...)) is rejected by CH with "Unsupported
            // types to CAST AS Map" because Map can't be constructed from a string literal --
            // the map() function returns the right type natively.
            int pairs = (int) Randomly.getNotCachedInteger(0, 4);
            StringBuilder sb = new StringBuilder("map(");
            for (int i = 0; i < pairs; i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(ClickHouseToStringVisitor.asString(generateConstantFromTerm(m.keyType())));
                sb.append(", ");
                sb.append(ClickHouseToStringVisitor.asString(generateConstantFromTerm(m.valueType())));
            }
            sb.append(")");
            return new sqlancer.clickhouse.ast.ClickHouseRawText(sb.toString());
        }
        if (term instanceof sqlancer.clickhouse.ClickHouseType.Point) {
            // (x, y)::Point. x and y in some bounded range so the geo functions don't blow up.
            double x = (globalState.getRandomly().getInteger() % 1000) / 10.0;
            double y = (globalState.getRandomly().getInteger() % 1000) / 10.0;
            String literal = "(" + x + ", " + y + ")";
            return new ClickHouseCastOperation(ClickHouseCreateConstant.createStringConstant(literal),
                    new ClickHouseLancerDataType(term));
        }
        if (term instanceof sqlancer.clickhouse.ClickHouseType.Ring) {
            // Array(Point). Emit 3-4 points so the ring is non-degenerate (CH treats <3 as
            // invalid for area / containment).
            StringBuilder sb = new StringBuilder("[");
            int n = 3 + (int) Randomly.getNotCachedInteger(0, 2);
            for (int i = 0; i < n; i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                double x = (globalState.getRandomly().getInteger() % 1000) / 10.0;
                double y = (globalState.getRandomly().getInteger() % 1000) / 10.0;
                sb.append("(").append(x).append(", ").append(y).append(")");
            }
            sb.append("]");
            return new ClickHouseCastOperation(ClickHouseCreateConstant.createStringConstant(sb.toString()),
                    new ClickHouseLancerDataType(term));
        }
        if (term instanceof sqlancer.clickhouse.ClickHouseType.Polygon) {
            // Array(Ring). Emit 1 ring -- the outer boundary; inner-ring (holes) skipped.
            StringBuilder sb = new StringBuilder("[[");
            int n = 3 + (int) Randomly.getNotCachedInteger(0, 2);
            for (int i = 0; i < n; i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                double x = (globalState.getRandomly().getInteger() % 1000) / 10.0;
                double y = (globalState.getRandomly().getInteger() % 1000) / 10.0;
                sb.append("(").append(x).append(", ").append(y).append(")");
            }
            sb.append("]]");
            return new ClickHouseCastOperation(ClickHouseCreateConstant.createStringConstant(sb.toString()),
                    new ClickHouseLancerDataType(term));
        }
        if (term instanceof sqlancer.clickhouse.ClickHouseType.MultiPolygon) {
            // Array(Polygon). Emit 1 polygon.
            StringBuilder sb = new StringBuilder("[[[");
            int n = 3 + (int) Randomly.getNotCachedInteger(0, 2);
            for (int i = 0; i < n; i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                double x = (globalState.getRandomly().getInteger() % 1000) / 10.0;
                double y = (globalState.getRandomly().getInteger() % 1000) / 10.0;
                sb.append("(").append(x).append(", ").append(y).append(")");
            }
            sb.append("]]]");
            return new ClickHouseCastOperation(ClickHouseCreateConstant.createStringConstant(sb.toString()),
                    new ClickHouseLancerDataType(term));
        }
        if (term instanceof sqlancer.clickhouse.ClickHouseType.JSON) {
            // Simple JSON object literal cast to JSON. The string passes through SQL's single-
            // quoted-string escape -- double-quotes inside single-quoted strings don't need
            // escaping at the SQL level, so we render the JSON with bare `"` characters and let
            // the constant wrapper add the surrounding `'...'`. Two scalar fields, primitive
            // values.
            String literal = "{\"a\": " + (globalState.getRandomly().getInteger() % 1000) + ", \"b\": \"x"
                    + (globalState.getRandomly().getInteger() % 10) + "\"}";
            return new ClickHouseCastOperation(ClickHouseCreateConstant.createStringConstant(literal),
                    new ClickHouseLancerDataType(term));
        }
        if (term instanceof sqlancer.clickhouse.ClickHouseType.Variant v) {
            // Pick one alternative type and emit its literal directly; ClickHouse coerces the
            // scalar into the variant. Without the cast we can't disambiguate alternatives.
            ClickHouseType pickedAlt = Randomly.fromList(v.alternatives());
            return new ClickHouseCastOperation(generateConstantFromTerm(pickedAlt),
                    new ClickHouseLancerDataType(term));
        }
        if (term instanceof sqlancer.clickhouse.ClickHouseType.Dynamic) {
            // Cast a primitive scalar to Dynamic. The runtime-typed wrapper preserves the scalar's
            // type via type tags so the variant family can read it back.
            ClickHouseType inner = new Primitive(Kind.Int32);
            return new ClickHouseCastOperation(generateConstantFromTerm(inner),
                    new ClickHouseLancerDataType(term));
        }
        if (term instanceof sqlancer.clickhouse.ClickHouseType.IntervalType i) {
            // INTERVAL N <unit>. Pick a small positive value.
            int n = 1 + (int) Randomly.getNotCachedInteger(0, 100);
            String unit = i.kind().name().toUpperCase();
            // Render as a raw SQL fragment via the string-constant wrapper without the quoting
            // -- INTERVAL is a SQL keyword and can't be wrapped in single quotes.
            return ClickHouseCreateConstant.createStringConstant("INTERVAL " + n + " " + unit);
        }
        // Nested / AggregateFunction / SimpleAggregateFunction: structurally can't be emitted as
        // a simple literal. INSERTs into these columns happen via separate parallel-array /
        // -State combinator paths that aren't in this generator's scope.
        if (term instanceof sqlancer.clickhouse.ClickHouseType.Nested
                || term instanceof sqlancer.clickhouse.ClickHouseType.AggregateFunctionType
                || term instanceof sqlancer.clickhouse.ClickHouseType.SimpleAggregateFunctionType) {
            throw new IgnoreMeException();
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
        case Date:
        case Date32:
            // CAST('YYYY-MM-DD' AS Date|Date32). The literal is the same across both kinds; the cast
            // tag carries the range difference (Date32 spans 1900..2299, Date 1970..2149).
            return new ClickHouseCastOperation(ClickHouseCreateConstant.createStringConstant(randomDateLiteral()),
                    new ClickHouseLancerDataType(new Primitive(kind)));
        case DateTime:
            return new ClickHouseCastOperation(ClickHouseCreateConstant.createStringConstant(randomDateTimeLiteral()),
                    new ClickHouseLancerDataType(new Primitive(Kind.DateTime)));
        case UUID:
            // CAST('xxxxxxxx-...' AS UUID). UUID ORDER BY / primary-key ordering changed across CH
            // versions; emitting real UUID columns + literals exercises that path. Unit 1.2.
            return new ClickHouseCastOperation(ClickHouseCreateConstant.createStringConstant(randomUuidLiteral()),
                    new ClickHouseLancerDataType(new Primitive(Kind.UUID)));
        case IPv4:
            // CAST('a.b.c.d' AS IPv4). IPv4 is backed by UInt32 but compared with special ops --
            // comparison/ordering/CAST bug history. Unit 1.2.
            return new ClickHouseCastOperation(ClickHouseCreateConstant.createStringConstant(randomIPv4Literal()),
                    new ClickHouseLancerDataType(new Primitive(Kind.IPv4)));
        case IPv6:
            // CAST('h:h:h:h:h:h:h:h' AS IPv6). IPv6 is backed by FixedString(16); full 8-group hex
            // form is always valid text. Unit 1.2.
            return new ClickHouseCastOperation(ClickHouseCreateConstant.createStringConstant(randomIPv6Literal()),
                    new ClickHouseLancerDataType(new Primitive(Kind.IPv6)));
        default:
            // Truly unhandled scalar kind: skip rather than fabricate an untyped literal.
            throw new IgnoreMeException();
        }
    }

    // Random UUID text in canonical 8-4-4-4-12 hex form. Drawn from the seeded Randomly so runs
    // remain reproducible. Unit 1.2.
    private String randomUuidLiteral() {
        Randomly r = globalState.getRandomly();
        return String.format("%08x-%04x-%04x-%04x-%012x", r.getLong(0, 0x1_0000_0000L), r.getLong(0, 0x1_0000L),
                r.getLong(0, 0x1_0000L), r.getLong(0, 0x1_0000L), r.getLong(0, 0x1_0000_0000_0000L));
    }

    // Random dotted-quad IPv4 literal. Unit 1.2.
    private String randomIPv4Literal() {
        return String.format("%d.%d.%d.%d", Randomly.getNotCachedInteger(0, 256), Randomly.getNotCachedInteger(0, 256),
                Randomly.getNotCachedInteger(0, 256), Randomly.getNotCachedInteger(0, 256));
    }

    // Random full 8-group IPv6 literal (always valid text, no :: compression to keep it simple). Unit 1.2.
    private String randomIPv6Literal() {
        Randomly r = globalState.getRandomly();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            if (i > 0) {
                sb.append(':');
            }
            sb.append(String.format("%x", r.getLong(0, 0x1_0000L)));
        }
        return sb.toString();
    }

    // Random YYYY-MM-DD within a reasonable bug-bait range: covers the Date<->Date32 boundary
    // around the Unix epoch and the year-2038 / year-2105 transition surfaces ClickHouse handles
    // separately under the hood. Output is always a valid Gregorian date for any month/day combo
    // the constructor accepts.
    private String randomDateLiteral() {
        int year = 1970 + (int) Randomly.getNotCachedInteger(0, 80);
        int month = 1 + (int) Randomly.getNotCachedInteger(0, 12);
        int day = 1 + (int) Randomly.getNotCachedInteger(0, 28);
        return String.format("%04d-%02d-%02d", year, month, day);
    }

    private String randomDateTimeLiteral() {
        int year = 1970 + (int) Randomly.getNotCachedInteger(0, 80);
        int month = 1 + (int) Randomly.getNotCachedInteger(0, 12);
        int day = 1 + (int) Randomly.getNotCachedInteger(0, 28);
        int hour = (int) Randomly.getNotCachedInteger(0, 24);
        int min = (int) Randomly.getNotCachedInteger(0, 60);
        int sec = (int) Randomly.getNotCachedInteger(0, 60);
        return String.format("%04d-%02d-%02d %02d:%02d:%02d", year, month, day, hour, min, sec);
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
        ClickHouseExpression base = generateExpressionWithColumns(columnRefs, 3);
        // Occasionally fold a bare large-integer literal into the predicate as a top-level AND
        // conjunct: e.g. `expr AND 2147483648`. ClickHouse promotes the integer to a boolean as
        // "non-zero". Regression #101287 (`WHERE 2147483648 > b AND 2147483648` incorrectly
        // returning 0) shows the code path that promotes non-UInt8 integer constants to filter
        // truth values is bug-prone; emitting the shape from the generator makes TLP / SEMR
        // exercise that path uniformly. Probability is small because every emission shrinks the
        // covered surface of the underlying random predicate -- this is an additive surface, not
        // a replacement one.
        if (Randomly.getBooleanWithRatherLowProbability()) {
            // Span the integer-width transition boundaries the bug class lives near: <= UInt8 max,
            // <= Int32 max, > Int32 max (forces Int64 widening on the comparison path).
            long lit = Randomly.fromOptions(256L, 65536L, 2147483648L);
            ClickHouseExpression literal = ClickHouseCreateConstant.createInt64Constant(BigInteger.valueOf(lit));
            return new ClickHouseBinaryLogicalOperation(base, literal,
                    ClickHouseBinaryLogicalOperation.ClickHouseBinaryLogicalOperator.AND);
        }
        // GAP 2: typed-constant predicate conjuncts. #103049 (`WHERE x AND toNullable(N)` returns
        // 0 rows for N>=256) and #104393 (`WHERE p AND <LowCardinality(Nullable(UInt8)) constant>`
        // returns zero rows on MergeTree) are wrong-result bugs where AND-ing a constant that is
        // logically TRUE into the WHERE silently drops every row. The plain large-integer conjunct
        // above does NOT exercise them -- the trigger is specifically a *typed* wrapper
        // (toNullable / LowCardinality(Nullable(UInt8)) / Nullable(UInt8)) around the truthy value,
        // so the buggy short-circuit on the typed-constant filter path fires. The wrapped value is
        // always something that evaluates to TRUE on every row (a non-zero numeric constant, or a
        // comparison of a numeric constant against itself) so a correct engine is a no-op AND only
        // a buggy one drops rows -- making the divergence trivially catchable by TLPWhere / NoREC.
        // Probability is small (additive surface, not a replacement): every emission narrows the
        // covered surface of the underlying random predicate.
        if (Randomly.getBooleanWithSmallProbability()) {
            // Inner truthy value: either a non-zero numeric literal (256 is the #103049 boundary)
            // or a tautological comparison. Both are constant-TRUE so the AND is a logical no-op.
            String inner = Randomly.fromOptions("256", "1", "(1 = 1)", "(255 < 256)");
            // One of the four typed wrappers from the bug reports. ClickHouseRawText follows the
            // generateScalarSubquery precedent: no typed AST node carries a LowCardinality /
            // Nullable CAST wrapper, so the pre-rendered fragment is the minimal faithful surface.
            String wrapped = Randomly.fromOptions(
                    "toNullable(" + inner + ")",
                    "CAST(" + inner + " AS LowCardinality(Nullable(UInt8)))",
                    "CAST(" + inner + " AS Nullable(UInt8))",
                    "toLowCardinality(toNullable(" + inner + "))");
            ClickHouseExpression typedConjunct = new sqlancer.clickhouse.ast.ClickHouseRawText(wrapped);
            return new ClickHouseBinaryLogicalOperation(base, typedConjunct,
                    ClickHouseBinaryLogicalOperation.ClickHouseBinaryLogicalOperator.AND);
        }
        // GAP 5(b): scalar-subquery predicates. Wire generateScalarSubquery() into the shared
        // predicate path so ALL oracles (KeyCondition, SEMR, the materialization oracle) -- not
        // just the TLP base where it was previously the only consumer -- exercise
        // `col <cmp> (scalar subquery)`. The ORDER BY ... LIMIT 1 forms surfaced by
        // generateScalarSubquery are the #106082 / #106083 shape. Guard on a real in-scope column
        // so the comparison is against an actual column (not a fabricated constant), and skip when
        // there is no other table to read from (generateScalarSubquery returns null).
        if (Randomly.getBooleanWithSmallProbability() && !columnRefs.isEmpty()) {
            ClickHouseExpression subquery = generateScalarSubquery();
            if (subquery != null) {
                ClickHouseColumnReference col = columnRefs
                        .get((int) Randomly.getNotCachedInteger(0, columnRefs.size()));
                return new ClickHouseBinaryComparisonOperation(col, subquery,
                        ClickHouseBinaryComparisonOperation.ClickHouseBinaryComparisonOperator.getRandomOperator());
            }
        }
        // Unit 1.1: IN / NOT IN with a subquery RHS -- `col [NOT] IN (SELECT c FROM db.t [WHERE ...])`.
        // ClickHouse rewrites this to a semijoin/set and it interacts with PREWHERE, KeyCondition
        // index analysis, and partition pruning -- a dense wrong-result/crash area. The per-row IN
        // result is UInt8 / Nullable(UInt8), so the WHERE-partition invariant still holds and
        // TLPWhere / NoREC / SEMR exercise it for free. Additive low-probability surface, guarded on
        // a real in-scope column; generateInSubquery returns null when no type-compatible inner
        // column exists, in which case we fall through to the base predicate.
        if (Randomly.getBooleanWithSmallProbability() && !columnRefs.isEmpty()) {
            ClickHouseColumnReference col = columnRefs.get((int) Randomly.getNotCachedInteger(0, columnRefs.size()));
            ClickHouseExpression inSubquery = generateInSubquery(col);
            if (inSubquery != null) {
                ClickHouseBinaryComparisonOperation.ClickHouseBinaryComparisonOperator op = Randomly.getBoolean()
                        ? ClickHouseBinaryComparisonOperation.ClickHouseBinaryComparisonOperator.IN
                        : ClickHouseBinaryComparisonOperation.ClickHouseBinaryComparisonOperator.NOT_IN;
                return new ClickHouseBinaryComparisonOperation(col, inSubquery, op);
            }
        }
        return base;
    }

    /**
     * Build the RHS of an {@code IN} / {@code NOT IN} predicate: a single-column subquery
     * {@code (SELECT c FROM db.t [WHERE c <op> const])} that projects a column whose type category
     * matches {@code outer} so the set-membership test stays well-typed. Unlike
     * {@link #generateScalarSubquery()} this projects a multi-row set (no {@code LIMIT 1}) because
     * {@code IN} tests membership across the whole inner result. Returns null when no table has a
     * type-compatible column to project, so the caller can fall back to the base predicate.
     */
    private ClickHouseExpression generateInSubquery(ClickHouseColumnReference outer) {
        java.util.List<sqlancer.clickhouse.ClickHouseSchema.ClickHouseTable> tables = globalState.getSchema()
                .getDatabaseTables();
        if (tables.isEmpty()) {
            return null;
        }
        ClickHouseDataType outerType = outer.getColumn().getType().getType();
        boolean outerNumeric = isNumeric(outerType);
        sqlancer.clickhouse.ClickHouseSchema.ClickHouseTable t = Randomly.fromList(tables);
        String qualified = globalState.getDatabaseName() + "." + t.getName();
        // Numeric outer accepts any numeric inner column (CH coerces to a common supertype in IN);
        // a non-numeric outer requires an exact data-type match to avoid IN type-mismatch errors.
        java.util.List<ClickHouseColumn> candidates = t.getColumns().stream()
                .filter(c -> outerNumeric ? isNumeric(c.getType().getType()) : c.getType().getType() == outerType)
                .collect(Collectors.toList());
        if (candidates.isEmpty()) {
            return null;
        }
        ClickHouseColumn inner = Randomly.fromList(candidates);
        String colName = inner.getName();
        StringBuilder sb = new StringBuilder("(SELECT ").append(colName).append(" FROM ").append(qualified);
        // Optionally constrain the inner scan with a simple bound on the projected column itself so
        // the IN set is a non-trivial subset; the predicate stays well-typed via a same-type constant.
        if (Randomly.getBoolean()) {
            ClickHouseExpression bound = generateConstantFromTerm(inner.getType().getTypeTerm());
            String op = Randomly.fromOptions(">=", "<=", "!=");
            sb.append(" WHERE ").append(colName).append(" ").append(op).append(" ")
                    .append(ClickHouseToStringVisitor.asString(bound));
        }
        sb.append(")");
        return new sqlancer.clickhouse.ast.ClickHouseRawText(sb.toString());
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
                // ANY / SEMI joins are non-deterministic across algorithms and break TLP /
                // NoREC / SEMR multiset equality. Restrict the random pick to deterministic
                // shapes; the dedicated JoinAlgorithm oracle filters non-deterministic shapes a
                // second time at oracle level.
                ClickHouseExpression.ClickHouseJoin.JoinType options = Randomly.fromList(DETERMINISTIC_JOIN_TYPES);
                ClickHouseExpression.ClickHouseJoin j = new ClickHouseExpression.ClickHouseJoin(leftTable, rightTable,
                        options, joinClause);
                // GAP 4: same optional `AND <key> IS [NOT] NULL` enrichment as the table-scoped
                // getRandomJoinClauses overload above (#105716 / #105717).
                if (joinClause != null) {
                    j.setOnClause(maybeEnrichJoinOnClause(joinClause, options));
                }
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
