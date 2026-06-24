package sqlancer.clickhouse.transport;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Properties;
import java.util.concurrent.Executor;

public final class ClickHouseTransportConnection implements Connection {

    private final ClickHouseTransport transport;
    private boolean closed;

    public ClickHouseTransportConnection(ClickHouseTransport transport) {
        this.transport = transport;
    }

    public ClickHouseTransport getTransport() {
        return transport;
    }

    @Override
    public Statement createStatement() {
        return new ClickHouseTransportStatement(this, null);
    }

    @Override
    public PreparedStatement prepareStatement(String sql) {
        return new ClickHouseTransportStatement(this, sql);
    }

    @Override
    public void close() {
        if (!closed) {
            transport.close();
            closed = true;
        }
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public DatabaseMetaData getMetaData() {

        return (DatabaseMetaData) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] { DatabaseMetaData.class }, (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.equals("getDatabaseProductVersion")) {
                        return transport.getServerVersion();
                    }
                    if (name.equals("getDatabaseProductName")) {
                        return "ClickHouse";
                    }
                    if (name.equals("getConnection")) {
                        return this;
                    }
                    if (name.equals("toString")) {
                        return "ClickHouseTransportDatabaseMetaData";
                    }
                    if (name.equals("equals")) {
                        return proxy == args[0];
                    }
                    if (name.equals("hashCode")) {
                        return System.identityHashCode(proxy);
                    }
                    throw new UnsupportedOperationException("DatabaseMetaData." + name + " not implemented");
                });
    }

    @Override
    public Statement createStatement(int rsType, int rsConcur) {
        return unsupported("createStatement(int,int)");
    }

    @Override
    public Statement createStatement(int rsType, int rsConcur, int rsHold) {
        return unsupported("createStatement(int,int,int)");
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int autoKeys) {
        return unsupported("prepareStatement(String,int)");
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int[] keys) {
        return unsupported("prepareStatement(String,int[])");
    }

    @Override
    public PreparedStatement prepareStatement(String sql, String[] keys) {
        return unsupported("prepareStatement(String,String[])");
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int t, int c) {
        return unsupported("prepareStatement(String,int,int)");
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int t, int c, int h) {
        return unsupported("prepareStatement(String,int,int,int)");
    }

    @Override
    public java.sql.CallableStatement prepareCall(String sql) {
        return unsupported("prepareCall");
    }

    @Override
    public java.sql.CallableStatement prepareCall(String sql, int t, int c) {
        return unsupported("prepareCall");
    }

    @Override
    public java.sql.CallableStatement prepareCall(String sql, int t, int c, int h) {
        return unsupported("prepareCall");
    }

    @Override
    public String nativeSQL(String sql) {
        return sql;
    }

    @Override
    public void setAutoCommit(boolean a) {
         }

    @Override
    public boolean getAutoCommit() {
        return true;
    }

    @Override
    public void commit() {
         }

    @Override
    public void rollback() {
        unsupported("rollback");
    }

    @Override
    public void rollback(java.sql.Savepoint s) {
        unsupported("rollback(Savepoint)");
    }

    @Override
    public java.sql.Savepoint setSavepoint() {
        return unsupported("setSavepoint");
    }

    @Override
    public java.sql.Savepoint setSavepoint(String n) {
        return unsupported("setSavepoint(String)");
    }

    @Override
    public void releaseSavepoint(java.sql.Savepoint s) {
        unsupported("releaseSavepoint");
    }

    @Override
    public void setReadOnly(boolean r) {
         }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public void setCatalog(String c) {
         }

    @Override
    public String getCatalog() {
        return null;
    }

    @Override
    public void setTransactionIsolation(int level) {
         }

    @Override
    public int getTransactionIsolation() {
        return Connection.TRANSACTION_NONE;
    }

    @Override
    public java.sql.SQLWarning getWarnings() {
        return null;
    }

    @Override
    public void clearWarnings() {
         }

    @Override
    public java.util.Map<String, Class<?>> getTypeMap() {
        return java.util.Collections.emptyMap();
    }

    @Override
    public void setTypeMap(java.util.Map<String, Class<?>> m) {
         }

    @Override
    public void setHoldability(int h) {
         }

    @Override
    public int getHoldability() {
        return java.sql.ResultSet.CLOSE_CURSORS_AT_COMMIT;
    }

    @Override
    public java.sql.Clob createClob() {
        return unsupported("createClob");
    }

    @Override
    public java.sql.Blob createBlob() {
        return unsupported("createBlob");
    }

    @Override
    public java.sql.NClob createNClob() {
        return unsupported("createNClob");
    }

    @Override
    public java.sql.SQLXML createSQLXML() {
        return unsupported("createSQLXML");
    }

    @Override
    public boolean isValid(int timeout) {
        return !closed;
    }

    @Override
    public void setClientInfo(String n, String v) {
         }

    @Override
    public void setClientInfo(Properties p) {
         }

    @Override
    public String getClientInfo(String n) {
        return null;
    }

    @Override
    public Properties getClientInfo() {
        return new Properties();
    }

    @Override
    public java.sql.Array createArrayOf(String t, Object[] e) {
        return unsupported("createArrayOf");
    }

    @Override
    public java.sql.Struct createStruct(String t, Object[] a) {
        return unsupported("createStruct");
    }

    @Override
    public void setSchema(String s) {
         }

    @Override
    public String getSchema() {
        return null;
    }

    @Override
    public void abort(Executor e) {
        close();
    }

    @Override
    public void setNetworkTimeout(Executor e, int ms) {
         }

    @Override
    public int getNetworkTimeout() {
        return 0;
    }

    @Override
    public <T> T unwrap(Class<T> iface) {
        return unsupported("unwrap");
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return false;
    }

    private static <T> T unsupported(String op) {
        throw new UnsupportedOperationException("ClickHouseTransportConnection: " + op + " not implemented");
    }
}
