package org.praxisplatform.uischema.bulk;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.ZoneOffset;
import java.util.UUID;

/** Transactional lifecycle accounting over immutable scope buckets and append-only allocations. */
final class BulkQuotaLedger {
    static final int MAX_PENDING_DEPLOYMENT = 100;
    static final int MAX_PENDING_SUBJECT = 10;
    static final int MAX_ACTIVE_DEPLOYMENT = 80;

    private BulkQuotaLedger() { }

    /** Lock order: namespace binding, operation control, deployment bucket, subject bucket, proposal. */
    static Scope lockProposal(Connection connection, BulkExecutionInfrastructure infrastructure,
            BulkStoredProposal proposal, boolean proposalMustExist, boolean enforcePendingCapacity) throws SQLException {
        var context = proposal.snapshot().context();
        String deployment;
        try (var statement = connection.prepareStatement("""
                select deployment_id from praxis_bulk.praxis_bulk_namespace_binding
                where namespace_id=? for share
                """)) {
            statement.setString(1, context.namespaceId());
            try (var rows = statement.executeQuery()) {
                if (!rows.next())
                    throw new BulkProposalStorageException(BulkProposalStorageException.Reason.CORRUPT);
                deployment = rows.getString(1);
                if (!infrastructure.deploymentId().equals(deployment) || rows.next())
                    throw new BulkProposalStorageException(BulkProposalStorageException.Reason.CORRUPT);
            }
        }
        try (var statement = connection.prepareStatement("""
                select state from praxis_bulk.praxis_bulk_operation_control
                where namespace_id=? and operation_id=? for share
                """)) {
            statement.setString(1, context.namespaceId());
            statement.setString(2, context.operationRef().operationId());
            try (var rows = statement.executeQuery()) {
                if (!rows.next() || !"READY".equals(rows.getString(1)) || rows.next())
                    throw new BulkProposalStorageException(BulkProposalStorageException.Reason.UNAVAILABLE);
            }
        }
        lockDeployment(connection, deployment);
        String subjectDigest = BulkScopeDigests.subjectQuotaDigest(deployment, context.subjectId());
        String authorizationDigest = BulkScopeDigests.authorizationScopeDigest(context.namespaceId(),
                context.subjectId(), context.resourceKey(), context.operationRef().operationId());
        try (var statement = connection.prepareStatement("""
                insert into praxis_bulk.praxis_bulk_subject_bucket
                    (deployment_id, subject_scope_digest_version, subject_scope_digest)
                values (?, ?, ?) on conflict do nothing
                """)) {
            statement.setString(1, deployment);
            statement.setInt(2, BulkScopeDigests.VERSION);
            statement.setString(3, subjectDigest);
            statement.executeUpdate();
        }
        try (var statement = connection.prepareStatement("""
                select deployment_id from praxis_bulk.praxis_bulk_subject_bucket
                where deployment_id=? and subject_scope_digest_version=? and subject_scope_digest=? for update
                """)) {
            statement.setString(1, deployment);
            statement.setInt(2, BulkScopeDigests.VERSION);
            statement.setString(3, subjectDigest);
            try (var rows = statement.executeQuery()) {
                if (!rows.next() || rows.next()) throw new BulkProposalStorageException(
                        BulkProposalStorageException.Reason.CORRUPT);
            }
        }
        try (var statement = connection.prepareStatement("""
                select namespace_id, subject_id, resource_key, operation_id
                  from praxis_bulk.praxis_bulk_proposal where proposal_id=? for update
                """)) {
            statement.setObject(1, proposal.id());
            try (var rows = statement.executeQuery()) {
                boolean found = rows.next();
                if (found != proposalMustExist) throw new BulkProposalStorageException(
                        proposalMustExist ? BulkProposalStorageException.Reason.CONFLICT
                                : BulkProposalStorageException.Reason.CONFLICT);
                if (found && (!context.namespaceId().equals(rows.getString(1))
                        || !context.subjectId().equals(rows.getString(2))
                        || !context.resourceKey().equals(rows.getString(3))
                        || !context.operationRef().operationId().equals(rows.getString(4)) || rows.next()))
                    throw new BulkProposalStorageException(BulkProposalStorageException.Reason.CORRUPT);
            }
        }
        if (enforcePendingCapacity) requirePendingRoom(connection, deployment, subjectDigest);
        return new Scope(deployment, subjectDigest, authorizationDigest);
    }

    static void insertPending(Connection connection, BulkStoredProposal proposal, Scope scope) throws SQLException {
        try (var statement = connection.prepareStatement("""
                insert into praxis_bulk.praxis_bulk_allocation
                    (allocation_id, namespace_id, deployment_id, subject_scope_digest_version,
                     subject_scope_digest, authorization_scope_digest_version,
                     authorization_scope_digest, kind, proposal_id, execution_id, state,
                     created_at, released_at, release_reason)
                values (?, ?, ?, ?, ?, ?, ?, 'PROPOSAL_PENDING', ?, null, 'PENDING', ?, null, null)
                """)) {
            statement.setObject(1, allocationId("PROPOSAL_PENDING", proposal.id()));
            statement.setString(2, proposal.snapshot().context().namespaceId());
            statement.setString(3, scope.deploymentId());
            statement.setInt(4, BulkScopeDigests.VERSION);
            statement.setString(5, scope.subjectDigest());
            statement.setInt(6, BulkScopeDigests.VERSION);
            statement.setString(7, scope.authorizationDigest());
            statement.setObject(8, proposal.id());
            statement.setObject(9, proposal.createdAt().atOffset(ZoneOffset.UTC));
            statement.executeUpdate();
        }
    }

    static void requireExecutionRoom(Connection connection, Scope scope) throws SQLException {
        try (var statement = connection.prepareStatement("""
                select count(*) from praxis_bulk.praxis_bulk_allocation
                 where deployment_id=? and kind='EXECUTION_ACTIVE' and state='ACTIVE'
                """)) {
            statement.setString(1, scope.deploymentId());
            try (var rows = statement.executeQuery()) {
                if (!rows.next() || rows.getLong(1) >= MAX_ACTIVE_DEPLOYMENT)
                    throw new BulkDurableExecutionException(BulkDurableExecutionException.Reason.CAPACITY);
            }
        }
    }

    static void activateExecution(Connection connection, BulkFingerprintContext context,
            UUID proposalId, UUID executionId, Scope scope) throws SQLException {
        try (var statement = connection.prepareStatement("""
                update praxis_bulk.praxis_bulk_allocation
                   set state='CONSUMED'
                 where proposal_id=? and kind='PROPOSAL_PENDING' and state='PENDING'
                   and namespace_id=? and deployment_id=?
                   and subject_scope_digest_version=? and subject_scope_digest=?
                   and authorization_scope_digest_version=? and authorization_scope_digest=?
                """)) {
            statement.setObject(1, proposalId);
            statement.setString(2, context.namespaceId());
            statement.setString(3, scope.deploymentId());
            statement.setInt(4, BulkScopeDigests.VERSION);
            statement.setString(5, scope.subjectDigest());
            statement.setInt(6, BulkScopeDigests.VERSION);
            statement.setString(7, scope.authorizationDigest());
            if (statement.executeUpdate() != 1)
                throw new BulkDurableExecutionException(BulkDurableExecutionException.Reason.EXPIRED);
        }
        try (var statement = connection.prepareStatement("""
                insert into praxis_bulk.praxis_bulk_allocation
                    (allocation_id, namespace_id, deployment_id, subject_scope_digest_version,
                     subject_scope_digest, authorization_scope_digest_version, authorization_scope_digest,
                     kind, proposal_id, execution_id, state, created_at)
                select ?, e.namespace_id, ?, ?, ?, ?, ?, 'EXECUTION_ACTIVE', null, e.execution_id,
                       'ACTIVE', e.created_at
                  from praxis_bulk.praxis_bulk_execution e
                 where e.execution_id=? and e.proposal_id=?
                """)) {
            statement.setObject(1, allocationId("EXECUTION_ACTIVE", executionId));
            statement.setString(2, scope.deploymentId());
            statement.setInt(3, BulkScopeDigests.VERSION);
            statement.setString(4, scope.subjectDigest());
            statement.setInt(5, BulkScopeDigests.VERSION);
            statement.setString(6, scope.authorizationDigest());
            statement.setObject(7, executionId);
            statement.setObject(8, proposalId);
            if (statement.executeUpdate() != 1)
                throw new BulkDurableExecutionException(BulkDurableExecutionException.Reason.CORRUPT);
        }
    }

    static boolean tombstoneExists(Connection connection, BulkFingerprintContext scope,
            String authorizationDigest, String keyDigest) throws SQLException {
        try (var statement = connection.prepareStatement("""
                select 1 from praxis_bulk.praxis_bulk_tombstone
                 where namespace_id=? and authorization_scope_digest_version=?
                   and authorization_scope_digest=? and resource_key=? and operation_id=?
                   and idempotency_key_digest=?
                """)) {
            statement.setString(1, scope.namespaceId());
            statement.setInt(2, BulkScopeDigests.VERSION);
            statement.setString(3, authorizationDigest);
            statement.setString(4, scope.resourceKey());
            statement.setString(5, scope.operationRef().operationId());
            statement.setString(6, keyDigest);
            try (var rows = statement.executeQuery()) { return rows.next(); }
        }
    }

    private static void lockDeployment(Connection connection, String deployment) throws SQLException {
        try (var statement = connection.prepareStatement("""
                select deployment_id from praxis_bulk.praxis_bulk_deployment_bucket
                 where deployment_id=? for update
                """)) {
            statement.setString(1, deployment);
            try (var rows = statement.executeQuery()) {
                if (!rows.next() || rows.next()) throw new BulkProposalStorageException(
                        BulkProposalStorageException.Reason.CORRUPT);
            }
        }
    }

    private static void requirePendingRoom(Connection connection, String deployment, String subjectDigest)
            throws SQLException {
        try (var statement = connection.prepareStatement("""
                select count(*) from praxis_bulk.praxis_bulk_allocation
                 where deployment_id=? and kind='PROPOSAL_PENDING' and state='PENDING'
                """)) {
            statement.setString(1, deployment);
            try (var rows = statement.executeQuery()) {
                if (!rows.next() || rows.getLong(1) >= MAX_PENDING_DEPLOYMENT)
                    throw new BulkProposalStorageException(BulkProposalStorageException.Reason.CAPACITY);
            }
        }
        try (var statement = connection.prepareStatement("""
                select count(*) from praxis_bulk.praxis_bulk_allocation
                 where deployment_id=? and subject_scope_digest_version=? and subject_scope_digest=?
                   and kind='PROPOSAL_PENDING' and state='PENDING'
                """)) {
            statement.setString(1, deployment);
            statement.setInt(2, BulkScopeDigests.VERSION);
            statement.setString(3, subjectDigest);
            try (var rows = statement.executeQuery()) {
                if (!rows.next() || rows.getLong(1) >= MAX_PENDING_SUBJECT)
                    throw new BulkProposalStorageException(BulkProposalStorageException.Reason.CAPACITY);
            }
        }
    }

    private static UUID allocationId(String kind, UUID ownerId) {
        return UUID.nameUUIDFromBytes(("praxis.bulk.allocation/1:" + kind + ":" + ownerId)
                .getBytes(StandardCharsets.UTF_8));
    }

    record Scope(String deploymentId, String subjectDigest, String authorizationDigest) { }
}
