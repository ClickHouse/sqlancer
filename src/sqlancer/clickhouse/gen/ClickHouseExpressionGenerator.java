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
import sqlancer.clickhouse.ast.ClickHouseBinaryComparisonOperation;
import sqlancer.clickhouse.ast.ClickHouseBinaryFunctionOperation;
import sqlancer.clickhouse.ast.ClickHouseBinaryLogicalOperation;
import sqlancer.clickhouse.ast.ClickHouseCastOperation;
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

            java.util.List<String> path = Randomly.fromOptions(java.util.List.of("a"), java.util.List.of("b"));
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

        StringBuilder sb = new StringBuilder(fn.getName()).append("(").append(ClickHouseToStringVisitor.asString(col));
        if (fn.getShape() == ClickHouseGeoFunction.ArgShape.POINT_POLYGON) {

            sb.append(", ").append("[[(0.0, 0.0), (10.0, 0.0), (10.0, 10.0), (0.0, 10.0)]]");
        } else if (fn.getShape() == ClickHouseGeoFunction.ArgShape.POLYGON_POLYGON) {
            sb.append(", ").append("[[(0.0, 0.0), (10.0, 0.0), (10.0, 10.0), (0.0, 10.0)]]");
        }
        sb.append(")");
        return new sqlancer.clickhouse.ast.ClickHouseRawText(sb.toString());
    }

    public ClickHouseExpression generateDateIntervalArith(List<ClickHouseColumnReference> columns) {
        List<ClickHouseColumnReference> dateCols = temporalColumns(columns);
        if (dateCols.isEmpty()) {
            return null;
        }
        ClickHouseColumnReference col = Randomly.fromList(dateCols);
        String sign = Randomly.getBoolean() ? "+" : "-";

        if (Randomly.getBooleanWithRatherLowProbability()) {
            CompoundIntervalKind kind = Randomly.fromOptions(CompoundIntervalKind.values());
            int[] comps = randomCompoundIntervalComponents(kind);
            return new sqlancer.clickhouse.ast.ClickHouseRawText(
                    renderCompoundIntervalArith(ClickHouseToStringVisitor.asString(col), sign, kind, comps));
        }
        String unit = Randomly.fromOptions("SECOND", "MINUTE", "HOUR", "DAY", "WEEK", "MONTH", "QUARTER", "YEAR");
        int n = 1 + (int) Randomly.getNotCachedInteger(0, 365);
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

    private static List<ClickHouseColumnReference> temporalColumns(List<ClickHouseColumnReference> columns) {
        List<ClickHouseColumnReference> dateCols = new java.util.ArrayList<>();
        for (ClickHouseColumnReference c : columns) {
            com.clickhouse.data.ClickHouseDataType t = c.getColumn().getType().getType();
            if (t == com.clickhouse.data.ClickHouseDataType.Date || t == com.clickhouse.data.ClickHouseDataType.Date32
                    || t == com.clickhouse.data.ClickHouseDataType.DateTime
                    || t == com.clickhouse.data.ClickHouseDataType.DateTime64) {
                dateCols.add(c);
            }
        }
        return dateCols;
    }

    public enum CompoundIntervalKind {
        YEAR_TO_MONTH("YEAR TO MONTH", "YEAR", "MONTH"), DAY_TO_HOUR("DAY TO HOUR", "DAY", "HOUR"),
        DAY_TO_MINUTE("DAY TO MINUTE", "DAY", "HOUR", "MINUTE"),
        DAY_TO_SECOND("DAY TO SECOND", "DAY", "HOUR", "MINUTE", "SECOND"),
        HOUR_TO_MINUTE("HOUR TO MINUTE", "HOUR", "MINUTE"), HOUR_TO_SECOND("HOUR TO SECOND", "HOUR", "MINUTE", "SECOND"),
        MINUTE_TO_SECOND("MINUTE TO SECOND", "MINUTE", "SECOND");

        private final String sqlKindPair;
        private final String[] units;

        CompoundIntervalKind(String sqlKindPair, String... units) {
            this.sqlKindPair = sqlKindPair;
            this.units = units;
        }

        public String getSqlKindPair() {
            return sqlKindPair;
        }

        public String[] getUnits() {
            return units.clone();
        }
    }

    public static String renderCompoundIntervalLiteral(CompoundIntervalKind kind, int[] components) {
        if (components.length != kind.units.length) {
            throw new AssertionError("component count " + components.length + " != units " + kind.units.length);
        }
        StringBuilder v = new StringBuilder();
        if (kind == CompoundIntervalKind.YEAR_TO_MONTH) {
            v.append(components[0]).append('-').append(components[1]);
        } else {
            boolean leadingIsDay = kind.units[0].equals("DAY");

            v.append(leadingIsDay ? Integer.toString(components[0]) : String.format("%02d", components[0]));
            for (int k = 1; k < components.length; k++) {
                v.append(k == 1 && leadingIsDay ? ' ' : ':').append(String.format("%02d", components[k]));
            }
        }
        return "INTERVAL '" + v + "' " + kind.sqlKindPair;
    }

    public static String renderCompoundIntervalArith(String dateExprSql, String sign, CompoundIntervalKind kind,
            int[] components) {
        return "(" + dateExprSql + " " + sign + " " + renderCompoundIntervalLiteral(kind, components) + ")";
    }

    public static String renderDecomposedIntervalArith(String dateExprSql, String sign, CompoundIntervalKind kind,
            int[] components) {
        if (components.length != kind.units.length) {
            throw new AssertionError("component count " + components.length + " != units " + kind.units.length);
        }
        StringBuilder sb = new StringBuilder("(").append(dateExprSql);
        for (int k = 0; k < components.length; k++) {
            sb.append(' ').append(sign).append(" INTERVAL ").append(components[k]).append(' ').append(kind.units[k]);
        }
        return sb.append(')').toString();
    }

    public static int[] randomCompoundIntervalComponents(CompoundIntervalKind kind) {
        String[] units = kind.getUnits();
        int[] comps = new int[units.length];
        for (int k = 0; k < units.length; k++) {
            int bound = k == 0 ? 31 : "MONTH".equals(units[k]) ? 12 : "HOUR".equals(units[k]) ? 24 : 60;
            comps[k] = (int) Randomly.getNotCachedInteger(0, bound);
        }
        return comps;
    }

    public String[] renderCompoundAndDecomposedInterval(List<ClickHouseColumnReference> columns) {
        List<ClickHouseColumnReference> dateCols = temporalColumns(columns);
        if (dateCols.isEmpty()) {
            return null;
        }
        ClickHouseColumnReference col = Randomly.fromList(dateCols);
        CompoundIntervalKind kind = Randomly.fromOptions(CompoundIntervalKind.values());
        int[] comps = randomCompoundIntervalComponents(kind);
        String sign = Randomly.getBoolean() ? "+" : "-";
        String d = ClickHouseToStringVisitor.asString(col);
        String compound = "toString(" + renderCompoundIntervalArith(d, sign, kind, comps) + ")";
        String decomposed = "toString(" + renderDecomposedIntervalArith(d, sign, kind, comps) + ")";
        return new String[] { compound, decomposed, kind.name() };
    }

    public ClickHouseExpression generateMultiIf(List<ClickHouseColumnReference> columns) {
        List<ClickHouseColumnReference> numeric = numericColumns(columns);
        if (numeric.isEmpty()) {
            return null;
        }
        String c1 = renderNumericCondition(numeric);
        String c2 = renderNumericCondition(numeric);
        String a = ClickHouseToStringVisitor.asString(generateNumericExpressionWithColumns(numeric, 3));
        String b = ClickHouseToStringVisitor.asString(generateNumericExpressionWithColumns(numeric, 3));
        String d = ClickHouseToStringVisitor.asString(generateNumericExpressionWithColumns(numeric, 3));
        String inner;
        if (Randomly.getBoolean()) {
            inner = "CASE WHEN " + c1 + " THEN " + a + " WHEN " + c2 + " THEN " + b + " ELSE " + d + " END";
        } else {
            inner = "multiIf(" + c1 + ", " + a + ", " + c2 + ", " + b + ", " + d + ")";
        }

        return new sqlancer.clickhouse.ast.ClickHouseRawText("CAST((" + inner + ") AS Nullable(Float64))");
    }

    public String[] renderMultiIfAndNestedIf(List<ClickHouseColumnReference> columns) {
        List<ClickHouseColumnReference> numeric = numericColumns(columns);
        if (numeric.isEmpty()) {
            return null;
        }
        String c1 = renderNumericCondition(numeric);
        String c2 = renderNumericCondition(numeric);
        String a = ClickHouseToStringVisitor.asString(generateNumericExpressionWithColumns(numeric, 3));
        String b = ClickHouseToStringVisitor.asString(generateNumericExpressionWithColumns(numeric, 3));
        String d = ClickHouseToStringVisitor.asString(generateNumericExpressionWithColumns(numeric, 3));

        String multiIfSql = "CAST((multiIf(" + c1 + ", " + a + ", " + c2 + ", " + b + ", " + d
                + ")) AS Nullable(Float64))";
        String nestedIfSql = "CAST((if(" + c1 + ", " + a + ", if(" + c2 + ", " + b + ", " + d
                + "))) AS Nullable(Float64))";
        return new String[] { multiIfSql, nestedIfSql };
    }

    private String renderNumericCondition(List<ClickHouseColumnReference> numeric) {
        ClickHouseExpression cond = new ClickHouseBinaryComparisonOperation(
                generateNumericExpressionWithColumns(numeric, 2), generateNumericExpressionWithColumns(numeric, 2),
                ClickHouseBinaryComparisonOperation.ClickHouseBinaryComparisonOperator.getRandomOperator());
        return "(" + ClickHouseToStringVisitor.asString(cond) + ")";
    }

    public ClickHouseExpression generateStringCall(List<ClickHouseColumnReference> columns) {
        List<ClickHouseColumnReference> stringCols = new java.util.ArrayList<>();
        for (ClickHouseColumnReference c : columns) {
            if (c.getColumn().getType().getType() == ClickHouseDataType.String) {
                stringCols.add(c);
            }
        }
        if (stringCols.isEmpty()) {
            return null;
        }
        ClickHouseColumnReference col = Randomly.fromList(stringCols);
        String s = ClickHouseToStringVisitor.asString(col);

        String fn = Randomly.fromOptions("lower", "upper", "reverse", "length", "lengthUTF8", "trimLeft", "trimRight",
                "trimBoth", "empty", "notEmpty", "substring", "replaceRegexp", "replaceOne", "naturalSortKey",
                "overlayKeyword");
        String sql;
        switch (fn) {
        case "substring":
            sql = "substring(" + s + ", " + (1 + Randomly.getNotCachedInteger(0, 6)) + ", "
                    + (1 + Randomly.getNotCachedInteger(0, 8)) + ")";
            break;
        case "replaceRegexp":
            sql = "replaceRegexpAll(" + s + ", '[0-9]+', 'N')";
            break;
        case "replaceOne":
            sql = "replaceOne(" + s + ", 'a', 'b')";
            break;
        case "overlayKeyword":
            sql = "OVERLAY(" + s + " PLACING 'ab' FROM " + (1 + Randomly.getNotCachedInteger(0, 6))
                    + (Randomly.getBoolean() ? " FOR " + Randomly.getNotCachedInteger(0, 5) : "") + ")";
            break;
        default:
            sql = fn + "(" + s + ")";
            break;
        }
        return new sqlancer.clickhouse.ast.ClickHouseRawText(sql);
    }

    private static final List<String> TEXT_SEARCH_VOCABULARY = List.of("alpha", "bravo", "charlie", "delta", "echo",
            "foxtrot", "golf", "hotel", "india", "juliet", "clickhouse", "olap", "search", "token", "index", "query");

    public ClickHouseExpression generateTextSearchPredicate(List<ClickHouseColumnReference> columns) {
        List<ClickHouseColumnReference> stringCols = new java.util.ArrayList<>();
        for (ClickHouseColumnReference c : columns) {
            if (c.getColumn().getType().getType() == ClickHouseDataType.String) {
                stringCols.add(c);
            }
        }
        if (stringCols.isEmpty()) {
            return null;
        }
        String s = ClickHouseToStringVisitor.asString(Randomly.fromList(stringCols));
        String w1 = TEXT_SEARCH_VOCABULARY.get((int) Randomly.getNotCachedInteger(0, TEXT_SEARCH_VOCABULARY.size()));
        String w2 = TEXT_SEARCH_VOCABULARY.get((int) Randomly.getNotCachedInteger(0, TEXT_SEARCH_VOCABULARY.size()));
        String fn = Randomly.fromOptions("startsWith", "endsWith", "multiSearchAny");
        String sql;
        switch (fn) {
        case "startsWith":
            sql = "startsWith(" + s + ", '" + w1 + "')";
            break;
        case "endsWith":
            sql = "endsWith(" + s + ", '" + w1 + "')";
            break;
        default:
            sql = "multiSearchAny(" + s + ", ['" + w1 + "', '" + w2 + "'])";
            break;
        }
        return new sqlancer.clickhouse.ast.ClickHouseRawText(sql);
    }

    public ClickHouseExpression generateDateTransform(List<ClickHouseColumnReference> columns) {
        List<ClickHouseColumnReference> dateCols = new java.util.ArrayList<>();
        boolean dateTimeResolution = false;
        for (ClickHouseColumnReference c : columns) {
            ClickHouseDataType t = c.getColumn().getType().getType();
            if (t == ClickHouseDataType.Date || t == ClickHouseDataType.Date32 || t == ClickHouseDataType.DateTime
                    || t == ClickHouseDataType.DateTime64) {
                dateCols.add(c);
            }
        }
        if (dateCols.isEmpty()) {
            return null;
        }
        ClickHouseColumnReference col = Randomly.fromList(dateCols);
        ClickHouseDataType colType = col.getColumn().getType().getType();
        dateTimeResolution = colType == ClickHouseDataType.DateTime || colType == ClickHouseDataType.DateTime64;

        List<String> transforms = new java.util.ArrayList<>(
                List.of("toYYYYMM", "toYYYYMMDD", "toYear", "toMonth", "toDayOfMonth", "toDayOfWeek", "toISOWeek",
                        "toQuarter", "toStartOfMonth", "toStartOfYear", "toStartOfQuarter", "toRelativeMonthNum",
                        "toRelativeYearNum", "toRelativeWeekNum", "toRelativeDayNum"));
        if (dateTimeResolution) {

            transforms.addAll(List.of("toStartOfDay", "toStartOfHour", "toStartOfMinute", "toHour", "toMinute",
                    "toRelativeHourNum", "toYYYYMMDDhhmmss"));
        }
        String transform = Randomly.fromList(transforms);
        String lit = dateTimeResolution ? "toDateTime('2021-06-15 12:30:45')" : "toDate('2021-06-15')";
        String op = Randomly.fromOptions("<", "<=", "=", ">=", ">", "!=");
        String s = ClickHouseToStringVisitor.asString(col);
        String sql = "(" + transform + "(" + s + ") " + op + " " + transform + "(" + lit + "))";
        return new sqlancer.clickhouse.ast.ClickHouseRawText(sql);
    }

    public ClickHouseExpression generateScalarSubquery() {
        java.util.List<sqlancer.clickhouse.ClickHouseSchema.ClickHouseTable> tables = globalState.getSchema()
                .getDatabaseTables();
        if (tables.isEmpty()) {
            return null;
        }
        sqlancer.clickhouse.ClickHouseSchema.ClickHouseTable t = Randomly.fromList(tables);
        String qualified = globalState.getDatabaseName() + "." + t.getName();

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

        String fnName = Randomly.fromOptions("arrayMap", "arrayFilter", "arrayCount", "arrayExists", "arrayAll",
                "arrayFirst", "arrayLast", "arraySort", "arrayMin", "arrayMax", "arraySum");

        sqlancer.clickhouse.ast.ClickHouseExpression body;
        if (Randomly.getBoolean()) {
            body = new sqlancer.clickhouse.ast.ClickHouseRawText("x");
        } else {
            body = new sqlancer.clickhouse.ast.ClickHouseRawText("x + 1");
        }
        sqlancer.clickhouse.ast.ClickHouseLambda lambda = new sqlancer.clickhouse.ast.ClickHouseLambda(List.of("x"),
                body);
        StringBuilder sb = new StringBuilder(fnName).append("(");
        sb.append(ClickHouseToStringVisitor.asString(lambda));
        sb.append(", ");
        sb.append(ClickHouseToStringVisitor.asString(arrCol));
        sb.append(")");
        return new sqlancer.clickhouse.ast.ClickHouseRawText(sb.toString());
    }

    public ClickHouseExpression generateWindowCall(List<ClickHouseColumnReference> columns) {
        if (columns.isEmpty()) {
            return null;
        }

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
        case SUM:
        case COUNT:
        case MIN:
        case MAX:
        case AVG:
        case FIRST_VALUE:
        case LAST_VALUE:
            List<ClickHouseColumnReference> numeric = numericColumns(columns);
            if (numeric.isEmpty()) {
                return null;
            }
            argument = numeric.get((int) Randomly.getNotCachedInteger(0, numeric.size()));
            break;
        default:
            break;
        }

        List<ClickHouseExpression> partitionBy = new java.util.ArrayList<>();
        if (Randomly.getBoolean()) {
            partitionBy.add(columns.get((int) Randomly.getNotCachedInteger(0, columns.size())));
        }
        List<ClickHouseExpression> orderBy = new java.util.ArrayList<>();
        orderBy.add(columns.get((int) Randomly.getNotCachedInteger(0, columns.size())));
        return new sqlancer.clickhouse.ast.ClickHouseWindowFunction(kind, argument, partitionBy, orderBy);
    }

    public ClickHouseExpression generateDictGet(String dictName, ClickHouseColumnReference keyCol) {
        String sql = "dictGet('" + dictName + "', 'col', toUInt64(" + ClickHouseToStringVisitor.asString(keyCol) + "))";
        return new sqlancer.clickhouse.ast.ClickHouseRawText(sql);
    }

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
                    .getRandomScalar();
            ClickHouseExpression argExpr = generateNumericExpressionWithColumns(numeric, remainingDepth - 1);
            List<ClickHouseAggregateCombinator> chain = maybeGenerateCombinatorChain(columns, remainingDepth);
            return new ClickHouseAggregate(argExpr, func, chain);
        }
        return generateNumericExpressionWithColumns(numeric, remainingDepth);
    }

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

            return List.of(generateExpressionWithColumns(columns, remainingDepth));
        case RESAMPLE:

            return List.of(generateConstant(new ClickHouseLancerDataType(ClickHouseDataType.Int32)),
                    generateConstant(new ClickHouseLancerDataType(ClickHouseDataType.Int32)),
                    generateConstant(new ClickHouseLancerDataType(ClickHouseDataType.Int32)));
        default:
            return java.util.Collections.emptyList();
        }
    }

    private static List<ClickHouseColumnReference> numericColumns(List<ClickHouseColumnReference> cols) {
        return cols.stream().filter(c -> isNumeric(c.getColumn().getType().getType())).collect(Collectors.toList());
    }

    public static List<ClickHouseColumnReference> integerColumns(List<ClickHouseColumnReference> cols) {
        return cols.stream().filter(c -> isInteger(c.getColumn().getType().getType())).collect(Collectors.toList());
    }

    private static boolean isNumeric(ClickHouseDataType type) {
        return isInteger(type) || type == ClickHouseDataType.Float32 || type == ClickHouseDataType.Float64;
    }

    private static boolean isFloat(ClickHouseDataType type) {
        return type == ClickHouseDataType.Float32 || type == ClickHouseDataType.Float64;
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
                    .getRandomScalar();
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

        List<ClickHouseColumnReference[]> sameType = new ArrayList<>();
        List<ClickHouseColumnReference[]> numericPairs = new ArrayList<>();
        for (ClickHouseColumnReference l : leftColumns) {
            ClickHouseDataType lt = l.getColumn().getType().getType();
            if (isFloat(lt)) {
                continue;
            }
            for (ClickHouseColumnReference r : rightColumns) {
                ClickHouseDataType rt = r.getColumn().getType().getType();
                if (isFloat(rt)) {
                    continue;
                }
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

            throw new IgnoreMeException();
        }
        ClickHouseColumnReference[] pair = Randomly.fromList(pool);
        return new ClickHouseExpression.ClickHouseJoinOnClause(pair[0], pair[1]);
    }

    private ClickHouseExpression maybeEnrichJoinOnClause(ClickHouseExpression.ClickHouseJoinOnClause equality,
            ClickHouseExpression.ClickHouseJoin.JoinType joinType) {

        boolean outer = joinType == ClickHouseExpression.ClickHouseJoin.JoinType.RIGHT_OUTER
                || joinType == ClickHouseExpression.ClickHouseJoin.JoinType.LEFT_OUTER
                || joinType == ClickHouseExpression.ClickHouseJoin.JoinType.FULL_OUTER;
        boolean enrich = outer ? Randomly.getBoolean() : Randomly.getBooleanWithSmallProbability();
        if (!enrich) {
            return equality;
        }

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

                ClickHouseExpression.ClickHouseJoin.JoinType options = Randomly.fromList(DETERMINISTIC_JOIN_TYPES);
                ClickHouseExpression.ClickHouseJoin j = new ClickHouseExpression.ClickHouseJoin(leftTable, rightTable,
                        options, joinClause);

                if (joinClause != null) {
                    j.setOnClause(maybeEnrichJoinOnClause(joinClause, options));
                }
                joinStatements.add(j);
                leftTables.add(rightTable);
            }
        }
        return joinStatements;
    }

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

            int h = (int) Randomly.getNotCachedInteger(0, 24);
            int m = (int) Randomly.getNotCachedInteger(0, 60);
            int s = (int) Randomly.getNotCachedInteger(0, 60);
            String literal = String.format("%02d:%02d:%02d", h, m, s);
            return new ClickHouseCastOperation(ClickHouseCreateConstant.createStringConstant(literal),
                    new ClickHouseLancerDataType(term));
        }
        if (term instanceof sqlancer.clickhouse.ClickHouseType.Time64 t64) {

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

            sqlancer.clickhouse.ClickHouseType.EnumEntry entry = Randomly.fromList(en.entries());
            return ClickHouseCreateConstant.createStringConstant(entry.name());
        }
        if (term instanceof sqlancer.clickhouse.ClickHouseType.Tuple t) {

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

            double x = (globalState.getRandomly().getInteger() % 1000) / 10.0;
            double y = (globalState.getRandomly().getInteger() % 1000) / 10.0;
            String literal = "(" + x + ", " + y + ")";
            return new ClickHouseCastOperation(ClickHouseCreateConstant.createStringConstant(literal),
                    new ClickHouseLancerDataType(term));
        }
        if (term instanceof sqlancer.clickhouse.ClickHouseType.Ring) {

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

            String literal = "{\"a\": " + (globalState.getRandomly().getInteger() % 1000) + ", \"b\": \"x"
                    + (globalState.getRandomly().getInteger() % 10) + "\"}";
            return new ClickHouseCastOperation(ClickHouseCreateConstant.createStringConstant(literal),
                    new ClickHouseLancerDataType(term));
        }
        if (term instanceof sqlancer.clickhouse.ClickHouseType.Variant v) {

            ClickHouseType pickedAlt = Randomly.fromList(v.alternatives());
            return new ClickHouseCastOperation(generateConstantFromTerm(pickedAlt), new ClickHouseLancerDataType(term));
        }
        if (term instanceof sqlancer.clickhouse.ClickHouseType.Dynamic) {

            ClickHouseType inner = new Primitive(Kind.Int32);
            return new ClickHouseCastOperation(generateConstantFromTerm(inner), new ClickHouseLancerDataType(term));
        }
        if (term instanceof sqlancer.clickhouse.ClickHouseType.IntervalType i) {

            int n = 1 + (int) Randomly.getNotCachedInteger(0, 100);
            String unit = i.kind().name().toUpperCase();

            return ClickHouseCreateConstant.createStringConstant("INTERVAL " + n + " " + unit);
        }
        if (term instanceof sqlancer.clickhouse.ClickHouseType.SimpleAggregateFunctionType saf) {

            return generateConstantFromTerm(saf.arg());
        }

        if (term instanceof sqlancer.clickhouse.ClickHouseType.Nested
                || term instanceof sqlancer.clickhouse.ClickHouseType.AggregateFunctionType) {
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

            return new ClickHouseCastOperation(ClickHouseCreateConstant.createStringConstant(randomDateLiteral()),
                    new ClickHouseLancerDataType(new Primitive(kind)));
        case DateTime:
            return new ClickHouseCastOperation(ClickHouseCreateConstant.createStringConstant(randomDateTimeLiteral()),
                    new ClickHouseLancerDataType(new Primitive(Kind.DateTime)));
        case UUID:

            return new ClickHouseCastOperation(ClickHouseCreateConstant.createStringConstant(randomUuidLiteral()),
                    new ClickHouseLancerDataType(new Primitive(Kind.UUID)));
        case IPv4:

            return new ClickHouseCastOperation(ClickHouseCreateConstant.createStringConstant(randomIPv4Literal()),
                    new ClickHouseLancerDataType(new Primitive(Kind.IPv4)));
        case IPv6:

            return new ClickHouseCastOperation(ClickHouseCreateConstant.createStringConstant(randomIPv6Literal()),
                    new ClickHouseLancerDataType(new Primitive(Kind.IPv6)));
        default:

            throw new IgnoreMeException();
        }
    }

    private String randomUuidLiteral() {
        Randomly r = globalState.getRandomly();
        return String.format("%08x-%04x-%04x-%04x-%012x", r.getLong(0, 0x1_0000_0000L), r.getLong(0, 0x1_0000L),
                r.getLong(0, 0x1_0000L), r.getLong(0, 0x1_0000L), r.getLong(0, 0x1_0000_0000_0000L));
    }

    private String randomIPv4Literal() {
        return String.format("%d.%d.%d.%d", Randomly.getNotCachedInteger(0, 256), Randomly.getNotCachedInteger(0, 256),
                Randomly.getNotCachedInteger(0, 256), Randomly.getNotCachedInteger(0, 256));
    }

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

        if (Randomly.getBooleanWithRatherLowProbability() && !columnRefs.isEmpty()) {
            ClickHouseExpression dt = generateDateTransform(columnRefs);
            if (dt != null) {
                if (Randomly.getBoolean()) {
                    return dt;
                }
                return new ClickHouseBinaryLogicalOperation(base, dt,
                        ClickHouseBinaryLogicalOperation.ClickHouseBinaryLogicalOperator.AND);
            }
        }

        if (Randomly.getBooleanWithRatherLowProbability()) {

            long lit = Randomly.fromOptions(256L, 65536L, 2147483648L);
            ClickHouseExpression literal = ClickHouseCreateConstant.createInt64Constant(BigInteger.valueOf(lit));
            return new ClickHouseBinaryLogicalOperation(base, literal,
                    ClickHouseBinaryLogicalOperation.ClickHouseBinaryLogicalOperator.AND);
        }

        if (Randomly.getBooleanWithSmallProbability()) {

            String inner = Randomly.fromOptions("256", "1", "(1 = 1)", "(255 < 256)");

            String wrapped = Randomly.fromOptions("toNullable(" + inner + ")",
                    "CAST(" + inner + " AS LowCardinality(Nullable(UInt8)))", "CAST(" + inner + " AS Nullable(UInt8))",
                    "toLowCardinality(toNullable(" + inner + "))");
            ClickHouseExpression typedConjunct = new sqlancer.clickhouse.ast.ClickHouseRawText(wrapped);
            return new ClickHouseBinaryLogicalOperation(base, typedConjunct,
                    ClickHouseBinaryLogicalOperation.ClickHouseBinaryLogicalOperator.AND);
        }

        if (Randomly.getBooleanWithSmallProbability() && !columnRefs.isEmpty()) {
            ClickHouseExpression subquery = generateScalarSubquery();
            if (subquery != null) {
                ClickHouseColumnReference col = columnRefs
                        .get((int) Randomly.getNotCachedInteger(0, columnRefs.size()));
                return new ClickHouseBinaryComparisonOperation(col, subquery,
                        ClickHouseBinaryComparisonOperation.ClickHouseBinaryComparisonOperator.getRandomOperator());
            }
        }

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

        if (globalState.getClickHouseOptions().textSearchPredicateEmission
                && Randomly.getBooleanWithSmallProbability()) {
            ClickHouseExpression textPred = generateTextSearchPredicate(columnRefs);
            if (textPred != null) {
                return Randomly.getBoolean() ? textPred
                        : new ClickHouseBinaryLogicalOperation(base, textPred,
                                ClickHouseBinaryLogicalOperation.ClickHouseBinaryLogicalOperator.AND);
            }
        }

        if (ClickHouseVariantPredicateFactory.gateOpen(globalState.getClickHouseOptions().variantWhereEmission,
                Randomly.getBooleanWithSmallProbability())) {
            List<String> intExprs = integerColumns(columnRefs).stream()
                    .map(c -> "toInt64(" + ClickHouseToStringVisitor.asString(c) + ")").collect(Collectors.toList());
            List<String> strExprs = columnRefs.stream()
                    .map(c -> "toString(" + ClickHouseToStringVisitor.asString(c) + ")").collect(Collectors.toList());
            ClickHouseExpression variantPred = new sqlancer.clickhouse.ast.ClickHouseRawText(
                    ClickHouseVariantPredicateFactory.renderRandomFragment(intExprs, strExprs));
            return Randomly.getBoolean() ? variantPred
                    : new ClickHouseBinaryLogicalOperation(base, variantPred,
                            ClickHouseBinaryLogicalOperation.ClickHouseBinaryLogicalOperator.AND);
        }
        return base;
    }

    private ClickHouseExpression generateInSubquery(ClickHouseColumnReference outer) {

        if (Randomly.getBooleanWithRatherLowProbability()) {
            ClickHouseExpression joined = generateJoinedDerivedInSubquery(outer);
            if (joined != null) {
                return joined;
            }
        }
        java.util.List<sqlancer.clickhouse.ClickHouseSchema.ClickHouseTable> tables = globalState.getSchema()
                .getDatabaseTables();
        if (tables.isEmpty()) {
            return null;
        }
        ClickHouseDataType outerType = outer.getColumn().getType().getType();
        boolean outerNumeric = isNumeric(outerType);
        sqlancer.clickhouse.ClickHouseSchema.ClickHouseTable t = Randomly.fromList(tables);
        String qualified = globalState.getDatabaseName() + "." + t.getName();

        java.util.List<ClickHouseColumn> candidates = t.getColumns().stream()
                .filter(c -> outerNumeric ? isNumeric(c.getType().getType()) : c.getType().getType() == outerType)
                .collect(Collectors.toList());
        if (candidates.isEmpty()) {
            return null;
        }
        ClickHouseColumn inner = Randomly.fromList(candidates);
        String colName = inner.getName();
        StringBuilder sb = new StringBuilder("(SELECT ").append(colName).append(" FROM ").append(qualified);

        if (Randomly.getBoolean()) {
            ClickHouseExpression bound = generateConstantFromTerm(inner.getType().getTypeTerm());
            String op = Randomly.fromOptions(">=", "<=", "!=");
            sb.append(" WHERE ").append(colName).append(" ").append(op).append(" ")
                    .append(ClickHouseToStringVisitor.asString(bound));
        }
        sb.append(")");
        return new sqlancer.clickhouse.ast.ClickHouseRawText(sb.toString());
    }

    public ClickHouseExpression generateJoinedDerivedInSubquery(ClickHouseColumnReference outer) {
        if (!isNumeric(outer.getColumn().getType().getType())) {
            return null;
        }
        java.util.List<sqlancer.clickhouse.ClickHouseSchema.ClickHouseTable> numericTables = globalState.getSchema()
                .getDatabaseTables().stream()
                .filter(t -> t.getColumns().stream().anyMatch(c -> isNumeric(c.getType().getType())))
                .collect(Collectors.toList());
        if (numericTables.isEmpty()) {
            return null;
        }
        String db = globalState.getDatabaseName();
        int joinCount = Randomly.getBoolean() ? 2 : 3;

        java.util.List<sqlancer.clickhouse.ClickHouseSchema.ClickHouseTable> sources = new java.util.ArrayList<>();
        java.util.List<ClickHouseColumn> joinCols = new java.util.ArrayList<>();
        for (int i = 0; i <= joinCount; i++) {
            sqlancer.clickhouse.ClickHouseSchema.ClickHouseTable t = Randomly.fromList(numericTables);
            sources.add(t);
            joinCols.add(Randomly.fromList(t.getColumns().stream().filter(c -> isNumeric(c.getType().getType()))
                    .collect(Collectors.toList())));
        }

        boolean bothDerived = !Randomly.getBooleanWithRatherLowProbability();
        boolean firstDerived = bothDerived || Randomly.getBoolean();
        boolean lastDerived = bothDerived || Randomly.getBoolean();

        String k = joinCols.get(0).getName();
        StringBuilder sb = new StringBuilder("(SELECT a.").append(k).append(" FROM ");
        if (firstDerived) {
            sb.append("(SELECT ").append(k).append(" FROM ").append(db).append(".")
                    .append(sources.get(0).getName()).append(") AS a");
        } else {
            sb.append(db).append(".").append(sources.get(0).getName()).append(" AS a");
        }

        String prevHandle = "a." + k;

        for (int i = 1; i <= joinCount; i++) {
            boolean isLast = i == joinCount;
            if (isLast && lastDerived) {
                String lastCol = joinCols.get(i).getName();
                sb.append(" JOIN (SELECT ").append(lastCol);
                if (!lastCol.equals(k)) {
                    sb.append(" AS ").append(k);
                }
                sb.append(" FROM ").append(db).append(".").append(sources.get(i).getName()).append(") AS b ON b.")
                        .append(k).append(" = ").append(prevHandle);
            } else {
                String alias = isLast ? "b" : "e" + (i - 1);
                sb.append(" JOIN ").append(db).append(".").append(sources.get(i).getName()).append(" AS ")
                        .append(alias).append(" ON ").append(alias).append(".").append(joinCols.get(i).getName())
                        .append(" = ").append(prevHandle);

                prevHandle = alias + "." + joinCols.get(i).getName();
            }
        }

        if (Randomly.getBoolean()) {
            ClickHouseExpression bound = generateConstantFromTerm(joinCols.get(0).getType().getTypeTerm());
            sb.append(" WHERE a.").append(k).append(Randomly.fromOptions(" >= ", " <= ", " != "))
                    .append(ClickHouseToStringVisitor.asString(bound));
        }
        sb.append(")");
        return new sqlancer.clickhouse.ast.ClickHouseRawText(sb.toString());
    }

    public ClickHouseExpression generateJoinedDerivedInPredicate(List<ClickHouseColumnReference> columns) {
        List<ClickHouseColumnReference> numeric = numericColumns(columns);
        if (numeric.isEmpty()) {
            return null;
        }
        ClickHouseColumnReference col = Randomly.fromList(numeric);
        ClickHouseExpression rhs = generateJoinedDerivedInSubquery(col);
        if (rhs == null) {
            return null;
        }
        ClickHouseBinaryComparisonOperation.ClickHouseBinaryComparisonOperator op = Randomly.getBoolean()
                ? ClickHouseBinaryComparisonOperation.ClickHouseBinaryComparisonOperator.IN
                : ClickHouseBinaryComparisonOperation.ClickHouseBinaryComparisonOperator.NOT_IN;
        return new ClickHouseBinaryComparisonOperation(col, rhs, op);
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

                ClickHouseExpression.ClickHouseJoin.JoinType options = Randomly.fromList(DETERMINISTIC_JOIN_TYPES);
                ClickHouseExpression.ClickHouseJoin j = new ClickHouseExpression.ClickHouseJoin(leftTable, rightTable,
                        options, joinClause);

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
