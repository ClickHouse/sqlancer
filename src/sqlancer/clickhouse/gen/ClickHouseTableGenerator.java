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

    // Engine pool widening (workstream 10 of the 2026-05-27 coverage expansion plan): plain
    // MergeTree dominates the picker but Replacing/Summing variants are now eligible at low
    // probability. The 2026-05-20 false-positive cluster (NoREC visible-cardinality drift on
    // dedupe engines whose ORDER BY contained NaN-producing function calls) is mitigated below
    // via isValidOrderByForDedupeEngine, which refuses function-of-numeric ORDER BY when the
    // engine is Replacing or Summing -- column-only ORDER BY is still accepted.
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
        // Pre-build dummy columns with their final types. The same dataType instance is then handed
        // to the column builder so the emitted DDL matches what the in-memory column list claims;
        // ORDER BY / PARTITION BY / engine-arg pickers downstream rely on this invariant.
        for (int i = 0; i < nrColumns; i++) {
            columns.add(ClickHouseSchema.ClickHouseColumn.createDummy(ClickHouseCommon.createColumnName(i), null,
                    globalState));
        }
        // Engine selection is schema-aware: dedupe engines (Replacing/Summing) require columns
        // with appropriate type semantics, otherwise they degenerate into a "dedupe by ORDER BY
        // key" engine that produces non-deterministic visible cardinality across SELECTs (the
        // 2026-05-20 false-positive cluster). pickEngine() falls back to plain MergeTree when
        // the column shape can't support the dedupe semantics.
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

            // Replacing/Summing dedupe rows by ORDER BY key; ORDER BY tuple() (empty sort key)
            // treats every row as a duplicate, so visible row counts drift with the merge
            // schedule and any oracle that compares two SELECTs against the same table sees
            // racy cardinality (the false (756/126), (78/13), (5/1) trips in the 2026-05-19
            // run all came from this combination). For these engines we require a non-empty
            // sort key -- fall back to the first column rather than tuple().
            boolean engineRequiresNonEmptyOrderBy = engine == ClickHouseEngine.ReplacingMergeTree
                    || engine == ClickHouseEngine.SummingMergeTree;
            String fallbackOrderBy = engineRequiresNonEmptyOrderBy ? " ORDER BY " + columns.get(0).getName() + " "
                    : " ORDER BY tuple() ";

            // SAMPLE BY must reference a column that is part of the primary key, otherwise ClickHouse
            // rejects the CREATE outright ("Sampling expression must be present in the primary key.",
            // Code 36, seen across the 30-min CH 26.6.1.229 fuzz run). The earlier generate-and-
            // validate approach (isValidSampleBy) only checked that the sampling expression contained
            // SOME column, never that it overlapped the ORDER BY/primary key, so every SAMPLE BY whose
            // column differed from the sort key was a guaranteed failure. We instead capture the exact
            // ORDER BY key when it is a single bare integer column and reuse THAT column verbatim as
            // the SAMPLE BY expression -- the only shape guaranteed to be in the primary key. Stays
            // null for tuple()/function/non-integer ORDER BY keys, in which case SAMPLE BY is skipped.
            String sampleByColumn = null;

            if (Randomly.getBoolean()) {
                // For dedupe engines (Replacing/Summing), function-of-numeric ORDER BY produces
                // NaN under common float arithmetic (log/sqrt of negative, division by zero)
                // which the dedupe key bucketer treats as a hash collision, collapsing rows
                // non-deterministically. Refuse those shapes here -- column-only ORDER BY is
                // still permitted via isValidOrderByForDedupe.
                java.util.function.Predicate<ClickHouseExpression> orderByValidator = isDedupeEngine(engine)
                        ? ClickHouseTableGenerator::isValidOrderByForDedupe
                        : ClickHouseTableGenerator::isValidOrderBy;
                // GAP 3 (suspicious non-monotonic key pool). The KeyCondition / partition-pruning
                // range analyser mis-handles ORDER BY / PARTITION BY keys built from non-monotonic
                // or only-partially-monotonic functions; four wrong-result reports on v26.5.1.882
                // (ClickHouse#106080/#106082/#106083/#106084) all use exactly such keys
                // (ORDER BY sqrt(c0), ORDER BY (c0)/(-158854227), ORDER BY -c0; PARTITION BY
                // c0*c0, gcd(c0,c0), intDiv(c0,-691388354)). The generic depth-3 recursive builder
                // only stumbles onto these named functions by chance and dilutes them with noise,
                // so the KeyCondition oracle -- the correct detector for this class -- rarely fires.
                // We bias ~40% of plain-MergeTree ORDER BYs to a single deliberately non-monotonic
                // key from a curated pool. Dedupe engines are excluded: their key must stay a bare
                // column (isValidOrderByForDedupe, see comment block above) because NaN-producing
                // function keys collapse the dedupe bucketer non-deterministically.
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
                    // Capture the ORDER BY key for SAMPLE BY reuse iff it is a single bare integer
                    // column (no wrapper, no function). Any other shape is ineligible: function/
                    // arithmetic keys are not a verbatim primary-key prefix expression, and float/
                    // string/Date sampling columns trip separate server-side checks.
                    sampleByColumn = bareIntegerColumnName(expr);
                } else {
                    sb.append(fallbackOrderBy);
                    sampleByColumn = fallbackSampleColumn(engineRequiresNonEmptyOrderBy);
                }
            } else {
                sb.append(fallbackOrderBy);
                sampleByColumn = fallbackSampleColumn(engineRequiresNonEmptyOrderBy);
            }

            if (Randomly.getBoolean()) {
                // GAP 3: same suspicious-key bias for PARTITION BY (the partition-pruning range
                // analyser is the other half of the KeyCondition surface). PARTITION BY rejects
                // float keys (isValidPartitionBy / "Floating point partition key is not
                // supported"), so buildSuspiciousKey(true) draws only from the integer-result
                // sub-pool (c*c, intDiv, gcd, c%n, -c, toYYYYMM(date)). The suspicious path is
                // applied on plain MergeTree only -- dedupe engines (Replacing/Summing) take the
                // generic-only path here so their partition key never picks up a NaN-producing
                // function shape.
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
            // SAMPLE BY only when the ORDER BY key is a single bare integer column we can reuse
            // verbatim (sampleByColumn non-null). Reusing the sort-key column guarantees the
            // sampling expression is present in the primary key, which is the invariant ClickHouse
            // enforces; any independently generated sampling expression would be rejected with
            // "Sampling expression must be present in the primary key." See sampleByColumn above.
            if (sampleByColumn != null && Randomly.getBoolean()) {
                sb.append(" SAMPLE BY ");
                sb.append(sampleByColumn);
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

    private static boolean isDedupeEngine(ClickHouseEngine engine) {
        return engine == ClickHouseEngine.ReplacingMergeTree || engine == ClickHouseEngine.SummingMergeTree;
    }

    // Weighted engine pick: plain MergeTree dominates so historical coverage is preserved;
    // Replacing/Summing variants are eligible when the column shape supports them. The schema-
    // awareness check looks for a viable ver/sum column; otherwise the dedupe engine collapses
    // every row into one (no version differentiator, nothing to sum) and visible cardinality
    // drifts across SELECTs as the merge thread runs.
    private ClickHouseEngine pickEngine(List<ClickHouseSchema.ClickHouseColumn> cols) {
        int roll = (int) Randomly.getNotCachedInteger(0, 100);
        if (roll < 80) {
            return ClickHouseEngine.MergeTree;
        }
        if (roll < 90) {
            // ReplacingMergeTree needs a version-column candidate (UInt*/Date*/DateTime*).
            // Without one, the engine has no tiebreaker between same-PK rows and just keeps the
            // last-merged. Fall back to plain MergeTree.
            boolean hasVerCandidate = cols.stream().anyMatch(this::isValidReplacingVer);
            return hasVerCandidate ? ClickHouseEngine.ReplacingMergeTree : ClickHouseEngine.MergeTree;
        }
        // SummingMergeTree needs at least one numeric column to sum. Without one the engine just
        // dedupes by ORDER BY key, which is the same non-deterministic-cardinality shape the
        // 2026-05-20 false-positive cluster surfaced.
        boolean hasSumCandidate = cols.stream().anyMatch(this::isValidSummingCol);
        return hasSumCandidate ? ClickHouseEngine.SummingMergeTree : ClickHouseEngine.MergeTree;
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
    // 1) PROJECTION p (SELECT col1, col2) -- a "reorder" projection (column subset).
    // 2) PROJECTION p (SELECT count() GROUP BY cN) -- an "aggregating" projection on one key.
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

    // ORDER BY must reference at least one column -- "Sorting key cannot contain constants" --
    // and reject column references to composite / geo / nested / JSON-family / AggregateFunction
    // types. CH rejects these as ORDER BY keys (Map/Tuple/Nested by error, JSON/Variant/Dynamic
    // by missing comparator, AggregateFunction because the state has no total order). Workstreams
    // 2/4/5/6/7.
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

    // ORDER BY for dedupe engines must be a column reference, not a function-of-column. NaN-
    // producing functions (log/sqrt of negative, divide-by-zero) on the ORDER BY key collapse
    // distinct rows into the same dedupe bucket non-deterministically, which presents to oracles
    // as visible-cardinality drift between two SELECTs against the same table.
    static boolean isValidOrderByForDedupe(ClickHouseExpression expr) {
        return expr instanceof ClickHouseColumnReference;
    }

    // PARTITION BY rejects float keys ("Floating point partition key is not supported") and
    // all-constant expressions ("Partition key cannot contain constants"). Also reject the
    // unorderable composite types.
    //
    // referencesFloatColumn alone is insufficient: it only inspects column TYPES, so a float-RESULT
    // function over an INTEGER column slips through and wastes a CREATE on a server-side rejection.
    // The 30-min fuzz run on CH 26.6.1.229 produced a stream of tolerated-but-wasteful
    // "Code: 36 ... Floating point partition key is not supported: radians(c2). (BAD_ARGUMENTS)"
    // failures from exactly this gap -- radians/sqrt/exp/sin/cos/log/cbrt of an int column, and
    // real division a/b (always Float64 in ClickHouse), all type-check as float but pass
    // referencesFloatColumn. isFloatResultExpression closes the gap by inspecting the evaluated
    // result type. Integer-result non-monotonic shapes (intDiv, gcd, modulo, c*c, -c, abs,
    // toYYYYMM/toWeek/toDayOfWeek on Date) are NOT rejected here -- those are the
    // #106080/#106084 KeyCondition surface and are valid partition keys.
    static boolean isValidPartitionBy(ClickHouseExpression expr) {
        return hasColumnReference(expr) && !referencesFloatColumn(expr) && !isFloatResultExpression(expr)
                && !referencesUnorderableComposite(expr);
    }

    // Float-returning unary function names from ClickHouseUnaryFunctionOperation's operator enum
    // (EXP, SQRT, ERF, SIN, COS, TAN, RADIANS, LOG) plus the float-returning extras emitted as raw
    // text by buildSuspiciousKey (cbrt, degrees, exp2, log2/log10, etc.). SIGN and ABS are
    // deliberately ABSENT: both preserve the integer result type, so they remain valid PARTITION BY
    // keys. ClickHouse widens every one of the listed functions to Float64 even over an integer
    // argument, which makes the resulting expression an illegal floating-point partition key.
    private static final java.util.Set<String> FLOAT_RESULT_FUNCTIONS = java.util.Set.of("exp", "exp2", "exp10", "sqrt",
            "cbrt", "erf", "sin", "cos", "tan", "asin", "acos", "atan", "sinh", "cosh", "tanh", "radians", "degrees",
            "log", "log2", "log10", "ln", "pow", "power");

    // True when the expression's evaluated RESULT type is floating point, regardless of whether it
    // references a float COLUMN. ClickHouse rejects any floating-point PARTITION BY key, so this is
    // the predicate that catches float-RESULT functions over integer columns (radians(intCol),
    // sqrt(intCol), a/b real division) that referencesFloatColumn misses. Recurses so float
    // propagation through arithmetic (e.g. sqrt(c0) + 1, which stays Float64) is also caught.
    private static boolean isFloatResultExpression(ClickHouseExpression expr) {
        if (expr instanceof ClickHouseUnaryFunctionOperation ufo) {
            // getOperatorRepresentation() returns the lower-case function name (e.g. "sqrt").
            if (FLOAT_RESULT_FUNCTIONS.contains(ufo.getOperatorRepresentation())) {
                return true;
            }
            return isFloatResultExpression(ufo.getExpression());
        }
        if (expr instanceof ClickHouseBinaryArithmeticOperation bao) {
            // Real division '/' always yields Float64 in ClickHouse, even over two integers
            // (use intDiv for integer division). Any other arithmetic propagates a float operand.
            if (bao.getOperator() == ClickHouseBinaryArithmeticOperation.ClickHouseBinaryArithmeticOperator.DIV) {
                return true;
            }
            return isFloatResultExpression(bao.getLeft()) || isFloatResultExpression(bao.getRight());
        }
        if (expr instanceof ClickHouseRawText rt) {
            // buildSuspiciousKey emits some functions (cbrt, date extractors) as raw text. Match the
            // leading function-name token against the float-returning set so a raw "cbrt(c0)" key is
            // rejected for PARTITION BY while the integer-result "toYYYYMM(d)" raw text is kept.
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

    // SAMPLE BY must reference a column (the actual primary-key check is server-side).
    static boolean isValidSampleBy(ClickHouseExpression expr) {
        return hasColumnReference(expr) && !referencesUnorderableComposite(expr);
    }

    // Return the column name iff expr is a single bare reference to an UNSIGNED integer column;
    // otherwise null. Used to derive a SAMPLE BY expression that is guaranteed to be present in the
    // primary key (we only call this on the emitted ORDER BY key). ClickHouse additionally requires
    // the sampling column to be an unsigned integer type ("Invalid sampling column type ... Must be
    // unsigned integer"), so signed Int*/Date/Float/String columns are rejected here even though
    // they are valid sort keys. No wrapper types (Nullable/LowCardinality/Array) -- a wrapped
    // column is not a plain unsigned-integer sampling expression.
    private static String bareIntegerColumnName(ClickHouseExpression expr) {
        if (!(expr instanceof ClickHouseColumnReference cr)) {
            return null;
        }
        sqlancer.clickhouse.ClickHouseType term = cr.getColumn().getType().getTypeTerm();
        if (term instanceof sqlancer.clickhouse.ClickHouseType.Nullable
                || term instanceof sqlancer.clickhouse.ClickHouseType.LowCardinality
                || term instanceof sqlancer.clickhouse.ClickHouseType.Array
                || term instanceof sqlancer.clickhouse.ClickHouseType.Unknown) {
            return null;
        }
        switch (cr.getColumn().getType().getType()) {
        case UInt8:
        case UInt16:
        case UInt32:
        case UInt64:
            return cr.getColumn().getName();
        default:
            return null;
        }
    }

    // When ORDER BY fell back to the engine default, the sort key is either tuple() (plain
    // MergeTree -- no sampling column at all) or columns.get(0) (dedupe engines). Only the latter
    // gives a bare column we could reuse, and only if it is an unsigned integer; otherwise null so
    // SAMPLE BY is skipped.
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

    // GAP 3 suspicious-key emission probability. ~40% of plain-MergeTree ORDER BY / PARTITION BY
    // generations take the curated non-monotonic pool; the remaining ~60% keep the generic
    // depth-3 recursive path. The pool is ADDITIVE coverage, not a replacement -- the generic
    // path still produces the bulk of column-only and arbitrary-expression keys.
    private static final int SUSPICIOUS_KEY_PERCENT = 40;

    private static boolean rollSuspiciousKey() {
        return Randomly.getNotCachedInteger(0, 100) < SUSPICIOUS_KEY_PERCENT;
    }

    // GAP 3: build a single deliberately non-monotonic (or only-partially-monotonic) key over one
    // column, drawn from a curated pool that mirrors the shapes in the four v26.5.1.882 wrong-
    // result reports (#106080/#106082/#106083/#106084). These functions defeat KeyCondition's
    // monotonic-range reasoning and partition pruning, which is precisely what the KeyCondition
    // oracle is built to detect; the generic recursive builder reaches them only by chance.
    //
    // forPartitionBy=true restricts the pool to integer-RESULT expressions: PARTITION BY rejects
    // float keys ("Floating point partition key is not supported"), and sqrt/cbrt/exp* of an
    // integer column return Float64. isValidPartitionBy's referencesFloatColumn check only
    // inspects column types, not result types, so it would let sqrt(intCol) through and waste a
    // CREATE on a server-side rejection -- we exclude those shapes here instead.
    //
    // Returns null when the table has no column of a suitable type for any pooled shape (e.g. a
    // String-only table); the caller then falls back to the generic path.
    private ClickHouseExpression buildSuspiciousKey(boolean forPartitionBy) {
        List<ClickHouseSchema.ClickHouseColumn> intCols = columns.stream().filter(ClickHouseTableGenerator::isIntegerColumn)
                .collect(Collectors.toList());
        List<ClickHouseSchema.ClickHouseColumn> dateCols = columns.stream().filter(ClickHouseTableGenerator::isDateColumn)
                .collect(Collectors.toList());

        // Collect the candidate shape builders that this table's column set can actually support,
        // then pick one uniformly. Each entry is a no-arg supplier closing over a freshly picked
        // column of the right type so the chosen shape always references an existing, type-correct
        // column.
        List<Supplier<ClickHouseExpression>> shapes = new ArrayList<>();

        if (!intCols.isEmpty()) {
            // Integer-result shapes -- valid for both ORDER BY and PARTITION BY.
            // c * c : quadratic, non-monotonic over signed ranges (#106080 PARTITION BY c0*c0).
            shapes.add(() -> {
                ClickHouseExpression c = pickRef(intCols);
                return ClickHouseBinaryArithmeticOperation.create(c, c,
                        ClickHouseBinaryArithmeticOperation.ClickHouseBinaryArithmeticOperator.MULT);
            });
            // intDiv(c, <small nonzero, sometimes negative>) : step function, only partially
            // monotonic (#106082/#106083 PARTITION BY intDiv(c0,-691388354), ORDER BY (c0)/const).
            shapes.add(() -> ClickHouseBinaryFunctionOperation.create(pickRef(intCols), intConst(smallNonZeroDivisor()),
                    ClickHouseBinaryFunctionOperation.ClickHouseBinaryFunctionOperator.INT_DIV));
            // gcd(c, c) : collapses to |c|, non-monotonic across the sign boundary (#106082
            // PARTITION BY gcd(c0,c0)).
            shapes.add(() -> {
                ClickHouseExpression c = pickRef(intCols);
                return ClickHouseBinaryFunctionOperation.create(c, c,
                        ClickHouseBinaryFunctionOperation.ClickHouseBinaryFunctionOperator.GCD);
            });
            // c % <nonzero> : sawtooth, strongly non-monotonic.
            shapes.add(() -> ClickHouseBinaryArithmeticOperation.create(pickRef(intCols), intConst(smallNonZeroDivisor()),
                    ClickHouseBinaryArithmeticOperation.ClickHouseBinaryArithmeticOperator.MODULO));
            // -c : monotonically DEcreasing -- KeyCondition must flip range bounds; the reports
            // show this alone is enough to misfire (#106084 ORDER BY -c0).
            shapes.add(() -> new ClickHouseUnaryPrefixOperation(pickRef(intCols),
                    ClickHouseUnaryPrefixOperation.ClickHouseUnaryPrefixOperator.MINUS));
            // abs(c) : folds the negative half onto the positive, non-monotonic at zero.
            shapes.add(() -> new ClickHouseUnaryFunctionOperation(pickRef(intCols),
                    ClickHouseUnaryFunctionOperation.ClickHouseUnaryFunctionOperator.ABS));
            // sign(c) : 3-valued step (-1/0/1), only weakly monotonic.
            shapes.add(() -> new ClickHouseUnaryFunctionOperation(pickRef(intCols),
                    ClickHouseUnaryFunctionOperation.ClickHouseUnaryFunctionOperator.SIGN));

            if (!forPartitionBy) {
                // Float-result shapes -- ORDER BY only (PARTITION BY rejects float keys). We feed
                // an INTEGER column so isValidOrderBy / referencesFloatColumn accept the key; the
                // function itself widens to Float64. sqrt over an Int column is fine, sqrt over a
                // Float column would be rejected by referencesFloatColumn.
                // sqrt(c) : partially monotonic, undefined for negatives -> NaN (#106080/#106084
                // ORDER BY sqrt(c0)).
                shapes.add(() -> new ClickHouseUnaryFunctionOperation(pickRef(intCols),
                        ClickHouseUnaryFunctionOperation.ClickHouseUnaryFunctionOperator.SQRT));
                // exp(c) : monotonic but explodes -- range-bound arithmetic in KeyCondition can
                // overflow to +inf.
                shapes.add(() -> new ClickHouseUnaryFunctionOperation(pickRef(intCols),
                        ClickHouseUnaryFunctionOperation.ClickHouseUnaryFunctionOperator.EXP));
                // sin(c) : periodic, the canonical non-monotonic function.
                shapes.add(() -> new ClickHouseUnaryFunctionOperation(pickRef(intCols),
                        ClickHouseUnaryFunctionOperation.ClickHouseUnaryFunctionOperator.SIN));
                // c / <negative const> : monotonically decreasing float (#106083 ORDER BY
                // (c0)/(-158854227)). Division yields Float64 so it is ORDER-BY-only.
                shapes.add(() -> ClickHouseBinaryArithmeticOperation.create(pickRef(intCols),
                        intConst(-(long) Randomly.getNotCachedInteger(1, 1_000_000_000)),
                        ClickHouseBinaryArithmeticOperation.ClickHouseBinaryArithmeticOperator.DIV));
                // cbrt(c) : monotonic but partially so under float rounding. No AST enum entry
                // exists for cbrt, so emit raw text over the column reference -- the same
                // raw-fragment precedent ClickHouseRawText documents for unmodelled functions.
                shapes.add(() -> new ClickHouseRawText(
                        "cbrt(" + ClickHouseToStringVisitor.asString(pickRef(intCols)) + ")"));
            }
        }

        if (!dateCols.isEmpty()) {
            // Date/DateTime bucketing functions. toYYYYMM is monotonic but lossy; toWeek(d, 3) and
            // toDayOfWeek are periodic / non-monotonic. All return integers so they are valid for
            // both ORDER BY and PARTITION BY. No AST enum entries model these date extractors, so
            // they are emitted as raw text over a column reference (ClickHouseRawText precedent).
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
        // Defensive result-type gate for the PARTITION BY path. The forPartitionBy guards above
        // already exclude the float-RESULT shapes (sqrt/exp/sin/cbrt/division) from the pool, but
        // routing the chosen key through the same isFloatResultExpression predicate that
        // isValidPartitionBy uses keeps the two in lockstep: if a future shape is added to the
        // integer sub-pool that turns out to widen to Float64, it is dropped here (caller falls
        // back to the generic path) rather than emitted as an illegal floating-point partition key
        // ("Floating point partition key is not supported", CH 26.6.1.229).
        if (forPartitionBy && isFloatResultExpression(key)) {
            return null;
        }
        return key;
    }

    // Pick a column from the candidate list and return its reference node. The list is guaranteed
    // non-empty by the caller (buildSuspiciousKey checks isEmpty before adding a shape).
    private static ClickHouseColumnReference pickRef(List<ClickHouseSchema.ClickHouseColumn> cands) {
        return Randomly.fromList(cands).asColumnReference(null);
    }

    // A small nonzero divisor for intDiv / modulo, occasionally negative to exercise the sign-flip
    // path in KeyCondition's range arithmetic (the reports use both signs: intDiv(c0,-691388354)).
    private static long smallNonZeroDivisor() {
        long magnitude = 1 + Randomly.getNotCachedInteger(1, 1000);
        return Randomly.getBoolean() ? -magnitude : magnitude;
    }

    // Wrap a long in an Int32/Int64 constant node so the value renders as a bare integer literal
    // alongside the column reference. Int32 covers the small-divisor case; values outside the
    // Int32 range fall back to Int64.
    private static ClickHouseExpression intConst(long val) {
        if (val >= Integer.MIN_VALUE && val <= Integer.MAX_VALUE) {
            return ClickHouseCreateConstant.createInt32Constant(val);
        }
        return ClickHouseCreateConstant.createInt64Constant(java.math.BigInteger.valueOf(val));
    }

    // GAP 3 column-type classifiers. Integer columns are eligible for the arithmetic / sqrt /
    // intDiv / gcd pool; Date/DateTime columns feed the toYYYYMM / toWeek / toDayOfWeek pool.
    // Wrapper types (Nullable / LowCardinality / Array) are excluded -- a function over a wrapped
    // column changes the result type and reintroduces the validator-rejection / NaN-bucket risks
    // the curated pool is designed to avoid.
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
