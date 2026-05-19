package sqlancer.clickhouse.transport;

import java.io.Closeable;
import java.sql.SQLException;
import java.util.List;

/**
 * Minimum transport contract sqlancer needs to drive ClickHouse: execute a side-effect statement
 * and execute a SELECT that returns rows. Implementations exist for the JDBC driver
 * (clickhouse-jdbc, used historically) and for raw HTTP POST to the server's `/` endpoint
 * (preferred -- avoids the JDBC driver's intermittent MalformedChunkCodingException /
 * UInt64-overflow / per-request URI rebuild costs we documented in the 2026-05-19 run).
 */
public interface ClickHouseTransport extends Closeable {

    /** Execute a statement that returns no rows (DDL / INSERT). Throws on server error. */
    void executeUpdate(String sql) throws SQLException;

    /** Execute a query and return its rows. The returned data is fully materialised. */
    ResultData executeQuery(String sql) throws SQLException;

    /** Server version string, used by {@code SQLConnection.getDatabaseVersion()}. */
    String getServerVersion() throws SQLException;

    @Override
    void close();

    /** Plain-data carrier for one full SELECT result. Materialised; not streamed. */
    final class ResultData {
        public final List<String> columnNames;
        public final List<String> columnTypes;
        // Each inner list is one row; values are the raw textual form ClickHouse emits (or null
        // for SQL NULL).
        public final List<List<String>> rows;

        public ResultData(List<String> columnNames, List<String> columnTypes, List<List<String>> rows) {
            this.columnNames = columnNames;
            this.columnTypes = columnTypes;
            this.rows = rows;
        }
    }
}
