package org.praxisplatform.uischema.bulk;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Explicit PostgreSQL deployment migration for protected bulk-proposal input.
 *
 * <p>This class is intentionally not an auto-configuration component. A host calls it during an
 * explicit deployment step with the same operational datasource used by the protected store. It
 * owns only the {@value #SCHEMA} schema and never baselines, cleans, grants, evaluates, admits or
 * executes proposals.</p>
 */
public final class BulkExecutionMigrator {
    static final String SCHEMA = "praxis_bulk";
    static final String HISTORY_TABLE = "praxis_bulk_schema_history";
    private static final String PROPOSAL_TABLE = "praxis_bulk_proposal";
    private static final String EVALUATION_TABLE = "praxis_bulk_evaluation";
    private static final String REJECTION_FUNCTION = "reject_praxis_bulk_proposal_update";
    private static final String REJECTION_TRIGGER = "praxis_bulk_proposal_reject_update";
    private static final String EVALUATION_REJECTION_FUNCTION = "reject_praxis_bulk_evaluation_update";
    private static final String EVALUATION_REJECTION_TRIGGER = "praxis_bulk_evaluation_reject_update";

    private static final String EXECUTION_TABLE = "praxis_bulk_execution";
    private static final String RECEIPT_TABLE = "praxis_bulk_item_receipt";
    private static final String ADMISSION_TABLE = "praxis_bulk_admission";
    private static final String BINDING_FUNCTION = "protect_praxis_bulk_execution_binding";
    private static final String BINDING_TRIGGER = "praxis_bulk_execution_protect_binding";
    private static final String TERMINAL_REASON_FUNCTION = "protect_praxis_bulk_terminal_reason";
    private static final String TERMINAL_REASON_TRIGGER = "praxis_bulk_execution_protect_terminal_reason";
    private static final String RECEIPT_FUNCTION = "reject_praxis_bulk_item_receipt_mutation";
    private static final String RECEIPT_TRIGGER = "praxis_bulk_item_receipt_reject_mutation";
    private static final String ADMISSION_FUNCTION = "reject_praxis_bulk_admission_mutation";
    private static final String ADMISSION_TRIGGER = "praxis_bulk_admission_reject_mutation";
    private static final String NAMESPACE_BINDING_TABLE = "praxis_bulk_namespace_binding";
    private static final String OPERATION_CONTROL_TABLE = "praxis_bulk_operation_control";
    private static final String DEPLOYMENT_BUCKET_TABLE = "praxis_bulk_deployment_bucket";
    private static final String SUBJECT_BUCKET_TABLE = "praxis_bulk_subject_bucket";
    private static final String ALLOCATION_TABLE = "praxis_bulk_allocation";
    private static final String TOMBSTONE_TABLE = "praxis_bulk_tombstone";
    private static final Set<String> V5_TABLES = Set.of(HISTORY_TABLE, PROPOSAL_TABLE, EVALUATION_TABLE,
            EXECUTION_TABLE, RECEIPT_TABLE, ADMISSION_TABLE, NAMESPACE_BINDING_TABLE,
            OPERATION_CONTROL_TABLE, DEPLOYMENT_BUCKET_TABLE, SUBJECT_BUCKET_TABLE,
            ALLOCATION_TABLE, TOMBSTONE_TABLE);
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

    private BulkExecutionMigrator() { }

    /**
     * Applies pending protected-storage migrations and validates the resulting PostgreSQL catalog.
     * The call must happen outside a Spring transaction because it owns deployment DDL, not a
     * proposal write transaction.
     *
     * @return the number of migrations executed by Flyway
     */
    public static int migrate(DataSource dataSource, Map<String, String> namespaceToDeploymentId) {
        DataSource operationalDataSource = Objects.requireNonNull(dataSource, "dataSource");
        return migrate(operationalDataSource, namespaceToDeploymentId,
                BulkExecutionRoleConfiguration.none(currentDatabaseRole(operationalDataSource)));
    }

    /**
     * Applies migrations and validates every bulk ACL against the explicitly configured host
     * runtime and retention-operator PostgreSQL roles.
     */
    public static int migrate(DataSource dataSource, Map<String, String> namespaceToDeploymentId,
            BulkExecutionRoleConfiguration roles) {
        requireOutsideSpringTransaction();
        DataSource operationalDataSource = Objects.requireNonNull(dataSource, "dataSource");
        Map<String, String> deployments = canonicalDeploymentMap(namespaceToDeploymentId);
        BulkExecutionRoleConfiguration roleConfiguration = Objects.requireNonNull(roles, "roles");
        assertKnownDedicatedSchema(operationalDataSource);
        int migrationsExecuted = flyway(operationalDataSource).migrate().migrationsExecuted;
        initializeGovernedLifecycle(operationalDataSource, deployments);
        validate(operationalDataSource, roleConfiguration);
        return migrationsExecuted;
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
    private static void initializeGovernedLifecycle(DataSource dataSource, Map<String, String> deployments) {
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
                validateAdmissionRows(connection);
                validateEvidenceBinding(connection);
                bootstrapLifecycle(connection, deployments);
                validateLifecycleRows(connection);
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

    private static void bootstrapLifecycle(Connection connection, Map<String, String> deployments) throws SQLException {
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

        for (String deployment : deployments.values().stream().distinct().sorted().toList()) {
            try (var statement = connection.prepareStatement("""
                    insert into praxis_bulk.praxis_bulk_deployment_bucket(deployment_id)
                    values (?) on conflict (deployment_id) do nothing
                    """)) {
                statement.setString(1, deployment);
                statement.executeUpdate();
            }
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

        // A bootstrap retry may run while already-published operations are active.
        // Lock controls first, then every deployment bucket, before reading history.
        try (var statement = connection.createStatement()) {
            statement.executeQuery("""
                    select namespace_id from praxis_bulk.praxis_bulk_namespace_binding
                    order by namespace_id for share
                    """).close();
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
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
                select execution_id, proposal_id, namespace_id, subject_id, resource_key, operation_id,
                       status, created_at, terminal_at, next_ordinal, target_count, owner_epoch,
                       active_attempt_id, terminal_reason_code,
                       (select count(*) from praxis_bulk.praxis_bulk_admission a
                        where a.execution_id = e.execution_id) as admission_count
                from praxis_bulk.praxis_bulk_execution e order by execution_id
                """)) {
            while (rows.next()) {
                OffsetDateTime terminal = rows.getObject(9, OffsetDateTime.class);
                var execution = new ExecutionRow(rows.getObject(1, UUID.class), rows.getObject(2, UUID.class),
                        rows.getString(3), rows.getString(4), rows.getString(5), rows.getString(6),
                        rows.getString(7), rows.getObject(8, OffsetDateTime.class).toInstant(),
                        terminal == null ? null : terminal.toInstant(), rows.getInt(10), rows.getInt(11),
                        rows.getLong(12), rows.getObject(13, UUID.class), rows.getString(14), rows.getLong(15));
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
        boolean terminal = switch (execution.status()) {
            case "COMPLETED", "COMPLETED_WITH_ERRORS", "STOPPED" -> true;
            case "RUNNING", "UNIT_IN_FLIGHT", "UNIT_COMMITTED_PENDING_ACK", "RECONCILIATION_REQUIRED" -> false;
            default -> throw new IllegalStateException("Unknown bulk execution status");
        };
        require(!terminal || (execution.terminalAt() != null && execution.activeAttemptId() == null
                        && execution.ownerEpoch() >= 1
                        && (execution.status().equals("STOPPED") == (execution.terminalReason() != null))
                        && (execution.status().equals("COMPLETED") == (execution.admissionCount() == 0)
                            || execution.status().equals("STOPPED"))),
                "Bulk terminal execution cannot be classified for quota release");
        return new AllocationRow("EXECUTION_ACTIVE", null, execution.id(), execution.namespaceId(), deployment,
                scope.subjectDigest(), scope.authorizationDigest(), terminal ? "RELEASED" : "ACTIVE",
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
        Map<String, String> bindings = readNamespaceBindings(connection);
        var proposals = readProposals(connection);
        var executions = readExecutions(connection);
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
        require(observed.keySet().equals(expected.keySet()),
                "Bulk allocations do not cover exactly the retained proposals and executions");
        for (var entry : expected.entrySet()) {
            AllocationRow actual = observed.get(entry.getKey());
            AllocationRow required = entry.getValue();
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
            require(rows.next() && rows.getLong(1) == 0 && !rows.next(),
                    "Historical bulk operation lacks deny-only or validated control row");
        }
        requireQuotaWithinLimits(connection);
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
        requireOutsideSpringTransaction();
        DataSource operationalDataSource = Objects.requireNonNull(dataSource, "dataSource");
        BulkExecutionRoleConfiguration roleConfiguration = Objects.requireNonNull(roles, "roles");
        assertKnownDedicatedSchema(operationalDataSource);
        flyway(operationalDataSource).validate();
        try (Connection connection = operationalDataSource.getConnection()) {
            String previousSearchPath;
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("select current_setting('search_path')")) {
                require(rows.next(), "Unable to read current PostgreSQL search_path");
                previousSearchPath = rows.getString(1);
            }
            try {
                setCatalogSearchPath(connection, "pg_catalog");
                assertPostgreSql(connection);
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
                validateGovernedLifecycleCatalog(connection, roleConfiguration);
                validateAdmissionRows(connection);
                validateEvidenceBinding(connection);
                validateLifecycleRows(connection);
                validateOwnedSchema(connection);
            } finally {
                setCatalogSearchPath(connection, previousSearchPath);
            }
        } catch (SQLException error) {
            throw new IllegalStateException("Unable to validate protected bulk proposal storage", error);
        }
    }

    private static Flyway flyway(DataSource dataSource) {
        return Flyway.configure()
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

    private static void requireOutsideSpringTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Bulk storage migration must not run inside an active Spring transaction");
        }
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
                              'praxis_bulk_operation_control', 'praxis_bulk_deployment_bucket',
                              'praxis_bulk_subject_bucket', 'praxis_bulk_allocation',
                              'praxis_bulk_tombstone'))
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
                    || !allowedRelations().containsAll(relations)
                    || !allowedFunctions().containsAll(functions)
                    || !types.isEmpty()
                    || !orphanIndexes.isEmpty()
                    || !rules.isEmpty() || !policies.isEmpty()
                    || !allowedTriggers().containsAll(triggers)) {
                throw new IllegalStateException("Refusing an unknown nonempty praxis_bulk schema: relations="
                        + relations + ", functions=" + functions + ", types=" + types + ", indexes="
                        + orphanIndexes + ", triggers=" + triggers + ", rules=" + rules + ", policies=" + policies);
            }
        } catch (SQLException error) {
            throw new IllegalStateException("Unable to inspect dedicated bulk storage schema", error);
        }
    }

    private static Set<String> allowedRelations() {
        return Set.of(HISTORY_TABLE, PROPOSAL_TABLE, EVALUATION_TABLE, EXECUTION_TABLE,
                RECEIPT_TABLE, ADMISSION_TABLE, NAMESPACE_BINDING_TABLE, OPERATION_CONTROL_TABLE,
                DEPLOYMENT_BUCKET_TABLE, SUBJECT_BUCKET_TABLE, ALLOCATION_TABLE, TOMBSTONE_TABLE);
    }

    private static Set<String> allowedFunctions() {
        var names = new LinkedHashSet<>(V5_FUNCTIONS);
        names.addAll(Set.of(REJECTION_FUNCTION + "()", EVALUATION_REJECTION_FUNCTION + "()",
                BINDING_FUNCTION + "()", TERMINAL_REASON_FUNCTION + "()",
                RECEIPT_FUNCTION + "()", ADMISSION_FUNCTION + "()"));
        return names;
    }

    private static Set<String> allowedTriggers() {
        var names = new LinkedHashSet<>(V5_TRIGGERS);
        names.addAll(Set.of(PROPOSAL_TABLE + "." + REJECTION_TRIGGER,
                EVALUATION_TABLE + "." + EVALUATION_REJECTION_TRIGGER,
                EXECUTION_TABLE + "." + BINDING_TRIGGER,
                RECEIPT_TABLE + "." + RECEIPT_TRIGGER,
                EXECUTION_TABLE + "." + TERMINAL_REASON_TRIGGER,
                ADMISSION_TABLE + "." + ADMISSION_TRIGGER));
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
                  and not coalesce((index_class.relname = ? and table_class.relname = ?
                    and not i.indisunique and i.indisvalid and i.indisready and i.indislive
                    and i.indpred is null and i.indexprs is null and i.indnatts = 1 and i.indnkeyatts = 1
                    and am.amname = 'btree' and key_attribute.attname = 'success'
                    and i.indoption[0] = 0 and i.indcollation[0] = key_attribute.attcollation
                    and opclass.opcdefault), false)
                """)) {
            statement.setString(1, SCHEMA);
            statement.setString(2, "praxis_bulk_schema_history_s_idx");
            statement.setString(3, HISTORY_TABLE);
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
                              'praxis_bulk_operation_control', 'praxis_bulk_deployment_bucket',
                              'praxis_bulk_subject_bucket', 'praxis_bulk_allocation',
                              'praxis_bulk_tombstone'))
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
        require(relations.equals(allowedRelations())
                        && functions.equals(allowedFunctions())
                        && types.isEmpty()
                        && orphanIndexes.isEmpty()
                        && rules.isEmpty() && policies.isEmpty()
                        && triggers.equals(allowedTriggers()),
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
                Map.entry("payload", new ColumnDefinition("bytea", false, null, "NEVER", null)));
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
                require(result.next() && "proposal_id,fingerprint".equals(result.getString(1))
                                && !result.getBoolean(2) && !result.getBoolean(3) && result.getBoolean(4) && !result.next(),
                        "proposal_id and fingerprint must have the only immediate validated unique key");
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
                Map.entry("praxis_bulk_proposal_payload_length_check",
                        "((octet_length(payload)>=1)and(octet_length(payload)<=8388608))"));
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
                    "proposal table check constraint is invalid: " + check.getKey());
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
                Map.entry("created_at", "timestamp(6) with time zone|true"),
                Map.entry("updated_at", "timestamp(6) with time zone|true"),
                Map.entry("terminal_at", "timestamp(6) with time zone|false"),
                Map.entry("terminal_reason_code", "text|false")));
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
                Map.entry("praxis_bulk_execution_attempt_shape_check", "CHECK ((((active_attempt_id IS NULL) = (active_attempt_ordinal IS NULL)) AND ((active_attempt_id IS NULL) = (active_target_digest IS NULL)) AND ((active_attempt_id IS NULL) = (active_attempt_epoch IS NULL)) AND ((active_attempt_id IS NULL) OR ((active_attempt_ordinal = next_ordinal) AND ((active_attempt_ordinal >= 0) AND (active_attempt_ordinal <= (target_count - 1))) AND ((active_attempt_epoch >= 1) AND (active_attempt_epoch <= owner_epoch)) AND (active_target_digest ~ '^sha256:[0-9a-f]{64}$'::text)))))"),
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
                Map.entry("praxis_bulk_execution_terminal_reason_check", "CHECK ((((status = 'STOPPED'::text) = (terminal_reason_code IS NOT NULL)) AND ((terminal_reason_code IS NULL) OR (terminal_reason_code = ANY (ARRAY['LEGACY_REASON_NOT_RECORDED'::text, 'DEADLINE_EXCEEDED'::text, 'AUTHORIZATION_REVOKED'::text, 'POLICY_BLOCKED'::text, 'COMMON_GOVERNANCE_CHANGED'::text, 'COMMON_GOVERNANCE_UNAVAILABLE'::text, 'DEPENDENCY_UNAVAILABLE'::text, 'UNIT_ROLLED_BACK'::text, 'RECOVERY_STOPPED'::text, 'EVALUATOR_UNAVAILABLE'::text, 'STRUCTURAL_REVISION_CHANGED'::text])))))"),
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
        validateV5Columns(connection);
        validateV5Constraints(connection);
        validateV5Indexes(connection);
        validateV5Triggers(connection);
        validateV5Functions(connection);
        validateV5RolesAndPrivileges(connection, roles);
    }

    private static void validateV5Columns(Connection connection) throws SQLException {
        validateDurableColumns(connection, NAMESPACE_BINDING_TABLE, Map.ofEntries(
                Map.entry("namespace_id", "text|true"), Map.entry("deployment_id", "text|true"),
                Map.entry("bound_at", "timestamp with time zone|true")));
        validateDurableColumns(connection, OPERATION_CONTROL_TABLE, Map.ofEntries(
                Map.entry("namespace_id", "text|true"), Map.entry("operation_id", "text|true"),
                Map.entry("state", "text|true"), Map.entry("generation", "bigint|true"),
                Map.entry("descriptor_fingerprint", "text|false"),
                Map.entry("structural_revision", "text|false"),
                Map.entry("updated_at", "timestamp with time zone|true")));
        validateDurableColumns(connection, DEPLOYMENT_BUCKET_TABLE,
                Map.of("deployment_id", "text|true"));
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
        validateDurableConstraints(connection, NAMESPACE_BINDING_TABLE, false, Map.ofEntries(
                Map.entry("praxis_bulk_namespace_binding_pkey", "PRIMARY KEY (namespace_id)"),
                Map.entry("praxis_bulk_namespace_binding_pair_key", "UNIQUE (namespace_id, deployment_id)"),
                Map.entry("praxis_bulk_namespace_binding_namespace_check", "CHECK ((btrim(namespace_id) <> ''::text))"),
                Map.entry("praxis_bulk_namespace_binding_deployment_check", "CHECK ((btrim(deployment_id) <> ''::text))")));
        validateDurableConstraints(connection, OPERATION_CONTROL_TABLE, false, Map.ofEntries(
                Map.entry("praxis_bulk_operation_control_pkey", "PRIMARY KEY (namespace_id, operation_id)"),
                Map.entry("praxis_bulk_operation_control_namespace_id_fkey", "FOREIGN KEY (namespace_id) REFERENCES praxis_bulk.praxis_bulk_namespace_binding(namespace_id) ON DELETE RESTRICT"),
                Map.entry("praxis_bulk_operation_control_operation_check", "CHECK ((btrim(operation_id) <> ''::text))"),
                Map.entry("praxis_bulk_operation_control_generation_check", "CHECK ((generation >= 0))"),
                Map.entry("praxis_bulk_operation_control_state_check", "CHECK ((state = ANY (ARRAY['UNCOMPOSED'::text, 'SUSPENDED'::text, 'READY'::text])))"),
                Map.entry("praxis_bulk_operation_control_ready_check", "CHECK ((((state = 'READY'::text) AND (descriptor_fingerprint IS NOT NULL) AND (descriptor_fingerprint ~ '^sha256:[0-9a-f]{64}$'::text) AND (structural_revision IS NOT NULL) AND (btrim(structural_revision) <> ''::text)) OR ((state <> 'READY'::text) AND (descriptor_fingerprint IS NULL) AND (structural_revision IS NULL))))")));
        validateDurableConstraints(connection, DEPLOYMENT_BUCKET_TABLE, false, Map.ofEntries(
                Map.entry("praxis_bulk_deployment_bucket_pkey", "PRIMARY KEY (deployment_id)"),
                Map.entry("praxis_bulk_deployment_bucket_id_check", "CHECK ((btrim(deployment_id) <> ''::text))")));
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
                Map.entry("praxis_bulk_tombstone_terminal_check", "CHECK ((terminal_status = ANY (ARRAY['COMPLETED'::text, 'COMPLETED_WITH_ERRORS'::text, 'STOPPED'::text])))"),
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
                trigger(EXECUTION_TABLE, "praxis_bulk_execution_release_active_allocation", "AFTER UPDATE", "release_active_allocation_on_terminal"),
                trigger(RECEIPT_TABLE, "praxis_bulk_receipt_guard_terminal", "BEFORE INSERT", "guard_terminal_evidence_insert"),
                trigger(ADMISSION_TABLE, "praxis_bulk_admission_guard_terminal", "BEFORE INSERT", "guard_terminal_evidence_insert"));
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
            statement.setArray(2, connection.createArrayOf("text", V5_TRIGGERS.toArray()));
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

    private static void validateV5Functions(Connection connection) throws SQLException {
        String migration = readV5Migration();
        var keys = new LinkedHashSet<>(V5_FUNCTIONS);
        keys.add(RECEIPT_FUNCTION + "()");
        keys.add(ADMISSION_FUNCTION + "()");
        Set<String> definer = Set.of("protect_allocation_transition()", "validate_allocation_binding()",
                "guard_terminal_execution()", "release_active_allocation_on_terminal()",
                "purge_terminal_execution(p_execution_id uuid)",
                "expire_unconsumed_proposal(p_proposal_id uuid)");
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
                    boolean sqlHelper = name.equals("terminal_evidence_complete");
                    boolean booleanResult = sqlHelper || name.equals("purge_terminal_execution")
                            || name.equals("expire_unconsumed_proposal");
                    require((sqlHelper ? "sql" : "plpgsql").equals(rows.getString(3))
                                    && (booleanResult ? "boolean" : "trigger").equals(rows.getString(4))
                                    && rows.getBoolean(5) == definer.contains(key)
                                    && (sqlHelper ? "s" : "v").equals(rows.getString(6))
                                    && "f".equals(rows.getString(7)) && !rows.getBoolean(8)
                                    && "u".equals(rows.getString(9)) && rows.getBoolean(10),
                            "governed lifecycle function attributes differ: " + key);
                    if (definer.contains(key)) {
                        require("praxis_bulk_retention_owner".equals(rows.getString(11)),
                                "SECURITY DEFINER function has unexpected owner: " + key);
                    } else {
                        require(!Set.of("praxis_bulk_retention_owner", "praxis_bulk_retention_executor")
                                        .contains(rows.getString(11)),
                                "invoker function is owned by a retention role: " + key);
                    }
                    require(normalizeExpression(extractV5FunctionBody(migration, name))
                                    .equals(normalizeExpression(rows.getString(12))),
                            "governed lifecycle function body differs: " + key);
                    actual.add(key);
                }
            }
        }
        require(actual.equals(keys), "governed lifecycle functions are missing");
        validateV5FunctionPrivileges(connection);
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

    private static String extractV5FunctionBody(String migration, String function) {
        Pattern pattern = Pattern.compile("(?is)create\\s+(?:or\\s+replace\\s+)?function\\s+praxis_bulk\\."
                + Pattern.quote(function) + "\\s*\\([^)]*\\).*?\\bas\\s*\\$\\$(.*?)\\$\\$\\s*;");
        Matcher matcher = pattern.matcher(migration);
        require(matcher.find(), "Function is absent from V5 migration resource: " + function);
        String body = matcher.group(1);
        require(!matcher.find(), "Function is duplicated in V5 migration resource: " + function);
        return body;
    }

    private static void validateV5FunctionPrivileges(Connection connection) throws SQLException {
        Set<String> expected = Set.of(
                "terminal_evidence_complete(p_execution_id uuid, p_required_count integer)|praxis_bulk_retention_owner|EXECUTE",
                "purge_terminal_execution(p_execution_id uuid)|praxis_bulk_retention_executor|EXECUTE",
                "expire_unconsumed_proposal(p_proposal_id uuid)|praxis_bulk_retention_executor|EXECUTE");
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
            statement.setArray(2, connection.createArrayOf("text", V5_FUNCTIONS.toArray()));
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
        validateConfiguredRoles(connection, roleConfiguration);
        validateSchemaAndTableOwners(connection, roleConfiguration.expectedSchemaOwnerRole());
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
                select count(*) from pg_roles
                where rolname in ('praxis_bulk_retention_owner', 'praxis_bulk_retention_executor')
                  and not rolcanlogin and not rolinherit and not rolsuper and not rolcreatedb
                  and not rolcreaterole and not rolreplication and not rolbypassrls
                """)) {
            require(rows.next() && rows.getLong(1) == 2 && !rows.next(),
                    "bulk retention roles must be unprivileged NOLOGIN NOINHERIT roles");
        }
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
                select count(*) from pg_auth_members m
                join pg_roles granted on granted.oid=m.roleid
                join pg_roles member on member.oid=m.member
                where granted.rolname='praxis_bulk_retention_owner'
                   or member.rolname in ('praxis_bulk_retention_owner', 'praxis_bulk_retention_executor')
                """)) {
            require(rows.next() && rows.getLong(1) == 0 && !rows.next(),
                    "bulk retention owner/member topology is unsafe");
        }
        var expectedSchema = new LinkedHashSet<String>();
        expectedSchema.add("praxis_bulk_retention_owner|USAGE");
        expectedSchema.add("praxis_bulk_retention_executor|USAGE");
        roleConfiguration.runtimeGranteeRoles().forEach(role -> expectedSchema.add(role + "|USAGE"));
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

        Map<String, Set<String>> expectedOwner = Map.ofEntries(
                Map.entry(NAMESPACE_BINDING_TABLE, Set.of("T:SELECT", "C:deployment_id:UPDATE")),
                Map.entry(OPERATION_CONTROL_TABLE, Set.of("T:SELECT", "C:state:UPDATE")),
                Map.entry(DEPLOYMENT_BUCKET_TABLE, Set.of("T:SELECT", "C:deployment_id:UPDATE")),
                Map.entry(SUBJECT_BUCKET_TABLE, Set.of("T:SELECT", "C:deployment_id:UPDATE")),
                Map.entry(PROPOSAL_TABLE, Set.of("T:SELECT", "T:DELETE", "C:proposal_id:UPDATE")),
                Map.entry(EVALUATION_TABLE, Set.of("T:SELECT", "T:DELETE")),
                Map.entry(EXECUTION_TABLE, Set.of("T:SELECT", "T:DELETE", "C:execution_id:UPDATE")),
                Map.entry(RECEIPT_TABLE, Set.of("T:SELECT", "T:DELETE")),
                Map.entry(ADMISSION_TABLE, Set.of("T:SELECT", "T:DELETE")),
                Map.entry(ALLOCATION_TABLE, Set.of("T:SELECT", "T:DELETE", "C:state:UPDATE",
                        "C:released_at:UPDATE", "C:release_reason:UPDATE")),
                Map.entry(TOMBSTONE_TABLE, Set.of("T:SELECT", "T:INSERT")));
        for (var entry : expectedOwner.entrySet()) {
            Set<String> actual = tableRolePrivileges(connection, entry.getKey(),
                    "praxis_bulk_retention_owner");
            require(actual.equals(entry.getValue()), "retention-owner grants differ: " + entry.getKey());
            require(tableRolePrivileges(connection, entry.getKey(), "praxis_bulk_retention_executor").isEmpty(),
                    "retention executor must not have direct table privileges: " + entry.getKey());
            require(tableRolePrivileges(connection, entry.getKey(), "PUBLIC").isEmpty(),
                    "PUBLIC must not have governed lifecycle table privileges: " + entry.getKey());
        }
        validateRuntimeTablePrivileges(connection, roleConfiguration.runtimeGranteeRoles());
        validateRuntimeRoleMemberships(connection, roleConfiguration.runtimeGranteeRoles());
        validateRetentionExecutorMemberships(connection, roleConfiguration.retentionExecutorMembers());
    }

    private static void validateConfiguredRoles(Connection connection,
            BulkExecutionRoleConfiguration configuration) throws SQLException {
        var expected = new LinkedHashSet<>(configuration.runtimeGranteeRoles());
        expected.addAll(configuration.retentionExecutorMembers());
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

    private static void validateSchemaAndTableOwners(Connection connection, String expectedOwner)
            throws SQLException {
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
            statement.setArray(2, connection.createArrayOf("text", V5_TABLES.toArray()));
            try (var rows = statement.executeQuery()) {
                var found = new LinkedHashSet<String>();
                while (rows.next()) {
                    found.add(rows.getString(1));
                    require(expectedOwner.equals(rows.getString(2)),
                            "bulk table owner differs from explicit migration owner: " + rows.getString(1));
                }
                require(found.equals(V5_TABLES), "bulk table ownership inventory differs");
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

    private static void validateRuntimeTablePrivileges(Connection connection, Set<String> runtimeRoles)
            throws SQLException {
        Map<String, Set<String>> allowedByTable = Map.ofEntries(
                Map.entry(NAMESPACE_BINDING_TABLE, Set.of("T:SELECT", "C:deployment_id:UPDATE")),
                Map.entry(OPERATION_CONTROL_TABLE, Set.of("T:SELECT", "C:state:UPDATE",
                        "C:generation:UPDATE", "C:descriptor_fingerprint:UPDATE",
                        "C:structural_revision:UPDATE", "C:updated_at:UPDATE")),
                Map.entry(DEPLOYMENT_BUCKET_TABLE, Set.of("T:SELECT", "C:deployment_id:UPDATE")),
                Map.entry(SUBJECT_BUCKET_TABLE, Set.of("T:SELECT", "T:INSERT", "C:deployment_id:UPDATE")),
                Map.entry(PROPOSAL_TABLE, Set.of("T:SELECT", "T:INSERT", "C:proposal_id:UPDATE")),
                Map.entry(EVALUATION_TABLE, Set.of("T:SELECT", "T:INSERT")),
                Map.entry(EXECUTION_TABLE, Set.of("T:SELECT", "T:INSERT", "T:UPDATE")),
                Map.entry(RECEIPT_TABLE, Set.of("T:SELECT", "T:INSERT")),
                Map.entry(ADMISSION_TABLE, Set.of("T:SELECT", "T:INSERT")),
                Map.entry(ALLOCATION_TABLE, Set.of("T:SELECT", "T:INSERT", "C:state:UPDATE",
                        "C:released_at:UPDATE", "C:release_reason:UPDATE")),
                Map.entry(TOMBSTONE_TABLE, Set.of("T:SELECT")));
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
                            || role.equals("praxis_bulk_retention_executor")) continue;
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
                where coalesce(t.evidence_count, 0) <> coalesce(t.ordinal_count, 0)
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
                       and coalesce(t.evidence_count, 0) not between e.next_ordinal and e.next_ordinal + 1)
                """)) {
            try (var rows = statement.executeQuery()) {
                require(rows.next() && rows.getLong(1) == 0 && !rows.next(),
                        "admission and receipt evidence must form one unambiguous contiguous prefix");
            }
        }
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
                       v.input_fingerprint, v.evaluation_fingerprint, v.payload
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
                            var proposal = new BulkStoredProposal(rows.getObject(5, UUID.class),
                                    rows.getObject(13, OffsetDateTime.class).toInstant(),
                                    rows.getObject(14, OffsetDateTime.class).toInstant(), intent);
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
        Object id = target.id();
        String type = id instanceof Integer ? "integer" : "string";
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            for (String value : new String[] {"praxis.bulk.unit/1", evaluation.fingerprint(),
                    Integer.toString(ordinal), type, id.toString(), target.expectedVersion()}) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                hash.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
                hash.update(bytes);
            }
            return "sha256:" + HexFormat.of().formatHex(hash.digest());
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
    }

    private static void validateDurableColumns(Connection connection, String table,
            Map<String, String> expected) throws SQLException {
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
                                && normalizeExpression(expectedBody).equals(normalizeExpression(rows.getString(3)))
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
        try (var statement = connection.prepareStatement("select set_config('search_path', ?, false)")) {
            statement.setString(1, searchPath);
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
                                UUID activeAttemptId, String terminalReason, long admissionCount) { }
    private record AllocationRow(String kind, UUID proposalId, UUID executionId, String namespaceId,
                                 String deploymentId, String subjectDigest, String authorizationDigest,
                                 String state, Instant createdAt, Instant releasedAt, String releaseReason) { }
}
