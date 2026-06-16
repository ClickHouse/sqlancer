package sqlancer.clickhouse.gen;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.clickhouse.data.ClickHouseDataType;

import sqlancer.Randomly;
import sqlancer.clickhouse.ClickHouseErrors;
import sqlancer.clickhouse.ClickHouseProvider;
import sqlancer.clickhouse.ClickHouseSchema;
import sqlancer.clickhouse.ClickHouseToStringVisitor;
import sqlancer.clickhouse.ast.ClickHouseBinaryArithmeticOperation;
import sqlancer.clickhouse.ast.ClickHouseBinaryFunctionOperation;
import sqlancer.clickhouse.ast.ClickHouseColumnReference;
import sqlancer.clickhouse.ast.ClickHouseExpression;
import sqlancer.clickhouse.ast.ClickHouseRawText;
import sqlancer.clickhouse.ast.ClickHouseUnaryFunctionOperation;
import sqlancer.clickhouse.ast.ClickHouseUnaryPrefixOperation;
import sqlancer.clickhouse.ast.constant.ClickHouseCreateConstant;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.common.visitor.BinaryOperation;
import sqlancer.common.visitor.UnaryOperation;

public class ClickHouseTableGenerator {

    private enum ClickHouseEngine {

        MergeTree, ReplacingMergeTree, SummingMergeTree, CollapsingMergeTree, VersionedCollapsingMergeTree,
        AggregatingMergeTree
    }

    private final StringBuilder sb = new StringBuilder();
    private final String tableName;
    private int columnId;
    private final List<String> columnNames = new ArrayList<>();
    private final List<ClickHouseSchema.ClickHouseColumn> columns = new ArrayList<>();
    private final ClickHouseProvider.ClickHouseGlobalState globalState;

    public ClickHouseTableGenerator(String tableName, ClickHouseProvider.ClickHouseGlobalState globalState) {
        this.tableName = tableName;
        this.globalState = globalState;
    }

    public static SQLQueryAdapter createTableStatement(String tableName,
            ClickHouseProvider.ClickHouseGlobalState globalState) {
        ClickHouseTableGenerator chTableGenerator = new ClickHouseTableGenerator(tableName, globalState);
        chTableGenerator.start();
        ExpectedErrors errors = new ExpectedErrors();
        ClickHouseErrors.addExpectedExpressionErrors(errors);
        ClickHouseErrors.addTextIndexErrors(errors);
        return new SQLQueryAdapter(chTableGenerator.sb.toString(), errors, true);
    }

    public void start() {
        ClickHouseExpressionGenerator gen = new ClickHouseExpressionGenerator(globalState).allowAggregates(false);
        sb.append("CREATE ");
        sb.append("TABLE ");
        if (Randomly.getBoolean()) {
            sb.append("IF NOT EXISTS ");
        }
        sb.append(this.globalState.getDatabaseName());
        sb.append(".");
        sb.append(this.tableName);
        sb.append(" (");
        int nrColumns = 1 + Randomly.smallNumber();

        for (int i = 0; i < nrColumns; i++) {
            columns.add(ClickHouseSchema.ClickHouseColumn.createDummy(ClickHouseCommon.createColumnName(i), null,
                    globalState));
        }

        ClickHouseEngine engine = pickEngine(columns);
        for (int i = 0; i < nrColumns; i++) {
            if (i != 0) {
                sb.append(", ");
            }
            ClickHouseSchema.ClickHouseColumn dummy = columns.get(i);
            String columnName = dummy.getName();
            ClickHouseColumnBuilder columnBuilder = new ClickHouseColumnBuilder();
            sb.append(columnBuilder.createColumn(columnName, dummy.getType(), globalState, columns));
            columnNames.add(columnName);
            columnId++;
        }
        if (Randomly.getBooleanWithSmallProbability()) {
            for (int i = 0; i < Randomly.smallNumber(); i++) {
                addColumnsConstraint(gen);
            }
        }

        if (Randomly.getBooleanWithSmallProbability()) {
            int idxCount = 1 + (int) Randomly.getNotCachedInteger(0, Math.min(2, columns.size()));
            for (int i = 0; i < idxCount; i++) {
                String skipIndex = renderSkipIndex(i, columns.get(i % columns.size()));
                if (skipIndex != null) {
                    sb.append(", ");
                    sb.append(skipIndex);
                }
            }
        }

        if (columns.size() >= 2 && Randomly.getBooleanWithSmallProbability()) {
            String projection = renderProjection(0, columns);
            if (projection != null) {
                sb.append(", ");
                sb.append(projection);
            }
        }
        sb.append(") ENGINE = ");
        sb.append(engine);
        sb.append("(");
        sb.append(renderEngineArgs(engine));
        sb.append(") ");
        if (isMergeTreeFamily(engine)) {
            Supplier<ClickHouseExpression> exprFactory = () -> gen.generateExpressionWithColumns(
                    columns.stream().map(c -> c.asColumnReference(null)).collect(Collectors.toList()), 3);

            boolean engineRequiresNonEmptyOrderBy = isDedupeEngine(engine);

            String fallbackKeyColumn = columns.stream().filter(ClickHouseTableGenerator::isBareKeyColumn)
                    .map(ClickHouseSchema.ClickHouseColumn::getName).findFirst().orElse(columns.get(0).getName());
            String fallbackOrderBy = engineRequiresNonEmptyOrderBy ? " ORDER BY " + fallbackKeyColumn + " "
                    : " ORDER BY tuple() ";

            String sampleByColumn = null;
            String primaryKeyClause = null;
            boolean orderByHandled = false;

            if (!isDedupeEngine(engine) && Randomly.getBooleanWithSmallProbability()) {
                java.util.List<String> bareCols = columns.stream().filter(ClickHouseTableGenerator::isBareKeyColumn)
                        .map(ClickHouseSchema.ClickHouseColumn::getName).collect(Collectors.toList());
                if (bareCols.size() >= 2) {
                    java.util.List<String> obCols = pickDistinct(bareCols,
                            2 + (int) Randomly.getNotCachedInteger(0, Math.min(2, bareCols.size() - 1)));
                    int pkCount = 1 + (int) Randomly.getNotCachedInteger(0, obCols.size() - 1);
                    sb.append(" ORDER BY (").append(String.join(", ", obCols)).append(")");
                    java.util.List<String> pkCols = obCols.subList(0, pkCount);
                    primaryKeyClause = " PRIMARY KEY (" + String.join(", ", pkCols) + ")";
                    sampleByColumn = firstBareUnsignedIntIn(pkCols);
                    orderByHandled = true;
                }
            }

            if (!orderByHandled && Randomly.getBoolean()) {

                java.util.function.Predicate<ClickHouseExpression> orderByValidator = isDedupeEngine(engine)
                        ? ClickHouseTableGenerator::isValidOrderByForDedupe : ClickHouseTableGenerator::isValidOrderBy;

                ClickHouseExpression expr = null;
                if (!isDedupeEngine(engine) && rollSuspiciousKey()) {
                    expr = buildSuspiciousKey(false);
                }
                if (expr == null) {
                    expr = generateValidated(exprFactory, orderByValidator);
                }
                if (expr != null) {
                    sb.append(" ORDER BY ");
                    sb.append(ClickHouseToStringVisitor.asString(expr));

                    sampleByColumn = bareIntegerColumnName(expr);
                } else {
                    sb.append(fallbackOrderBy);
                    sampleByColumn = fallbackSampleColumn(engineRequiresNonEmptyOrderBy);
                }
            } else if (!orderByHandled) {
                sb.append(fallbackOrderBy);
                sampleByColumn = fallbackSampleColumn(engineRequiresNonEmptyOrderBy);
            }

            if (primaryKeyClause != null) {
                sb.append(primaryKeyClause);
            }

            if (Randomly.getBoolean()) {

                ClickHouseExpression expr = null;
                if (!isDedupeEngine(engine) && rollSuspiciousKey()) {
                    expr = buildSuspiciousKey(true);
                }
                if (expr == null) {
                    expr = generateValidated(exprFactory, ClickHouseTableGenerator::isValidPartitionBy);
                }
                if (expr != null) {
                    sb.append(" PARTITION BY ");
                    sb.append(ClickHouseToStringVisitor.asString(expr));
                }
            }

            if (sampleByColumn != null && Randomly.getBoolean()) {
                sb.append(" SAMPLE BY ");
                sb.append(sampleByColumn);
            }

            sb.append(" SETTINGS allow_suspicious_indices=1, allow_nullable_key=1");
            if (Randomly.getBooleanWithSmallProbability()) {
                sb.append(", min_bytes_for_wide_part=0");
            }

            if (Randomly.getBooleanWithSmallProbability()) {
                sb.append(", index_granularity=").append(Randomly.fromOptions(1L, 2L, 4L, 8L));
            }
            if (Randomly.getBooleanWithSmallProbability()) {
                sb.append(", enable_mixed_granularity_parts=1");
            }
            if (Randomly.getBooleanWithSmallProbability()) {
                sb.append(", ratio_of_defaults_for_sparse_serialization=")
                        .append(Randomly.fromOptions(0.0, 0.5, 0.95, 1.0));
            }

            if (Randomly.getBooleanWithRatherLowProbability()) {
                sb.append(", enable_block_number_column=1, enable_block_offset_column=1");
            }
        }

    }

    private static boolean isMergeTreeFamily(ClickHouseEngine engine) {
        return engine == ClickHouseEngine.MergeTree || isDedupeEngine(engine);
    }

    private static boolean isDedupeEngine(ClickHouseEngine engine) {
        return engine == ClickHouseEngine.ReplacingMergeTree || engine == ClickHouseEngine.SummingMergeTree
                || engine == ClickHouseEngine.CollapsingMergeTree
                || engine == ClickHouseEngine.VersionedCollapsingMergeTree
                || engine == ClickHouseEngine.AggregatingMergeTree;
    }

    private static boolean isSimpleAggregateColumn(ClickHouseSchema.ClickHouseColumn col) {
        return col.getType().getTypeTerm()
                .unwrap() instanceof sqlancer.clickhouse.ClickHouseType.SimpleAggregateFunctionType;
    }

    static boolean isValidSign(ClickHouseSchema.ClickHouseColumn col) {
        sqlancer.clickhouse.ClickHouseType term = col.getType().getTypeTerm();
        return term instanceof sqlancer.clickhouse.ClickHouseType.Primitive p
                && p.kind() == sqlancer.clickhouse.ClickHouseType.Kind.Int8;
    }

    static boolean isBareKeyColumn(ClickHouseSchema.ClickHouseColumn col) {
        sqlancer.clickhouse.ClickHouseType u = col.getType().getTypeTerm().unwrap();
        return u instanceof sqlancer.clickhouse.ClickHouseType.Primitive
                || u instanceof sqlancer.clickhouse.ClickHouseType.FixedString
                || u instanceof sqlancer.clickhouse.ClickHouseType.Decimal
                || u instanceof sqlancer.clickhouse.ClickHouseType.DateTime64Type
                || u instanceof sqlancer.clickhouse.ClickHouseType.Enum
                || u instanceof sqlancer.clickhouse.ClickHouseType.Time
                || u instanceof sqlancer.clickhouse.ClickHouseType.Time64;
    }

    static java.util.List<String> pickDistinct(java.util.List<String> src, int k) {
        java.util.List<String> pool = new java.util.ArrayList<>(src);
        java.util.List<String> out = new java.util.ArrayList<>();
        for (int i = 0; i < k && !pool.isEmpty(); i++) {
            out.add(pool.remove((int) Randomly.getNotCachedInteger(0, pool.size())));
        }
        return out;
    }

    private ClickHouseEngine pickEngine(List<ClickHouseSchema.ClickHouseColumn> cols) {
        int roll = (int) Randomly.getNotCachedInteger(0, 100);
        if (roll < 78) {
            return ClickHouseEngine.MergeTree;
        }
        if (roll < 86) {

            boolean hasVerCandidate = cols.stream().anyMatch(this::isValidReplacingVer);
            return hasVerCandidate ? ClickHouseEngine.ReplacingMergeTree : ClickHouseEngine.MergeTree;
        }
        if (roll < 92) {

            boolean hasSumCandidate = cols.stream().anyMatch(this::isValidSummingCol);
            return hasSumCandidate ? ClickHouseEngine.SummingMergeTree : ClickHouseEngine.MergeTree;
        }
        if (roll < 96) {

            boolean hasSign = cols.stream().anyMatch(ClickHouseTableGenerator::isValidSign);
            if (!hasSign) {
                return ClickHouseEngine.MergeTree;
            }
            if (roll < 94) {
                return ClickHouseEngine.CollapsingMergeTree;
            }
            boolean hasVerCandidate = cols.stream().anyMatch(this::isValidReplacingVer);
            return hasVerCandidate ? ClickHouseEngine.VersionedCollapsingMergeTree
                    : ClickHouseEngine.CollapsingMergeTree;
        }

        boolean hasSimpleAgg = cols.stream().anyMatch(ClickHouseTableGenerator::isSimpleAggregateColumn);
        boolean hasBareKey = cols.stream().anyMatch(ClickHouseTableGenerator::isBareKeyColumn);
        return hasSimpleAgg && hasBareKey ? ClickHouseEngine.AggregatingMergeTree : ClickHouseEngine.MergeTree;
    }

    private String renderEngineArgs(ClickHouseEngine engine) {
        if (engine == ClickHouseEngine.ReplacingMergeTree) {
            List<ClickHouseSchema.ClickHouseColumn> candidates = columns.stream().filter(this::isValidReplacingVer)
                    .collect(Collectors.toList());
            if (candidates.isEmpty() || !Randomly.getBoolean()) {
                return "";
            }
            return Randomly.fromList(candidates).getName();
        }
        if (engine == ClickHouseEngine.SummingMergeTree) {
            List<ClickHouseSchema.ClickHouseColumn> candidates = columns.stream().filter(this::isValidSummingCol)
                    .collect(Collectors.toList());
            if (candidates.isEmpty() || !Randomly.getBoolean()) {
                return "";
            }

            return Randomly.fromList(candidates).getName();
        }
        if (engine == ClickHouseEngine.CollapsingMergeTree) {

            List<ClickHouseSchema.ClickHouseColumn> signs = columns.stream()
                    .filter(ClickHouseTableGenerator::isValidSign).collect(Collectors.toList());
            return Randomly.fromList(signs).getName();
        }
        if (engine == ClickHouseEngine.VersionedCollapsingMergeTree) {

            List<ClickHouseSchema.ClickHouseColumn> signs = columns.stream()
                    .filter(ClickHouseTableGenerator::isValidSign).collect(Collectors.toList());
            List<ClickHouseSchema.ClickHouseColumn> vers = columns.stream().filter(this::isValidReplacingVer)
                    .collect(Collectors.toList());
            return Randomly.fromList(signs).getName() + ", " + Randomly.fromList(vers).getName();
        }
        return "";
    }

    private boolean isValidReplacingVer(ClickHouseSchema.ClickHouseColumn col) {
        sqlancer.clickhouse.ClickHouseType term = col.getType().getTypeTerm();
        if (term instanceof sqlancer.clickhouse.ClickHouseType.Nullable
                || term instanceof sqlancer.clickhouse.ClickHouseType.LowCardinality
                || term instanceof sqlancer.clickhouse.ClickHouseType.Array
                || term instanceof sqlancer.clickhouse.ClickHouseType.Unknown) {
            return false;
        }
        ClickHouseDataType t = col.getType().getType();
        switch (t) {
        case UInt8:
        case UInt16:
        case UInt32:
        case UInt64:
        case UInt128:
        case UInt256:
        case Date:
        case Date32:
        case DateTime:
        case DateTime32:
        case DateTime64:
            return true;
        default:
            return false;
        }
    }

    private boolean isValidSummingCol(ClickHouseSchema.ClickHouseColumn col) {
        sqlancer.clickhouse.ClickHouseType term = col.getType().getTypeTerm();
        if (term instanceof sqlancer.clickhouse.ClickHouseType.Nullable
                || term instanceof sqlancer.clickhouse.ClickHouseType.LowCardinality
                || term instanceof sqlancer.clickhouse.ClickHouseType.Array
                || term instanceof sqlancer.clickhouse.ClickHouseType.Unknown) {
            return false;
        }
        ClickHouseDataType t = col.getType().getType();
        switch (t) {
        case Int8:
        case Int16:
        case Int32:
        case Int64:
        case UInt8:
        case UInt16:
        case UInt32:
        case UInt64:
        case Float32:
        case Float64:
        case Decimal:
            return true;
        default:
            return false;
        }
    }

    private String renderProjection(int idx, List<ClickHouseSchema.ClickHouseColumn> cols) {
        String name = String.format("p_%d", idx);

        if (Randomly.getBoolean()) {

            String groupCol = cols.get((int) Randomly.getNotCachedInteger(0, cols.size())).getName();
            return String.format("PROJECTION %s (SELECT count() GROUP BY %s)", name, groupCol);
        }

        int subsetSize = Math.min(cols.size(), 1 + (int) Randomly.getNotCachedInteger(0, 2));
        List<ClickHouseSchema.ClickHouseColumn> subset = Randomly.extractNrRandomColumns(cols, subsetSize);
        String colList = subset.stream().map(ClickHouseSchema.ClickHouseColumn::getName)
                .collect(Collectors.joining(", "));

        return String.format("PROJECTION %s (SELECT %s ORDER BY %s)", name, colList, colList);
    }

    private String renderSkipIndex(int idx, ClickHouseSchema.ClickHouseColumn col) {
        ClickHouseDataType t = col.getType().getType();
        String textTarget = textIndexTarget(col);

        List<String[]> candidates = new ArrayList<>();
        candidates.add(new String[] { col.getName(), "bloom_filter(0.01)" });
        candidates.add(new String[] { col.getName(), "set(100)" });
        if (t == ClickHouseDataType.Int32 || t == ClickHouseDataType.String) {
            candidates.add(new String[] { col.getName(), "minmax" });
        }
        if (t == ClickHouseDataType.String) {
            candidates.add(new String[] { col.getName(), "ngrambf_v1(3, 256, 2, 0)" });
        }
        if (textTarget != null) {
            candidates.add(new String[] { textTarget, "text(tokenizer = " + pickTokenizer() + ")" });
            candidates.add(new String[] { textTarget, "text(tokenizer = " + pickTokenizer() + ")" });
        }

        String[] chosen = Randomly.fromList(candidates);
        int granularity = chosen[1].startsWith("text(") ? 1 : Randomly.fromOptions(1, 2, 4);
        return String.format("INDEX idx_%s_%d %s TYPE %s GRANULARITY %d", col.getName(), idx, chosen[0], chosen[1],
                granularity);
    }

    private static String pickTokenizer() {
        return Randomly.fromOptions("'splitByNonAlpha'", "ngrams(2)", "ngrams(3)", "ngrams(4)", "'array'", "'asciiCJK'",
                "splitByString([' '])", "splitByString([' ', '-', '::'])", "sparseGrams(3, 5)");
    }

    private static String textIndexTarget(ClickHouseSchema.ClickHouseColumn col) {
        sqlancer.clickhouse.ClickHouseType u = col.getType().getTypeTerm().unwrap();
        if (isStringLeaf(u)) {
            return col.getName();
        }
        if (u instanceof sqlancer.clickhouse.ClickHouseType.Array arr && isStringLeaf(arr.inner().unwrap())) {
            return col.getName();
        }
        if (u instanceof sqlancer.clickhouse.ClickHouseType.Map m) {
            boolean keyStr = isStringLeaf(m.keyType().unwrap());
            boolean valStr = isStringLeaf(m.valueType().unwrap());
            if (keyStr && valStr) {
                return Randomly.getBoolean() ? "mapKeys(" + col.getName() + ")" : "mapValues(" + col.getName() + ")";
            }
            if (keyStr) {
                return "mapKeys(" + col.getName() + ")";
            }
            if (valStr) {
                return "mapValues(" + col.getName() + ")";
            }
        }
        return null;
    }

    private static boolean isStringLeaf(sqlancer.clickhouse.ClickHouseType t) {
        return t instanceof sqlancer.clickhouse.ClickHouseType.Primitive p
                && p.kind() == sqlancer.clickhouse.ClickHouseType.Kind.String
                || t instanceof sqlancer.clickhouse.ClickHouseType.FixedString;
    }

    private static final int CLAUSE_VALIDATION_RETRY_LIMIT = 5;

    private static ClickHouseExpression generateValidated(Supplier<ClickHouseExpression> factory,
            Predicate<ClickHouseExpression> validator) {
        for (int attempt = 0; attempt < CLAUSE_VALIDATION_RETRY_LIMIT; attempt++) {
            ClickHouseExpression expr = factory.get();
            if (validator.test(expr)) {
                return expr;
            }
        }
        return null;
    }

    static boolean isValidOrderBy(ClickHouseExpression expr) {
        return hasColumnReference(expr) && !referencesUnorderableComposite(expr);
    }

    private static boolean referencesUnorderableComposite(ClickHouseExpression expr) {
        if (expr instanceof ClickHouseColumnReference cr) {
            sqlancer.clickhouse.ClickHouseType t = cr.getColumn().getType().getTypeTerm().unwrap();
            return t instanceof sqlancer.clickhouse.ClickHouseType.Tuple
                    || t instanceof sqlancer.clickhouse.ClickHouseType.Map
                    || t instanceof sqlancer.clickhouse.ClickHouseType.Enum
                    || t instanceof sqlancer.clickhouse.ClickHouseType.Nested
                    || t instanceof sqlancer.clickhouse.ClickHouseType.JSON
                    || t instanceof sqlancer.clickhouse.ClickHouseType.Variant
                    || t instanceof sqlancer.clickhouse.ClickHouseType.Dynamic
                    || t instanceof sqlancer.clickhouse.ClickHouseType.Point
                    || t instanceof sqlancer.clickhouse.ClickHouseType.Ring
                    || t instanceof sqlancer.clickhouse.ClickHouseType.Polygon
                    || t instanceof sqlancer.clickhouse.ClickHouseType.MultiPolygon
                    || t instanceof sqlancer.clickhouse.ClickHouseType.AggregateFunctionType
                    || t instanceof sqlancer.clickhouse.ClickHouseType.SimpleAggregateFunctionType
                    || t instanceof sqlancer.clickhouse.ClickHouseType.IntervalType
                    || t instanceof sqlancer.clickhouse.ClickHouseType.Time
                    || t instanceof sqlancer.clickhouse.ClickHouseType.Time64;
        }
        if (expr instanceof BinaryOperation<?> bo) {
            return referencesUnorderableComposite((ClickHouseExpression) bo.getLeft())
                    || referencesUnorderableComposite((ClickHouseExpression) bo.getRight());
        }
        if (expr instanceof UnaryOperation<?> uo) {
            return referencesUnorderableComposite((ClickHouseExpression) uo.getExpression());
        }
        return false;
    }

    static boolean isValidOrderByForDedupe(ClickHouseExpression expr) {

        return expr instanceof ClickHouseColumnReference cr && isBareKeyColumn(cr.getColumn());
    }

    static boolean isValidPartitionBy(ClickHouseExpression expr) {
        return hasColumnReference(expr) && !referencesFloatColumn(expr) && !isFloatResultExpression(expr)
                && !referencesUnorderableComposite(expr);
    }

    private static final java.util.Set<String> FLOAT_RESULT_FUNCTIONS = java.util.Set.of("exp", "exp2", "exp10", "sqrt",
            "cbrt", "erf", "sin", "cos", "tan", "asin", "acos", "atan", "sinh", "cosh", "tanh", "radians", "degrees",
            "log", "log2", "log10", "ln", "pow", "power");

    private static boolean isFloatResultExpression(ClickHouseExpression expr) {
        if (expr instanceof ClickHouseUnaryFunctionOperation ufo) {

            if (FLOAT_RESULT_FUNCTIONS.contains(ufo.getOperatorRepresentation())) {
                return true;
            }
            return isFloatResultExpression(ufo.getExpression());
        }
        if (expr instanceof ClickHouseBinaryArithmeticOperation bao) {

            if (bao.getOperator() == ClickHouseBinaryArithmeticOperation.ClickHouseBinaryArithmeticOperator.DIV) {
                return true;
            }
            return isFloatResultExpression(bao.getLeft()) || isFloatResultExpression(bao.getRight());
        }
        if (expr instanceof ClickHouseRawText rt) {

            String sql = rt.getSql();
            int paren = sql.indexOf('(');
            String head = paren >= 0 ? sql.substring(0, paren).trim().toLowerCase(java.util.Locale.ROOT) : "";
            return FLOAT_RESULT_FUNCTIONS.contains(head);
        }
        if (expr instanceof BinaryOperation<?> bo) {
            return isFloatResultExpression((ClickHouseExpression) bo.getLeft())
                    || isFloatResultExpression((ClickHouseExpression) bo.getRight());
        }
        if (expr instanceof UnaryOperation<?> uo) {
            return isFloatResultExpression((ClickHouseExpression) uo.getExpression());
        }
        return false;
    }

    static boolean isValidSampleBy(ClickHouseExpression expr) {
        return hasColumnReference(expr) && !referencesUnorderableComposite(expr);
    }

    private static String bareIntegerColumnName(ClickHouseExpression expr) {
        if (!(expr instanceof ClickHouseColumnReference cr)) {
            return null;
        }
        return bareUnsignedIntColumnName(cr.getColumn());
    }

    static String bareUnsignedIntColumnName(ClickHouseSchema.ClickHouseColumn col) {
        sqlancer.clickhouse.ClickHouseType term = col.getType().getTypeTerm();
        if (term instanceof sqlancer.clickhouse.ClickHouseType.Nullable
                || term instanceof sqlancer.clickhouse.ClickHouseType.LowCardinality
                || term instanceof sqlancer.clickhouse.ClickHouseType.Array
                || term instanceof sqlancer.clickhouse.ClickHouseType.Unknown) {
            return null;
        }
        switch (col.getType().getType()) {
        case UInt8:
        case UInt16:
        case UInt32:
        case UInt64:
            return col.getName();
        default:
            return null;
        }
    }

    private String firstBareUnsignedIntIn(java.util.List<String> pkColumnNames) {
        return columns.stream().filter(c -> pkColumnNames.contains(c.getName()))
                .map(ClickHouseTableGenerator::bareUnsignedIntColumnName).filter(java.util.Objects::nonNull)
                .findFirst().orElse(null);
    }

    private String fallbackSampleColumn(boolean engineRequiresNonEmptyOrderBy) {
        if (!engineRequiresNonEmptyOrderBy) {
            return null;
        }
        return bareIntegerColumnName(columns.get(0).asColumnReference(null));
    }

    private static boolean hasColumnReference(ClickHouseExpression expr) {
        if (expr instanceof ClickHouseColumnReference) {
            return true;
        }
        if (expr instanceof BinaryOperation<?> bo) {
            return hasColumnReference((ClickHouseExpression) bo.getLeft())
                    || hasColumnReference((ClickHouseExpression) bo.getRight());
        }
        if (expr instanceof UnaryOperation<?> uo) {
            return hasColumnReference((ClickHouseExpression) uo.getExpression());
        }
        return false;
    }

    private static boolean referencesFloatColumn(ClickHouseExpression expr) {
        if (expr instanceof ClickHouseColumnReference cr) {
            ClickHouseDataType t = cr.getColumn().getType().getType();
            return t == ClickHouseDataType.Float32 || t == ClickHouseDataType.Float64;
        }
        if (expr instanceof BinaryOperation<?> bo) {
            return referencesFloatColumn((ClickHouseExpression) bo.getLeft())
                    || referencesFloatColumn((ClickHouseExpression) bo.getRight());
        }
        if (expr instanceof UnaryOperation<?> uo) {
            return referencesFloatColumn((ClickHouseExpression) uo.getExpression());
        }
        return false;
    }

    private static final int SUSPICIOUS_KEY_PERCENT = 40;

    private static boolean rollSuspiciousKey() {
        return Randomly.getNotCachedInteger(0, 100) < SUSPICIOUS_KEY_PERCENT;
    }

    private ClickHouseExpression buildSuspiciousKey(boolean forPartitionBy) {
        List<ClickHouseSchema.ClickHouseColumn> intCols = columns.stream()
                .filter(ClickHouseTableGenerator::isIntegerColumn).collect(Collectors.toList());
        List<ClickHouseSchema.ClickHouseColumn> dateCols = columns.stream()
                .filter(ClickHouseTableGenerator::isDateColumn).collect(Collectors.toList());

        List<Supplier<ClickHouseExpression>> shapes = new ArrayList<>();

        if (!intCols.isEmpty()) {

            shapes.add(() -> {
                ClickHouseExpression c = pickRef(intCols);
                return ClickHouseBinaryArithmeticOperation.create(c, c,
                        ClickHouseBinaryArithmeticOperation.ClickHouseBinaryArithmeticOperator.MULT);
            });

            shapes.add(() -> ClickHouseBinaryFunctionOperation.create(pickRef(intCols), intConst(smallNonZeroDivisor()),
                    ClickHouseBinaryFunctionOperation.ClickHouseBinaryFunctionOperator.INT_DIV));

            shapes.add(() -> {
                ClickHouseExpression c = pickRef(intCols);
                return ClickHouseBinaryFunctionOperation.create(c, c,
                        ClickHouseBinaryFunctionOperation.ClickHouseBinaryFunctionOperator.GCD);
            });

            shapes.add(
                    () -> ClickHouseBinaryArithmeticOperation.create(pickRef(intCols), intConst(smallNonZeroDivisor()),
                            ClickHouseBinaryArithmeticOperation.ClickHouseBinaryArithmeticOperator.MODULO));

            shapes.add(() -> new ClickHouseUnaryPrefixOperation(pickRef(intCols),
                    ClickHouseUnaryPrefixOperation.ClickHouseUnaryPrefixOperator.MINUS));

            shapes.add(() -> new ClickHouseUnaryFunctionOperation(pickRef(intCols),
                    ClickHouseUnaryFunctionOperation.ClickHouseUnaryFunctionOperator.ABS));

            shapes.add(() -> new ClickHouseUnaryFunctionOperation(pickRef(intCols),
                    ClickHouseUnaryFunctionOperation.ClickHouseUnaryFunctionOperator.SIGN));

            if (!forPartitionBy) {

                shapes.add(() -> new ClickHouseUnaryFunctionOperation(pickRef(intCols),
                        ClickHouseUnaryFunctionOperation.ClickHouseUnaryFunctionOperator.SQRT));

                shapes.add(() -> new ClickHouseUnaryFunctionOperation(pickRef(intCols),
                        ClickHouseUnaryFunctionOperation.ClickHouseUnaryFunctionOperator.EXP));

                shapes.add(() -> new ClickHouseUnaryFunctionOperation(pickRef(intCols),
                        ClickHouseUnaryFunctionOperation.ClickHouseUnaryFunctionOperator.SIN));

                shapes.add(() -> ClickHouseBinaryArithmeticOperation.create(pickRef(intCols),
                        intConst(-(long) Randomly.getNotCachedInteger(1, 1_000_000_000)),
                        ClickHouseBinaryArithmeticOperation.ClickHouseBinaryArithmeticOperator.DIV));

                shapes.add(() -> new ClickHouseRawText(
                        "cbrt(" + ClickHouseToStringVisitor.asString(pickRef(intCols)) + ")"));
            }
        }

        if (!dateCols.isEmpty()) {

            shapes.add(() -> new ClickHouseRawText(
                    "toYYYYMM(" + ClickHouseToStringVisitor.asString(pickRef(dateCols)) + ")"));
            shapes.add(() -> new ClickHouseRawText(
                    "toWeek(" + ClickHouseToStringVisitor.asString(pickRef(dateCols)) + ", 3)"));
            shapes.add(() -> new ClickHouseRawText(
                    "toDayOfWeek(" + ClickHouseToStringVisitor.asString(pickRef(dateCols)) + ")"));
        }

        if (shapes.isEmpty()) {
            return null;
        }
        ClickHouseExpression key = Randomly.fromList(shapes).get();

        if (forPartitionBy && isFloatResultExpression(key)) {
            return null;
        }
        return key;
    }

    private static ClickHouseColumnReference pickRef(List<ClickHouseSchema.ClickHouseColumn> cands) {
        return Randomly.fromList(cands).asColumnReference(null);
    }

    private static long smallNonZeroDivisor() {
        long magnitude = 1 + Randomly.getNotCachedInteger(1, 1000);
        return Randomly.getBoolean() ? -magnitude : magnitude;
    }

    private static ClickHouseExpression intConst(long val) {
        if (val >= Integer.MIN_VALUE && val <= Integer.MAX_VALUE) {
            return ClickHouseCreateConstant.createInt32Constant(val);
        }
        return ClickHouseCreateConstant.createInt64Constant(java.math.BigInteger.valueOf(val));
    }

    private static boolean isIntegerColumn(ClickHouseSchema.ClickHouseColumn col) {
        sqlancer.clickhouse.ClickHouseType term = col.getType().getTypeTerm();
        if (term instanceof sqlancer.clickhouse.ClickHouseType.Nullable
                || term instanceof sqlancer.clickhouse.ClickHouseType.LowCardinality
                || term instanceof sqlancer.clickhouse.ClickHouseType.Array
                || term instanceof sqlancer.clickhouse.ClickHouseType.Unknown) {
            return false;
        }
        switch (col.getType().getType()) {
        case Int8:
        case Int16:
        case Int32:
        case Int64:
        case UInt8:
        case UInt16:
        case UInt32:
        case UInt64:
            return true;
        default:
            return false;
        }
    }

    private static boolean isDateColumn(ClickHouseSchema.ClickHouseColumn col) {
        sqlancer.clickhouse.ClickHouseType term = col.getType().getTypeTerm();
        if (term instanceof sqlancer.clickhouse.ClickHouseType.Nullable
                || term instanceof sqlancer.clickhouse.ClickHouseType.LowCardinality
                || term instanceof sqlancer.clickhouse.ClickHouseType.Array
                || term instanceof sqlancer.clickhouse.ClickHouseType.Unknown) {
            return false;
        }
        switch (col.getType().getType()) {
        case Date:
        case Date32:
        case DateTime:
        case DateTime32:
        case DateTime64:
            return true;
        default:
            return false;
        }
    }

    private void addColumnsConstraint(ClickHouseExpressionGenerator gen) {
        for (int i = 0; i < Randomly.smallNumber() + 1; i++) {
            sb.append(",");
            sb.append(" CONSTRAINT ");
            sb.append(ClickHouseCommon.createConstraintName(i));
            sb.append(" CHECK ");
            ClickHouseExpression expr = gen.generateExpressionWithColumns(
                    columns.stream().map(c -> c.asColumnReference(null)).collect(Collectors.toList()), 2);
            sb.append(ClickHouseToStringVisitor.asString(expr));
        }
    }
}
