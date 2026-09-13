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
    private final JdbcTemplate jdbc;

    public BulkExecutionInfrastructure(DataSource dataSource,
            PlatformTransactionManager transactionManager, String namespace) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.transactionManager = Objects.requireNonNull(transactionManager, "transactionManager");
        if (namespace == null || namespace.isBlank() || namespace.length() > 200
                || !namespace.equals(namespace.strip()) || namespace.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Explicit namespace of 1 to 200 characters without surrounding whitespace or controls is required");
        }
        this.namespace = namespace;
        validateBinding();
        this.jdbc = new JdbcTemplate(dataSource);
    }

    public DataSource dataSource() { return dataSource; }
    public PlatformTransactionManager transactionManager() { return transactionManager; }
    public String namespace() { return namespace; }

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
                return work.doInConnection(connection);
            });
        });
    }

    private void validateBinding() {
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
