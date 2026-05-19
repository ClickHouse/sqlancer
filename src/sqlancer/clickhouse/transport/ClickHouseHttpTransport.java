package sqlancer.clickhouse.transport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTTP-backed transport. Posts each statement to {@code http://host:port/} with the SQL as the
 * request body and the result format pinned to {@code TabSeparatedWithNamesAndTypes} so we get a
 * deterministic header (names line + types line) plus rows. Bypasses clickhouse-jdbc entirely.
 *
 * Design notes:
 * <ul>
 *   <li>One {@link HttpURLConnection} per request (no keep-alive pooling). The JDK's default
 *       connection pool covers loopback fine for sqlancer's throughput; the alternative
 *       (Apache HC) is what causes the MalformedChunkCodingException family we're trying to
 *       avoid.</li>
 *   <li>Settings (database, max_execution_time, etc.) ride as query-string parameters so the
 *       server applies them before committing to a response.</li>
 *   <li>For SELECTs we materialise the full result. sqlancer's oracles read rows fully anyway
 *       (cardinality comparison, first-column-as-string list) so streaming buys nothing.</li>
 *   <li>For INSERTs and DDL we still go through this path; the server accepts them on the same
 *       endpoint and the response is empty (or a small stats line).</li>
 * </ul>
 */
public final class ClickHouseHttpTransport implements ClickHouseTransport {

    static {
        // JDK's HttpURLConnection caps the per-destination keep-alive pool at 5 by default. With
        // 6 sqlancer threads talking to one ClickHouse, that cap forces the 6th request to either
        // open a fresh TCP socket every time or wait, defeating most of the keep-alive win. Bump
        // to 32 so up to 32 concurrent sqlancer workers all share the pool. These props are read
        // when the http handler is first initialised; setting them before the first HTTP call
        // (i.e. at class-load time of this transport) ensures the lookup uses our values.
        if (System.getProperty("http.keepAlive") == null) {
            System.setProperty("http.keepAlive", "true");
        }
        if (System.getProperty("http.maxConnections") == null) {
            System.setProperty("http.maxConnections", "32");
        }
    }

    private static final Pattern ERROR_CODE = Pattern.compile("Code: (\\d+)");
    private static final String FORMAT = "TabSeparatedWithNamesAndTypes";

    private final String baseUrl; // "http://host:port"
    private final String authHeader; // may be null for unauthenticated default user
    private final Map<String, String> settings; // applied to every request as ?k=v
    private final int httpReadTimeoutMillis;
    private final int httpConnectTimeoutMillis;
    private String database; // mutable: USE statements update this so subsequent requests pick it up

    private volatile String cachedServerVersion;

    public ClickHouseHttpTransport(String host, int port, String user, String password, String database,
            Map<String, String> settings, int httpConnectTimeoutMillis, int httpReadTimeoutMillis) {
        this.baseUrl = String.format("http://%s:%d", host, port);
        this.database = database;
        this.settings = new LinkedHashMap<>(settings);
        this.httpConnectTimeoutMillis = httpConnectTimeoutMillis;
        this.httpReadTimeoutMillis = httpReadTimeoutMillis;
        // ClickHouse accepts Basic auth even when the user has no password (empty string). Sending
        // an Authorization header avoids the entrypoint's default-user-disabled trap we hit on the
        // :head image where unauthenticated default-user access was tightened.
        if (user != null) {
            String pw = password == null ? "" : password;
            String token = Base64.getEncoder().encodeToString((user + ":" + pw).getBytes(StandardCharsets.UTF_8));
            this.authHeader = "Basic " + token;
        } else {
            this.authHeader = null;
        }
    }

    @Override
    public void executeUpdate(String sql) throws SQLException {
        // Strip a trailing ";" -- the HTTP endpoint rejects multi-statement bodies and refuses
        // even a stray trailing semicolon ("Multi-statements are not allowed").
        String body = trimTrailingSemicolon(sql);
        // USE is parsed locally; the server's HTTP endpoint silently no-ops it because the per-
        // request `?database=` parameter overrides. We track it on the transport so subsequent
        // requests target the right schema.
        String useTarget = matchUse(body);
        if (useTarget != null) {
            this.database = useTarget;
            return;
        }
        post(body, null);
    }

    @Override
    public ResultData executeQuery(String sql) throws SQLException {
        String body = trimTrailingSemicolon(sql) + " FORMAT " + FORMAT;
        return post(body, ResultParser.INSTANCE);
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
        // HttpURLConnection is per-request; nothing to release.
    }

    // ===== request plumbing ===================================================================

    private interface ResponseHandler<T> {
        T parse(InputStream body) throws IOException;
    }

    private static final class ResultParser implements ResponseHandler<ResultData> {
        static final ResultParser INSTANCE = new ResultParser();

        @Override
        public ResultData parse(InputStream body) throws IOException {
            // TabSeparatedWithNamesAndTypes:
            //   line 1: column names (TAB-separated)
            //   line 2: column types (TAB-separated)
            //   line 3..: rows
            // TSV-escaping: \t \n \\ \\N (the last is the literal SQL NULL token, distinct from
            // an empty cell).
            byte[] raw = readAllBytes(body);
            String text = new String(raw, StandardCharsets.UTF_8);
            if (text.isEmpty()) {
                return new ResultData(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
            }
            // Split into logical lines respecting TSV row delimiter (raw '\n'; escaped is '\\n').
            // Standard String.split won't do because escaped backslash-n must NOT terminate a row.
            List<String> lines = splitTsvLines(text);
            if (lines.size() < 2) {
                // Update with no rows (empty SELECT) shouldn't reach here -- but be defensive.
                return new ResultData(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
            }
            List<String> names = splitTsvFields(lines.get(0));
            List<String> types = splitTsvFields(lines.get(1));
            List<List<String>> rows = new ArrayList<>(Math.max(0, lines.size() - 2));
            for (int i = 2; i < lines.size(); i++) {
                if (lines.get(i).isEmpty()) {
                    // Trailing newline produces an empty tail entry; skip it.
                    continue;
                }
                rows.add(splitTsvFields(lines.get(i)));
            }
            return new ResultData(names, types, rows);
        }
    }

    private <T> T post(String body, ResponseHandler<T> handler) throws SQLException {
        URL url;
        try {
            url = new URL(baseUrl + "/?" + buildQueryString());
        } catch (IOException e) {
            throw new SQLException("Bad ClickHouse URL", e);
        }
        HttpURLConnection con = null;
        boolean keepAlive = false;
        try {
            con = (HttpURLConnection) url.openConnection();
            con.setRequestMethod("POST");
            con.setDoOutput(true);
            con.setConnectTimeout(httpConnectTimeoutMillis);
            con.setReadTimeout(httpReadTimeoutMillis);
            con.setRequestProperty("Content-Type", "text/plain; charset=UTF-8");
            // Explicit keep-alive header. HTTP/1.1 implies it but ClickHouse occasionally honours
            // server-side close hints, so being explicit costs nothing.
            con.setRequestProperty("Connection", "keep-alive");
            if (authHeader != null) {
                con.setRequestProperty("Authorization", authHeader);
            }
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            con.setFixedLengthStreamingMode(payload.length);
            try (var out = con.getOutputStream()) {
                out.write(payload);
            }
            int status = con.getResponseCode();
            if (status / 100 != 2) {
                // ClickHouse writes the full error body (Code: NNN. DB::Exception: ...) to the
                // error stream. Surface it as a SQLException carrying the same shape sqlancer's
                // ClickHouseErrors substring-matches against.
                String errBody = readErrorStream(con);
                // Drain the error stream so the JDK reuses the socket for the next request.
                // (ClickHouse keeps the connection alive even on 4xx/5xx responses.)
                keepAlive = true;
                throw newServerException(status, errBody, body);
            }
            try (InputStream in = con.getInputStream()) {
                if (handler == null) {
                    drain(in);
                    keepAlive = true;
                    return null;
                }
                T result = handler.parse(in);
                keepAlive = true;
                return result;
            }
        } catch (IOException e) {
            throw new SQLException("HTTP transport error: " + e.getMessage(), e);
        } finally {
            // Only disconnect on failures. On success the JDK's keep-alive cache reuses the TCP
            // socket for the next request to the same origin, eliminating the connect / TLS
            // handshake cost. (For loopback this is mainly a syscall-rate win -- before keep-alive,
            // throughput sat around 44 q/s for the full 25-oracle composite; after, ~3-4x higher.)
            if (con != null && !keepAlive) {
                con.disconnect();
            }
        }
    }

    private String buildQueryString() {
        StringBuilder sb = new StringBuilder();
        // database first so server-side error messages mention the right scope
        appendParam(sb, "database", database);
        for (Map.Entry<String, String> e : settings.entrySet()) {
            appendParam(sb, e.getKey(), e.getValue());
        }
        return sb.toString();
    }

    private static void appendParam(StringBuilder sb, String k, String v) {
        if (v == null) {
            return;
        }
        if (sb.length() > 0) {
            sb.append('&');
        }
        sb.append(URLEncoder.encode(k, StandardCharsets.UTF_8));
        sb.append('=');
        sb.append(URLEncoder.encode(v, StandardCharsets.UTF_8));
    }

    private static SQLException newServerException(int httpStatus, String body, String query) {
        // Preserve the ClickHouse error code in the SQLException's vendorCode so
        // ClickHouseErrors.errorIsExpected() can substring-match against the message text exactly
        // like the JDBC driver did.
        int vendorCode = 0;
        Matcher m = ERROR_CODE.matcher(body);
        if (m.find()) {
            try {
                vendorCode = Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {
                // leave vendorCode at 0
            }
        }
        // Keep the message close to JDBC's: "Code: 42. DB::Exception: ..." passes substring tests
        // ClickHouseErrors already uses (NUMBER_OF_ARGUMENTS_DOESNT_MATCH, ILLEGAL_DIVISION, etc.).
        return new SQLException(body.trim() + " [http " + httpStatus + ", query: "
                + (query.length() > 120 ? query.substring(0, 120) + "..." : query) + "]", null, vendorCode);
    }

    private static byte[] readAllBytes(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static void drain(InputStream in) throws IOException {
        byte[] buf = new byte[4096];
        while (in.read(buf) > 0) {
            // intentional
        }
    }

    private static String readErrorStream(HttpURLConnection con) {
        try (InputStream es = con.getErrorStream()) {
            if (es == null) {
                return "(no error stream)";
            }
            return new String(readAllBytes(es), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "(error stream read failed: " + e.getMessage() + ")";
        }
    }

    // ===== TSV split logic (TabSeparatedWithNamesAndTypes) ====================================

    private static List<String> splitTsvLines(String text) {
        // Rows are separated by literal '\n' (0x0A). Within a value, '\n' is encoded as the two
        // characters '\\' + 'n' -- so we walk byte by byte and only split on literal LF.
        List<String> out = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                out.add(text.substring(start, i));
                start = i + 1;
            }
        }
        if (start < text.length()) {
            out.add(text.substring(start));
        }
        return out;
    }

    private static List<String> splitTsvFields(String line) {
        // Fields separated by literal '\t' (0x09). '\t' inside a value is "\\t". We process
        // escapes after splitting because raw '\t' is a hard delimiter ClickHouse never emits
        // inside a value.
        List<String> out = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) == '\t') {
                out.add(unescapeTsv(line.substring(start, i)));
                start = i + 1;
            }
        }
        out.add(unescapeTsv(line.substring(start)));
        return out;
    }

    private static String unescapeTsv(String raw) {
        if (raw.equals("\\N")) {
            return null; // SQL NULL sentinel
        }
        if (raw.indexOf('\\') < 0) {
            return raw;
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\\' && i + 1 < raw.length()) {
                char next = raw.charAt(i + 1);
                switch (next) {
                case 't': sb.append('\t'); break;
                case 'n': sb.append('\n'); break;
                case 'r': sb.append('\r'); break;
                case '0': sb.append('\0'); break;
                case 'b': sb.append('\b'); break;
                case 'f': sb.append('\f'); break;
                case 'a': sb.append((char) 7); break;
                case 'v': sb.append((char) 11); break;
                case '\\': sb.append('\\'); break;
                case '\'': sb.append('\''); break;
                default: sb.append(next); break;
                }
                i++;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    // ===== misc ===============================================================================

    private static String trimTrailingSemicolon(String sql) {
        String s = sql.trim();
        while (s.endsWith(";")) {
            s = s.substring(0, s.length() - 1).trim();
        }
        return s;
    }

    private static final Pattern USE_PATTERN = Pattern.compile("^\\s*USE\\s+`?([\\w_]+)`?\\s*;?\\s*$",
            Pattern.CASE_INSENSITIVE);

    private static String matchUse(String sql) {
        Matcher m = USE_PATTERN.matcher(sql);
        return m.matches() ? m.group(1) : null;
    }
}
