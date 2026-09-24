package org.praxisplatform.uischema.bulk;

import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * Test-only direct datasource wrapper for one deterministic commit outcome on one chosen thread.
 *
 * <p>{@link Mode#COMMIT_THEN_ACK_LOST} commits to PostgreSQL and then reports a synthetic
 * connection failure, modelling a lost commit acknowledgement. It is not a process-kill
 * simulation. {@link Mode#ROLLBACK_THEN_FAIL} physically rolls back before reporting the error,
 * so transaction-manager cleanup cannot accidentally confirm pending work by restoring
 * auto-commit.</p>
 */
final class BulkCommitFaultDataSource implements DataSource {
    enum Mode {
        COMMIT_THEN_ACK_LOST,
        ROLLBACK_THEN_FAIL
    }

    private final DataSource delegate;
    private final AtomicReference<ArmedFault> armedFault = new AtomicReference<>();

    BulkCommitFaultDataSource(DataSource delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    /** Arms exactly one subsequent physical commit attempt made by {@code thread}. */
    void arm(Thread thread, Mode mode) {
        Objects.requireNonNull(thread, "thread");
        Objects.requireNonNull(mode, "mode");
        if (!armedFault.compareAndSet(null, new ArmedFault(thread, mode))) {
            throw new IllegalStateException("A bulk commit fault is already armed");
        }
    }

    boolean isArmed() {
        return armedFault.get() != null;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return wrap(delegate.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return wrap(delegate.getConnection(username, password));
    }

    private Connection wrap(Connection connection) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[] { Connection.class },
                (proxy, method, arguments) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "equals" -> proxy == arguments[0];
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "toString" -> "BulkCommitFaultConnection";
                            default -> throw new UnsupportedOperationException(method.getName());
                        };
                    }
                    if (!"commit".equals(method.getName()) || method.getParameterCount() != 0) {
                        return invoke(connection, method, arguments);
                    }
                    ArmedFault current = armedFault.get();
                    if (current == null || current.thread() != Thread.currentThread()
                            || !armedFault.compareAndSet(current, null)) {
                        return invoke(connection, method, arguments);
                    }
                    if (current.mode() == Mode.COMMIT_THEN_ACK_LOST) {
                        connection.commit();
                        throw fault("commit acknowledgement was lost after PostgreSQL committed");
                    }
                    connection.rollback();
                    throw fault("PostgreSQL rolled back before the injected commit failure");
                });
    }

    private static Object invoke(Connection connection, java.lang.reflect.Method method, Object[] arguments)
            throws Throwable {
        try {
            return method.invoke(connection, arguments);
        } catch (InvocationTargetException error) {
            throw error.getCause();
        }
    }

    private static SQLException fault(String message) {
        return new SQLException(message, "08006");
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return delegate.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        delegate.setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        delegate.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return delegate.getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return delegate.getParentLogger();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        return delegate.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isInstance(this) || delegate.isWrapperFor(iface);
    }

    private record ArmedFault(Thread thread, Mode mode) { }
}
