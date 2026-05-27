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

        // Root ClickHouseDataType of the term. Nullable and LowCardinality are transparent; parameterised
        // primitives map onto the JDBC flat enum's representative tag (Decimal -> Decimal, FixedString
        // -> FixedString, DateTime64Type -> DateTime64, Array -> Array). Unknown maps to Nothing as a
        // lossy compatibility shim for legacy callers that expect the flat enum.
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
            // Interval* maps to the IntervalSecond representative; CH's JDBC enum doesn't have a
            // generic Interval, so we collapse to one entry.
            if (inner instanceof ClickHouseType.IntervalType) {
                return ClickHouseDataType.IntervalSecond;
            }
            return ClickHouseDataType.Nothing;
        }

        public static ClickHouseLancerDataType getRandom() {
            return getRandom(null);
        }

        // Pick a random v2 type, optionally wrapping with Nullable / LowCardinality / Array when the
        // feature flags on `state` permit. With all flags off the result is always a scalar.
        // `state` may be null -- in that case all wrappers are disabled (used by legacy fixtures,
        // dummy-column factories, and binary-operator leaf-type picks in the expression generator
        // where we don't want Array leaves to appear inside arithmetic).
        public static ClickHouseLancerDataType getRandom(ClickHouseGlobalState state) {
            ClickHouseOptions opts = state == null ? null : state.getDbmsSpecificOptions();
            boolean enableNullable = opts != null && opts.enableNullable;
            boolean enableLowCardinality = opts != null && opts.enableLowCardinality;
            boolean enableArray = opts != null && opts.enableArrayJoin;
            ClickHouseType picked = pickScalarType();
            if (enableNullable && Randomly.getBooleanWithSmallProbability() && Nullable.canWrap(picked)) {
                picked = new Nullable(picked);
            }
            // Array wraps before LowCardinality so the canonical form is LowCardinality(Array(...))
            // -- but ClickHouse rejects LowCardinality(Array(...)), so when Array is picked we never
            // wrap it in LowCardinality. Array(Nullable(T)) is allowed and we keep that order.
            if (enableArray && Randomly.getBooleanWithSmallProbability() && Array.canWrap(picked)) {
                picked = new Array(picked);
            }
            if (enableLowCardinality && Randomly.getBooleanWithSmallProbability() && LowCardinality.canWrap(picked)) {
                picked = new LowCardinality(picked);
            }
            return new ClickHouseLancerDataType(picked);
        }

        // Weighted scalar-type pick. Distribution biased toward bug-bait surfaces:
        // * Int32 / String -- v1 default, keeps generated output close to historical baselines.
        // * UInt32 / UInt64 -- needed for ReplacingMergeTree(ver) and Summing column args, and to
        // surface mixed-width JOIN-key cross-type bugs (e.g. #101652).
        // * Date / DateTime -- exercises Date arithmetic and time-based partition keys
        // (toYYYYMM(t) shape from #104781 reporter).
        // * Other Int*/Float* variants -- low individual weight, present for coverage.
        // * FixedString(N) / Decimal(p,s) / DateTime64(prec) -- parameterised, exercised at low
        // rate so the generator surfaces them without dominating the pool.
        // UUID / IPv4 / IPv6 are omitted from the picker: literal emission is feasible (toUUID(...)
        // etc.) but PQS does not have ResultSet round-trip support for them, so columns of those
        // types poison PQS iterations with IgnoreMeException at the row-fetch step. They remain
        // reachable via schema reflection of pre-existing tables -- the parser still recognises
        // their type strings -- but the generator does not synthesise them.
        // Pick a primitive Kind for use as a leaf in composite type construction (Tuple element,
        // Map value, Nested field). Restricted to types that round-trip through the existing
        // constant emitters so the composite's literal form is well-defined.
        private static Kind pickPrimitiveKind() {
            int r = (int) Randomly.getNotCachedInteger(0, 5);
            switch (r) {
            case 0: return Kind.Int32;
            case 1: return Kind.String;
            case 2: return Kind.UInt64;
            case 3: return Kind.Float64;
            default: return Kind.Date;
            }
        }

        private static ClickHouseType pickScalarType() {
            int roll = (int) Randomly.getNotCachedInteger(0, 100);
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
                // Decimal at 1% (compressed from 2% to make room for new composite types).
                int p = 1 + (int) Randomly.getNotCachedInteger(0, Randomly.getBoolean() ? 18 : 38);
                int s = (int) Randomly.getNotCachedInteger(0, p + 1);
                return new Decimal(p, s);
            }
            if (roll < 93) {
                // 1% -- DateTime64 with random precision 0..6.
                return new DateTime64Type((int) Randomly.getNotCachedInteger(0, 7));
            }
            if (roll < 94) {
                // Time / Time64 -- recent CH addition (>= 24.x).
                if (Randomly.getBoolean()) {
                    return new ClickHouseType.Time();
                }
                return new ClickHouseType.Time64((int) Randomly.getNotCachedInteger(0, 7));
            }
            if (roll < 95) {
                // Tuple(T1, T2) of 2-3 primitive elements -- workstream 2.
                int arity = 2 + (int) Randomly.getNotCachedInteger(0, 2);
                java.util.List<ClickHouseType> els = new java.util.ArrayList<>();
                for (int i = 0; i < arity; i++) {
                    // Inner types are restricted to scalar primitives to keep the literal
                    // emission bounded; the inner picker recurses but we cap at depth 1.
                    els.add(new Primitive(pickPrimitiveKind()));
                }
                return new ClickHouseType.Tuple(els);
            }
            if (roll < 96) {
                // Map(K, V) -- K from the hashable-key set, V from a primitive.
                ClickHouseType key;
                int keyRoll = (int) Randomly.getNotCachedInteger(0, 4);
                switch (keyRoll) {
                case 0: key = new Primitive(Kind.String); break;
                case 1: key = new Primitive(Kind.UInt32); break;
                case 2: key = new Primitive(Kind.Int32); break;
                default: key = new Primitive(Kind.Date); break;
                }
                return new ClickHouseType.Map(key, new Primitive(pickPrimitiveKind()));
            }
            if (roll < 97) {
                // Geo: Point is the canonical case; Ring/Polygon/MultiPolygon are nested arrays
                // of points and exercise more code paths than the picker should emit blindly --
                // pick those at lower weight.
                int geoRoll = (int) Randomly.getNotCachedInteger(0, 10);
                if (geoRoll < 7) {
                    return new ClickHouseType.Point();
                } else if (geoRoll < 9) {
                    return new ClickHouseType.Ring();
                } else {
                    return new ClickHouseType.Polygon();
                }
            }
            if (roll < 98) {
                // Nested -- DDL only; subfield access requires ARRAY JOIN which the expression
                // generator doesn't synthesise yet, so most queries against nested columns will
                // fail with tolerated errors.
                int fieldCount = 2 + (int) Randomly.getNotCachedInteger(0, 2);
                java.util.List<ClickHouseType.NestedField> fields = new java.util.ArrayList<>();
                for (int i = 0; i < fieldCount; i++) {
                    fields.add(new ClickHouseType.NestedField("nf" + i, new Primitive(pickPrimitiveKind())));
                }
                return new ClickHouseType.Nested(fields);
            }
            // The remaining 2% of the roll range covers JSON-family, AggregateFunction, and Enum.
            // Each gets sub-divided uniformly via a fresh roll so the picker stays sensitive to
            // the same total weighting across the tail. Workstreams 2/5/6.
            int tailRoll = (int) Randomly.getNotCachedInteger(0, 3);
            if (tailRoll == 0) {
                // JSON / Variant / Dynamic (workstream 6).
                int jvdRoll = (int) Randomly.getNotCachedInteger(0, 3);
                if (jvdRoll == 0) {
                    return new ClickHouseType.JSON();
                }
                if (jvdRoll == 1) {
                    java.util.List<ClickHouseType> alts = java.util.List.of(new Primitive(Kind.Int32),
                            new Primitive(Kind.String));
                    return new ClickHouseType.Variant(alts);
                }
                return new ClickHouseType.Dynamic();
            }
            if (tailRoll == 1) {
                // AggregateFunction(name, ArgType) and SimpleAggregateFunction sister entry.
                // Workstream 5 of the plan.
                String aggName = Randomly.fromOptions("sum", "min", "max", "any", "anyLast", "count");
                Kind argKind = Randomly.fromOptions(Kind.Int32, Kind.Int64, Kind.UInt32, Kind.UInt64, Kind.Float64);
                if (Randomly.getBoolean()) {
                    return new ClickHouseType.AggregateFunctionType(aggName,
                            java.util.List.of(new Primitive(argKind)));
                }
                return new ClickHouseType.SimpleAggregateFunctionType(aggName, new Primitive(argKind));
            }
            // Remaining 1% -- Enum8 / Enum16 with a small entry set. The value domain is constrained
            // to the appropriate signed range; entry names are short identifiers so the DDL stays
            // compact and the literal emission picks readable values. Tuple is intentionally NOT
            // emitted here yet -- composite-column INSERT support needs more work in the constant
            // generator and many oracles emit `col + 1` blindly which would fail on tuple columns.
            int entryCount = 2 + (int) Randomly.getNotCachedInteger(0, 4);
            java.util.List<ClickHouseType.EnumEntry> entries = new java.util.ArrayList<>();
            java.util.Set<Integer> usedValues = new java.util.HashSet<>();
            int width = Randomly.getBoolean() ? 8 : 16;
            int valueBound = width == 8 ? 127 : 32767;
            for (int i = 0; i < entryCount; i++) {
                int v;
                do {
                    // Keep values in [0, valueBound] so the renderer doesn't need to handle the
                    // sign. The full signed range is permitted by CH but our generator emits the
                    // positive half only to simplify rollback / replay reading.
                    v = (int) Randomly.getNotCachedInteger(0, valueBound + 1);
                } while (!usedValues.add(v));
                // Entry names are short ASCII identifiers prefixed with 'e' to keep them legal.
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
        case FixedString:
            // FixedString round-trips as a String literal (single-quoted with embedded NULs escaped
            // by the JDBC driver). PQS compares as text since the value domain is bytes.
            return ClickHouseCreateConstant.createStringConstant(randomRowValues.getString(columnIndex));
        case Date:
        case Date32:
        case DateTime:
        case DateTime32:
        case DateTime64:
            // Render the temporal value as a quoted string. ClickHouse coerces a string literal in a
            // comparison against a Date/DateTime column via the usual parseDateTimeBestEffort path,
            // so the predicate stays well-typed. getString() on a Date column returns YYYY-MM-DD;
            // on DateTime it returns YYYY-MM-DD HH:MM:SS[.fraction]. Null was already handled above.
            return ClickHouseCreateConstant.createStringConstant(randomRowValues.getString(columnIndex));
        default:
            // Types beyond the v2 emit surface (Decimal, IPv*, UUID, Enum*, composites, etc.) are not
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

        /**
         * Engine name as returned by {@code system.tables.engine} (e.g. {@code MergeTree}, {@code ReplacingMergeTree},
         * {@code View}). Used by oracles to gate engine-specific query shapes: {@code FINAL} is rejected by plain
         * {@code MergeTree} but accepted by Replacing/Summing/Aggregating variants, so emitting FINAL blindly poisons
         * iterations against plain MergeTree tables.
         *
         * <p>
         * Empty string when the engine could not be discovered (legacy or stripped catalog response). Callers treat
         * empty as "do not emit engine-specific shapes" to fail closed.
         */
        private final String engine;

        public ClickHouseTable(String tableName, List<ClickHouseColumn> columns, List<TableIndex> indexes,
                boolean isView) {
            this(tableName, columns, indexes, isView, "");
        }

        public ClickHouseTable(String tableName, List<ClickHouseColumn> columns, List<TableIndex> indexes,
                boolean isView, String engine) {
            super(tableName, columns, indexes, isView);
            this.engine = engine == null ? "" : engine;
        }

        public String getEngine() {
            return engine;
        }

        /**
         * True for engines that accept the {@code FINAL} modifier in a SELECT. Plain {@code MergeTree} does not -- it
         * raises {@code ILLEGAL_FINAL} -- so this method returns false for it even though MergeTree is in the same
         * engine family.
         */
        public boolean supportsFinal() {
            return engine.equals("ReplacingMergeTree") || engine.equals("SummingMergeTree")
                    || engine.equals("AggregatingMergeTree") || engine.equals("CollapsingMergeTree")
                    || engine.equals("VersionedCollapsingMergeTree");
        }
    }

    public static ClickHouseSchema fromConnection(SQLConnection con, String databaseName) throws SQLException {
        List<ClickHouseTable> databaseTables = new ArrayList<>();
        List<String> tableNames = getTableNames(con);
        java.util.Map<String, String> engineByName = getTableEngines(con, databaseName);
        for (String tableName : tableNames) {
            List<ClickHouseColumn> databaseColumns = getTableColumns(con, tableName);
            List<TableIndex> indexes = Collections.emptyList();
            boolean isView = matchesViewName(tableName);
            String engine = engineByName.getOrDefault(tableName, "");
            ClickHouseTable t = new ClickHouseTable(tableName, databaseColumns, indexes, isView, engine);
            for (ClickHouseColumn c : databaseColumns) {
                c.setTable(t);
            }
            databaseTables.add(t);

        }
        return new ClickHouseSchema(databaseTables);
    }

    // Pulls engine names for every table in the database in one round trip. Missing entries (e.g.
    // a table that was just dropped between SHOW TABLES and this call) map to empty string -- the
    // table object then refuses to opt in to engine-specific shapes via supportsFinal().
    private static java.util.Map<String, String> getTableEngines(SQLConnection con, String databaseName)
            throws SQLException {
        java.util.Map<String, String> engines = new java.util.HashMap<>();
        try (Statement s = con.createStatement()) {
            String q = "SELECT name, engine FROM system.tables WHERE database = '" + databaseName.replace("'", "''")
                    + "'";
            try (ResultSet rs = s.executeQuery(q)) {
                while (rs.next()) {
                    engines.put(rs.getString(1), rs.getString(2));
                }
            }
        }
        return engines;
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
