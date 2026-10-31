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
        BulkExecutionMigrator.prepareLiveAttestation();
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
        return withLifecycleRead(work, null);
    }

    /** Same runtime attestation and namespace check, with a bounded database-local identity read. */
    boolean verifyControlDatabaseIdentity(int key1, int key2) {
        return withLifecycleRead(ignored -> true, new DatabaseIdentity(key1, key2));
    }

    private record DatabaseIdentity(int key1, int key2) { }

    private <T> T withLifecycleRead(ConnectionCallback<T> work, DatabaseIdentity identity) {
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
                String previous = constrainLifecycleAttestationTimeouts(connection);
                try {
                    BulkExecutionMigrator.validateLiveRuntimeRoleAccess(connection, roleConfiguration);
                    restoreAndVerifyLifecycleBinding(connection, previous, identity);
                } catch (java.sql.SQLException | RuntimeException | Error failure) {
                    restoreAfterLifecycleFailure(connection, previous, failure);
                    throw failure;
                }
                requireLifecycleTimeRemaining(dataSource);
                T result = work.doInConnection(connection);
                requireLifecycleTimeRemaining(dataSource);
                return result;
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
                establishOwnedSnapshotTransaction(connection);
                String previous = constrainSnapshotAttestationTimeouts(connection);
                try {
                    BulkExecutionMigrator.validateLiveRuntimeRoleAccess(connection, roleConfiguration);
                    restoreAndVerifyLifecycleBinding(connection, previous, null);
                } catch (java.sql.SQLException | RuntimeException | Error failure) {
                    restoreAfterLifecycleFailure(connection, previous, failure);
                    throw failure;
                }
                requireLifecycleTimeRemaining(dataSource);
                T result = work.doInConnection(connection);
                requireLifecycleTimeRemaining(dataSource);
                return result;
            });
        });
    }

    /** Materializes this owned transaction's existing contract before its first SDK SELECT. */
    private void establishOwnedSnapshotTransaction(java.sql.Connection connection) throws java.sql.SQLException {
        requireLifecycleTimeRemaining(dataSource);
        try (var statement = connection.createStatement()) {
            DataSourceUtils.applyTransactionTimeout(statement, dataSource);
            statement.executeUpdate("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ, READ ONLY");
        }
        requireLifecycleTimeRemaining(dataSource);
    }

    /** The read-only snapshot's state and lower-preserving clamp share one submission. */
    static String constrainSnapshotAttestationTimeouts(java.sql.Connection connection)
            throws java.sql.SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
                with previous as materialized (
                    select case when current_setting('statement_timeout') = '0'
                                      or current_setting('statement_timeout')::interval > interval '2 seconds'
                                then '2s' else current_setting('statement_timeout') end value)
                select value,
                       set_config('lock_timeout',
                           case when current_setting('lock_timeout') = '0'
                                     or current_setting('lock_timeout')::interval > interval '1 second'
                                then '1s' else current_setting('lock_timeout') end, true),
                       set_config('statement_timeout',
                           case when value::interval > interval '250 milliseconds'
                                then '250ms' else value end, true)
                       , current_setting('transaction_isolation'),
                       current_setting('transaction_read_only')
                from previous
                """)) {
            if (!rows.next()) throw new IllegalStateException("Unable to read lifecycle statement timeout");
            String previous = rows.getString(1);
            if (previous == null || rows.getString(2) == null || rows.getString(3) == null
                    || !"repeatable read".equals(rows.getString(4))
                    || !"on".equals(rows.getString(5)) || rows.next())
                throw new IllegalStateException("Lifecycle timeout query returned invalid rows");
            return previous;
        }
    }

    /** The return value is the exact post-lifecycle-clamp timeout to restore, including lower limits. */
    static String constrainLifecycleAttestationTimeouts(java.sql.Connection connection)
            throws java.sql.SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
                with previous as materialized (
                    select case when current_setting('statement_timeout') = '0'
                                      or current_setting('statement_timeout')::interval > interval '2 seconds'
                                then '2s' else current_setting('statement_timeout') end value)
                select value,
                       set_config('lock_timeout',
                           case when current_setting('lock_timeout') = '0'
                                     or current_setting('lock_timeout')::interval > interval '1 second'
                                then '1s' else current_setting('lock_timeout') end, true),
                       set_config('statement_timeout',
                           case when value::interval > interval '250 milliseconds'
                                then '250ms' else value end, true)
                from previous
                """)) {
            if (!rows.next()) throw new IllegalStateException("Unable to read lifecycle statement timeout");
            String previous = rows.getString(1);
            if (previous == null || rows.getString(2) == null || rows.getString(3) == null || rows.next())
                throw new IllegalStateException("Lifecycle timeout query returned invalid rows");
            return previous;
        }
    }

    static void requireLifecycleTimeRemaining(DataSource source) {
        if (!(TransactionSynchronizationManager.getResource(source) instanceof ConnectionHolder holder)
                || !holder.hasTimeout())
            throw new IllegalStateException("Lifecycle transaction deadline is absent");
        holder.getTimeToLiveInMillis();
    }

    static void restoreAfterLifecycleFailure(java.sql.Connection connection, String previous, Throwable primary) {
        try { restoreStatementTimeout(connection, previous); }
        catch (java.sql.SQLException | RuntimeException | Error restoreFailure) {
            if (restoreFailure != primary) primary.addSuppressed(restoreFailure);
        }
    }

    private void restoreAndVerifyLifecycleBinding(java.sql.Connection connection, String previous,
            DatabaseIdentity identity) throws java.sql.SQLException {
        String sql = "select set_config('statement_timeout', ?, true);\n"
                + "select deployment_id from praxis_bulk.praxis_bulk_namespace_binding where namespace_id = ?";
        if (identity != null) sql += ";\n" + """
                select exists (
                    select 1 from pg_catalog.pg_locks l
                    where l.locktype = 'advisory' and l.granted and l.mode = 'ExclusiveLock'
                      and l.database = (select d.oid from pg_catalog.pg_database d
                                        where d.datname = current_database())
                      and l.classid = ?::oid and l.objid = ?::oid and l.objsubid = 2
                      and l.pid <> pg_backend_pid())
                """;
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, previous); statement.setString(2, namespace);
            if (identity != null) {
                statement.setLong(3, Integer.toUnsignedLong(identity.key1()));
                statement.setLong(4, Integer.toUnsignedLong(identity.key2()));
            }
            requireLifecycleResult(statement.execute(), "Lifecycle timeout restoration result is absent");
            try (var rows = statement.getResultSet()) {
                if (rows == null || !rows.next() || !previous.equals(rows.getString(1)) || rows.next())
                    throw new IllegalStateException("Lifecycle timeout restoration returned invalid rows");
            }
            requireLifecycleResult(statement.getMoreResults(java.sql.Statement.CLOSE_CURRENT_RESULT),
                    "Operational namespace binding result is absent");
            try (var rows = statement.getResultSet()) {
                if (rows == null || !rows.next() || !deploymentId.equals(rows.getString(1)) || rows.next())
                    throw new IllegalStateException("Operational namespace is not bound to the configured deployment");
            }
            if (identity != null) {
                requireLifecycleResult(statement.getMoreResults(java.sql.Statement.CLOSE_CURRENT_RESULT),
                        "Database identity result is absent");
                try (var rows = statement.getResultSet()) {
                    if (rows == null || !rows.next() || !rows.getBoolean(1) || rows.wasNull() || rows.next())
                        throw new IllegalStateException("Runtime and control-plane bindings do not reach the same PostgreSQL database");
                }
            }
            if (statement.getMoreResults(java.sql.Statement.CLOSE_CURRENT_RESULT) || statement.getUpdateCount() != -1)
                throw new IllegalStateException("Lifecycle verification returned unexpected extra results");
        }
    }

    static void requireLifecycleResult(boolean result, String message) {
        if (!result) throw new IllegalStateException(message);
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
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
                with previous as materialized (select current_setting('statement_timeout') value)
                select value, set_config('statement_timeout',
                    case when value = '0' or value::interval > interval '250 milliseconds'
                         then '250ms' else value end, true)
                from previous
                """)) {
            if (!rows.next()) throw new IllegalStateException("Unable to read PostgreSQL statement timeout");
            String previous = rows.getString(1);
            if (previous == null || rows.next())
                throw new IllegalStateException("PostgreSQL statement timeout query returned invalid rows");
            return previous;
        }
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
            } catch (java.sql.SQLException | RuntimeException | Error restoreFailure) {
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
