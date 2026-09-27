package org.praxisplatform.uischema.bulk;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import javax.sql.DataSource;

/** Test-only barrier around the first physical commit on the selected worker thread. */
final class BulkCommitPauseDataSource implements DataSource {
    enum PausePoint { BEFORE_COMMIT, AFTER_COMMIT }

    private final DataSource delegate;
    private final PausePoint pausePoint;
    private final AtomicBoolean firstCommit = new AtomicBoolean(true);
    private final CountDownLatch markerCommitted = new CountDownLatch(1);
    private final CountDownLatch releaseWorker = new CountDownLatch(1);
    private volatile Thread worker;

    BulkCommitPauseDataSource(DataSource delegate) { this(delegate, PausePoint.AFTER_COMMIT); }
    BulkCommitPauseDataSource(DataSource delegate, PausePoint pausePoint) {
        this.delegate = delegate;
        this.pausePoint = pausePoint;
    }

    void arm(Thread worker) { this.worker = worker; }
    boolean awaitMarker(long timeout, TimeUnit unit) throws InterruptedException {
        return markerCommitted.await(timeout, unit);
    }
    void release() { releaseWorker.countDown(); }

    @Override public Connection getConnection() throws SQLException { return wrap(delegate.getConnection()); }
    @Override public Connection getConnection(String username, String password) throws SQLException {
        return wrap(delegate.getConnection(username, password));
    }

    private Connection wrap(Connection delegate) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "equals" -> proxy == args[0];
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "toString" -> "BulkCommitPauseConnection";
                            default -> throw new UnsupportedOperationException(method.getName());
                        };
                    }
                    boolean selectedCommit = "commit".equals(method.getName())
                            && Thread.currentThread() == worker
                            && firstCommit.compareAndSet(true, false);
                    if (selectedCommit && pausePoint == PausePoint.BEFORE_COMMIT) pause();
                    Object result;
                    try { result = method.invoke(delegate, args); }
                    catch (InvocationTargetException error) { throw error.getCause(); }
                    if (selectedCommit && pausePoint == PausePoint.AFTER_COMMIT) pause();
                    return result;
                });
    }

    private void pause() throws SQLException {
        markerCommitted.countDown();
        try {
            if (!releaseWorker.await(10, TimeUnit.SECONDS))
                throw new SQLException("test commit barrier timed out");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new SQLException("test commit barrier interrupted", error);
        }
    }

    @Override public PrintWriter getLogWriter() throws SQLException { return delegate.getLogWriter(); }
    @Override public void setLogWriter(PrintWriter out) throws SQLException { delegate.setLogWriter(out); }
    @Override public void setLoginTimeout(int seconds) throws SQLException { delegate.setLoginTimeout(seconds); }
    @Override public int getLoginTimeout() throws SQLException { return delegate.getLoginTimeout(); }
    @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return delegate.getParentLogger();
    }
    @Override public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) return iface.cast(this);
        return delegate.unwrap(iface);
    }
    @Override public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isInstance(this) || delegate.isWrapperFor(iface);
    }
}
