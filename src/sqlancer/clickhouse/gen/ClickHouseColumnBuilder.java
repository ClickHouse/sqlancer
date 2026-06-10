package sqlancer.clickhouse.gen;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import com.clickhouse.data.ClickHouseDataType;

import sqlancer.Randomly;
import sqlancer.clickhouse.ClickHouseProvider;
import sqlancer.clickhouse.ClickHouseSchema;
import sqlancer.clickhouse.ClickHouseType;
import sqlancer.clickhouse.ClickHouseVisitor;

public class ClickHouseColumnBuilder {

    private final StringBuilder sb = new StringBuilder();

    private static boolean allowAlias = true;
    private static boolean allowMaterialized = true;
    private static boolean allowDefaultValue = true;
    private static boolean allowCodec = true;

    private enum Constraints {
        DEFAULT, MATERIALIZED, CODEC, STATISTICS, ALIAS // TTL
    }

    // Statistics kinds accepted by ClickHouse on column declarations. tdigest works on numeric
    // columns (precision histograms); uniq works on every type (HLL-based distinct count);
    // countmin works on String/numeric (frequency sketches). minmax works on ordered numerics.
    // Note the spelling: CH HEAD accepts 'countmin' (no underscore); 'count_min' is rejected with
    // INCORRECT_QUERY. The plan's spelling was wrong; this is the corrected form.
    private static final List<String> STATISTICS_KINDS_NUMERIC = List.of("tdigest", "uniq", "countmin", "minmax");
    private static final List<String> STATISTICS_KINDS_STRING = List.of("uniq", "countmin");
    private static final List<String> STATISTICS_KINDS_OTHER = List.of("uniq");

    public String createColumn(String columnName, ClickHouseProvider.ClickHouseGlobalState globalState,
            List<ClickHouseSchema.ClickHouseColumn> columns) {
        return createColumn(columnName, ClickHouseSchema.ClickHouseLancerDataType.getRandom(globalState), globalState,
                columns);
    }

    // Variant that accepts a pre-chosen column type. The table generator pre-builds dummy columns
    // (so ORDER BY / PARTITION BY / engine-arg pickers can reason about types before the column
    // list is rendered) and then asks this builder to emit the column DDL using *that same* type --
    // otherwise the dummy and emitted columns would carry independent random types and an engine
    // arg picked from the dummy list would reference a server-side column of a different type.
    public String createColumn(String columnName, ClickHouseSchema.ClickHouseLancerDataType dataType,
            ClickHouseProvider.ClickHouseGlobalState globalState, List<ClickHouseSchema.ClickHouseColumn> columns) {
        sb.append(columnName);
        sb.append(" ");
        List<Constraints> constraints = new ArrayList<>();
        // Unit 3.2: (Simple)AggregateFunction columns reject DEFAULT / MATERIALIZED / ALIAS /
        // STATISTICS (they have aggregate-state semantics, not an ordinary value domain). Emit the
        // bare `name Type` form for them -- skip the constraint roll entirely.
        boolean isStateColumn = dataType.getTypeTerm().unwrap() instanceof ClickHouseType.SimpleAggregateFunctionType
                || dataType.getTypeTerm().unwrap() instanceof ClickHouseType.AggregateFunctionType;
        if (!isStateColumn && Randomly.getBooleanWithSmallProbability()) {
            constraints = Randomly.subset(Constraints.values());
            if (!allowAlias || columns.isEmpty() || columns.size() == 1) {
                constraints.remove(Constraints.ALIAS);
            }
            if (!allowMaterialized) {
                constraints.remove(Constraints.MATERIALIZED);
            }
            if (!allowDefaultValue) {
                constraints.remove(Constraints.DEFAULT);
            }
            if (constraints.contains(Constraints.MATERIALIZED)) {
                constraints.remove(Constraints.ALIAS);
                constraints.remove(Constraints.DEFAULT);
            } else if (constraints.contains(Constraints.ALIAS)) {
                constraints.remove(Constraints.DEFAULT);
                constraints.remove(Constraints.CODEC);
                // ALIAS columns have no physical storage; STATISTICS is rejected for them.
                constraints.remove(Constraints.STATISTICS);
            }
        }

        if (!constraints.contains(Constraints.ALIAS)) {
            sb.append(dataType);
        }

        Collections.sort(constraints);

        for (Constraints c : constraints) {
            switch (c) {
            case MATERIALIZED:
                if (allowMaterialized) {
                    sb.append(" MATERIALIZED (");
                    sb.append(
                            ClickHouseVisitor.asString(
                                    new ClickHouseExpressionGenerator(globalState).generateExpressionWithColumns(
                                            columns.stream().filter(p -> !p.getName().contentEquals(columnName))
                                                    .map(p -> p.asColumnReference(null)).collect(Collectors.toList()),
                                            2)));
                    sb.append(")");
                }
                break;
            case DEFAULT:
                if (allowDefaultValue) {
                    sb.append(" DEFAULT ");
                    // Render through the visitor -- ClickHouseExpression instances that don't
                    // override toString() (Cast wrappers used for v2 Date/Decimal/FixedString
                    // emission) would otherwise stringify as Object hash codes.
                    sb.append(ClickHouseVisitor
                            .asString(new ClickHouseExpressionGenerator(globalState).generateConstant(dataType)));
                }
                break;
            case ALIAS:
                if (allowAlias) {
                    sb.append(" ALIAS ");
                    sb.append(Randomly.fromList(columns.stream().filter(p -> !p.getName().contentEquals(columnName))
                            .collect(Collectors.toList())).getName());
                }
                break;
            case CODEC:
                if (allowCodec) {
                    sb.append(" CODEC (");
                    sb.append(pickCodec(dataType));
                    sb.append(")");
                }
                break;
            case STATISTICS:
                // Inline STATISTICS(...) requires `set allow_experimental_statistics = 1` on the
                // session or merged config. We emit at small probability (the constraints set
                // already gates via getBooleanWithSmallProbability above) and trust the expected
                // errors catalogue to absorb cases where the server hasn't enabled it. The kinds
                // list is filtered by base type so legitimately-supported configs go through.
                String kind = pickStatisticsKind(dataType);
                if (kind != null) {
                    sb.append(" STATISTICS(");
                    sb.append(kind);
                    sb.append(")");
                }
                break;
            default:
                throw new AssertionError();
            }
        }
        return sb.toString();
    }

    private static String pickStatisticsKind(ClickHouseSchema.ClickHouseLancerDataType dataType) {
        ClickHouseType term = dataType.getTypeTerm();
        if (term instanceof ClickHouseType.Array || term instanceof ClickHouseType.Unknown
                || term instanceof ClickHouseType.LowCardinality) {
            return null;
        }
        ClickHouseDataType base = dataType.getType();
        boolean isNumeric = base == ClickHouseDataType.Int8 || base == ClickHouseDataType.Int16
                || base == ClickHouseDataType.Int32 || base == ClickHouseDataType.Int64
                || base == ClickHouseDataType.UInt8 || base == ClickHouseDataType.UInt16
                || base == ClickHouseDataType.UInt32 || base == ClickHouseDataType.UInt64
                || base == ClickHouseDataType.Float32 || base == ClickHouseDataType.Float64;
        boolean isString = base == ClickHouseDataType.String || base == ClickHouseDataType.FixedString;
        if (isNumeric) {
            return Randomly.fromList(STATISTICS_KINDS_NUMERIC);
        }
        if (isString) {
            return Randomly.fromList(STATISTICS_KINDS_STRING);
        }
        return Randomly.fromList(STATISTICS_KINDS_OTHER);
    }

    // Type-aware codec selection. Each codec has constraints on which column types ClickHouse
    // accepts it on; emitting an incompatible codec raises BAD_ARGUMENTS at CREATE time and turns
    // every reproducer's CREATE TABLE into noise. The constraint matrix below mirrors the
    // server's CompressionFactoryAdditions::validateCodec checks (DoubleDelta/Gorilla/FPC/Delta/T64
    // are numeric-only; Gorilla/FPC are float-only). NONE/LZ4/LZ4HC/ZSTD are universal.
    // ZSTD_QAT / DEFLATE_QPL require special hardware support on the server and are gated on a
    // build flag; do not emit them from the generator (they'll bail with "compression method not
    // supported" everywhere outside Intel-QAT-equipped servers).
    private static String pickCodec(ClickHouseSchema.ClickHouseLancerDataType dataType) {
        List<String> options = new ArrayList<>();
        options.add("NONE");
        options.add("LZ4");
        options.add("LZ4HC(" + Randomly.fromOptions(0, 1, 6, 9, 12) + ")");
        options.add("ZSTD(" + Randomly.fromOptions(1, 3, 6, 9, 19) + ")");

        ClickHouseType term = dataType.getTypeTerm();
        ClickHouseDataType base = dataType.getType();
        boolean isFloat = base == ClickHouseDataType.Float32 || base == ClickHouseDataType.Float64;
        boolean isNumericIntegral = base == ClickHouseDataType.Int8 || base == ClickHouseDataType.Int16
                || base == ClickHouseDataType.Int32 || base == ClickHouseDataType.Int64
                || base == ClickHouseDataType.Int128 || base == ClickHouseDataType.Int256
                || base == ClickHouseDataType.UInt8 || base == ClickHouseDataType.UInt16
                || base == ClickHouseDataType.UInt32 || base == ClickHouseDataType.UInt64
                || base == ClickHouseDataType.UInt128 || base == ClickHouseDataType.UInt256;
        boolean isDateLike = base == ClickHouseDataType.Date || base == ClickHouseDataType.Date32
                || base == ClickHouseDataType.DateTime || base == ClickHouseDataType.DateTime64;
        boolean isPlainPrimitive = !(term instanceof ClickHouseType.Nullable)
                && !(term instanceof ClickHouseType.LowCardinality) && !(term instanceof ClickHouseType.Array);

        if (isPlainPrimitive) {
            // Delta / DoubleDelta / Gorilla / FPC are PURE TRANSFORMERS -- they only re-encode
            // values, never compress. CH refuses to accept them as the sole codec with
            // 'Compression codec Delta(N) does not compress anything'. Always chain with a
            // generic compressor.
            if (isNumericIntegral || isDateLike) {
                int n = Randomly.fromOptions(1, 2, 4, 8);
                options.add("Delta(" + n + "), LZ4");
                options.add("DoubleDelta, LZ4");
                options.add("T64, LZ4"); // T64 is a transformer too on some CH versions
            }
            if (isFloat) {
                options.add("Gorilla, LZ4");
                options.add("FPC, LZ4");
            }
            // Explicit transformer + compressor chains with a stronger compression level.
            if ((isNumericIntegral || isDateLike) && Randomly.getBooleanWithSmallProbability()) {
                int n = Randomly.fromOptions(1, 2, 4, 8);
                int z = Randomly.fromOptions(1, 3, 6);
                options.add("Delta(" + n + "), ZSTD(" + z + ")");
            }
        }
        return Randomly.fromList(options);
    }

}
