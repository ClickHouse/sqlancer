package sqlancer.clickhouse;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.clickhouse.data.ClickHouseDataType;

import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.SQLConnection;
import sqlancer.clickhouse.ClickHouseProvider.ClickHouseGlobalState;
import sqlancer.clickhouse.ClickHouseSchema.ClickHouseTable;
import sqlancer.clickhouse.ClickHouseType.Array;
import sqlancer.clickhouse.ClickHouseType.DateTime64Type;
import sqlancer.clickhouse.ClickHouseType.Decimal;
import sqlancer.clickhouse.ClickHouseType.FixedString;
import sqlancer.clickhouse.ClickHouseType.Kind;
import sqlancer.clickhouse.ClickHouseType.LowCardinality;
import sqlancer.clickhouse.ClickHouseType.Nullable;
import sqlancer.clickhouse.ClickHouseType.Primitive;
import sqlancer.clickhouse.ClickHouseType.Unknown;
import sqlancer.clickhouse.ast.ClickHouseColumnReference;
import sqlancer.clickhouse.ast.ClickHouseConstant;
import sqlancer.clickhouse.ast.constant.ClickHouseCreateConstant;
import sqlancer.common.schema.AbstractRelationalTable;
import sqlancer.common.schema.AbstractRowValue;
import sqlancer.common.schema.AbstractSchema;
import sqlancer.common.schema.AbstractTableColumn;
import sqlancer.common.schema.AbstractTables;
import sqlancer.common.schema.TableIndex;

public class ClickHouseSchema extends AbstractSchema<ClickHouseGlobalState, ClickHouseTable> {

    public static class ClickHouseLancerDataType {

        private final ClickHouseType typeTerm;
        private final ClickHouseDataType clickHouseType;
        private final String textRepr;

        public ClickHouseLancerDataType(ClickHouseDataType type) {
            Optional<Kind> kindOpt = Kind.fromClickHouseDataType(type);
            this.typeTerm = kindOpt.isPresent() ? new Primitive(kindOpt.get()) : new Unknown(type.name());
            this.clickHouseType = type;
            this.textRepr = typeTerm.toString();
        }

        public ClickHouseLancerDataType(String textRepr) {
            this.textRepr = textRepr;
            this.typeTerm = ClickHouseTypeParser.parse(textRepr);
            this.clickHouseType = rootClickHouseDataType(this.typeTerm);
        }

        public ClickHouseLancerDataType(ClickHouseType typeTerm) {
            this.typeTerm = typeTerm;
            this.textRepr = typeTerm.toString();
            this.clickHouseType = rootClickHouseDataType(typeTerm);
        }

        private static ClickHouseDataType rootClickHouseDataType(ClickHouseType t) {
            ClickHouseType inner = t.unwrap();
            if (inner instanceof Primitive p) {
                return p.kind().toClickHouseDataType();
            }
            if (inner instanceof FixedString) {
                return ClickHouseDataType.FixedString;
            }
            if (inner instanceof Decimal) {
                return ClickHouseDataType.Decimal;
            }
            if (inner instanceof DateTime64Type) {
                return ClickHouseDataType.DateTime64;
            }
            if (inner instanceof Array) {
                return ClickHouseDataType.Array;
            }
            if (inner instanceof ClickHouseType.Tuple) {
                return ClickHouseDataType.Tuple;
            }
            if (inner instanceof ClickHouseType.Enum e) {
                return e.width() == 8 ? ClickHouseDataType.Enum8 : ClickHouseDataType.Enum16;
            }
            if (inner instanceof ClickHouseType.Time) {
                return ClickHouseDataType.Time;
            }
            if (inner instanceof ClickHouseType.Time64) {
                return ClickHouseDataType.Time64;
            }
            if (inner instanceof ClickHouseType.Map) {
                return ClickHouseDataType.Map;
            }
            if (inner instanceof ClickHouseType.Point) {
                return ClickHouseDataType.Point;
            }
            if (inner instanceof ClickHouseType.Ring) {
                return ClickHouseDataType.Ring;
            }
            if (inner instanceof ClickHouseType.Polygon) {
                return ClickHouseDataType.Polygon;
            }
            if (inner instanceof ClickHouseType.MultiPolygon) {
                return ClickHouseDataType.MultiPolygon;
            }
            if (inner instanceof ClickHouseType.Nested) {
                return ClickHouseDataType.Nested;
            }
            if (inner instanceof ClickHouseType.JSON) {
                return ClickHouseDataType.JSON;
            }
            if (inner instanceof ClickHouseType.Variant) {
                return ClickHouseDataType.Variant;
            }
            if (inner instanceof ClickHouseType.Dynamic) {
                return ClickHouseDataType.Dynamic;
            }
            if (inner instanceof ClickHouseType.AggregateFunctionType) {
                return ClickHouseDataType.AggregateFunction;
            }
            if (inner instanceof ClickHouseType.SimpleAggregateFunctionType) {
                return ClickHouseDataType.SimpleAggregateFunction;
            }

            if (inner instanceof ClickHouseType.IntervalType) {
                return ClickHouseDataType.IntervalSecond;
            }
            return ClickHouseDataType.Nothing;
        }

        public static ClickHouseLancerDataType getRandom() {
            return getRandom(null);
        }

        public static ClickHouseLancerDataType getRandom(ClickHouseGlobalState state) {
            ClickHouseOptions opts = state == null ? null : state.getDbmsSpecificOptions();
            boolean enableNullable = opts != null && opts.enableNullable;
            boolean enableLowCardinality = opts != null && opts.enableLowCardinality;
            boolean enableArray = opts != null && opts.enableArrayJoin;
            ClickHouseType picked = pickScalarType();
            if (enableNullable && Randomly.getBooleanWithSmallProbability() && Nullable.canWrap(picked)) {
                picked = new Nullable(picked);
            }

            if (enableArray && Randomly.getBooleanWithSmallProbability() && Array.canWrap(picked)) {
                picked = new Array(picked);
            }
            if (enableLowCardinality && Randomly.getBooleanWithSmallProbability() && LowCardinality.canWrap(picked)) {
                picked = new LowCardinality(picked);
            }
            return new ClickHouseLancerDataType(picked);
        }

        private static ClickHouseType pickSimpleAggregateFunctionType() {
            String func = Randomly.fromOptions("sum", "min", "max");
            Kind kind;
            if (func.equals("sum")) {

                kind = Randomly.fromOptions(Kind.Int64, Kind.UInt64);
            } else {

                kind = Randomly.fromOptions(Kind.Int32, Kind.Int64, Kind.UInt32, Kind.UInt64, Kind.Float64);
            }
            return new ClickHouseType.SimpleAggregateFunctionType(func, new Primitive(kind));
        }

        private static Kind pickPrimitiveKind() {
            int r = (int) Randomly.getNotCachedInteger(0, 5);
            switch (r) {
            case 0:
                return Kind.Int32;
            case 1:
                return Kind.String;
            case 2:
                return Kind.UInt64;
            case 3:
                return Kind.Float64;
            default:
                return Kind.Date;
            }
        }

        private static ClickHouseType pickScalarType() {
            int roll = (int) Randomly.getNotCachedInteger(0, 101);
            if (roll < 20) {
                return new Primitive(Kind.Int32);
            }
            if (roll < 35) {
                return new Primitive(Kind.String);
            }
            if (roll < 47) {
                return new Primitive(Kind.UInt32);
            }
            if (roll < 57) {
                return new Primitive(Kind.UInt64);
            }
            if (roll < 63) {
                return new Primitive(Kind.Date);
            }
            if (roll < 69) {
                return new Primitive(Kind.DateTime);
            }
            if (roll < 73) {
                return new Primitive(Kind.Int64);
            }
            if (roll < 77) {
                return new Primitive(Kind.Int8);
            }
            if (roll < 81) {
                return new Primitive(Kind.UInt8);
            }
            if (roll < 84) {
                return new Primitive(Kind.Float32);
            }
            if (roll < 87) {
                return new Primitive(Kind.Float64);
            }
            if (roll < 89) {
                return new Primitive(Kind.Bool);
            }
            if (roll < 91) {
                return new FixedString(1 + (int) Randomly.getNotCachedInteger(0, 16));
            }
            if (roll < 92) {

                if (Randomly.getBooleanWithSmallProbability()) {
                    int p256 = 39 + (int) Randomly.getNotCachedInteger(0, 38);
                    int s256 = (int) Randomly.getNotCachedInteger(0, p256 + 1);
                    return new Decimal(p256, s256);
                }
                int p = 1 + (int) Randomly.getNotCachedInteger(0, Randomly.getBoolean() ? 18 : 38);
                int s = (int) Randomly.getNotCachedInteger(0, p + 1);
                return new Decimal(p, s);
            }
            if (roll < 93) {

                return new DateTime64Type((int) Randomly.getNotCachedInteger(0, 7));
            }
            if (roll < 94) {

                if (Randomly.getBoolean()) {
                    return new ClickHouseType.Time();
                }
                return new ClickHouseType.Time64((int) Randomly.getNotCachedInteger(0, 7));
            }

            if (roll < 95) {

                return new Primitive(Kind.Date32);
            }
            if (roll < 96) {

                return pickSimpleAggregateFunctionType();
            }
            if (roll < 97) {
                return new Primitive(Kind.UInt16);
            }
            if (roll < 98) {

                return new Primitive(Randomly.getBoolean() ? Kind.UInt128 : Kind.UInt256);
            }
            if (roll < 99) {

                return new Primitive(Randomly.fromOptions(Kind.IPv4, Kind.IPv6, Kind.UUID));
            }
            if (roll < 100) {

                return new Primitive(Randomly.getBoolean() ? Kind.Int128 : Kind.Int256);
            }

            int entryCount = 2 + (int) Randomly.getNotCachedInteger(0, 4);
            java.util.List<ClickHouseType.EnumEntry> entries = new java.util.ArrayList<>();
            java.util.Set<Integer> usedValues = new java.util.HashSet<>();
            int width = Randomly.getBoolean() ? 8 : 16;
            int valueBound = width == 8 ? 127 : 32767;
            for (int i = 0; i < entryCount; i++) {
                int v;
                do {

                    v = (int) Randomly.getNotCachedInteger(0, valueBound + 1);
                } while (!usedValues.add(v));

                entries.add(new ClickHouseType.EnumEntry("e" + i, v));
            }
            return new ClickHouseType.Enum(width, entries);
        }

        public ClickHouseType getTypeTerm() {
            return typeTerm;
        }

        public ClickHouseDataType getType() {
            return clickHouseType;
        }

        @Override
        public String toString() {
            return textRepr;
        }

    }

    public static class ClickHouseColumn extends AbstractTableColumn<ClickHouseTable, ClickHouseLancerDataType> {

        private final boolean isAlias;
        private final boolean isMaterialized;

        public ClickHouseColumn(String name, ClickHouseLancerDataType columnType, boolean isAlias,
                boolean isMaterialized, ClickHouseTable table) {
            super(name, table, columnType);
            this.isAlias = isAlias;
            this.isMaterialized = isMaterialized;
        }

        public static ClickHouseSchema.ClickHouseColumn createDummy(String name, ClickHouseTable table) {
            return createDummy(name, table, null);
        }

        public static ClickHouseSchema.ClickHouseColumn createDummy(String name, ClickHouseTable table,
                ClickHouseGlobalState state) {
            return new ClickHouseSchema.ClickHouseColumn(name, ClickHouseLancerDataType.getRandom(state), false, false,
                    table);
        }

        public boolean isAlias() {
            return isAlias;
        }

        public boolean isMaterialized() {
            return isMaterialized;
        }

        public ClickHouseColumnReference asColumnReference(String tableAlias) {
            return new ClickHouseColumnReference(this, null, tableAlias);
        }

    }

    public static ClickHouseConstant getConstant(ResultSet randomRowValues, int columnIndex,
            ClickHouseDataType valueType) throws SQLException {
        if (randomRowValues.getString(columnIndex) == null) {
            return ClickHouseCreateConstant.createNullConstant();
        }
        switch (valueType) {
        case Int8:
            return ClickHouseCreateConstant.createInt8Constant(randomRowValues.getLong(columnIndex));
        case Int16:
            return ClickHouseCreateConstant.createInt16Constant(randomRowValues.getLong(columnIndex));
        case Int32:
            return ClickHouseCreateConstant.createInt32Constant(randomRowValues.getLong(columnIndex));
        case Int64:
            return ClickHouseCreateConstant
                    .createInt64Constant(java.math.BigInteger.valueOf(randomRowValues.getLong(columnIndex)));
        case UInt8:
            return ClickHouseCreateConstant.createUInt8Constant(randomRowValues.getLong(columnIndex));
        case UInt16:
            return ClickHouseCreateConstant.createUInt16Constant(randomRowValues.getLong(columnIndex));
        case UInt32:
            return ClickHouseCreateConstant.createUInt32Constant(randomRowValues.getLong(columnIndex));
        case UInt64:
            return ClickHouseCreateConstant
                    .createUInt64Constant(java.math.BigInteger.valueOf(randomRowValues.getLong(columnIndex)));
        case Float32:
            return ClickHouseCreateConstant.createFloat32Constant(randomRowValues.getFloat(columnIndex));
        case Float64:
            return ClickHouseCreateConstant.createFloat64Constant(randomRowValues.getDouble(columnIndex));
        case Bool:
            return ClickHouseCreateConstant.createBoolean(randomRowValues.getBoolean(columnIndex));
        case String:
        case FixedString:

            return ClickHouseCreateConstant.createStringConstant(randomRowValues.getString(columnIndex));
        case Date:
        case Date32:
        case DateTime:
        case DateTime32:
        case DateTime64:

            return ClickHouseCreateConstant.createStringConstant(randomRowValues.getString(columnIndex));
        default:

            throw new IgnoreMeException();
        }
    }

    public static class ClickHouseRowValue
            extends AbstractRowValue<ClickHouseTables, ClickHouseColumn, ClickHouseConstant> {

        public ClickHouseRowValue(ClickHouseSchema.ClickHouseTables tables,
                Map<ClickHouseSchema.ClickHouseColumn, ClickHouseConstant> values) {
            super(tables, values);
        }

    }

    public static class ClickHouseTables extends AbstractTables<ClickHouseTable, ClickHouseColumn> {

        public ClickHouseTables(List<ClickHouseSchema.ClickHouseTable> tables) {
            super(tables);
        }

    }

    public ClickHouseSchema(List<ClickHouseTable> databaseTables) {
        super(databaseTables);
    }

    public ClickHouseTables getRandomTableNonEmptyTables() {
        List<ClickHouseTable> stable = getTablesStableForRepeatedReads();
        if (stable.isEmpty()) {
            throw new IgnoreMeException();
        }
        return new ClickHouseTables(Randomly.nonEmptySubset(stable));
    }

    public List<ClickHouseTable> getTablesStableForRepeatedReads() {
        List<ClickHouseTable> stable = new ArrayList<>();
        for (ClickHouseTable t : getDatabaseTables()) {
            if (t.isStableForRepeatedReads()) {
                stable.add(t);
            }
        }
        return stable;
    }

    private static ClickHouseLancerDataType getColumnType(String typeString) {
        return new ClickHouseLancerDataType(typeString);
    }

    public static class ClickHouseTable
            extends AbstractRelationalTable<ClickHouseColumn, TableIndex, ClickHouseGlobalState> {

        private final String engine;
        private final String samplingKey;

        public ClickHouseTable(String tableName, List<ClickHouseColumn> columns, List<TableIndex> indexes,
                boolean isView) {
            this(tableName, columns, indexes, isView, "");
        }

        public ClickHouseTable(String tableName, List<ClickHouseColumn> columns, List<TableIndex> indexes,
                boolean isView, String engine) {
            this(tableName, columns, indexes, isView, engine, "");
        }

        public ClickHouseTable(String tableName, List<ClickHouseColumn> columns, List<TableIndex> indexes,
                boolean isView, String engine, String samplingKey) {
            super(tableName, columns, indexes, isView);
            this.engine = engine == null ? "" : engine;
            this.samplingKey = samplingKey == null ? "" : samplingKey;
        }

        public String getEngine() {
            return engine;
        }

        public String getSamplingKey() {
            return samplingKey;
        }

        public boolean hasSamplingKey() {
            return !samplingKey.isEmpty();
        }

        public boolean supportsFinal() {
            return engine.equals("ReplacingMergeTree") || engine.equals("SummingMergeTree")
                    || engine.equals("AggregatingMergeTree") || engine.equals("CollapsingMergeTree")
                    || engine.equals("VersionedCollapsingMergeTree");
        }

        public boolean isStableForRepeatedReads() {
            return !supportsFinal();
        }
    }

    public static ClickHouseSchema fromConnection(SQLConnection con, String databaseName) throws SQLException {
        List<ClickHouseTable> databaseTables = new ArrayList<>();
        List<String> tableNames = getTableNames(con);
        java.util.Map<String, TableMeta> metaByName = getTableMeta(con, databaseName);
        for (String tableName : tableNames) {
            List<ClickHouseColumn> databaseColumns = getTableColumns(con, tableName);
            List<TableIndex> indexes = Collections.emptyList();
            boolean isView = matchesViewName(tableName);
            TableMeta meta = metaByName.getOrDefault(tableName, TableMeta.EMPTY);
            ClickHouseTable t = new ClickHouseTable(tableName, databaseColumns, indexes, isView, meta.engine,
                    meta.samplingKey);
            for (ClickHouseColumn c : databaseColumns) {
                c.setTable(t);
            }
            databaseTables.add(t);

        }
        return new ClickHouseSchema(databaseTables);
    }

    private static final class TableMeta {
        static final TableMeta EMPTY = new TableMeta("", "");
        final String engine;
        final String samplingKey;

        TableMeta(String engine, String samplingKey) {
            this.engine = engine == null ? "" : engine;
            this.samplingKey = samplingKey == null ? "" : samplingKey;
        }
    }

    private static java.util.Map<String, TableMeta> getTableMeta(SQLConnection con, String databaseName)
            throws SQLException {
        java.util.Map<String, TableMeta> meta = new java.util.HashMap<>();
        try (Statement s = con.createStatement()) {
            String q = "SELECT name, engine, sampling_key FROM system.tables WHERE database = '"
                    + databaseName.replace("'", "''") + "'";
            try (ResultSet rs = s.executeQuery(q)) {
                while (rs.next()) {
                    meta.put(rs.getString(1), new TableMeta(rs.getString(2), rs.getString(3)));
                }
            }
        }
        return meta;
    }

    private static List<String> getTableNames(SQLConnection con) throws SQLException {
        List<String> tableNames = new ArrayList<>();
        try (Statement s = con.createStatement()) {
            ResultSet tableRs = s.executeQuery("SHOW TABLES");
            while (tableRs.next()) {
                String tableName = tableRs.getString(1);
                tableNames.add(tableName);
            }
        }
        return tableNames;
    }

    private static List<ClickHouseColumn> getTableColumns(SQLConnection con, String tableName) throws SQLException {
        List<ClickHouseColumn> columns = new ArrayList<>();
        List<ClickHouseColumn> ephemeral = new ArrayList<>();
        try (Statement s = con.createStatement()) {
            try (ResultSet rs = s.executeQuery("DESCRIBE " + tableName)) {
                while (rs.next()) {
                    String columnName = rs.getString("name");
                    String dataType = rs.getString("type");
                    String defaultType = rs.getString("default_type");
                    boolean isAlias = "ALIAS".compareTo(defaultType) == 0;
                    boolean isMaterialized = "MATERIALIZED".compareTo(defaultType) == 0;
                    ClickHouseColumn c = new ClickHouseColumn(columnName, getColumnType(dataType), isAlias,
                            isMaterialized, null);
                    if ("EPHEMERAL".compareTo(defaultType) == 0) {
                        ephemeral.add(c);
                    } else {
                        columns.add(c);
                    }
                }
            }
        }
        return columns.isEmpty() ? ephemeral : columns;
    }

}
