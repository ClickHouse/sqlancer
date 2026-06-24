package sqlancer.mysql.gen.tblmaintenance;

import java.util.List;
import java.util.stream.Collectors;

import sqlancer.Randomly;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.mysql.MySQLGlobalState;
import sqlancer.mysql.MySQLSchema.MySQLTable;
import sqlancer.mysql.MySQLSchema.MySQLTable.MySQLEngine;

public class MySQLRepair {

    private final List<MySQLTable> tables;
    private final StringBuilder sb = new StringBuilder();

    public MySQLRepair(List<MySQLTable> tables) {
        this.tables = tables;
    }

    public static SQLQueryAdapter repair(MySQLGlobalState globalState) {
        List<MySQLTable> tables = globalState.getSchema().getDatabaseTablesRandomSubsetNotEmpty();
        for (MySQLTable table : tables) {

            if (table.getEngine() == MySQLEngine.MY_ISAM) {
                return new SQLQueryAdapter("SELECT 1");
            }
        }
        return new MySQLRepair(tables).repair();
    }

    private SQLQueryAdapter repair() {
        sb.append("REPAIR");
        if (Randomly.getBoolean()) {
            sb.append(" ");
            sb.append(Randomly.fromOptions("NO_WRITE_TO_BINLOG", "LOCAL"));
        }
        sb.append(" TABLE ");
        sb.append(tables.stream().map(t -> t.getName()).collect(Collectors.joining(", ")));
        if (Randomly.getBoolean()) {
            sb.append(" QUICK");
        }
        if (Randomly.getBoolean()) {
            sb.append(" EXTENDED");
        }
        if (Randomly.getBoolean()) {
            sb.append(" USE_FRM");
        }
        return new SQLQueryAdapter(sb.toString());
    }

}
