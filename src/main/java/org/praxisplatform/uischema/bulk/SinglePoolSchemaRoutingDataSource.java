package org.praxisplatform.uischema.bulk;

import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.AbstractDataSource;
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

    /**
     * Internal checkout for the standalone publication verifier. It must establish a
     * transaction-local catalog path before running any query and must not run domain SQL.
     */
    DataSource publicationVerificationDataSource() {
        return new AbstractDataSource() {
            @Override
            public Connection getConnection() throws SQLException {
                // Preserve host admission, including rejection of missing tenant context.
                Objects.requireNonNull(routeResolver.resolve(), "SchemaRouteResolver returned null");
                Connection connection = targetDataSource.getConnection();
                try {
                    if (connectionInspector != null) connectionInspector.accept(connection);
                    return connection;
                } catch (RuntimeException | Error failure) {
                    closeQuietly(connection);
                    throw failure;
                }
            }

            @Override
            public Connection getConnection(String username, String password) throws SQLException {
                throw new SQLFeatureNotSupportedException("Per-call publication credentials are forbidden");
            }
        };
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
        } catch (RuntimeException | Error failure) {
            closeQuietly(rawConnection);
            throw failure;
        }

        RoutingState state = new RoutingState();
        return (Connection) Proxy.newProxyInstance(
                SinglePoolSchemaRoutingDataSource.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("close".equals(name) && (args == null || args.length == 0)) {
                        if (!state.closed) {
                            state.closed = true;
                            boolean mustAbort = false;
                            try {
                                if (!rawConnection.isClosed()) {
                                    if (!rawConnection.getAutoCommit()) {
                                        try {
                                            rawConnection.rollback();
                                        } catch (SQLException ignored) {
                                            mustAbort = true;
                                        }
                                        state.txApplied = false;
                                        try {
                                            rawConnection.setAutoCommit(true);
                                        } catch (SQLException ignored) {
                                            mustAbort = true;
                                        }
                                    }
                                    if (state.sessionDirty) {
                                        if (!mustAbort && rawConnection.getAutoCommit()) {
                                            try {
                                                applySearchPath(rawConnection, resetPath, false);
                                                state.sessionDirty = false;
                                                state.sessionApplied = false;
                                            } catch (SQLException ignored) {
                                                mustAbort = true;
                                            }
                                        } else {
                                            mustAbort = true;
                                        }
                                    } else {
                                        state.sessionApplied = false;
                                    }
                                }
                            } catch (SQLException ignored) {
                                mustAbort = true;
                            } finally {
                                if (mustAbort) {
                                    abortQuietly(rawConnection);
                                }
                                rawConnection.close();
                            }
                        }
                        return null;
                    }
                    if ("unwrap".equals(name) && args != null && args.length == 1) {
                        if (args[0] instanceof Class<?> iface && isSafeProxyWrapperInterface(iface)) {
                            return proxy;
                        }
                        throw new SQLException(
                                "Direct physical Connection unwrapping is forbidden on schema-routed connections",
                                "42501");
                    }
                    if ("isWrapperFor".equals(name) && args != null && args.length == 1) {
                        return args[0] instanceof Class<?> iface && isSafeProxyWrapperInterface(iface);
                    }
                    if ("setAutoCommit".equals(name)
                            && args != null
                            && args.length == 1
                            && args[0] instanceof Boolean) {
                        Object result = invokeUnwrapping(method, rawConnection, args);
                        state.txApplied = false;
                        return result;
                    }
                    if ("commit".equals(name) && (args == null || args.length == 0)) {
                        try {
                            return invokeUnwrapping(method, rawConnection, args);
                        } finally {
                            state.txApplied = false;
                        }
                    }
                    if ("rollback".equals(name)) {
                        if (args == null || args.length == 0) {
                            try {
                                return invokeUnwrapping(method, rawConnection, args);
                            } finally {
                                state.txApplied = false;
                            }
                        }
                        return invokeUnwrapping(method, rawConnection, args);
                    }
                    if ("createStatement".equals(name)) {
                        Statement rawStatement = (Statement) invokeUnwrapping(method, rawConnection, args);
                        return wrapStatement(
                                rawConnection, (Connection) proxy, rawStatement, effectiveSearchPath, state, null);
                    }
                    if (("prepareStatement".equals(name) || "prepareCall".equals(name))
                            && args != null
                            && args.length >= 1
                            && args[0] instanceof String sql) {
                        if (!isSetTransactionStatement(sql)) {
                            ensureSearchPath(rawConnection, effectiveSearchPath, state);
                        }
                        Statement rawStatement = (Statement) invokeUnwrapping(method, rawConnection, args);
                        return wrapStatement(
                                rawConnection, (Connection) proxy, rawStatement, effectiveSearchPath, state, sql);
                    }
                    if ("nativeSQL".equals(name)
                            || "getMetaData".equals(name)
                            || "getSchema".equals(name)
                            || "setSavepoint".equals(name)) {
                        ensureSearchPath(rawConnection, effectiveSearchPath, state);
                        return invokeUnwrapping(method, rawConnection, args);
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
                    return invokeUnwrapping(method, rawConnection, args);
                });
    }

    private static Statement wrapStatement(
            Connection rawConnection,
            Connection routedProxy,
            Statement rawStatement,
            String effectiveSearchPath,
            RoutingState state,
            String boundSql) {
        if (rawStatement == null) {
            return null;
        }
        Class<?> statementInterface = rawStatement instanceof CallableStatement
                ? CallableStatement.class
                : rawStatement instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
        return (Statement) Proxy.newProxyInstance(
                SinglePoolSchemaRoutingDataSource.class.getClassLoader(),
                new Class<?>[] {statementInterface},
                (stmtProxy, stmtMethod, stmtArgs) -> {
                    String stmtMethodName = stmtMethod.getName();
                    if ("getConnection".equals(stmtMethodName) && (stmtArgs == null || stmtArgs.length == 0)) {
                        return routedProxy;
                    }
                    if ("equals".equals(stmtMethodName) && stmtArgs != null && stmtArgs.length == 1) {
                        return stmtProxy == stmtArgs[0];
                    }
                    if ("hashCode".equals(stmtMethodName) && (stmtArgs == null || stmtArgs.length == 0)) {
                        return System.identityHashCode(stmtProxy);
                    }
                    if ("toString".equals(stmtMethodName) && (stmtArgs == null || stmtArgs.length == 0)) {
                        return "SinglePoolSchemaRoutedStatement[" + statementInterface.getSimpleName() + "]";
                    }
                    if ("unwrap".equals(stmtMethodName) && stmtArgs != null && stmtArgs.length == 1) {
                        if (stmtArgs[0] instanceof Class<?> iface && isSafeProxyWrapperInterface(iface)) {
                            return stmtProxy;
                        }
                        throw new SQLException(
                                "Direct physical Statement unwrapping is forbidden on schema-routed statements",
                                "42501");
                    }
                    if ("isWrapperFor".equals(stmtMethodName) && stmtArgs != null && stmtArgs.length == 1) {
                        return stmtArgs[0] instanceof Class<?> iface && isSafeProxyWrapperInterface(iface);
                    }
                    if ("execute".equals(stmtMethodName)
                            || "executeQuery".equals(stmtMethodName)
                            || "executeUpdate".equals(stmtMethodName)
                            || "executeLargeUpdate".equals(stmtMethodName)
                            || "addBatch".equals(stmtMethodName)) {
                        String sql = stmtArgs != null && stmtArgs.length >= 1 && stmtArgs[0] instanceof String s
                                ? s
                                : boundSql;
                        if (sql == null || !isSetTransactionStatement(sql)) {
                            ensureSearchPath(rawConnection, effectiveSearchPath, state);
                        }
                    } else if ("executeBatch".equals(stmtMethodName)
                            || "executeLargeBatch".equals(stmtMethodName)) {
                        if (boundSql == null || !isSetTransactionStatement(boundSql)) {
                            ensureSearchPath(rawConnection, effectiveSearchPath, state);
                        }
                    }
                    return invokeUnwrapping(stmtMethod, rawStatement, stmtArgs);
                });
    }

    private static void ensureSearchPath(
            Connection rawConnection, String effectiveSearchPath, RoutingState state) throws SQLException {
        if (rawConnection.isClosed()) {
            return;
        }
        if (rawConnection.getAutoCommit()) {
            if (!state.sessionApplied) {
                applySearchPath(rawConnection, effectiveSearchPath, false);
                state.sessionApplied = true;
                state.sessionDirty = true;
            }
        } else if (!state.txApplied) {
            applySearchPath(rawConnection, effectiveSearchPath, true);
            state.txApplied = true;
        }
    }

    private static boolean isSetTransactionStatement(String sql) {
        return sql != null && sql.stripLeading().regionMatches(true, 0, "SET TRANSACTION", 0, 15);
    }

    private static Object invokeUnwrapping(Method method, Object target, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException ex) {
            throw ex.getTargetException();
        }
    }

    private static final class RoutingState {
        private boolean closed;
        private boolean sessionApplied;
        private boolean sessionDirty;
        private boolean txApplied;
    }

    private static void applySearchPath(Connection connection, String searchPath, boolean localOnly)
            throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement("SELECT set_config('search_path', ?, ?)")) {
            stmt.setString(1, searchPath);
            stmt.setBoolean(2, localOnly);
            stmt.execute();
        }
    }

    private static boolean isSafeProxyWrapperInterface(Class<?> iface) {
        return iface == java.sql.Wrapper.class || iface == AutoCloseable.class || iface == Object.class;
    }

    private static void abortQuietly(Connection connection) {
        try {
            connection.abort(Runnable::run);
        } catch (SQLException | UnsupportedOperationException | SecurityException ignored) {
            // Best-effort physical eviction when a dirty routed session cannot be reset cleanly.
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
