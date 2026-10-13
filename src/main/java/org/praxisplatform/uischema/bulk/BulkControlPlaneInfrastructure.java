package org.praxisplatform.uischema.bulk;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.orm.jpa.EntityManagerFactoryInfo;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Explicit transaction binding for readiness publication and suspension. It uses the separately
 * granted PostgreSQL control-plane role; it never participates in a domain mutation transaction.
 */
public final class BulkControlPlaneInfrastructure {
    private static final SecureRandom FENCE_RANDOM = new SecureRandom();
    private final DataSource dataSource;
    private final PlatformTransactionManager transactionManager;
    private final String namespace;
    private final String deploymentId;
    private final String expectedRole;
    private final JdbcTemplate jdbc;
    private final BulkExecutionInfrastructure runtime;

    public BulkControlPlaneInfrastructure(DataSource dataSource, PlatformTransactionManager transactionManager,
            String namespace, String deploymentId, String expectedRole, BulkExecutionInfrastructure runtime) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.transactionManager = Objects.requireNonNull(transactionManager, "transactionManager");
        this.namespace = canonical(namespace, "namespace");
        this.deploymentId = canonical(deploymentId, "deploymentId");
        this.expectedRole = canonicalRole(expectedRole);
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        if (!runtime.roleConfiguration().controlPlaneGranteeRoles().contains(this.expectedRole))
            throw new IllegalArgumentException("Expected control-plane role must be present in the explicit control-plane allowlist");
        if (runtime.dataSource() == dataSource)
            throw new IllegalArgumentException("Control-plane and runtime datasources must be distinct bindings");
        if (!runtime.namespace().equals(namespace) || !runtime.deploymentId().equals(deploymentId))
            throw new IllegalArgumentException("Control-plane and runtime namespace/deployment bindings must match");
        validateBinding();
        this.jdbc = new JdbcTemplate(dataSource);
    }

    public String namespace() { return namespace; }
    public String deploymentId() { return deploymentId; }
    public String expectedRole() { return expectedRole; }

    <T> T withConnection(ConnectionCallback<T> work) {
        Objects.requireNonNull(work, "work");
        validateBinding();
        TransactionTemplate independent = new TransactionTemplate(transactionManager);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        independent.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        independent.setTimeout(3);
        return independent.execute(status -> {
            if (!TransactionSynchronizationManager.isActualTransactionActive()
                    || !TransactionSynchronizationManager.isSynchronizationActive()
                    || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
                throw new IllegalStateException("An independent writable control-plane transaction is required");
            }
            if (!(TransactionSynchronizationManager.getResource(dataSource) instanceof ConnectionHolder))
                throw new IllegalStateException("Control-plane JDBC connection is not exposed by its transaction manager");
            return jdbc.execute((ConnectionCallback<T>) connection -> {
                if (connection.getAutoCommit() || connection.isReadOnly()
                        || !DataSourceUtils.isConnectionTransactional(DataSourceUtils.getTargetConnection(connection), dataSource))
                    throw new IllegalStateException("Control-plane work must use its transaction connection");
                String previous = BulkExecutionInfrastructure.constrainLifecycleAttestationTimeouts(connection);
                int key1 = FENCE_RANDOM.nextInt();
                int key2 = FENCE_RANDOM.nextInt();
                try {
                    BulkExecutionMigrator.validateLiveControlPlaneRoleAccess(
                            connection, runtime.roleConfiguration(), expectedRole);
                    restoreAndAcquireDatabaseIdentityLock(connection, previous, key1, key2);
                } catch (java.sql.SQLException | RuntimeException | Error failure) {
                    BulkExecutionInfrastructure.restoreAfterLifecycleFailure(connection, previous, failure);
                    throw failure;
                }
                runtime.verifyControlDatabaseIdentity(key1, key2);
                BulkExecutionInfrastructure.requireLifecycleTimeRemaining(dataSource);
                T result = work.doInConnection(connection);
                BulkExecutionInfrastructure.requireLifecycleTimeRemaining(dataSource);
                return result;
            });
        });
    }

    /**
     * Proves the runtime and control credentials reach the same PostgreSQL database without
     * comparing JDBC URLs or granting either application role monitoring privileges. The
     * transaction-scoped advisory lock is database-local; the runtime connection must observe
     * this exact unpredictable lock while the control transaction holds it.
     */
    private void restoreAndAcquireDatabaseIdentityLock(java.sql.Connection controlConnection,
            String previous, int key1, int key2) throws java.sql.SQLException {
        try (var statement = controlConnection.prepareStatement("""
                select set_config('statement_timeout', ?, true);
                select pg_try_advisory_xact_lock(?, ?)
                """)) {
            statement.setString(1, previous); statement.setInt(2, key1); statement.setInt(3, key2);
            BulkExecutionInfrastructure.requireLifecycleResult(statement.execute(),
                    "Control-plane timeout restoration result is absent");
            try (var rows = statement.getResultSet()) {
                if (rows == null || !rows.next() || !previous.equals(rows.getString(1)) || rows.next())
                    throw new IllegalStateException("Control-plane timeout restoration returned invalid rows");
            }
            BulkExecutionInfrastructure.requireLifecycleResult(
                    statement.getMoreResults(java.sql.Statement.CLOSE_CURRENT_RESULT),
                    "Control-plane database identity lock result is absent");
            try (var row = statement.getResultSet()) {
                if (row == null || !row.next() || !row.getBoolean(1) || row.wasNull() || row.next())
                    throw new IllegalStateException("Could not acquire the temporary control-plane database identity lock");
            }
            if (statement.getMoreResults(java.sql.Statement.CLOSE_CURRENT_RESULT) || statement.getUpdateCount() != -1)
                throw new IllegalStateException("Control-plane verification returned unexpected extra results");
        }
    }

    private void validateBinding() {
        if (transactionManager instanceof AbstractPlatformTransactionManager local
                && !local.isGlobalRollbackOnParticipationFailure())
            throw new IllegalArgumentException("Control-plane rollback behavior must be fail-closed");
        if (dataSource instanceof AbstractRoutingDataSource || dataSource instanceof DelegatingDataSource)
            throw new IllegalArgumentException("Control plane requires a stable direct datasource binding");
        if (transactionManager instanceof DataSourceTransactionManager jdbcManager) {
            if (jdbcManager.getDataSource() != dataSource)
                throw new IllegalArgumentException("Control-plane JDBC manager must use the exact datasource instance");
        } else if (transactionManager instanceof JpaTransactionManager jpa) {
            if (jpa.getDataSource() != dataSource
                    || !(jpa.getEntityManagerFactory() instanceof EntityManagerFactoryInfo info)
                    || info.getDataSource() != dataSource)
                throw new IllegalArgumentException("Control-plane JPA manager and factory must expose the exact datasource instance");
        } else {
            throw new IllegalArgumentException("Only verifiable local JDBC or JPA control-plane managers are supported");
        }
    }

    private static String canonical(String value, String name) {
        if (value == null || value.isBlank() || !value.equals(value.strip()) || value.length() > 200
                || value.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException(name + " must be canonical nonblank text");
        return value;
    }

    private static String canonicalRole(String value) {
        if (value == null || value.isBlank() || !value.equals(value.strip())
                || value.codePoints().anyMatch(Character::isISOControl)
                || value.getBytes(StandardCharsets.UTF_8).length > 63)
            throw new IllegalArgumentException("expectedRole must be a PostgreSQL role name");
        return value;
    }
}
