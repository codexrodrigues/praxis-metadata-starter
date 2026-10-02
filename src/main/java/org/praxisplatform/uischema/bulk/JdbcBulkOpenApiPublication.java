package org.praxisplatform.uischema.bulk;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * Real JDBC boundary for the deployment global OpenAPI publication ledger.
 * The caller owns the physical transaction, timeout and namespace -> global -> operation
 * lock order. This JDBC class manages the ledger; lifecycle and public-serving wiring belong
 * to their respective consumers rather than this boundary.
 * V15 binds every supported operation READY CAS to this ledger's current generation/digest,
 * including controls bootstrapped after suspension. The removed legacy CAS cannot grant READY.
 * This SQL fence does not install or attest any process-local document snapshot; the lifecycle
 * must still bind the captured response set and public serving to the committed identity.
 * First publication also requires SUSPENDED, invalidating READY rows from an upgrade.
 */
final class JdbcBulkOpenApiPublication {
    private JdbcBulkOpenApiPublication() { }

    /** Holds namespace and publication SHARE locks until the caller commits or rolls back. */
    static Snapshot lockForRead(Connection connection, String namespaceId, String deploymentId) throws SQLException {
        requireTransaction(connection);
        try (var query = connection.prepareStatement("""
                select state, generation, document_digest, updated_at
                  from praxis_bulk.lock_openapi_publication(?, ?)
                """)) {
            query.setString(1, namespaceId); query.setString(2, deploymentId);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) throw new SQLException("OpenAPI publication lock returned no row");
                var result = new Snapshot(rows.getString(1), rows.getLong(2), rows.getString(3),
                        rows.getObject(4, OffsetDateTime.class).toInstant());
                if (rows.next()) throw new SQLException("OpenAPI publication lock returned duplicate rows");
                return result;
            }
        }
    }

    /** CAS publication, or invalidate all currently READY operations before global suspension. */
    static Transition transition(Connection connection, String namespaceId, String deploymentId,
            long expectedGeneration, Target target, String documentDigest) throws SQLException {
        requireTransaction(connection); Objects.requireNonNull(target, "target");
        try (var query = connection.prepareStatement("""
                select applied, generation from praxis_bulk.transition_openapi_publication(?, ?, ?, ?, ?)
                """)) {
            query.setString(1, namespaceId); query.setString(2, deploymentId); query.setLong(3, expectedGeneration);
            query.setString(4, target.name()); query.setString(5, documentDigest);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) throw new SQLException("OpenAPI publication transition returned no result");
                var result = new Transition(rows.getBoolean(1), rows.getLong(2));
                if (rows.next()) throw new SQLException("OpenAPI publication transition returned duplicate rows");
                return result;
            }
        }
    }

    private static void requireTransaction(Connection connection) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        if (connection.getAutoCommit()) throw new SQLException("OpenAPI publication requires a caller-owned transaction");
    }

    enum Target { PUBLISHED, SUSPENDED }
    record Snapshot(String state, long generation, String documentDigest, Instant updatedAt) {
        boolean published() { return "PUBLISHED".equals(state); }
    }
    record Transition(boolean applied, long generation) { }
}
