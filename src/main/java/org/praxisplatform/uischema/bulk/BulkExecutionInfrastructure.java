package org.praxisplatform.uischema.bulk;

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
 * Explicit operational binding for JDBC work participating in the host's local transaction.
 * Construction performs no database access, DDL, worker registration or operation discovery.
 * The host owns the lifecycle and stable configuration of the supplied beans.
 */
public final class BulkExecutionInfrastructure {
    private final DataSource dataSource;
    private final PlatformTransactionManager transactionManager;
    private final String namespace;
    private final String deploymentId;
    private final BulkExecutionRoleConfiguration roleConfiguration;
    private final JdbcTemplate jdbc;

    public BulkExecutionInfrastructure(DataSource dataSource,
            PlatformTransactionManager transactionManager, String namespace, String deploymentId,
            BulkExecutionRoleConfiguration roleConfiguration) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.transactionManager = Objects.requireNonNull(transactionManager, "transactionManager");
        this.roleConfiguration = Objects.requireNonNull(roleConfiguration, "roleConfiguration");
        if (this.roleConfiguration.runtimeGranteeRoles().isEmpty()) {
            throw new IllegalArgumentException("At least one explicit runtime PostgreSQL role is required");
        }
        if (namespace == null || namespace.isBlank() || namespace.length() > 200
                || !namespace.equals(namespace.strip()) || namespace.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Explicit namespace of 1 to 200 characters without surrounding whitespace or controls is required");
        }
        if (deploymentId == null || deploymentId.isBlank() || deploymentId.length() > 200
                || !deploymentId.equals(deploymentId.strip())
                || deploymentId.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Explicit deploymentId of 1 to 200 characters without surrounding whitespace or controls is required");
        }
        this.namespace = namespace;
        this.deploymentId = deploymentId;
        validateBinding();
        this.jdbc = new JdbcTemplate(dataSource);
    }

    public DataSource dataSource() { return dataSource; }
    public PlatformTransactionManager transactionManager() { return transactionManager; }
    public String namespace() { return namespace; }
    public String deploymentId() { return deploymentId; }
    BulkExecutionRoleConfiguration roleConfiguration() { return roleConfiguration; }

    /**
     * Joins an existing writable transaction using the configured manager (MANDATORY).
     * Never starts an independent transaction. A returned value is provisional until the
     * caller's outer transaction commits. Callback failure marks that transaction rollback-only.
     * The trusted callback must not commit, roll back, change auto-commit or retain the connection.
     * Unit deadlines and authorization remain responsibilities of the execution layer.
     */
    public <T> T withConnection(ConnectionCallback<T> work) {
        Objects.requireNonNull(work, "work");
        validateBinding();
        TransactionTemplate mandatory = new TransactionTemplate(transactionManager);
        mandatory.setPropagationBehavior(TransactionDefinition.PROPAGATION_MANDATORY);
        return mandatory.execute(status -> {
            if (!TransactionSynchronizationManager.isActualTransactionActive()
                    || !TransactionSynchronizationManager.isSynchronizationActive()
                    || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                    || status.isRollbackOnly()) {
                throw new IllegalStateException("An active writable operational transaction is required");
            }
            if (!(TransactionSynchronizationManager.getResource(dataSource) instanceof ConnectionHolder)) {
                throw new IllegalStateException("Operational JDBC connection is not exposed by the transaction manager");
            }
            return jdbc.execute((ConnectionCallback<T>) connection -> {
                if (connection.getAutoCommit() || connection.isReadOnly()
                        || !DataSourceUtils.isConnectionTransactional(DataSourceUtils.getTargetConnection(connection), dataSource)) {
                    throw new IllegalStateException("JDBC work must use the operational transaction connection");
                }
                attestWithBoundStatementTimeout(connection,
                        attestedConnection -> BulkExecutionMigrator.validateLiveRuntimeRoleAccess(
                                attestedConnection, roleConfiguration));
                verifyDurableNamespaceBinding(connection);
                return work.doInConnection(connection);
            });
        });
    }

    /**
     * Opens a short runtime-role transaction for read-only lifecycle verification. The method
     * suspends any caller transaction and is intentionally separate from proposal/mutation work.
     */
    <T> T withLifecycleRead(ConnectionCallback<T> work) {
        Objects.requireNonNull(work, "work");
        validateBinding();
        TransactionTemplate independent = new TransactionTemplate(transactionManager);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        independent.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        independent.setTimeout(2);
        return independent.execute(status -> {
            if (!TransactionSynchronizationManager.isActualTransactionActive()
                    || !TransactionSynchronizationManager.isSynchronizationActive()
                    || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
                throw new IllegalStateException("An independent writable lifecycle verification transaction is required");
            }
            if (!(TransactionSynchronizationManager.getResource(dataSource) instanceof ConnectionHolder)) {
                throw new IllegalStateException("Runtime JDBC connection is not exposed by its transaction manager");
            }
            return jdbc.execute((ConnectionCallback<T>) connection -> {
                if (connection.getAutoCommit() || connection.isReadOnly()
                        || !DataSourceUtils.isConnectionTransactional(DataSourceUtils.getTargetConnection(connection), dataSource)) {
                    throw new IllegalStateException("Lifecycle verification must use the runtime transaction connection");
                }
                constrainLifecycleTimeouts(connection);
                attestWithBoundStatementTimeout(connection,
                        attestedConnection -> BulkExecutionMigrator.validateLiveRuntimeRoleAccess(
                                attestedConnection, roleConfiguration));
                verifyDurableNamespaceBinding(connection);
                return work.doInConnection(connection);
            });
        });
    }

    /** One short, physically read-only MVCC snapshot, isolated from any caller transaction. */
    <T> T withConsistentRead(ConnectionCallback<T> work) {
        Objects.requireNonNull(work, "work");
        validateBinding();
        TransactionTemplate independent = new TransactionTemplate(transactionManager);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        independent.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        independent.setReadOnly(true);
        independent.setTimeout(3);
        return independent.execute(status -> {
            if (!TransactionSynchronizationManager.isActualTransactionActive()
                    || !TransactionSynchronizationManager.isSynchronizationActive()
                    || !TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                    || !(TransactionSynchronizationManager.getResource(dataSource) instanceof ConnectionHolder)) {
                throw new IllegalStateException("An independent read-only operational transaction is required");
            }
            return jdbc.execute((ConnectionCallback<T>) connection -> {
                if (connection.getAutoCommit()
                        || !DataSourceUtils.isConnectionTransactional(
                                DataSourceUtils.getTargetConnection(connection), dataSource)) {
                    throw new IllegalStateException("Snapshot must use the bound operational connection");
                }
                try (var statement = connection.createStatement();
                        var state = statement.executeQuery("""
                                select current_setting('transaction_isolation'),
                                       current_setting('transaction_read_only')
                                """)) {
                    if (!state.next() || !"repeatable read".equals(state.getString(1))
                            || !"on".equals(state.getString(2)) || state.next()) {
                        throw new IllegalStateException("PostgreSQL read-only repeatable-read snapshot is required");
                    }
                }
                constrainLifecycleTimeouts(connection);
                attestWithBoundStatementTimeout(connection,
                        attested -> BulkExecutionMigrator.validateLiveRuntimeRoleAccess(
                                attested, roleConfiguration));
                verifyDurableNamespaceBinding(connection);
                return work.doInConnection(connection);
            });
        });
    }

    static void constrainLifecycleTimeouts(java.sql.Connection connection) throws java.sql.SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("""
                    select set_config('lock_timeout',
                        case when current_setting('lock_timeout') = '0'
                                  or current_setting('lock_timeout')::interval > interval '1 second'
                             then '1s' else current_setting('lock_timeout') end, true),
                           set_config('statement_timeout',
                        case when current_setting('statement_timeout') = '0'
                                  or current_setting('statement_timeout')::interval > interval '2 seconds'
                             then '2s' else current_setting('statement_timeout') end, true)
                    """);
        }
    }

    static String constrainLiveAttestationStatementTimeout(java.sql.Connection connection)
            throws java.sql.SQLException {
        String previous;
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                "select current_setting('statement_timeout')")) {
            if (!rows.next()) throw new IllegalStateException("Unable to read PostgreSQL statement timeout");
            previous = rows.getString(1);
            if (rows.next()) throw new IllegalStateException("PostgreSQL statement timeout query returned multiple rows");
        }
        try (var statement = connection.createStatement()) {
            statement.execute("select set_config('statement_timeout', "
                    + "case when current_setting('statement_timeout') = '0' "
                    + "or current_setting('statement_timeout')::interval > interval '250 milliseconds' "
                    + "then '250ms' else current_setting('statement_timeout') end, true)");
        }
        return previous;
    }

    static void restoreStatementTimeout(java.sql.Connection connection, String previous)
            throws java.sql.SQLException {
        try (var statement = connection.prepareStatement("select set_config('statement_timeout', ?, true)")) {
            statement.setString(1, previous);
            statement.execute();
        }
    }

    static void attestWithBoundStatementTimeout(java.sql.Connection connection, SqlAttestation attestation)
            throws java.sql.SQLException {
        String previous = constrainLiveAttestationStatementTimeout(connection);
        Throwable primaryFailure = null;
        try {
            attestation.run(connection);
        } catch (java.sql.SQLException | RuntimeException | Error failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            try {
                restoreStatementTimeout(connection, previous);
            } catch (java.sql.SQLException restoreFailure) {
                if (primaryFailure != null) {
                    primaryFailure.addSuppressed(restoreFailure);
                } else {
                    throw restoreFailure;
                }
            }
        }
    }

    @FunctionalInterface
    interface SqlAttestation {
        void run(java.sql.Connection connection) throws java.sql.SQLException;
    }

    private void verifyDurableNamespaceBinding(java.sql.Connection connection) throws java.sql.SQLException {
        try (var statement = connection.prepareStatement("""
                select deployment_id from praxis_bulk.praxis_bulk_namespace_binding where namespace_id = ?
                """)) {
            statement.setString(1, namespace);
            try (var rows = statement.executeQuery()) {
                if (!rows.next() || !deploymentId.equals(rows.getString(1)) || rows.next()) {
                    throw new IllegalStateException("Operational namespace is not bound to the configured deployment");
                }
            }
        }
    }

    private void validateBinding() {
        if (transactionManager instanceof AbstractPlatformTransactionManager local
                && !local.isGlobalRollbackOnParticipationFailure()) {
            throw new IllegalArgumentException("Participation failure must mark the operational transaction rollback-only");
        }
        // This first adapter binding deliberately excludes routing and wrapper composition.
        // Supplying the actual shared pool avoids inventing equivalence by URL or bean name.
        if (dataSource instanceof AbstractRoutingDataSource || dataSource instanceof DelegatingDataSource) {
            throw new IllegalArgumentException("Supply the stable operational datasource directly, without routing or wrappers");
        }
        if (transactionManager instanceof JpaTransactionManager jpa) {
            if (jpa.getDataSource() != dataSource
                    || !(jpa.getEntityManagerFactory() instanceof EntityManagerFactoryInfo info)
                    || info.getDataSource() != dataSource) {
                throw new IllegalArgumentException("JPA manager and EntityManagerFactory must expose the same operational datasource instance");
            }
        } else if (transactionManager instanceof DataSourceTransactionManager jdbcManager) {
            if (jdbcManager.getDataSource() != dataSource) {
                throw new IllegalArgumentException("JDBC manager must use the same operational datasource instance");
            }
        } else {
            throw new IllegalArgumentException("Only verifiable local JDBC or JPA transaction managers are supported");
        }
    }
}
