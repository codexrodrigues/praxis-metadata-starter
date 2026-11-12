package org.praxisplatform.uischema.bulk;

import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

/**
 * Canonical single-pool PostgreSQL {@code search_path} operational {@link DataSource}.
 *
 * <p>Unlike {@link AbstractRoutingDataSource} (which switches physical databases/pools) or generic
 * {@link DelegatingDataSource} subclasses (which allow mutable rebinding, lazy checkout or per-call
 * credential switching), this {@code final} adapter binds immutably to one direct operational pool,
 * forbids per-call credential overrides and pool unwrapping, validates PostgreSQL schema
 * identifiers strictly, and enforces {@code search_path} isolation across {@code autoCommit} and
 * explicit transactions while preserving {@code ConnectionHolder} reference identity for
 * {@link BulkExecutionInfrastructure}.
 */
public final class SinglePoolSchemaRoutingDataSource implements DataSource {

    @FunctionalInterface
    public interface SchemaRouteResolver {
        SchemaRoute resolve() throws SQLException;
    }

    public record SchemaRoute(String primarySchema, String fallbackSchema, String resetSchema) {
        private static final SchemaRoute PASSTHROUGH = new SchemaRoute(null, null, null);

        public SchemaRoute {
            if (primarySchema != null) {
                validateIdentifierOrThrow(primarySchema, "primarySchema");
                validateIdentifierOrThrow(resetSchema, "resetSchema");
                if (fallbackSchema != null) {
                    validateIdentifierOrThrow(fallbackSchema, "fallbackSchema");
                }
            } else if (fallbackSchema != null || resetSchema != null) {
                throw new IllegalArgumentException("Passthrough SchemaRoute must not set fallbackSchema or resetSchema");
            }
        }

        public static SchemaRoute passthrough() {
            return PASSTHROUGH;
        }

        public static SchemaRoute of(String primarySchema, String fallbackSchema, String resetSchema) {
            return new SchemaRoute(
                    Objects.requireNonNull(primarySchema, "primarySchema"),
                    fallbackSchema,
                    Objects.requireNonNull(resetSchema, "resetSchema"));
        }

        public boolean isPassthrough() {
            return primarySchema == null;
        }

        String effectiveSearchPath() {
            if (fallbackSchema != null && !primarySchema.equals(fallbackSchema)) {
                return primarySchema + ", " + fallbackSchema;
            }
            return primarySchema;
        }

        private static void validateIdentifierOrThrow(String identifier, String fieldName) {
            if (!isSafeIdentifier(identifier)) {
                throw new IllegalArgumentException("Invalid PostgreSQL schema identifier for " + fieldName);
            }
        }
    }

    private final DataSource targetDataSource;
    private final SchemaRouteResolver routeResolver;
    private final Consumer<Connection> connectionInspector;

    public SinglePoolSchemaRoutingDataSource(DataSource targetDataSource, SchemaRouteResolver routeResolver) {
        this(targetDataSource, routeResolver, null);
    }

    public SinglePoolSchemaRoutingDataSource(
            DataSource targetDataSource,
            SchemaRouteResolver routeResolver,
            Consumer<Connection> connectionInspector) {
        Objects.requireNonNull(targetDataSource, "targetDataSource");
        Objects.requireNonNull(routeResolver, "routeResolver");
        if (targetDataSource instanceof AbstractRoutingDataSource
                || targetDataSource instanceof DelegatingDataSource
                || targetDataSource instanceof SinglePoolSchemaRoutingDataSource) {
            throw new IllegalArgumentException(
                    "Target operational datasource must be a direct physical pool without routing or wrappers");
        }
        this.targetDataSource = targetDataSource;
        this.routeResolver = routeResolver;
        this.connectionInspector = connectionInspector;
    }

    public static boolean isSafeIdentifier(String identifier) {
        return identifier != null
                && identifier.matches("[a-z_][a-z0-9_]*")
                && identifier.getBytes(StandardCharsets.UTF_8).length <= 63;
    }

    @Override
    public Connection getConnection() throws SQLException {
        SchemaRoute route = Objects.requireNonNull(routeResolver.resolve(), "SchemaRouteResolver returned null");
        Connection rawConnection = targetDataSource.getConnection();
        if (route.isPassthrough()) {
            return rawConnection;
        }
        return bindSearchPath(rawConnection, route);
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        throw new SQLFeatureNotSupportedException(
                "Per-call credential switching is forbidden on SinglePoolSchemaRoutingDataSource");
    }

    private Connection bindSearchPath(Connection rawConnection, SchemaRoute route) throws SQLException {
        String primarySchema = route.primarySchema();
        String resetPath = route.resetSchema();
        String effectiveSearchPath = route.effectiveSearchPath();
        try {
            if (connectionInspector != null) {
                connectionInspector.accept(rawConnection);
            }
            boolean autoCommit = rawConnection.getAutoCommit();
            applySearchPath(rawConnection, effectiveSearchPath, !autoCommit);
        } catch (SQLException | RuntimeException | Error failure) {
            closeQuietly(rawConnection);
            throw failure;
        }

        AtomicBoolean closed = new AtomicBoolean(false);
        return (Connection) Proxy.newProxyInstance(
                SinglePoolSchemaRoutingDataSource.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("close".equals(name) && (args == null || args.length == 0)) {
                        if (closed.compareAndSet(false, true)) {
                            try {
                                if (!rawConnection.isClosed()) {
                                    if (!rawConnection.getAutoCommit()) {
                                        try {
                                            rawConnection.rollback();
                                        } catch (SQLException ignored) {
                                            // Defensive rollback of uncommitted state on return to pool.
                                        }
                                        try {
                                            rawConnection.setAutoCommit(true);
                                        } catch (SQLException ignored) {
                                            // Pool will evict broken physical handles.
                                        }
                                    }
                                    if (rawConnection.getAutoCommit()) {
                                        applySearchPath(rawConnection, resetPath, false);
                                    }
                                }
                            } catch (SQLException ignored) {
                                // Pool will evict broken physical handles.
                            } finally {
                                rawConnection.close();
                            }
                        }
                        return null;
                    }
                    if ("unwrap".equals(name) && args != null && args.length == 1 && args[0] instanceof Class<?> iface) {
                        if (iface.isInstance(proxy) && !Connection.class.equals(iface)) {
                            return proxy;
                        }
                        if (Connection.class.isAssignableFrom(iface)) {
                            throw new SQLException(
                                    "Direct physical Connection unwrapping is forbidden on schema-routed connections",
                                    "42501");
                        }
                    }
                    if ("isWrapperFor".equals(name)
                            && args != null
                            && args.length == 1
                            && args[0] instanceof Class<?> iface
                            && Connection.class.isAssignableFrom(iface)) {
                        return false;
                    }
                    if ("setAutoCommit".equals(name)
                            && args != null
                            && args.length == 1
                            && args[0] instanceof Boolean nextAutoCommit) {
                        Object result;
                        try {
                            result = method.invoke(rawConnection, args);
                        } catch (InvocationTargetException ex) {
                            throw ex.getTargetException();
                        }
                        if (!rawConnection.isClosed()) {
                            applySearchPath(rawConnection, effectiveSearchPath, !nextAutoCommit);
                        }
                        return result;
                    }
                    if (("commit".equals(name) || "rollback".equals(name))
                            && (args == null || args.length == 0)) {
                        Object result;
                        try {
                            result = method.invoke(rawConnection, args);
                        } catch (InvocationTargetException ex) {
                            throw ex.getTargetException();
                        }
                        if (!rawConnection.isClosed() && !rawConnection.getAutoCommit()) {
                            applySearchPath(rawConnection, effectiveSearchPath, true);
                        }
                        return result;
                    }
                    if ("equals".equals(name) && args != null && args.length == 1) {
                        return proxy == args[0];
                    }
                    if ("hashCode".equals(name) && (args == null || args.length == 0)) {
                        return System.identityHashCode(proxy);
                    }
                    if ("toString".equals(name) && (args == null || args.length == 0)) {
                        return "SinglePoolSchemaRoutedConnection[" + primarySchema + "]";
                    }
                    try {
                        return method.invoke(rawConnection, args);
                    } catch (InvocationTargetException ex) {
                        throw ex.getTargetException();
                    }
                });
    }

    private static void applySearchPath(Connection connection, String searchPath, boolean localOnly)
            throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement("SELECT set_config('search_path', ?, ?)")) {
            stmt.setString(1, searchPath);
            stmt.setBoolean(2, localOnly);
            stmt.execute();
        }
    }

    private static void closeQuietly(Connection connection) {
        try {
            connection.close();
        } catch (SQLException ignored) {
            // Ignored on checkout failure path.
        }
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return targetDataSource.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        targetDataSource.setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        targetDataSource.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return targetDataSource.getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return targetDataSource.getParentLogger();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface != null && iface.isInstance(this)) {
            return iface.cast(this);
        }
        throw new SQLException("Unwrapping underlying pool from SinglePoolSchemaRoutingDataSource is forbidden", "42501");
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface != null && iface.isInstance(this);
    }
}
