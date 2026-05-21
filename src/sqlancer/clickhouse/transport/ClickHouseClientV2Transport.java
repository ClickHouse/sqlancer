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

// Transport backed by clickhouse-java's client-v2 (com.clickhouse.client.api.Client). Requests
// RowBinaryWithNamesAndTypes output so the result-parsing layer is identical to
// {@link ClickHouseHttpTransport} -- both feed bytes through {@link ClickHouseRowBinaryParser} into the
// transport-agnostic {@link ClickHouseTransport.ResultData}.
//
// Why client-v2 and not jdbc-v2 (the historical default):
//  - jdbc-v2 wraps client-v2 anyway; every error in the jdbc-v2 stack has a client-v2 root cause.
//  - JDBC's primitive accessors lose UInt64 (gets ArithmeticException via getLong) and out-of-range
//    DateTime (gets java.time.DateTimeException via getTimestamp). We don't need primitives -- the
//    parser hands us textual ClickHouse values directly.
//  - client-v2 exposes the raw response InputStream, so we avoid the JDBC ResultSet close-time
//    `Premature end of chunk coded message body` family that the local patch in jdbc-v2 was
//    written to suppress. The patch (and the maintained-jar burden) disappears entirely.
//
// Server-side ClickHouse settings (max_execution_time, allow_experimental_analyzer, ...) are
// applied per-query via {@link QuerySettings#serverSetting}. They are NOT pinned at the client
// builder because client-v2 pools connections; per-query attachment guarantees every request
// carries the same setting regardless of which pooled connection it lands on.
public final class ClickHouseClientV2Transport implements ClickHouseTransport {

    private static final Pattern USE_PATTERN = Pattern.compile("^\\s*USE\\s+`?([\\w_]+)`?\\s*;?\\s*$",
            Pattern.CASE_INSENSITIVE);

    private final Client client;
    private final Map<String, String> serverSettings;
    private String database; // updated by USE statements
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
            // USE is parsed locally; we steer the per-request `database` parameter ourselves.
            this.database = useTarget;
            return;
        }
        try (QueryResponse response = runQuery(body)) {
            // Drain the body. INSERT/DDL responses are empty or a stats summary; we don't care
            // about the content, only that the server signalled completion.
            try (InputStream in = response.getInputStream()) {
                byte[] buf = new byte[4096];
                while (in.read(buf) > 0) {
                    // intentional
                }
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
            // Best-effort close.
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
            // CompletableFuture.get() wraps the real cause. Unwrap so as-SQLException catches it.
            Throwable cause = ex.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw ex;
        }
    }

    private static SQLException asSqlException(Throwable t, String query) {
        // Preserve the ClickHouse error code (vendorCode) in the SQLException so
        // ClickHouseErrors.errorIsExpected() can substring-match against the message text exactly
        // like the JDBC driver did.
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
                    // leave at 0
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
