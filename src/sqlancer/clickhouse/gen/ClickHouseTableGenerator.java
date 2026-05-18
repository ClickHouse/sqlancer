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
import sqlancer.clickhouse.ast.ClickHouseColumnReference;
import sqlancer.clickhouse.ast.ClickHouseExpression;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.common.visitor.BinaryOperation;
import sqlancer.common.visitor.UnaryOperation;

public class ClickHouseTableGenerator {

    private enum ClickHouseEngine {
        // MergeTree family only. Log-family (Log, TinyLog, StripeLog) lacks parts, projections, and
        // skipping indexes; Memory lacks persistence-layer plumbing and behaves differently for
        // mutations / inserts; planner-level oracles hit false-positive divergence on both without
        // yielding bug-finding signal in proportion.
        //
        // Replacing/Summing variants are included because the regression family around the query
        // condition cache (ClickHouse#104781) was reported against ReplacingMergeTree, and the
        // engine-specific merge logic is itself a bug-bait surface (deduplication on ver, sum-on-
        // merge accumulator). AggregatingMergeTree is excluded -- it requires every non-PK column
        // to be a SimpleAggregateFunction or AggregateFunction type, which the v1 type system
        // does not yet emit.
        MergeTree, ReplacingMergeTree, SummingMergeTree
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
        return new SQLQueryAdapter(chTableGenerator.sb.toString(), errors, true);
    }

    public void start() {
        ClickHouseEngine engine = Randomly.fromOptions(ClickHouseEngine.values());
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
        // Pre-build dummy columns with their final types. The same dataType instance is then handed
        // to the column builder so the emitted DDL matches what the in-memory column list claims;
        // ORDER BY / PARTITION BY / engine-arg pickers downstream rely on this invariant.
        for (int i = 0; i < nrColumns; i++) {
            columns.add(ClickHouseSchema.ClickHouseColumn.createDummy(ClickHouseCommon.createColumnName(i), null,
                    globalState));
        }
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
        // Skip indexes. Emitted inside the column-list block per ClickHouse grammar (comma-separated
        // entries alongside columns). ClickHouse#104781 fires only when a non-PK column carries an
        // index that participates in the query-condition cache code path -- generating these
        // unlocks the bug shape.
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
        // Projections. Emitted inside the column-list block per ClickHouse grammar. A projection
        // is a materialised secondary index built from a sub-SELECT against the same row set; at
        // query time the optimiser may choose to read from the projection instead of the base
        // part. Regressions #103052 (DISTINCT + partial aggregate projection drops rows) and
        // #88350 (count() wrong with UNION + projection column cleanup) are projection-only --
        // without emitting a projection, no oracle reaches that code path.
        //
        // We emit only column-list and simple-aggregate projections; ORDER BY in projections is
        // syntactically supported but compounds the failure-attribution surface, deferred.
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

            if (Randomly.getBoolean()) {
                ClickHouseExpression expr = generateValidated(exprFactory, ClickHouseTableGenerator::isValidOrderBy);
                if (expr != null) {
                    sb.append(" ORDER BY ");
                    sb.append(ClickHouseToStringVisitor.asString(expr));
                } else {
                    sb.append(" ORDER BY tuple() ");
                }
            } else {
                sb.append(" ORDER BY tuple() ");
            }

            if (Randomly.getBoolean()) {
                ClickHouseExpression expr = generateValidated(exprFactory,
                        ClickHouseTableGenerator::isValidPartitionBy);
                if (expr != null) {
                    sb.append(" PARTITION BY ");
                    sb.append(ClickHouseToStringVisitor.asString(expr));
                }
            }
            if (Randomly.getBoolean()) {
                ClickHouseExpression expr = generateValidated(exprFactory, ClickHouseTableGenerator::isValidSampleBy);
                if (expr != null) {
                    sb.append(" SAMPLE BY ");
                    sb.append(ClickHouseToStringVisitor.asString(expr));
                }
            }
            // Suppress index sanity checks https://github.com/sqlancer/sqlancer/issues/788; permit
            // Nullable columns in ORDER BY / PARTITION BY / SAMPLE BY -- otherwise ClickHouse
            // rejects them with ILLEGAL_COLUMN when the v1 type flags emit Nullable columns.
            //
            // min_bytes_for_wide_part=0 forces every part to be Wide rather than Compact.
            // ClickHouse#104781 was reported on a Wide-part table; Compact parts route reads
            // through a different code path that does not exercise the cache-key bug, so we flip
            // a small fraction of tables to Wide to keep that surface covered.
            sb.append(" SETTINGS allow_suspicious_indices=1, allow_nullable_key=1");
            if (Randomly.getBooleanWithSmallProbability()) {
                sb.append(", min_bytes_for_wide_part=0");
            }
            // TODO: PRIMARY KEY
        }

    }

    private static boolean isMergeTreeFamily(ClickHouseEngine engine) {
        return engine == ClickHouseEngine.MergeTree || engine == ClickHouseEngine.ReplacingMergeTree
                || engine == ClickHouseEngine.SummingMergeTree;
    }

    // ReplacingMergeTree(ver) requires UInt*/Date/DateTime; SummingMergeTree(col[, ...]) requires
    // numeric columns. With type-system v2 the picker emits UInt32/UInt64/Date/DateTime so a
    // suitable column is now available. We still emit the empty-args form often -- both engines
    // accept it and merge-by-PK is the default shape -- but when a viable column exists we pick
    // one ~50% of the time so dedup-on-ver / sum-on-merge code paths are exercised.
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
            // SummingMergeTree's args slot is "0 or 1 parameter" -- one identifier or one tuple of
            // identifiers. Always pick one column here; multi-column tuple emission `((c0, c1))` is
            // grammatically valid but compounds the PK/partition-overlap rejection rate and adds
            // no extra bug surface at this stage. The col cannot overlap the primary key, but ORDER
            // BY is generated after this method so we cannot pre-validate; ClickHouse rejects the
            // overlap at CREATE time and the error catalog absorbs it.
            return Randomly.fromList(candidates).getName();
        }
        return "";
    }

    // ReplacingMergeTree(ver) accepts only unsigned integers (any width) and date / datetime types.
    // Nullable wrappers are rejected; LowCardinality wrappers are too. Match against the unwrapped
    // root ClickHouseDataType, then exclude wrappers explicitly.
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

    // SummingMergeTree(col) accepts numeric columns. Nullable / Array / LowCardinality variants are
    // rejected. The col MUST be outside the ORDER BY tuple, which we cannot prove here -- if the
    // server rejects, the error catalog absorbs.
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

    // Projection emission. Picks one of two shapes:
    //   1) PROJECTION p (SELECT col1, col2)            -- a "reorder" projection (column subset).
    //   2) PROJECTION p (SELECT count() GROUP BY cN)   -- an "aggregating" projection on one key.
    // Aggregating projections must be over an aggregate function with a GROUP BY; without GROUP BY
    // the projection would materialise a single row per part and ClickHouse rejects it as
    // ambiguous against base reads. Both shapes are valid for the v1 type system (Int32/String).
    private String renderProjection(int idx, List<ClickHouseSchema.ClickHouseColumn> cols) {
        String name = String.format("p_%d", idx);
        // 50/50 between the two shapes; the column subset / GROUP BY key is picked uniformly so a
        // small table (~3 cols) sees each combination over time.
        if (Randomly.getBoolean()) {
            // Aggregating projection. Pick one column as the GROUP BY key; count() needs no
            // argument and is always valid.
            String groupCol = cols.get((int) Randomly.getNotCachedInteger(0, cols.size())).getName();
            return String.format("PROJECTION %s (SELECT count() GROUP BY %s)", name, groupCol);
        }
        // Column-subset projection. Pick 1 or 2 columns; if columns.size() is 1 the upstream
        // gate (columns.size() >= 2) blocks emission so subset always has at least one viable pair.
        int subsetSize = Math.min(cols.size(), 1 + (int) Randomly.getNotCachedInteger(0, 2));
        List<ClickHouseSchema.ClickHouseColumn> subset = Randomly.extractNrRandomColumns(cols, subsetSize);
        String colList = subset.stream().map(ClickHouseSchema.ClickHouseColumn::getName)
                .collect(Collectors.joining(", "));
        return String.format("PROJECTION %s (SELECT %s)", name, colList);
    }

    // Skip-index emission. Index name is derived from the column to keep CREATE statements
    // deterministic and readable in repro logs. Returns null when no compatible index type fits
    // the column's data type -- the caller drops the clause rather than emitting an invalid one.
    private String renderSkipIndex(int idx, ClickHouseSchema.ClickHouseColumn col) {
        ClickHouseDataType t = col.getType().getType();
        // Pick an index type that ClickHouse will accept for this column. bloom_filter and set
        // accept any type; minmax requires ordered types (numeric / string is acceptable);
        // ngrambf_v1 is String-only. Listed in declaration order so the random pick is uniform
        // over the types that are actually valid for `t`.
        List<String> typeChoices = new ArrayList<>();
        typeChoices.add("bloom_filter(0.01)");
        typeChoices.add("set(100)");
        if (t == ClickHouseDataType.Int32 || t == ClickHouseDataType.String) {
            typeChoices.add("minmax");
        }
        if (t == ClickHouseDataType.String) {
            typeChoices.add("ngrambf_v1(3, 256, 2, 0)");
        }
        String type = Randomly.fromList(typeChoices);
        int granularity = Randomly.fromOptions(1, 2, 4);
        return String.format("INDEX idx_%s_%d %s TYPE %s GRANULARITY %d", col.getName(), idx, col.getName(), type,
                granularity);
    }

    private static final int CLAUSE_VALIDATION_RETRY_LIMIT = 5;

    // Generate an expression and run it through the supplied validator; retry up to
    // CLAUSE_VALIDATION_RETRY_LIMIT times. Returns null when no valid expression was produced --
    // the caller drops the clause rather than emitting one that ClickHouse will reject.
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

    // ORDER BY must reference at least one column -- "Sorting key cannot contain constants".
    static boolean isValidOrderBy(ClickHouseExpression expr) {
        return hasColumnReference(expr);
    }

    // PARTITION BY rejects float keys ("Floating point partition key is not supported") and
    // all-constant expressions ("Partition key cannot contain constants").
    static boolean isValidPartitionBy(ClickHouseExpression expr) {
        return hasColumnReference(expr) && !referencesFloatColumn(expr);
    }

    // SAMPLE BY must reference a column (the actual primary-key check is server-side).
    static boolean isValidSampleBy(ClickHouseExpression expr) {
        return hasColumnReference(expr);
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
