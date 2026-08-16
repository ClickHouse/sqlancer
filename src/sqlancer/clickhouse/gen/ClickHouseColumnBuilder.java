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
    private static boolean allowEphemeral = true;

    private enum Constraints {
        DEFAULT, MATERIALIZED, CODEC, STATISTICS, ALIAS, EPHEMERAL
    }

    private static final List<String> STATISTICS_KINDS_NUMERIC = List.of("tdigest", "uniq", "countmin", "minmax",
            "uniq_v2", "basic");
    private static final List<String> STATISTICS_KINDS_STRING = List.of("uniq", "countmin", "uniq_v2", "basic");
    private static final List<String> STATISTICS_KINDS_OTHER = List.of("uniq", "uniq_v2");

    public String createColumn(String columnName, ClickHouseProvider.ClickHouseGlobalState globalState,
            List<ClickHouseSchema.ClickHouseColumn> columns) {
        return createColumn(columnName, ClickHouseSchema.ClickHouseLancerDataType.getRandom(globalState), globalState,
                columns);
    }

    public String createColumn(String columnName, ClickHouseSchema.ClickHouseLancerDataType dataType,
            ClickHouseProvider.ClickHouseGlobalState globalState, List<ClickHouseSchema.ClickHouseColumn> columns) {
        sb.append(columnName);
        sb.append(" ");
        List<Constraints> constraints = new ArrayList<>();

        boolean isStateColumn = dataType.getTypeTerm().unwrap() instanceof ClickHouseType.SimpleAggregateFunctionType
                || dataType.getTypeTerm().unwrap() instanceof ClickHouseType.AggregateFunctionType;
        if (!isStateColumn && Randomly.getBooleanWithSmallProbability()) {
            constraints = Randomly.subset(Constraints.values());
            if (!allowAlias || columns.isEmpty() || columns.size() == 1) {
                constraints.remove(Constraints.ALIAS);
            }
            if (!allowEphemeral || columns.size() <= 1) {
                constraints.remove(Constraints.EPHEMERAL);
            }
            if (!allowMaterialized) {
                constraints.remove(Constraints.MATERIALIZED);
            }
            if (!allowDefaultValue) {
                constraints.remove(Constraints.DEFAULT);
            }
            if (constraints.contains(Constraints.EPHEMERAL)) {
                constraints.remove(Constraints.DEFAULT);
                constraints.remove(Constraints.MATERIALIZED);
                constraints.remove(Constraints.ALIAS);
                constraints.remove(Constraints.CODEC);
                constraints.remove(Constraints.STATISTICS);
            } else if (constraints.contains(Constraints.MATERIALIZED)) {
                constraints.remove(Constraints.ALIAS);
                constraints.remove(Constraints.DEFAULT);
            } else if (constraints.contains(Constraints.ALIAS)) {
                constraints.remove(Constraints.DEFAULT);
                constraints.remove(Constraints.CODEC);

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
            case EPHEMERAL:
                if (allowEphemeral) {
                    sb.append(" EPHEMERAL");
                    if (Randomly.getBoolean()) {
                        sb.append(" ");
                        sb.append(ClickHouseVisitor
                                .asString(new ClickHouseExpressionGenerator(globalState).generateConstant(dataType)));
                    }
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

            if (isNumericIntegral || isDateLike) {
                int n = Randomly.fromOptions(1, 2, 4, 8);
                options.add("Delta(" + n + "), LZ4");
                options.add("DoubleDelta, LZ4");
                options.add("T64, LZ4");
            }
            if (isFloat) {
                options.add("Gorilla, LZ4");
                options.add("FPC, LZ4");
                options.add("ALP, LZ4");
            }

            if ((isNumericIntegral || isDateLike) && Randomly.getBooleanWithSmallProbability()) {
                int n = Randomly.fromOptions(1, 2, 4, 8);
                int z = Randomly.fromOptions(1, 3, 6);
                options.add("Delta(" + n + "), ZSTD(" + z + ")");
            }
        }
        return Randomly.fromList(options);
    }

}
