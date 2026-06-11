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

final class ClickHouseRowBinaryParser {

    static final String FORMAT = "RowBinaryWithNamesAndTypes";

    private ClickHouseRowBinaryParser() {
    }

    static ClickHouseTransport.ResultData parse(InputStream body) throws IOException {

        QuerySettings qs = new QuerySettings().setUseTimeZone("UTC");
        RowBinaryWithNamesAndTypesFormatReader reader;
        try {
            reader = new RowBinaryWithNamesAndTypesFormatReader(body, qs,
                    new BinaryStreamReader.DefaultByteBufferAllocator());
        } catch (Exception e) {
            if (e instanceof IOException) {
                throw (IOException) e;
            }

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

        while (reader.hasNext()) {
            reader.next();
            List<String> row = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {

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
