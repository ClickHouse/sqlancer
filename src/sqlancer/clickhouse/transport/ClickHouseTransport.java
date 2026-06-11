package sqlancer.clickhouse.transport;

import java.io.Closeable;
import java.sql.SQLException;
import java.util.List;

public interface ClickHouseTransport extends Closeable {

    void executeUpdate(String sql) throws SQLException;

    ResultData executeQuery(String sql) throws SQLException;

    String getServerVersion() throws SQLException;

    @Override
    void close();

    final class ResultData {
        public final List<String> columnNames;
        public final List<String> columnTypes;

        public final List<List<String>> rows;

        public ResultData(List<String> columnNames, List<String> columnTypes, List<List<String>> rows) {
            this.columnNames = columnNames;
            this.columnTypes = columnTypes;
            this.rows = rows;
        }
    }
}
