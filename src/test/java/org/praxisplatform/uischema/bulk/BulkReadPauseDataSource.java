package org.praxisplatform.uischema.bulk;

import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import javax.sql.DataSource;

/** Test barrier after the execution identity SELECT has fixed the reader's MVCC snapshot. */
final class BulkReadPauseDataSource implements DataSource {
    private final DataSource delegate;
    private final String sqlMarker;
    private final AtomicBoolean first = new AtomicBoolean(true);
    private final CountDownLatch observed = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private volatile Thread reader;

    BulkReadPauseDataSource(DataSource delegate) {
        this(delegate, "from praxis_bulk.praxis_bulk_execution e");
    }
    BulkReadPauseDataSource(DataSource delegate, String sqlMarker) {
        this.delegate = delegate;
        this.sqlMarker = sqlMarker;
    }
    void arm(Thread reader) { this.reader = reader; }
    boolean awaitObservation(long timeout, TimeUnit unit) throws InterruptedException {
        return observed.await(timeout, unit);
    }
    void release() { release.countDown(); }

    @Override public Connection getConnection() throws SQLException { return wrap(delegate.getConnection()); }
    @Override public Connection getConnection(String username, String password) throws SQLException {
        return wrap(delegate.getConnection(username, password));
    }

    private Connection wrap(Connection actual) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, arguments) -> {
                    if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method, arguments);
                    Object value = invoke(actual, method, arguments);
                    if ("prepareStatement".equals(method.getName()) && arguments != null
                            && arguments.length > 0 && arguments[0] instanceof String sql
                            && sql.contains(sqlMarker)
                            && (!sqlMarker.equals("from praxis_bulk.praxis_bulk_execution e")
                                || sql.contains("where e.execution_id=?"))) {
                        return wrapStatement((PreparedStatement) value);
                    }
                    return value;
                });
    }

    private PreparedStatement wrapStatement(PreparedStatement actual) {
        return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class}, (proxy, method, arguments) -> {
                    if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method, arguments);
                    Object value = invoke(actual, method, arguments);
                    if ("executeQuery".equals(method.getName()) && Thread.currentThread() == reader
                            && first.compareAndSet(true, false)) {
                        observed.countDown();
                        try {
                            if (!release.await(10, TimeUnit.SECONDS))
                                throw new SQLException("read snapshot barrier timed out");
                        } catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                            throw new SQLException("read snapshot barrier interrupted", error);
                        }
                    }
                    return value;
                });
    }

    private static Object invoke(Object target, Method method, Object[] arguments) throws Throwable {
        try { return method.invoke(target, arguments); }
        catch (InvocationTargetException error) { throw error.getCause(); }
    }

    private static Object objectMethod(Object proxy, Method method, Object[] arguments) {
        return switch (method.getName()) {
            case "equals" -> proxy == arguments[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> "BulkReadPauseProxy";
            default -> throw new UnsupportedOperationException(method.getName());
        };
    }

    @Override public PrintWriter getLogWriter() throws SQLException { return delegate.getLogWriter(); }
    @Override public void setLogWriter(PrintWriter out) throws SQLException { delegate.setLogWriter(out); }
    @Override public void setLoginTimeout(int seconds) throws SQLException { delegate.setLoginTimeout(seconds); }
    @Override public int getLoginTimeout() throws SQLException { return delegate.getLoginTimeout(); }
    @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return delegate.getParentLogger();
    }
    @Override public <T> T unwrap(Class<T> type) throws SQLException {
        if (type.isInstance(this)) return type.cast(this);
        return delegate.unwrap(type);
    }
    @Override public boolean isWrapperFor(Class<?> type) throws SQLException {
        return type.isInstance(this) || delegate.isWrapperFor(type);
    }
}
