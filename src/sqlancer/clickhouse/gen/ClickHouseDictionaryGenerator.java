package sqlancer.clickhouse.gen;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import com.clickhouse.data.ClickHouseDataType;

import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.clickhouse.ClickHouseProvider.ClickHouseGlobalState;
import sqlancer.clickhouse.ClickHouseSchema.ClickHouseColumn;
import sqlancer.clickhouse.ClickHouseSchema.ClickHouseTable;
import sqlancer.clickhouse.ast.ClickHouseDictionaryDdlStatement;

public final class ClickHouseDictionaryGenerator {

    private static final AtomicLong DICT_COUNTER = new AtomicLong();

    private ClickHouseDictionaryGenerator() {
    }

    public static ClickHouseDictionaryDdlStatement createDictionary(ClickHouseGlobalState state) {
        List<ClickHouseTable> tables = state.getSchema().getDatabaseTables().stream().filter(t -> !t.isView())
                .collect(Collectors.toList());
        if (tables.isEmpty()) {
            throw new IgnoreMeException();
        }
        ClickHouseTable srcTable = Randomly.fromList(tables);
        List<ClickHouseColumn> intCols = srcTable.getColumns().stream().filter(c -> {
            ClickHouseDataType t = c.getType().getType();
            return t == ClickHouseDataType.UInt32 || t == ClickHouseDataType.UInt64 || t == ClickHouseDataType.Int32
                    || t == ClickHouseDataType.Int64;
        }).collect(Collectors.toList());
        if (intCols.isEmpty() || srcTable.getColumns().size() < 2) {
            throw new IgnoreMeException();
        }
        ClickHouseColumn keyCol = Randomly.fromList(intCols);
        ClickHouseColumn valCol = Randomly
                .fromList(srcTable.getColumns().stream().filter(c -> c != keyCol).collect(Collectors.toList()));

        String dictName = "d" + DICT_COUNTER.incrementAndGet();
        String fqDict = state.getDatabaseName() + "." + dictName;
        String layout = Randomly.fromOptions("HASHED()", "FLAT()", "COMPLEX_KEY_HASHED()", "HASHED()");

        String sql = String.format(
                "CREATE DICTIONARY %s (%s UInt64, %s String) PRIMARY KEY %s "
                        + "SOURCE(CLICKHOUSE(TABLE '%s' DB '%s')) LIFETIME(0) LAYOUT(%s)",
                fqDict, keyCol.getName(), valCol.getName(), keyCol.getName(), srcTable.getName(),
                state.getDatabaseName(), layout);
        return new ClickHouseDictionaryDdlStatement(ClickHouseDictionaryDdlStatement.Kind.CREATE_DICTIONARY, dictName,
                sql);
    }

    public static ClickHouseDictionaryDdlStatement dropDictionary(String dictName, ClickHouseGlobalState state) {
        String sql = "DROP DICTIONARY IF EXISTS " + state.getDatabaseName() + "." + dictName;
        return new ClickHouseDictionaryDdlStatement(ClickHouseDictionaryDdlStatement.Kind.DROP_DICTIONARY, dictName,
                sql);
    }

    public static ClickHouseDictionaryDdlStatement alterDictionaryLifetime(String dictName,
            ClickHouseGlobalState state) {
        String sql = "ALTER DICTIONARY " + state.getDatabaseName() + "." + dictName + " LIFETIME(0)";
        return new ClickHouseDictionaryDdlStatement(ClickHouseDictionaryDdlStatement.Kind.ALTER_DICTIONARY, dictName,
                sql);
    }

    public static boolean execute(ClickHouseGlobalState state, ClickHouseDictionaryDdlStatement stmt) {
        try (Statement s = state.getConnection().createStatement()) {
            s.execute(stmt.getSql());
            return true;
        } catch (SQLException e) {
            return false;
        }
    }
}
