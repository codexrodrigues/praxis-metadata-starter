package org.praxisplatform.uischema.bulk;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Explicit PostgreSQL deployment migration for protected bulk-proposal input.
 *
 * <p>This class is intentionally not an auto-configuration component. A host calls it during an
 * explicit deployment step with the same operational datasource used by the protected store. It
 * owns only the {@value #SCHEMA} schema and never baselines, cleans, evaluates, admits or
 * executes proposals. On V8 upgrade it grants the new private manifest to explicitly configured
 * runtime roles that already hold the exact protected-evaluation write privileges.</p>
 */
public final class BulkExecutionMigrator {
    static final String SCHEMA = "praxis_bulk";
    static final String HISTORY_TABLE = "praxis_bulk_schema_history";
    private static final String PROPOSAL_TABLE = "praxis_bulk_proposal";
    private static final String EVALUATION_TABLE = "praxis_bulk_evaluation";
    private static final String MANIFEST_TABLE = "praxis_bulk_target_manifest";
    private static final String MANIFEST_BOOTSTRAP_TABLE = "praxis_bulk_manifest_bootstrap";
    private static final String PREVIEW_STATE_TABLE = "praxis_bulk_preview_state";
    private static final String TARGET_PREVIEW_TABLE = "praxis_bulk_target_preview";
    private static final String PREVIEW_BOOTSTRAP_TABLE = "praxis_bulk_preview_bootstrap";
    private static final String PREVIEW_INTEGRITY_TABLE = "praxis_bulk_preview_item_integrity";
    private static final String PREVIEW_INTEGRITY_BOOTSTRAP_TABLE = "praxis_bulk_preview_integrity_bootstrap";
    private static final String PREVIEW_READER_BOOTSTRAP_TABLE = "praxis_bulk_preview_reader_bootstrap";
    private static final String REJECTION_FUNCTION = "reject_praxis_bulk_proposal_update";
    private static final String REJECTION_TRIGGER = "praxis_bulk_proposal_reject_update";
    private static final String EVALUATION_REJECTION_FUNCTION = "reject_praxis_bulk_evaluation_update";
    private static final String EVALUATION_REJECTION_TRIGGER = "praxis_bulk_evaluation_reject_update";

    private static final String EXECUTION_TABLE = "praxis_bulk_execution";
    private static final String RECEIPT_TABLE = "praxis_bulk_item_receipt";
    private static final String ADMISSION_TABLE = "praxis_bulk_admission";
    private static final String ATOMIC_RECEIPT_TABLE = "praxis_bulk_atomic_receipt";
    private static final String ATOMIC_ITEM_TABLE = "praxis_bulk_atomic_item_result";
    private static final String ATOMIC_EFFECT_TABLE = "praxis_bulk_atomic_effect_ref";
    private static final String ATOMIC_REJECTION_TABLE = "praxis_bulk_atomic_rejection";
    private static final String ATOMIC_BOOTSTRAP_TABLE = "praxis_bulk_atomic_bootstrap";
    private static final List<String> ATOMIC_RUNTIME_TABLES = List.of(ATOMIC_RECEIPT_TABLE,
            ATOMIC_ITEM_TABLE, ATOMIC_EFFECT_TABLE, ATOMIC_REJECTION_TABLE);
    private static final String BINDING_FUNCTION = "protect_praxis_bulk_execution_binding";
    private static final String BINDING_TRIGGER = "praxis_bulk_execution_protect_binding";
    private static final String TERMINAL_REASON_FUNCTION = "protect_praxis_bulk_terminal_reason";
    private static final String TERMINAL_REASON_TRIGGER = "praxis_bulk_execution_protect_terminal_reason";
    private static final String RECEIPT_FUNCTION = "reject_praxis_bulk_item_receipt_mutation";
    private static final String RECEIPT_TRIGGER = "praxis_bulk_item_receipt_reject_mutation";
    private static final String ADMISSION_FUNCTION = "reject_praxis_bulk_admission_mutation";
    private static final String ADMISSION_TRIGGER = "praxis_bulk_admission_reject_mutation";
    private static final String DESCRIPTOR_FUNCTION = "protect_execution_descriptor_binding";
    private static final String DESCRIPTOR_TRIGGER = "praxis_bulk_execution_protect_descriptor_binding";
    private static final String INSERT_FENCE_FUNCTION = "guard_descriptor_fence";
    private static final String PROPOSAL_INSERT_FENCE_TRIGGER = "praxis_bulk_proposal_descriptor_fence";
    private static final String EXECUTION_INSERT_FENCE_TRIGGER = "praxis_bulk_execution_descriptor_fence";
    private static final String NAMESPACE_BINDING_TABLE = "praxis_bulk_namespace_binding";
    private static final String OPERATION_CONTROL_TABLE = "praxis_bulk_operation_control";
    private static final String OPENAPI_PUBLICATION_TABLE = "praxis_bulk_openapi_publication";
    private static final String DEPLOYMENT_BUCKET_TABLE = "praxis_bulk_deployment_bucket";
    private static final String SUBJECT_BUCKET_TABLE = "praxis_bulk_subject_bucket";
    private static final String ALLOCATION_TABLE = "praxis_bulk_allocation";
    private static final String TOMBSTONE_TABLE = "praxis_bulk_tombstone";
    private static final String CAPACITY_READ_BOOTSTRAP_TABLE = "praxis_bulk_capacity_read_bootstrap";
    private static final String CAPACITY_MARKER_TABLE = "praxis_bulk_capacity_marker";
    private static final String CAPACITY_INSTALLATION_TABLE = "praxis_bulk_capacity_installation";
    private static final Set<String> V18_FUNCTIONS = Set.of(
            "protect_capacity_marker()", "reject_capacity_installation_mutation()",
            "protect_capacity_read_bootstrap()");
    private static final Set<String> V18_TRIGGERS = Set.of(
            CAPACITY_READ_BOOTSTRAP_TABLE + ".praxis_bulk_capacity_read_bootstrap_protect",
            CAPACITY_MARKER_TABLE + ".praxis_bulk_capacity_marker_protect",
            CAPACITY_INSTALLATION_TABLE + ".praxis_bulk_capacity_installation_immutable");
    private static final Set<String> V5_TABLES = Set.of(HISTORY_TABLE, PROPOSAL_TABLE, EVALUATION_TABLE,
            MANIFEST_TABLE, MANIFEST_BOOTSTRAP_TABLE, PREVIEW_STATE_TABLE,
            TARGET_PREVIEW_TABLE, PREVIEW_BOOTSTRAP_TABLE, PREVIEW_INTEGRITY_TABLE,
            PREVIEW_INTEGRITY_BOOTSTRAP_TABLE,
            PREVIEW_READER_BOOTSTRAP_TABLE,
            EXECUTION_TABLE, RECEIPT_TABLE, ADMISSION_TABLE, NAMESPACE_BINDING_TABLE,
            OPERATION_CONTROL_TABLE, OPENAPI_PUBLICATION_TABLE, DEPLOYMENT_BUCKET_TABLE, SUBJECT_BUCKET_TABLE,
            ALLOCATION_TABLE, TOMBSTONE_TABLE, ATOMIC_RECEIPT_TABLE, ATOMIC_ITEM_TABLE,
            ATOMIC_EFFECT_TABLE, ATOMIC_REJECTION_TABLE, ATOMIC_BOOTSTRAP_TABLE);
    private static final Set<String> V18_TABLES = Set.of(
            CAPACITY_READ_BOOTSTRAP_TABLE, CAPACITY_MARKER_TABLE, CAPACITY_INSTALLATION_TABLE);
    private static final Set<String> V5_INDEXES = Set.of(
            "praxis_bulk_allocation_pending_deployment_idx", "praxis_bulk_allocation_pending_subject_idx",
            "praxis_bulk_allocation_active_deployment_idx", "praxis_bulk_execution_retention_idx",
            "praxis_bulk_proposal_expiry_idx");
    private static final Set<String> V5_FUNCTIONS = Set.of(
            "guard_lifecycle_delete()", "guard_tombstone_mutation()", "guard_bucket_mutation()",
            "protect_allocation_transition()", "validate_allocation_binding()",
            "protect_namespace_binding()", "protect_operation_control()",
            "guard_new_bulk_admission()", "guard_new_bulk_evaluation()",
            "terminal_evidence_complete(p_execution_id uuid, p_required_count integer)",
            "guard_terminal_execution()", "guard_terminal_evidence_insert()",
            "release_active_allocation_on_terminal()",
            "purge_terminal_execution(p_execution_id uuid)",
            "expire_unconsumed_proposal(p_proposal_id uuid)");
    // Historical schema recognition must retain V6's exact signature until V15 runs.
    private static final Set<String> V6_FUNCTIONS = Set.of(
            "guard_new_bulk_admission()", "guard_new_bulk_evaluation()",
            "lock_operation_control(p_namespace_id text, p_operation_id text)",
            "transition_operation_control(p_namespace_id text, p_operation_id text, p_expected_generation bigint, p_target_state text, p_descriptor_fingerprint text, p_structural_revision text)");
    // Current attestation permits only the publication-bound CAS, never the historical overload.
    private static final Set<String> V15_CONTROL_FUNCTIONS = Set.of(
            "guard_new_bulk_admission()", "guard_new_bulk_evaluation()",
            "lock_operation_control(p_namespace_id text, p_operation_id text)",
            "transition_operation_control(p_namespace_id text, p_operation_id text, p_expected_generation bigint, p_target_state text, p_descriptor_fingerprint text, p_structural_revision text, p_expected_publication_generation bigint, p_expected_publication_digest text)");
    private static final Set<String> V7_FUNCTIONS = Set.of(INSERT_FENCE_FUNCTION + "()");
    private static final Set<String> V8_FUNCTIONS = Set.of(
            "require_complete_target_manifest()", "reject_target_manifest_mutation()");
    private static final Set<String> V9_FUNCTIONS = Set.of(
            "require_complete_target_preview()", "require_complete_preview_parent()",
            "reject_preview_mutation()");
    private static final Set<String> V10_FUNCTIONS = Set.of("protect_cancel_request()");
    private static final Set<String> V11_FUNCTIONS = Set.of(
            "require_complete_preview_integrity_bootstrap()",
            "require_complete_preview_item_integrity()");
    private static final Set<String> V12_FUNCTIONS = Set.of("assert_preview_integrity_complete()");
    private static final Set<String> V14_FUNCTIONS = Set.of(
            "lock_openapi_publication(p_namespace_id text, p_deployment_id text)",
            "transition_openapi_publication(p_namespace_id text, p_deployment_id text, p_expected_generation bigint, p_target_state text, p_document_digest text)");
    private static final Set<String> V16_FUNCTIONS = Set.of(
            "guard_bulk_protocol_insert()", "guard_atomic_receipt_insert()",
            "guard_atomic_item_insert()", "guard_atomic_effect_insert()",
            "guard_atomic_rejection_insert()", "reject_atomic_evidence_mutation()",
            "atomic_evidence_complete(p_execution_id uuid, p_required_count integer)",
            "guard_atomic_attempt_transition()", "guard_per_item_evidence_insert()");
    private static final Set<String> V5_TRIGGERS = Set.of(
            PROPOSAL_TABLE + ".praxis_bulk_proposal_guard_delete",
            EVALUATION_TABLE + ".praxis_bulk_evaluation_guard_delete",
            EXECUTION_TABLE + ".praxis_bulk_execution_guard_delete",
            RECEIPT_TABLE + ".praxis_bulk_receipt_guard_delete",
            ADMISSION_TABLE + ".praxis_bulk_admission_guard_delete",
            ALLOCATION_TABLE + ".praxis_bulk_allocation_guard_delete",
            TOMBSTONE_TABLE + ".praxis_bulk_tombstone_guard_mutation",
            ALLOCATION_TABLE + ".praxis_bulk_allocation_protect_transition",
            ALLOCATION_TABLE + ".praxis_bulk_allocation_validate_binding",
            DEPLOYMENT_BUCKET_TABLE + ".praxis_bulk_deployment_bucket_guard_mutation",
            SUBJECT_BUCKET_TABLE + ".praxis_bulk_subject_bucket_guard_mutation",
            NAMESPACE_BINDING_TABLE + ".praxis_bulk_namespace_binding_immutable",
            OPERATION_CONTROL_TABLE + ".praxis_bulk_operation_control_protect",
            PROPOSAL_TABLE + ".praxis_bulk_proposal_guard_admission",
            EXECUTION_TABLE + ".praxis_bulk_execution_guard_admission",
            EVALUATION_TABLE + ".praxis_bulk_evaluation_guard_admission",
            EXECUTION_TABLE + ".praxis_bulk_execution_guard_terminal",
            EXECUTION_TABLE + ".praxis_bulk_execution_release_active_allocation",
            RECEIPT_TABLE + ".praxis_bulk_receipt_guard_terminal",
            ADMISSION_TABLE + ".praxis_bulk_admission_guard_terminal");
    private static final Set<String> V8_TRIGGERS = Set.of(
            EVALUATION_TABLE + ".praxis_bulk_evaluation_require_manifest",
            MANIFEST_TABLE + ".praxis_bulk_target_manifest_immutable",
            MANIFEST_TABLE + ".praxis_bulk_target_manifest_guard_delete");
    private static final Set<String> V10_TRIGGERS = Set.of(
            EXECUTION_TABLE + ".praxis_bulk_execution_protect_cancel");
    private static final Set<String> V9_TRIGGERS = Set.of(
            EVALUATION_TABLE + ".praxis_bulk_evaluation_require_preview",
            TARGET_PREVIEW_TABLE + ".praxis_bulk_target_preview_guard_insert",
            PREVIEW_STATE_TABLE + ".praxis_bulk_preview_state_immutable",
            PREVIEW_STATE_TABLE + ".praxis_bulk_preview_state_guard_delete",
            TARGET_PREVIEW_TABLE + ".praxis_bulk_target_preview_immutable",
            TARGET_PREVIEW_TABLE + ".praxis_bulk_target_preview_guard_delete");
    private static final Set<String> V11_TRIGGERS = Set.of(
            PREVIEW_STATE_TABLE + ".praxis_bulk_preview_state_integrity_guard_insert",
            EVALUATION_TABLE + ".praxis_bulk_evaluation_require_preview_item_integrity",
            PREVIEW_INTEGRITY_TABLE + ".praxis_bulk_preview_item_integrity_immutable",
            PREVIEW_INTEGRITY_TABLE + ".praxis_bulk_preview_item_integrity_guard_delete");
    private static final Set<String> V16_TRIGGERS = Set.of(
            PROPOSAL_TABLE + ".praxis_bulk_proposal_protocol_insert",
            EXECUTION_TABLE + ".praxis_bulk_execution_protocol_insert",
            EXECUTION_TABLE + ".praxis_bulk_execution_guard_atomic_attempt",
            RECEIPT_TABLE + ".praxis_bulk_receipt_per_item_only",
            ADMISSION_TABLE + ".praxis_bulk_admission_per_item_only",
            ATOMIC_RECEIPT_TABLE + ".praxis_bulk_atomic_receipt_guard_insert",
            ATOMIC_RECEIPT_TABLE + ".praxis_bulk_atomic_receipt_reject_mutation",
            ATOMIC_RECEIPT_TABLE + ".praxis_bulk_atomic_receipt_guard_delete",
            ATOMIC_ITEM_TABLE + ".praxis_bulk_atomic_item_result_guard_insert",
            ATOMIC_ITEM_TABLE + ".praxis_bulk_atomic_item_result_reject_mutation",
            ATOMIC_ITEM_TABLE + ".praxis_bulk_atomic_item_result_guard_delete",
            ATOMIC_EFFECT_TABLE + ".praxis_bulk_atomic_effect_ref_guard_insert",
            ATOMIC_EFFECT_TABLE + ".praxis_bulk_atomic_effect_ref_reject_mutation",
            ATOMIC_EFFECT_TABLE + ".praxis_bulk_atomic_effect_ref_guard_delete",
            ATOMIC_REJECTION_TABLE + ".praxis_bulk_atomic_rejection_guard_insert",
            ATOMIC_REJECTION_TABLE + ".praxis_bulk_atomic_rejection_reject_mutation",
            ATOMIC_REJECTION_TABLE + ".praxis_bulk_atomic_rejection_guard_delete");
    private static volatile MigrationExpectations migrationExpectations;

    private BulkExecutionMigrator() { }

    /**
     * Applies pending protected-storage migrations and validates the resulting PostgreSQL catalog.
     * The call must happen outside a Spring transaction because it owns deployment DDL, not a
     * proposal write transaction. The owner datasource must return clean auto-commit connections
     * to one stable PostgreSQL database, with an exclusive backend throughout each simultaneous
     * loan. Observed aliases are rejected; this is not a certification of connection multiplexers.
     *
     * @return the number of migrations executed by Flyway
     */
    public static int migrate(DataSource dataSource, Map<String, String> namespaceToDeploymentId) {
        DataSource operationalDataSource = Objects.requireNonNull(dataSource, "dataSource");
        return migrate(operationalDataSource, namespaceToDeploymentId,
                BulkExecutionRoleConfiguration.none(migrationOwnerRole(operationalDataSource)), List.of());
    }

    /**
     * Applies migrations and bootstraps deny-only control rows for the explicitly declared
     * confirmation operations. The identity list is part of deployment configuration; it is
     * never inferred from request headers or historical proposals. The owner datasource must
     * satisfy the clean auto-commit, stable database, and exclusive backend loan requirements
     * of {@link #migrate(DataSource, Map)}.
     */
    public static int migrateWithOperations(DataSource dataSource, Map<String, String> namespaceToDeploymentId,
            List<BulkOperationControlIdentity> operations) {
        DataSource operationalDataSource = Objects.requireNonNull(dataSource, "dataSource");
        return migrate(operationalDataSource, namespaceToDeploymentId,
                BulkExecutionRoleConfiguration.none(migrationOwnerRole(operationalDataSource)), operations);
    }

    /**
     * Applies migrations and validates every bulk ACL against the explicitly configured host
     * runtime, retention-operator, and control-plane PostgreSQL roles. The owner datasource must
     * satisfy the clean auto-commit, stable database, and exclusive backend loan requirements
     * of {@link #migrate(DataSource, Map)}.
     */
    public static int migrate(DataSource dataSource, Map<String, String> namespaceToDeploymentId,
            BulkExecutionRoleConfiguration roles) {
        return migrate(dataSource, namespaceToDeploymentId, roles, List.of());
    }

    /**
     * Applies migrations and validates every bulk ACL against the explicitly configured host
     * runtime, retention-operator, and control-plane PostgreSQL roles, while provisioning control
     * identities for every declared confirmation operation. The owner datasource must satisfy
     * the clean auto-commit, stable database, and exclusive backend loan requirements
     * of {@link #migrate(DataSource, Map)}.
     */
    public static int migrate(DataSource dataSource, Map<String, String> namespaceToDeploymentId,
            BulkExecutionRoleConfiguration roles, List<BulkOperationControlIdentity> operations) {
        requireOutsideSpringTransaction();
        DataSource operationalDataSource = Objects.requireNonNull(dataSource, "dataSource");
        Map<String, String> deployments = canonicalDeploymentMap(namespaceToDeploymentId);
        List<BulkOperationControlIdentity> controlIdentities = canonicalControlIdentities(operations, deployments);
        BulkExecutionRoleConfiguration roleConfiguration = Objects.requireNonNull(roles, "roles");
        // V20 is an access-path extension of a completely bootstrapped V19 catalog.
        // Never hold migration coordination while initializer takes its own advisory lock.
        int migrationsExecuted = migrateSchemaWithHistoricalPreflight(operationalDataSource, roleConfiguration, 19, deployments);
        validateOwnerBootstrapBeforeWrites(operationalDataSource, roleConfiguration, deployments);
        initializeGovernedLifecycle(operationalDataSource, deployments, controlIdentities, roleConfiguration);
        completeCapacityReadBootstrap(operationalDataSource, roleConfiguration);
        completeCapacityOccupancyBootstrap(operationalDataSource, roleConfiguration);
        migrationsExecuted += migrateSchemaWithHistoricalPreflight(operationalDataSource, roleConfiguration, 20, deployments);
        validate(operationalDataSource, roleConfiguration);
        return migrationsExecuted;
    }

    /**
     * Coordinates owner DDL with the existing lifecycle advisory key. Read history only after
     * acquiring it, so a waiter observes an upgrade committed by the preceding owner. Flyway
     * and V18 bootstrap use separate connections and do not acquire this key. Release it before
     * initializeGovernedLifecycle, which acquires the same key on its own connection.
     * A re-read of V19 follows the existing owner bootstrap path, not a stale V18 preflight or
     * public serving validation of an intermediate PENDING V19 installation. The host's owner
     * pool must accommodate this retained connection plus Flyway's migration connections;
     * this method does not create a second pool. Before starting that transaction, two live
     * clean owner loans are checked for an observed backend alias. The second loan closes before
     * the advisory fence or Flyway. These probes and the new advisory Statement have a
     * query timeout of at most ten seconds, preserving any shorter positive JDBC timeout
     * and the host's native PostgreSQL limits. This bounds coordinator waiting, not the
     * whole migration, Flyway DDL, or the legacy lifecycle initializer's advisory wait.
     */
    private static int migrateSchemaWithHistoricalPreflight(DataSource source,
            BulkExecutionRoleConfiguration roles, int targetVersion, Map<String, String> deployments) {
        try (Connection coordination = source.getConnection()) {
            MigrationOwnerBackend owner = readMigrationOwnerBackend(coordination);
            require(roles.expectedSchemaOwnerRole().equals(owner.role()),
                    "Bulk schema coordination requires its explicit owner credential");
            try (Connection probe = source.getConnection()) {
                MigrationOwnerBackend second = readMigrationOwnerBackend(probe);
                require(roles.expectedSchemaOwnerRole().equals(second.role()),
                        "Bulk schema coordination requires its explicit owner credential");
                require(owner.database().equals(second.database()),
                        "Bulk schema migration requires owner connections to the same database");
                require(owner.pid() != second.pid(),
                        "Bulk schema migration requires independent owner connections");
            }
            coordination.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            coordination.setAutoCommit(false);
            try {
                try (var statement = coordination.createStatement()) {
                    int existingTimeout = statement.getQueryTimeout();
                    statement.setQueryTimeout(existingTimeout > 0 ? Math.min(existingTimeout, 10) : 10);
                    statement.execute("select pg_advisory_xact_lock(1347574124, 5)");
                }
                assertKnownDedicatedSchema(source);
                int historyVersion = currentHistoryVersion(coordination);
                if (historyVersion == 20) {
                    // Existing current storage is attested before any bootstrap or binding write.
                    validate(source, roles);
                    coordination.commit();
                    return 0;
                }
                if (targetVersion == 20) {
                    require(historyVersion == 19, "V20 requires a completely bootstrapped V19 predecessor");
                    validateCurrent(source, roles, 19);
                } else if (historyVersion == 19) {
                    validateCurrent(source, roles, 19, true, deployments);
                }
                if (historyVersion == 17) validateV17BeforeUpgrade(source, roles);
                if (historyVersion == 18) {
                    validateV18BeforeUpgrade(source, roles);
                    // Only V18 may be pending here. Earlier bootstraps were attested COMPLETE;
                    // this canonical owner transaction rechecks its latch/ACL before any grant.
                    completeCapacityReadBootstrap(source, roles);
                }
                int migrationsExecuted = flyway(source, targetVersion,
                        new PublicationCreationCallback(roles, deployments, owner))
                        .migrate().migrationsExecuted;
                coordination.commit();
                return migrationsExecuted;
            } catch (SQLException | RuntimeException | Error failure) {
                try { coordination.rollback(); }
                catch (SQLException rollback) { failure.addSuppressed(rollback); }
                throw failure;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to coordinate bulk schema upgrade", failure);
        }
    }

    /** Migration-only inference: serving validation keeps its existing datasource contract. */
    private static String migrationOwnerRole(DataSource source) {
        requireOutsideSpringTransaction();
        try (Connection connection = source.getConnection()) {
            return readMigrationOwnerBackend(connection).role();
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to identify the bulk migration owner", failure);
        }
    }

    /** Read without changing caller state; a manual transaction is rejected before any SQL. */
    private static MigrationOwnerBackend readMigrationOwnerBackend(Connection connection) throws SQLException {
        require(connection.getAutoCommit(), "Bulk schema migration requires auto-commit owner connections");
        assertPostgreSql(connection);
        try (var statement = connection.createStatement()) {
            int existingTimeout = statement.getQueryTimeout();
            statement.setQueryTimeout(existingTimeout > 0 ? Math.min(existingTimeout, 10) : 10);
            try (var rows = statement.executeQuery(
                    "select pg_catalog.pg_backend_pid(), current_user, pg_catalog.current_database()")) {
                require(rows.next(), "Unable to identify the bulk migration owner backend");
                int pid = rows.getInt(1);
                String role = rows.getString(2);
                String database = rows.getString(3);
                require(pid > 0 && role != null && !role.isBlank() && database != null && !database.isBlank()
                        && !rows.next(), "Bulk migration owner backend identity is invalid");
                return new MigrationOwnerBackend(pid, role, database);
            }
        }
    }

    private record MigrationOwnerBackend(int pid, String role, String database) { }

    private static void completeCapacityReadBootstrap(DataSource source,
            BulkExecutionRoleConfiguration roles) {
        requireOutsideSpringTransaction();
        var manager = new org.springframework.jdbc.datasource.DataSourceTransactionManager(source);
        var transaction = new org.springframework.transaction.support.TransactionTemplate(manager);
        transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(10);
        transaction.execute(status -> new org.springframework.jdbc.core.JdbcTemplate(source).execute(
                (org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
                    assertPostgreSql(connection);
                    require(!connection.getAutoCommit(), "V18 capacity bootstrap requires an owner transaction");
                    try (var statement = connection.createStatement();
                            var rows = statement.executeQuery("select bootstrap_version, phase from praxis_bulk."
                                    + CAPACITY_READ_BOOTSTRAP_TABLE + " where bootstrap_version=18 for update")) {
                        require(rows.next() && rows.getInt(1) == 18, "V18 capacity bootstrap row is absent");
                        String phase = rows.getString(2);
                        require(!rows.next(), "V18 capacity bootstrap row is ambiguous");
                        if ("COMPLETE".equals(phase)) {
                            validateCapacityInstallationCatalog(connection, roles, "COMPLETE", true);
                            return null;
                        }
                        require("PENDING".equals(phase), "V18 capacity bootstrap phase changed");
                    }
                    validateCapacityInstallationCatalog(connection, roles, "PENDING", false);
                    require(queryCount(connection, "select count(*) from praxis_bulk."
                                    + CAPACITY_MARKER_TABLE) == 0
                                    && queryCount(connection, "select count(*) from praxis_bulk."
                                    + CAPACITY_INSTALLATION_TABLE) == 0,
                            "V18 pending capacity bootstrap already has a physical identity or right");
                    String owner = roles.expectedSchemaOwnerRole();
                    try (var statement = connection.createStatement();
                            var rows = statement.executeQuery("select current_user")) {
                        require(rows.next() && owner.equals(rows.getString(1)) && !rows.next(),
                                "V18 bootstrap requires its explicit schema owner credential");
                    }
                    for (String role : new java.util.TreeSet<>(roles.runtimeGranteeRoles())) {
                        String quoted = "\"" + role.replace("\"", "\"\"") + "\"";
                        try (var statement = connection.createStatement()) {
                            statement.execute("grant select on praxis_bulk." + CAPACITY_MARKER_TABLE
                                    + ", praxis_bulk." + CAPACITY_INSTALLATION_TABLE + " to " + quoted);
                        }
                    }
                    validateCapacityInstallationCatalog(connection, roles, "PENDING", true);
                    try (var update = connection.prepareStatement("update praxis_bulk."
                            + CAPACITY_READ_BOOTSTRAP_TABLE
                            + " set phase='COMPLETE' where bootstrap_version=18 and phase='PENDING'")) {
                        require(update.executeUpdate() == 1, "V18 capacity bootstrap completion lost");
                    }
                    validateCapacityInstallationCatalog(connection, roles, "COMPLETE", true);
                    return null;
                }));
    }

    private static void completeCapacityOccupancyBootstrap(DataSource source, BulkExecutionRoleConfiguration roles) {
        requireOutsideSpringTransaction();
        var manager = new org.springframework.jdbc.datasource.DataSourceTransactionManager(source);
        var transaction = new org.springframework.transaction.support.TransactionTemplate(manager);
        transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(10);
        transaction.execute(status -> new org.springframework.jdbc.core.JdbcTemplate(source).execute(
                (org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
                    assertPostgreSql(connection);
                    require(!connection.getAutoCommit(), "V19 occupancy bootstrap requires an owner transaction");
                    try (var statement = connection.createStatement(); var rows = statement.executeQuery("select current_user")) {
                        require(rows.next() && roles.expectedSchemaOwnerRole().equals(rows.getString(1)) && !rows.next(),
                                "V19 bootstrap requires explicit owner credential");
                    }
                    // Deparse against the canonical catalog scope; the owned transaction restores
                    // the caller's path on commit or rollback, including an aborted SQL transaction.
                    setCatalogSearchPath(connection, "pg_catalog", true);
                    BulkCapacityOccupancyCatalog.bootstrap(connection, roles);
                    return null;
                }));
    }

    private static int queryCount(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            if (!rows.next()) throw new IllegalStateException("Capacity bootstrap count unavailable");
            int result = rows.getInt(1);
            if (rows.next()) throw new IllegalStateException("Capacity bootstrap count ambiguous");
            return result;
        }
    }

    private static List<BulkOperationControlIdentity> canonicalControlIdentities(
            List<BulkOperationControlIdentity> operations, Map<String, String> deployments) {
        Objects.requireNonNull(operations, "operations");
        var unique = new java.util.TreeSet<>(java.util.Comparator
                .comparing(BulkOperationControlIdentity::namespaceId)
                .thenComparing(BulkOperationControlIdentity::confirmationOperationId));
        for (BulkOperationControlIdentity identity : operations) {
            Objects.requireNonNull(identity, "operation control identity");
            if (!deployments.containsKey(identity.namespaceId())) {
                throw new IllegalArgumentException("Every operation control identity requires an explicit namespace binding");
            }
            if (!unique.add(identity)) {
                throw new IllegalArgumentException("Duplicate operation control identity");
            }
        }
        return List.copyOf(unique);
    }

    private static Map<String, String> canonicalDeploymentMap(Map<String, String> mapping) {
        Objects.requireNonNull(mapping, "namespaceToDeploymentId");
        var copy = new LinkedHashMap<String, String>();
        mapping.forEach((namespace, deployment) -> {
            requireCanonicalIdentity(namespace, "namespaceId");
            requireCanonicalIdentity(deployment, "deploymentId");
            copy.put(namespace, deployment);
        });
        return Map.copyOf(copy);
    }

    private static void requireCanonicalIdentity(String value, String name) {
        if (value == null || value.isBlank() || !value.equals(value.strip())
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " must be canonical nonblank text");
        }
    }

    /** Flyway DDL is complete before this retryable, all-or-nothing data bootstrap. */
    private static void initializeGovernedLifecycle(DataSource dataSource, Map<String, String> deployments,
            List<BulkOperationControlIdentity> operations, BulkExecutionRoleConfiguration roles) {
        try (Connection connection = dataSource.getConnection()) {
            assertPostgreSql(connection);
            boolean originalAutoCommit = connection.getAutoCommit();
            String originalSearchPath;
            try (var statement = connection.createStatement();
                 var rows = statement.executeQuery("select current_setting('search_path')")) {
                require(rows.next(), "Unable to read current PostgreSQL search_path");
                originalSearchPath = rows.getString(1);
            }
            try {
                connection.setAutoCommit(false);
                setCatalogSearchPath(connection, "pg_catalog");
                // Flyway's history lock ends before bootstrap. This transaction lock serializes
                // concurrent migrators even on a brand-new schema with no bucket rows yet.
                try (var statement = connection.createStatement()) {
                    statement.execute("select pg_advisory_xact_lock(1347574124, 5)");
                }
                // Flyway's migration count also includes future versions; only this durable
                // V8 phase may authorize the one-time manifest ACL grant.
                boolean pendingManifestBootstrap = lockManifestBootstrap(connection);
                boolean pendingPreviewBootstrap = lockPreviewBootstrap(connection);
                boolean pendingIntegrityBootstrap = lockPreviewIntegrityBootstrap(connection);
                boolean pendingReaderBootstrap = lockPreviewReaderBootstrap(connection);
                boolean pendingAtomicBootstrap = lockAtomicBootstrap(connection);
                lockCapacityBootstrapMarkers(connection);
                int historyVersion = currentHistoryVersion(connection);
                require(historyVersion == 19 || historyVersion == 20, "Owner bootstrap requires V19 or V20");
                validateProtectedCatalog(connection, roles, historyVersion, 0, historyVersion == 19, deployments);
                validateAdmissionRows(connection);
                validateEvidenceBinding(connection);
                validateAtomicRows(connection);
                bootstrapLifecycle(connection, deployments, operations);
                if (pendingManifestBootstrap) BulkOrdinalManifest.backfillAndValidate(connection);
                else BulkOrdinalManifest.validateAll(connection);
                if (pendingManifestBootstrap) provisionManifestRuntimeGrants(connection, roles);
                if (pendingPreviewBootstrap) provisionPreviewRuntimeGrants(connection, roles);
                if (pendingIntegrityBootstrap) provisionPreviewIntegrityRuntimeGrants(connection, roles);
                if (pendingReaderBootstrap) provisionPreviewReaderRuntimeGrants(connection, roles);
                if (pendingAtomicBootstrap) provisionAtomicRuntimeGrants(connection, roles);
                validateLifecycleRows(connection);
                if (pendingManifestBootstrap) completeManifestBootstrap(connection);
                if (pendingPreviewBootstrap) completePreviewBootstrap(connection);
                // A wrong role set must roll back the PENDING -> COMPLETE transition and
                // its grants. Do not defer ACL attestation until after bootstrap commits.
                validateV5RolesAndPrivileges(connection, roles);
                validateV5Functions(connection, roles);
                validateOpenApiPublicationCatalog(connection);
                validateManifestCatalog(connection, roles);
                validatePreviewCatalog(connection, roles);
                BulkPreviewStorage.validateAll(connection);
                if (pendingIntegrityBootstrap) BulkPreviewItemIntegrity.backfillAndValidate(connection);
                else BulkPreviewItemIntegrity.validateAll(connection);
                validatePreviewIntegrityCatalog(connection, roles,
                        pendingIntegrityBootstrap ? "PENDING" : "COMPLETE");
                if (pendingIntegrityBootstrap) completePreviewIntegrityBootstrap(connection);
                validatePreviewIntegrityCatalog(connection, roles, "COMPLETE");
                validatePreviewReaderCatalog(connection, pendingReaderBootstrap ? "PENDING" : "COMPLETE");
                if (pendingReaderBootstrap) completePreviewReaderBootstrap(connection);
                validatePreviewReaderCatalog(connection, "COMPLETE");
                validateAtomicBootstrap(connection, pendingAtomicBootstrap ? "PENDING" : "COMPLETE", roles);
                if (pendingAtomicBootstrap) completeAtomicBootstrap(connection);
                validateAtomicBootstrap(connection, "COMPLETE", roles);
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                try { connection.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
                throw failure;
            } finally {
                try { setCatalogSearchPath(connection, originalSearchPath); }
                finally { connection.setAutoCommit(originalAutoCommit); }
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to initialize governed bulk lifecycle", failure);
        }
    }

    /**
     * Capacity bootstraps run in separate owner transactions after this initializer releases
     * its advisory fence. Pin their phase and ACL snapshot before READ_COMMITTED attestation:
     * a concurrent grant-before-COMPLETE transaction must commit or roll back first.
     * Keep ascending latch order, and never add the advisory fence to those separate transactions.
     */
    private static void lockCapacityBootstrapMarkers(Connection connection) throws SQLException {
        for (var marker : List.of(Map.entry(CAPACITY_READ_BOOTSTRAP_TABLE, 18),
                Map.entry(BulkCapacityOccupancyCatalog.BOOTSTRAP, 19))) {
            try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                    "select bootstrap_version, phase from praxis_bulk." + marker.getKey() + " for update")) {
                require(rows.next() && rows.getInt(1) == marker.getValue(),
                        "Capacity bootstrap marker version differs");
                require(Set.of("PENDING", "COMPLETE").contains(rows.getString(2)) && !rows.next(),
                        "Capacity bootstrap marker phase or cardinality differs");
            }
        }
    }

    private static boolean lockManifestBootstrap(Connection connection) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
                select bootstrap_version, phase from praxis_bulk.praxis_bulk_manifest_bootstrap for update
                """)) {
            require(rows.next() && rows.getInt(1) == 8, "V8 manifest bootstrap marker is missing");
            String phase = rows.getString(2);
            require(!rows.next() && ("PENDING".equals(phase) || "COMPLETE".equals(phase)),
                    "V8 manifest bootstrap marker differs");
            return "PENDING".equals(phase);
        }
    }

    private static void completeManifestBootstrap(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            require(statement.executeUpdate("""
                    update praxis_bulk.praxis_bulk_manifest_bootstrap set phase='COMPLETE'
                    where bootstrap_version=8 and phase='PENDING'
                    """) == 1, "V8 manifest bootstrap transition failed");
        }
    }

    private static void provisionManifestRuntimeGrants(Connection connection, BulkExecutionRoleConfiguration roles)
            throws SQLException {
        for (String role : roles.runtimeGranteeRoles()) {
            require(tableRolePrivileges(connection, EVALUATION_TABLE, role).equals(Set.of("T:SELECT", "T:INSERT")),
                    "manifest upgrade requires an existing exact evaluation runtime grant: " + role);
            // Only the new V8 relation is provisioned. A failed backfill rolls this grant back
            // with the bootstrap transaction; all other runtime ACLs remain host-owned.
            try (var statement = connection.createStatement()) {
                statement.execute("grant select, insert on praxis_bulk.praxis_bulk_target_manifest to \""
                        + role.replace("\"", "\"\"") + "\"");
            }
        }
    }

    private static boolean lockPreviewBootstrap(Connection connection) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
                select bootstrap_version, phase from praxis_bulk.praxis_bulk_preview_bootstrap for update
                """)) {
            require(rows.next() && rows.getInt(1) == 9, "V9 preview bootstrap marker is missing");
            String phase = rows.getString(2);
            require(!rows.next() && ("PENDING".equals(phase) || "COMPLETE".equals(phase)),
                    "V9 preview bootstrap marker differs");
            return "PENDING".equals(phase);
        }
    }

    private static void completePreviewBootstrap(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            require(statement.executeUpdate("""
                    update praxis_bulk.praxis_bulk_preview_bootstrap set phase='COMPLETE'
                    where bootstrap_version=9 and phase='PENDING'
                    """) == 1, "V9 preview bootstrap transition failed");
        }
    }

    private static void provisionPreviewRuntimeGrants(Connection connection, BulkExecutionRoleConfiguration roles)
            throws SQLException {
        for (String role : roles.runtimeGranteeRoles()) {
            require(tableRolePrivileges(connection, EVALUATION_TABLE, role).equals(Set.of("T:SELECT", "T:INSERT")),
                    "preview upgrade requires an existing exact evaluation runtime grant: " + role);
            try (var statement = connection.createStatement()) {
                for (String table : List.of(PREVIEW_STATE_TABLE, TARGET_PREVIEW_TABLE))
                    statement.execute("grant select, insert on praxis_bulk." + table + " to \""
                            + role.replace("\"", "\"\"") + "\"");
            }
        }
    }

    private static boolean lockPreviewIntegrityBootstrap(Connection connection) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
                select bootstrap_version, phase
                  from praxis_bulk.praxis_bulk_preview_integrity_bootstrap for update
                """)) {
            require(rows.next() && rows.getInt(1) == 11, "V11 integrity bootstrap marker is missing");
            String phase = rows.getString(2);
            require(!rows.next() && ("PENDING".equals(phase) || "COMPLETE".equals(phase)),
                    "V11 integrity bootstrap marker differs");
            return "PENDING".equals(phase);
        }
    }

    private static void completePreviewIntegrityBootstrap(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            require(statement.executeUpdate("""
                    update praxis_bulk.praxis_bulk_preview_integrity_bootstrap set phase='COMPLETE'
                    where bootstrap_version=11 and phase='PENDING'
                    """) == 1, "V11 integrity bootstrap transition failed");
        }
    }

    private static void provisionPreviewIntegrityRuntimeGrants(Connection connection,
            BulkExecutionRoleConfiguration roles) throws SQLException {
        for (String role : roles.runtimeGranteeRoles()) {
            require(tableRolePrivileges(connection, TARGET_PREVIEW_TABLE, role).equals(Set.of("T:SELECT", "T:INSERT")),
                    "integrity upgrade requires an existing exact preview runtime grant: " + role);
            try (var statement = connection.createStatement()) {
                statement.execute("grant select, insert on praxis_bulk." + PREVIEW_INTEGRITY_TABLE + " to \""
                        + role.replace("\"", "\"\"") + "\"");
            }
        }
    }

    private static boolean lockPreviewReaderBootstrap(Connection connection) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
                select bootstrap_version, phase
                  from praxis_bulk.praxis_bulk_preview_reader_bootstrap for update
                """)) {
            require(rows.next() && rows.getInt(1) == 12, "V12 reader bootstrap marker is missing");
            String phase = rows.getString(2);
            require(!rows.next() && ("PENDING".equals(phase) || "COMPLETE".equals(phase)),
                    "V12 reader bootstrap marker differs");
            return "PENDING".equals(phase);
        }
    }

    private static boolean lockAtomicBootstrap(Connection connection) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
                select bootstrap_version,phase from praxis_bulk.praxis_bulk_atomic_bootstrap for update
                """)) {
            require(rows.next() && rows.getInt(1) == 16, "V16 atomic bootstrap marker is missing");
            String phase = rows.getString(2);
            require(!rows.next() && ("PENDING".equals(phase) || "COMPLETE".equals(phase)),
                    "V16 atomic bootstrap marker differs");
            return "PENDING".equals(phase);
        }
    }

    private static void provisionAtomicRuntimeGrants(Connection connection,
            BulkExecutionRoleConfiguration roles) throws SQLException {
        for (String role : roles.runtimeGranteeRoles()) {
            require(tableRolePrivileges(connection, RECEIPT_TABLE, role)
                            .equals(Set.of("T:SELECT", "T:INSERT"))
                            && tableRolePrivileges(connection, ADMISSION_TABLE, role)
                            .equals(Set.of("T:SELECT", "T:INSERT")),
                    "atomic upgrade requires an existing exact execution runtime grant: " + role);
            String quotedRole = "\"" + role.replace("\"", "\"\"") + "\"";
            for (String table : ATOMIC_RUNTIME_TABLES) {
                try (var statement = connection.createStatement()) {
                    statement.execute("grant select,insert on praxis_bulk." + table + " to " + quotedRole);
                }
            }
            try (var statement = connection.createStatement()) {
                statement.execute("grant execute on function praxis_bulk.atomic_evidence_complete(uuid,integer) to "
                        + quotedRole);
            }
        }
    }

    private static void validateAtomicBootstrap(Connection connection, String expectedPhase,
            BulkExecutionRoleConfiguration roles) throws SQLException {
        validateAtomicBootstrap(connection, expectedPhase, roles, false);
    }

    private static void validateAtomicBootstrap(Connection connection, String expectedPhase,
            BulkExecutionRoleConfiguration roles, boolean beforeGrant) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
                select bootstrap_version,phase from praxis_bulk.praxis_bulk_atomic_bootstrap
                """)) {
            require(rows.next() && rows.getInt(1) == 16 && expectedPhase.equals(rows.getString(2))
                    && !rows.next(), "V16 atomic bootstrap state differs");
        }
        for (String table : ATOMIC_RUNTIME_TABLES) {
            for (String role : roles.runtimeGranteeRoles())
                require(tableRolePrivileges(connection, table, role).equals(beforeGrant ? bootstrapTableGrants(expectedPhase) : Set.of("T:SELECT", "T:INSERT")),
                        "V16 runtime grants differ: " + role + " " + table);
            require(tableRolePrivileges(connection, table, "praxis_bulk_retention_owner")
                            .equals(Set.of("T:SELECT", "T:DELETE")),
                    "V16 retention grants differ: " + table);
            require(tableRolePrivileges(connection, table, "PUBLIC").isEmpty(),
                    "V16 table cannot grant PUBLIC: " + table);
        }
        require(tableRolePrivileges(connection, ATOMIC_BOOTSTRAP_TABLE, "PUBLIC").isEmpty(),
                "V16 bootstrap marker cannot grant PUBLIC");
        try (var statement = connection.prepareStatement("""
                select count(*) from (
                    select acl.grantee,c.relowner
                    from pg_class c,
                         lateral aclexplode(coalesce(c.relacl,acldefault('r',c.relowner))) acl
                    where c.oid=?::regclass
                    union all
                    select acl.grantee,c.relowner
                    from pg_class c join pg_attribute a on a.attrelid=c.oid,
                         lateral aclexplode(a.attacl) acl
                    where c.oid=?::regclass and a.attnum>0 and not a.attisdropped
                      and a.attacl is not null
                ) grants where grantee<>relowner
                """)) {
            statement.setString(1, SCHEMA + "." + ATOMIC_BOOTSTRAP_TABLE);
            statement.setString(2, SCHEMA + "." + ATOMIC_BOOTSTRAP_TABLE);
            try (var rows = statement.executeQuery()) {
                require(rows.next() && rows.getLong(1) == 0 && !rows.next(),
                        "V16 bootstrap marker must remain owner-only");
            }
        }
    }

    private static void completeAtomicBootstrap(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            require(statement.executeUpdate("""
                    update praxis_bulk.praxis_bulk_atomic_bootstrap set phase='COMPLETE'
                     where bootstrap_version=16 and phase='PENDING'
                    """) == 1, "V16 atomic bootstrap transition failed");
        }
    }

    private static void provisionPreviewReaderRuntimeGrants(Connection connection,
            BulkExecutionRoleConfiguration roles) throws SQLException {
        for (String role : roles.runtimeGranteeRoles()) {
            require(tableRolePrivileges(connection, PREVIEW_INTEGRITY_TABLE, role)
                            .equals(Set.of("T:SELECT", "T:INSERT")),
                    "reader upgrade requires an existing exact leaf runtime grant: " + role);
            try (var statement = connection.createStatement()) {
                statement.execute("grant execute on function praxis_bulk.assert_preview_integrity_complete() to \""
                        + role.replace("\"", "\"\"") + "\"");
            }
        }
    }

    private static void completePreviewReaderBootstrap(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            require(statement.executeUpdate("""
                    update praxis_bulk.praxis_bulk_preview_reader_bootstrap set phase='COMPLETE'
                    where bootstrap_version=12 and phase='PENDING'
                    """) == 1, "V12 reader bootstrap transition failed");
        }
    }

    private static void bootstrapLifecycle(Connection connection, Map<String, String> deployments,
            List<BulkOperationControlIdentity> operations) throws SQLException {
        Set<String> historicalNamespaces = new LinkedHashSet<>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
                select namespace_id from praxis_bulk.praxis_bulk_proposal
                union select namespace_id from praxis_bulk.praxis_bulk_execution
                """)) {
            while (rows.next()) historicalNamespaces.add(rows.getString(1));
        }
        require(deployments.keySet().containsAll(historicalNamespaces),
                "Explicit deployment mapping does not cover every historical bulk namespace");
        for (var entry : deployments.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            try (var statement = connection.prepareStatement("""
                    insert into praxis_bulk.praxis_bulk_namespace_binding(namespace_id, deployment_id, bound_at)
                    values (?, ?, clock_timestamp()) on conflict (namespace_id) do nothing
                    """)) {
                statement.setString(1, entry.getKey());
                statement.setString(2, entry.getValue());
                statement.executeUpdate();
            }
        }
        Map<String, String> bound = readNamespaceBindings(connection);
        require(bound.equals(deployments), "Bulk namespace deployment binding differs from explicit map");

        // Create absent bucket identities without updating existing quota rows. Under V17,
        // ON CONFLICT may wait for an uncommitted quota MVCC touch before the global locks;
        // the active bucket FOR UPDATE locks remain after global/operation locks below.
        for (String deployment : deployments.values().stream().distinct().sorted().toList()) {
            try (var statement = connection.prepareStatement("""
                    insert into praxis_bulk.praxis_bulk_deployment_bucket(deployment_id)
                    values (?) on conflict (deployment_id) do nothing
                    """)) {
                statement.setString(1, deployment);
                statement.executeUpdate();
            }
        }
        // Provision deny-only rows without changing an existing global publication. Acquire
        // namespace -> global -> operation controls -> buckets, including bootstrap retries.
        try (var statement = connection.createStatement()) {
            statement.executeQuery("""
                    select namespace_id from praxis_bulk.praxis_bulk_namespace_binding
                    order by namespace_id for share
                    """).close();
            statement.executeUpdate("""
                    insert into praxis_bulk.praxis_bulk_openapi_publication
                        (deployment_id, state, generation, document_digest, updated_at)
                    select deployment_id, 'UNCOMPOSED', 0, null, clock_timestamp()
                      from (select distinct deployment_id from praxis_bulk.praxis_bulk_namespace_binding) b
                      order by deployment_id
                    on conflict (deployment_id) do nothing
                    """);
            statement.executeQuery("""
                    select deployment_id from praxis_bulk.praxis_bulk_openapi_publication
                    order by deployment_id for share
                    """).close();
        }

        try (var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    insert into praxis_bulk.praxis_bulk_operation_control
                        (namespace_id, operation_id, state, generation, descriptor_fingerprint,
                         structural_revision, updated_at)
                    select scope.namespace_id, scope.operation_id, 'UNCOMPOSED', 0, null, null, clock_timestamp()
                    from (select namespace_id, operation_id from praxis_bulk.praxis_bulk_proposal
                          union select namespace_id, operation_id from praxis_bulk.praxis_bulk_execution) scope
                    on conflict (namespace_id, operation_id) do nothing
            """);
        }
        for (BulkOperationControlIdentity identity : operations) {
            try (var statement = connection.prepareStatement("""
                    insert into praxis_bulk.praxis_bulk_operation_control
                        (namespace_id, operation_id, state, generation, descriptor_fingerprint,
                         structural_revision, updated_at)
                    values (?, ?, 'UNCOMPOSED', 0, null, null, clock_timestamp())
                    on conflict (namespace_id, operation_id) do nothing
                    """)) {
                statement.setString(1, identity.namespaceId());
                statement.setString(2, identity.confirmationOperationId());
                statement.executeUpdate();
            }
        }

        // A bootstrap retry may run while already-published operations are active.
        // Namespace/global SHARE locks are already held; controls precede quota buckets.
        try (var statement = connection.createStatement()) {
            statement.executeQuery("""
                    select namespace_id, operation_id from praxis_bulk.praxis_bulk_operation_control
                    order by namespace_id, operation_id for share
                    """).close();
            statement.executeQuery("""
                    select deployment_id from praxis_bulk.praxis_bulk_deployment_bucket
                    order by deployment_id for update
                    """).close();
        }
        var proposals = readProposals(connection);
        var executions = readExecutions(connection);
        for (ProposalRow proposal : proposals.values()) {
            String deployment = bound.get(proposal.namespaceId());
            require(deployment != null, "Bulk proposal has no deployment binding");
            var scope = scopeDigests(deployment, proposal.namespaceId(), proposal.subjectId(),
                    proposal.resourceKey(), proposal.operationId());
            insertSubjectBucket(connection, deployment, scope.subjectDigest());
        }
        for (ExecutionRow execution : executions.values()) {
            ProposalRow proposal = proposals.get(execution.proposalId());
            require(proposal != null && sameScope(proposal, execution),
                    "Bulk execution scope differs from its proposal");
            String deployment = bound.get(execution.namespaceId());
            require(deployment != null, "Bulk execution has no deployment binding");
            var scope = scopeDigests(deployment, execution.namespaceId(), execution.subjectId(),
                    execution.resourceKey(), execution.operationId());
            insertSubjectBucket(connection, deployment, scope.subjectDigest());
        }
        // All mutating paths use binding/control -> deployment bucket -> subject bucket
        // -> proposal -> execution. Subject rows are locked only after deployment rows.
        try (var statement = connection.createStatement()) {
            statement.executeQuery("""
                    select deployment_id, subject_scope_digest_version, subject_scope_digest
                    from praxis_bulk.praxis_bulk_subject_bucket
                    order by deployment_id, subject_scope_digest_version, subject_scope_digest for update
                    """).close();
        }
        for (ProposalRow proposal : proposals.values()) {
            ExecutionRow execution = executions.values().stream()
                    .filter(row -> row.proposalId().equals(proposal.id())).findFirst().orElse(null);
            String deployment = bound.get(proposal.namespaceId());
            var scope = scopeDigests(deployment, proposal.namespaceId(), proposal.subjectId(),
                    proposal.resourceKey(), proposal.operationId());
            insertAllocation(connection, proposalAllocation(proposal, execution, deployment, scope));
        }
        for (ExecutionRow execution : executions.values()) {
            String deployment = bound.get(execution.namespaceId());
            var scope = scopeDigests(deployment, execution.namespaceId(), execution.subjectId(),
                    execution.resourceKey(), execution.operationId());
            insertAllocation(connection, executionAllocation(execution, deployment, scope));
        }
        requireQuotaWithinLimits(connection);
    }

    private static Map<String, String> readNamespaceBindings(Connection connection) throws SQLException {
        var result = new LinkedHashMap<String, String>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
                select namespace_id, deployment_id from praxis_bulk.praxis_bulk_namespace_binding
                """)) {
            while (rows.next()) result.put(rows.getString(1), rows.getString(2));
        }
        return result;
    }

    private static void insertSubjectBucket(Connection connection, String deployment, String digest) throws SQLException {
        try (var statement = connection.prepareStatement("""
                insert into praxis_bulk.praxis_bulk_subject_bucket
                    (deployment_id, subject_scope_digest_version, subject_scope_digest)
                values (?, ?, ?) on conflict do nothing
                """)) {
            statement.setString(1, deployment);
            statement.setInt(2, BulkScopeDigests.VERSION);
            statement.setString(3, digest);
            statement.executeUpdate();
        }
    }

    private static ScopeDigests scopeDigests(String deployment, String namespace, String subject,
            String resource, String operation) {
        requireCanonicalIdentity(subject, "canonicalSubjectId");
        return new ScopeDigests(BulkScopeDigests.subjectQuotaDigest(deployment, subject),
                BulkScopeDigests.authorizationScopeDigest(namespace, subject, resource, operation));
    }

    private static Map<UUID, ProposalRow> readProposals(Connection connection) throws SQLException {
        var proposals = new LinkedHashMap<UUID, ProposalRow>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
                select proposal_id, namespace_id, subject_id, resource_key, operation_id, created_at
                from praxis_bulk.praxis_bulk_proposal order by proposal_id
                """)) {
            while (rows.next()) {
                var proposal = new ProposalRow(rows.getObject(1, UUID.class), rows.getString(2),
                        rows.getString(3), rows.getString(4), rows.getString(5),
                        rows.getObject(6, OffsetDateTime.class).toInstant());
                proposals.put(proposal.id(), proposal);
            }
        }
        return proposals;
    }

    private static Map<UUID, ExecutionRow> readExecutions(Connection connection) throws SQLException {
        var executions = new LinkedHashMap<UUID, ExecutionRow>();
        String modeProjection = BulkCapacityOccupancyCatalog.installed(connection)
                ? "e.execution_mode" : "'SYNC'::text";
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
                select execution_id, proposal_id, namespace_id, subject_id, resource_key, operation_id,
                       status, created_at, terminal_at, next_ordinal, target_count, owner_epoch,
                       active_attempt_id, terminal_reason_code,
                       (select count(*) from praxis_bulk.praxis_bulk_admission a
                        where a.execution_id = e.execution_id) as admission_count, %s
                from praxis_bulk.praxis_bulk_execution e order by execution_id
                """.formatted(modeProjection))) {
            while (rows.next()) {
                OffsetDateTime terminal = rows.getObject(9, OffsetDateTime.class);
                var execution = new ExecutionRow(rows.getObject(1, UUID.class), rows.getObject(2, UUID.class),
                        rows.getString(3), rows.getString(4), rows.getString(5), rows.getString(6),
                        rows.getString(7), rows.getObject(8, OffsetDateTime.class).toInstant(),
                        terminal == null ? null : terminal.toInstant(), rows.getInt(10), rows.getInt(11),
                        rows.getLong(12), rows.getObject(13, UUID.class), rows.getString(14), rows.getLong(15), rows.getString(16));
                executions.put(execution.id(), execution);
            }
        }
        return executions;
    }

    private static boolean sameScope(ProposalRow proposal, ExecutionRow execution) {
        return proposal.namespaceId().equals(execution.namespaceId())
                && proposal.subjectId().equals(execution.subjectId())
                && proposal.resourceKey().equals(execution.resourceKey())
                && proposal.operationId().equals(execution.operationId());
    }

    private static AllocationRow proposalAllocation(ProposalRow proposal, ExecutionRow execution,
            String deployment, ScopeDigests scope) {
        require(execution == null || sameScope(proposal, execution),
                "Consumed bulk proposal has a mismatched execution");
        return new AllocationRow("PROPOSAL_PENDING", proposal.id(), null, proposal.namespaceId(), deployment,
                scope.subjectDigest(), scope.authorizationDigest(), execution == null ? "PENDING" : "CONSUMED",
                proposal.createdAt(), null, null);
    }

    private static AllocationRow executionAllocation(ExecutionRow execution, String deployment, ScopeDigests scope) {
        require(Set.of("SYNC", "ASYNC").contains(execution.executionMode())
                        && (!execution.status().equals("QUEUED") || execution.executionMode().equals("ASYNC")),
                "Bulk allocation execution mode differs");
        boolean terminal = switch (execution.status()) {
            case "COMPLETED", "COMPLETED_WITH_ERRORS", "STOPPED" -> true;
            case "QUEUED", "RUNNING", "UNIT_IN_FLIGHT", "UNIT_COMMITTED_PENDING_ACK", "RECONCILIATION_REQUIRED" -> false;
            default -> throw new IllegalStateException("Unknown bulk execution status");
        };
        require(!terminal || (execution.terminalAt() != null && execution.activeAttemptId() == null
                        && execution.ownerEpoch() >= 1
                        && (execution.status().equals("STOPPED") == (execution.terminalReason() != null))
                        && (execution.status().equals("COMPLETED") == (execution.admissionCount() == 0)
                            || execution.status().equals("STOPPED"))),
                "Bulk terminal execution cannot be classified for quota release");
        return new AllocationRow("ASYNC".equals(execution.executionMode()) ? "EXECUTION_ASYNC" : "EXECUTION_ACTIVE", null, execution.id(), execution.namespaceId(), deployment,
                scope.subjectDigest(), scope.authorizationDigest(), terminal ? "RELEASED"
                        : execution.status().equals("QUEUED") ? "QUEUED" : "ACTIVE",
                execution.createdAt(), terminal ? execution.terminalAt() : null,
                terminal ? "TERMINAL_RECONCILED" : null);
    }

    private static void insertAllocation(Connection connection, AllocationRow allocation) throws SQLException {
        try (var statement = connection.prepareStatement("""
                insert into praxis_bulk.praxis_bulk_allocation
                    (allocation_id, namespace_id, deployment_id, subject_scope_digest_version,
                     subject_scope_digest, authorization_scope_digest_version,
                     authorization_scope_digest, kind, proposal_id, execution_id, state,
                     created_at, released_at, release_reason)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) on conflict do nothing
                """)) {
            statement.setObject(1, UUID.nameUUIDFromBytes(("praxis.bulk.allocation/1:" + allocation.kind() + ":"
                    + (allocation.proposalId() == null ? allocation.executionId() : allocation.proposalId()))
                    .getBytes(StandardCharsets.UTF_8)));
            statement.setString(2, allocation.namespaceId());
            statement.setString(3, allocation.deploymentId());
            statement.setInt(4, BulkScopeDigests.VERSION);
            statement.setString(5, allocation.subjectDigest());
            statement.setInt(6, BulkScopeDigests.VERSION);
            statement.setString(7, allocation.authorizationDigest());
            statement.setString(8, allocation.kind());
            statement.setObject(9, allocation.proposalId());
            statement.setObject(10, allocation.executionId());
            statement.setString(11, allocation.state());
            statement.setObject(12, OffsetDateTime.ofInstant(allocation.createdAt(), java.time.ZoneOffset.UTC));
            statement.setObject(13, allocation.releasedAt() == null ? null
                    : OffsetDateTime.ofInstant(allocation.releasedAt(), java.time.ZoneOffset.UTC));
            statement.setString(14, allocation.releaseReason());
            statement.executeUpdate();
        }
    }

    private static void requireQuotaWithinLimits(Connection connection) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
                select count(*) from (
                    select deployment_id from praxis_bulk.praxis_bulk_allocation
                    where kind='PROPOSAL_PENDING' and state='PENDING'
                    group by deployment_id having count(*) > 100
                    union all
                    select deployment_id from praxis_bulk.praxis_bulk_allocation
                    where kind='EXECUTION_ACTIVE' and state='ACTIVE'
                    group by deployment_id having count(*) > 80
                    union all
                    select deployment_id from praxis_bulk.praxis_bulk_allocation
                    where kind='PROPOSAL_PENDING' and state='PENDING'
                    group by deployment_id, subject_scope_digest_version, subject_scope_digest
                    having count(*) > 10
                ) exceeded
                """)) {
            require(rows.next() && rows.getLong(1) == 0 && !rows.next(),
                    "Historical bulk allocations exceed governed quotas");
        }
    }

    private static void validateLifecycleRows(Connection connection) throws SQLException {
        validateLifecycleRows(connection, Map.of(), false);
    }

    private static void validateLifecycleRows(Connection connection, Map<String, String> deployments,
            boolean pendingLegacyDerivation) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
                select count(*) from praxis_bulk.praxis_bulk_operation_control c
                join praxis_bulk.praxis_bulk_namespace_binding b on b.namespace_id=c.namespace_id
                left join praxis_bulk.praxis_bulk_openapi_publication p on p.deployment_id=b.deployment_id
                where c.state='READY' and (p.state is distinct from 'PUBLISHED'
                    or c.publication_generation is distinct from p.generation
                    or c.publication_document_digest is distinct from p.document_digest)
                """)) {
            require(rows.next() && rows.getLong(1)==0 && !rows.next(),
                    "READY operation publication tuple is not current");
        }

        Map<String, String> bindings = readNamespaceBindings(connection);
        var proposals = readProposals(connection);
        var executions = readExecutions(connection);
        if (pendingLegacyDerivation) {
            require(deployments.entrySet().containsAll(bindings.entrySet()),
                    "Historical namespace binding conflicts with explicit deployment configuration");
            // Only absent projections may be derived. Existing bindings remain immutable.
            bindings = deployments;
        }
        require(!bindings.isEmpty() || (proposals.isEmpty() && executions.isEmpty()),
                "Historical bulk rows lack deployment bindings");
        var executionByProposal = new LinkedHashMap<UUID, ExecutionRow>();
        for (ExecutionRow execution : executions.values()) {
            ProposalRow proposal = proposals.get(execution.proposalId());
            require(proposal != null && sameScope(proposal, execution),
                    "Bulk execution/proposal scope is inconsistent");
            require(executionByProposal.putIfAbsent(execution.proposalId(), execution) == null,
                    "Bulk proposal has more than one execution");
        }
        var expected = new LinkedHashMap<String, AllocationRow>();
        for (ProposalRow proposal : proposals.values()) {
            String deployment = bindings.get(proposal.namespaceId());
            require(deployment != null, "Bulk proposal namespace lacks deployment binding");
            ScopeDigests digests = scopeDigests(deployment, proposal.namespaceId(), proposal.subjectId(),
                    proposal.resourceKey(), proposal.operationId());
            AllocationRow allocation = proposalAllocation(proposal, executionByProposal.get(proposal.id()),
                    deployment, digests);
            expected.put("P:" + proposal.id(), allocation);
        }
        for (ExecutionRow execution : executions.values()) {
            String deployment = bindings.get(execution.namespaceId());
            require(deployment != null, "Bulk execution namespace lacks deployment binding");
            ScopeDigests digests = scopeDigests(deployment, execution.namespaceId(), execution.subjectId(),
                    execution.resourceKey(), execution.operationId());
            AllocationRow allocation = executionAllocation(execution, deployment, digests);
            expected.put("E:" + execution.id(), allocation);
        }
        var observed = new LinkedHashMap<String, AllocationRow>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
                select namespace_id, deployment_id, subject_scope_digest_version, subject_scope_digest,
                       authorization_scope_digest_version, authorization_scope_digest, kind,
                       proposal_id, execution_id, state, created_at, released_at, release_reason
                from praxis_bulk.praxis_bulk_allocation
                """)) {
            while (rows.next()) {
                require(rows.getInt(3) == BulkScopeDigests.VERSION
                                && rows.getInt(5) == BulkScopeDigests.VERSION,
                        "Unknown bulk allocation digest version");
                UUID proposalId = rows.getObject(8, UUID.class);
                UUID executionId = rows.getObject(9, UUID.class);
                OffsetDateTime released = rows.getObject(12, OffsetDateTime.class);
                var allocation = new AllocationRow(rows.getString(7), proposalId, executionId,
                        rows.getString(1), rows.getString(2), rows.getString(4), rows.getString(6),
                        rows.getString(10), rows.getObject(11, OffsetDateTime.class).toInstant(),
                        released == null ? null : released.toInstant(), rows.getString(13));
                String key = proposalId != null ? "P:" + proposalId : "E:" + executionId;
                require(observed.putIfAbsent(key, allocation) == null,
                        "Duplicate bulk allocation owner");
            }
        }
        require(pendingLegacyDerivation ? expected.keySet().containsAll(observed.keySet())
                        : observed.keySet().equals(expected.keySet()),
                "Bulk allocations do not cover exactly the retained proposals and executions");
        for (var entry : observed.entrySet()) {
            AllocationRow actual = entry.getValue();
            AllocationRow required = expected.get(entry.getKey());
            require(actual.kind().equals(required.kind())
                            && Objects.equals(actual.proposalId(), required.proposalId())
                            && Objects.equals(actual.executionId(), required.executionId())
                            && actual.namespaceId().equals(required.namespaceId())
                            && actual.deploymentId().equals(required.deploymentId())
                            && actual.subjectDigest().equals(required.subjectDigest())
                            && actual.authorizationDigest().equals(required.authorizationDigest())
                            && actual.createdAt().equals(required.createdAt())
                            && actual.state().equals(required.state())
                            && (actual.state().equals("RELEASED")
                                ? actual.releasedAt() != null && actual.releaseReason().equals("TERMINAL_RECONCILED")
                                  && !actual.releasedAt().isBefore(required.releasedAt())
                                : actual.releasedAt() == null && actual.releaseReason() == null),
                    "Bulk allocation differs from canonical scope or lifecycle evidence");
        }
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
                select count(*) from (
                    select namespace_id, operation_id from praxis_bulk.praxis_bulk_proposal
                    union select namespace_id, operation_id from praxis_bulk.praxis_bulk_execution
                ) historical left join praxis_bulk.praxis_bulk_operation_control c using(namespace_id, operation_id)
                where c.namespace_id is null
                """)) {
            require(rows.next() && (pendingLegacyDerivation || rows.getLong(1) == 0) && !rows.next(),
                    "Historical bulk operation lacks deny-only or validated control row");
        }
        if (pendingLegacyDerivation) validateProjectedLifecycleQuotas(expected.values());
        requireQuotaWithinLimits(connection);
    }

    /** Read-only quota check of the entire canonical derivation before inserting absent rows. */
    private static void validateProjectedLifecycleQuotas(java.util.Collection<AllocationRow> allocations) {
        var pendingByDeployment = new LinkedHashMap<String, Integer>();
        var pendingBySubject = new LinkedHashMap<List<String>, Integer>();
        var activeByDeployment = new LinkedHashMap<String, Integer>();
        for (AllocationRow allocation : allocations) {
            if ("PROPOSAL_PENDING".equals(allocation.kind()) && "PENDING".equals(allocation.state())) {
                require(pendingByDeployment.merge(allocation.deploymentId(), 1, Integer::sum) <= 100,
                        "Historical bulk allocations exceed governed deployment proposal quota");
                require(pendingBySubject.merge(List.of(allocation.deploymentId(), allocation.subjectDigest()),
                                1, Integer::sum) <= 10,
                        "Historical bulk allocations exceed governed subject proposal quota");
            } else if ("EXECUTION_ACTIVE".equals(allocation.kind()) && "ACTIVE".equals(allocation.state())) {
                require(activeByDeployment.merge(allocation.deploymentId(), 1, Integer::sum) <= 80,
                        "Historical bulk allocations exceed governed execution quota");
            }
        }
    }

    /**
     * Validates Flyway history/checksums and the durable PostgreSQL structure used for proposals.
     * This is stronger than migration-name validation: it rejects catalog drift that weakens the
     * immutable protected-input contract.
     */
    public static void validate(DataSource dataSource) {
        DataSource operationalDataSource = Objects.requireNonNull(dataSource, "dataSource");
        validate(operationalDataSource,
                BulkExecutionRoleConfiguration.none(currentDatabaseRole(operationalDataSource)));
    }

    /** Validates storage against the exact role identities configured by the host. */
    public static void validate(DataSource dataSource, BulkExecutionRoleConfiguration roles) {
        validateCurrent(dataSource, roles, 0);
    }

    private static void validateV17BeforeUpgrade(DataSource dataSource,
            BulkExecutionRoleConfiguration roles) {
        validateCurrent(dataSource, roles, 17);
    }

    /** Exact historical V18 serving catalog, or its owner-only zero-ACL read bootstrap. */
    private static void validateV18BeforeUpgrade(DataSource dataSource,
            BulkExecutionRoleConfiguration roles) {
        validateCurrent(dataSource, roles, 18);
    }

    private static void validateCurrent(DataSource dataSource, BulkExecutionRoleConfiguration roles,
            int historicalVersion) {
        validateCurrent(dataSource, roles, historicalVersion, false);
    }

    private static void validateOwnerBootstrapBeforeWrites(DataSource source,
            BulkExecutionRoleConfiguration roles, Map<String, String> deployments) {
        // Serialize dispatch and its exact historical attestation. Release this loan before
        // initializeGovernedLifecycle acquires the same key on its own connection.
        try (Connection coordination = source.getConnection()) {
            MigrationOwnerBackend owner = readMigrationOwnerBackend(coordination);
            require(roles.expectedSchemaOwnerRole().equals(owner.role()),
                    "Owner bootstrap requires its explicit owner credential");
            coordination.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            coordination.setAutoCommit(false);
            try {
                try (var statement = coordination.createStatement()) {
                    int existingTimeout = statement.getQueryTimeout();
                    statement.setQueryTimeout(existingTimeout > 0 ? Math.min(existingTimeout, 10) : 10);
                    statement.execute("select pg_advisory_xact_lock(1347574124, 5)");
                }
                int historyVersion = currentHistoryVersion(coordination);
                if (historyVersion == 19) validateCurrent(source, roles, 19, true, deployments);
                else {
                    require(historyVersion == 20, "Owner bootstrap requires V19 or V20");
                    validateCurrent(source, roles, 0, false);
                }
                coordination.commit();
            } catch (SQLException | RuntimeException | Error failure) {
                try { coordination.rollback(); }
                catch (SQLException rollback) { failure.addSuppressed(rollback); }
                throw failure;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to attest owner bootstrap predecessor", failure);
        }
    }

    private static void validateCurrent(DataSource dataSource, BulkExecutionRoleConfiguration roles,
            int historicalVersion, boolean ownerBootstrap) {
        validateCurrent(dataSource, roles, historicalVersion, ownerBootstrap, Map.of());
    }

    private static void validateCurrent(DataSource dataSource, BulkExecutionRoleConfiguration roles,
            int historicalVersion, boolean ownerBootstrap, Map<String, String> deployments) {
        requireOutsideSpringTransaction();
        DataSource operationalDataSource = Objects.requireNonNull(dataSource, "dataSource");
        BulkExecutionRoleConfiguration roleConfiguration = Objects.requireNonNull(roles, "roles");
        assertKnownDedicatedSchema(operationalDataSource);
        require(historicalVersion == 0 || historicalVersion == 17 || historicalVersion == 18 || historicalVersion == 19,
                "Unsupported historical bulk preflight");
        if (historicalVersion != 0) {
            Flyway.configure().dataSource(operationalDataSource)
                    .locations("classpath:db/praxis-bulk-migrations")
                    .schemas(SCHEMA).defaultSchema(SCHEMA).table(HISTORY_TABLE)
                    .target(org.flywaydb.core.api.MigrationVersion.fromVersion(Integer.toString(historicalVersion)))
                    .createSchemas(true).baselineOnMigrate(false).cleanDisabled(true)
                    .validateOnMigrate(true).load().validate();
        } else {
            flyway(operationalDataSource).validate();
        }
        try (Connection connection = operationalDataSource.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);
            String previousSearchPath;
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("select current_setting('search_path')")) {
                require(rows.next(), "Unable to read current PostgreSQL search_path");
                previousSearchPath = rows.getString(1);
            }
            try {
                setCatalogSearchPath(connection, "pg_catalog");
                assertPostgreSql(connection);
                int historyVersion = currentHistoryVersion(connection);
                boolean physicalV18 = isV18Installed(connection);
                require((historyVersion >= 18) == physicalV18,
                        "V18 history and physical installation disagree");
                if (historicalVersion != 0) require(historyVersion == historicalVersion,
                        "V" + historicalVersion + " preflight requires the exact historical version");
                validateProtectedCatalog(connection, roleConfiguration, historyVersion,
                        historicalVersion, ownerBootstrap && historyVersion == 19, deployments);
            } finally {
                setCatalogSearchPath(connection, previousSearchPath);
            }
            connection.commit();
        } catch (SQLException error) {
            throw new IllegalStateException("Unable to validate protected bulk proposal storage", error);
        }
    }

    /** Owner-only retry attestation; serving callers always require COMPLETE. */
    private static Map<String, String> ownerBootstrapPhases(Connection connection) throws SQLException {
        Map<String, Integer> markers = Map.of(
                MANIFEST_BOOTSTRAP_TABLE, 8, PREVIEW_BOOTSTRAP_TABLE, 9,
                PREVIEW_INTEGRITY_BOOTSTRAP_TABLE, 11, PREVIEW_READER_BOOTSTRAP_TABLE, 12,
                ATOMIC_BOOTSTRAP_TABLE, 16, CAPACITY_READ_BOOTSTRAP_TABLE, 18,
                BulkCapacityOccupancyCatalog.BOOTSTRAP, 19);
        var phases = new LinkedHashMap<String, String>();
        for (var marker : markers.entrySet()) {
            try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                    "select bootstrap_version, phase from praxis_bulk." + marker.getKey())) {
                require(rows.next() && rows.getInt(1) == marker.getValue(), "Owner bootstrap marker version differs");
                String phase = rows.getString(2);
                require(Set.of("PENDING", "COMPLETE").contains(phase) && !rows.next(),
                        "Owner bootstrap marker phase differs");
                phases.put(marker.getKey(), phase);
            }
        }
        requirePhaseDependency(phases, PREVIEW_BOOTSTRAP_TABLE, MANIFEST_BOOTSTRAP_TABLE);
        requirePhaseDependency(phases, PREVIEW_INTEGRITY_BOOTSTRAP_TABLE, PREVIEW_BOOTSTRAP_TABLE);
        requirePhaseDependency(phases, PREVIEW_READER_BOOTSTRAP_TABLE, PREVIEW_INTEGRITY_BOOTSTRAP_TABLE);
        requirePhaseDependency(phases, ATOMIC_BOOTSTRAP_TABLE, PREVIEW_READER_BOOTSTRAP_TABLE);
        requirePhaseDependency(phases, CAPACITY_READ_BOOTSTRAP_TABLE, ATOMIC_BOOTSTRAP_TABLE);
        requirePhaseDependency(phases, BulkCapacityOccupancyCatalog.BOOTSTRAP, CAPACITY_READ_BOOTSTRAP_TABLE);
        return Map.copyOf(phases);
    }

    private static void requirePhaseDependency(Map<String, String> phases, String marker, String prerequisite) {
        require(!"COMPLETE".equals(phase(phases, marker)) || "COMPLETE".equals(phase(phases, prerequisite)),
                "Owner bootstrap COMPLETE phase has an incomplete prerequisite");
    }

    private static String phase(Map<String, String> phases, String marker) {
        return phases.getOrDefault(marker, "COMPLETE");
    }

    private static Set<String> bootstrapTableGrants(String phase) {
        return "COMPLETE".equals(phase) ? Set.of("T:SELECT", "T:INSERT") : Set.of();
    }

    private static void validateProtectedCatalog(Connection connection,
            BulkExecutionRoleConfiguration roleConfiguration, int historyVersion,
            int historicalVersion, boolean ownerBootstrap, Map<String, String> deployments) throws SQLException {
        Map<String, String> phases = ownerBootstrap ? ownerBootstrapPhases(connection) : Map.of();
        boolean pendingLegacyDerivation = ownerBootstrap && historyVersion == 19
                && "PENDING".equals(phase(phases, MANIFEST_BOOTSTRAP_TABLE));
        validateProposalTable(connection);
        validateColumns(connection);
        validatePrimaryKey(connection);
        validateChecks(connection);
        validateProposalUnique(connection);
        validateImmutableUpdateTrigger(connection, PROPOSAL_TABLE, REJECTION_TRIGGER, REJECTION_FUNCTION,
                "praxis_bulk.praxis_bulk_proposal");
        validateEvaluationTable(connection);
        validateEvaluationColumns(connection);
        validateEvaluationPrimaryKey(connection);
        validateEvaluationForeignKey(connection);
        validateEvaluationChecks(connection);
        validateImmutableUpdateTrigger(connection, EVALUATION_TABLE, EVALUATION_REJECTION_TRIGGER,
                EVALUATION_REJECTION_FUNCTION, "praxis_bulk.praxis_bulk_evaluation");
        validateDurableExecution(connection);
        validateDurableAdmission(connection);
        validateAtomicCatalog(connection);
        validateAtomicBootstrap(connection, phase(phases, ATOMIC_BOOTSTRAP_TABLE), roleConfiguration, ownerBootstrap);
        validateGovernedLifecycleCatalog(connection, roleConfiguration, phases, pendingLegacyDerivation);
        validateManifestCatalog(connection, roleConfiguration, phase(phases, MANIFEST_BOOTSTRAP_TABLE));
        if ("COMPLETE".equals(phase(phases, MANIFEST_BOOTSTRAP_TABLE))) BulkOrdinalManifest.validateAll(connection);
        validatePreviewCatalog(connection, roleConfiguration, phase(phases, PREVIEW_BOOTSTRAP_TABLE));
        if ("COMPLETE".equals(phase(phases, PREVIEW_BOOTSTRAP_TABLE))) BulkPreviewStorage.validateAll(connection);
        validatePreviewIntegrityCatalog(connection, roleConfiguration, phase(phases, PREVIEW_INTEGRITY_BOOTSTRAP_TABLE), ownerBootstrap);
        validatePreviewReaderCatalog(connection, phase(phases, PREVIEW_READER_BOOTSTRAP_TABLE));
        if ("COMPLETE".equals(phase(phases, PREVIEW_INTEGRITY_BOOTSTRAP_TABLE))) BulkPreviewItemIntegrity.validateAll(connection);
        validateDescriptorFenceRows(connection);
        validateAdmissionRows(connection);
        validateEvidenceBinding(connection);
        validateAtomicRows(connection);
        validateLifecycleRows(connection, deployments, pendingLegacyDerivation);
        if (historicalVersion == 18) {
            String phase = capacityReadBootstrapPhase(connection);
            validateCapacityInstallationCatalog(connection, roleConfiguration, phase,
                    "COMPLETE".equals(phase));
            if ("PENDING".equals(phase))
                require(queryCount(connection, "select count(*) from praxis_bulk." + CAPACITY_MARKER_TABLE) == 0
                                && queryCount(connection, "select count(*) from praxis_bulk." + CAPACITY_INSTALLATION_TABLE) == 0,
                        "V18 pending capacity bootstrap already has a physical identity or right");
        } else if (isV18Installed(connection)) {
            String capacityPhase = phase(phases, CAPACITY_READ_BOOTSTRAP_TABLE);
            validateCapacityInstallationCatalog(connection, roleConfiguration, capacityPhase,
                    "COMPLETE".equals(capacityPhase), phases);
            if ("PENDING".equals(capacityPhase))
                require(queryCount(connection, "select count(*) from praxis_bulk." + CAPACITY_MARKER_TABLE) == 0
                                && queryCount(connection, "select count(*) from praxis_bulk." + CAPACITY_INSTALLATION_TABLE) == 0,
                        "V18 pending bootstrap already has a physical identity or right");
        }
        if (BulkCapacityOccupancyCatalog.installed(connection)) {
            require((historyVersion == 19 || historyVersion == 20)
                            && phase(phases, BulkCapacityOccupancyCatalog.BOOTSTRAP)
                                    .equals(BulkCapacityOccupancyCatalog.phase(connection)),
                    "V19 history/bootstrap disagree");
            BulkCapacityOccupancyCatalog.validate(connection, roleConfiguration,
                    "COMPLETE".equals(phase(phases, BulkCapacityOccupancyCatalog.BOOTSTRAP)));
        }
        validateWorkerQueueIndex(connection, roleConfiguration, historyVersion);
        validateOwnedSchema(connection);
    }

    private static Flyway flyway(DataSource dataSource) {
        return flyway(dataSource, 20);
    }

    private static Flyway flyway(DataSource dataSource, int targetVersion) {
        return flyway(dataSource, targetVersion, new Callback[0]);
    }

    private static Flyway flyway(DataSource dataSource, int targetVersion, Callback... callbacks) {
        return Flyway.configure().callbacks(callbacks).target(org.flywaydb.core.api.MigrationVersion.fromVersion(
                        Integer.toString(targetVersion)))
                .dataSource(dataSource)
                .locations("classpath:db/praxis-bulk-migrations")
                .schemas(SCHEMA)
                .defaultSchema(SCHEMA)
                .table(HISTORY_TABLE)
                .createSchemas(true)
                .baselineOnMigrate(false)
                .cleanDisabled(true)
                .validateOnMigrate(true)
                .load();
    }

    /**
     * Invocation-local provenance for the actual creation of the publication table. It never
     * runs for an already applied V14, and cannot repair an absent identity on a later upgrade.
     * DDL and seed use the migration connection; Flyway history may use another connection.
     */
    private static final class PublicationCreationCallback implements Callback {
        private static final String SCRIPT = "V14__bulk_openapi_publication.sql";
        private final BulkExecutionRoleConfiguration roles;
        private final Map<String, String> deployments;
        private final MigrationOwnerBackend coordinator;
        private final int checksum;
        private PublicationCreationWitness before;
        private Map<String, String> bindings;
        private boolean completed;

        private PublicationCreationCallback(BulkExecutionRoleConfiguration roles,
                Map<String, String> deployments, MigrationOwnerBackend coordinator) {
            this.roles = roles;
            this.deployments = Map.copyOf(deployments);
            this.coordinator = coordinator;
            // Flyway 11.17.0 checksum semantics: UTF-8 lines, no line endings, first BOM removed.
            var crc = new CRC32();
            String resource = readV14Migration();
            if (resource.startsWith("\uFEFF")) resource = resource.substring(1);
            resource.lines().forEach(line -> crc.update(line.getBytes(StandardCharsets.UTF_8)));
            this.checksum = (int) crc.getValue();
        }

        @Override
        public boolean supports(Event event, Context context) {
            return (event == Event.BEFORE_EACH_MIGRATE || event == Event.AFTER_EACH_MIGRATE)
                    && context.getMigrationInfo() != null
                    && org.flywaydb.core.api.MigrationVersion.fromVersion("14")
                            .equals(context.getMigrationInfo().getVersion());
        }

        @Override
        public boolean canHandleInTransaction(Event event, Context context) { return true; }

        @Override
        public String getCallbackName() { return "bulk-publication-creation"; }

        @Override
        public void handle(Event event, Context context) {
            require(supports(event, context), "Unexpected bulk publication creation event");
            var migration = context.getMigrationInfo();
            require(SCRIPT.equals(migration.getScript()) && migration.getChecksum() != null
                            && checksum == migration.getChecksum(),
                    "Bulk publication creation migration differs from its packaged resource");
            Connection connection = context.getConnection();
            try {
                PublicationCreationWitness current = publicationCreationWitness(connection, roles, coordinator);
                if (event == Event.BEFORE_EACH_MIGRATE) {
                    require(before == null && !completed, "Bulk publication creation origin already observed");
                    try (var statement = connection.createStatement();
                            var rows = statement.executeQuery(
                                    "select pg_catalog.to_regclass('praxis_bulk.praxis_bulk_openapi_publication') is null")) {
                        require(rows.next() && rows.getBoolean(1) && !rows.next(),
                                "Bulk publication creation requires an absent publication table");
                    }
                    validatePublicationPredecessor(connection, roles);
                    var observed = new LinkedHashMap<String, String>();
                    // Existing lock order: namespace SHARE in namespace order; no bucket locks here.
                    try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                            "select namespace_id, deployment_id from praxis_bulk.praxis_bulk_namespace_binding "
                                    + "order by namespace_id for share")) {
                        while (rows.next()) observed.put(rows.getString(1), rows.getString(2));
                    }
                    require(deployments.entrySet().containsAll(observed.entrySet()),
                            "Publication predecessor binding differs from explicit deployment map");
                    try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                            "select count(*) from praxis_bulk.praxis_bulk_namespace_binding b "
                                    + "where not exists (select 1 from praxis_bulk.praxis_bulk_deployment_bucket d "
                                    + "where d.deployment_id=b.deployment_id)")) {
                        require(rows.next() && rows.getLong(1) == 0 && !rows.next(),
                                "Publication predecessor binding has no durable deployment bucket");
                    }
                    bindings = Map.copyOf(observed);
                    before = current;
                } else {
                    require(before != null && !completed && before.equals(current),
                            "Bulk publication creation lost its migration transaction origin");
                    require(readNamespaceBindings(connection).equals(bindings),
                            "Publication predecessor bindings changed during migration");
                    for (String deployment : bindings.values().stream().distinct().sorted().toList()) {
                        try (var statement = connection.prepareStatement("""
                                insert into praxis_bulk.praxis_bulk_openapi_publication
                                    (deployment_id, state, generation, document_digest, updated_at)
                                values (?, 'UNCOMPOSED', 0, null, clock_timestamp())
                                """)) {
                            statement.setString(1, deployment);
                            require(statement.executeUpdate() == 1,
                                    "Bulk publication creation did not insert one identity");
                        }
                    }
                    completed = true;
                }
            } catch (SQLException failure) {
                throw new IllegalStateException("Unable to attest bulk publication creation", failure);
            }
        }
    }

    private record PublicationCreationWitness(int pid, String database, String owner, long transaction) { }

    private static PublicationCreationWitness publicationCreationWitness(Connection connection,
            BulkExecutionRoleConfiguration roles, MigrationOwnerBackend coordinator) throws SQLException {
        require(!connection.getAutoCommit(), "Bulk publication creation requires a migration transaction");
        assertPostgreSql(connection);
        validateConnectionIdentity(connection, Set.of(roles.expectedSchemaOwnerRole()),
                roles.expectedSchemaOwnerRole());
        try (var statement = connection.createStatement()) {
            int existingTimeout = statement.getQueryTimeout();
            statement.setQueryTimeout(existingTimeout > 0 ? Math.min(existingTimeout, 10) : 10);
            try (var rows = statement.executeQuery(
                    "select pg_catalog.pg_backend_pid(), pg_catalog.current_database(), current_user, "
                            + "pg_catalog.txid_current()")) {
                require(rows.next(), "Bulk publication migration identity is absent");
                var witness = new PublicationCreationWitness(rows.getInt(1), rows.getString(2),
                        rows.getString(3), rows.getLong(4));
                require(witness.pid() > 0 && witness.pid() != coordinator.pid()
                                && coordinator.database().equals(witness.database())
                                && coordinator.role().equals(witness.owner()) && witness.transaction() > 0
                                && !rows.next(), "Bulk publication migration identity differs from coordinator");
                return witness;
            }
        }
    }

    /** V5 binding/bucket primitives are unchanged through V13; current wrappers require V15+. */
    private static void validatePublicationPredecessor(Connection connection,
            BulkExecutionRoleConfiguration roles) throws SQLException {
        validateConfiguredRoles(connection, roles);
        validateConfiguredRoleInheritance(connection, roles);
        validateNoOwnerMembership(connection, roles.expectedSchemaOwnerRole());
        validateNamespaceBindingColumns(connection);
        validateDeploymentBucketColumns(connection);
        validateNamespaceBindingConstraints(connection);
        validateDeploymentBucketConstraints(connection);
        try (var statement = connection.prepareStatement("""
                select n.nspname, owner.rolname from pg_catalog.pg_namespace n
                join pg_catalog.pg_roles owner on owner.oid=n.nspowner where n.nspname=?
                """)) {
            statement.setString(1, SCHEMA);
            try (var rows = statement.executeQuery()) {
                require(rows.next() && roles.expectedSchemaOwnerRole().equals(rows.getString(2)) && !rows.next(),
                        "Publication predecessor schema owner differs");
            }
        }
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
                select count(*) from pg_catalog.pg_roles where
                rolname in ('praxis_bulk_retention_owner','praxis_bulk_retention_executor','praxis_bulk_control_owner')
                and not rolcanlogin and not rolinherit and not rolsuper and not rolcreatedb
                and not rolcreaterole and not rolreplication and not rolbypassrls
                """)) {
            require(rows.next() && rows.getLong(1) == 3 && !rows.next(),
                    "Publication predecessor dedicated role identity differs");
        }
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
                select count(*) from pg_catalog.pg_auth_members m
                join pg_catalog.pg_roles granted on granted.oid=m.roleid
                join pg_catalog.pg_roles member on member.oid=m.member
                where granted.rolname in ('praxis_bulk_retention_owner','praxis_bulk_control_owner')
                or member.rolname in ('praxis_bulk_retention_owner','praxis_bulk_retention_executor','praxis_bulk_control_owner')
                """)) {
            require(rows.next() && rows.getLong(1) == 0 && !rows.next(),
                    "Publication predecessor dedicated role topology is unsafe");
        }
        validateRuntimeRoleMemberships(connection, roles.runtimeGranteeRoles());
        validateConfiguredRoleMembershipClosure(connection, roles.controlPlaneGranteeRoles(),
                roles.controlPlaneGranteeRoles(), "Publication predecessor control membership differs");
        validateRetentionExecutorMemberships(connection, roles.retentionExecutorMembers());
        validatePublicationPredecessorSchemaAcl(connection, roles);
        for (String table : List.of(NAMESPACE_BINDING_TABLE, DEPLOYMENT_BUCKET_TABLE)) {
            validatePublicationPredecessorTable(connection, roles, table);
        }
        validatePublicationPredecessorTrigger(connection, roles, NAMESPACE_BINDING_TABLE,
                "praxis_bulk_namespace_binding_immutable", "protect_namespace_binding");
        validatePublicationPredecessorTrigger(connection, roles, DEPLOYMENT_BUCKET_TABLE,
                "praxis_bulk_deployment_bucket_guard_mutation", "guard_bucket_mutation");
    }

    private static void validatePublicationPredecessorSchemaAcl(Connection connection,
            BulkExecutionRoleConfiguration roles) throws SQLException {
        var required = new LinkedHashSet<>(Set.of("praxis_bulk_retention_owner|USAGE",
                "praxis_bulk_retention_executor|USAGE", "praxis_bulk_control_owner|USAGE"));
        var allowed = new LinkedHashSet<>(required);
        roles.runtimeGranteeRoles().forEach(role -> allowed.add(role + "|USAGE"));
        roles.controlPlaneGranteeRoles().forEach(role -> allowed.add(role + "|USAGE"));
        var actual = new LinkedHashSet<String>();
        try (var statement = connection.prepareStatement("""
                select coalesce(r.rolname,'PUBLIC'), acl.privilege_type, acl.is_grantable, acl.grantee=n.nspowner
                from pg_catalog.pg_namespace n
                cross join lateral pg_catalog.aclexplode(coalesce(n.nspacl, pg_catalog.acldefault('n',n.nspowner))) acl
                left join pg_catalog.pg_roles r on r.oid=acl.grantee where n.nspname=?
                """)) {
            statement.setString(1, SCHEMA);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (rows.getBoolean(4)) continue;
                    require(!rows.getBoolean(3), "Publication predecessor schema grants cannot be grantable");
                    actual.add(rows.getString(1) + "|" + rows.getString(2));
                }
            }
        }
        require(actual.containsAll(required) && allowed.containsAll(actual),
                "Publication predecessor schema ACL differs");
    }

    private static void validatePublicationPredecessorTable(Connection connection,
            BulkExecutionRoleConfiguration roles, String table) throws SQLException {
        try (var statement = connection.prepareStatement("""
                select owner.rolname from pg_catalog.pg_class c
                join pg_catalog.pg_roles owner on owner.oid=c.relowner where c.oid=?::regclass
                """)) {
            statement.setString(1, SCHEMA + "." + table);
            try (var rows = statement.executeQuery()) {
                require(rows.next() && roles.expectedSchemaOwnerRole().equals(rows.getString(1)) && !rows.next(),
                        "Publication predecessor table owner differs: " + table);
            }
        }
        var grantees = new LinkedHashSet<String>();
        try (var statement = connection.prepareStatement("""
                select distinct coalesce(r.rolname,'PUBLIC') from pg_catalog.pg_class c
                cross join lateral pg_catalog.aclexplode(coalesce(c.relacl,pg_catalog.acldefault('r',c.relowner))) acl
                left join pg_catalog.pg_roles r on r.oid=acl.grantee
                where c.oid=?::regclass and acl.grantee<>c.relowner
                union
                select distinct coalesce(r.rolname,'PUBLIC') from pg_catalog.pg_attribute a
                join pg_catalog.pg_class c on c.oid=a.attrelid
                cross join lateral pg_catalog.aclexplode(a.attacl) acl
                left join pg_catalog.pg_roles r on r.oid=acl.grantee
                where c.oid=?::regclass and acl.grantee<>c.relowner
                """)) {
            statement.setString(1, SCHEMA + "." + table);
            statement.setString(2, SCHEMA + "." + table);
            try (var rows = statement.executeQuery()) { while (rows.next()) grantees.add(rows.getString(1)); }
        }
        require(grantees.contains("praxis_bulk_retention_owner"), "Publication predecessor retention grants absent");
        var allowed = new LinkedHashSet<>(roles.runtimeGranteeRoles());
        allowed.add("praxis_bulk_retention_owner");
        require(allowed.containsAll(grantees), "Publication predecessor table has unexpected grantee: " + table);
        for (String role : grantees) {
            require(tableRolePrivileges(connection, table, role).equals(Set.of("T:SELECT", "C:deployment_id:UPDATE")),
                    "Publication predecessor lock-only grants differ: " + table);
        }
    }

    private static void validatePublicationPredecessorTrigger(Connection connection,
            BulkExecutionRoleConfiguration roles, String table, String trigger, String function) throws SQLException {
        // pg_get_triggerdef is search_path-sensitive on Flyway's migration connection.
        // Exact physical attributes attest the V5 ROW/BEFORE/DELETE/UPDATE trigger (1|2|8|16).
        String body = extractFunctionBody(readV5Migration(), function, "V5");
        try (var statement = connection.prepareStatement("""
                select t.tgenabled,t.tgtype,p.prosrc,l.lanname,
                       p.prorettype::regtype::text,p.prosecdef,p.proname,n.nspname,p.pronargs,
                       p.proconfig=array['search_path=pg_catalog, pg_temp']::text[],owner.rolname,
                       p.provolatile,p.proparallel,p.proleakproof,t.tgnargs,t.tgattr::text,
                       t.tgqual is null,t.tgconstraint=0,p.prokind
                from pg_catalog.pg_trigger t join pg_catalog.pg_proc p on p.oid=t.tgfoid
                join pg_catalog.pg_namespace n on n.oid=p.pronamespace
                join pg_catalog.pg_language l on l.oid=p.prolang
                join pg_catalog.pg_roles owner on owner.oid=p.proowner
                where t.tgrelid=?::regclass and t.tgname=? and not t.tgisinternal
                """)) {
            statement.setString(1, SCHEMA + "." + table);
            statement.setString(2, trigger);
            try (var rows = statement.executeQuery()) {
                require(rows.next() && "O".equals(rows.getString(1))
                                && rows.getInt(2) == 27
                                && normalizeExpression(body).equals(normalizeExpression(rows.getString(3)))
                                && "plpgsql".equals(rows.getString(4)) && "trigger".equals(rows.getString(5))
                                && !rows.getBoolean(6) && function.equals(rows.getString(7))
                                && SCHEMA.equals(rows.getString(8)) && rows.getInt(9)==0 && rows.getBoolean(10)
                                && roles.expectedSchemaOwnerRole().equals(rows.getString(11))
                                && "v".equals(rows.getString(12)) && "u".equals(rows.getString(13))
                                && !rows.getBoolean(14) && rows.getInt(15) == 0 && "".equals(rows.getString(16))
                                && rows.getBoolean(17) && rows.getBoolean(18) && "f".equals(rows.getString(19))
                                && !rows.next(),
                        "Publication predecessor immutable trigger differs: " + table);
            }
        }
    }

    private static void requireOutsideSpringTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Bulk storage migration must not run inside an active Spring transaction");
        }
    }

    private static int currentHistoryVersion(Connection connection) throws SQLException {
        if (!schemaExists(connection)) return 0;
        try (var statement = connection.prepareStatement("""
                select max(version::integer) from praxis_bulk.praxis_bulk_schema_history
                where success and version ~ '^[0-9]+$'
                """); var rows = statement.executeQuery()) {
            if (!rows.next()) return 0;
            int version = rows.getInt(1);
            if (rows.wasNull()) version = 0;
            if (rows.next() || version > 20)
                throw new IllegalStateException("Unsupported bulk schema history version");
            return version;
        }
    }

    /** Catalog-only physical discriminator: restricted runtime roles cannot read Flyway history. */
    private static boolean isV18Installed(Connection connection) throws SQLException {
        var found = new LinkedHashSet<String>();
        try (var statement = connection.prepareStatement("""
                select c.relname, c.relkind from pg_catalog.pg_class c
                join pg_catalog.pg_namespace n on n.oid=c.relnamespace
                where n.nspname=? and c.relname = any (?::text[])
                """)) {
            statement.setString(1, SCHEMA);
            statement.setArray(2, connection.createArrayOf("text", V18_TABLES.toArray()));
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    require("r".equals(rows.getString(2)), "V18 relation kind changed: " + rows.getString(1));
                    require(found.add(rows.getString(1)), "V18 relation inventory is ambiguous");
                }
            }
        }
        require(found.isEmpty() || found.equals(V18_TABLES), "V18 physical installation is partial");
        return !found.isEmpty();
    }

    private static void assertKnownDedicatedSchema(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            assertPostgreSql(connection);
            if (!schemaExists(connection)) return;
            Set<String> relations = queryNames(connection, """
                    select c.relname
                    from pg_class c join pg_namespace n on n.oid = c.relnamespace
                    where n.nspname = ? and c.relkind in ('r', 'p', 'v', 'm', 'S', 'f')
                    """);
            Set<String> functions = queryNames(connection, """
                    select p.proname || '(' || pg_get_function_identity_arguments(p.oid) || ')'
                    from pg_proc p join pg_namespace n on n.oid = p.pronamespace
                    where n.nspname = ? and p.prokind in ('f', 'p', 'a', 'w')
                    """);
            Set<String> types = queryNames(connection, """
                    select t.typname
                    from pg_type t join pg_namespace n on n.oid = t.typnamespace
                    where n.nspname = ? and t.typelem = 0 and t.typtype in ('b', 'c', 'd', 'e', 'm', 'r')
                      and not (t.typtype = 'c' and t.typrelid <> 0
                          and t.typname in ('praxis_bulk_schema_history', 'praxis_bulk_proposal',
                              'praxis_bulk_evaluation', 'praxis_bulk_execution', 'praxis_bulk_item_receipt',
                              'praxis_bulk_admission', 'praxis_bulk_namespace_binding',
                              'praxis_bulk_operation_control', 'praxis_bulk_openapi_publication', 'praxis_bulk_deployment_bucket',
                              'praxis_bulk_subject_bucket', 'praxis_bulk_allocation',
                              'praxis_bulk_target_manifest', 'praxis_bulk_manifest_bootstrap',
                              'praxis_bulk_preview_state', 'praxis_bulk_target_preview',
                              'praxis_bulk_preview_bootstrap',
                              'praxis_bulk_preview_item_integrity',
                              'praxis_bulk_preview_integrity_bootstrap',
                              'praxis_bulk_preview_reader_bootstrap',
                              'praxis_bulk_atomic_receipt', 'praxis_bulk_atomic_item_result',
                              'praxis_bulk_atomic_effect_ref', 'praxis_bulk_atomic_rejection',
                              'praxis_bulk_atomic_bootstrap',
                              'praxis_bulk_tombstone',
                              'praxis_bulk_capacity_read_bootstrap',
                              'praxis_bulk_capacity_marker', 'praxis_bulk_capacity_installation',
                              'praxis_bulk_capacity_slot','praxis_bulk_capacity_occupation',
                              'praxis_bulk_capacity_occupancy_bootstrap'))
                    """);
            Set<String> orphanIndexes = unexpectedOrphanIndexes(connection);
            Set<String> triggers = queryNames(connection, """
                    select c.relname || '.' || t.tgname
                    from pg_trigger t join pg_class c on c.oid = t.tgrelid
                        join pg_namespace n on n.oid = c.relnamespace
                    where n.nspname = ? and not t.tgisinternal
                    """);
            Set<String> rules = userRules(connection);
            Set<String> policies = rowSecurityPolicies(connection);

            if (relations.isEmpty() && functions.isEmpty() && types.isEmpty()
                    && orphanIndexes.isEmpty() && triggers.isEmpty() && rules.isEmpty() && policies.isEmpty()) return;
            if (!relations.contains(HISTORY_TABLE)
                    || !allowedRelations(isV18Installed(connection), BulkCapacityOccupancyCatalog.installed(connection)).containsAll(relations)
                    || !governedHistoricalFunctions(isV18Installed(connection), BulkCapacityOccupancyCatalog.installed(connection)).containsAll(functions)
                    || !types.isEmpty()
                    || !orphanIndexes.isEmpty()
                    || !rules.isEmpty() || !policies.isEmpty()
                    || !allowedTriggers(isV18Installed(connection), BulkCapacityOccupancyCatalog.installed(connection)).containsAll(triggers)) {
                throw new IllegalStateException("Refusing an unknown nonempty praxis_bulk schema: relations="
                        + relations + ", functions=" + functions + ", types=" + types + ", indexes="
                        + orphanIndexes + ", triggers=" + triggers + ", rules=" + rules + ", policies=" + policies);
            }
        } catch (SQLException error) {
            throw new IllegalStateException("Unable to inspect dedicated bulk storage schema", error);
        }
    }

    private static Set<String> allowedRelations(boolean v18, boolean v19) {
        var names = new LinkedHashSet<>(Set.of(HISTORY_TABLE, PROPOSAL_TABLE, EVALUATION_TABLE, MANIFEST_TABLE,
                MANIFEST_BOOTSTRAP_TABLE, PREVIEW_STATE_TABLE, TARGET_PREVIEW_TABLE,
                PREVIEW_BOOTSTRAP_TABLE, PREVIEW_INTEGRITY_TABLE,
                PREVIEW_INTEGRITY_BOOTSTRAP_TABLE, PREVIEW_READER_BOOTSTRAP_TABLE, EXECUTION_TABLE,
                RECEIPT_TABLE, ADMISSION_TABLE, NAMESPACE_BINDING_TABLE, OPERATION_CONTROL_TABLE, OPENAPI_PUBLICATION_TABLE,
                DEPLOYMENT_BUCKET_TABLE, SUBJECT_BUCKET_TABLE, ALLOCATION_TABLE, TOMBSTONE_TABLE,
                ATOMIC_RECEIPT_TABLE, ATOMIC_ITEM_TABLE, ATOMIC_EFFECT_TABLE,
                ATOMIC_REJECTION_TABLE, ATOMIC_BOOTSTRAP_TABLE));
        if (v18) names.addAll(V18_TABLES);
        if (v19) names.addAll(BulkCapacityOccupancyCatalog.TABLES);
        return names;
    }

    /** Recognition before Flyway only; never used by current exact catalog attestation. */
    private static Set<String> governedHistoricalFunctions(boolean v18, boolean v19) {
        var names = new LinkedHashSet<>(allowedFunctions(v18, v19));
        names.addAll(V6_FUNCTIONS);
        return names;
    }

    private static Set<String> allowedFunctions(boolean v18, boolean v19) {
        var names = new LinkedHashSet<>(V5_FUNCTIONS);
        names.addAll(V15_CONTROL_FUNCTIONS);
        names.addAll(V8_FUNCTIONS);
        names.addAll(V9_FUNCTIONS);
        names.addAll(V10_FUNCTIONS);
        names.addAll(V11_FUNCTIONS);
        names.addAll(V12_FUNCTIONS);
        names.addAll(V14_FUNCTIONS);
        names.addAll(V16_FUNCTIONS);
        if (v18) names.addAll(V18_FUNCTIONS);
        if (v19) names.addAll(BulkCapacityOccupancyCatalog.FUNCTIONS);
        names.addAll(Set.of(REJECTION_FUNCTION + "()", EVALUATION_REJECTION_FUNCTION + "()",
                BINDING_FUNCTION + "()", TERMINAL_REASON_FUNCTION + "()",
                RECEIPT_FUNCTION + "()", ADMISSION_FUNCTION + "()", DESCRIPTOR_FUNCTION + "()",
                INSERT_FENCE_FUNCTION + "()"));
        return names;
    }

    private static Set<String> allowedTriggers(boolean v18, boolean v19) {
        var names = new LinkedHashSet<>(V5_TRIGGERS);
        names.addAll(Set.of(PROPOSAL_TABLE + "." + REJECTION_TRIGGER,
                EVALUATION_TABLE + "." + EVALUATION_REJECTION_TRIGGER,
                EXECUTION_TABLE + "." + BINDING_TRIGGER,
                EXECUTION_TABLE + "." + DESCRIPTOR_TRIGGER,
                PROPOSAL_TABLE + "." + PROPOSAL_INSERT_FENCE_TRIGGER,
                EXECUTION_TABLE + "." + EXECUTION_INSERT_FENCE_TRIGGER,
                RECEIPT_TABLE + "." + RECEIPT_TRIGGER,
                EXECUTION_TABLE + "." + TERMINAL_REASON_TRIGGER,
                ADMISSION_TABLE + "." + ADMISSION_TRIGGER));
        names.addAll(V8_TRIGGERS);
        names.addAll(V9_TRIGGERS);
        names.addAll(V10_TRIGGERS);
        names.addAll(V11_TRIGGERS);
        names.addAll(V16_TRIGGERS);
        if (v18) names.addAll(V18_TRIGGERS);
        if (v19) names.addAll(BulkCapacityOccupancyCatalog.triggerKeys());
        return names;
    }

    private static boolean schemaExists(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("select exists (select 1 from pg_namespace where nspname = ?)")) {
            statement.setString(1, SCHEMA);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getBoolean(1);
            }
        }
    }

    private static Set<String> queryNames(Connection connection, String sql) throws SQLException {
        Set<String> names = new LinkedHashSet<>();
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, SCHEMA);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) names.add(result.getString(1));
            }
        }
        return names;
    }

    /** Physical access path only: this does not grant execution authority or run online per job. */
    private static void validateWorkerQueueIndex(Connection connection,
            BulkExecutionRoleConfiguration roles, int historyVersion) throws SQLException {
        String expectedDefinition = "CREATE INDEX praxis_bulk_execution_worker_queue_idx ON "
                + "praxis_bulk.praxis_bulk_execution USING btree (namespace_id, created_at, execution_id) "
                + "WHERE ((execution_mode = 'ASYNC'::text) AND (status = 'QUEUED'::text))";
        try (var statement = connection.prepareStatement("""
                select table_ns.nspname, table_class.relname, owner.rolname, am.amname,
                       i.indisvalid, i.indisready, i.indislive, i.indisunique, i.indisprimary,
                       i.indnatts, i.indnkeyatts, i.indexprs is null, pg_get_indexdef(i.indexrelid),
                       pg_get_expr(i.indpred, i.indrelid), index_class.relacl is null
                  from pg_class index_class join pg_namespace n on n.oid=index_class.relnamespace
                  join pg_index i on i.indexrelid=index_class.oid
                  join pg_class table_class on table_class.oid=i.indrelid
                  join pg_namespace table_ns on table_ns.oid=table_class.relnamespace
                  join pg_roles owner on owner.oid=index_class.relowner
                  join pg_am am on am.oid=index_class.relam
                 where n.nspname='praxis_bulk' and index_class.relname='praxis_bulk_execution_worker_queue_idx'
                """ ); var rows = statement.executeQuery()) {
            if (historyVersion < 20) {
                require(!rows.next(), "Worker queue index is premature for historical storage");
                return;
            }
            require(historyVersion == 20 && rows.next(), "V20 worker queue index is absent");
            require(SCHEMA.equals(rows.getString(1)) && EXECUTION_TABLE.equals(rows.getString(2))
                            && roles.expectedSchemaOwnerRole().equals(rows.getString(3))
                            && "btree".equals(rows.getString(4))
                            && rows.getBoolean(5) && rows.getBoolean(6) && rows.getBoolean(7)
                            && !rows.getBoolean(8) && !rows.getBoolean(9)
                            && rows.getInt(10) == 3 && rows.getInt(11) == 3 && rows.getBoolean(12)
                            && normalizeExpression(expectedDefinition).equals(normalizeExpression(rows.getString(13)))
                            && normalizeExpression("((execution_mode = 'ASYNC'::text) AND (status = 'QUEUED'::text))")
                                    .equals(normalizeExpression(rows.getString(14)))
                            && rows.getBoolean(15) && !rows.next(),
                    "V20 worker queue index definition or ownership differs");
        }
        List<String> columns = List.of("namespace_id", "created_at", "execution_id");
        List<String> classes = List.of("text_ops", "timestamptz_ops", "uuid_ops");
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
                select keys.ordinality, a.attname, i.indoption[keys.ordinality::integer-1],
                       i.indcollation[keys.ordinality::integer-1]=a.attcollation,
                       opns.nspname, op.opcname, op.opcdefault, op.opcintype=a.atttypid,
                       op.opcmethod=index_class.relam
                  from pg_index i join pg_class index_class on index_class.oid=i.indexrelid
                  cross join lateral unnest(i.indkey) with ordinality keys(attnum,ordinality)
                  join pg_attribute a on a.attrelid=i.indrelid and a.attnum=keys.attnum
                  join pg_opclass op on op.oid=i.indclass[keys.ordinality::integer-1]
                  join pg_namespace opns on opns.oid=op.opcnamespace
                 where i.indexrelid='praxis_bulk.praxis_bulk_execution_worker_queue_idx'::regclass
                 order by keys.ordinality
                """)) {
            for (int ordinal = 0; ordinal < 3; ordinal++) {
                require(rows.next() && rows.getInt(1) == ordinal + 1
                                && columns.get(ordinal).equals(rows.getString(2)) && rows.getInt(3) == 0
                                && rows.getBoolean(4) && "pg_catalog".equals(rows.getString(5))
                                && classes.get(ordinal).equals(rows.getString(6)) && rows.getBoolean(7)
                                && rows.getBoolean(8) && rows.getBoolean(9),
                        "V20 worker queue index key, ordering, collation or opclass differs");
            }
            require(!rows.next(), "V20 worker queue index has extra keys");
        }
    }

    private static Set<String> unexpectedOrphanIndexes(Connection connection) throws SQLException {
        Set<String> names = new LinkedHashSet<>();
        try (var statement = connection.prepareStatement("""
                select index_class.relname
                from pg_index i
                join pg_class index_class on index_class.oid = i.indexrelid
                join pg_class table_class on table_class.oid = i.indrelid
                join pg_namespace n on n.oid = index_class.relnamespace
                join pg_am am on am.oid = index_class.relam
                left join lateral unnest(i.indkey) with ordinality key_column(attnum, ordinal_position)
                    on key_column.ordinal_position = 1
                left join pg_attribute key_attribute on key_attribute.attrelid = i.indrelid
                    and key_attribute.attnum = key_column.attnum
                left join pg_opclass opclass on opclass.oid = i.indclass[0]
                left join pg_constraint c on c.conindid = i.indexrelid and c.conrelid = i.indrelid
                    and c.contype in ('p', 'u', 'x')
                where n.nspname = ? and c.oid is null
                  and index_class.relname not in
                      ('praxis_bulk_allocation_pending_deployment_idx',
                       'praxis_bulk_allocation_pending_subject_idx',
                       'praxis_bulk_allocation_active_deployment_idx',
                       'praxis_bulk_execution_retention_idx', 'praxis_bulk_proposal_expiry_idx')
                  and not (index_class.relname='praxis_bulk_execution_worker_queue_idx' and ?)
                  and not coalesce((index_class.relname = ? and table_class.relname = ?
                    and not i.indisunique and i.indisvalid and i.indisready and i.indislive
                    and i.indpred is null and i.indexprs is null and i.indnatts = 1 and i.indnkeyatts = 1
                    and am.amname = 'btree' and key_attribute.attname = 'success'
                    and i.indoption[0] = 0 and i.indcollation[0] = key_attribute.attcollation
                    and opclass.opcdefault), false)
                """)) {
            statement.setString(1, SCHEMA);
            statement.setBoolean(2, currentHistoryVersion(connection) == 20);
            statement.setString(3, "praxis_bulk_schema_history_s_idx");
            statement.setString(4, HISTORY_TABLE);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) names.add(result.getString(1));
            }
        }
        return names;
    }

    private static Set<String> userRules(Connection connection) throws SQLException {
        return queryNames(connection, """
                select c.relname || '.' || r.rulename
                from pg_rewrite r join pg_class c on c.oid = r.ev_class
                    join pg_namespace n on n.oid = c.relnamespace
                where n.nspname = ? and r.rulename <> '_RETURN'
                """);
    }

    private static Set<String> rowSecurityPolicies(Connection connection) throws SQLException {
        return queryNames(connection, """
                select c.relname || '.' || p.polname
                from pg_policy p join pg_class c on c.oid = p.polrelid
                    join pg_namespace n on n.oid = c.relnamespace
                where n.nspname = ?
                """);
    }

    private static void validateOwnedSchema(Connection connection) throws SQLException {
        Set<String> relations = queryNames(connection, """
                select c.relname
                from pg_class c join pg_namespace n on n.oid = c.relnamespace
                where n.nspname = ? and c.relkind in ('r', 'p', 'v', 'm', 'S', 'f')
                """);
        Set<String> functions = queryNames(connection, """
                select p.proname || '(' || pg_get_function_identity_arguments(p.oid) || ')'
                from pg_proc p join pg_namespace n on n.oid = p.pronamespace
                where n.nspname = ? and p.prokind in ('f', 'p', 'a', 'w')
                """);
        Set<String> types = queryNames(connection, """
                select t.typname
                from pg_type t join pg_namespace n on n.oid = t.typnamespace
                where n.nspname = ? and t.typelem = 0 and t.typtype in ('b', 'c', 'd', 'e', 'm', 'r')
                      and not (t.typtype = 'c' and t.typrelid <> 0
                          and t.typname in ('praxis_bulk_schema_history', 'praxis_bulk_proposal',
                              'praxis_bulk_evaluation', 'praxis_bulk_execution', 'praxis_bulk_item_receipt',
                              'praxis_bulk_admission', 'praxis_bulk_namespace_binding',
                              'praxis_bulk_operation_control', 'praxis_bulk_openapi_publication', 'praxis_bulk_deployment_bucket',
                              'praxis_bulk_subject_bucket', 'praxis_bulk_allocation',
                              'praxis_bulk_target_manifest', 'praxis_bulk_manifest_bootstrap',
                              'praxis_bulk_preview_state', 'praxis_bulk_target_preview',
                              'praxis_bulk_preview_bootstrap',
                              'praxis_bulk_preview_item_integrity',
                              'praxis_bulk_preview_integrity_bootstrap',
                              'praxis_bulk_preview_reader_bootstrap',
                              'praxis_bulk_tombstone',
                              'praxis_bulk_atomic_receipt', 'praxis_bulk_atomic_item_result',
                              'praxis_bulk_atomic_effect_ref', 'praxis_bulk_atomic_rejection',
                              'praxis_bulk_atomic_bootstrap',
                              'praxis_bulk_capacity_read_bootstrap',
                              'praxis_bulk_capacity_marker', 'praxis_bulk_capacity_installation',
                              'praxis_bulk_capacity_slot','praxis_bulk_capacity_occupation',
                              'praxis_bulk_capacity_occupancy_bootstrap'))
                """);
        Set<String> orphanIndexes = unexpectedOrphanIndexes(connection);
        Set<String> triggers = queryNames(connection, """
                select c.relname || '.' || t.tgname
                from pg_trigger t join pg_class c on c.oid = t.tgrelid
                    join pg_namespace n on n.oid = c.relnamespace
                where n.nspname = ? and not t.tgisinternal
                """);
        Set<String> rules = userRules(connection);
        Set<String> policies = rowSecurityPolicies(connection);
        require(relations.equals(allowedRelations(isV18Installed(connection), BulkCapacityOccupancyCatalog.installed(connection)))
                        && functions.equals(allowedFunctions(isV18Installed(connection), BulkCapacityOccupancyCatalog.installed(connection)))
                        && types.isEmpty()
                        && orphanIndexes.isEmpty()
                        && rules.isEmpty() && policies.isEmpty()
                        && triggers.equals(allowedTriggers(isV18Installed(connection), BulkCapacityOccupancyCatalog.installed(connection))),
                "protected bulk storage schema contains unexpected owned objects: indexes=" + orphanIndexes
                        + ", rules=" + rules + ", policies=" + policies);
    }

    private static void validateColumns(Connection connection) throws SQLException {
        Map<String, ColumnDefinition> expected = Map.ofEntries(
                Map.entry("proposal_id", new ColumnDefinition("uuid", false, null, "NEVER", null)),
                Map.entry("namespace_id", new ColumnDefinition("text", false, null, "NEVER", null)),
                Map.entry("subject_id", new ColumnDefinition("text", false, null, "NEVER", null)),
                Map.entry("resource_key", new ColumnDefinition("text", false, null, "NEVER", null)),
                Map.entry("operation_id", new ColumnDefinition("text", false, null, "NEVER", null)),
                Map.entry("created_at", new ColumnDefinition("timestamp with time zone", false, null, "NEVER", 6)),
                Map.entry("expires_at", new ColumnDefinition("timestamp with time zone", false, null, "NEVER", 6)),
                Map.entry("fingerprint", new ColumnDefinition("text", false, null, "NEVER", null)),
                Map.entry("payload", new ColumnDefinition("bytea", false, null, "NEVER", null)),
                Map.entry("control_generation", new ColumnDefinition("bigint", true, null, "NEVER", null)),
                Map.entry("control_descriptor_fingerprint", new ColumnDefinition("text", true, null, "NEVER", null)),
                Map.entry("control_structural_revision", new ColumnDefinition("text", true, null, "NEVER", null)),
                Map.entry("atomicity", new ColumnDefinition("text", false, null, "NEVER", null)),
                Map.entry("protocol_version", new ColumnDefinition("smallint", false, null, "NEVER", null)));
        if (BulkCapacityOccupancyCatalog.installed(connection)) {
            expected = new LinkedHashMap<>(expected);
            expected.put("execution_mode", new ColumnDefinition("text", false, null, "NEVER", null));
        }
        Map<String, ColumnDefinition> actual = new LinkedHashMap<>();
        try (var statement = connection.prepareStatement("""
                select column_name, data_type, is_nullable, column_default, is_generated, datetime_precision
                from information_schema.columns
                where table_schema = ? and table_name = ?
                """)) {
            statement.setString(1, SCHEMA);
            statement.setString(2, PROPOSAL_TABLE);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    actual.put(result.getString(1), new ColumnDefinition(
                            result.getString(2), "YES".equals(result.getString(3)), result.getString(4), result.getString(5),
                            result.getObject(6, Integer.class)));
                }
            }
        }
        require(expected.equals(actual), "proposal table columns, types or nullability differ from V1");
    }

    private static void validateProposalTable(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("""
                select c.relkind, c.relpersistence
                from pg_class c join pg_namespace n on n.oid = c.relnamespace
                where n.nspname = ? and c.relname = ?
                """)) {
            statement.setString(1, SCHEMA);
            statement.setString(2, PROPOSAL_TABLE);
            try (ResultSet result = statement.executeQuery()) {
                require(result.next() && "r".equals(result.getString(1)) && "p".equals(result.getString(2))
                                && !result.next(),
                        "proposal storage must be an ordinary permanent table");
            }
        }
    }

    private static void validateEvaluationTable(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("""
                select c.relkind, c.relpersistence
                from pg_class c join pg_namespace n on n.oid = c.relnamespace
                where n.nspname = ? and c.relname = ?
                """)) {
            statement.setString(1, SCHEMA);
            statement.setString(2, EVALUATION_TABLE);
            try (ResultSet result = statement.executeQuery()) {
                require(result.next() && "r".equals(result.getString(1)) && "p".equals(result.getString(2))
                                && !result.next(),
                        "evaluation storage must be an ordinary permanent table");
            }
        }
    }

    private static void validateEvaluationColumns(Connection connection) throws SQLException {
        Map<String, ColumnDefinition> expected = Map.ofEntries(
                Map.entry("proposal_id", new ColumnDefinition("uuid", false, null, "NEVER", null)),
                Map.entry("input_fingerprint", new ColumnDefinition("text", false, null, "NEVER", null)),
                Map.entry("evaluation_fingerprint", new ColumnDefinition("text", false, null, "NEVER", null)),
                Map.entry("payload", new ColumnDefinition("bytea", false, null, "NEVER", null)));
        Map<String, ColumnDefinition> actual = new LinkedHashMap<>();
        try (var statement = connection.prepareStatement("""
                select column_name, data_type, is_nullable, column_default, is_generated, datetime_precision
                from information_schema.columns
                where table_schema = ? and table_name = ?
                """)) {
            statement.setString(1, SCHEMA);
            statement.setString(2, EVALUATION_TABLE);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    actual.put(result.getString(1), new ColumnDefinition(
                            result.getString(2), "YES".equals(result.getString(3)), result.getString(4), result.getString(5),
                            result.getObject(6, Integer.class)));
                }
            }
        }
        require(expected.equals(actual), "evaluation table columns, types or nullability differ from V2");
    }

    private static void validatePrimaryKey(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("""
                select string_agg(a.attname, ',' order by key_columns.ordinality), c.condeferrable
                from pg_constraint c
                join unnest(c.conkey) with ordinality as key_columns(attnum, ordinality) on true
                join pg_attribute a on a.attrelid = c.conrelid and a.attnum = key_columns.attnum
                join pg_namespace n on n.oid = c.connamespace
                where n.nspname = ? and c.conrelid = ?::regclass and c.contype = 'p'
                group by c.oid
                """)) {
            statement.setString(1, SCHEMA);
            statement.setString(2, SCHEMA + "." + PROPOSAL_TABLE);
            try (ResultSet result = statement.executeQuery()) {
                require(result.next() && "proposal_id".equals(result.getString(1)) && !result.getBoolean(2) && !result.next(),
                        "proposal_id must be the only primary key column");
            }
        }
    }

    private static void validateProposalUnique(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("""
                select string_agg(a.attname, ',' order by key_columns.ordinality),
                       c.condeferrable, c.condeferred, c.convalidated
                from pg_constraint c
                join unnest(c.conkey) with ordinality as key_columns(attnum, ordinality) on true
                join pg_attribute a on a.attrelid = c.conrelid and a.attnum = key_columns.attnum
                join pg_namespace n on n.oid = c.connamespace
                where n.nspname = ? and c.conrelid = ?::regclass and c.contype = 'u'
                group by c.oid
                """)) {
            statement.setString(1, SCHEMA);
            statement.setString(2, SCHEMA + "." + PROPOSAL_TABLE);
            try (ResultSet result = statement.executeQuery()) {
                var found = new LinkedHashSet<String>();
                while (result.next()) {
                    require(!result.getBoolean(2) && !result.getBoolean(3) && result.getBoolean(4),
                            "proposal unique key must be immediate and validated");
                    found.add(result.getString(1));
                }
                require(found.equals(BulkCapacityOccupancyCatalog.installed(connection)
                        ? Set.of("proposal_id,fingerprint", "proposal_id,atomicity,protocol_version", "proposal_id,execution_mode")
                        : Set.of("proposal_id,fingerprint", "proposal_id,atomicity,protocol_version")),
                        "proposal unique bindings differ from V16");
            }
        }
    }

    private static void validateEvaluationPrimaryKey(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("""
                select string_agg(a.attname, ',' order by key_columns.ordinality), c.condeferrable, c.condeferred
                from pg_constraint c
                join unnest(c.conkey) with ordinality as key_columns(attnum, ordinality) on true
                join pg_attribute a on a.attrelid = c.conrelid and a.attnum = key_columns.attnum
                join pg_namespace n on n.oid = c.connamespace
                where n.nspname = ? and c.conrelid = ?::regclass and c.contype = 'p'
                group by c.oid
                """)) {
            statement.setString(1, SCHEMA);
            statement.setString(2, SCHEMA + "." + EVALUATION_TABLE);
            try (ResultSet result = statement.executeQuery()) {
                require(result.next() && "proposal_id".equals(result.getString(1))
                                && !result.getBoolean(2) && !result.getBoolean(3) && !result.next(),
                        "evaluation proposal_id must be the only immediate primary key column");
            }
        }
    }

    private static void validateEvaluationForeignKey(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("""
                select string_agg(source_column.attname, ',' order by source_key.ordinality),
                       string_agg(target_column.attname, ',' order by target_key.ordinality),
                       target_namespace.nspname, target_relation.relname,
                       c.condeferrable, c.condeferred, c.convalidated,
                       c.confmatchtype, c.confupdtype, c.confdeltype
                from pg_constraint c
                join unnest(c.conkey) with ordinality as source_key(attnum, ordinality) on true
                join pg_attribute source_column on source_column.attrelid = c.conrelid and source_column.attnum = source_key.attnum
                join unnest(c.confkey) with ordinality as target_key(attnum, ordinality)
                    on target_key.ordinality = source_key.ordinality
                join pg_attribute target_column on target_column.attrelid = c.confrelid and target_column.attnum = target_key.attnum
                join pg_class target_relation on target_relation.oid = c.confrelid
                join pg_namespace target_namespace on target_namespace.oid = target_relation.relnamespace
                join pg_namespace source_namespace on source_namespace.oid = c.connamespace
                where source_namespace.nspname = ? and c.conrelid = ?::regclass and c.contype = 'f'
                group by c.oid, target_namespace.nspname, target_relation.relname
                """)) {
            statement.setString(1, SCHEMA);
            statement.setString(2, SCHEMA + "." + EVALUATION_TABLE);
            try (ResultSet result = statement.executeQuery()) {
                require(result.next()
                                && "proposal_id,input_fingerprint".equals(result.getString(1))
                                && "proposal_id,fingerprint".equals(result.getString(2))
                                && SCHEMA.equals(result.getString(3)) && PROPOSAL_TABLE.equals(result.getString(4))
                                && !result.getBoolean(5) && !result.getBoolean(6) && result.getBoolean(7)
                                && "s".equals(result.getString(8)) && "a".equals(result.getString(9))
                                && "a".equals(result.getString(10)) && !result.next(),
                        "evaluation must have the only immediate validated proposal and input-fingerprint foreign key");
            }
        }
    }

    private static void validateChecks(Connection connection) throws SQLException {
        Map<String, String> expected = Map.ofEntries(
                Map.entry("praxis_bulk_proposal_namespace_id_nonblank_check",
                        "(btrim(namespace_id)<>''::text)"),
                Map.entry("praxis_bulk_proposal_subject_id_nonblank_check",
                        "(btrim(subject_id)<>''::text)"),
                Map.entry("praxis_bulk_proposal_resource_key_nonblank_check",
                        "(btrim(resource_key)<>''::text)"),
                Map.entry("praxis_bulk_proposal_operation_id_nonblank_check",
                        "(btrim(operation_id)<>''::text)"),
                Map.entry("praxis_bulk_proposal_valid_window_check", "(expires_at>created_at)"),
                Map.entry("praxis_bulk_proposal_fingerprint_format_check", "(fingerprint~'^sha256:[0-9a-f]{64}$'::text)"),
                Map.entry("praxis_bulk_proposal_control_tuple_check",
                        "(((control_generationisnull)and(control_descriptor_fingerprintisnull)and(control_structural_revisionisnull))or((control_generationisnotnull)and(control_generation>=1)and(control_descriptor_fingerprintisnotnull)and(control_descriptor_fingerprint~'^sha256:[0-9a-f]{64}$'::text)and(control_structural_revisionisnotnull)and(btrim(control_structural_revision)<>''::text)and(length(control_structural_revision)<=200)))"),
                Map.entry("praxis_bulk_proposal_payload_length_check",
                        "((octet_length(payload)>=1)and(octet_length(payload)<=8388608))"),
                Map.entry("praxis_bulk_proposal_atomicity_check",
                        "((atomicity=any(array['PER_ITEM'::text,'ATOMIC'::text]))and(not(atomicityisdistinctfrom((replace(convert_from(payload,'UTF8'::name),(chr(92)||'u0000'::text),(chr(92)||'uFFFD'::text)))::json->>'atomicity'::text))))"),
                Map.entry("praxis_bulk_proposal_protocol_check",
                        "(protocol_version=any(array[1,2]))"));
        if (BulkCapacityOccupancyCatalog.installed(connection)) {
            expected = new LinkedHashMap<>(expected);
            expected.put("proposal_execution_mode_check", normalizeExpression(BulkCapacityOccupancyCatalog.proposalModeExpression()));
        }
        Map<String, ConstraintDefinition> actual = new LinkedHashMap<>();
        try (var statement = connection.prepareStatement("""
                select c.conname, c.convalidated, pg_get_expr(c.conbin, c.conrelid)
                from pg_constraint c join pg_namespace n on n.oid = c.connamespace
                where n.nspname = ? and c.conrelid = ?::regclass and c.contype = 'c'
                """)) {
            statement.setString(1, SCHEMA);
            statement.setString(2, SCHEMA + "." + PROPOSAL_TABLE);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) actual.put(result.getString(1),
                        new ConstraintDefinition(result.getBoolean(2), normalizeExpression(result.getString(3))));
            }
        }
        require(actual.keySet().equals(expected.keySet()), "proposal table check constraints differ from V1");
        for (Map.Entry<String, String> check : expected.entrySet()) {
            ConstraintDefinition definition = actual.get(check.getKey());
            require(definition.validated() && check.getValue().equals(definition.expression()),
                    "proposal table check constraint is invalid: " + check.getKey()
                            + " expected=" + check.getValue() + " actual=" + definition.expression());
        }
    }

    private static void validateEvaluationChecks(Connection connection) throws SQLException {
        Map<String, String> expected = Map.ofEntries(
                Map.entry("praxis_bulk_evaluation_fingerprint_format_check",
                        "(evaluation_fingerprint~'^sha256:[0-9a-f]{64}$'::text)"),
                Map.entry("praxis_bulk_evaluation_payload_length_check",
                        "((octet_length(payload)>=1)and(octet_length(payload)<=8388608))"));
        Map<String, ConstraintDefinition> actual = new LinkedHashMap<>();
        try (var statement = connection.prepareStatement("""
                select c.conname, c.convalidated, pg_get_expr(c.conbin, c.conrelid)
                from pg_constraint c join pg_namespace n on n.oid = c.connamespace
                where n.nspname = ? and c.conrelid = ?::regclass and c.contype = 'c'
                """)) {
            statement.setString(1, SCHEMA);
            statement.setString(2, SCHEMA + "." + EVALUATION_TABLE);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) actual.put(result.getString(1),
                        new ConstraintDefinition(result.getBoolean(2), normalizeExpression(result.getString(3))));
            }
        }
        require(actual.keySet().equals(expected.keySet()), "evaluation table check constraints differ from V2");
        for (Map.Entry<String, String> check : expected.entrySet()) {
            ConstraintDefinition definition = actual.get(check.getKey());
            require(definition.validated() && check.getValue().equals(definition.expression()),
                    "evaluation table check constraint is invalid: " + check.getKey());
        }
    }

    private static void validateImmutableUpdateTrigger(Connection connection, String table, String trigger,
            String function, String relation) throws SQLException {
        try (var statement = connection.prepareStatement("""
                select t.tgenabled, pg_get_triggerdef(t.oid), p.prosrc, l.lanname, p.prorettype::regtype::text, p.prosecdef
                from pg_trigger t
                join pg_class c on c.oid = t.tgrelid
                join pg_namespace n on n.oid = c.relnamespace
                join pg_proc p on p.oid = t.tgfoid
                join pg_language l on l.oid = p.prolang
                where n.nspname = ? and c.relname = ? and t.tgname = ? and not t.tgisinternal
                """)) {
            statement.setString(1, SCHEMA);
            statement.setString(2, table);
            statement.setString(3, trigger);
            try (ResultSet result = statement.executeQuery()) {
                require(result.next(), "immutable update trigger is missing");
                String definition = normalizeExpression(result.getString(2));
                require("O".equals(result.getString(1))
                                && definition.equals("createtrigger" + trigger + "beforeupdateon" + SCHEMA + "." + table
                                        + "foreachrowexecutefunction" + SCHEMA + "." + function + "()")
                                && "plpgsql".equals(result.getString(4))
                                && "trigger".equals(result.getString(5))
                                && !result.getBoolean(6)
                                && normalizeExpression(result.getString(3)).equals(
                                        "beginraiseexception'" + relation + " is immutable'usingerrcode='55000';end;")
                                && !result.next(),
                        "immutable update trigger differs from protected storage migration");
            }
        }
    }

    /** V3 execution and receipt plus V4 execution changes, checked against real PostgreSQL. */
    private static void validateDurableExecution(Connection connection) throws SQLException {
        validateDurableColumns(connection, "praxis_bulk_execution", Map.ofEntries(
                Map.entry("execution_id", "uuid|true"),
                Map.entry("proposal_id", "uuid|true"),
                Map.entry("namespace_id", "text|true"),
                Map.entry("subject_id", "text|true"),
                Map.entry("resource_key", "text|true"),
                Map.entry("operation_id", "text|true"),
                Map.entry("idempotency_key_digest", "text|true"),
                Map.entry("reservation_fingerprint", "text|true"),
                Map.entry("input_fingerprint", "text|true"),
                Map.entry("evaluation_fingerprint", "text|true"),
                Map.entry("control_generation", "bigint|false"),
                Map.entry("control_descriptor_fingerprint", "text|false"),
                Map.entry("structural_revision", "text|true"),
                Map.entry("owner_id", "text|true"),
                Map.entry("owner_epoch", "bigint|true"),
                Map.entry("status", "text|true"),
                Map.entry("next_ordinal", "integer|true"),
                Map.entry("target_count", "integer|true"),
                Map.entry("deadline_at", "timestamp(6) with time zone|true"),
                Map.entry("active_attempt_id", "uuid|false"),
                Map.entry("active_attempt_ordinal", "integer|false"),
                Map.entry("active_target_digest", "text|false"),
                Map.entry("active_attempt_epoch", "bigint|false"),
                Map.entry("active_unit_deadline_at", "timestamp(6) with time zone|false"),
                Map.entry("atomicity", "text|true"),
                Map.entry("protocol_version", "smallint|true"),
                Map.entry("active_set_digest", "text|false"),
                Map.entry("created_at", "timestamp(6) with time zone|true"),
                Map.entry("updated_at", "timestamp(6) with time zone|true"),
                Map.entry("terminal_at", "timestamp(6) with time zone|false"),
                Map.entry("terminal_reason_code", "text|false"),
                Map.entry("cancel_requested_at", "timestamp(6) with time zone|false")));
        validateDurableColumns(connection, "praxis_bulk_item_receipt", Map.ofEntries(
                Map.entry("execution_id", "uuid|true"),
                Map.entry("unit_ordinal", "integer|true"),
                Map.entry("target_digest", "text|true"),
                Map.entry("expected_version", "text|true"),
                Map.entry("attempt_id", "uuid|true"),
                Map.entry("owner_epoch", "bigint|true"),
                Map.entry("outcome", "text|true"),
                Map.entry("confirmed_at", "timestamp(6) with time zone|true"),
                Map.entry("unit_deadline_at", "timestamp(6) with time zone|false")));
        validateDurableConstraints(connection, "praxis_bulk_evaluation", true, Map.ofEntries(
                Map.entry("praxis_bulk_evaluation_proposal_fingerprint_key", "UNIQUE (proposal_id, evaluation_fingerprint)")));
        validateDurableConstraints(connection, "praxis_bulk_execution", false, Map.ofEntries(
                Map.entry("praxis_bulk_execution_attempt_shape_check", "CHECK ((((atomicity = 'PER_ITEM'::text) AND (active_set_digest IS NULL) AND ((active_attempt_id IS NULL) = (active_attempt_ordinal IS NULL)) AND ((active_attempt_id IS NULL) = (active_target_digest IS NULL)) AND ((active_attempt_id IS NULL) = (active_attempt_epoch IS NULL)) AND ((active_attempt_id IS NULL) OR ((active_attempt_ordinal = next_ordinal) AND ((active_attempt_ordinal >= 0) AND (active_attempt_ordinal <= (target_count - 1))) AND ((active_attempt_epoch >= 1) AND (active_attempt_epoch <= owner_epoch)) AND (active_target_digest ~ '^sha256:[0-9a-f]{64}$'::text)))) OR ((atomicity = 'ATOMIC'::text) AND ((target_count >= 1) AND (target_count <= 50)) AND ((next_ordinal = 0) OR (next_ordinal = target_count)) AND (active_attempt_ordinal IS NULL) AND (active_target_digest IS NULL) AND ((active_attempt_id IS NULL) = (active_attempt_epoch IS NULL)) AND ((active_attempt_id IS NULL) = (active_set_digest IS NULL)) AND ((active_attempt_id IS NULL) OR ((next_ordinal = 0) AND ((active_attempt_epoch >= 1) AND (active_attempt_epoch <= owner_epoch)) AND (active_set_digest ~ '^sha256:[0-9a-f]{64}$'::text))))))"),
                Map.entry("praxis_bulk_execution_atomicity_check", "CHECK ((atomicity = ANY (ARRAY['PER_ITEM'::text, 'ATOMIC'::text])))"),
                Map.entry("praxis_bulk_execution_protocol_check", "CHECK ((protocol_version = ANY (ARRAY[1, 2])))"),
                Map.entry("praxis_bulk_execution_atomic_terminal_check", "CHECK (((atomicity <> 'ATOMIC'::text) OR (status <> 'COMPLETED_WITH_ERRORS'::text)))"),
                Map.entry("praxis_bulk_execution_atomicity_protocol_fkey", "FOREIGN KEY (proposal_id, atomicity, protocol_version) REFERENCES praxis_bulk.praxis_bulk_proposal(proposal_id, atomicity, protocol_version)"),
                Map.entry("praxis_bulk_execution_control_tuple_check", "CHECK ((((control_generation IS NULL) AND (control_descriptor_fingerprint IS NULL)) OR ((control_generation IS NOT NULL) AND (control_generation >= 1) AND (control_descriptor_fingerprint IS NOT NULL) AND (control_descriptor_fingerprint ~ '^sha256:[0-9a-f]{64}$'::text))))"),
                Map.entry("praxis_bulk_execution_deadline_check", "CHECK ((deadline_at > created_at))"),
                Map.entry("praxis_bulk_execution_digest_format_check", "CHECK ((idempotency_key_digest ~ '^sha256:[0-9a-f]{64}$'::text))"),
                Map.entry("praxis_bulk_execution_epoch_check", "CHECK ((owner_epoch >= 1))"),
                Map.entry("praxis_bulk_execution_evaluation_fingerprint_check", "CHECK ((evaluation_fingerprint ~ '^sha256:[0-9a-f]{64}$'::text))"),
                Map.entry("praxis_bulk_execution_evaluation_fkey", "FOREIGN KEY (proposal_id, evaluation_fingerprint) REFERENCES praxis_bulk.praxis_bulk_evaluation(proposal_id, evaluation_fingerprint)"),
                Map.entry("praxis_bulk_execution_input_fingerprint_check", "CHECK ((input_fingerprint ~ '^sha256:[0-9a-f]{64}$'::text))"),
                Map.entry("praxis_bulk_execution_namespace_nonblank_check", "CHECK ((btrim(namespace_id) <> ''::text))"),
                Map.entry("praxis_bulk_execution_operation_nonblank_check", "CHECK ((btrim(operation_id) <> ''::text))"),
                Map.entry("praxis_bulk_execution_owner_nonblank_check", "CHECK ((btrim(owner_id) <> ''::text))"),
                Map.entry("praxis_bulk_execution_pkey", "PRIMARY KEY (execution_id)"),
                Map.entry("praxis_bulk_execution_progress_check", "CHECK ((((target_count >= 1) AND (target_count <= 10000)) AND ((next_ordinal >= 0) AND (next_ordinal <= target_count))))"),
                Map.entry("praxis_bulk_execution_proposal_key", "UNIQUE (proposal_id)"),
                Map.entry("praxis_bulk_execution_reservation_fingerprint_check", "CHECK ((reservation_fingerprint ~ '^sha256:[0-9a-f]{64}$'::text))"),
                Map.entry("praxis_bulk_execution_resource_nonblank_check", "CHECK ((btrim(resource_key) <> ''::text))"),
                Map.entry("praxis_bulk_execution_revision_nonblank_check", "CHECK ((btrim(structural_revision) <> ''::text))"),
                Map.entry("praxis_bulk_execution_scoped_idempotency_key", "UNIQUE (namespace_id, subject_id, resource_key, operation_id, idempotency_key_digest)"),
                Map.entry("praxis_bulk_execution_state_shape_check", "CHECK ((((status = 'RUNNING'::text) AND (active_attempt_id IS NULL) AND (next_ordinal < target_count) AND (terminal_at IS NULL)) OR ((status = ANY (ARRAY['UNIT_IN_FLIGHT'::text, 'UNIT_COMMITTED_PENDING_ACK'::text])) AND (active_attempt_id IS NOT NULL) AND (terminal_at IS NULL)) OR ((status = ANY (ARRAY['COMPLETED'::text, 'COMPLETED_WITH_ERRORS'::text])) AND (active_attempt_id IS NULL) AND (next_ordinal = target_count) AND (terminal_at IS NOT NULL)) OR ((status = 'STOPPED'::text) AND (terminal_at IS NOT NULL) AND (active_attempt_id IS NULL) AND (active_attempt_ordinal IS NULL) AND (active_target_digest IS NULL) AND (active_attempt_epoch IS NULL)) OR ((status = 'RECONCILIATION_REQUIRED'::text) AND (terminal_at IS NULL))))"),
                Map.entry("praxis_bulk_execution_status_check", "CHECK ((status = ANY (ARRAY['RUNNING'::text, 'UNIT_IN_FLIGHT'::text, 'UNIT_COMMITTED_PENDING_ACK'::text, 'COMPLETED'::text, 'COMPLETED_WITH_ERRORS'::text, 'STOPPED'::text, 'RECONCILIATION_REQUIRED'::text])))"),
                Map.entry("praxis_bulk_execution_terminal_reason_check", "CHECK ((((status = 'STOPPED'::text) = (terminal_reason_code IS NOT NULL)) AND ((terminal_reason_code IS NULL) OR (terminal_reason_code = ANY (ARRAY['LEGACY_REASON_NOT_RECORDED'::text, 'DEADLINE_EXCEEDED'::text, 'AUTHORIZATION_REVOKED'::text, 'POLICY_BLOCKED'::text, 'COMMON_GOVERNANCE_CHANGED'::text, 'COMMON_GOVERNANCE_UNAVAILABLE'::text, 'DEPENDENCY_UNAVAILABLE'::text, 'UNIT_ROLLED_BACK'::text, 'RECOVERY_STOPPED'::text, 'EVALUATOR_UNAVAILABLE'::text, 'STRUCTURAL_REVISION_CHANGED'::text, 'CANCELLED_BY_USER'::text])))))"),
                Map.entry("praxis_bulk_execution_cancel_shape_check", "CHECK (((terminal_reason_code IS DISTINCT FROM 'CANCELLED_BY_USER'::text) OR ((cancel_requested_at IS NOT NULL) AND (next_ordinal < target_count) AND (active_attempt_id IS NULL))))"),
                Map.entry("praxis_bulk_execution_cancel_terminal_check", "CHECK (((cancel_requested_at IS NULL) OR (status <> 'STOPPED'::text) OR (terminal_reason_code = 'CANCELLED_BY_USER'::text)))"),
                Map.entry("praxis_bulk_execution_time_order_check", "CHECK (((created_at <= updated_at) AND ((terminal_at IS NULL) OR ((created_at <= terminal_at) AND (terminal_at <= updated_at))) AND ((cancel_requested_at IS NULL) OR ((created_at <= cancel_requested_at) AND (cancel_requested_at <= updated_at) AND ((terminal_at IS NULL) OR (cancel_requested_at <= terminal_at))))))"),
                Map.entry("praxis_bulk_execution_active_unit_deadline_check", "CHECK (((active_unit_deadline_at IS NULL) OR ((active_attempt_id IS NOT NULL) AND (active_unit_deadline_at <= deadline_at))))"),
                Map.entry("praxis_bulk_execution_subject_nonblank_check", "CHECK ((btrim(subject_id) <> ''::text))")));
        validateDurableConstraints(connection, "praxis_bulk_item_receipt", false, Map.ofEntries(
                Map.entry("praxis_bulk_item_receipt_attempt_key", "UNIQUE (attempt_id)"),
                Map.entry("praxis_bulk_item_receipt_epoch_check", "CHECK ((owner_epoch >= 1))"),
                Map.entry("praxis_bulk_item_receipt_execution_fkey", "FOREIGN KEY (execution_id) REFERENCES praxis_bulk.praxis_bulk_execution(execution_id)"),
                Map.entry("praxis_bulk_item_receipt_expected_version_nonblank_check", "CHECK ((btrim(expected_version) <> ''::text))"),
                Map.entry("praxis_bulk_item_receipt_ordinal_check", "CHECK ((unit_ordinal >= 0))"),
                Map.entry("praxis_bulk_item_receipt_outcome_check", "CHECK ((outcome = ANY (ARRAY['CONFIRMED'::text, 'UNCHANGED'::text])))"),
                Map.entry("praxis_bulk_item_receipt_pkey", "PRIMARY KEY (execution_id, unit_ordinal)"),
                Map.entry("praxis_bulk_item_receipt_target_digest_check", "CHECK ((target_digest ~ '^sha256:[0-9a-f]{64}$'::text))"),
                Map.entry("praxis_bulk_item_receipt_unit_deadline_check", "CHECK (((unit_deadline_at IS NULL) OR (confirmed_at < unit_deadline_at)))"),
                Map.entry("praxis_bulk_item_receipt_target_key", "UNIQUE (execution_id, target_digest)")));
        validateDurableTrigger(connection, "praxis_bulk_execution", "praxis_bulk_execution_protect_binding", "protect_praxis_bulk_execution_binding",
                "CREATE TRIGGER praxis_bulk_execution_protect_binding BEFORE UPDATE ON praxis_bulk.praxis_bulk_execution FOR EACH ROW EXECUTE FUNCTION praxis_bulk.protect_praxis_bulk_execution_binding()",
                """
                begin
                    if new.execution_id is distinct from old.execution_id
                       or new.proposal_id is distinct from old.proposal_id
                       or new.namespace_id is distinct from old.namespace_id
                       or new.subject_id is distinct from old.subject_id
                       or new.resource_key is distinct from old.resource_key
                       or new.operation_id is distinct from old.operation_id
                       or new.idempotency_key_digest is distinct from old.idempotency_key_digest
                       or new.reservation_fingerprint is distinct from old.reservation_fingerprint
                       or new.input_fingerprint is distinct from old.input_fingerprint
                       or new.evaluation_fingerprint is distinct from old.evaluation_fingerprint
                       or new.structural_revision is distinct from old.structural_revision
                       or new.atomicity is distinct from old.atomicity
                       or new.protocol_version is distinct from old.protocol_version
                       or new.target_count is distinct from old.target_count
                       or new.deadline_at is distinct from old.deadline_at
                       or new.created_at is distinct from old.created_at then
                        raise exception 'praxis_bulk.praxis_bulk_execution binding is immutable' using errcode = '55000';
                    end if;
                    return new;
                end;
                """);
        validateDurableTrigger(connection, EXECUTION_TABLE, TERMINAL_REASON_TRIGGER, TERMINAL_REASON_FUNCTION,
                "CREATE TRIGGER praxis_bulk_execution_protect_terminal_reason BEFORE INSERT OR UPDATE ON praxis_bulk.praxis_bulk_execution FOR EACH ROW EXECUTE FUNCTION praxis_bulk.protect_praxis_bulk_terminal_reason()",
                """
                begin
                    if tg_op = 'INSERT' then
                        if new.terminal_reason_code = 'LEGACY_REASON_NOT_RECORDED' then
                            raise exception 'legacy stop reason is reserved for migration history' using errcode = '55000';
                        end if;
                    else
                        if old.status = 'STOPPED'
                           and new.terminal_reason_code is distinct from old.terminal_reason_code then
                            raise exception 'terminal stop reason is immutable' using errcode = '55000';
                        end if;
                        if old.status is distinct from 'STOPPED'
                           and new.status = 'STOPPED'
                           and new.terminal_reason_code = 'LEGACY_REASON_NOT_RECORDED' then
                            raise exception 'legacy stop reason is reserved for migration history' using errcode = '55000';
                        end if;
                    end if;
                    return new;
                end;
                """);
        validateDurableTrigger(connection, "praxis_bulk_item_receipt", "praxis_bulk_item_receipt_reject_mutation", "reject_praxis_bulk_item_receipt_mutation",
                "CREATE TRIGGER praxis_bulk_item_receipt_reject_mutation BEFORE DELETE OR UPDATE ON praxis_bulk.praxis_bulk_item_receipt FOR EACH ROW EXECUTE FUNCTION praxis_bulk.reject_praxis_bulk_item_receipt_mutation()",
                """
                begin
                    if tg_op = 'DELETE' and current_user = 'praxis_bulk_retention_owner' then
                        return old;
                    end if;
                    raise exception 'praxis_bulk.praxis_bulk_item_receipt is immutable' using errcode = '55000';
                end;
                """);
    }

    /** V16 evidence is a separate closed topology, never an alias for per-item receipts. */
    private static void validateAtomicCatalog(Connection connection) throws SQLException {
        validateDurableColumns(connection, ATOMIC_RECEIPT_TABLE, Map.ofEntries(
                Map.entry("execution_id", "uuid|true"), Map.entry("attempt_id", "uuid|true"),
                Map.entry("owner_epoch", "bigint|true"), Map.entry("set_digest", "text|true"),
                Map.entry("target_count", "integer|true"),
                Map.entry("effect_count", "integer|true"),
                Map.entry("effect_digest", "text|true"),
                Map.entry("confirmed_at", "timestamp with time zone|true"),
                Map.entry("unit_deadline_at", "timestamp with time zone|true")));
        validateDurableColumns(connection, ATOMIC_ITEM_TABLE, Map.ofEntries(
                Map.entry("execution_id", "uuid|true"), Map.entry("unit_ordinal", "integer|true"),
                Map.entry("target_digest", "text|true"), Map.entry("expected_version", "text|true"),
                Map.entry("outcome", "text|true")));
        validateDurableColumns(connection, ATOMIC_EFFECT_TABLE, Map.ofEntries(
                Map.entry("execution_id", "uuid|true"), Map.entry("unit_ordinal", "integer|true"),
                Map.entry("effect_ref", "text|true")));
        validateDurableColumns(connection, ATOMIC_REJECTION_TABLE, Map.ofEntries(
                Map.entry("execution_id", "uuid|true"), Map.entry("attempt_id", "uuid|true"),
                Map.entry("set_digest", "text|true"), Map.entry("reason_code", "text|true"),
                Map.entry("recorded_at", "timestamp with time zone|true")));
        validateDurableColumns(connection, ATOMIC_BOOTSTRAP_TABLE,
                Map.of("bootstrap_version", "integer|true", "phase", "text|true"));
        validateAtomicConstraintTopology(connection, ATOMIC_RECEIPT_TABLE, Set.of(
                "praxis_bulk_atomic_receipt_pkey", "praxis_bulk_atomic_receipt_attempt_id_key",
                "praxis_bulk_atomic_receipt_execution_id_fkey",
                "praxis_bulk_atomic_receipt_owner_epoch_check",
                "praxis_bulk_atomic_receipt_set_digest_check",
                "praxis_bulk_atomic_receipt_target_count_check",
                "praxis_bulk_atomic_receipt_effect_count_check",
                "praxis_bulk_atomic_receipt_effect_digest_check",
                "praxis_bulk_atomic_receipt_time_check"));
        validateAtomicConstraintTopology(connection, ATOMIC_ITEM_TABLE, Set.of(
                "praxis_bulk_atomic_item_result_pkey", "praxis_bulk_atomic_item_result_target_key",
                "praxis_bulk_atomic_item_result_execution_id_fkey",
                "praxis_bulk_atomic_item_result_unit_ordinal_check",
                "praxis_bulk_atomic_item_result_target_digest_check",
                "praxis_bulk_atomic_item_result_expected_version_check",
                "praxis_bulk_atomic_item_result_outcome_check"));
        validateAtomicConstraintTopology(connection, ATOMIC_EFFECT_TABLE, Set.of(
                "praxis_bulk_atomic_effect_ref_pkey", "praxis_bulk_atomic_effect_ref_item_fkey",
                "praxis_bulk_atomic_effect_ref_effect_ref_check"));
        validateAtomicConstraintTopology(connection, ATOMIC_REJECTION_TABLE, Set.of(
                "praxis_bulk_atomic_rejection_pkey", "praxis_bulk_atomic_rejection_attempt_id_key",
                "praxis_bulk_atomic_rejection_execution_id_fkey",
                "praxis_bulk_atomic_rejection_set_digest_check",
                "praxis_bulk_atomic_rejection_reason_code_check"));
        validateAtomicConstraintTopology(connection, ATOMIC_BOOTSTRAP_TABLE, Set.of(
                "praxis_bulk_atomic_bootstrap_pkey",
                "praxis_bulk_atomic_bootstrap_bootstrap_version_check",
                "praxis_bulk_atomic_bootstrap_phase_check"));
    }

    private static void validateAtomicConstraintTopology(Connection connection, String table,
            Set<String> expected) throws SQLException {
        var actual = new LinkedHashSet<String>();
        try (var statement = connection.prepareStatement("""
                select c.conname,pg_get_constraintdef(c.oid),c.convalidated,c.condeferrable,c.condeferred,
                       i.indisvalid,i.indisready,i.indislive,i.indimmediate
                  from pg_constraint c left join pg_index i on i.indexrelid=c.conindid
                 where c.conrelid=?::regclass
                """)) {
            statement.setString(1, SCHEMA + "." + table);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    require(rows.getBoolean(3) && !rows.getBoolean(4) && !rows.getBoolean(5),
                            "V16 atomic constraint is not validated/immediate: " + table);
                    if (rows.getObject(6) != null)
                        require(rows.getBoolean(6) && rows.getBoolean(7)
                                        && rows.getBoolean(8) && rows.getBoolean(9),
                                "V16 atomic constraint index is invalid: " + table);
                    String name = rows.getString(1);
                    require(normalizeExpression(rows.getString(2)).equals(
                                    normalizeExpression(atomicConstraintDefinition(name))),
                            "V16 atomic constraint definition differs: " + name);
                    require(actual.add(name), "duplicate V16 atomic constraint");
                }
            }
        }
        require(actual.equals(expected), "V16 atomic constraint topology differs: " + table);
    }

    private static String atomicConstraintDefinition(String name) {
        return switch (name) {
            case "praxis_bulk_atomic_receipt_pkey" -> "PRIMARY KEY (execution_id)";
            case "praxis_bulk_atomic_receipt_attempt_id_key" -> "UNIQUE (attempt_id)";
            case "praxis_bulk_atomic_receipt_execution_id_fkey" ->
                    "FOREIGN KEY (execution_id) REFERENCES praxis_bulk.praxis_bulk_execution(execution_id)";
            case "praxis_bulk_atomic_receipt_owner_epoch_check" -> "CHECK ((owner_epoch >= 1))";
            case "praxis_bulk_atomic_receipt_set_digest_check" ->
                    "CHECK ((set_digest ~ '^sha256:[0-9a-f]{64}$'::text))";
            case "praxis_bulk_atomic_receipt_target_count_check" ->
                    "CHECK (((target_count >= 1) AND (target_count <= 50)))";
            case "praxis_bulk_atomic_receipt_effect_count_check" ->
                    "CHECK (((effect_count >= 0) AND (effect_count <= 400)))";
            case "praxis_bulk_atomic_receipt_effect_digest_check" ->
                    "CHECK ((effect_digest ~ '^sha256:[0-9a-f]{64}$'::text))";
            case "praxis_bulk_atomic_receipt_time_check" ->
                    "CHECK ((confirmed_at < unit_deadline_at))";
            case "praxis_bulk_atomic_item_result_pkey" ->
                    "PRIMARY KEY (execution_id, unit_ordinal)";
            case "praxis_bulk_atomic_item_result_target_key" ->
                    "UNIQUE (execution_id, target_digest)";
            case "praxis_bulk_atomic_item_result_execution_id_fkey" ->
                    "FOREIGN KEY (execution_id) REFERENCES praxis_bulk.praxis_bulk_atomic_receipt(execution_id)";
            case "praxis_bulk_atomic_item_result_unit_ordinal_check" ->
                    "CHECK (((unit_ordinal >= 0) AND (unit_ordinal <= 49)))";
            case "praxis_bulk_atomic_item_result_target_digest_check" ->
                    "CHECK ((target_digest ~ '^sha256:[0-9a-f]{64}$'::text))";
            case "praxis_bulk_atomic_item_result_expected_version_check" ->
                    "CHECK ((btrim(expected_version) <> ''::text))";
            case "praxis_bulk_atomic_item_result_outcome_check" ->
                    "CHECK ((outcome = ANY (ARRAY['CONFIRMED'::text, 'UNCHANGED'::text])))";
            case "praxis_bulk_atomic_effect_ref_pkey" ->
                    "PRIMARY KEY (execution_id, effect_ref)";
            case "praxis_bulk_atomic_effect_ref_item_fkey" ->
                    "FOREIGN KEY (execution_id, unit_ordinal) REFERENCES praxis_bulk.praxis_bulk_atomic_item_result(execution_id, unit_ordinal)";
            case "praxis_bulk_atomic_effect_ref_effect_ref_check" ->
                    "CHECK ((((length(effect_ref) >= 1) AND (length(effect_ref) <= 200))"
                            + " AND (effect_ref = btrim(effect_ref, concat("
                            + atomicChrArguments(BulkTargetDigest.edgeWhitespaceCodePoints()) + ")))"
                            + " AND (effect_ref = translate(effect_ref, concat("
                            + atomicChrArguments(BulkTargetDigest.storedControlCodePoints())
                            + "), ''::text))))";
            case "praxis_bulk_atomic_rejection_pkey" -> "PRIMARY KEY (execution_id)";
            case "praxis_bulk_atomic_rejection_attempt_id_key" -> "UNIQUE (attempt_id)";
            case "praxis_bulk_atomic_rejection_execution_id_fkey" ->
                    "FOREIGN KEY (execution_id) REFERENCES praxis_bulk.praxis_bulk_execution(execution_id)";
            case "praxis_bulk_atomic_rejection_set_digest_check" ->
                    "CHECK ((set_digest ~ '^sha256:[0-9a-f]{64}$'::text))";
            case "praxis_bulk_atomic_rejection_reason_code_check" ->
                    "CHECK ((reason_code = ANY (ARRAY['TARGET_VERSION_CONFLICT'::text, 'TARGET_STATE_CONFLICT'::text, 'TARGET_NOT_FOUND'::text, 'TARGET_DENIED'::text, 'TARGET_INVALID'::text, 'TARGET_DEPENDENCY_CHANGED'::text, 'DEADLINE_EXCEEDED'::text, 'AUTHORIZATION_REVOKED'::text, 'POLICY_BLOCKED'::text, 'COMMON_GOVERNANCE_CHANGED'::text, 'COMMON_GOVERNANCE_UNAVAILABLE'::text, 'DEPENDENCY_UNAVAILABLE'::text, 'UNIT_ROLLED_BACK'::text, 'RECOVERY_STOPPED'::text, 'EVALUATOR_UNAVAILABLE'::text, 'STRUCTURAL_REVISION_CHANGED'::text])))";
            case "praxis_bulk_atomic_bootstrap_pkey" -> "PRIMARY KEY (bootstrap_version)";
            case "praxis_bulk_atomic_bootstrap_bootstrap_version_check" ->
                    "CHECK ((bootstrap_version = 16))";
            case "praxis_bulk_atomic_bootstrap_phase_check" ->
                    "CHECK ((phase = ANY (ARRAY['PENDING'::text, 'COMPLETE'::text])))";
            default -> throw new IllegalStateException("Unexpected V16 atomic constraint: " + name);
        };
    }

    private static String atomicChrArguments(int[] codePoints) {
        var arguments = new ArrayList<String>(codePoints.length);
        for (int codePoint : codePoints) arguments.add("chr(" + codePoint + ")");
        return String.join(", ", arguments);
    }

    /** V4 never widens the meaning of a V3 receipt: admission certifies no mutation. */
    private static void validateDurableAdmission(Connection connection) throws SQLException {
        validateDurableColumns(connection, ADMISSION_TABLE, Map.ofEntries(
                Map.entry("execution_id", "uuid|true"),
                Map.entry("unit_ordinal", "integer|true"),
                Map.entry("target_digest", "text|true"),
                Map.entry("expected_version", "text|true"),
                Map.entry("attempt_id", "uuid|true"),
                Map.entry("owner_epoch", "bigint|true"),
                Map.entry("outcome", "text|true"),
                Map.entry("reason_code", "text|true"),
                Map.entry("recorded_at", "timestamp(6) with time zone|true")));
        validateDurableConstraints(connection, ADMISSION_TABLE, false, Map.ofEntries(
                Map.entry("praxis_bulk_admission_pkey", "PRIMARY KEY (execution_id, unit_ordinal)"),
                Map.entry("praxis_bulk_admission_target_key", "UNIQUE (execution_id, target_digest)"),
                Map.entry("praxis_bulk_admission_attempt_key", "UNIQUE (attempt_id)"),
                Map.entry("praxis_bulk_admission_execution_fkey", "FOREIGN KEY (execution_id) REFERENCES praxis_bulk.praxis_bulk_execution(execution_id)"),
                Map.entry("praxis_bulk_admission_ordinal_check", "CHECK ((unit_ordinal >= 0))"),
                Map.entry("praxis_bulk_admission_target_digest_check", "CHECK ((target_digest ~ '^sha256:[0-9a-f]{64}$'::text))"),
                Map.entry("praxis_bulk_admission_expected_version_nonblank_check", "CHECK ((btrim(expected_version) <> ''::text))"),
                Map.entry("praxis_bulk_admission_epoch_check", "CHECK ((owner_epoch >= 1))"),
                Map.entry("praxis_bulk_admission_outcome_reason_check", "CHECK ((((outcome = 'DENIED'::text) AND (reason_code = 'TARGET_DENIED'::text)) OR ((outcome = 'INVALID'::text) AND (reason_code = ANY (ARRAY['TARGET_NOT_FOUND'::text, 'TARGET_INVALID'::text]))) OR ((outcome = 'CONFLICT'::text) AND (reason_code = ANY (ARRAY['TARGET_VERSION_CONFLICT'::text, 'TARGET_STATE_CONFLICT'::text, 'TARGET_DEPENDENCY_CHANGED'::text])))))")));
        validateDurableTrigger(connection, ADMISSION_TABLE, ADMISSION_TRIGGER, ADMISSION_FUNCTION,
                "CREATE TRIGGER praxis_bulk_admission_reject_mutation BEFORE DELETE OR UPDATE ON praxis_bulk.praxis_bulk_admission FOR EACH ROW EXECUTE FUNCTION praxis_bulk.reject_praxis_bulk_admission_mutation()",
                """
                begin
                    if tg_op = 'DELETE' and current_user = 'praxis_bulk_retention_owner' then
                        return old;
                    end if;
                    raise exception 'praxis_bulk.praxis_bulk_admission is immutable' using errcode = '55000';
                end;
                """);
        // The host grants SELECT/INSERT to its runtime role explicitly. Both table- and
        // column-level ACLs must reject PUBLIC, mutation rights and grant options.
        try (var statement = connection.prepareStatement("""
                select count(*)
                from (
                    select acl.grantee, acl.privilege_type, acl.is_grantable, c.relowner
                    from pg_class c,
                         lateral aclexplode(coalesce(c.relacl, acldefault('r', c.relowner))) acl
                    where c.oid = ?::regclass
                    union all
                    select acl.grantee, acl.privilege_type, acl.is_grantable, c.relowner
                    from pg_class c join pg_attribute a on a.attrelid = c.oid,
                         lateral aclexplode(a.attacl) acl
                    where c.oid = ?::regclass and a.attnum > 0 and not a.attisdropped
                      and a.attacl is not null
                ) grants
                where grantee <> relowner
                  and (grantee = 0 or privilege_type not in ('SELECT', 'INSERT') or is_grantable)
                  and not (grantee = (select oid from pg_roles
                                      where rolname = 'praxis_bulk_retention_owner')
                           and privilege_type = 'DELETE' and not is_grantable)
                """)) {
            statement.setString(1, SCHEMA + "." + ADMISSION_TABLE);
            statement.setString(2, SCHEMA + "." + ADMISSION_TABLE);
            try (var rows = statement.executeQuery()) {
                require(rows.next() && rows.getLong(1) == 0 && !rows.next(),
                        "admission storage grants must be limited to scoped SELECT/INSERT roles");
            }
        }
    }

    /** V5 is a security boundary, so validate its physical catalog rather than trusting history alone. */
    private static void validateGovernedLifecycleCatalog(Connection connection,
            BulkExecutionRoleConfiguration roles) throws SQLException {
        validateGovernedLifecycleCatalog(connection, roles, Map.of());
    }

    private static void validateGovernedLifecycleCatalog(Connection connection,
            BulkExecutionRoleConfiguration roles, Map<String, String> phases) throws SQLException {
        validateGovernedLifecycleCatalog(connection, roles, phases, false);
    }

    private static void validateGovernedLifecycleCatalog(Connection connection,
            BulkExecutionRoleConfiguration roles, Map<String, String> phases,
            boolean pendingLegacyDerivation) throws SQLException {
        validateV5Columns(connection);
        validateV5Constraints(connection);
        validateV5Indexes(connection);
        validateV5Triggers(connection);
        validateV5Functions(connection, roles, phases);
        validateV5RolesAndPrivileges(connection, roles, false, phases);
        validateOpenApiPublicationCatalog(connection, pendingLegacyDerivation);
        validateDescriptorFenceCatalog(connection);
    }

    private static void validateOpenApiPublicationCatalog(Connection connection) throws SQLException {
        validateOpenApiPublicationCatalog(connection, false);
    }

    private static void validateOpenApiPublicationCatalog(Connection connection,
            boolean pendingLegacyDerivation) throws SQLException {
        // V14 DDL creates an empty ledger. Only the first owner lifecycle bootstrap
        // may insert absent UNCOMPOSED identities; existing rows/ACLs remain exact.
        validateDurableColumns(connection, OPENAPI_PUBLICATION_TABLE, Map.ofEntries(
                Map.entry("deployment_id", "text|true"),
                Map.entry("state", "text|true"), Map.entry("generation", "bigint|true"),
                Map.entry("document_digest", "text|false"), Map.entry("updated_at", "timestamp with time zone|true")));
        validateDurableConstraints(connection, OPENAPI_PUBLICATION_TABLE, false, Map.ofEntries(
                Map.entry("praxis_bulk_openapi_publication_pkey", "PRIMARY KEY (deployment_id)"),
                Map.entry("praxis_bulk_openapi_publication_binding_fkey", "FOREIGN KEY (deployment_id) REFERENCES praxis_bulk.praxis_bulk_deployment_bucket(deployment_id) ON DELETE RESTRICT"),
                Map.entry("praxis_bulk_openapi_publication_deployment_check", "CHECK (((deployment_id <> ''::text) AND (deployment_id !~ '^[[:space:]]|[[:space:]]$|[[:cntrl:]]'::text)))"),
                Map.entry("praxis_bulk_openapi_publication_generation_check", "CHECK ((generation >= 0))"),
                Map.entry("praxis_bulk_openapi_publication_state_check", "CHECK ((state = ANY (ARRAY['UNCOMPOSED'::text, 'SUSPENDED'::text, 'PUBLISHED'::text])))"),
                Map.entry("praxis_bulk_openapi_publication_digest_check", "CHECK ((((state = 'PUBLISHED'::text) AND (document_digest IS NOT NULL) AND (document_digest ~ '^sha256:[0-9a-f]{64}$'::text)) OR ((state = ANY (ARRAY['UNCOMPOSED'::text, 'SUSPENDED'::text])) AND (document_digest IS NULL))))")));
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
                select count(*) from (select distinct deployment_id from praxis_bulk.praxis_bulk_namespace_binding) b
                left join praxis_bulk.praxis_bulk_openapi_publication p on p.deployment_id=b.deployment_id
                where p.deployment_id is null
                """)) {
            require(rows.next() && (pendingLegacyDerivation || rows.getLong(1) == 0) && !rows.next(),
                    "every bound deployment requires its durable OpenAPI publication row");
        }
    }

    private static void validateManifestCatalog(Connection connection,
            BulkExecutionRoleConfiguration roles) throws SQLException {
        validateManifestCatalog(connection, roles, "COMPLETE");
    }

    private static void validateManifestCatalog(Connection connection,
            BulkExecutionRoleConfiguration roles, String expectedPhase) throws SQLException {
        validateDurableColumns(connection, MANIFEST_BOOTSTRAP_TABLE, Map.of(
                "bootstrap_version", "integer|true", "phase", "text|true"));
        validateDurableConstraints(connection, MANIFEST_BOOTSTRAP_TABLE, false, Map.of(
                "praxis_bulk_manifest_bootstrap_pkey", "PRIMARY KEY (bootstrap_version)",
                "praxis_bulk_manifest_bootstrap_version_check", "CHECK ((bootstrap_version = 8))",
                "praxis_bulk_manifest_bootstrap_phase_check",
                        "CHECK ((phase = ANY (ARRAY['PENDING'::text, 'COMPLETE'::text])))"));
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
                select bootstrap_version, phase from praxis_bulk.praxis_bulk_manifest_bootstrap
                """)) {
            require(rows.next() && rows.getInt(1) == 8 && expectedPhase.equals(rows.getString(2))
                    && !rows.next(), "V8 manifest bootstrap is not complete");
        }
        validateManifestBootstrapAcl(connection);
        validateDurableColumns(connection, MANIFEST_TABLE, Map.of(
                "proposal_id", "uuid|true", "evaluation_fingerprint", "text|true",
                "ordinal", "integer|true", "wire_identity", "bytea|true",
                "wire_identity_digest", "text|true", "expected_version", "bytea|true",
                "target_count", "integer|true", "target_digest", "text|true"));
        var expectedConstraints = Map.ofEntries(
                Map.entry("praxis_bulk_target_manifest_pkey", "PRIMARY KEY (proposal_id, ordinal)"),
                Map.entry("praxis_bulk_target_manifest_proposal_fkey",
                        "FOREIGN KEY (proposal_id) REFERENCES praxis_bulk.praxis_bulk_proposal(proposal_id) ON DELETE RESTRICT"),
                Map.entry("praxis_bulk_target_manifest_evaluation_fkey",
                        "FOREIGN KEY (proposal_id, evaluation_fingerprint) REFERENCES praxis_bulk.praxis_bulk_evaluation(proposal_id, evaluation_fingerprint) ON DELETE RESTRICT"),
                Map.entry("praxis_bulk_target_manifest_identity_key", "UNIQUE (proposal_id, wire_identity_digest)"),
                Map.entry("praxis_bulk_target_manifest_ordinal_check", "CHECK (((ordinal >= 0) AND (ordinal <= 9999)))"),
                Map.entry("praxis_bulk_target_manifest_identity_check", "CHECK (((octet_length(wire_identity) >= 1) AND (octet_length(wire_identity) <= 8388608)))"),
                Map.entry("praxis_bulk_target_manifest_identity_digest_check", "CHECK ((wire_identity_digest ~ '^sha256:[0-9a-f]{64}$'::text))"),
                Map.entry("praxis_bulk_target_manifest_expected_version_check", "CHECK (((octet_length(expected_version) >= 1) AND (octet_length(expected_version) <= 8388608)))"),
                Map.entry("praxis_bulk_target_manifest_target_count_check", "CHECK (((target_count >= 1) AND (target_count <= 10000)))"),
                Map.entry("praxis_bulk_target_manifest_digest_check", "CHECK ((target_digest ~ '^sha256:[0-9a-f]{64}$'::text))"));
        validateDurableConstraints(connection, MANIFEST_TABLE, false, expectedConstraints);
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
                select t.tgname, t.tgenabled, t.tgdeferrable, t.tginitdeferred,
                       p.proname, t.tgtype, t.tgconstraint <> 0
                  from pg_trigger t join pg_proc p on p.oid=t.tgfoid
                 where t.tgrelid='praxis_bulk.praxis_bulk_target_manifest'::regclass
                   and not t.tgisinternal
                union all
                select t.tgname, t.tgenabled, t.tgdeferrable, t.tginitdeferred,
                       p.proname, t.tgtype, t.tgconstraint <> 0
                  from pg_trigger t join pg_proc p on p.oid=t.tgfoid
                 where t.tgrelid='praxis_bulk.praxis_bulk_evaluation'::regclass
                   and t.tgname='praxis_bulk_evaluation_require_manifest'
                """)) {
            var names = new LinkedHashSet<String>();
            while (rows.next()) {
                String name = rows.getString(1);
                require("O".equals(rows.getString(2)), "manifest trigger disabled: " + name);
                if (name.equals("praxis_bulk_evaluation_require_manifest")) {
                    require(rows.getBoolean(3) && rows.getBoolean(4)
                            && "require_complete_target_manifest".equals(rows.getString(5))
                            && rows.getInt(6) == 5 && rows.getBoolean(7),
                            "evaluation manifest commit fence differs");
                } else {
                    require(!rows.getBoolean(3) && !rows.getBoolean(4)
                            && (name.equals("praxis_bulk_target_manifest_immutable")
                                ? "reject_target_manifest_mutation" : "guard_lifecycle_delete")
                                .equals(rows.getString(5))
                            && rows.getInt(6) == (name.equals("praxis_bulk_target_manifest_immutable") ? 27 : 11)
                            && !rows.getBoolean(7), "manifest mutation guard differs");
                }
                names.add(name);
            }
            require(names.equals(Set.of("praxis_bulk_evaluation_require_manifest",
                    "praxis_bulk_target_manifest_immutable", "praxis_bulk_target_manifest_guard_delete")),
                    "manifest trigger inventory differs");
        }
        for (String role : roles.runtimeGranteeRoles()) {
            require(tableRolePrivileges(connection, MANIFEST_TABLE, role).equals(bootstrapTableGrants(expectedPhase)),
                    "manifest runtime grants differ: " + role);
        }
    }

    private static void validateManifestBootstrapAcl(Connection connection) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
                select count(*) from (
                    select acl.grantee, acl.grantor, c.relowner
                      from pg_class c cross join lateral
                           aclexplode(coalesce(c.relacl, acldefault('r', c.relowner))) acl
                     where c.oid='praxis_bulk.praxis_bulk_manifest_bootstrap'::regclass
                    union all
                    select acl.grantee, acl.grantor, c.relowner
                      from pg_class c join pg_attribute a on a.attrelid=c.oid
                           cross join lateral aclexplode(a.attacl) acl
                     where c.oid='praxis_bulk.praxis_bulk_manifest_bootstrap'::regclass
                       and a.attnum>0 and not a.attisdropped and a.attacl is not null
                ) grants where grantee <> relowner or grantor <> relowner
                """)) {
            require(rows.next() && rows.getLong(1) == 0 && !rows.next(),
                    "manifest bootstrap marker ACL must be owner-only");
        }
    }

    private static void validatePreviewCatalog(Connection connection,
            BulkExecutionRoleConfiguration roles) throws SQLException {
        validatePreviewCatalog(connection, roles, "COMPLETE");
    }

    private static void validatePreviewCatalog(Connection connection,
            BulkExecutionRoleConfiguration roles, String expectedPhase) throws SQLException {
        validateDurableColumns(connection, PREVIEW_BOOTSTRAP_TABLE, Map.of(
                "bootstrap_version", "integer|true", "phase", "text|true"));
        validateDurableColumns(connection, PREVIEW_STATE_TABLE, Map.of(
                "proposal_id", "uuid|true", "evaluation_fingerprint", "text|true",
                "projection_state", "text|true", "projector_revision", "text|false",
                "target_count", "integer|false", "public_allowlist", "bytea|false",
                "projection_digest", "text|false", "integrity_version", "integer|true"));
        validateDurableColumns(connection, TARGET_PREVIEW_TABLE, Map.of(
                "proposal_id", "uuid|true", "evaluation_fingerprint", "text|true",
                "ordinal", "integer|true", "decision", "text|true", "diagnostics", "bytea|true"));
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
                select bootstrap_version, phase from praxis_bulk.praxis_bulk_preview_bootstrap
                """)) {
            require(rows.next() && rows.getInt(1) == 9 && expectedPhase.equals(rows.getString(2))
                    && !rows.next(), "V9 preview bootstrap is not complete");
        }
        validateOwnerOnlyTableAcl(connection, PREVIEW_BOOTSTRAP_TABLE);
        validatePreviewConstraints(connection, PREVIEW_BOOTSTRAP_TABLE, Map.of(
                "praxis_bulk_preview_bootstrap_pkey", "PRIMARY KEY (bootstrap_version)",
                "praxis_bulk_preview_bootstrap_bootstrap_version_check", "CHECK ((bootstrap_version = 9))",
                "praxis_bulk_preview_bootstrap_phase_check", "CHECK ((phase = ANY (ARRAY['PENDING'::text, 'COMPLETE'::text])))"));
        validatePreviewConstraints(connection, PREVIEW_STATE_TABLE, Map.of(
                "praxis_bulk_preview_state_pkey", "PRIMARY KEY (proposal_id)",
                "praxis_bulk_preview_state_evaluation_fkey", "FOREIGN KEY (proposal_id, evaluation_fingerprint) REFERENCES praxis_bulk.praxis_bulk_evaluation(proposal_id, evaluation_fingerprint) ON DELETE RESTRICT",
                "praxis_bulk_preview_state_binding_key", "UNIQUE (proposal_id, evaluation_fingerprint)",
                "praxis_bulk_preview_state_state_check", "CHECK ((((projection_state = 'COMPLETE'::text) AND (projector_revision IS NOT NULL) AND ((length(projector_revision) >= 1) AND (length(projector_revision) <= 128)) AND (target_count IS NOT NULL) AND (public_allowlist IS NOT NULL) AND ((octet_length(public_allowlist) >= 2) AND (octet_length(public_allowlist) <= 65536)) AND (projection_digest ~ '^sha256:[0-9a-f]{64}$'::text)) OR ((projection_state = ANY (ARRAY['UNAVAILABLE'::text, 'UNAVAILABLE_LEGACY'::text])) AND (projector_revision IS NULL) AND (target_count IS NULL) AND (public_allowlist IS NULL) AND (projection_digest IS NULL))))",
                "praxis_bulk_preview_state_count_check", "CHECK (((target_count IS NULL) OR ((target_count >= 1) AND (target_count <= 10000))))",
                "praxis_bulk_preview_state_integrity_version_check", "CHECK ((integrity_version = 11))"));
        validatePreviewConstraints(connection, TARGET_PREVIEW_TABLE, Map.of(
                "praxis_bulk_target_preview_pkey", "PRIMARY KEY (proposal_id, ordinal)",
                "praxis_bulk_target_preview_manifest_fkey", "FOREIGN KEY (proposal_id, ordinal) REFERENCES praxis_bulk.praxis_bulk_target_manifest(proposal_id, ordinal) ON DELETE RESTRICT",
                "praxis_bulk_target_preview_state_fkey", "FOREIGN KEY (proposal_id, evaluation_fingerprint) REFERENCES praxis_bulk.praxis_bulk_preview_state(proposal_id, evaluation_fingerprint) ON DELETE RESTRICT",
                "praxis_bulk_target_preview_ordinal_check", "CHECK (((ordinal >= 0) AND (ordinal <= 9999)))",
                "praxis_bulk_target_preview_decision_check", "CHECK ((decision = ANY (ARRAY['EXECUTABLE'::text, 'BLOCKED'::text])))",
                "praxis_bulk_target_preview_diagnostics_check", "CHECK (((octet_length(diagnostics) >= 2) AND (octet_length(diagnostics) <= 65536)))"));
        var expected = Set.of("praxis_bulk_evaluation_require_preview",
                "praxis_bulk_target_preview_guard_insert",
                "praxis_bulk_preview_state_immutable", "praxis_bulk_preview_state_guard_delete",
                "praxis_bulk_target_preview_immutable", "praxis_bulk_target_preview_guard_delete");
        Set<String> observed = new LinkedHashSet<>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
                select t.tgname, t.tgenabled, t.tgdeferrable, t.tginitdeferred,
                       p.proname, t.tgtype, t.tgconstraint <> 0
                  from pg_trigger t join pg_proc p on p.oid=t.tgfoid
                 where t.tgrelid in ('praxis_bulk.praxis_bulk_evaluation'::regclass,
                        'praxis_bulk.praxis_bulk_preview_state'::regclass,
                        'praxis_bulk.praxis_bulk_target_preview'::regclass)
                   and t.tgname = any (array['praxis_bulk_evaluation_require_preview',
                        'praxis_bulk_target_preview_guard_insert',
                        'praxis_bulk_preview_state_immutable', 'praxis_bulk_preview_state_guard_delete',
                        'praxis_bulk_target_preview_immutable', 'praxis_bulk_target_preview_guard_delete'])
                """)) {
            while (rows.next()) {
                String name = rows.getString(1);
                require("O".equals(rows.getString(2)), "preview trigger disabled: " + name);
                if (name.equals("praxis_bulk_evaluation_require_preview")) {
                    require(rows.getBoolean(3) && rows.getBoolean(4) && rows.getBoolean(7)
                            && "require_complete_target_preview".equals(rows.getString(5))
                            && rows.getInt(6) == 5, "preview commit fence differs");
                } else if (name.equals("praxis_bulk_target_preview_guard_insert")) {
                    require(!rows.getBoolean(3) && !rows.getBoolean(4) && !rows.getBoolean(7)
                            && "require_complete_preview_parent".equals(rows.getString(5))
                            && rows.getInt(6) == 7, "preview parent insert guard differs");
                } else {
                    boolean immutable = name.endsWith("_immutable");
                    require(!rows.getBoolean(3) && !rows.getBoolean(4) && !rows.getBoolean(7)
                            && (immutable ? "reject_preview_mutation" : "guard_lifecycle_delete").equals(rows.getString(5))
                            && rows.getInt(6) == (immutable ? 27 : 11), "preview mutation guard differs");
                }
                observed.add(name);
            }
        }
        require(observed.equals(expected), "preview trigger inventory differs");
        for (String role : roles.runtimeGranteeRoles())
            for (String table : List.of(PREVIEW_STATE_TABLE, TARGET_PREVIEW_TABLE))
                require(tableRolePrivileges(connection, table, role).equals(bootstrapTableGrants(expectedPhase)),
                        "preview runtime grants differ: " + role + " " + table);
    }

    private static void validatePreviewIntegrityCatalog(Connection connection,
            BulkExecutionRoleConfiguration roles, String expectedPhase) throws SQLException {
        validatePreviewIntegrityCatalog(connection, roles, expectedPhase, false);
    }

    private static void validatePreviewIntegrityCatalog(Connection connection,
            BulkExecutionRoleConfiguration roles, String expectedPhase, boolean beforeGrant) throws SQLException {
        validateDurableColumns(connection, PREVIEW_INTEGRITY_BOOTSTRAP_TABLE, Map.of(
                "bootstrap_version", "integer|true", "phase", "text|true"));
        validateDurableColumns(connection, PREVIEW_INTEGRITY_TABLE, Map.of(
                "proposal_id", "uuid|true", "ordinal", "integer|true",
                "digest_version", "integer|true", "item_digest", "text|true"));
        validateOwnerOnlyTableAcl(connection, PREVIEW_INTEGRITY_BOOTSTRAP_TABLE);
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
                select bootstrap_version, phase from praxis_bulk.praxis_bulk_preview_integrity_bootstrap
                """)) {
            require(rows.next() && rows.getInt(1) == 11 && expectedPhase.equals(rows.getString(2))
                    && !rows.next(), "V11 integrity bootstrap phase differs");
        }
        validatePreviewConstraints(connection, PREVIEW_INTEGRITY_BOOTSTRAP_TABLE, Map.of(
                "praxis_bulk_preview_integrity_bootstrap_pkey", "PRIMARY KEY (bootstrap_version)",
                "praxis_bulk_preview_integrity_bootstrap_bootstrap_version_check", "CHECK ((bootstrap_version = 11))",
                "praxis_bulk_preview_integrity_bootstrap_phase_check",
                        "CHECK ((phase = ANY (ARRAY['PENDING'::text, 'COMPLETE'::text])))"));
        validatePreviewConstraints(connection, PREVIEW_INTEGRITY_TABLE, Map.of(
                "praxis_bulk_preview_item_integrity_pkey", "PRIMARY KEY (proposal_id, ordinal)",
                "praxis_bulk_preview_item_integrity_preview_fkey",
                        "FOREIGN KEY (proposal_id, ordinal) REFERENCES praxis_bulk.praxis_bulk_target_preview(proposal_id, ordinal) ON DELETE RESTRICT",
                "praxis_bulk_preview_item_integrity_version_check", "CHECK ((digest_version = 1))",
                "praxis_bulk_preview_item_integrity_digest_check",
                        "CHECK ((item_digest ~ '^sha256:[0-9a-f]{64}$'::text))"));
        var observed = new LinkedHashSet<String>();
        try (var statement = connection.prepareStatement("""
                select r.relname || '.' || t.tgname, t.tgenabled, t.tgdeferrable,
                       t.tginitdeferred, t.tgconstraint<>0, t.tgtype,
                       p.proname, n.nspname
                  from pg_trigger t join pg_class r on r.oid=t.tgrelid
                  join pg_proc p on p.oid=t.tgfoid join pg_namespace n on n.oid=p.pronamespace
                 where r.relnamespace='praxis_bulk'::regnamespace and not t.tgisinternal
                   and (r.relname || '.' || t.tgname)=any (?::text[])
                """)) {
            statement.setArray(1, connection.createArrayOf("text", V11_TRIGGERS.toArray()));
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    String key = rows.getString(1);
                    require("O".equals(rows.getString(2)) && SCHEMA.equals(rows.getString(8)),
                            "V11 trigger disabled or rebound: " + key);
                    boolean deferred = key.equals(EVALUATION_TABLE + ".praxis_bulk_evaluation_require_preview_item_integrity");
                    require(rows.getBoolean(3) == deferred && rows.getBoolean(4) == deferred
                            && rows.getBoolean(5) == deferred, "V11 trigger deferral differs: " + key);
                    String expectedFunction;
                    int expectedType;
                    if (deferred) {
                        expectedFunction = "require_complete_preview_item_integrity";
                        expectedType = 5;
                    } else if (key.endsWith("integrity_guard_insert")) {
                        expectedFunction = "require_complete_preview_integrity_bootstrap";
                        expectedType = 7;
                    } else if (key.endsWith("_immutable")) {
                        expectedFunction = "reject_preview_mutation";
                        expectedType = 27;
                    } else {
                        expectedFunction = "guard_lifecycle_delete";
                        expectedType = 11;
                    }
                    require(expectedFunction.equals(rows.getString(7)) && rows.getInt(6) == expectedType,
                            "V11 trigger binding differs: " + key);
                    observed.add(key);
                }
            }
        }
        require(observed.equals(V11_TRIGGERS), "V11 trigger inventory differs");
        for (String role : roles.runtimeGranteeRoles())
            require(tableRolePrivileges(connection, PREVIEW_INTEGRITY_TABLE, role)
                    .equals(beforeGrant ? bootstrapTableGrants(expectedPhase) : Set.of("T:SELECT", "T:INSERT")), "V11 runtime grants differ: " + role);
    }

    private static void validatePreviewReaderCatalog(Connection connection, String expectedPhase)
            throws SQLException {
        validateDurableColumns(connection, PREVIEW_READER_BOOTSTRAP_TABLE, Map.of(
                "bootstrap_version", "integer|true", "phase", "text|true"));
        validateOwnerOnlyTableAcl(connection, PREVIEW_READER_BOOTSTRAP_TABLE);
        validatePreviewConstraints(connection, PREVIEW_READER_BOOTSTRAP_TABLE, Map.of(
                "praxis_bulk_preview_reader_bootstrap_pkey", "PRIMARY KEY (bootstrap_version)",
                "praxis_bulk_preview_reader_bootstrap_bootstrap_version_check", "CHECK ((bootstrap_version = 12))",
                "praxis_bulk_preview_reader_bootstrap_phase_check",
                "CHECK ((phase = ANY (ARRAY['PENDING'::text, 'COMPLETE'::text])))"));
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
                select bootstrap_version, phase from praxis_bulk.praxis_bulk_preview_reader_bootstrap
                """)) {
            require(rows.next() && rows.getInt(1) == 12 && expectedPhase.equals(rows.getString(2))
                    && !rows.next(), "V12 reader bootstrap phase differs");
        }
    }

    private static void validatePreviewConstraints(Connection connection, String table, Map<String, String> expected)
            throws SQLException {
        validateDurableConstraints(connection, table, false, expected);
    }

    private static void validateOwnerOnlyTableAcl(Connection connection, String table) throws SQLException {
        try (var statement = connection.prepareStatement("""
                select count(*) from (
                    select acl.grantee, acl.grantor, c.relowner from pg_class c
                    cross join lateral aclexplode(coalesce(c.relacl, acldefault('r', c.relowner))) acl
                    where c.oid=?::regclass
                    union all
                    select acl.grantee, acl.grantor, c.relowner from pg_class c
                    join pg_attribute a on a.attrelid=c.oid
                    cross join lateral aclexplode(a.attacl) acl
                    where c.oid=?::regclass and a.attnum>0 and not a.attisdropped and a.attacl is not null
                ) grants where grantee<>relowner or grantor<>relowner
                """)) {
            statement.setString(1, SCHEMA + "." + table);
            statement.setString(2, SCHEMA + "." + table);
            try (var rows = statement.executeQuery()) {
                require(rows.next() && rows.getLong(1) == 0 && !rows.next(), "preview bootstrap ACL must be owner-only");
            }
        }
    }

    /**
     * Re-attests the governed runtime role on the very connection about to perform work. Unlike
     * {@link #validate(DataSource, BulkExecutionRoleConfiguration)}, this deliberately checks
     * only the live role/ACL/function boundary, not Flyway history or mutable lifecycle rows.
     *
     * <p>The authenticated and effective identities must be the same configured runtime
     * grantee. The full role and controlled-function allowlists are then checked against the
     * current PostgreSQL catalogs, so grants or memberships added after provisioning fail
     * closed before the caller touches the namespace/control rows.</p>
     */
    static void validateLiveRuntimeRoleAccess(Connection connection,
            BulkExecutionRoleConfiguration roles) {
        Objects.requireNonNull(connection, "connection");
        BulkExecutionRoleConfiguration configuration = Objects.requireNonNull(roles, "roles");
        try {
            validateConnectionIdentity(connection, configuration.runtimeGranteeRoles(), null);
            validateV5Functions(connection, configuration);
            validateV5RolesAndPrivileges(connection, configuration, true);
            validateDescriptorFenceCatalog(connection);
            if (BulkCapacityOccupancyCatalog.installed(connection))
                BulkCapacityOccupancyCatalog.validateAccess(connection, configuration);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to attest the live bulk runtime role", failure);
        }
    }

    /** Re-attests the configured CAS-only control-plane role on its operation connection. */
    static void validateLiveControlPlaneRoleAccess(Connection connection,
            BulkExecutionRoleConfiguration roles, String expectedRole) {
        Objects.requireNonNull(connection, "connection");
        BulkExecutionRoleConfiguration configuration = Objects.requireNonNull(roles, "roles");
        String expected = Objects.requireNonNull(expectedRole, "expectedRole");
        require(configuration.controlPlaneGranteeRoles().contains(expected),
                "configured control-plane identity is not an allowlisted grantee");
        try {
            validateConnectionIdentity(connection, configuration.controlPlaneGranteeRoles(), expected);
            validateV5Functions(connection, configuration);
            validateV5RolesAndPrivileges(connection, configuration, true);
            validateDescriptorFenceCatalog(connection);
            if (BulkCapacityOccupancyCatalog.installed(connection))
                BulkCapacityOccupancyCatalog.validateAccess(connection, configuration);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to attest the live bulk control-plane role", failure);
        }
    }

    private static void validateConnectionIdentity(Connection connection, Set<String> allowedRoles,
            String exactRole) throws SQLException {
        String sessionUser;
        String currentUser;
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery("select session_user, current_user")) {
            require(rows.next(), "Unable to identify the current PostgreSQL session");
            sessionUser = rows.getString(1);
            currentUser = rows.getString(2);
            require(!rows.next(), "PostgreSQL session identity query returned multiple rows");
        }
        require(sessionUser != null && sessionUser.equals(currentUser),
                "Bulk connection session and effective PostgreSQL identities must match");
        require(allowedRoles.contains(sessionUser),
                "Bulk connection identity is not an allowlisted PostgreSQL role");
        require(exactRole == null || exactRole.equals(sessionUser),
                "Control-plane connection identity differs from its configured PostgreSQL role");
    }

    private static void validateDescriptorFenceCatalog(Connection connection) throws SQLException {
        validateDurableTrigger(connection, EXECUTION_TABLE, DESCRIPTOR_TRIGGER, DESCRIPTOR_FUNCTION,
                "CREATE TRIGGER " + DESCRIPTOR_TRIGGER + " BEFORE UPDATE ON praxis_bulk." + EXECUTION_TABLE
                        + " FOR EACH ROW EXECUTE FUNCTION praxis_bulk." + DESCRIPTOR_FUNCTION + "()",
                "begin if new.control_generation is distinct from old.control_generation or "
                        + "new.control_descriptor_fingerprint is distinct from old.control_descriptor_fingerprint then "
                        + "raise exception 'praxis_bulk.praxis_bulk_execution descriptor binding is immutable' "
                        + "using errcode = '55000'; end if; return new; end;");
        String fenceBody = migrationExpectations().normalizedDescriptorFenceBody();
        validateDescriptorInsertFence(connection, PROPOSAL_TABLE, PROPOSAL_INSERT_FENCE_TRIGGER,
                "CREATE TRIGGER " + PROPOSAL_INSERT_FENCE_TRIGGER + " BEFORE INSERT ON praxis_bulk."
                        + PROPOSAL_TABLE + " FOR EACH ROW EXECUTE FUNCTION praxis_bulk." + INSERT_FENCE_FUNCTION + "()",
                fenceBody);
        validateDescriptorInsertFence(connection, EXECUTION_TABLE, EXECUTION_INSERT_FENCE_TRIGGER,
                "CREATE TRIGGER " + EXECUTION_INSERT_FENCE_TRIGGER + " BEFORE INSERT ON praxis_bulk."
                        + EXECUTION_TABLE + " FOR EACH ROW EXECUTE FUNCTION praxis_bulk." + INSERT_FENCE_FUNCTION + "()",
                fenceBody);
    }

    private static void validateDescriptorFenceRows(Connection connection) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
                select count(*)
                  from praxis_bulk.praxis_bulk_execution e
                  join praxis_bulk.praxis_bulk_proposal p on p.proposal_id=e.proposal_id
                 where e.control_generation is distinct from p.control_generation
                    or e.control_descriptor_fingerprint is distinct from p.control_descriptor_fingerprint
                    or (e.control_generation is not null
                        and e.structural_revision is distinct from p.control_structural_revision)
                """)) {
            require(rows.next() && rows.getLong(1) == 0 && !rows.next(),
                    "execution descriptor tuple differs from its protected proposal");
        }
    }

    /** Protected predecessor primitives shared with the current catalog attestation. */
    private static void validateNamespaceBindingColumns(Connection connection) throws SQLException {
        validateDurableColumns(connection, NAMESPACE_BINDING_TABLE, Map.ofEntries(
                Map.entry("namespace_id", "text|true"), Map.entry("deployment_id", "text|true"),
                Map.entry("bound_at", "timestamp with time zone|true")));
    }

    private static void validateDeploymentBucketColumns(Connection connection) throws SQLException {
        validateDurableColumns(connection, DEPLOYMENT_BUCKET_TABLE,
                Map.of("deployment_id", "text|true"));
    }

    private static void validateNamespaceBindingConstraints(Connection connection) throws SQLException {
        validateDurableConstraints(connection, NAMESPACE_BINDING_TABLE, false, Map.ofEntries(
                Map.entry("praxis_bulk_namespace_binding_pkey", "PRIMARY KEY (namespace_id)"),
                Map.entry("praxis_bulk_namespace_binding_pair_key", "UNIQUE (namespace_id, deployment_id)"),
                Map.entry("praxis_bulk_namespace_binding_namespace_check", "CHECK ((btrim(namespace_id) <> ''::text))"),
                Map.entry("praxis_bulk_namespace_binding_deployment_check", "CHECK ((btrim(deployment_id) <> ''::text))")));
    }

    private static void validateDeploymentBucketConstraints(Connection connection) throws SQLException {
        validateDurableConstraints(connection, DEPLOYMENT_BUCKET_TABLE, false, Map.ofEntries(
                Map.entry("praxis_bulk_deployment_bucket_pkey", "PRIMARY KEY (deployment_id)"),
                Map.entry("praxis_bulk_deployment_bucket_id_check", "CHECK ((btrim(deployment_id) <> ''::text))")));
    }

    private static void validateV5Columns(Connection connection) throws SQLException {
        validateNamespaceBindingColumns(connection);
        validateDurableColumns(connection, OPERATION_CONTROL_TABLE, Map.ofEntries(
                Map.entry("namespace_id", "text|true"), Map.entry("operation_id", "text|true"),
                Map.entry("state", "text|true"), Map.entry("generation", "bigint|true"),
                Map.entry("descriptor_fingerprint", "text|false"),
                Map.entry("structural_revision", "text|false"),
                Map.entry("publication_generation", "bigint|false"),
                Map.entry("publication_document_digest", "text|false"),
                Map.entry("updated_at", "timestamp with time zone|true")));
        validateDeploymentBucketColumns(connection);
        validateDurableColumns(connection, SUBJECT_BUCKET_TABLE, Map.ofEntries(
                Map.entry("deployment_id", "text|true"),
                Map.entry("subject_scope_digest_version", "integer|true"),
                Map.entry("subject_scope_digest", "text|true")));
        validateDurableColumns(connection, ALLOCATION_TABLE, Map.ofEntries(
                Map.entry("allocation_id", "uuid|true"), Map.entry("namespace_id", "text|true"),
                Map.entry("deployment_id", "text|true"),
                Map.entry("subject_scope_digest_version", "integer|true"),
                Map.entry("subject_scope_digest", "text|true"),
                Map.entry("authorization_scope_digest_version", "integer|true"),
                Map.entry("authorization_scope_digest", "text|true"), Map.entry("kind", "text|true"),
                Map.entry("proposal_id", "uuid|false"), Map.entry("execution_id", "uuid|false"),
                Map.entry("state", "text|true"),
                Map.entry("created_at", "timestamp with time zone|true"),
                Map.entry("released_at", "timestamp with time zone|false"),
                Map.entry("release_reason", "text|false")));
        validateDurableColumns(connection, TOMBSTONE_TABLE, Map.ofEntries(
                Map.entry("namespace_id", "text|true"),
                Map.entry("authorization_scope_digest_version", "integer|true"),
                Map.entry("authorization_scope_digest", "text|true"),
                Map.entry("resource_key", "text|true"), Map.entry("operation_id", "text|true"),
                Map.entry("idempotency_key_digest", "text|true"), Map.entry("proposal_id", "uuid|true"),
                Map.entry("execution_id", "uuid|true"), Map.entry("terminal_status", "text|true"),
                Map.entry("terminal_at", "timestamp with time zone|true"),
                Map.entry("purged_at", "timestamp with time zone|true")));
    }

    private static void validateV5Constraints(Connection connection) throws SQLException {
        validateNamespaceBindingConstraints(connection);
        validateDurableConstraints(connection, OPERATION_CONTROL_TABLE, false, Map.ofEntries(
                Map.entry("praxis_bulk_operation_control_pkey", "PRIMARY KEY (namespace_id, operation_id)"),
                Map.entry("praxis_bulk_operation_control_namespace_id_fkey", "FOREIGN KEY (namespace_id) REFERENCES praxis_bulk.praxis_bulk_namespace_binding(namespace_id) ON DELETE RESTRICT"),
                Map.entry("praxis_bulk_operation_control_operation_check", "CHECK ((btrim(operation_id) <> ''::text))"),
                Map.entry("praxis_bulk_operation_control_generation_check", "CHECK ((generation >= 0))"),
                Map.entry("praxis_bulk_operation_control_state_check", "CHECK ((state = ANY (ARRAY['UNCOMPOSED'::text, 'SUSPENDED'::text, 'READY'::text])))"),
                Map.entry("praxis_bulk_operation_control_publication_check", "CHECK ((((state = 'READY'::text) AND (publication_generation IS NOT NULL) AND (publication_generation >= 1) AND (publication_document_digest IS NOT NULL) AND (publication_document_digest ~ '^sha256:[0-9a-f]{64}$'::text)) OR ((state <> 'READY'::text) AND (publication_generation IS NULL) AND (publication_document_digest IS NULL))))"),
                Map.entry("praxis_bulk_operation_control_ready_check", "CHECK ((((state = 'READY'::text) AND (descriptor_fingerprint IS NOT NULL) AND (descriptor_fingerprint ~ '^sha256:[0-9a-f]{64}$'::text) AND (structural_revision IS NOT NULL) AND (btrim(structural_revision) <> ''::text)) OR ((state <> 'READY'::text) AND (descriptor_fingerprint IS NULL) AND (structural_revision IS NULL))))")));
        validateDeploymentBucketConstraints(connection);
        validateDurableConstraints(connection, SUBJECT_BUCKET_TABLE, false, Map.ofEntries(
                Map.entry("praxis_bulk_subject_bucket_pkey", "PRIMARY KEY (deployment_id, subject_scope_digest_version, subject_scope_digest)"),
                Map.entry("praxis_bulk_subject_bucket_deployment_id_fkey", "FOREIGN KEY (deployment_id) REFERENCES praxis_bulk.praxis_bulk_deployment_bucket(deployment_id) ON DELETE RESTRICT"),
                Map.entry("praxis_bulk_subject_bucket_version_check", "CHECK ((subject_scope_digest_version >= 1))"),
                Map.entry("praxis_bulk_subject_bucket_digest_check", "CHECK ((subject_scope_digest ~ '^sha256:[0-9a-f]{64}$'::text))")));
        validateDurableConstraints(connection, ALLOCATION_TABLE, false, Map.ofEntries(
                Map.entry("praxis_bulk_allocation_pkey", "PRIMARY KEY (allocation_id)"),
                Map.entry("praxis_bulk_allocation_deployment_id_fkey", "FOREIGN KEY (deployment_id) REFERENCES praxis_bulk.praxis_bulk_deployment_bucket(deployment_id) ON DELETE RESTRICT"),
                Map.entry("praxis_bulk_allocation_namespace_deployment_fkey", "FOREIGN KEY (namespace_id, deployment_id) REFERENCES praxis_bulk.praxis_bulk_namespace_binding(namespace_id, deployment_id) ON DELETE RESTRICT"),
                Map.entry("praxis_bulk_allocation_proposal_key", "UNIQUE (proposal_id)"),
                Map.entry("praxis_bulk_allocation_execution_key", "UNIQUE (execution_id)"),
                Map.entry("praxis_bulk_allocation_proposal_fkey", "FOREIGN KEY (proposal_id) REFERENCES praxis_bulk.praxis_bulk_proposal(proposal_id) ON DELETE RESTRICT"),
                Map.entry("praxis_bulk_allocation_execution_fkey", "FOREIGN KEY (execution_id) REFERENCES praxis_bulk.praxis_bulk_execution(execution_id) ON DELETE RESTRICT"),
                Map.entry("praxis_bulk_allocation_subject_bucket_fkey", "FOREIGN KEY (deployment_id, subject_scope_digest_version, subject_scope_digest) REFERENCES praxis_bulk.praxis_bulk_subject_bucket(deployment_id, subject_scope_digest_version, subject_scope_digest) ON DELETE RESTRICT"),
                Map.entry("praxis_bulk_allocation_scope_version_check", "CHECK ((authorization_scope_digest_version >= 1))"),
                Map.entry("praxis_bulk_allocation_scope_digest_check", "CHECK ((authorization_scope_digest ~ '^sha256:[0-9a-f]{64}$'::text))"),
                Map.entry("praxis_bulk_allocation_shape_check", "CHECK ((((kind = 'PROPOSAL_PENDING'::text) AND (proposal_id IS NOT NULL) AND (execution_id IS NULL) AND (state = ANY (ARRAY['PENDING'::text, 'CONSUMED'::text, 'RELEASED'::text]))) OR ((kind = 'EXECUTION_ACTIVE'::text) AND (execution_id IS NOT NULL) AND (proposal_id IS NULL) AND (state = ANY (ARRAY['ACTIVE'::text, 'RELEASED'::text])))))"),
                Map.entry("praxis_bulk_allocation_release_check", "CHECK ((((state = ANY (ARRAY['PENDING'::text, 'ACTIVE'::text, 'CONSUMED'::text])) AND (released_at IS NULL) AND (release_reason IS NULL)) OR ((kind = 'PROPOSAL_PENDING'::text) AND (state = 'RELEASED'::text) AND (release_reason IS NOT NULL) AND (release_reason = 'PROPOSAL_EXPIRED'::text) AND (released_at IS NOT NULL) AND (released_at >= created_at)) OR ((kind = 'EXECUTION_ACTIVE'::text) AND (state = 'RELEASED'::text) AND (release_reason IS NOT NULL) AND (release_reason = 'TERMINAL_RECONCILED'::text) AND (released_at IS NOT NULL) AND (released_at >= created_at))))")));
        validateDurableConstraints(connection, TOMBSTONE_TABLE, false, Map.ofEntries(
                Map.entry("praxis_bulk_tombstone_pkey", "PRIMARY KEY (namespace_id, authorization_scope_digest_version, authorization_scope_digest, resource_key, operation_id, idempotency_key_digest)"),
                Map.entry("praxis_bulk_tombstone_namespace_id_fkey", "FOREIGN KEY (namespace_id) REFERENCES praxis_bulk.praxis_bulk_namespace_binding(namespace_id) ON DELETE RESTRICT"),
                Map.entry("praxis_bulk_tombstone_proposal_key", "UNIQUE (proposal_id)"),
                Map.entry("praxis_bulk_tombstone_execution_key", "UNIQUE (execution_id)"),
                Map.entry("praxis_bulk_tombstone_scope_version_check", "CHECK ((authorization_scope_digest_version >= 1))"),
                Map.entry("praxis_bulk_tombstone_scope_digest_check", "CHECK ((authorization_scope_digest ~ '^sha256:[0-9a-f]{64}$'::text))"),
                Map.entry("praxis_bulk_tombstone_key_digest_check", "CHECK ((idempotency_key_digest ~ '^sha256:[0-9a-f]{64}$'::text))"),
                Map.entry("praxis_bulk_tombstone_resource_check", "CHECK ((btrim(resource_key) <> ''::text))"),
                Map.entry("praxis_bulk_tombstone_operation_check", "CHECK ((btrim(operation_id) <> ''::text))"),
                Map.entry("praxis_bulk_tombstone_terminal_check", "CHECK ((terminal_status = ANY (ARRAY['COMPLETED'::text, 'COMPLETED_WITH_ERRORS'::text, 'STOPPED'::text, 'CANCELLED'::text])))"),
                Map.entry("praxis_bulk_tombstone_time_check", "CHECK ((purged_at >= terminal_at))")));
    }

    private static void validateV5Indexes(Connection connection) throws SQLException {
        Map<String, String> expected = Map.ofEntries(
                Map.entry("praxis_bulk_allocation_pending_deployment_idx", "CREATE INDEX praxis_bulk_allocation_pending_deployment_idx ON praxis_bulk.praxis_bulk_allocation USING btree (deployment_id) WHERE ((kind = 'PROPOSAL_PENDING'::text) AND (state = 'PENDING'::text))"),
                Map.entry("praxis_bulk_allocation_pending_subject_idx", "CREATE INDEX praxis_bulk_allocation_pending_subject_idx ON praxis_bulk.praxis_bulk_allocation USING btree (deployment_id, subject_scope_digest_version, subject_scope_digest) WHERE ((kind = 'PROPOSAL_PENDING'::text) AND (state = 'PENDING'::text))"),
                Map.entry("praxis_bulk_allocation_active_deployment_idx", "CREATE INDEX praxis_bulk_allocation_active_deployment_idx ON praxis_bulk.praxis_bulk_allocation USING btree (deployment_id) WHERE ((kind = 'EXECUTION_ACTIVE'::text) AND (state = 'ACTIVE'::text))"),
                Map.entry("praxis_bulk_execution_retention_idx", "CREATE INDEX praxis_bulk_execution_retention_idx ON praxis_bulk.praxis_bulk_execution USING btree (terminal_at, execution_id) WHERE (status = ANY (ARRAY['COMPLETED'::text, 'COMPLETED_WITH_ERRORS'::text, 'STOPPED'::text]))"),
                Map.entry("praxis_bulk_proposal_expiry_idx", "CREATE INDEX praxis_bulk_proposal_expiry_idx ON praxis_bulk.praxis_bulk_proposal USING btree (expires_at, proposal_id)"));
        var actual = new LinkedHashMap<String, String>();
        try (var statement = connection.prepareStatement("""
                select c.relname, pg_get_indexdef(i.indexrelid), i.indisvalid, i.indisready,
                       i.indislive, i.indisunique, i.indisprimary, am.amname
                from pg_index i join pg_class c on c.oid=i.indexrelid
                join pg_namespace n on n.oid=c.relnamespace join pg_am am on am.oid=c.relam
                where n.nspname=? and c.relname = any (?::text[])
                """)) {
            statement.setString(1, SCHEMA);
            statement.setArray(2, connection.createArrayOf("text", V5_INDEXES.toArray()));
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    require(rows.getBoolean(3) && rows.getBoolean(4) && rows.getBoolean(5)
                                    && !rows.getBoolean(6) && !rows.getBoolean(7) && "btree".equals(rows.getString(8)),
                            "governed lifecycle index is invalid: " + rows.getString(1));
                    actual.put(rows.getString(1), normalizeExpression(rows.getString(2)));
                }
            }
        }
        var normalized = new LinkedHashMap<String, String>();
        expected.forEach((name, definition) -> normalized.put(name, normalizeExpression(definition)));
        require(actual.equals(normalized), "governed lifecycle index definitions differ");
    }

    private static void validateV5Triggers(Connection connection) throws SQLException {
        Map<String, TriggerSpec> expected = Map.ofEntries(
                trigger(PROPOSAL_TABLE, "praxis_bulk_proposal_guard_delete", "BEFORE DELETE", "guard_lifecycle_delete"),
                trigger(EVALUATION_TABLE, "praxis_bulk_evaluation_guard_delete", "BEFORE DELETE", "guard_lifecycle_delete"),
                trigger(EXECUTION_TABLE, "praxis_bulk_execution_guard_delete", "BEFORE DELETE", "guard_lifecycle_delete"),
                trigger(RECEIPT_TABLE, "praxis_bulk_receipt_guard_delete", "BEFORE DELETE", "guard_lifecycle_delete"),
                trigger(ADMISSION_TABLE, "praxis_bulk_admission_guard_delete", "BEFORE DELETE", "guard_lifecycle_delete"),
                trigger(ALLOCATION_TABLE, "praxis_bulk_allocation_guard_delete", "BEFORE DELETE", "guard_lifecycle_delete"),
                trigger(TOMBSTONE_TABLE, "praxis_bulk_tombstone_guard_mutation", "BEFORE DELETE OR UPDATE", "guard_tombstone_mutation"),
                trigger(DEPLOYMENT_BUCKET_TABLE, "praxis_bulk_deployment_bucket_guard_mutation", "BEFORE DELETE OR UPDATE", "guard_bucket_mutation"),
                trigger(SUBJECT_BUCKET_TABLE, "praxis_bulk_subject_bucket_guard_mutation", "BEFORE DELETE OR UPDATE", "guard_bucket_mutation"),
                trigger(ALLOCATION_TABLE, "praxis_bulk_allocation_protect_transition", "BEFORE UPDATE", "protect_allocation_transition"),
                trigger(ALLOCATION_TABLE, "praxis_bulk_allocation_validate_binding", "BEFORE INSERT", "validate_allocation_binding"),
                trigger(NAMESPACE_BINDING_TABLE, "praxis_bulk_namespace_binding_immutable", "BEFORE DELETE OR UPDATE", "protect_namespace_binding"),
                trigger(OPERATION_CONTROL_TABLE, "praxis_bulk_operation_control_protect", "BEFORE UPDATE", "protect_operation_control"),
                trigger(PROPOSAL_TABLE, "praxis_bulk_proposal_guard_admission", "BEFORE INSERT", "guard_new_bulk_admission"),
                trigger(EXECUTION_TABLE, "praxis_bulk_execution_guard_admission", "BEFORE INSERT", "guard_new_bulk_admission"),
                trigger(EVALUATION_TABLE, "praxis_bulk_evaluation_guard_admission", "BEFORE INSERT", "guard_new_bulk_evaluation"),
                trigger(EXECUTION_TABLE, "praxis_bulk_execution_guard_terminal", "BEFORE UPDATE", "guard_terminal_execution"),
                trigger(EXECUTION_TABLE, "praxis_bulk_execution_protect_cancel", "BEFORE INSERT OR UPDATE", "protect_cancel_request"),
                trigger(EXECUTION_TABLE, "praxis_bulk_execution_release_active_allocation", "AFTER UPDATE", "release_active_allocation_on_terminal"),
                trigger(RECEIPT_TABLE, "praxis_bulk_receipt_guard_terminal", "BEFORE INSERT", "guard_terminal_evidence_insert"),
                trigger(ADMISSION_TABLE, "praxis_bulk_admission_guard_terminal", "BEFORE INSERT", "guard_terminal_evidence_insert"),
                trigger(PROPOSAL_TABLE, "praxis_bulk_proposal_protocol_insert", "BEFORE INSERT", "guard_bulk_protocol_insert"),
                trigger(EXECUTION_TABLE, "praxis_bulk_execution_protocol_insert", "BEFORE INSERT", "guard_bulk_protocol_insert"),
                trigger(EXECUTION_TABLE, "praxis_bulk_execution_guard_atomic_attempt", "BEFORE UPDATE", "guard_atomic_attempt_transition"),
                trigger(RECEIPT_TABLE, "praxis_bulk_receipt_per_item_only", "BEFORE INSERT", "guard_per_item_evidence_insert"),
                trigger(ADMISSION_TABLE, "praxis_bulk_admission_per_item_only", "BEFORE INSERT", "guard_per_item_evidence_insert"),
                trigger(ATOMIC_RECEIPT_TABLE, "praxis_bulk_atomic_receipt_guard_insert", "BEFORE INSERT", "guard_atomic_receipt_insert"),
                trigger(ATOMIC_RECEIPT_TABLE, "praxis_bulk_atomic_receipt_reject_mutation", "BEFORE UPDATE", "reject_atomic_evidence_mutation"),
                trigger(ATOMIC_RECEIPT_TABLE, "praxis_bulk_atomic_receipt_guard_delete", "BEFORE DELETE", "guard_lifecycle_delete"),
                trigger(ATOMIC_ITEM_TABLE, "praxis_bulk_atomic_item_result_guard_insert", "BEFORE INSERT", "guard_atomic_item_insert"),
                trigger(ATOMIC_ITEM_TABLE, "praxis_bulk_atomic_item_result_reject_mutation", "BEFORE UPDATE", "reject_atomic_evidence_mutation"),
                trigger(ATOMIC_ITEM_TABLE, "praxis_bulk_atomic_item_result_guard_delete", "BEFORE DELETE", "guard_lifecycle_delete"),
                trigger(ATOMIC_EFFECT_TABLE, "praxis_bulk_atomic_effect_ref_guard_insert", "BEFORE INSERT", "guard_atomic_effect_insert"),
                trigger(ATOMIC_EFFECT_TABLE, "praxis_bulk_atomic_effect_ref_reject_mutation", "BEFORE UPDATE", "reject_atomic_evidence_mutation"),
                trigger(ATOMIC_EFFECT_TABLE, "praxis_bulk_atomic_effect_ref_guard_delete", "BEFORE DELETE", "guard_lifecycle_delete"),
                trigger(ATOMIC_REJECTION_TABLE, "praxis_bulk_atomic_rejection_guard_insert", "BEFORE INSERT", "guard_atomic_rejection_insert"),
                trigger(ATOMIC_REJECTION_TABLE, "praxis_bulk_atomic_rejection_reject_mutation", "BEFORE UPDATE", "reject_atomic_evidence_mutation"),
                trigger(ATOMIC_REJECTION_TABLE, "praxis_bulk_atomic_rejection_guard_delete", "BEFORE DELETE", "guard_lifecycle_delete"));
        var actual = new LinkedHashMap<String, TriggerSpec>();
        try (var statement = connection.prepareStatement("""
                select r.relname, t.tgname, t.tgenabled, pg_get_triggerdef(t.oid),
                       pn.nspname, p.proname, p.pronargs
                from pg_trigger t join pg_class r on r.oid=t.tgrelid
                join pg_namespace rn on rn.oid=r.relnamespace join pg_proc p on p.oid=t.tgfoid
                join pg_namespace pn on pn.oid=p.pronamespace
                where rn.nspname=? and not t.tgisinternal
                  and (r.relname || '.' || t.tgname) = any (?::text[])
                """)) {
            statement.setString(1, SCHEMA);
            var triggerKeys = new LinkedHashSet<>(V5_TRIGGERS);
            triggerKeys.addAll(V10_TRIGGERS);
            triggerKeys.addAll(V16_TRIGGERS);
            statement.setArray(2, connection.createArrayOf("text", triggerKeys.toArray()));
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    String key = rows.getString(1) + "." + rows.getString(2);
                    require("O".equals(rows.getString(3)) && SCHEMA.equals(rows.getString(5))
                                    && rows.getInt(7) == 0, "governed lifecycle trigger is disabled or rebound: " + key);
                    actual.put(key, new TriggerSpec(rows.getString(6), normalizeExpression(rows.getString(4))));
                }
            }
        }
        require(actual.equals(expected), "governed lifecycle trigger definitions differ");
    }

    private static Map.Entry<String, TriggerSpec> trigger(String table, String name, String event, String function) {
        String definition = "CREATE TRIGGER " + name + " " + event + " ON praxis_bulk." + table
                + " FOR EACH ROW EXECUTE FUNCTION praxis_bulk." + function + "()";
        return Map.entry(table + "." + name, new TriggerSpec(function, normalizeExpression(definition)));
    }

    private static void validateV5Functions(Connection connection,
            BulkExecutionRoleConfiguration roleConfiguration) throws SQLException {
        validateV5Functions(connection, roleConfiguration, Map.of());
    }

    private static void validateV5Functions(Connection connection,
            BulkExecutionRoleConfiguration roleConfiguration, Map<String, String> phases) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
                select to_regprocedure('praxis_bulk.transition_operation_control(text,text,bigint,text,text,text)') is null
                """)) {
            require(rows.next() && rows.getBoolean(1) && !rows.next(),
                    "legacy operation-control CAS must be absent after publication cutover");
        }
        Map<String, FunctionBodyExpectation> expectedBodies = migrationExpectations().functionBodies();
        var keys = new LinkedHashSet<>(V5_FUNCTIONS);
        keys.addAll(V15_CONTROL_FUNCTIONS);
        keys.addAll(V8_FUNCTIONS);
        keys.addAll(V9_FUNCTIONS);
        keys.addAll(V10_FUNCTIONS);
        keys.addAll(V11_FUNCTIONS);
        keys.addAll(V12_FUNCTIONS);
        keys.addAll(V14_FUNCTIONS);
        keys.addAll(V16_FUNCTIONS);
        keys.add(RECEIPT_FUNCTION + "()");
        keys.add(ADMISSION_FUNCTION + "()");
        Set<String> definer = Set.of("protect_allocation_transition()", "validate_allocation_binding()",
                "guard_new_bulk_admission()", "guard_new_bulk_evaluation()",
                "lock_operation_control(p_namespace_id text, p_operation_id text)",
                "transition_operation_control(p_namespace_id text, p_operation_id text, p_expected_generation bigint, p_target_state text, p_descriptor_fingerprint text, p_structural_revision text, p_expected_publication_generation bigint, p_expected_publication_digest text)",
                "lock_openapi_publication(p_namespace_id text, p_deployment_id text)",
                "transition_openapi_publication(p_namespace_id text, p_deployment_id text, p_expected_generation bigint, p_target_state text, p_document_digest text)",
                "guard_terminal_execution()", "release_active_allocation_on_terminal()",
                "purge_terminal_execution(p_execution_id uuid)",
                "expire_unconsumed_proposal(p_proposal_id uuid)",
                "require_complete_preview_integrity_bootstrap()",
                "assert_preview_integrity_complete()");
        var actual = new LinkedHashSet<String>();
        try (var statement = connection.prepareStatement("""
                select p.proname || '(' || pg_get_function_identity_arguments(p.oid) || ')',
                       p.proname, l.lanname, p.prorettype::regtype::text, p.prosecdef,
                       p.provolatile, p.prokind, p.proleakproof, p.proparallel,
                       p.proconfig = array['search_path=pg_catalog, pg_temp']::text[],
                       owner.rolname, p.prosrc
                from pg_proc p join pg_namespace n on n.oid=p.pronamespace
                join pg_language l on l.oid=p.prolang join pg_roles owner on owner.oid=p.proowner
                where n.nspname=? and (p.proname || '(' || pg_get_function_identity_arguments(p.oid) || ')')
                      = any (?::text[])
                """)) {
            statement.setString(1, SCHEMA);
            statement.setArray(2, connection.createArrayOf("text", keys.toArray()));
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    String key = rows.getString(1);
                    String name = rows.getString(2);
                    boolean sqlHelper = name.equals("terminal_evidence_complete")
                            || name.equals("atomic_evidence_complete");
                    boolean booleanResult = sqlHelper || name.equals("purge_terminal_execution")
                            || name.equals("expire_unconsumed_proposal")
                            || name.equals("assert_preview_integrity_complete");
                    boolean recordResult = name.equals("lock_operation_control")
                            || name.equals("transition_operation_control")
                            || name.equals("lock_openapi_publication") || name.equals("transition_openapi_publication");
                    require((sqlHelper ? "sql" : "plpgsql").equals(rows.getString(3))
                                    && (recordResult ? "record" : booleanResult ? "boolean" : "trigger").equals(rows.getString(4))
                                    && rows.getBoolean(5) == definer.contains(key)
                                    && (sqlHelper || name.equals("assert_preview_integrity_complete") ? "s" : "v")
                                            .equals(rows.getString(6))
                                    && "f".equals(rows.getString(7)) && !rows.getBoolean(8)
                                    && "u".equals(rows.getString(9)) && rows.getBoolean(10),
                            "governed lifecycle function attributes differ: " + key);
                    if (key.startsWith("lock_operation_control(")
                            || key.startsWith("transition_operation_control(")
                            || V14_FUNCTIONS.contains(key)
                            || key.equals("guard_new_bulk_admission()")
                            || key.equals("guard_new_bulk_evaluation()")) {
                        require("praxis_bulk_control_owner".equals(rows.getString(11)),
                                "operation-control function has unexpected owner: " + key);
                    } else if (key.equals("require_complete_preview_integrity_bootstrap()")
                            || key.equals("assert_preview_integrity_complete()")) {
                        require(roleConfiguration.expectedSchemaOwnerRole().equals(rows.getString(11)),
                                "preview bootstrap guard has unexpected owner: " + key);
                    } else if (definer.contains(key)) {
                        require("praxis_bulk_retention_owner".equals(rows.getString(11)),
                                "SECURITY DEFINER function has unexpected owner: " + key);
                    } else if (V10_FUNCTIONS.contains(key)) {
                        require(roleConfiguration.expectedSchemaOwnerRole().equals(rows.getString(11)),
                                "cancellation invoker function has unexpected owner: " + key);
                    } else {
                        require(!Set.of("praxis_bulk_retention_owner", "praxis_bulk_retention_executor",
                                        "praxis_bulk_control_owner")
                                        .contains(rows.getString(11)),
                                "invoker function is owned by a retention role: " + key);
                    }
                    FunctionBodyExpectation expectedBody = expectedBodies.get(name);
                    if (BulkCapacityOccupancyCatalog.installed(connection) && BulkCapacityOccupancyCatalog.REPLACED.containsKey(name))
                        expectedBody = new FunctionBodyExpectation("V19", normalizeExpression(BulkCapacityOccupancyCatalog.functionBody(name)));
                    require(expectedBody != null
                                    && expectedBody.normalizedBody().equals(normalizeExpression(rows.getString(12))),
                            "governed lifecycle function body differs from "
                                    + (expectedBody == null ? "versioned migration" : expectedBody.version())
                                    + " expectation: " + key);
                    actual.add(key);
                }
            }
        }
        require(actual.equals(keys), "governed lifecycle functions are missing");
        validateV5FunctionPrivileges(connection, roleConfiguration, phases);
    }

    private static String readV5Migration() {
        try (InputStream input = BulkExecutionMigrator.class.getResourceAsStream(
                "/db/praxis-bulk-migrations/V5__bulk_governed_lifecycle.sql")) {
            require(input != null, "V5 governed lifecycle migration resource is missing");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to read V5 governed lifecycle migration", failure);
        }
    }

    private static String readV6Migration() {
        try (InputStream input = BulkExecutionMigrator.class.getResourceAsStream(
                "/db/praxis-bulk-migrations/V6__bulk_operation_control_security.sql")) {
            require(input != null, "V6 operation-control security migration resource is missing");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to read V6 operation-control security migration", failure);
        }
    }

    private static String readV7Migration() {
        try (InputStream input = BulkExecutionMigrator.class.getResourceAsStream(
                "/db/praxis-bulk-migrations/V7__bulk_operation_descriptor_fence.sql")) {
            require(input != null, "V7 descriptor fence migration resource is missing");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to read V7 descriptor fence migration", failure);
        }
    }

    private static String readV8Migration() {
        try (InputStream input = BulkExecutionMigrator.class.getResourceAsStream(
                "/db/praxis-bulk-migrations/V8__bulk_ordinal_manifest.sql")) {
            require(input != null, "V8 ordinal manifest migration resource is missing");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to read V8 ordinal manifest migration", failure);
        }
    }

    private static String readV9Migration() {
        try (InputStream input = BulkExecutionMigrator.class.getResourceAsStream(
                "/db/praxis-bulk-migrations/V9__bulk_safe_preview_projection.sql")) {
            require(input != null, "V9 preview migration resource is missing");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to read V9 preview migration", failure);
        }
    }

    private static String readV10Migration() {
        try (InputStream input = BulkExecutionMigrator.class.getResourceAsStream(
                "/db/praxis-bulk-migrations/V10__bulk_durable_cancellation.sql")) {
            require(input != null, "V10 cancellation migration resource is missing");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to read V10 cancellation migration", failure);
        }
    }

    private static String readV11Migration() {
        try (InputStream input = BulkExecutionMigrator.class.getResourceAsStream(
                "/db/praxis-bulk-migrations/V11__bulk_preview_item_integrity.sql")) {
            require(input != null, "V11 preview integrity migration resource is missing");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to read V11 preview integrity migration", failure);
        }
    }

    private static String readV12Migration() {
        try (InputStream input = BulkExecutionMigrator.class.getResourceAsStream(
                "/db/praxis-bulk-migrations/V12__bulk_preview_reader_gate.sql")) {
            require(input != null, "V12 reader-gate migration resource is missing");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to read V12 reader-gate migration", failure);
        }
    }

    private static String readV13Migration() {
        try (InputStream input = BulkExecutionMigrator.class.getResourceAsStream(
                "/db/praxis-bulk-migrations/V13__bulk_execution_time_order.sql")) {
            require(input != null, "V13 execution time-order migration resource is missing");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to read V13 execution time-order migration", failure);
        }
    }

    private static String readV15Migration() {
        try (InputStream input = BulkExecutionMigrator.class.getResourceAsStream(
                "/db/praxis-bulk-migrations/V15__bulk_operation_publication_fence.sql")) {
            require(input != null, "V15 operation publication-fence migration resource is missing");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to read V15 operation publication-fence migration", failure);
        }
    }

    private static String readV14Migration() {
        try (InputStream input = BulkExecutionMigrator.class.getResourceAsStream(
                "/db/praxis-bulk-migrations/V14__bulk_openapi_publication.sql")) {
            require(input != null, "V14 OpenAPI publication migration resource is missing");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to read V14 OpenAPI publication migration", failure);
        }
    }

    private static String readV16Migration() {
        try (InputStream input = BulkExecutionMigrator.class.getResourceAsStream(
                "/db/praxis-bulk-migrations/V16__bulk_atomic_set_execution.sql")) {
            require(input != null, "V16 atomic execution migration resource is missing");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to read V16 atomic execution migration", failure);
        }
    }

    private static String readV17Migration() {
        try (InputStream input = BulkExecutionMigrator.class.getResourceAsStream(
                "/db/praxis-bulk-migrations/V17__bulk_pending_quota_snapshot_fence.sql")) {
            require(input != null, "V17 pending quota snapshot fence migration resource is missing");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to read V17 pending quota snapshot fence migration", failure);
        }
    }

    private static String readV18Migration() {
        try (InputStream input = BulkExecutionMigrator.class.getResourceAsStream(
                "/db/praxis-bulk-migrations/V18__bulk_capacity_installation.sql")) {
            require(input != null, "V18 capacity installation migration resource is missing");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to read V18 capacity installation migration", failure);
        }
    }

    /** Exact owner/runtime ACL and source-owned V18 structure, also checked on owner entry points. */
    static void validateCapacityInstallationCatalog(Connection connection,
            BulkExecutionRoleConfiguration roles) throws SQLException {
        validateCapacityInstallationCatalog(connection, roles, "COMPLETE", true);
    }

    private static String capacityReadBootstrapPhase(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery("select bootstrap_version, phase from praxis_bulk."
                        + CAPACITY_READ_BOOTSTRAP_TABLE)) {
            require(rows.next() && rows.getInt(1) == 18, "V18 capacity read bootstrap row is absent");
            String phase = rows.getString(2);
            require(("PENDING".equals(phase) || "COMPLETE".equals(phase)) && !rows.next(),
                    "V18 capacity read bootstrap row changed");
            return phase;
        }
    }

    private static void validateCapacityInstallationCatalog(Connection connection,
            BulkExecutionRoleConfiguration roles, String expectedPhase, boolean expectedGrant)
            throws SQLException {
        validateCapacityInstallationCatalog(connection, roles, expectedPhase, expectedGrant, Map.of());
    }

    private static void validateCapacityInstallationCatalog(Connection connection,
            BulkExecutionRoleConfiguration roles, String expectedPhase, boolean expectedGrant,
            Map<String, String> phases) throws SQLException {
        require(isV18Installed(connection), "V18 capacity installation is not applied");
        // During the atomic grant-before-COMPLETE interval the transaction sees the new ACL
        // while the row still says PENDING. Final validation runs again after the phase CAS.
        if (expectedGrant == "COMPLETE".equals(expectedPhase))
            validateV5RolesAndPrivileges(connection, roles, false, phases);
        require(expectedPhase.equals(capacityReadBootstrapPhase(connection)),
                "V18 capacity bootstrap has not completed its expected phase");
        Map<String, String> bootstrapColumns = Map.of("bootstrap_version", "smallint", "phase", "text");
        Map<String, String> markerColumns = Map.ofEntries(
                Map.entry("marker_id", "smallint"), Map.entry("database_id", "uuid"),
                Map.entry("deployment_id", "text"), Map.entry("tenant_id", "text"),
                Map.entry("environment", "text"), Map.entry("binding_id", "text"),
                Map.entry("binding_generation", "bigint"), Map.entry("attestation_id", "uuid"),
                Map.entry("authority_id", "uuid"), Map.entry("authority_epoch", "bigint"),
                Map.entry("state", "text"));
        Map<String, String> installationColumns = Map.ofEntries(
                Map.entry("token_id", "uuid"), Map.entry("marker_id", "smallint"),
                Map.entry("database_id", "uuid"), Map.entry("deployment_id", "text"),
                Map.entry("tenant_id", "text"), Map.entry("environment", "text"),
                Map.entry("binding_id", "text"), Map.entry("binding_generation", "bigint"),
                Map.entry("attestation_id", "uuid"), Map.entry("authority_id", "uuid"),
                Map.entry("authority_epoch", "bigint"), Map.entry("request_id", "uuid"),
                Map.entry("capacity_class", "text"), Map.entry("token_ordinal", "integer"),
                Map.entry("payload_digest", "text"), Map.entry("token_state", "text"));
        validateCapacityColumns(connection, CAPACITY_READ_BOOTSTRAP_TABLE, bootstrapColumns);
        validateCapacityColumns(connection, CAPACITY_MARKER_TABLE, markerColumns);
        validateCapacityColumns(connection, CAPACITY_INSTALLATION_TABLE, installationColumns);
        for (String table : V18_TABLES) {
            validateCapacityConstraints(connection, table);
            validateCapacityIndexes(connection, table);
        }
        String source = readV18Migration();
        for (String function : Set.of("protect_capacity_marker", "reject_capacity_installation_mutation",
                "protect_capacity_read_bootstrap")) {
            String expectedBody = normalizeExpression(extractFunctionBody(source, function, "V18"));
            try (var statement = connection.prepareStatement("""
                    select p.prosrc, p.prosecdef, p.provolatile, p.prokind, p.proparallel,
                           p.proleakproof, p.proconfig = array['search_path=pg_catalog, pg_temp']::text[],
                           pg_catalog.pg_get_userbyid(p.proowner), p.prorettype::regtype::text
                    from pg_catalog.pg_proc p where p.oid=to_regprocedure(?)
                    """)) {
                statement.setString(1, SCHEMA + "." + function + "()");
                try (var rows = statement.executeQuery()) {
                    require(rows.next() && expectedBody.equals(normalizeExpression(rows.getString(1)))
                                    && !rows.getBoolean(2) && "v".equals(rows.getString(3))
                                    && "f".equals(rows.getString(4)) && "u".equals(rows.getString(5))
                                    && !rows.getBoolean(6) && rows.getBoolean(7)
                                    && roles.expectedSchemaOwnerRole().equals(rows.getString(8))
                                    && "trigger".equals(rows.getString(9)) && !rows.next(),
                            "V18 capacity function changed: " + function);
                }
            }
            try (var statement = connection.prepareStatement("""
                    select count(*) from pg_catalog.pg_proc p
                    cross join lateral aclexplode(coalesce(p.proacl, acldefault('f',p.proowner))) acl
                    where p.oid=to_regprocedure(?) and acl.grantee<>p.proowner
                    """)) {
                statement.setString(1, SCHEMA + "." + function + "()");
                try (var rows = statement.executeQuery()) {
                    require(rows.next() && rows.getLong(1) == 0 && !rows.next(),
                            "V18 capacity trigger function access changed: " + function);
                }
            }
        }
        try (var statement = connection.prepareStatement("""
                select c.relname, t.tgname, t.tgenabled, t.tgtype,
                       p.proname, t.tgfoid,
                       t.tgqual is null and t.tgnargs=0 and octet_length(t.tgargs)=0
                           and t.tgattr::text='' and t.tgconstraint=0 as no_extra_trigger_policy
                from pg_catalog.pg_trigger t
                join pg_catalog.pg_class c on c.oid=t.tgrelid
                join pg_catalog.pg_proc p on p.oid=t.tgfoid
                where c.oid in (to_regclass(?),to_regclass(?),to_regclass(?)) and not t.tgisinternal
                """)) {
            statement.setString(1, SCHEMA + "." + CAPACITY_READ_BOOTSTRAP_TABLE);
            statement.setString(2, SCHEMA + "." + CAPACITY_MARKER_TABLE);
            statement.setString(3, SCHEMA + "." + CAPACITY_INSTALLATION_TABLE);
            var seen = new LinkedHashSet<String>();
            try (var expectedOid = connection.prepareStatement("select to_regprocedure(?)::oid");
                    var rows = statement.executeQuery()) {
                while (rows.next()) {
                    String key = rows.getString(1) + "." + rows.getString(2);
                    if (BulkCapacityOccupancyCatalog.installed(connection)
                            && key.equals(CAPACITY_INSTALLATION_TABLE + ".praxis_bulk_capacity_installation_create_slot")) continue;
                    String expectedFunction = rows.getString(1).equals(CAPACITY_READ_BOOTSTRAP_TABLE)
                            ? "protect_capacity_read_bootstrap"
                            : rows.getString(1).equals(CAPACITY_MARKER_TABLE)
                                    ? "protect_capacity_marker" : "reject_capacity_installation_mutation";
                    expectedOid.setString(1, SCHEMA + "." + expectedFunction + "()");
                    long functionOid;
                    try (var function = expectedOid.executeQuery()) {
                        require(function.next(), "V18 capacity trigger function is absent");
                        functionOid = function.getLong(1);
                        require(!function.wasNull() && !function.next(),
                                "V18 capacity trigger function is ambiguous");
                    }
                    require(seen.add(key) && V18_TRIGGERS.contains(key)
                                    && "O".equals(rows.getString(3)) && rows.getInt(4) == 27
                                    && expectedFunction.equals(rows.getString(5))
                                    && rows.getLong(6) == functionOid && rows.getBoolean(7),
                            "V18 capacity trigger changed");
                }
            }
            require(seen.equals(V18_TRIGGERS), "V18 capacity trigger inventory changed");
        }
        for (String role : roles.runtimeGranteeRoles())
            require(tableRolePrivileges(connection, CAPACITY_READ_BOOTSTRAP_TABLE, role).isEmpty(),
                    "V18 runtime must not read capacity bootstrap");
        for (String role : roles.controlPlaneGranteeRoles())
            require(tableRolePrivileges(connection, CAPACITY_READ_BOOTSTRAP_TABLE, role).isEmpty(),
                    "V18 control plane must not read capacity bootstrap");
        require(tableRolePrivileges(connection, CAPACITY_READ_BOOTSTRAP_TABLE, "PUBLIC").isEmpty()
                        && tableRolePrivileges(connection, CAPACITY_READ_BOOTSTRAP_TABLE,
                                "praxis_bulk_retention_owner").isEmpty()
                        && tableRolePrivileges(connection, CAPACITY_READ_BOOTSTRAP_TABLE,
                                "praxis_bulk_control_owner").isEmpty(),
                "V18 capacity bootstrap privilege changed");
        for (String table : V18_TABLES) {
            if (CAPACITY_READ_BOOTSTRAP_TABLE.equals(table)) continue;
            for (String role : roles.runtimeGranteeRoles())
                require(tableRolePrivileges(connection, table, role).equals(
                                expectedGrant ? Set.of("T:SELECT") : Set.of()),
                        "V18 runtime capacity grant changed: " + table);
            for (String role : roles.controlPlaneGranteeRoles())
                require(tableRolePrivileges(connection, table, role).isEmpty(),
                        "V18 control-plane capacity grant changed: " + table);
            require(tableRolePrivileges(connection, table, "praxis_bulk_retention_owner").isEmpty()
                            && tableRolePrivileges(connection, table, "praxis_bulk_control_owner").isEmpty(),
                    "V18 internal capacity grant changed: " + table);
        }
    }

    private static void validateCapacityColumns(Connection connection, String table,
            Map<String, String> expected) throws SQLException {
        var seen = new LinkedHashSet<String>();
        try (var statement = connection.prepareStatement("""
                select a.attname, pg_catalog.format_type(a.atttypid,a.atttypmod), a.attnotnull,
                       pg_catalog.pg_get_expr(d.adbin,d.adrelid), a.attgenerated, a.attidentity
                from pg_catalog.pg_attribute a
                left join pg_catalog.pg_attrdef d on d.adrelid=a.attrelid and d.adnum=a.attnum
                where a.attrelid=to_regclass(?) and a.attnum>0 and not a.attisdropped
                """)) {
            statement.setString(1, SCHEMA + "." + table);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    String name = rows.getString(1);
                    String value = rows.getString(4);
                    boolean defaultOne = name.equals("marker_id");
                    require(seen.add(name) && expected.get(name) != null
                                    && expected.get(name).equals(rows.getString(2)) && rows.getBoolean(3)
                                    && (defaultOne ? "1".equals(value) || "1::smallint".equals(value)
                                            : value == null)
                                    && "".equals(rows.getString(5)) && "".equals(rows.getString(6)),
                            "V18 capacity column changed: " + table + "." + name);
                }
            }
        }
        require(seen.equals(expected.keySet()), "V18 capacity column inventory changed: " + table);
    }

    private record CapacityConstraint(String type, String definition, String key, String foreignKey,
                                      String foreignTable, String updateAction, String deleteAction,
                                      String matchType) { }

    private static CapacityConstraint capacityKey(String type, String definition, String key) {
        return new CapacityConstraint(type, definition, key, "-", "-", " ", " ", " ");
    }

    private static CapacityConstraint capacityCheck(String definition, String key) {
        return capacityKey("c", definition, key);
    }

    private static CapacityConstraint capacityForeign(String definition, String key, String foreignKey) {
        return new CapacityConstraint("f", definition, key, foreignKey, CAPACITY_MARKER_TABLE,
                "a", "r", "s");
    }

    /** Fixed PG14 rendering of the reviewed V18 SQL; no live catalog supplies expectations. */
    private static Map<String, CapacityConstraint> expectedCapacityConstraints(String table) {
        return switch (table) {
            case CAPACITY_READ_BOOTSTRAP_TABLE -> Map.of(
                    "capacity_read_bootstrap_pkey", capacityKey("p", "PRIMARY KEY (bootstrap_version)", "1"),
                    "capacity_read_bootstrap_version_check", capacityCheck(
                            "CHECK ((bootstrap_version = 18))", "1"),
                    "capacity_read_bootstrap_phase_check", capacityCheck(
                            "CHECK ((phase = ANY (ARRAY['PENDING'::text, 'COMPLETE'::text])))", "2"));
            case CAPACITY_MARKER_TABLE -> Map.ofEntries(
                    Map.entry("capacity_marker_pkey", capacityKey("p", "PRIMARY KEY (marker_id)", "1")),
                    Map.entry("capacity_marker_singleton", capacityCheck("CHECK ((marker_id = 1))", "1")),
                    Map.entry("capacity_marker_database_unique", capacityKey("u", "UNIQUE (database_id)", "2")),
                    Map.entry("capacity_marker_generation_positive", capacityCheck(
                            "CHECK ((binding_generation > 0))", "7")),
                    Map.entry("capacity_marker_attestation_unique", capacityKey("u", "UNIQUE (attestation_id)", "8")),
                    Map.entry("capacity_marker_epoch_positive", capacityCheck("CHECK ((authority_epoch > 0))", "10")),
                    Map.entry("capacity_marker_state_check", capacityCheck(
                            "CHECK ((state = ANY (ARRAY['PROVISIONED'::text, 'ACTIVE'::text, 'FENCED'::text])))",
                            "11")),
                    Map.entry("capacity_marker_full_identity_unique", capacityKey("u",
                            "UNIQUE (marker_id, database_id, deployment_id, tenant_id, environment, "
                            + "binding_id, binding_generation, attestation_id, authority_id, authority_epoch)",
                            "1 2 3 4 5 6 7 8 9 10")),
                    Map.entry("capacity_marker_deployment_canonical", capacityCheck(
                            "CHECK (((deployment_id <> ''::text) AND (deployment_id = btrim(deployment_id)) "
                            + "AND (deployment_id !~ '[[:cntrl:]]'::text)))", "3")),
                    Map.entry("capacity_marker_tenant_canonical", capacityCheck(
                            "CHECK (((tenant_id <> ''::text) AND (tenant_id = btrim(tenant_id)) "
                            + "AND (tenant_id !~ '[[:cntrl:]]'::text)))", "4")),
                    Map.entry("capacity_marker_environment_canonical", capacityCheck(
                            "CHECK (((environment <> ''::text) AND (environment = btrim(environment)) "
                            + "AND (environment !~ '[[:cntrl:]]'::text)))", "5")),
                    Map.entry("capacity_marker_binding_canonical", capacityCheck(
                            "CHECK (((binding_id <> ''::text) AND (binding_id = btrim(binding_id)) "
                            + "AND (binding_id !~ '[[:cntrl:]]'::text)))", "6")));
            case CAPACITY_INSTALLATION_TABLE -> Map.ofEntries(
                    Map.entry("capacity_installation_pkey", capacityKey("p", "PRIMARY KEY (token_id)", "1")),
                    Map.entry("capacity_installation_singleton", capacityCheck("CHECK ((marker_id = 1))", "2")),
                    Map.entry("capacity_installation_generation_positive", capacityCheck(
                            "CHECK ((binding_generation > 0))", "8")),
                    Map.entry("capacity_installation_epoch_positive", capacityCheck(
                            "CHECK ((authority_epoch > 0))", "11")),
                    Map.entry("capacity_installation_class_check", capacityCheck(
                            "CHECK ((capacity_class = ANY (ARRAY['ACTIVE'::text, 'QUEUE'::text])))", "13")),
                    Map.entry("capacity_installation_ordinal_positive", capacityCheck(
                            "CHECK ((token_ordinal > 0))", "14")),
                    Map.entry("capacity_installation_digest_check", capacityCheck(
                            "CHECK ((payload_digest ~ '^sha256:[0-9a-f]{64}$'::text))", "15")),
                    Map.entry("capacity_installation_token_state_check", capacityCheck(
                            "CHECK ((token_state = 'ISSUED'::text))", "16")),
                    Map.entry("capacity_installation_request_ordinal_unique", capacityKey("u",
                            "UNIQUE (request_id, token_ordinal)", "12 14")),
                    Map.entry("capacity_installation_marker_fk", capacityForeign(
                            "FOREIGN KEY (marker_id, database_id, deployment_id, tenant_id, environment, "
                            + "binding_id, binding_generation, attestation_id, authority_id, authority_epoch) "
                            + "REFERENCES praxis_bulk_capacity_marker(marker_id, database_id, deployment_id, "
                            + "tenant_id, environment, binding_id, binding_generation, attestation_id, "
                            + "authority_id, authority_epoch) ON DELETE RESTRICT",
                            "2 3 4 5 6 7 8 9 10 11", "1 2 3 4 5 6 7 8 9 10")));
            default -> throw new IllegalArgumentException("Unknown V18 capacity table: " + table);
        };
    }

    private static String capacityArrayKey(String value) {
        return value == null ? "-" : value.replace("{", "").replace("}", "").replace(",", " ");
    }

    private static String capacityDefinition(String value) {
        if (value == null) return "-";
        // pg_get_constraintdef alone may qualify the referenced table under pg_catalog
        // search_path. The FK OID and exact key arrays are checked separately below.
        return normalizeExpression(value).replace("referencespraxis_bulk.", "references");
    }

    private static void validateCapacityConstraints(Connection connection, String table) throws SQLException {
        Map<String, CapacityConstraint> expected = new LinkedHashMap<>(expectedCapacityConstraints(table));
        if (BulkCapacityOccupancyCatalog.installed(connection) && table.equals(CAPACITY_INSTALLATION_TABLE))
            expected.put("capacity_installation_token_class_key", capacityKey("u", "UNIQUE (token_id, capacity_class)", "1 13"));
        var seen = new LinkedHashSet<String>();
        try (var statement = connection.prepareStatement("""
                select k.conname, pg_catalog.pg_get_constraintdef(k.oid,false), k.convalidated,
                       k.condeferrable, k.condeferred, k.contype, k.conkey::text, k.confkey::text,
                       case when k.confrelid=0 then '-' else k.confrelid::regclass::text end,
                       k.confupdtype, k.confdeltype, k.confmatchtype
                from pg_catalog.pg_constraint k where k.conrelid=to_regclass(?)
                """)) {
            statement.setString(1, SCHEMA + "." + table);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    String name = rows.getString(1);
                    CapacityConstraint source = expected.get(name);
                    require(seen.add(name) && source != null
                                    && capacityDefinition(source.definition()).equals(
                                            capacityDefinition(rows.getString(2)))
                                    && rows.getBoolean(3) && !rows.getBoolean(4) && !rows.getBoolean(5)
                                    && source.type().equals(rows.getString(6))
                                    && source.key().equals(capacityArrayKey(rows.getString(7)))
                                    && source.foreignKey().equals(capacityArrayKey(rows.getString(8)))
                                    && source.foreignTable().equals(
                                            rows.getString(9).replace("praxis_bulk.", ""))
                                    && source.updateAction().equals(rows.getString(10))
                                    && source.deleteAction().equals(rows.getString(11))
                                    && source.matchType().equals(rows.getString(12)),
                            "V18 capacity constraint changed: " + table + "." + name);
                }
            }
        }
        require(seen.equals(expected.keySet()), "V18 capacity constraint inventory changed: " + table);
    }

    private record CapacityIndex(String columns, String key, boolean primary, int width) { }

    private static Map<String, CapacityIndex> expectedCapacityIndexes(String table) {
        return switch (table) {
            case CAPACITY_READ_BOOTSTRAP_TABLE -> Map.of(
                    "capacity_read_bootstrap_pkey", new CapacityIndex("bootstrap_version", "1", true, 1));
            case CAPACITY_MARKER_TABLE -> Map.of(
                    "capacity_marker_pkey", new CapacityIndex("marker_id", "1", true, 1),
                    "capacity_marker_database_unique", new CapacityIndex("database_id", "2", false, 1),
                    "capacity_marker_attestation_unique", new CapacityIndex("attestation_id", "8", false, 1),
                    "capacity_marker_full_identity_unique", new CapacityIndex(
                            "marker_id, database_id, deployment_id, tenant_id, environment, binding_id, "
                            + "binding_generation, attestation_id, authority_id, authority_epoch",
                            "1 2 3 4 5 6 7 8 9 10", false, 10));
            case CAPACITY_INSTALLATION_TABLE -> Map.of(
                    "capacity_installation_pkey", new CapacityIndex("token_id", "1", true, 1),
                    "capacity_installation_request_ordinal_unique", new CapacityIndex(
                            "request_id, token_ordinal", "12 14", false, 2));
            default -> throw new IllegalArgumentException("Unknown V18 capacity table: " + table);
        };
    }

    private static void validateCapacityIndexes(Connection connection, String table) throws SQLException {
        Map<String, CapacityIndex> expected = new LinkedHashMap<>(expectedCapacityIndexes(table));
        if (BulkCapacityOccupancyCatalog.installed(connection) && table.equals(CAPACITY_INSTALLATION_TABLE))
            expected.put("capacity_installation_token_class_key", new CapacityIndex("token_id, capacity_class", "1 13", false, 2));
        var seen = new LinkedHashSet<String>();
        try (var statement = connection.prepareStatement("""
                select i.relname, pg_catalog.pg_get_indexdef(i.oid), x.indkey::text,
                       x.indisunique, x.indisprimary, x.indisvalid, x.indisready, x.indislive,
                       pg_catalog.pg_get_expr(x.indpred,x.indrelid,false), x.indexprs is null,
                       x.indnkeyatts, x.indnatts
                from pg_catalog.pg_index x join pg_catalog.pg_class i on i.oid=x.indexrelid
                where x.indrelid=to_regclass(?)
                """)) {
            statement.setString(1, SCHEMA + "." + table);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    String name = rows.getString(1);
                    CapacityIndex source = expected.get(name);
                    require(seen.add(name) && source != null
                                    && normalizeExpression(rows.getString(2)).equals(normalizeExpression(
                                            "CREATE UNIQUE INDEX " + name + " ON praxis_bulk." + table
                                                    + " USING btree (" + source.columns() + ")"))
                                    && source.key().equals(capacityArrayKey(rows.getString(3)))
                                    && rows.getBoolean(4) && rows.getBoolean(5) == source.primary()
                                    && rows.getBoolean(6) && rows.getBoolean(7) && rows.getBoolean(8)
                                    && rows.getString(9) == null && rows.getBoolean(10)
                                    && rows.getInt(11) == source.width()
                                    && rows.getInt(12) == source.width(),
                            "V18 capacity index changed: " + table + "." + name);
                }
            }
        }
        require(seen.equals(expected.keySet()), "V18 capacity index inventory changed: " + table);
    }

    private static MigrationExpectations migrationExpectations() {
        MigrationExpectations cached = migrationExpectations;
        if (cached != null) return cached;
        synchronized (BulkExecutionMigrator.class) {
            cached = migrationExpectations;
            if (cached == null) {
                cached = loadMigrationExpectations();
                migrationExpectations = cached;
            }
        }
        return cached;
    }

    private static MigrationExpectations loadMigrationExpectations() {
        String v5Migration = readV5Migration();
        String v6Migration = readV6Migration();
        String v7Migration = readV7Migration();
        var v5FunctionNames = new LinkedHashSet<String>();
        V5_FUNCTIONS.forEach(signature -> v5FunctionNames.add(functionName(signature)));
        v5FunctionNames.add(functionName(RECEIPT_FUNCTION + "()"));
        v5FunctionNames.add(functionName(ADMISSION_FUNCTION + "()"));

        var v6FunctionNames = new LinkedHashSet<String>();
        V6_FUNCTIONS.forEach(signature -> v6FunctionNames.add(functionName(signature)));

        var expectedBodies = new LinkedHashMap<String, FunctionBodyExpectation>();
        for (String function : v5FunctionNames) {
            if (!v6FunctionNames.contains(function)) {
                expectedBodies.put(function,
                        new FunctionBodyExpectation("V5", normalizeExpression(
                                extractFunctionBody(v5Migration, function, "V5"))));
            }
        }
        for (String function : v6FunctionNames) {
            expectedBodies.put(function,
                    new FunctionBodyExpectation("V6", normalizeExpression(
                            extractFunctionBody(v6Migration, function, "V6"))));
        }
        String v8Migration = readV8Migration();
        for (String function : Set.of("purge_terminal_execution", "expire_unconsumed_proposal",
                "require_complete_target_manifest", "reject_target_manifest_mutation")) {
            expectedBodies.put(function, new FunctionBodyExpectation("V8", normalizeExpression(
                    extractFunctionBody(v8Migration, function, "V8"))));
        }
        String v9Migration = readV9Migration();
        for (String function : Set.of("purge_terminal_execution", "expire_unconsumed_proposal",
                "require_complete_target_preview", "require_complete_preview_parent",
                "reject_preview_mutation")) {
            expectedBodies.put(function, new FunctionBodyExpectation("V9", normalizeExpression(
                    extractFunctionBody(v9Migration, function, "V9"))));
        }
        String v10Migration = readV10Migration();
        for (String function : Set.of("protect_cancel_request", "guard_terminal_evidence_insert",
                "purge_terminal_execution")) {
            expectedBodies.put(function, new FunctionBodyExpectation("V10", normalizeExpression(
                    extractFunctionBody(v10Migration, function, "V10"))));
        }
        String v11Migration = readV11Migration();
        for (String function : Set.of("require_complete_preview_integrity_bootstrap",
                "require_complete_preview_item_integrity", "purge_terminal_execution",
                "expire_unconsumed_proposal")) {
            expectedBodies.put(function, new FunctionBodyExpectation("V11", normalizeExpression(
                    extractFunctionBody(v11Migration, function, "V11"))));
        }
        String v12Migration = readV12Migration();
        expectedBodies.put("assert_preview_integrity_complete", new FunctionBodyExpectation("V12",
                normalizeExpression(extractFunctionBody(v12Migration,
                        "assert_preview_integrity_complete", "V12"))));
        String v13Migration = readV13Migration();
        expectedBodies.put("guard_terminal_execution", new FunctionBodyExpectation("V13",
                normalizeExpression(extractFunctionBody(v13Migration, "guard_terminal_execution", "V13"))));
        String v14Migration = readV14Migration();
        for (String signature : V14_FUNCTIONS) {
            String function = functionName(signature);
            expectedBodies.put(function, new FunctionBodyExpectation("V14",
                    normalizeExpression(extractFunctionBody(v14Migration, function, "V14"))));
        }
        String v15Migration = readV15Migration();
        for (String function : Set.of("lock_operation_control", "transition_operation_control",
                "guard_new_bulk_admission", "guard_new_bulk_evaluation", "transition_openapi_publication",
                "purge_terminal_execution", "expire_unconsumed_proposal")) {
            expectedBodies.put(function, new FunctionBodyExpectation("V15",
                    normalizeExpression(extractFunctionBody(v15Migration, function, "V15"))));
        }
        String v16Migration = readV16Migration();
        for (String signature : V16_FUNCTIONS) {
            String function = functionName(signature);
            expectedBodies.put(function, new FunctionBodyExpectation("V16",
                    normalizeExpression(extractFunctionBody(v16Migration, function, "V16"))));
        }
        for (String function : Set.of("terminal_evidence_complete", "purge_terminal_execution"))
            expectedBodies.put(function, new FunctionBodyExpectation("V16",
                    normalizeExpression(extractFunctionBody(v16Migration, function, "V16"))));
        String v17Migration = readV17Migration();
        expectedBodies.put("guard_bucket_mutation", new FunctionBodyExpectation("V17",
                normalizeExpression(extractFunctionBody(v17Migration, "guard_bucket_mutation", "V17"))));
        return new MigrationExpectations(expectedBodies,
                normalizeExpression(extractFunctionBody(v7Migration, INSERT_FENCE_FUNCTION, "V7")));
    }

    private static String functionName(String signature) {
        int argumentsStart = signature.indexOf('(');
        require(argumentsStart > 0, "Invalid governed function signature: " + signature);
        return signature.substring(0, argumentsStart);
    }

    private record FunctionBodyExpectation(String version, String normalizedBody) {
        private FunctionBodyExpectation {
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(normalizedBody, "normalizedBody");
        }
    }

    private record MigrationExpectations(Map<String, FunctionBodyExpectation> functionBodies,
            String normalizedDescriptorFenceBody) {
        private MigrationExpectations {
            functionBodies = Map.copyOf(functionBodies);
            Objects.requireNonNull(normalizedDescriptorFenceBody, "normalizedDescriptorFenceBody");
        }
    }

    private static String extractFunctionBody(String migration, String function, String version) {
        Pattern pattern = Pattern.compile("(?is)create\\s+(?:or\\s+replace\\s+)?function\\s+praxis_bulk\\."
                + Pattern.quote(function) + "\\s*\\([^)]*\\).*?\\bas\\s*\\$\\$(.*?)\\$\\$\\s*;");
        Matcher matcher = pattern.matcher(migration);
        require(matcher.find(), "Function is absent from " + version + " migration resource: " + function);
        String body = matcher.group(1);
        require(!matcher.find(), "Function is duplicated in " + version + " migration resource: " + function);
        return body;
    }

    private static void validateV5FunctionPrivileges(Connection connection,
            BulkExecutionRoleConfiguration roles) throws SQLException {
        validateV5FunctionPrivileges(connection, roles, Map.of());
    }

    private static void validateV5FunctionPrivileges(Connection connection,
            BulkExecutionRoleConfiguration roles, Map<String, String> phases) throws SQLException {
        var expected = new LinkedHashSet<String>(Set.of(
                "terminal_evidence_complete(p_execution_id uuid, p_required_count integer)|praxis_bulk_retention_owner|EXECUTE",
                "atomic_evidence_complete(p_execution_id uuid, p_required_count integer)|praxis_bulk_retention_owner|EXECUTE",
                "lock_operation_control(p_namespace_id text, p_operation_id text)|praxis_bulk_retention_owner|EXECUTE",
                "purge_terminal_execution(p_execution_id uuid)|praxis_bulk_retention_executor|EXECUTE",
                "expire_unconsumed_proposal(p_proposal_id uuid)|praxis_bulk_retention_executor|EXECUTE"));
        if (BulkCapacityOccupancyCatalog.installed(connection))
            expected.add("lock_operation_control(p_namespace_id text, p_operation_id text)|" + BulkCapacityOccupancyCatalog.OWNER + "|EXECUTE");
        roles.runtimeGranteeRoles().forEach(role -> expected.add(
                "lock_operation_control(p_namespace_id text, p_operation_id text)|" + role + "|EXECUTE"));
        if ("COMPLETE".equals(phase(phases, PREVIEW_READER_BOOTSTRAP_TABLE)))
            roles.runtimeGranteeRoles().forEach(role -> expected.add(
                    "assert_preview_integrity_complete()|" + role + "|EXECUTE"));
        roles.runtimeGranteeRoles().forEach(role -> expected.add(
                "lock_openapi_publication(p_namespace_id text, p_deployment_id text)|" + role + "|EXECUTE"));
        if ("COMPLETE".equals(phase(phases, ATOMIC_BOOTSTRAP_TABLE)))
            roles.runtimeGranteeRoles().forEach(role -> expected.add(
                    "atomic_evidence_complete(p_execution_id uuid, p_required_count integer)|" + role + "|EXECUTE"));
        roles.controlPlaneGranteeRoles().forEach(role -> expected.add(
                "transition_operation_control(p_namespace_id text, p_operation_id text, p_expected_generation bigint, p_target_state text, p_descriptor_fingerprint text, p_structural_revision text, p_expected_publication_generation bigint, p_expected_publication_digest text)|" + role + "|EXECUTE"));
        roles.controlPlaneGranteeRoles().forEach(role -> expected.add(
                "transition_openapi_publication(p_namespace_id text, p_deployment_id text, p_expected_generation bigint, p_target_state text, p_document_digest text)|" + role + "|EXECUTE"));
        var actual = new LinkedHashSet<String>();
        try (var statement = connection.prepareStatement("""
                select p.proname || '(' || pg_get_function_identity_arguments(p.oid) || ')',
                       coalesce(grantee.rolname, 'PUBLIC'), acl.privilege_type, acl.is_grantable,
                       acl.grantee = p.proowner
                from pg_proc p join pg_namespace n on n.oid=p.pronamespace
                cross join lateral aclexplode(coalesce(p.proacl, acldefault('f', p.proowner))) acl
                left join pg_roles grantee on grantee.oid=acl.grantee
                where n.nspname=? and (p.proname || '(' || pg_get_function_identity_arguments(p.oid) || ')')
                      = any (?::text[])
                """)) {
            statement.setString(1, SCHEMA);
            var functionKeys = new LinkedHashSet<>(V5_FUNCTIONS);
            functionKeys.addAll(V15_CONTROL_FUNCTIONS);
            functionKeys.addAll(V7_FUNCTIONS);
            functionKeys.addAll(V8_FUNCTIONS);
            functionKeys.addAll(V9_FUNCTIONS);
            functionKeys.addAll(V10_FUNCTIONS);
            functionKeys.addAll(V11_FUNCTIONS);
            functionKeys.addAll(V12_FUNCTIONS);
            functionKeys.addAll(V14_FUNCTIONS);
            functionKeys.addAll(V16_FUNCTIONS);
            if (isV18Installed(connection)) functionKeys.addAll(V18_FUNCTIONS);
            statement.setArray(2, connection.createArrayOf("text", functionKeys.toArray()));
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (rows.getBoolean(5)) continue;
                    require(!rows.getBoolean(4), "retention function grant option is forbidden");
                    actual.add(rows.getString(1) + "|" + rows.getString(2) + "|" + rows.getString(3));
                }
            }
        }
        require(actual.equals(expected), "governed lifecycle function grants differ");
    }

    private static void validateV5RolesAndPrivileges(Connection connection,
            BulkExecutionRoleConfiguration roleConfiguration) throws SQLException {
        validateV5RolesAndPrivileges(connection, roleConfiguration, false);
    }

    private static void validateV5RolesAndPrivileges(Connection connection,
            BulkExecutionRoleConfiguration roleConfiguration, boolean liveCaller) throws SQLException {
        validateV5RolesAndPrivileges(connection, roleConfiguration, liveCaller, Map.of());
    }

    private static void validateV5RolesAndPrivileges(Connection connection,
            BulkExecutionRoleConfiguration roleConfiguration, boolean liveCaller, Map<String, String> phases) throws SQLException {
        validateConfiguredRoles(connection, roleConfiguration);
        validateConfiguredRoleInheritance(connection, roleConfiguration);
        validateSchemaAndTableOwners(connection, roleConfiguration.expectedSchemaOwnerRole());
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
                select count(*) from pg_roles
                where rolname in ('praxis_bulk_retention_owner', 'praxis_bulk_retention_executor',
                                  'praxis_bulk_control_owner')
                  and not rolcanlogin and not rolinherit and not rolsuper and not rolcreatedb
                  and not rolcreaterole and not rolreplication and not rolbypassrls
                """)) {
            require(rows.next() && rows.getLong(1) == 3 && !rows.next(),
                    "bulk internal roles must be unprivileged NOLOGIN NOINHERIT roles");
        }
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
                select count(*) from pg_auth_members m
                join pg_roles granted on granted.oid=m.roleid
                join pg_roles member on member.oid=m.member
                where granted.rolname in ('praxis_bulk_retention_owner', 'praxis_bulk_control_owner')
                   or member.rolname in ('praxis_bulk_retention_owner', 'praxis_bulk_retention_executor',
                                         'praxis_bulk_control_owner')
                """)) {
            require(rows.next() && rows.getLong(1) == 0 && !rows.next(),
                    "bulk internal owner/member topology is unsafe");
        }
        var expectedSchema = new LinkedHashSet<String>();
        expectedSchema.add("praxis_bulk_retention_owner|USAGE");
        expectedSchema.add("praxis_bulk_retention_executor|USAGE");
        expectedSchema.add("praxis_bulk_control_owner|USAGE");
        roleConfiguration.runtimeGranteeRoles().forEach(role -> expectedSchema.add(role + "|USAGE"));
        roleConfiguration.controlPlaneGranteeRoles().forEach(role -> expectedSchema.add(role + "|USAGE"));
        if (BulkCapacityOccupancyCatalog.installed(connection)) expectedSchema.add(BulkCapacityOccupancyCatalog.OWNER + "|USAGE");
        var actualSchema = new LinkedHashSet<String>();
        try (var statement = connection.prepareStatement("""
                select coalesce(r.rolname, 'PUBLIC'), acl.privilege_type, acl.is_grantable,
                       acl.grantee=n.nspowner
                from pg_namespace n
                cross join lateral aclexplode(coalesce(n.nspacl, acldefault('n', n.nspowner))) acl
                left join pg_roles r on r.oid=acl.grantee where n.nspname=?
                """)) {
            statement.setString(1, SCHEMA);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (rows.getBoolean(4)) continue;
                    String role = rows.getString(1);
                    require(!"PUBLIC".equals(role) && !rows.getBoolean(3),
                            "PUBLIC or grant-option access to bulk schema is forbidden");
                    actualSchema.add(role + "|" + rows.getString(2));
                }
            }
        }
        require(actualSchema.equals(expectedSchema), "bulk retention schema grants differ");

        Map<String, Set<String>> expectedOwner = new LinkedHashMap<>(Map.ofEntries(
                Map.entry(NAMESPACE_BINDING_TABLE, Set.of("T:SELECT", "C:deployment_id:UPDATE")),
                Map.entry(OPENAPI_PUBLICATION_TABLE, Set.of()),
                Map.entry(OPERATION_CONTROL_TABLE, Set.of()),
                Map.entry(DEPLOYMENT_BUCKET_TABLE, Set.of("T:SELECT", "C:deployment_id:UPDATE")),
                Map.entry(SUBJECT_BUCKET_TABLE, Set.of("T:SELECT", "C:deployment_id:UPDATE")),
                Map.entry(PROPOSAL_TABLE, Set.of("T:SELECT", "T:DELETE", "C:proposal_id:UPDATE")),
                Map.entry(EVALUATION_TABLE, Set.of("T:SELECT", "T:DELETE")),
                Map.entry(MANIFEST_TABLE, Set.of("T:SELECT", "T:DELETE")),
                Map.entry(PREVIEW_STATE_TABLE, Set.of("T:SELECT", "T:DELETE")),
                Map.entry(TARGET_PREVIEW_TABLE, Set.of("T:SELECT", "T:DELETE")),
                Map.entry(PREVIEW_INTEGRITY_TABLE, Set.of("T:SELECT", "T:DELETE")),
                Map.entry(EXECUTION_TABLE, Set.of("T:SELECT", "T:DELETE", "C:execution_id:UPDATE")),
                Map.entry(RECEIPT_TABLE, Set.of("T:SELECT", "T:DELETE")),
                Map.entry(ADMISSION_TABLE, Set.of("T:SELECT", "T:DELETE")),
                Map.entry(ATOMIC_RECEIPT_TABLE, Set.of("T:SELECT", "T:DELETE")),
                Map.entry(ATOMIC_ITEM_TABLE, Set.of("T:SELECT", "T:DELETE")),
                Map.entry(ATOMIC_EFFECT_TABLE, Set.of("T:SELECT", "T:DELETE")),
                Map.entry(ATOMIC_REJECTION_TABLE, Set.of("T:SELECT", "T:DELETE")),
                Map.entry(ALLOCATION_TABLE, Set.of("T:SELECT", "T:DELETE", "C:state:UPDATE",
                        "C:released_at:UPDATE", "C:release_reason:UPDATE")),
                Map.entry(TOMBSTONE_TABLE, Set.of("T:SELECT", "T:INSERT"))));
        if (isV18Installed(connection)) {
            expectedOwner.put(CAPACITY_READ_BOOTSTRAP_TABLE, Set.of());
            expectedOwner.put(CAPACITY_MARKER_TABLE, Set.of());
            expectedOwner.put(CAPACITY_INSTALLATION_TABLE, Set.of());
        }
        if (BulkCapacityOccupancyCatalog.installed(connection)) {
            expectedOwner.put(BulkCapacityOccupancyCatalog.SLOT, Set.of("T:SELECT"));
            expectedOwner.put(BulkCapacityOccupancyCatalog.HISTORY, Set.of("T:SELECT", "T:DELETE"));
            expectedOwner.put(BulkCapacityOccupancyCatalog.BOOTSTRAP, Set.of());
        }
        for (var entry : expectedOwner.entrySet()) {
            Set<String> actual = tableRolePrivileges(connection, entry.getKey(),
                    "praxis_bulk_retention_owner");
            require(actual.equals(entry.getValue()), "retention-owner grants differ: " + entry.getKey());
            require(tableRolePrivileges(connection, entry.getKey(), "praxis_bulk_retention_executor").isEmpty(),
                    "retention executor must not have direct table privileges: " + entry.getKey());
            require(tableRolePrivileges(connection, entry.getKey(), "PUBLIC").isEmpty(),
                    "PUBLIC must not have governed lifecycle table privileges: " + entry.getKey());
        }
        require(tableRolePrivileges(connection, OPERATION_CONTROL_TABLE, "praxis_bulk_control_owner")
                        .equals(Set.of("T:SELECT", "C:state:UPDATE", "C:generation:UPDATE",
                                "C:descriptor_fingerprint:UPDATE", "C:structural_revision:UPDATE",
                                "C:updated_at:UPDATE", "C:publication_generation:UPDATE", "C:publication_document_digest:UPDATE")),
                "operation-control definer-owner privileges differ");
        require(tableRolePrivileges(connection, OPENAPI_PUBLICATION_TABLE, "praxis_bulk_control_owner")
                        .equals(Set.of("T:SELECT", "C:state:UPDATE", "C:generation:UPDATE",
                                "C:document_digest:UPDATE", "C:updated_at:UPDATE")),
                "OpenAPI publication definer-owner privileges differ");
        require(tableRolePrivileges(connection, NAMESPACE_BINDING_TABLE, "praxis_bulk_control_owner")
                        .equals(Set.of("T:SELECT", "C:deployment_id:UPDATE")),
                "publication definer-owner namespace-lock privileges differ");
        require(tableRolePrivileges(connection, PROPOSAL_TABLE, "praxis_bulk_control_owner")
                        .equals(Set.of("C:proposal_id:SELECT", "C:namespace_id:SELECT", "C:operation_id:SELECT",
                                "C:control_generation:SELECT", "C:control_descriptor_fingerprint:SELECT",
                                "C:control_structural_revision:SELECT")),
                "operation-control definer-owner proposal column privileges differ");
        require(tableRolePrivileges(connection, EXECUTION_TABLE, "praxis_bulk_control_owner").isEmpty(),
                "operation-control definer-owner must not read execution payloads");
        for (String table : V5_TABLES) {
            if (!OPERATION_CONTROL_TABLE.equals(table) && !PROPOSAL_TABLE.equals(table)
                    && !EXECUTION_TABLE.equals(table) && !OPENAPI_PUBLICATION_TABLE.equals(table)
                    && !NAMESPACE_BINDING_TABLE.equals(table)) {
                require(tableRolePrivileges(connection, table, "praxis_bulk_control_owner").isEmpty(),
                        "operation-control definer-owner has unrelated table privileges: " + table);
            }
        }
        for (String role : roleConfiguration.runtimeGranteeRoles()) {
            require(tableRolePrivileges(connection, MANIFEST_TABLE, role).equals(bootstrapTableGrants(phase(phases, MANIFEST_BOOTSTRAP_TABLE))),
                    "bulk runtime manifest grants differ: " + role);
            for (String table : List.of(PREVIEW_STATE_TABLE, TARGET_PREVIEW_TABLE, PREVIEW_INTEGRITY_TABLE))
                require(tableRolePrivileges(connection, table, role).equals(bootstrapTableGrants(phase(phases,
                                PREVIEW_INTEGRITY_TABLE.equals(table) ? PREVIEW_INTEGRITY_BOOTSTRAP_TABLE : PREVIEW_BOOTSTRAP_TABLE))),
                        "bulk runtime preview grants differ: " + role + " " + table);
        }
        validateRuntimeTablePrivileges(connection, roleConfiguration.runtimeGranteeRoles(), liveCaller, phases);
        validateRuntimeRoleMemberships(connection, roleConfiguration.runtimeGranteeRoles());
        validateConfiguredRoleMembershipClosure(connection, roleConfiguration.controlPlaneGranteeRoles(),
                roleConfiguration.controlPlaneGranteeRoles(),
                "control-plane role membership introduces an unconfigured grantee");
        validateRetentionExecutorMemberships(connection, roleConfiguration.retentionExecutorMembers());
    }

    private static void validateConfiguredRoles(Connection connection,
            BulkExecutionRoleConfiguration configuration) throws SQLException {
        var expected = new LinkedHashSet<>(configuration.runtimeGranteeRoles());
        expected.addAll(configuration.retentionExecutorMembers());
        expected.addAll(configuration.controlPlaneGranteeRoles());
        if (expected.isEmpty()) return;
        var actual = new LinkedHashSet<String>();
        try (var statement = connection.prepareStatement("""
                select rolname from pg_roles
                where rolname = any (?::text[])
                  and not rolsuper and not rolcreatedb and not rolcreaterole
                  and not rolreplication and not rolbypassrls
                """)) {
            statement.setArray(1, connection.createArrayOf("text", expected.toArray()));
            try (var rows = statement.executeQuery()) {
                while (rows.next()) actual.add(rows.getString(1));
            }
        }
        require(actual.equals(expected), "configured bulk host roles must exist and be unprivileged");
    }

    private static void validateConfiguredRoleInheritance(Connection connection,
            BulkExecutionRoleConfiguration configuration) throws SQLException {
        var roots = new LinkedHashSet<>(configuration.runtimeGranteeRoles());
        roots.addAll(configuration.retentionExecutorMembers());
        roots.addAll(configuration.controlPlaneGranteeRoles());

        var allowed = new LinkedHashSet<String>();
        for (String role : configuration.retentionExecutorMembers()) {
            allowed.add(role + "|praxis_bulk_retention_executor");
            for (String retentionRole : configuration.retentionExecutorMembers()) {
                if (!role.equals(retentionRole)) allowed.add(role + "|" + retentionRole);
            }
        }
        var actual = new LinkedHashSet<String>();
        if (!roots.isEmpty()) {
            try (var statement = connection.prepareStatement("""
                    with recursive inherited_roles(root, inherited_role) as (
                        select membership.member, membership.roleid
                        from pg_auth_members membership
                        where membership.member = any (
                            select oid from pg_roles where rolname = any (?::text[]))
                        union
                        select inherited.root, membership.roleid
                        from inherited_roles inherited
                        join pg_auth_members membership on membership.member=inherited.inherited_role
                    )
                    select root.rolname, inherited.rolname
                    from inherited_roles roles
                    join pg_roles root on root.oid=roles.root
                    join pg_roles inherited on inherited.oid=roles.inherited_role
                    """)) {
                statement.setArray(1, connection.createArrayOf("text", roots.toArray()));
                try (var rows = statement.executeQuery()) {
                    while (rows.next()) actual.add(rows.getString(1) + "|" + rows.getString(2));
                }
            }
        }
        require(allowed.containsAll(actual),
                "configured bulk roles inherit unexpected PostgreSQL roles");
    }

    private static void validateSchemaAndTableOwners(Connection connection, String expectedOwner)
            throws SQLException {
        var expectedTables = new LinkedHashSet<>(V5_TABLES);
        if (isV18Installed(connection)) expectedTables.addAll(V18_TABLES);
        if (BulkCapacityOccupancyCatalog.installed(connection)) expectedTables.addAll(BulkCapacityOccupancyCatalog.TABLES);
        try (var statement = connection.prepareStatement("""
                select r.rolname from pg_namespace n join pg_roles r on r.oid=n.nspowner
                where n.nspname=?
                """)) {
            statement.setString(1, SCHEMA);
            try (var rows = statement.executeQuery()) {
                require(rows.next() && expectedOwner.equals(rows.getString(1)) && !rows.next(),
                        "bulk schema owner differs from explicit migration owner");
            }
        }
        try (var statement = connection.prepareStatement("""
                select c.relname, owner.rolname from pg_class c
                join pg_namespace n on n.oid=c.relnamespace
                join pg_roles owner on owner.oid=c.relowner
                where n.nspname=? and c.relname = any (?::text[])
                """)) {
            statement.setString(1, SCHEMA);
            statement.setArray(2, connection.createArrayOf("text", expectedTables.toArray()));
            try (var rows = statement.executeQuery()) {
                var found = new LinkedHashSet<String>();
                while (rows.next()) {
                    found.add(rows.getString(1));
                    require(expectedOwner.equals(rows.getString(2)),
                            "bulk table owner differs from explicit migration owner: " + rows.getString(1));
                }
                require(found.equals(expectedTables), "bulk table ownership inventory differs");
            }
        }
        validateNoOwnerMembership(connection, expectedOwner);
    }

    private static void validateNoOwnerMembership(Connection connection, String owner) throws SQLException {
        try (var statement = connection.prepareStatement("""
                with recursive owner_members(member) as (
                    select m.member from pg_auth_members m
                    join pg_roles owner on owner.oid=m.roleid where owner.rolname=?
                    union
                    select m.member from pg_auth_members m
                    join owner_members parent on m.roleid=parent.member
                )
                select count(*) from owner_members
                """)) {
            statement.setString(1, owner);
            try (var rows = statement.executeQuery()) {
                require(rows.next() && rows.getLong(1) == 0 && !rows.next(),
                        "migration owner cannot be assumable through PostgreSQL role membership");
            }
        }
    }

    private static String currentDatabaseRole(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection();
                var statement = connection.createStatement();
                var rows = statement.executeQuery("select current_user")) {
            require(rows.next(), "Unable to identify the current PostgreSQL role");
            String role = rows.getString(1);
            require(role != null && !role.isBlank() && !rows.next(), "Current PostgreSQL role is not canonical");
            return role;
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to identify the current PostgreSQL role", failure);
        }
    }

    private static void validateRuntimeTablePrivileges(Connection connection, Set<String> runtimeRoles,
            boolean liveCaller)
            throws SQLException {
        validateRuntimeTablePrivileges(connection, runtimeRoles, liveCaller, Map.of());
    }

    private static void validateRuntimeTablePrivileges(Connection connection, Set<String> runtimeRoles,
            boolean liveCaller, Map<String, String> phases)
            throws SQLException {
        Map<String, Set<String>> allowedByTable = new LinkedHashMap<>(Map.ofEntries(
                Map.entry(NAMESPACE_BINDING_TABLE, Set.of("T:SELECT", "C:deployment_id:UPDATE")),
                Map.entry(OPERATION_CONTROL_TABLE, Set.of()),
                Map.entry(OPENAPI_PUBLICATION_TABLE, Set.of()),
                Map.entry(DEPLOYMENT_BUCKET_TABLE, Set.of("T:SELECT", "C:deployment_id:UPDATE")),
                Map.entry(SUBJECT_BUCKET_TABLE, Set.of("T:SELECT", "T:INSERT", "C:deployment_id:UPDATE")),
                Map.entry(PROPOSAL_TABLE, Set.of("T:SELECT", "T:INSERT", "C:proposal_id:UPDATE")),
                Map.entry(EVALUATION_TABLE, Set.of("T:SELECT", "T:INSERT")),
                Map.entry(MANIFEST_TABLE, Set.of("T:SELECT", "T:INSERT")),
                Map.entry(MANIFEST_BOOTSTRAP_TABLE, Set.of()),
                Map.entry(PREVIEW_STATE_TABLE, Set.of("T:SELECT", "T:INSERT")),
                Map.entry(TARGET_PREVIEW_TABLE, Set.of("T:SELECT", "T:INSERT")),
                Map.entry(PREVIEW_BOOTSTRAP_TABLE, Set.of()),
                Map.entry(PREVIEW_INTEGRITY_TABLE, Set.of("T:SELECT", "T:INSERT")),
                Map.entry(PREVIEW_INTEGRITY_BOOTSTRAP_TABLE, Set.of()),
                Map.entry(PREVIEW_READER_BOOTSTRAP_TABLE, Set.of()),
                Map.entry(EXECUTION_TABLE, Set.of("T:SELECT", "T:INSERT", "T:UPDATE")),
                Map.entry(RECEIPT_TABLE, Set.of("T:SELECT", "T:INSERT")),
                Map.entry(ADMISSION_TABLE, Set.of("T:SELECT", "T:INSERT")),
                Map.entry(ATOMIC_RECEIPT_TABLE, Set.of("T:SELECT", "T:INSERT")),
                Map.entry(ATOMIC_ITEM_TABLE, Set.of("T:SELECT", "T:INSERT")),
                Map.entry(ATOMIC_EFFECT_TABLE, Set.of("T:SELECT", "T:INSERT")),
                Map.entry(ATOMIC_REJECTION_TABLE, Set.of("T:SELECT", "T:INSERT")),
                Map.entry(ATOMIC_BOOTSTRAP_TABLE, Set.of()),
                Map.entry(ALLOCATION_TABLE, Set.of("T:SELECT", "T:INSERT", "C:state:UPDATE",
                        "C:released_at:UPDATE", "C:release_reason:UPDATE")),
                Map.entry(TOMBSTONE_TABLE, Set.of("T:SELECT"))));
        allowedByTable.put(MANIFEST_TABLE, bootstrapTableGrants(phase(phases, MANIFEST_BOOTSTRAP_TABLE)));
        allowedByTable.put(PREVIEW_STATE_TABLE, bootstrapTableGrants(phase(phases, PREVIEW_BOOTSTRAP_TABLE)));
        allowedByTable.put(TARGET_PREVIEW_TABLE, bootstrapTableGrants(phase(phases, PREVIEW_BOOTSTRAP_TABLE)));
        allowedByTable.put(PREVIEW_INTEGRITY_TABLE, bootstrapTableGrants(phase(phases, PREVIEW_INTEGRITY_BOOTSTRAP_TABLE)));
        for (String table : ATOMIC_RUNTIME_TABLES)
            allowedByTable.put(table, bootstrapTableGrants(phase(phases, ATOMIC_BOOTSTRAP_TABLE)));
        if (isV18Installed(connection)) {
            allowedByTable.put(CAPACITY_READ_BOOTSTRAP_TABLE, Set.of());
            // The owner validates the durable PENDING/COMPLETE row. Restricted callers cannot
            // read it; on their live path, exact committed read grants are mandatory instead.
            boolean complete = liveCaller || "COMPLETE".equals(capacityReadBootstrapPhase(connection));
            allowedByTable.put(CAPACITY_MARKER_TABLE, complete ? Set.of("T:SELECT") : Set.of());
            allowedByTable.put(CAPACITY_INSTALLATION_TABLE, complete ? Set.of("T:SELECT") : Set.of());
            if (liveCaller) for (String role : runtimeRoles) {
                require(tableRolePrivileges(connection, CAPACITY_MARKER_TABLE, role)
                                .equals(Set.of("T:SELECT")),
                        "live runtime capacity marker read grant is absent");
                require(tableRolePrivileges(connection, CAPACITY_INSTALLATION_TABLE, role)
                                .equals(Set.of("T:SELECT")),
                        "live runtime capacity installation read grant is absent");
            }
        }
        if (BulkCapacityOccupancyCatalog.installed(connection)) {
            boolean complete = liveCaller || "COMPLETE".equals(BulkCapacityOccupancyCatalog.phase(connection));
            allowedByTable.put(BulkCapacityOccupancyCatalog.BOOTSTRAP, Set.of());
            for (String table : Set.of(BulkCapacityOccupancyCatalog.SLOT, BulkCapacityOccupancyCatalog.HISTORY)) {
                allowedByTable.put(table, complete ? Set.of("T:SELECT") : Set.of());
                if (liveCaller) for (String role : runtimeRoles)
                    require(tableRolePrivileges(connection, table, role).equals(Set.of("T:SELECT")), "V19 live runtime SELECT absent");
            }
        }
        try (var statement = connection.prepareStatement("""
                select c.relname, coalesce(r.rolname, 'PUBLIC'), acl.privilege_type,
                       acl.is_grantable, 'T'::text as grant_scope, null::text as column_name,
                       acl.grantee = c.relowner as is_owner
                from pg_class c join pg_namespace n on n.oid=c.relnamespace
                cross join lateral aclexplode(coalesce(c.relacl, acldefault('r', c.relowner))) acl
                left join pg_roles r on r.oid=acl.grantee
                where n.nspname=? and c.relname = any (?::text[])
                union all
                select c.relname, coalesce(r.rolname, 'PUBLIC'), acl.privilege_type,
                       acl.is_grantable, 'C'::text, a.attname,
                       acl.grantee = c.relowner
                from pg_class c join pg_namespace n on n.oid=c.relnamespace
                join pg_attribute a on a.attrelid=c.oid and a.attnum>0 and not a.attisdropped
                cross join lateral aclexplode(a.attacl) acl
                left join pg_roles r on r.oid=acl.grantee
                where n.nspname=? and c.relname = any (?::text[]) and a.attacl is not null
                """)) {
            statement.setString(1, SCHEMA);
            statement.setArray(2, connection.createArrayOf("text", allowedByTable.keySet().toArray()));
            statement.setString(3, SCHEMA);
            statement.setArray(4, connection.createArrayOf("text", allowedByTable.keySet().toArray()));
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (rows.getBoolean(7)) continue;
                    String table = rows.getString(1);
                    String role = rows.getString(2);
                    String permission = rows.getString(5).equals("T")
                            ? "T:" + rows.getString(3)
                            : "C:" + rows.getString(6) + ":" + rows.getString(3);
                    require(!"PUBLIC".equals(role) && !rows.getBoolean(4),
                            "PUBLIC or grant-option access is forbidden on governed table " + table);
                    if (role.equals("praxis_bulk_retention_owner")
                            || role.equals("praxis_bulk_retention_executor")
                            || role.equals("praxis_bulk_control_owner")
                            || role.equals(BulkCapacityOccupancyCatalog.OWNER)) continue;
                    require(runtimeRoles.contains(role), "unconfigured bulk table grantee: " + role);
                    require(allowedByTable.get(table).contains(permission),
                            "bulk runtime privilege exceeds its table allowlist: " + role + " " + table + " " + permission);
                }
            }
        }
    }

    private static void validateRuntimeRoleMemberships(Connection connection, Set<String> runtimeRoles)
            throws SQLException {
        validateConfiguredRoleMembershipClosure(connection, runtimeRoles, runtimeRoles,
                "runtime role membership introduces an unconfigured grantee");
    }

    private static void validateRetentionExecutorMemberships(Connection connection, Set<String> expectedMembers)
            throws SQLException {
        var actualMembers = new LinkedHashSet<String>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
                with recursive membership(roleid, member, admin_option) as (
                    select m.roleid, m.member, m.admin_option
                    from pg_auth_members m join pg_roles r on r.oid=m.roleid
                    where r.rolname='praxis_bulk_retention_executor'
                    union
                    select m.roleid, m.member, m.admin_option
                    from pg_auth_members m join membership parent on m.roleid=parent.member
                )
                select distinct child.rolname, bool_or(membership.admin_option)
                from membership join pg_roles child on child.oid=membership.member
                group by child.rolname
                """)) {
            while (rows.next()) {
                require(!rows.getBoolean(2), "retention executor memberships cannot have ADMIN OPTION");
                actualMembers.add(rows.getString(1));
            }
        }
        require(actualMembers.equals(expectedMembers), "retention executor members differ from explicit host configuration");
        validateConfiguredRoleMembershipClosure(connection, expectedMembers, expectedMembers,
                "retention executor membership introduces an unconfigured grantee");
    }

    private static void validateConfiguredRoleMembershipClosure(Connection connection, Set<String> roots,
            Set<String> allowedMembers, String message) throws SQLException {
        if (roots.isEmpty()) return;
        var actualMembers = new LinkedHashSet<String>();
        try (var statement = connection.prepareStatement("""
                with recursive membership(roleid, member, admin_option) as (
                    select m.roleid, m.member, m.admin_option from pg_auth_members m
                    where m.roleid = any (select oid from pg_roles where rolname = any (?::text[]))
                    union
                    select m.roleid, m.member, m.admin_option
                    from pg_auth_members m join membership parent on m.roleid=parent.member
                )
                select distinct child.rolname, bool_or(membership.admin_option)
                from membership join pg_roles child on child.oid=membership.member
                group by child.rolname
                """)) {
            statement.setArray(1, connection.createArrayOf("text", roots.toArray()));
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    require(!rows.getBoolean(2), "configured role memberships cannot have ADMIN OPTION");
                    actualMembers.add(rows.getString(1));
                }
            }
        }
        require(allowedMembers.containsAll(actualMembers), message);
    }

    private static Set<String> tableRolePrivileges(Connection connection, String table, String role)
            throws SQLException {
        var privileges = new LinkedHashSet<String>();
        try (var statement = connection.prepareStatement("""
                select 'T:' || acl.privilege_type, acl.is_grantable
                from pg_class c cross join lateral
                     aclexplode(coalesce(c.relacl, acldefault('r', c.relowner))) acl
                left join pg_roles r on r.oid=acl.grantee
                where c.oid=?::regclass and coalesce(r.rolname, 'PUBLIC')=?
                union all
                select 'C:' || a.attname || ':' || acl.privilege_type, acl.is_grantable
                from pg_class c join pg_attribute a on a.attrelid=c.oid
                cross join lateral aclexplode(a.attacl) acl
                left join pg_roles r on r.oid=acl.grantee
                where c.oid=?::regclass and a.attnum>0 and not a.attisdropped
                  and a.attacl is not null and coalesce(r.rolname, 'PUBLIC')=?
                """)) {
            statement.setString(1, SCHEMA + "." + table);
            statement.setString(2, role);
            statement.setString(3, SCHEMA + "." + table);
            statement.setString(4, role);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    require(!rows.getBoolean(2), "grant options are forbidden on governed lifecycle tables");
                    privileges.add(rows.getString(1));
                }
            }
        }
        return privileges;
    }

    /** Reject contradictory or noncontiguous durable evidence, including cross-table duplicates. */
    private static void validateAdmissionRows(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("""
                with evidence as (
                    select execution_id, unit_ordinal, target_digest, attempt_id, owner_epoch, false as admission
                    from praxis_bulk.praxis_bulk_item_receipt
                    union all
                    select execution_id, unit_ordinal, target_digest, attempt_id, owner_epoch, true as admission
                    from praxis_bulk.praxis_bulk_admission
                ), totals as (
                    select execution_id, count(*) as evidence_count,
                           count(*) filter (where admission) as admission_count,
                           count(distinct unit_ordinal) as ordinal_count,
                           count(distinct target_digest) as target_count,
                           count(distinct attempt_id) as attempt_count,
                           min(unit_ordinal) as first_ordinal, max(unit_ordinal) as last_ordinal,
                           max(owner_epoch) as latest_epoch
                    from evidence group by execution_id
                )
                select count(*)
                from praxis_bulk.praxis_bulk_execution e left join totals t using (execution_id)
                where e.atomicity='PER_ITEM' and (
                      coalesce(t.evidence_count, 0) <> coalesce(t.ordinal_count, 0)
                   or coalesce(t.evidence_count, 0) <> coalesce(t.target_count, 0)
                   or coalesce(t.evidence_count, 0) <> coalesce(t.attempt_count, 0)
                   or (t.evidence_count > 0 and (t.first_ordinal <> 0 or t.last_ordinal <> t.evidence_count - 1))
                   or t.latest_epoch > e.owner_epoch
                   or t.last_ordinal >= e.target_count
                   or (e.status in ('RUNNING', 'UNIT_IN_FLIGHT', 'STOPPED', 'COMPLETED', 'COMPLETED_WITH_ERRORS')
                       and coalesce(t.evidence_count, 0) <> e.next_ordinal)
                   or (e.status in ('COMPLETED', 'COMPLETED_WITH_ERRORS')
                       and coalesce(t.evidence_count, 0) <> e.target_count)
                   or (e.status = 'COMPLETED' and coalesce(t.admission_count, 0) <> 0)
                   or (e.status = 'COMPLETED_WITH_ERRORS' and coalesce(t.admission_count, 0) = 0)
                   or (e.status = 'UNIT_COMMITTED_PENDING_ACK'
                       and coalesce(t.evidence_count, 0) <> e.next_ordinal + 1)
                   or (e.status = 'UNIT_COMMITTED_PENDING_ACK' and not exists (
                       select 1 from praxis_bulk.praxis_bulk_item_receipt r
                       where r.execution_id = e.execution_id and r.unit_ordinal = e.next_ordinal
                         and r.attempt_id = e.active_attempt_id
                         and r.target_digest = e.active_target_digest
                         and r.owner_epoch = e.active_attempt_epoch))
                   or (e.status = 'UNIT_COMMITTED_PENDING_ACK' and exists (
                       select 1 from praxis_bulk.praxis_bulk_admission a
                       where a.execution_id = e.execution_id and a.unit_ordinal = e.next_ordinal))
                   or (e.status = 'RECONCILIATION_REQUIRED'
                       and coalesce(t.evidence_count, 0) not between e.next_ordinal and e.next_ordinal + 1))
                """)) {
            try (var rows = statement.executeQuery()) {
                require(rows.next() && rows.getLong(1) == 0 && !rows.next(),
                        "admission and receipt evidence must form one unambiguous contiguous prefix");
            }
        }
    }

    /** Attest each V16 all-or-nothing row against its immutable proposal and manifest. */
    private static void validateAtomicRows(Connection connection) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
                select e.execution_id,e.proposal_id,e.atomicity,e.protocol_version,e.status,
                       e.next_ordinal,e.target_count,e.active_attempt_id,e.active_set_digest,
                       e.owner_epoch,e.active_attempt_epoch,e.active_unit_deadline_at,
                       e.terminal_reason_code,p.atomicity,p.protocol_version,p.payload,p.fingerprint,
                       p.created_at,p.expires_at,v.payload,v.evaluation_fingerprint,
                       h.attempt_id,h.owner_epoch,h.set_digest,h.target_count,h.confirmed_at,
                       h.unit_deadline_at,j.attempt_id,j.set_digest,j.reason_code,
                       h.effect_count,h.effect_digest
                  from praxis_bulk.praxis_bulk_execution e
                  join praxis_bulk.praxis_bulk_proposal p on p.proposal_id=e.proposal_id
                  join praxis_bulk.praxis_bulk_evaluation v on v.proposal_id=e.proposal_id
                  left join praxis_bulk.praxis_bulk_atomic_receipt h on h.execution_id=e.execution_id
                  left join praxis_bulk.praxis_bulk_atomic_rejection j on j.execution_id=e.execution_id
                 where e.atomicity='ATOMIC' or h.execution_id is not null or j.execution_id is not null
                """)) {
            while (rows.next()) {
                try {
                    require("ATOMIC".equals(rows.getString(3)) && rows.getShort(4) == 2
                                    && "ATOMIC".equals(rows.getString(14)) && rows.getShort(15) == 2,
                            "atomic storage binding differs");
                    UUID executionId = rows.getObject(1, UUID.class);
                    UUID proposalId = rows.getObject(2, UUID.class);
                    var intent = BulkSnapshotStorageCodec.decode(rows.getBytes(16), rows.getString(17));
                    var proposal = new BulkStoredProposal(proposalId,
                            rows.getObject(18, OffsetDateTime.class).toInstant(),
                            rows.getObject(19, OffsetDateTime.class).toInstant(), intent);
                    var evaluated = BulkEvaluationStorageCodec.decode(proposal, rows.getBytes(20),
                            rows.getString(21));
                    require(evaluated.targets().size() == rows.getInt(7)
                                    && evaluated.targets().size() >= 1
                                    && evaluated.targets().size() <= 50,
                            "atomic target count differs");
                    var digests = new ArrayList<String>(evaluated.targets().size());
                    for (int ordinal = 0; ordinal < evaluated.targets().size(); ordinal++)
                        digests.add(evidenceTargetDigest(evaluated, ordinal));
                    String expectedSet = BulkTargetDigest.setOf(evaluated.fingerprint(), digests);
                    String status = rows.getString(5);
                    boolean hasHeader = rows.getObject(22) != null;
                    boolean hasRejection = rows.getObject(28) != null;
                    require(rows.getInt(6) == ("COMPLETED".equals(status) ? rows.getInt(7) : 0),
                            "atomic visible watermark differs");
                    if (hasHeader) {
                        require(!hasRejection && expectedSet.equals(rows.getString(24))
                                        && rows.getInt(25) == rows.getInt(7)
                                        && rows.getLong(23) <= rows.getLong(10)
                                        && rows.getObject(26, OffsetDateTime.class)
                                            .isBefore(rows.getObject(27, OffsetDateTime.class))
                                        && atomicEffectsMatch(connection, executionId,
                                                rows.getInt(31), rows.getString(32)),
                                "atomic committed header differs");
                    }
                    if (hasRejection) {
                        String reason = rows.getString(30);
                        require(!hasHeader && expectedSet.equals(rows.getString(29))
                                        && "STOPPED".equals(status) && reason != null
                                        && ("UNIT_ROLLED_BACK".equals(rows.getString(13))
                                            && !"DEADLINE_EXCEEDED".equals(reason)
                                            || "DEADLINE_EXCEEDED".equals(rows.getString(13))
                                            && "DEADLINE_EXCEEDED".equals(reason)),
                                "atomic rejection differs");
                    } else if ("STOPPED".equals(status)) {
                        require(!"UNIT_ROLLED_BACK".equals(rows.getString(13)),
                                "atomic rollback lacks typed rejection");
                    }
                    if ("COMPLETED".equals(status) || "UNIT_COMMITTED_PENDING_ACK".equals(status))
                        require(hasHeader, "atomic acknowledged state lacks header");
                    if ("RUNNING".equals(status) || "UNIT_IN_FLIGHT".equals(status)
                            || "STOPPED".equals(status))
                        require(!hasHeader, "atomic uncommitted state has header");
                    if ("UNIT_IN_FLIGHT".equals(status) || "UNIT_COMMITTED_PENDING_ACK".equals(status))
                        require(expectedSet.equals(rows.getString(9)) && rows.getObject(8) != null
                                        && rows.getObject(12, OffsetDateTime.class) != null,
                                "atomic active set differs");
                    int complete = hasHeader ? rows.getInt(7) : 0;
                    try (var evidence = connection.prepareStatement("""
                            select praxis_bulk.atomic_evidence_complete(?,?)
                            """)) {
                        evidence.setObject(1, executionId); evidence.setInt(2, complete);
                        try (var checked = evidence.executeQuery()) {
                            require(checked.next() && checked.getBoolean(1) && !checked.next(),
                                    "atomic set evidence differs");
                        }
                    }
                } catch (RuntimeException invalid) {
                    throw new IllegalStateException("atomic execution differs from protected set", invalid);
                }
            }
        }
    }

    private static boolean atomicEffectsMatch(Connection connection, UUID executionId,
            int expectedCount, String expectedDigest) throws SQLException {
        if (expectedCount < 0 || expectedCount > 400 || expectedDigest == null) return false;
        var effects = new ArrayList<BulkTargetDigest.EffectReference>();
        try (var statement = connection.prepareStatement("""
                select unit_ordinal,effect_ref from praxis_bulk.praxis_bulk_atomic_effect_ref
                 where execution_id=? order by unit_ordinal,effect_ref collate "C"
                """)) {
            statement.setObject(1, executionId);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (effects.size() >= 400) return false;
                    effects.add(new BulkTargetDigest.EffectReference(rows.getInt(1), rows.getString(2)));
                }
            }
        }
        return effects.size() == expectedCount
                && expectedDigest.equals(BulkTargetDigest.effectsOf(effects));
    }

    /** Decode the protected evaluation once per execution and bind each durable outcome to its ordered target. */
    private static void validateEvidenceBinding(Connection connection) throws SQLException {
        var evaluations = new LinkedHashMap<UUID, BulkEvaluationSnapshot>();
        try (var statement = connection.prepareStatement("""
                with evidence as (
                    select execution_id, unit_ordinal, target_digest, expected_version
                    from praxis_bulk.praxis_bulk_item_receipt
                    union all
                    select execution_id, unit_ordinal, target_digest, expected_version
                    from praxis_bulk.praxis_bulk_admission
                )
                select d.execution_id, d.unit_ordinal, d.target_digest, d.expected_version,
                       x.proposal_id, x.namespace_id, x.subject_id, x.resource_key, x.operation_id,
                       x.input_fingerprint, x.evaluation_fingerprint, x.target_count,
                       p.created_at, p.expires_at, p.fingerprint, p.payload,
                       v.input_fingerprint, v.evaluation_fingerprint, v.payload,
                       p.control_generation,p.control_descriptor_fingerprint,p.control_structural_revision
                from evidence d
                join praxis_bulk.praxis_bulk_execution x using (execution_id)
                join praxis_bulk.praxis_bulk_proposal p on p.proposal_id=x.proposal_id
                join praxis_bulk.praxis_bulk_evaluation v on v.proposal_id=x.proposal_id
                """)) {
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID executionId = rows.getObject(1, UUID.class);
                    try {
                        BulkEvaluationSnapshot evaluation = evaluations.get(executionId);
                        if (evaluation == null) {
                            require(rows.getString(10).equals(rows.getString(15))
                                            && rows.getString(10).equals(rows.getString(17))
                                            && rows.getString(11).equals(rows.getString(18)),
                                    "durable evidence fingerprint binding differs");
                            var intent = BulkSnapshotStorageCodec.decode(rows.getBytes(16), rows.getString(15));
                            var scope = intent.context();
                            require(scope.namespaceId().equals(rows.getString(6))
                                            && scope.subjectId().equals(rows.getString(7))
                                            && scope.resourceKey().equals(rows.getString(8))
                                            && scope.operationRef().operationId().equals(rows.getString(9)),
                                    "durable evidence scope differs");
                            var proposal = BulkStoredProposal.decoded(rows.getObject(5, UUID.class),
                                    rows.getObject(13, OffsetDateTime.class).toInstant(),
                                    rows.getObject(14, OffsetDateTime.class).toInstant(), intent,
                                    JdbcBulkProposalStore.expectation(rows.getObject(20, Long.class),rows.getString(21),rows.getString(22)));
                            evaluation = BulkEvaluationStorageCodec.decode(proposal, rows.getBytes(19), rows.getString(11));
                            require(evaluation.targets().size() == rows.getInt(12),
                                    "durable evidence target count differs");
                            evaluations.put(executionId, evaluation);
                        }
                        int ordinal = rows.getInt(2);
                        require(ordinal >= 0 && ordinal < evaluation.targets().size(),
                                "durable evidence ordinal differs");
                        var target = evaluation.targets().get(ordinal).target();
                        require(target.expectedVersion().equals(rows.getString(4))
                                        && evidenceTargetDigest(evaluation, ordinal).equals(rows.getString(3)),
                                "durable evidence target or version differs");
                    } catch (RuntimeException corrupt) {
                        throw new IllegalStateException("durable evidence does not match protected evaluation", corrupt);
                    }
                }
            }
        }
    }

    static String evidenceTargetDigest(BulkEvaluationSnapshot evaluation, int ordinal) {
        var target = evaluation.targets().get(ordinal).target();
        return BulkTargetDigest.of(evaluation.fingerprint(), ordinal, target.id(), target.expectedVersion());
    }

    private static void validateDurableColumns(Connection connection, String table,
            Map<String, String> expected) throws SQLException {
        if (BulkCapacityOccupancyCatalog.installed(connection) && EXECUTION_TABLE.equals(table)) {
            expected = new LinkedHashMap<>(expected);
            expected.put("execution_mode", "text|true");
            expected.put("queue_token_id", "uuid|false");
            expected.put("active_token_id", "uuid|false");
        }
        var actual = new LinkedHashMap<String, String>();
        try (var statement = connection.prepareStatement("""
                select a.attname, format_type(a.atttypid, a.atttypmod), a.attnotnull,
                       a.atthasdef, a.attidentity, a.attgenerated, r.relkind, r.relpersistence, r.relrowsecurity
                from pg_attribute a join pg_class r on r.oid = a.attrelid
                where a.attrelid = ?::regclass and a.attnum > 0 and not a.attisdropped
                """)) {
            statement.setString(1, SCHEMA + "." + table);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    require(!rows.getBoolean(4) && rows.getString(5).isEmpty() && rows.getString(6).isEmpty()
                                    && "r".equals(rows.getString(7)) && "p".equals(rows.getString(8))
                                    && !rows.getBoolean(9),
                            "durable storage defaults, generation, persistence or row security differ: " + table);
                    actual.put(rows.getString(1), rows.getString(2) + "|" + rows.getBoolean(3));
                }
            }
        }
        require(expected.equals(actual), "durable storage columns differ: " + table);
    }

    private static void validateDurableConstraints(Connection connection, String table, boolean onlyNewUnique,
            Map<String, String> expected) throws SQLException {
        // V19 changes the complete execution/allocation constraint inventories only.
        // Evaluation's unique-only comparison and proposal's dedicated validators stay separate.
        if (!onlyNewUnique && (EXECUTION_TABLE.equals(table) || ALLOCATION_TABLE.equals(table))
                && BulkCapacityOccupancyCatalog.installed(connection)) {
            expected = BulkCapacityOccupancyCatalog.extendConstraints(table, expected);
        }
        var actual = new LinkedHashMap<String, String>();
        try (var statement = connection.prepareStatement("""
                select c.conname, pg_get_constraintdef(c.oid), c.convalidated, c.condeferrable,
                       c.condeferred, i.indisvalid, i.indisready, i.indislive, i.indimmediate
                from pg_constraint c left join pg_index i on i.indexrelid = c.conindid
                where c.conrelid = ?::regclass and (not ? or c.contype = 'u')
                """)) {
            statement.setString(1, SCHEMA + "." + table);
            statement.setBoolean(2, onlyNewUnique);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    require(rows.getBoolean(3) && !rows.getBoolean(4) && !rows.getBoolean(5),
                            "durable constraint must be immediate and validated: " + rows.getString(1));
                    if (rows.getObject(6) != null) {
                        require(rows.getBoolean(6) && rows.getBoolean(7) && rows.getBoolean(8) && rows.getBoolean(9),
                                "durable constraint index is invalid: " + rows.getString(1));
                    }
                    actual.put(rows.getString(1), normalizeExpression(rows.getString(2)));
                }
            }
        }
        var normalized = new LinkedHashMap<String, String>();
        expected.forEach((name, definition) -> normalized.put(name, normalizeExpression(definition)));
        require(normalized.equals(actual), "durable storage constraints differ: " + table);
    }

    private static void validateDurableTrigger(Connection connection, String table, String trigger,
            String function, String expectedDefinition, String expectedBody) throws SQLException {
        try (var statement = connection.prepareStatement("""
                select t.tgenabled, pg_get_triggerdef(t.oid), p.prosrc, l.lanname,
                       p.prorettype::regtype::text, p.prosecdef, p.proname,
                       pn.nspname, p.pronargs, p.proconfig,
                       p.proconfig = array['search_path=pg_catalog, pg_temp']::text[]
                from pg_trigger t join pg_class r on r.oid = t.tgrelid
                join pg_proc p on p.oid = t.tgfoid join pg_namespace pn on pn.oid = p.pronamespace
                join pg_language l on l.oid = p.prolang
                where t.tgrelid = ?::regclass and t.tgname = ? and not t.tgisinternal
                """)) {
            statement.setString(1, SCHEMA + "." + table);
            statement.setString(2, trigger);
            try (var rows = statement.executeQuery()) {
                require(rows.next() && "O".equals(rows.getString(1))
                                && normalizeExpression(expectedDefinition).equals(normalizeExpression(rows.getString(2)))
                                && normalizeExpression(BulkCapacityOccupancyCatalog.installed(connection) && function.equals(BINDING_FUNCTION)
                                        ? BulkCapacityOccupancyCatalog.functionBody(function) : expectedBody)
                                        .equals(normalizeExpression(rows.getString(3)))
                                && "plpgsql".equals(rows.getString(4)) && "trigger".equals(rows.getString(5))
                                && !rows.getBoolean(6) && function.equals(rows.getString(7))
                                && SCHEMA.equals(rows.getString(8)) && rows.getInt(9) == 0
                                && (function.equals(RECEIPT_FUNCTION) || function.equals(ADMISSION_FUNCTION)
                                    ? rows.getBoolean(11) : rows.getObject(10) == null)
                                && !rows.next(),
                        "durable storage trigger or function differs: " + trigger);
            }
        }
    }

    private static void validateDescriptorInsertFence(Connection connection, String table, String trigger,
            String expectedDefinition, String normalizedExpectedBody) throws SQLException {
        try (var statement = connection.prepareStatement("""
                select t.tgenabled, pg_get_triggerdef(t.oid), p.prosrc, p.prosecdef,
                       p.proconfig = array['search_path=pg_catalog, pg_temp']::text[], owner.rolname,
                       p.proname, p.pronargs, l.lanname, p.prorettype::regtype::text
                from pg_trigger t join pg_class r on r.oid=t.tgrelid
                join pg_namespace n on n.oid=r.relnamespace
                join pg_proc p on p.oid=t.tgfoid join pg_roles owner on owner.oid=p.proowner
                join pg_language l on l.oid=p.prolang
                where n.nspname=? and r.relname=? and t.tgname=? and not t.tgisinternal
                """)) {
            statement.setString(1, SCHEMA);
            statement.setString(2, table);
            statement.setString(3, trigger);
            try (var rows = statement.executeQuery()) {
                require(rows.next() && "O".equals(rows.getString(1))
                                && normalizeExpression(expectedDefinition).equals(normalizeExpression(rows.getString(2)))
                                && normalizedExpectedBody.equals(normalizeExpression(rows.getString(3)))
                                && rows.getBoolean(4) && rows.getBoolean(5)
                                && "praxis_bulk_control_owner".equals(rows.getString(6))
                                && INSERT_FENCE_FUNCTION.equals(rows.getString(7)) && rows.getInt(8) == 0
                                && "plpgsql".equals(rows.getString(9)) && "trigger".equals(rows.getString(10))
                                && !rows.next(), "descriptor insert fence differs: " + trigger);
            }
        }
    }

    private static String normalizeExpression(String expression) {
        StringBuilder normalized = new StringBuilder(expression.length());
        boolean quoted = false;
        for (int index = 0; index < expression.length(); index++) {
            char current = expression.charAt(index);
            if (current == 39) {
                normalized.append(current);
                if (quoted && index + 1 < expression.length() && expression.charAt(index + 1) == 39) {
                    normalized.append(current);
                    index++;
                } else {
                    quoted = !quoted;
                }
            } else if (!quoted && Character.isWhitespace(current)) {
                continue;
            } else {
                normalized.append(quoted ? current : Character.toLowerCase(current));
            }
        }
        return normalized.toString();
    }

    private static void assertPostgreSql(Connection connection) throws SQLException {
        require("PostgreSQL".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName()),
                "bulk storage migration requires PostgreSQL");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static void setCatalogSearchPath(Connection connection, String searchPath) throws SQLException {
        setCatalogSearchPath(connection, searchPath, false);
    }

    private static void setCatalogSearchPath(Connection connection, String searchPath, boolean local) throws SQLException {
        try (var statement = connection.prepareStatement("select set_config('search_path', ?, ?)")) {
            statement.setString(1, searchPath);
            statement.setBoolean(2, local);
            statement.execute();
        }
    }

    private record ColumnDefinition(String dataType, boolean nullable, String defaultValue, String generated,
                                    Integer dateTimePrecision) { }
    private record ConstraintDefinition(boolean validated, String expression) { }
    private record TriggerSpec(String function, String definition) { }
    private record ScopeDigests(String subjectDigest, String authorizationDigest) { }
    private record ProposalRow(UUID id, String namespaceId, String subjectId, String resourceKey,
                               String operationId, Instant createdAt) { }
    private record ExecutionRow(UUID id, UUID proposalId, String namespaceId, String subjectId,
                                String resourceKey, String operationId, String status, Instant createdAt,
                                Instant terminalAt, int nextOrdinal, int targetCount, long ownerEpoch,
                                UUID activeAttemptId, String terminalReason, long admissionCount, String executionMode) { }
    private record AllocationRow(String kind, UUID proposalId, UUID executionId, String namespaceId,
                                 String deploymentId, String subjectDigest, String authorizationDigest,
                                 String state, Instant createdAt, Instant releasedAt, String releaseReason) { }
}
