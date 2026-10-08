package org.praxisplatform.uischema.bulk;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.praxisplatform.uischema.bulk.JdbcBulkCapacityLocalAdministration.Marker;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** Trusted, owner-credential local installation of already issued global rights. */
final class JdbcBulkCapacityInstallation {
    private static final SecureRandom WITNESS_RANDOM = new SecureRandom();
    private static final String MARKER = "praxis_bulk.praxis_bulk_capacity_marker";
    private static final String INSTALLATION = "praxis_bulk.praxis_bulk_capacity_installation";

    record ExpectedBinding(String deploymentId, String tenantId, String environment, String bindingId,
                           long generation, UUID databaseId, UUID attestationId,
                           UUID authorityId, long authorityEpoch) {
        ExpectedBinding {
            deploymentId = BulkCapacityAuthorityMigrator.canonical(deploymentId);
            tenantId = BulkCapacityAuthorityMigrator.canonical(tenantId);
            environment = BulkCapacityAuthorityMigrator.canonical(environment);
            bindingId = BulkCapacityAuthorityMigrator.canonical(bindingId);
            Objects.requireNonNull(databaseId, "databaseId");
            Objects.requireNonNull(attestationId, "attestationId");
            Objects.requireNonNull(authorityId, "authorityId");
            if (generation <= 0 || authorityEpoch <= 0)
                throw new IllegalArgumentException("Capacity binding generation and epoch must be positive");
        }
    }

    private final ExpectedBinding expected;
    private final DataSource owner;
    private final String ownerLogin;
    private final JdbcBulkCapacityLocalAdministration administration;
    private final BulkExecutionInfrastructure runtime;
    private final BulkCapacityAuthorityInfrastructure provisioner;
    private final JdbcBulkCapacityIssuer.CapacityReader reader;

    JdbcBulkCapacityInstallation(ExpectedBinding expected, DataSource owner,
            DataSourceTransactionManager ownerManager, String expectedOwnerLogin,
            BulkExecutionInfrastructure runtime, BulkCapacityAuthorityInfrastructure provisioner,
            JdbcBulkCapacityIssuer.CapacityReader reader,
            Duration transactionBudget, Duration lockBudget) {
        this.expected = Objects.requireNonNull(expected, "expected");
        this.owner = Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(ownerManager, "ownerManager");
        this.ownerLogin = BulkCapacityAuthorityMigrator.canonical(expectedOwnerLogin);
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.provisioner = Objects.requireNonNull(provisioner, "provisioner");
        this.reader = Objects.requireNonNull(reader, "reader");
        this.administration = new JdbcBulkCapacityLocalAdministration(expected, owner, ownerManager,
                ownerLogin, runtime.roleConfiguration(), transactionBudget, lockBudget);
        if (owner == runtime.dataSource()
                || !ownerLogin.equals(runtime.roleConfiguration().expectedSchemaOwnerRole())
                || !expected.deploymentId().equals(runtime.deploymentId())
                || !expected.deploymentId().equals(reader.identity().deploymentId())
                || !expected.deploymentId().equals(provisioner.identity().deploymentId())
                || !expected.environment().equals(reader.identity().environment())
                || !reader.identity().equals(provisioner.identity())
                || !expected.authorityId().equals(reader.identity().authorityId())
                || expected.authorityEpoch() != reader.identity().expectedAuthorityEpoch()
                || provisioner.access() != BulkCapacityAuthorityInfrastructure.Access.PROVISIONER)
            throw new IllegalArgumentException("Capacity installation credential or authority binding differs");
    }

    /** Fixes the physical identity once; a repeated call may only observe the exact same marker. */
    void bootstrap() {
        outsideTransaction();
        withOwner((connection, deadline) -> {
            try (var insert = connection.prepareStatement("""
                    insert into praxis_bulk.praxis_bulk_capacity_marker
                        (marker_id, database_id, deployment_id, tenant_id, environment, binding_id,
                         binding_generation, attestation_id, authority_id, authority_epoch, state)
                    values (1,?,?,?,?,?,?,?,?,?,'PROVISIONED') on conflict (marker_id) do nothing
                    """)) {
                bindExpected(insert);
                insert.executeUpdate();
            }
            Marker marker = lockMarker(connection);
            requireExpected(marker);
            if (!"PROVISIONED".equals(marker.state()) && !"ACTIVE".equals(marker.state()))
                throw new IllegalStateException("Capacity marker is fenced");
            return null;
        });
    }

    /** Local owner/runtime witness finishes before the independent global provisioner transaction. */
    void registerAttestation() {
        outsideTransaction();
        withOwner((connection, deadline) -> {
            Marker marker = lockMarker(connection);
            requireExpected(marker);
            if ("FENCED".equals(marker.state()))
                throw new IllegalStateException("Capacity marker is fenced");
            witnessRuntimeOnSameDatabase(connection, deadline);
            return null;
        });
        provisioner.registerAttestation(expected.tenantId(), expected.bindingId(), expected.generation(),
                expected.databaseId(), expected.attestationId());
    }

    /** Authenticated global readback completes before any owner transaction is opened. */
    void activate() {
        outsideTransaction();
        JdbcBulkCapacityIssuer.Attestation attestation = reader.readAttestation(expected.attestationId())
                .orElseThrow(() -> new IllegalStateException("Capacity attestation is absent"));
        requireExpected(attestation);
        withOwner((connection, deadline) -> {
            Marker marker = lockMarker(connection);
            requireExpected(marker);
            if ("FENCED".equals(marker.state()))
                throw new IllegalStateException("Capacity marker is fenced");
            if ("PROVISIONED".equals(marker.state())) {
                try (var update = connection.prepareStatement(
                        "update " + MARKER + " set state='ACTIVE' where marker_id=1")) {
                    if (update.executeUpdate() != 1)
                        throw new IllegalStateException("Capacity marker activation lost");
                }
            }
            return null;
        });
    }

    /** Returns true for a newly committed installation and false for exact, committed replay. */
    boolean install(UUID tokenId) {
        outsideTransaction();
        JdbcBulkCapacityIssuer.IssuedToken token = reader.readIssuedToken(
                Objects.requireNonNull(tokenId, "tokenId"))
                .orElseThrow(() -> new IllegalStateException("Issued capacity token is absent"));
        requireExpected(token);
        boolean inserted = withOwner((connection, deadline) -> {
            Marker marker = lockMarker(connection);
            requireExpected(marker);
            if (!"ACTIVE".equals(marker.state()))
                throw new IllegalStateException("Capacity marker is not active");
            Installation existing = readInstallation(connection, tokenId);
            if (existing != null) {
                requireToken(existing, token);
                return false;
            }
            try (var insert = connection.prepareStatement("""
                    insert into praxis_bulk.praxis_bulk_capacity_installation
                        (token_id, marker_id, database_id, deployment_id, tenant_id, environment,
                         binding_id, binding_generation, attestation_id, authority_id, authority_epoch,
                         request_id, capacity_class, token_ordinal, payload_digest, token_state)
                    values (?,1,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """)) {
                insert.setObject(1, token.tokenId());
                insert.setObject(2, token.databaseId());
                insert.setString(3, token.deploymentId());
                insert.setString(4, token.tenantId());
                insert.setString(5, token.environment());
                insert.setString(6, token.bindingId());
                insert.setLong(7, token.bindingGeneration());
                insert.setObject(8, token.attestationId());
                insert.setObject(9, token.authorityId());
                insert.setLong(10, token.authorityEpoch());
                insert.setObject(11, token.requestId());
                insert.setString(12, token.capacityClass().name());
                insert.setInt(13, token.ordinal());
                insert.setString(14, token.payloadDigest());
                insert.setString(15, token.tokenState());
                if (insert.executeUpdate() != 1)
                    throw new IllegalStateException("Capacity installation was not inserted");
            }
            return true;
        });
        // This second transaction proves a known commit was actually visible on readback.
        withOwner((connection, deadline) -> {
            requireToken(readInstallation(connection, tokenId), token);
            return null;
        });
        return inserted;
    }

    /** Terminal local fence, confirmed by the shared owner-local readback protocol. */
    void fence() {
        administration.fence();
    }

    private <T> T withOwner(JdbcBulkCapacityLocalAdministration.OwnerWork<T> work) {
        return administration.withOwner(work);
    }

    private void constrainTimeouts(Connection connection, long deadline) throws SQLException {
        administration.constrainTimeouts(connection, deadline);
    }

    private void witnessRuntimeOnSameDatabase(Connection ownerConnection, long deadline) throws SQLException {
        int first = WITNESS_RANDOM.nextInt();
        int second = WITNESS_RANDOM.nextInt();
        try (var lock = ownerConnection.prepareStatement("select pg_try_advisory_xact_lock(?,?)")) {
            lock.setInt(1, first);
            lock.setInt(2, second);
            try (var rows = lock.executeQuery()) {
                if (!rows.next() || !rows.getBoolean(1) || rows.next())
                    throw new IllegalStateException("Capacity database witness lock unavailable");
            }
        }
        try (Connection runtimeConnection = runtime.dataSource().getConnection()) {
            runtimeConnection.setReadOnly(true);
            runtimeConnection.setAutoCommit(false);
            try {
                constrainTimeouts(runtimeConnection, deadline);
                BulkExecutionMigrator.validateLiveRuntimeRoleAccess(runtimeConnection,
                        runtime.roleConfiguration());
                try (var query = runtimeConnection.prepareStatement("""
                        select exists (
                            select 1 from pg_catalog.pg_locks l
                            where l.locktype='advisory' and l.granted and l.mode='ExclusiveLock'
                              and l.database=(select d.oid from pg_catalog.pg_database d
                                              where d.datname=current_database())
                              and l.classid=?::oid and l.objid=?::oid and l.objsubid=2
                              and l.pid<>pg_backend_pid())
                        """)) {
                    query.setLong(1, Integer.toUnsignedLong(first));
                    query.setLong(2, Integer.toUnsignedLong(second));
                    try (var rows = query.executeQuery()) {
                        if (!rows.next() || !rows.getBoolean(1) || rows.next())
                            throw new IllegalStateException("Runtime credential reaches a different local database");
                    }
                }
            } finally {
                runtimeConnection.rollback();
            }
        }
    }

    private Marker lockMarker(Connection connection) throws SQLException {
        return administration.lockMarker(connection);
    }

    private Installation readInstallation(Connection connection, UUID tokenId) throws SQLException {
        try (var statement = connection.prepareStatement("select * from " + INSTALLATION + " where token_id=?")) {
            statement.setObject(1, tokenId);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) return null;
                Installation value = new Installation(rows.getObject("token_id", UUID.class),
                        rows.getObject("request_id", UUID.class), rows.getString("deployment_id"),
                        rows.getString("tenant_id"), rows.getString("environment"), rows.getString("binding_id"),
                        rows.getLong("binding_generation"), rows.getString("capacity_class"),
                        rows.getInt("token_ordinal"), rows.getString("payload_digest"),
                        rows.getString("token_state"), rows.getObject("database_id", UUID.class),
                        rows.getObject("attestation_id", UUID.class), rows.getObject("authority_id", UUID.class),
                        rows.getLong("authority_epoch"));
                if (rows.next()) throw new IllegalStateException("Duplicate capacity installation");
                return value;
            }
        }
    }

    private void bindExpected(java.sql.PreparedStatement insert) throws SQLException {
        insert.setObject(1, expected.databaseId());
        insert.setString(2, expected.deploymentId());
        insert.setString(3, expected.tenantId());
        insert.setString(4, expected.environment());
        insert.setString(5, expected.bindingId());
        insert.setLong(6, expected.generation());
        insert.setObject(7, expected.attestationId());
        insert.setObject(8, expected.authorityId());
        insert.setLong(9, expected.authorityEpoch());
    }

    private void requireExpected(Marker marker) {
        administration.requireExpected(marker);
    }

    private void requireExpected(JdbcBulkCapacityIssuer.Attestation attestation) {
        if (!expected.attestationId().equals(attestation.attestationId())
                || !expected.databaseId().equals(attestation.databaseId())
                || !expected.bindingId().equals(attestation.bindingId())
                || !expected.deploymentId().equals(attestation.deploymentId())
                || !expected.tenantId().equals(attestation.tenantId())
                || !expected.environment().equals(attestation.environment())
                || expected.generation() != attestation.bindingGeneration()
                || !expected.authorityId().equals(attestation.authorityId())
                || expected.authorityEpoch() != attestation.authorityEpoch())
            throw new IllegalStateException("Authenticated capacity attestation differs from trusted identity");
    }

    private void requireExpected(JdbcBulkCapacityIssuer.IssuedToken token) {
        if (!expected.databaseId().equals(token.databaseId())
                || !expected.attestationId().equals(token.attestationId())
                || !expected.deploymentId().equals(token.deploymentId())
                || !expected.tenantId().equals(token.tenantId())
                || !expected.environment().equals(token.environment())
                || !expected.bindingId().equals(token.bindingId())
                || expected.generation() != token.bindingGeneration()
                || !expected.authorityId().equals(token.authorityId())
                || expected.authorityEpoch() != token.authorityEpoch())
            throw new IllegalStateException("Issued capacity token differs from trusted binding");
    }

    private static void requireToken(Installation installed, JdbcBulkCapacityIssuer.IssuedToken token) {
        if (installed == null || !installed.tokenId().equals(token.tokenId())
                || !installed.requestId().equals(token.requestId())
                || !installed.deploymentId().equals(token.deploymentId())
                || !installed.tenantId().equals(token.tenantId())
                || !installed.environment().equals(token.environment())
                || !installed.bindingId().equals(token.bindingId())
                || installed.bindingGeneration() != token.bindingGeneration()
                || !installed.capacityClass().equals(token.capacityClass().name())
                || installed.ordinal() != token.ordinal()
                || !installed.payloadDigest().equals(token.payloadDigest())
                || !installed.tokenState().equals(token.tokenState())
                || !installed.databaseId().equals(token.databaseId())
                || !installed.attestationId().equals(token.attestationId())
                || !installed.authorityId().equals(token.authorityId())
                || installed.authorityEpoch() != token.authorityEpoch())
            throw new IllegalStateException("Installed capacity right differs from authenticated token");
    }

    private static void outsideTransaction() {
        JdbcBulkCapacityLocalAdministration.outsideTransaction();
    }

    private record Installation(UUID tokenId, UUID requestId, String deploymentId, String tenantId,
                                String environment, String bindingId, long bindingGeneration,
                                String capacityClass, int ordinal, String payloadDigest, String tokenState,
                                UUID databaseId, UUID attestationId, UUID authorityId, long authorityEpoch) { }
}
