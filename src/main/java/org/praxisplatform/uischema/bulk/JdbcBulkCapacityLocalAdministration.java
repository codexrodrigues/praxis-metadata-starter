package org.praxisplatform.uischema.bulk;

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

/** Local owner administration; no global-authority connection or runtime authorization. */
final class JdbcBulkCapacityLocalAdministration {
    private static final String MARKER = "praxis_bulk.praxis_bulk_capacity_marker";
    private final BulkCapacityBinding expected;
    private final DataSource owner;
    private final DataSourceTransactionManager ownerManager;
    private final String ownerLogin;
    private final BulkExecutionRoleConfiguration roles;
    private final long transactionMillis;
    private final long lockMillis;
    private final JdbcTemplate jdbc;

    JdbcBulkCapacityLocalAdministration(BulkCapacityBinding expected,
            DataSource owner, DataSourceTransactionManager manager, String ownerLogin,
            BulkExecutionRoleConfiguration roles, Duration budget, Duration lockBudget) {
        this.expected = Objects.requireNonNull(expected, "expected");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.ownerManager = Objects.requireNonNull(manager, "manager");
        this.ownerLogin = BulkCapacityAuthorityMigrator.canonical(ownerLogin);
        this.roles = Objects.requireNonNull(roles, "roles");
        this.transactionMillis = Objects.requireNonNull(budget, "budget").toMillis();
        this.lockMillis = Objects.requireNonNull(lockBudget, "lockBudget").toMillis();
        if (transactionMillis < 1000 || transactionMillis > 60_000 || lockMillis <= 0
                || lockMillis >= transactionMillis)
            throw new IllegalArgumentException("Capacity installation requires positive bounded time budgets");
        if (owner instanceof AbstractRoutingDataSource || owner instanceof DelegatingDataSource
                || manager.getDataSource() != owner
                || !this.ownerLogin.equals(roles.expectedSchemaOwnerRole()))
            throw new IllegalArgumentException("Capacity local owner configuration differs");
        this.jdbc = new JdbcTemplate(owner);
    }

    /** Observation only: an ACTIVE result is not permission to start a writer. */
    String inspect() {
        return inspect(deadline());
    }

    String inspect(long deadline) {
        String state = withOwner(deadline, true, (connection, remaining) -> {
            Marker marker = readMarker(connection, false);
            requireExpected(marker);
            return marker.state();
        });
        requireRemaining(deadline);
        return state;
    }

    /** Known commit plus independent readback, under one aggregate operation deadline. */
    boolean fence() {
        return fence(deadline());
    }

    boolean fence(long deadline) {
        boolean changed = withOwner(deadline, false, (connection, remaining) -> {
            Marker marker = lockMarker(connection);
            requireExpected(marker);
            authenticateCatalog(connection, deadline);
            if ("FENCED".equals(marker.state())) return false;
            requireRemaining(deadline);
            try (var update = connection.prepareStatement(
                    "update " + MARKER + " set state='FENCED' where marker_id=1")) {
                if (update.executeUpdate() != 1)
                    throw new IllegalStateException("Capacity fence lost");
            }
            return true;
        });
        if (!"FENCED".equals(inspect(deadline)))
            throw new IllegalStateException("Capacity fence readback differs");
        requireRemaining(deadline);
        return changed;
    }

    private long deadline() {
        outsideTransaction();
        return System.nanoTime() + transactionMillis * 1_000_000;
    }

    <T> T withOwner(OwnerWork<T> work) {
        return withOwner(deadline(), false, work);
    }

    private <T> T withOwner(long deadline, boolean readOnly, OwnerWork<T> work) {
        outsideTransaction();
        var transaction = new TransactionTemplate(ownerManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(Math.toIntExact((requireRemaining(deadline) + 999) / 1000));
        transaction.setReadOnly(readOnly);
        return transaction.execute(status -> jdbc.execute((ConnectionCallback<T>) connection -> {
            if (!TransactionSynchronizationManager.isActualTransactionActive()
                    || !TransactionSynchronizationManager.isSynchronizationActive()
                    || connection.getAutoCommit() || connection.isReadOnly() != readOnly
                    || !DataSourceUtils.isConnectionTransactional(
                            DataSourceUtils.getTargetConnection(connection), owner))
                throw new IllegalStateException("Capacity owner transaction is not bound to its JDBC connection");
            if (!"PostgreSQL".equals(connection.getMetaData().getDatabaseProductName()))
                throw new IllegalStateException("Capacity administration requires PostgreSQL");
            requireRemaining(deadline);
            try (var statement = connection.createStatement()) {
                statement.execute("set local search_path to pg_catalog, pg_temp");
            }
            authenticateCatalog(connection, deadline);
            T result = work.run(connection, deadline);
            requireRemaining(deadline);
            return result;
        }));
    }

    /** The existing V18 installation/ACL gate, not a new full V19 validator. */
    private void authenticateCatalog(Connection connection, long deadline) throws SQLException {
        constrainTimeouts(connection, deadline);
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery("select current_user")) {
            if (!rows.next() || !ownerLogin.equals(rows.getString(1)) || rows.next())
                throw new IllegalStateException("Capacity owner credential changed");
        }
        BulkExecutionMigrator.validateCapacityInstallationCatalog(connection, roles);
        constrainTimeouts(connection, deadline);
    }

    void constrainTimeouts(Connection connection, long deadline) throws SQLException {
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

    Marker lockMarker(Connection connection) throws SQLException {
        return readMarker(connection, true);
    }

    private Marker readMarker(Connection connection, boolean lock) throws SQLException {
        try (var statement = connection.prepareStatement(
                "select * from " + MARKER + " where marker_id=1" + (lock ? " for update" : ""));
                var rows = statement.executeQuery()) {
            if (!rows.next()) throw new IllegalStateException("Capacity marker is absent");
            Marker marker = new Marker(rows.getObject("database_id", UUID.class), rows.getString("deployment_id"),
                    rows.getString("tenant_id"), rows.getString("environment"), rows.getString("binding_id"),
                    rows.getLong("binding_generation"), rows.getObject("attestation_id", UUID.class),
                    rows.getObject("authority_id", UUID.class), rows.getLong("authority_epoch"),
                    rows.getString("state"));
            if (rows.next()) throw new IllegalStateException("Multiple capacity markers");
            if (!java.util.Set.of("PROVISIONED", "ACTIVE", "FENCED").contains(marker.state()))
                throw new IllegalStateException("Capacity marker state differs");
            return marker;
        }
    }

    void requireExpected(Marker marker) {
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

    static long requireRemaining(long deadline) {
        long remaining = (deadline - System.nanoTime()) / 1_000_000;
        if (remaining <= 0)
            throw new IllegalStateException("Capacity installation time budget expired");
        return remaining;
    }

    @FunctionalInterface
    interface OwnerWork<T> {
        T run(Connection connection, long deadline) throws SQLException;
    }

    static void outsideTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive())
            throw new IllegalStateException("Capacity installation rejects an outer transaction");
    }

    record Marker(UUID databaseId, String deploymentId, String tenantId, String environment,
                  String bindingId, long generation, UUID attestationId, UUID authorityId,
                  long authorityEpoch, String state) { }

}
