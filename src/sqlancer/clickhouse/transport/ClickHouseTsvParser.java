package sqlancer.clickhouse.transport;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

// Parses ClickHouse's `TabSeparatedWithNamesAndTypes` output into a transport-agnostic
// {@link ClickHouseTransport.ResultData}. Shared by every transport that requests TSV; concrete
// transports (raw HTTP, client-v2) differ only in how they obtain the InputStream.
//
// Wire format:
//   line 1: column names (TAB-separated)
//   line 2: column types (TAB-separated)
//   line 3..: rows
//   TSV escaping: \t \n \\ \\N (the last is the literal SQL NULL token, distinct from
//   an empty cell).
//
// Parsing is streaming: we read one line at a time via BufferedReader rather than materialising
// the whole response body into a single byte[]+String. The previous materialise-then-split
// approach hit `ByteArrayOutputStream`'s `Integer.MAX_VALUE - 8` array-length limit on result
// sets > 2 GiB (12 of 38 reproducer trips in the 2026-05-21 3-h baseline). Streaming bounds the
// transient buffer to BufferedReader's default (8 KB) regardless of total response size; the
// only memory still proportional to the result is the materialised `List<List<String>>` that
// the oracles consume, which fits in the 8 GiB heap for everything sqlancer typically generates.
//
// TSV is safe to readLine() over: literal newlines and carriage returns are escaped as `\n` /
// `\r` (two-character sequences) inside values, so {@link BufferedReader#readLine} never splits
// inside a value -- only on the row delimiter.
final class ClickHouseTsvParser {

    private ClickHouseTsvParser() {
    }

    static ClickHouseTransport.ResultData parse(InputStream body) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
        String namesLine = reader.readLine();
        if (namesLine == null) {
            return new ClickHouseTransport.ResultData(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        }
        String typesLine = reader.readLine();
        if (typesLine == null) {
            // Header without a types line -- shouldn't happen for TabSeparatedWithNamesAndTypes,
            // but be defensive.
            return new ClickHouseTransport.ResultData(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        }
        List<String> names = splitTsvFields(namesLine);
        List<String> types = splitTsvFields(typesLine);
        List<List<String>> rows = new ArrayList<>();
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                // Trailing newline produces an empty tail entry; skip it.
                continue;
            }
            rows.add(splitTsvFields(line));
        }
        return new ClickHouseTransport.ResultData(names, types, rows);
    }

    // Used only by the error-stream path in ClickHouseHttpTransport -- error bodies are bounded
    // by ClickHouse's exception serialiser (a few KB) so the in-memory buffer is safe there.
    static byte[] readAllBytes(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
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
                case 't':
                    sb.append('\t');
                    break;
                case 'n':
                    sb.append('\n');
                    break;
                case 'r':
                    sb.append('\r');
                    break;
                case '0':
                    sb.append('\0');
                    break;
                case 'b':
                    sb.append('\b');
                    break;
                case 'f':
                    sb.append('\f');
                    break;
                case 'a':
                    sb.append((char) 7);
                    break;
                case 'v':
                    sb.append((char) 11);
                    break;
                case '\\':
                    sb.append('\\');
                    break;
                case '\'':
                    sb.append('\'');
                    break;
                default:
                    sb.append(next);
                    break;
                }
                i++;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    static final String FORMAT = "TabSeparatedWithNamesAndTypes";
}
