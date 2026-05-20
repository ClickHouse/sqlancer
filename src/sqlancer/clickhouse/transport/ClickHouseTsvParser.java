package sqlancer.clickhouse.transport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
final class ClickHouseTsvParser {

    private ClickHouseTsvParser() {
    }

    static ClickHouseTransport.ResultData parse(InputStream body) throws IOException {
        byte[] raw = readAllBytes(body);
        String text = new String(raw, StandardCharsets.UTF_8);
        if (text.isEmpty()) {
            return new ClickHouseTransport.ResultData(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        }
        List<String> lines = splitTsvLines(text);
        if (lines.size() < 2) {
            return new ClickHouseTransport.ResultData(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
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
        return new ClickHouseTransport.ResultData(names, types, rows);
    }

    static byte[] readAllBytes(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

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
