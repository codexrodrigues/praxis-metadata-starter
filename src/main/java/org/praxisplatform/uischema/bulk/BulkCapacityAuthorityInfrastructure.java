package org.praxisplatform.uischema.bulk;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Explicit connection and credential binding for the independent capacity authority database.
 * This is not the local bulk control plane and never joins a domain transaction.
 */
final class BulkCapacityAuthorityInfrastructure {
    public enum Access { PROVISIONER, ALLOCATOR, READER }

    private final DataSource dataSource;
    private final DataSourceTransactionManager transactionManager;
    private final BulkCapacityAuthorityMigrator.Identity identity;
    private final BulkCapacityAuthorityMigrator.RoleConfiguration roles;
    private final Access access;
    private final String expectedLoginRole;
    private final JdbcTemplate jdbc;

    public BulkCapacityAuthorityInfrastructure(DataSource dataSource,
            DataSourceTransactionManager transactionManager,
            BulkCapacityAuthorityMigrator.Identity identity, Access access,
            BulkCapacityAuthorityMigrator.RoleConfiguration roles) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.transactionManager = Objects.requireNonNull(transactionManager, "transactionManager");
        this.identity = Objects.requireNonNull(identity, "identity");
        this.access = Objects.requireNonNull(access, "access");
        this.roles = Objects.requireNonNull(roles, "roles");
        this.expectedLoginRole = switch (access) {
            case PROVISIONER -> roles.provisionerLogin();
            case ALLOCATOR -> roles.allocatorLogin();
            case READER -> roles.readerLogin();
        };
        if (dataSource instanceof AbstractRoutingDataSource || dataSource instanceof DelegatingDataSource
                || transactionManager.getDataSource() != dataSource)
            throw new IllegalArgumentException("Capacity authority requires one non-routing JDBC datasource and manager");
        this.jdbc = new JdbcTemplate(dataSource);
        withConnection(connection -> null);
    }

    public BulkCapacityAuthorityMigrator.Identity identity() { return identity; }
    public Access access() { return access; }

    /**
     * One independent PostgreSQL transaction per authority operation. The caller cannot carry
     * local-domain locks into this operation; its own binding is attested on every call.
     */
    <T> T withConnection(ConnectionCallback<T> work) {
        Objects.requireNonNull(work, "work");
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Capacity authority rejects an outer transaction");
        var transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setReadOnly(access == Access.READER);
        return transaction.execute(status -> jdbc.execute((ConnectionCallback<T>) connection -> {
            if (!TransactionSynchronizationManager.isActualTransactionActive()
                    || !TransactionSynchronizationManager.isSynchronizationActive()
                    || connection.getAutoCommit()
                    || !DataSourceUtils.isConnectionTransactional(
                            DataSourceUtils.getTargetConnection(connection), dataSource)
                    || connection.isReadOnly() != (access == Access.READER))
                throw new IllegalStateException("Capacity authority transaction is not bound to its JDBC connection");
            attest(connection);
            return work.doInConnection(connection);
        }));
    }

    /** Provisioning is separate from allocation and only declares a binding in B5b.1a. */
    public void enrollBinding(String tenantId, String bindingId, long generation) {
        if (access != Access.PROVISIONER)
            throw new IllegalStateException("Capacity enrollment requires provisioner credentials");
        String tenant = BulkCapacityAuthorityMigrator.canonical(tenantId);
        String binding = BulkCapacityAuthorityMigrator.canonical(bindingId);
        if (generation <= 0) throw new IllegalArgumentException("Binding generation must be positive");
        withConnection(connection -> {
            try (var statement = connection.prepareStatement(
                    "select praxis_bulk_capacity.enroll_binding(?,?,?,?)")) {
                statement.setString(1, identity.deploymentId());
                statement.setString(2, tenant);
                statement.setString(3, binding);
                statement.setLong(4, generation);
                statement.execute();
            }
            return null;
        });
    }

    private void attest(Connection connection) throws SQLException {
        if (!"PostgreSQL".equals(connection.getMetaData().getDatabaseProductName()))
            throw new IllegalStateException("Capacity authority requires PostgreSQL");
        BulkCapacityAuthorityMigrator.assertLiveCatalog(connection, roles.ownerLogin());
        BulkCapacityAuthorityMigrator.assertCallers(connection, roles);
        String grantedRole = switch (access) {
            case PROVISIONER -> BulkCapacityAuthorityMigrator.PROVISIONER_ROLE;
            case ALLOCATOR -> BulkCapacityAuthorityMigrator.ALLOCATOR_ROLE;
            case READER -> BulkCapacityAuthorityMigrator.READER_ROLE;
        };
        try (var statement = connection.prepareStatement("""
                select current_user, pg_has_role(current_user, ?, 'MEMBER'),
                    praxis_bulk_capacity.assert_authority_identity(?,?,?,?)
                """)) {
            statement.setString(1, grantedRole);
            statement.setString(2, identity.deploymentId());
            statement.setString(3, identity.environment());
            statement.setObject(4, identity.authorityId());
            statement.setLong(5, identity.expectedAuthorityEpoch());
            try (var rows = statement.executeQuery()) {
                if (!rows.next() || !expectedLoginRole.equals(rows.getString(1))
                        || !rows.getBoolean(2) || !rows.getBoolean(3)
                        || rows.next())
                    throw new IllegalStateException("Capacity authority role, database, or expected epoch changed");
            }
        }
        for (var other : Access.values()) {
            if (other == access) continue;
            String role = switch (other) {
                case PROVISIONER -> BulkCapacityAuthorityMigrator.PROVISIONER_ROLE;
                case ALLOCATOR -> BulkCapacityAuthorityMigrator.ALLOCATOR_ROLE;
                case READER -> BulkCapacityAuthorityMigrator.READER_ROLE;
            };
            try (var statement = connection.prepareStatement(
                    "select pg_has_role(current_user, ?, 'MEMBER')")) {
                statement.setString(1, role);
                try (var rows = statement.executeQuery()) {
                    if (!rows.next() || rows.getBoolean(1) || rows.next())
                        throw new IllegalStateException("Capacity authority credentials overlap roles");
                }
            }
        }
        String[] functions = {
                "enroll_binding(text,text,text,bigint)",
                "request_capacity(uuid,text,text,text,text,integer,text)",
                "allocate_next(text,text)", "find_issue(uuid)",
                "assert_authority_identity(text,text,uuid,bigint)"
        };
        for (int index = 0; index < functions.length; index++) {
            boolean expected = switch (access) {
                case PROVISIONER -> index == 0 || index == 4;
                case ALLOCATOR -> index == 1 || index == 2 || index == 4;
                case READER -> index == 3 || index == 4;
            };
            try (var statement = connection.prepareStatement(
                    "select has_function_privilege(current_user, ?, 'EXECUTE')")) {
                statement.setString(1, "praxis_bulk_capacity." + functions[index]);
                try (var rows = statement.executeQuery()) {
                    if (!rows.next() || rows.getBoolean(1) != expected || rows.next())
                        throw new IllegalStateException("Capacity authority function privileges changed");
                }
            }
        }
        try (var statement = connection.prepareStatement("""
                select has_schema_privilege(current_user, 'praxis_bulk_capacity', 'CREATE'),
                       has_schema_privilege(current_user, 'praxis_bulk_capacity', 'USAGE')
                """);
             var rows = statement.executeQuery()) {
            if (!rows.next() || rows.getBoolean(1) || !rows.getBoolean(2) || rows.next())
                throw new IllegalStateException("Capacity authority schema privilege changed");
        }
        for (String table : BulkCapacityAuthorityMigrator.tableNames()) {
            for (String privilege : new String[] {"SELECT", "INSERT", "UPDATE", "DELETE",
                    "TRUNCATE", "REFERENCES", "TRIGGER"}) {
                try (var statement = connection.prepareStatement(
                        "select has_table_privilege(current_user, ?, ?)")) {
                    statement.setString(1, "praxis_bulk_capacity." + table);
                    statement.setString(2, privilege);
                    try (var rows = statement.executeQuery()) {
                        if (!rows.next() || rows.getBoolean(1) || rows.next())
                            throw new IllegalStateException("Capacity caller acquired direct table privilege");
                    }
                }
            }
            try (var statement = connection.prepareStatement("""
                    select count(*) from pg_catalog.pg_attribute a,
                         lateral aclexplode(a.attacl) x
                    where a.attrelid=to_regclass(?) and a.attnum>0
                      and x.grantee=(select oid from pg_catalog.pg_roles where rolname=current_user)
                    """)) {
                statement.setString(1, "praxis_bulk_capacity." + table);
                try (var rows = statement.executeQuery()) {
                    if (!rows.next() || rows.getInt(1) != 0)
                        throw new IllegalStateException("Capacity caller acquired direct column privilege");
                }
            }
        }
    }
}
