package sqlancer.clickhouse.transport;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * Materialised {@link ResultSet} on top of {@link ClickHouseTransport.ResultData}.
 *
 * <p>Only the methods sqlancer exercises are implemented; the rest throw to make accidental
 * coverage gaps loud rather than silent. Values are stored as the textual rendering produced by
 * client-v2's {@code RowBinaryWithNamesAndTypesFormatReader#getString(int)}, which matches CH's
 * server-side text formatter byte-for-byte. Numeric getters parse on demand. This sidesteps
 * clickhouse-jdbc 0.9.8's UInt64-into-{@code long} overflow bug we documented in the 2026-05-19
 * run (PQS oracle was crashing on legitimate values above {@code Long.MAX_VALUE}).
 */
final class ClickHouseTransportResultSet implements ResultSet {

    private final ClickHouseTransportStatement statement;
    private final ClickHouseTransport.ResultData data;
    private int cursor = -1; // before first row
    private boolean closed;
    private boolean lastWasNull;

    ClickHouseTransportResultSet(ClickHouseTransportStatement statement, ClickHouseTransport.ResultData data) {
        this.statement = statement;
        this.data = data;
    }

    @Override
    public boolean next() {
        if (closed) {
            return false;
        }
        cursor++;
        return cursor < data.rows.size();
    }

    @Override
    public void close() {
        closed = true;
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public boolean wasNull() {
        return lastWasNull;
    }

    @Override
    public String getString(int columnIndex) throws SQLException {
        String raw = cell(columnIndex);
        lastWasNull = raw == null;
        return raw;
    }

    @Override
    public String getString(String columnLabel) throws SQLException {
        return getString(findColumn(columnLabel));
    }

    @Override
    public int getInt(int columnIndex) throws SQLException {
        String s = getString(columnIndex);
        if (s == null) {
            return 0;
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            // Some integer-shaped columns come back as decimals ("42.0") under certain
            // aggregate settings. Tolerate by routing through Double.
            return (int) Double.parseDouble(s);
        }
    }

    @Override
    public int getInt(String columnLabel) throws SQLException {
        return getInt(findColumn(columnLabel));
    }

    @Override
    public long getLong(int columnIndex) throws SQLException {
        String s = getString(columnIndex);
        if (s == null) {
            return 0L;
        }
        // Avoid clickhouse-jdbc's UInt64-overflow trap by clamping any UInt64 value > Long.MAX_VALUE
        // to Long.MAX_VALUE rather than throwing. sqlancer's PQS oracle treats the long as a logical
        // identifier, not arithmetic.
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            java.math.BigInteger bi = new java.math.BigInteger(s);
            if (bi.compareTo(java.math.BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
                return Long.MAX_VALUE;
            }
            if (bi.compareTo(java.math.BigInteger.valueOf(Long.MIN_VALUE)) < 0) {
                return Long.MIN_VALUE;
            }
            return bi.longValueExact();
        }
    }

    @Override
    public long getLong(String columnLabel) throws SQLException {
        return getLong(findColumn(columnLabel));
    }

    @Override
    public boolean getBoolean(int columnIndex) throws SQLException {
        String s = getString(columnIndex);
        if (s == null) {
            return false;
        }
        return s.equals("1") || s.equalsIgnoreCase("true");
    }

    @Override
    public boolean getBoolean(String columnLabel) throws SQLException {
        return getBoolean(findColumn(columnLabel));
    }

    @Override
    public int findColumn(String columnLabel) throws SQLException {
        for (int i = 0; i < data.columnNames.size(); i++) {
            if (data.columnNames.get(i).equals(columnLabel)) {
                return i + 1; // 1-based per JDBC convention
            }
        }
        throw new SQLException("Column '" + columnLabel + "' not found in result set");
    }

    @Override
    public ResultSetMetaData getMetaData() {
        return new ClickHouseTransportResultSetMetaData(data);
    }

    @Override
    public Statement getStatement() {
        return statement;
    }

    private String cell(int columnIndex) throws SQLException {
        if (cursor < 0 || cursor >= data.rows.size()) {
            throw new SQLException("Cursor not on a row");
        }
        List<String> row = data.rows.get(cursor);
        if (columnIndex < 1 || columnIndex > row.size()) {
            throw new SQLException("Column index " + columnIndex + " out of range [1.." + row.size() + "]");
        }
        return row.get(columnIndex - 1);
    }

    // ---- minimal ResultSetMetaData ----------------------------------------------------------

    private static final class ClickHouseTransportResultSetMetaData implements ResultSetMetaData {
        private final ClickHouseTransport.ResultData data;

        ClickHouseTransportResultSetMetaData(ClickHouseTransport.ResultData data) {
            this.data = data;
        }

        @Override public int getColumnCount() { return data.columnNames.size(); }
        @Override public String getColumnName(int column) { return data.columnNames.get(column - 1); }
        @Override public String getColumnLabel(int column) { return data.columnNames.get(column - 1); }
        @Override public String getColumnTypeName(int column) { return data.columnTypes.get(column - 1); }

        // ---- the rest: throw, since sqlancer doesn't read them ----
        @Override public boolean isAutoIncrement(int c) { return false; }
        @Override public boolean isCaseSensitive(int c) { return true; }
        @Override public boolean isSearchable(int c) { return true; }
        @Override public boolean isCurrency(int c) { return false; }
        @Override public int isNullable(int c) { return columnNullableUnknown; }
        @Override public boolean isSigned(int c) { return true; }
        @Override public int getColumnDisplaySize(int c) { return 32; }
        @Override public String getSchemaName(int c) { return ""; }
        @Override public int getPrecision(int c) { return 0; }
        @Override public int getScale(int c) { return 0; }
        @Override public String getTableName(int c) { return ""; }
        @Override public String getCatalogName(int c) { return ""; }
        @Override public int getColumnType(int c) { return java.sql.Types.VARCHAR; }
        @Override public boolean isReadOnly(int c) { return true; }
        @Override public boolean isWritable(int c) { return false; }
        @Override public boolean isDefinitelyWritable(int c) { return false; }
        @Override public String getColumnClassName(int c) { return String.class.getName(); }
        @Override public <T> T unwrap(Class<T> i) { throw new UnsupportedOperationException(); }
        @Override public boolean isWrapperFor(Class<?> i) { return false; }
    }

    // ---- everything else: throw -------------------------------------------------------------

    @Override public byte getByte(int c) { return unsupportedB("getByte"); }
    @Override public short getShort(int c) { return unsupportedS("getShort"); }
    @Override public float getFloat(int c) throws SQLException {
        String s = getString(c);
        return s == null ? 0f : Float.parseFloat(s);
    }
    @Override public double getDouble(int c) throws SQLException {
        String s = getString(c);
        return s == null ? 0d : Double.parseDouble(s);
    }
    @Override public java.math.BigDecimal getBigDecimal(int c, int s) { return unsupported("getBigDecimal"); }
    @Override public byte[] getBytes(int c) { return unsupported("getBytes"); }
    @Override public java.sql.Date getDate(int c) { return unsupported("getDate"); }
    @Override public java.sql.Time getTime(int c) { return unsupported("getTime"); }
    @Override public java.sql.Timestamp getTimestamp(int c) { return unsupported("getTimestamp"); }
    @Override public java.io.InputStream getAsciiStream(int c) { return unsupported("getAsciiStream"); }
    @Override public java.io.InputStream getUnicodeStream(int c) { return unsupported("getUnicodeStream"); }
    @Override public java.io.InputStream getBinaryStream(int c) { return unsupported("getBinaryStream"); }
    @Override public byte getByte(String c) { return unsupportedB("getByte"); }
    @Override public short getShort(String c) { return unsupportedS("getShort"); }
    @Override public float getFloat(String c) throws SQLException { return getFloat(findColumn(c)); }
    @Override public double getDouble(String c) throws SQLException { return getDouble(findColumn(c)); }
    @Override public java.math.BigDecimal getBigDecimal(String c, int s) { return unsupported("getBigDecimal"); }
    @Override public byte[] getBytes(String c) { return unsupported("getBytes"); }
    @Override public java.sql.Date getDate(String c) { return unsupported("getDate"); }
    @Override public java.sql.Time getTime(String c) { return unsupported("getTime"); }
    @Override public java.sql.Timestamp getTimestamp(String c) { return unsupported("getTimestamp"); }
    @Override public java.io.InputStream getAsciiStream(String c) { return unsupported("getAsciiStream"); }
    @Override public java.io.InputStream getUnicodeStream(String c) { return unsupported("getUnicodeStream"); }
    @Override public java.io.InputStream getBinaryStream(String c) { return unsupported("getBinaryStream"); }
    @Override public java.sql.SQLWarning getWarnings() { return null; }
    @Override public void clearWarnings() { /* no-op */ }
    @Override public String getCursorName() { return null; }
    @Override public Object getObject(int c) throws SQLException { return getString(c); }
    @Override public Object getObject(String c) throws SQLException { return getString(c); }
    @Override public java.io.Reader getCharacterStream(int c) { return unsupported("getCharacterStream"); }
    @Override public java.io.Reader getCharacterStream(String c) { return unsupported("getCharacterStream"); }
    @Override public java.math.BigDecimal getBigDecimal(int c) throws SQLException {
        String s = getString(c);
        return s == null ? null : new java.math.BigDecimal(s);
    }
    @Override public java.math.BigDecimal getBigDecimal(String c) throws SQLException { return getBigDecimal(findColumn(c)); }
    @Override public boolean isBeforeFirst() { return cursor < 0; }
    @Override public boolean isAfterLast() { return cursor >= data.rows.size(); }
    @Override public boolean isFirst() { return cursor == 0; }
    @Override public boolean isLast() { return cursor == data.rows.size() - 1; }
    @Override public void beforeFirst() { cursor = -1; }
    @Override public void afterLast() { cursor = data.rows.size(); }
    @Override public boolean first() { cursor = 0; return !data.rows.isEmpty(); }
    @Override public boolean last() { cursor = data.rows.size() - 1; return !data.rows.isEmpty(); }
    @Override public int getRow() { return cursor < 0 ? 0 : cursor + 1; }
    @Override public boolean absolute(int row) { cursor = row - 1; return cursor >= 0 && cursor < data.rows.size(); }
    @Override public boolean relative(int rows) { cursor += rows; return cursor >= 0 && cursor < data.rows.size(); }
    @Override public boolean previous() { cursor = Math.max(-1, cursor - 1); return cursor >= 0; }
    @Override public void setFetchDirection(int d) { /* no-op */ }
    @Override public int getFetchDirection() { return FETCH_FORWARD; }
    @Override public void setFetchSize(int rows) { /* no-op */ }
    @Override public int getFetchSize() { return 0; }
    @Override public int getType() { return TYPE_FORWARD_ONLY; }
    @Override public int getConcurrency() { return CONCUR_READ_ONLY; }
    @Override public boolean rowUpdated() { return false; }
    @Override public boolean rowInserted() { return false; }
    @Override public boolean rowDeleted() { return false; }
    @Override public void updateNull(int c) { unsupportedV("updateNull"); }
    @Override public void updateBoolean(int c, boolean v) { unsupportedV("updateBoolean"); }
    @Override public void updateByte(int c, byte v) { unsupportedV("updateByte"); }
    @Override public void updateShort(int c, short v) { unsupportedV("updateShort"); }
    @Override public void updateInt(int c, int v) { unsupportedV("updateInt"); }
    @Override public void updateLong(int c, long v) { unsupportedV("updateLong"); }
    @Override public void updateFloat(int c, float v) { unsupportedV("updateFloat"); }
    @Override public void updateDouble(int c, double v) { unsupportedV("updateDouble"); }
    @Override public void updateBigDecimal(int c, java.math.BigDecimal v) { unsupportedV("updateBigDecimal"); }
    @Override public void updateString(int c, String v) { unsupportedV("updateString"); }
    @Override public void updateBytes(int c, byte[] v) { unsupportedV("updateBytes"); }
    @Override public void updateDate(int c, java.sql.Date v) { unsupportedV("updateDate"); }
    @Override public void updateTime(int c, java.sql.Time v) { unsupportedV("updateTime"); }
    @Override public void updateTimestamp(int c, java.sql.Timestamp v) { unsupportedV("updateTimestamp"); }
    @Override public void updateAsciiStream(int c, java.io.InputStream v, int l) { unsupportedV("updateAsciiStream"); }
    @Override public void updateBinaryStream(int c, java.io.InputStream v, int l) { unsupportedV("updateBinaryStream"); }
    @Override public void updateCharacterStream(int c, java.io.Reader v, int l) { unsupportedV("updateCharacterStream"); }
    @Override public void updateObject(int c, Object v, int s) { unsupportedV("updateObject"); }
    @Override public void updateObject(int c, Object v) { unsupportedV("updateObject"); }
    @Override public void updateNull(String c) { unsupportedV("updateNull"); }
    @Override public void updateBoolean(String c, boolean v) { unsupportedV("updateBoolean"); }
    @Override public void updateByte(String c, byte v) { unsupportedV("updateByte"); }
    @Override public void updateShort(String c, short v) { unsupportedV("updateShort"); }
    @Override public void updateInt(String c, int v) { unsupportedV("updateInt"); }
    @Override public void updateLong(String c, long v) { unsupportedV("updateLong"); }
    @Override public void updateFloat(String c, float v) { unsupportedV("updateFloat"); }
    @Override public void updateDouble(String c, double v) { unsupportedV("updateDouble"); }
    @Override public void updateBigDecimal(String c, java.math.BigDecimal v) { unsupportedV("updateBigDecimal"); }
    @Override public void updateString(String c, String v) { unsupportedV("updateString"); }
    @Override public void updateBytes(String c, byte[] v) { unsupportedV("updateBytes"); }
    @Override public void updateDate(String c, java.sql.Date v) { unsupportedV("updateDate"); }
    @Override public void updateTime(String c, java.sql.Time v) { unsupportedV("updateTime"); }
    @Override public void updateTimestamp(String c, java.sql.Timestamp v) { unsupportedV("updateTimestamp"); }
    @Override public void updateAsciiStream(String c, java.io.InputStream v, int l) { unsupportedV("updateAsciiStream"); }
    @Override public void updateBinaryStream(String c, java.io.InputStream v, int l) { unsupportedV("updateBinaryStream"); }
    @Override public void updateCharacterStream(String c, java.io.Reader v, int l) { unsupportedV("updateCharacterStream"); }
    @Override public void updateObject(String c, Object v, int s) { unsupportedV("updateObject"); }
    @Override public void updateObject(String c, Object v) { unsupportedV("updateObject"); }
    @Override public void insertRow() { unsupportedV("insertRow"); }
    @Override public void updateRow() { unsupportedV("updateRow"); }
    @Override public void deleteRow() { unsupportedV("deleteRow"); }
    @Override public void refreshRow() { unsupportedV("refreshRow"); }
    @Override public void cancelRowUpdates() { unsupportedV("cancelRowUpdates"); }
    @Override public void moveToInsertRow() { unsupportedV("moveToInsertRow"); }
    @Override public void moveToCurrentRow() { unsupportedV("moveToCurrentRow"); }
    @Override public Object getObject(int c, java.util.Map<String, Class<?>> map) { return unsupported("getObject(map)"); }
    @Override public java.sql.Ref getRef(int c) { return unsupported("getRef"); }
    @Override public java.sql.Blob getBlob(int c) { return unsupported("getBlob"); }
    @Override public java.sql.Clob getClob(int c) { return unsupported("getClob"); }
    @Override public java.sql.Array getArray(int c) { return unsupported("getArray"); }
    @Override public Object getObject(String c, java.util.Map<String, Class<?>> map) { return unsupported("getObject(map)"); }
    @Override public java.sql.Ref getRef(String c) { return unsupported("getRef"); }
    @Override public java.sql.Blob getBlob(String c) { return unsupported("getBlob"); }
    @Override public java.sql.Clob getClob(String c) { return unsupported("getClob"); }
    @Override public java.sql.Array getArray(String c) { return unsupported("getArray"); }
    @Override public java.sql.Date getDate(int c, java.util.Calendar cal) { return unsupported("getDate(cal)"); }
    @Override public java.sql.Date getDate(String c, java.util.Calendar cal) { return unsupported("getDate(cal)"); }
    @Override public java.sql.Time getTime(int c, java.util.Calendar cal) { return unsupported("getTime(cal)"); }
    @Override public java.sql.Time getTime(String c, java.util.Calendar cal) { return unsupported("getTime(cal)"); }
    @Override public java.sql.Timestamp getTimestamp(int c, java.util.Calendar cal) { return unsupported("getTimestamp(cal)"); }
    @Override public java.sql.Timestamp getTimestamp(String c, java.util.Calendar cal) { return unsupported("getTimestamp(cal)"); }
    @Override public java.net.URL getURL(int c) { return unsupported("getURL"); }
    @Override public java.net.URL getURL(String c) { return unsupported("getURL"); }
    @Override public void updateRef(int c, java.sql.Ref v) { unsupportedV("updateRef"); }
    @Override public void updateRef(String c, java.sql.Ref v) { unsupportedV("updateRef"); }
    @Override public void updateBlob(int c, java.sql.Blob v) { unsupportedV("updateBlob"); }
    @Override public void updateBlob(String c, java.sql.Blob v) { unsupportedV("updateBlob"); }
    @Override public void updateClob(int c, java.sql.Clob v) { unsupportedV("updateClob"); }
    @Override public void updateClob(String c, java.sql.Clob v) { unsupportedV("updateClob"); }
    @Override public void updateArray(int c, java.sql.Array v) { unsupportedV("updateArray"); }
    @Override public void updateArray(String c, java.sql.Array v) { unsupportedV("updateArray"); }
    @Override public java.sql.RowId getRowId(int c) { return unsupported("getRowId"); }
    @Override public java.sql.RowId getRowId(String c) { return unsupported("getRowId"); }
    @Override public void updateRowId(int c, java.sql.RowId v) { unsupportedV("updateRowId"); }
    @Override public void updateRowId(String c, java.sql.RowId v) { unsupportedV("updateRowId"); }
    @Override public int getHoldability() { return CLOSE_CURSORS_AT_COMMIT; }
    @Override public void updateNString(int c, String v) { unsupportedV("updateNString"); }
    @Override public void updateNString(String c, String v) { unsupportedV("updateNString"); }
    @Override public void updateNClob(int c, java.sql.NClob v) { unsupportedV("updateNClob"); }
    @Override public void updateNClob(String c, java.sql.NClob v) { unsupportedV("updateNClob"); }
    @Override public java.sql.NClob getNClob(int c) { return unsupported("getNClob"); }
    @Override public java.sql.NClob getNClob(String c) { return unsupported("getNClob"); }
    @Override public java.sql.SQLXML getSQLXML(int c) { return unsupported("getSQLXML"); }
    @Override public java.sql.SQLXML getSQLXML(String c) { return unsupported("getSQLXML"); }
    @Override public void updateSQLXML(int c, java.sql.SQLXML v) { unsupportedV("updateSQLXML"); }
    @Override public void updateSQLXML(String c, java.sql.SQLXML v) { unsupportedV("updateSQLXML"); }
    @Override public String getNString(int c) throws SQLException { return getString(c); }
    @Override public String getNString(String c) throws SQLException { return getString(c); }
    @Override public java.io.Reader getNCharacterStream(int c) { return unsupported("getNCharacterStream"); }
    @Override public java.io.Reader getNCharacterStream(String c) { return unsupported("getNCharacterStream"); }
    @Override public void updateNCharacterStream(int c, java.io.Reader v, long l) { unsupportedV("updateNCharacterStream"); }
    @Override public void updateNCharacterStream(String c, java.io.Reader v, long l) { unsupportedV("updateNCharacterStream"); }
    @Override public void updateAsciiStream(int c, java.io.InputStream v, long l) { unsupportedV("updateAsciiStream"); }
    @Override public void updateBinaryStream(int c, java.io.InputStream v, long l) { unsupportedV("updateBinaryStream"); }
    @Override public void updateCharacterStream(int c, java.io.Reader v, long l) { unsupportedV("updateCharacterStream"); }
    @Override public void updateAsciiStream(String c, java.io.InputStream v, long l) { unsupportedV("updateAsciiStream"); }
    @Override public void updateBinaryStream(String c, java.io.InputStream v, long l) { unsupportedV("updateBinaryStream"); }
    @Override public void updateCharacterStream(String c, java.io.Reader v, long l) { unsupportedV("updateCharacterStream"); }
    @Override public void updateBlob(int c, java.io.InputStream v, long l) { unsupportedV("updateBlob"); }
    @Override public void updateBlob(String c, java.io.InputStream v, long l) { unsupportedV("updateBlob"); }
    @Override public void updateClob(int c, java.io.Reader v, long l) { unsupportedV("updateClob"); }
    @Override public void updateClob(String c, java.io.Reader v, long l) { unsupportedV("updateClob"); }
    @Override public void updateNClob(int c, java.io.Reader v, long l) { unsupportedV("updateNClob"); }
    @Override public void updateNClob(String c, java.io.Reader v, long l) { unsupportedV("updateNClob"); }
    @Override public void updateNCharacterStream(int c, java.io.Reader v) { unsupportedV("updateNCharacterStream"); }
    @Override public void updateNCharacterStream(String c, java.io.Reader v) { unsupportedV("updateNCharacterStream"); }
    @Override public void updateAsciiStream(int c, java.io.InputStream v) { unsupportedV("updateAsciiStream"); }
    @Override public void updateBinaryStream(int c, java.io.InputStream v) { unsupportedV("updateBinaryStream"); }
    @Override public void updateCharacterStream(int c, java.io.Reader v) { unsupportedV("updateCharacterStream"); }
    @Override public void updateAsciiStream(String c, java.io.InputStream v) { unsupportedV("updateAsciiStream"); }
    @Override public void updateBinaryStream(String c, java.io.InputStream v) { unsupportedV("updateBinaryStream"); }
    @Override public void updateCharacterStream(String c, java.io.Reader v) { unsupportedV("updateCharacterStream"); }
    @Override public void updateBlob(int c, java.io.InputStream v) { unsupportedV("updateBlob"); }
    @Override public void updateBlob(String c, java.io.InputStream v) { unsupportedV("updateBlob"); }
    @Override public void updateClob(int c, java.io.Reader v) { unsupportedV("updateClob"); }
    @Override public void updateClob(String c, java.io.Reader v) { unsupportedV("updateClob"); }
    @Override public void updateNClob(int c, java.io.Reader v) { unsupportedV("updateNClob"); }
    @Override public void updateNClob(String c, java.io.Reader v) { unsupportedV("updateNClob"); }
    @Override public <T> T getObject(int c, Class<T> t) throws SQLException {
        if (t == String.class) return t.cast(getString(c));
        if (t == Long.class) return t.cast(getLong(c));
        if (t == Integer.class) return t.cast(getInt(c));
        return unsupported("getObject(Class)");
    }
    @Override public <T> T getObject(String c, Class<T> t) throws SQLException { return getObject(findColumn(c), t); }
    @Override public <T> T unwrap(Class<T> iface) { return unsupported("unwrap"); }
    @Override public boolean isWrapperFor(Class<?> iface) { return false; }

    private static <T> T unsupported(String op) {
        throw new UnsupportedOperationException("ClickHouseTransportResultSet: " + op + " not implemented");
    }
    private static void unsupportedV(String op) { unsupported(op); }
    private static byte unsupportedB(String op) { unsupported(op); return 0; }
    private static short unsupportedS(String op) { unsupported(op); return 0; }
    private static float unsupportedF(String op) { unsupported(op); return 0; }
    private static double unsupportedD(String op) { unsupported(op); return 0; }
}
