package sqlancer.clickhouse.transport;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import com.clickhouse.client.api.data_formats.RowBinaryWithNamesAndTypesFormatReader;
import com.clickhouse.client.api.data_formats.internal.BinaryStreamReader;
import com.clickhouse.client.api.metadata.TableSchema;
import com.clickhouse.client.api.query.QuerySettings;
import com.clickhouse.data.ClickHouseColumn;

// Parses ClickHouse's {@code RowBinaryWithNamesAndTypes} stream into a transport-agnostic
// {@link ClickHouseTransport.ResultData}. Replaces the hand-rolled TSV parser: client-v2's binary
// reader already implements deserialisers for every CH type we touch (Int*, UInt64 -> BigInteger,
// Float*, Decimal* -> BigDecimal, Date/Date32/DateTime/DateTime64, String, FixedString(N), UUID,
// Inet*, Nullable, LowCardinality, Array, Tuple, Map), so this file is a thin adapter rather than
// a parser.
//
// Why RowBinary, not TSV:
//   - TSV requires hand-rolled escape handling; the old parser dropped single-column empty-string
//     rows for years because of a misunderstanding of BufferedReader.readLine's trailing-newline
//     semantics. Binary has length-prefixed strings -- empty and NULL are structurally distinct.
//   - UInt64 round-trips as BigInteger via {@code getString(int)}; the TSV path was correct here
//     too (server-side text), but binary removes the in-flight UTF-8 decode entirely.
//   - DateTime values outside java.sql.Timestamp's Instant range render correctly because
//     {@code getString(int)} routes through CH's own text formatter rather than Java's Instant.
//
// The reader's {@code getString(int)} matches CH's TSV serialiser byte-for-byte: same shortest-
// round-trip Float64 representation, same `nan`/`inf` tokens, same `[a,b,c]` array rendering.
// Verified empirically on a probe table covering String, UInt64 above Long.MAX_VALUE, Decimal,
// Date32, DateTime64, Float Inf/NaN, Array(Int32), and NULL.
final class ClickHouseRowBinaryParser {

    static final String FORMAT = "RowBinaryWithNamesAndTypes";

    private ClickHouseRowBinaryParser() {
    }

    static ClickHouseTransport.ResultData parse(InputStream body) throws IOException {
        // The reader requires SOME timezone resolution: it routes DateTime values through a
        // ZoneId for textualisation. We don't care about TZ correctness for our use (we want the
        // raw text CH emits), but the reader refuses to construct without one. UTC is fine -- the
        // server-side `DateTime` values are textualised by the reader's own renderer using this
        // zone, and DateTime64 carries its own offset in the type metadata.
        QuerySettings qs = new QuerySettings().setUseTimeZone("UTC");
        RowBinaryWithNamesAndTypesFormatReader reader;
        try {
            reader = new RowBinaryWithNamesAndTypesFormatReader(body, qs,
                    new BinaryStreamReader.DefaultByteBufferAllocator());
        } catch (Exception e) {
            if (e instanceof IOException) {
                throw (IOException) e;
            }
            // Empty result (no header) on some DDL paths -- treat as empty rowset.
            return new ClickHouseTransport.ResultData(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        }

        TableSchema schema = reader.getSchema();
        List<ClickHouseColumn> cols = schema.getColumns();
        int n = cols.size();
        List<String> names = new ArrayList<>(n);
        List<String> types = new ArrayList<>(n);
        for (ClickHouseColumn c : cols) {
            names.add(c.getColumnName());
            types.add(c.getOriginalTypeName());
        }

        List<List<String>> rows = new ArrayList<>();
        // hasNext() advances internal state and decodes the next record; next() returns the row as a
        // Map, but we want positional String access so we walk getString(int) directly.
        while (reader.hasNext()) {
            reader.next();
            List<String> row = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                // Column indices on the reader are 1-based to match CH/JDBC convention.
                int idx = i + 1;
                if (reader.hasValue(idx)) {
                    row.add(reader.getString(idx));
                } else {
                    row.add(null);
                }
            }
            rows.add(row);
        }
        return new ClickHouseTransport.ResultData(names, types, rows);
    }

}
