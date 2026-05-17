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

        // Root ClickHouseDataType of the term. Nullable and LowCardinality are transparent; Unknown
        // maps to Nothing as a lossy compatibility shim for legacy callers that expect the flat enum
        // (documented in the v1 type-system foundation plan, Unit 5).
        private static ClickHouseDataType rootClickHouseDataType(ClickHouseType t) {
            ClickHouseType inner = t.unwrap();
            if (inner instanceof Primitive p) {
                return p.kind().toClickHouseDataType();
            }
            return ClickHouseDataType.Nothing;
        }

        public static ClickHouseLancerDataType getRandom() {
            return getRandom(null);
        }

        // Pick a random v1 type, optionally wrapping with Nullable / LowCardinality when the
        // feature flags on `state` permit. With both flags off the result is always a Primitive.
        // `state` may be null -- in that case both wrappers are disabled (used by legacy fixtures
        // and dummy-column factories).
        public static ClickHouseLancerDataType getRandom(ClickHouseGlobalState state) {
            ClickHouseOptions opts = state == null ? null : state.getDbmsSpecificOptions();
            boolean enableNullable = opts != null && opts.enableNullable;
            boolean enableLowCardinality = opts != null && opts.enableLowCardinality;
            Kind kind = Randomly.fromOptions(Kind.Int32, Kind.String);
            ClickHouseType picked = new Primitive(kind);
            if (enableNullable && Randomly.getBooleanWithSmallProbability() && Nullable.canWrap(picked)) {
                picked = new Nullable(picked);
            }
            if (enableLowCardinality && Randomly.getBooleanWithSmallProbability() && LowCardinality.canWrap(picked)) {
                picked = new LowCardinality(picked);
            }
            return new ClickHouseLancerDataType(picked);
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

        // Build a dummy column for schema generation. When state is non-null and the Nullable /
        // LowCardinality feature flags are enabled, the picked type may be wrapped accordingly;
        // callers that don't have a state (test fixtures, AST scaffolding) pass null.
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
            return ClickHouseCreateConstant.createStringConstant(randomRowValues.getString(columnIndex));
        default:
            // Types beyond the v1 set (Decimal, Date*, IPv*, UUID, Enum*, composites, etc.) are not
            // round-trippable through ClickHouseConstant yet -- callers (PQS) skip the row via
            // IgnoreMeException rather than fabricating a constant.
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
        return new ClickHouseTables(Randomly.nonEmptySubset(getDatabaseTables()));
    }

    private static ClickHouseLancerDataType getColumnType(String typeString) {
        return new ClickHouseLancerDataType(typeString);
    }

    public static class ClickHouseTable
            extends AbstractRelationalTable<ClickHouseColumn, TableIndex, ClickHouseGlobalState> {

        public ClickHouseTable(String tableName, List<ClickHouseColumn> columns, List<TableIndex> indexes,
                boolean isView) {
            super(tableName, columns, indexes, isView);
        }
    }

    public static ClickHouseSchema fromConnection(SQLConnection con, String databaseName) throws SQLException {
        List<ClickHouseTable> databaseTables = new ArrayList<>();
        List<String> tableNames = getTableNames(con);
        for (String tableName : tableNames) {
            List<ClickHouseColumn> databaseColumns = getTableColumns(con, tableName);
            List<TableIndex> indexes = Collections.emptyList();
            boolean isView = matchesViewName(tableName);
            ClickHouseTable t = new ClickHouseTable(tableName, databaseColumns, indexes, isView);
            for (ClickHouseColumn c : databaseColumns) {
                c.setTable(t);
            }
            databaseTables.add(t);

        }
        return new ClickHouseSchema(databaseTables);
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
                    columns.add(c);
                }
            }
        }
        return columns;
    }

}
