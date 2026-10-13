package org.praxisplatform.uischema.bulk;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;
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
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive())
            throw new IllegalStateException("Capacity authority rejects an outer transaction");
        var transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setReadOnly(access == Access.READER);
        transaction.setTimeout(3);
        return transaction.execute(status -> jdbc.execute((ConnectionCallback<T>) connection -> {
            if (!TransactionSynchronizationManager.isActualTransactionActive()
                    || !TransactionSynchronizationManager.isSynchronizationActive()
                    || connection.getAutoCommit()
                    || !DataSourceUtils.isConnectionTransactional(
                            DataSourceUtils.getTargetConnection(connection), dataSource)
                    || connection.isReadOnly() != (access == Access.READER))
                throw new IllegalStateException("Capacity authority transaction is not bound to its JDBC connection");
            BulkExecutionInfrastructure.constrainLifecycleTimeouts(connection);
            attest(connection);
            requireTransactionTime();
            T result = work.doInConnection(connection);
            requireTransactionTime();
            return result;
        }));
    }

    // Inspect the same Spring deadline after decoding, without another JDBC statement or renewal.
    private void requireTransactionTime() {
        Object resource = TransactionSynchronizationManager.getResource(dataSource);
        if (!(resource instanceof org.springframework.jdbc.datasource.ConnectionHolder holder)
                || !holder.hasTimeout())
            throw new IllegalStateException("Capacity authority transaction deadline is absent");
        holder.getTimeToLiveInMillis();
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

    /** Registers one physical attestation after the local owner has committed its marker and witness. */
    public void registerAttestation(String tenantId, String bindingId, long generation,
                                    UUID databaseId, UUID attestationId) {
        if (access != Access.PROVISIONER)
            throw new IllegalStateException("Capacity attestation requires provisioner credentials");
        String tenant = BulkCapacityAuthorityMigrator.canonical(tenantId);
        String binding = BulkCapacityAuthorityMigrator.canonical(bindingId);
        if (generation <= 0 || databaseId == null || attestationId == null)
            throw new IllegalArgumentException("Capacity attestation identity is incomplete");
        withConnection(connection -> {
            try (var statement = connection.prepareStatement(
                    "select praxis_bulk_capacity.register_attestation(?,?,?,?,?,?,?)")) {
                statement.setString(1, identity.deploymentId());
                statement.setString(2, tenant);
                statement.setString(3, identity.environment());
                statement.setString(4, binding);
                statement.setLong(5, generation);
                statement.setObject(6, databaseId);
                statement.setObject(7, attestationId);
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
                        || !BulkCapacityAuthorityCatalog.booleanValue(rows, 2) || !BulkCapacityAuthorityCatalog.booleanValue(rows, 3)
                        || rows.next())
                    throw new IllegalStateException("Capacity authority role, database, or expected epoch changed");
            }
        }
        var batch = new BulkCapacityAuthorityCatalog.ReadBatch();
        collectPrivileges(batch, access);
        batch.execute(connection);
    }

    private static void collectPrivileges(BulkCapacityAuthorityCatalog.ReadBatch batch, Access access) {
        for (var other : Access.values()) {
            if (other == access) continue;
            String role = switch (other) {
                case PROVISIONER -> BulkCapacityAuthorityMigrator.PROVISIONER_ROLE;
                case ALLOCATOR -> BulkCapacityAuthorityMigrator.ALLOCATOR_ROLE;
                case READER -> BulkCapacityAuthorityMigrator.READER_ROLE;
            };
            batch.add(
                    "select pg_has_role(current_user, to_regrole(?), 'MEMBER')", rows -> {
                if (!rows.next() || BulkCapacityAuthorityCatalog.booleanValue(rows, 1) || rows.next())
                    throw new IllegalStateException("Capacity authority credentials overlap roles");
            }, role);
        }
        String[] functions = {
                "enroll_binding(text,text,text,bigint)",
                "request_capacity(uuid,text,text,text,text,integer,text)",
                "allocate_next(text,text)", "find_issue(uuid)",
                "register_attestation(text,text,text,text,bigint,uuid,uuid)",
                "read_attestation(uuid)", "read_issued_token(uuid)",
                "assert_authority_identity(text,text,uuid,bigint)"
        };
        for (int index = 0; index < functions.length; index++) {
            boolean expected = switch (access) {
                case PROVISIONER -> index == 0 || index == 4 || index == 7;
                case ALLOCATOR -> index == 1 || index == 2 || index == 7;
                case READER -> index == 3 || index == 5 || index == 6 || index == 7;
            };
            batch.add(
                    "select has_function_privilege(current_user, to_regprocedure(?), 'EXECUTE')", rows -> {
                if (!rows.next() || BulkCapacityAuthorityCatalog.booleanValue(rows, 1) != expected || rows.next())
                    throw new IllegalStateException("Capacity authority function privileges changed");
            }, "praxis_bulk_capacity." + functions[index]);
        }
        batch.add("""
                select has_schema_privilege(current_user, to_regnamespace('praxis_bulk_capacity'), 'CREATE'),
                       has_schema_privilege(current_user, to_regnamespace('praxis_bulk_capacity'), 'USAGE')
                """, rows -> {
            if (!rows.next() || BulkCapacityAuthorityCatalog.booleanValue(rows, 1) || !BulkCapacityAuthorityCatalog.booleanValue(rows, 2) || rows.next())
                throw new IllegalStateException("Capacity authority schema privilege changed");
        });
        for (String table : BulkCapacityAuthorityMigrator.tableNames()) {
            for (String privilege : new String[] {"SELECT", "INSERT", "UPDATE", "DELETE",
                    "TRUNCATE", "REFERENCES", "TRIGGER"}) {
                batch.add(
                        "select has_table_privilege(current_user, to_regclass(?), ?)", rows -> {
                    if (!rows.next() || BulkCapacityAuthorityCatalog.booleanValue(rows, 1) || rows.next())
                        throw new IllegalStateException("Capacity caller acquired direct table privilege");
                }, "praxis_bulk_capacity." + table, privilege);
            }
            batch.add("""
                    select count(*) from pg_catalog.pg_attribute a,
                         lateral aclexplode(a.attacl) x
                    where a.attrelid=to_regclass(?) and a.attnum>0
                      and x.grantee=(select oid from pg_catalog.pg_roles where rolname=current_user)
                    """, rows -> {
                if (!rows.next() || BulkCapacityAuthorityCatalog.intValue(rows, 1) != 0 || rows.next())
                    throw new IllegalStateException("Capacity caller acquired direct column privilege");
            }, "praxis_bulk_capacity." + table);
        }
    }
}
