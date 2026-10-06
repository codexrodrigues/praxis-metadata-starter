package org.praxisplatform.uischema.bulk;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
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
    private final DataSourceTransactionManager ownerManager;
    private final String ownerLogin;
    private final BulkExecutionInfrastructure runtime;
    private final BulkCapacityAuthorityInfrastructure provisioner;
    private final JdbcBulkCapacityIssuer.CapacityReader reader;
    private final int transactionSeconds;
    private final long transactionMillis;
    private final long lockMillis;
    private final JdbcTemplate jdbc;

    JdbcBulkCapacityInstallation(ExpectedBinding expected, DataSource owner,
            DataSourceTransactionManager ownerManager, String expectedOwnerLogin,
            BulkExecutionInfrastructure runtime, BulkCapacityAuthorityInfrastructure provisioner,
            JdbcBulkCapacityIssuer.CapacityReader reader,
            Duration transactionBudget, Duration lockBudget) {
        this.expected = Objects.requireNonNull(expected, "expected");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.ownerManager = Objects.requireNonNull(ownerManager, "ownerManager");
        this.ownerLogin = BulkCapacityAuthorityMigrator.canonical(expectedOwnerLogin);
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.provisioner = Objects.requireNonNull(provisioner, "provisioner");
        this.reader = Objects.requireNonNull(reader, "reader");
        Objects.requireNonNull(transactionBudget, "transactionBudget");
        Objects.requireNonNull(lockBudget, "lockBudget");
        long txMillis = transactionBudget.toMillis();
        long requestedLockMillis = lockBudget.toMillis();
        if (txMillis < 1000 || txMillis > 60_000
                || requestedLockMillis <= 0 || requestedLockMillis >= txMillis)
            throw new IllegalArgumentException("Capacity installation requires positive bounded time budgets");
        this.transactionSeconds = Math.toIntExact((txMillis + 999) / 1000);
        this.transactionMillis = txMillis;
        this.lockMillis = requestedLockMillis;
        if (owner instanceof AbstractRoutingDataSource || owner instanceof DelegatingDataSource
                || ownerManager.getDataSource() != owner || owner == runtime.dataSource()
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
        this.jdbc = new JdbcTemplate(owner);
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

    /** Terminal local fence. It serializes with installation on the singleton marker row. */
    void fence() {
        outsideTransaction();
        withOwner((connection, deadline) -> {
            Marker marker = lockMarker(connection);
            requireExpected(marker);
            if (!"FENCED".equals(marker.state())) {
                try (var update = connection.prepareStatement(
                        "update " + MARKER + " set state='FENCED' where marker_id=1")) {
                    if (update.executeUpdate() != 1)
                        throw new IllegalStateException("Capacity fence lost");
                }
            }
            return null;
        });
    }

    private <T> T withOwner(OwnerWork<T> work) {
        outsideTransaction();
        var transaction = new TransactionTemplate(ownerManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(transactionSeconds);
        long deadline = System.nanoTime() + transactionMillis * 1_000_000;
        return transaction.execute(status -> jdbc.execute((ConnectionCallback<T>) connection -> {
            if (!TransactionSynchronizationManager.isActualTransactionActive()
                    || !TransactionSynchronizationManager.isSynchronizationActive()
                    || connection.getAutoCommit() || connection.isReadOnly()
                    || !DataSourceUtils.isConnectionTransactional(
                            DataSourceUtils.getTargetConnection(connection), owner))
                throw new IllegalStateException("Capacity owner transaction is not bound to its JDBC connection");
            constrainTimeouts(connection, deadline);
            try (var statement = connection.createStatement();
                    var rows = statement.executeQuery("select current_user")) {
                if (!rows.next() || !ownerLogin.equals(rows.getString(1)) || rows.next())
                    throw new IllegalStateException("Capacity owner credential changed");
            }
            BulkExecutionMigrator.validateCapacityInstallationCatalog(connection,
                    runtime.roleConfiguration());
            constrainTimeouts(connection, deadline);
            T result = work.run(connection, deadline);
            requireRemaining(deadline);
            return result;
        }));
    }

    private void constrainTimeouts(Connection connection, long deadline) throws SQLException {
        long statement = requireRemaining(deadline);
        long lock = Math.min(lockMillis, statement);
        try (var sql = connection.createStatement()) {
            sql.execute("select set_config('statement_timeout', case when current_setting('statement_timeout')='0' "
                    + "or current_setting('statement_timeout')::interval > interval '" + statement
                    + " milliseconds' then '" + statement + "ms' else current_setting('statement_timeout') end, true), "
                    + "set_config('lock_timeout', case when current_setting('lock_timeout')='0' "
                    + "or current_setting('lock_timeout')::interval > interval '" + lock
                    + " milliseconds' then '" + lock + "ms' else current_setting('lock_timeout') end, true)");
        }
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
        try (var statement = connection.prepareStatement("select * from " + MARKER + " where marker_id=1 for update");
                var rows = statement.executeQuery()) {
            if (!rows.next()) throw new IllegalStateException("Capacity marker is absent");
            Marker marker = new Marker(rows.getObject("database_id", UUID.class), rows.getString("deployment_id"),
                    rows.getString("tenant_id"), rows.getString("environment"), rows.getString("binding_id"),
                    rows.getLong("binding_generation"), rows.getObject("attestation_id", UUID.class),
                    rows.getObject("authority_id", UUID.class), rows.getLong("authority_epoch"),
                    rows.getString("state"));
            if (rows.next()) throw new IllegalStateException("Multiple capacity markers");
            return marker;
        }
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
        if (marker == null || !expected.databaseId().equals(marker.databaseId())
                || !expected.deploymentId().equals(marker.deploymentId())
                || !expected.tenantId().equals(marker.tenantId())
                || !expected.environment().equals(marker.environment())
                || !expected.bindingId().equals(marker.bindingId())
                || expected.generation() != marker.generation()
                || !expected.attestationId().equals(marker.attestationId())
                || !expected.authorityId().equals(marker.authorityId())
                || expected.authorityEpoch() != marker.authorityEpoch())
            throw new IllegalStateException("Capacity marker differs from trusted provisioning identity");
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

    private static long requireRemaining(long deadline) {
        long remaining = (deadline - System.nanoTime()) / 1_000_000;
        if (remaining <= 0)
            throw new IllegalStateException("Capacity installation time budget expired");
        return remaining;
    }

    @FunctionalInterface
    private interface OwnerWork<T> {
        T run(Connection connection, long deadline) throws SQLException;
    }

    private static void outsideTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive())
            throw new IllegalStateException("Capacity installation rejects an outer transaction");
    }

    private record Marker(UUID databaseId, String deploymentId, String tenantId, String environment,
                          String bindingId, long generation, UUID attestationId, UUID authorityId,
                          long authorityEpoch, String state) { }

    private record Installation(UUID tokenId, UUID requestId, String deploymentId, String tenantId,
                                String environment, String bindingId, long bindingGeneration,
                                String capacityClass, int ordinal, String payloadDigest, String tokenState,
                                UUID databaseId, UUID attestationId, UUID authorityId, long authorityEpoch) { }
}
