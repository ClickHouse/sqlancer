package sqlancer.clickhouse.transport;

import java.io.IOException;
import java.io.InputStream;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.ServerException;
import com.clickhouse.client.api.enums.Protocol;
import com.clickhouse.client.api.query.QueryResponse;
import com.clickhouse.client.api.query.QuerySettings;

public final class ClickHouseClientV2Transport implements ClickHouseTransport {

    private static final Pattern USE_PATTERN = Pattern.compile("^\\s*USE\\s+`?([\\w_]+)`?\\s*;?\\s*$",
            Pattern.CASE_INSENSITIVE);

    private final Client client;
    private final Map<String, String> serverSettings;
    private String database;
    private volatile String cachedServerVersion;

    public ClickHouseClientV2Transport(String host, int port, String user, String password, String database,
            Map<String, String> serverSettings, long connectTimeoutMillis, long socketTimeoutMillis) {
        this.database = database;
        this.serverSettings = new LinkedHashMap<>(serverSettings);
        Client.Builder b = new Client.Builder().addEndpoint(Protocol.HTTP, host, port, false)
                .setDefaultDatabase(database).setConnectTimeout(connectTimeoutMillis)
                .setSocketTimeout(socketTimeoutMillis);
        if (user != null) {
            b.setUsername(user);
            b.setPassword(password == null ? "" : password);
        }
        this.client = b.build();
    }

    @Override
    public void executeUpdate(String sql) throws SQLException {
        String body = trimTrailingSemicolon(sql);
        String useTarget = matchUse(body);
        if (useTarget != null) {

            this.database = useTarget;
            return;
        }
        try (QueryResponse response = runQuery(body)) {

            try (InputStream in = response.getInputStream()) {
                byte[] buf = new byte[4096];
                int n;
                do {
                    n = in.read(buf);
                } while (n > 0);
            }
        } catch (IOException e) {
            throw new SQLException("Transport I/O error: " + e.getMessage(), e);
        } catch (Exception e) {
            throw asSqlException(e, body);
        }
    }

    @Override
    public ResultData executeQuery(String sql) throws SQLException {
        String body = trimTrailingSemicolon(sql) + " FORMAT " + ClickHouseRowBinaryParser.FORMAT;
        try (QueryResponse response = runQuery(body); InputStream in = response.getInputStream()) {
            return ClickHouseRowBinaryParser.parse(in);
        } catch (IOException e) {
            throw new SQLException("Transport I/O error: " + e.getMessage(), e);
        } catch (Exception e) {
            throw asSqlException(e, body);
        }
    }

    @Override
    public String getServerVersion() throws SQLException {
        if (cachedServerVersion == null) {
            ResultData rd = executeQuery("SELECT version()");
            cachedServerVersion = rd.rows.isEmpty() ? "unknown" : rd.rows.get(0).get(0);
        }
        return cachedServerVersion;
    }

    @Override
    public void close() {
        try {
            client.close();
        } catch (Exception ignored) {

        }
    }

    private QueryResponse runQuery(String sql) throws Exception {
        QuerySettings qs = new QuerySettings().setDatabase(database);
        for (Map.Entry<String, String> e : serverSettings.entrySet()) {
            qs.serverSetting(e.getKey(), e.getValue());
        }
        try {
            return client.query(sql, qs).get();
        } catch (ExecutionException ex) {

            Throwable cause = ex.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw ex;
        }
    }

    private static SQLException asSqlException(Throwable t, String query) {

        int vendorCode = 0;
        String text = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
        if (t instanceof ServerException) {
            vendorCode = ((ServerException) t).getCode();
        } else {
            Matcher m = Pattern.compile("Code: (\\d+)").matcher(text);
            if (m.find()) {
                try {
                    vendorCode = Integer.parseInt(m.group(1));
                } catch (NumberFormatException ignored) {

                }
            }
        }
        String shortQuery = query.length() > 120 ? query.substring(0, 120) + "..." : query;
        return new SQLException(text.trim() + " [client-v2, query: " + shortQuery + "]", null, vendorCode, t);
    }

    private static String trimTrailingSemicolon(String sql) {
        String s = sql.trim();
        while (s.endsWith(";")) {
            s = s.substring(0, s.length() - 1).trim();
        }
        return s;
    }

    private static String matchUse(String sql) {
        Matcher m = USE_PATTERN.matcher(sql);
        return m.matches() ? m.group(1) : null;
    }
}
