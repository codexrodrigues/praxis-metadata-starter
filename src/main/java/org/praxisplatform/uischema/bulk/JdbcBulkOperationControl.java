package org.praxisplatform.uischema.bulk;

import java.sql.Array;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Restricted SQL-function boundary for the publication-bound operation-control row. */
final class JdbcBulkOperationControl {
    private JdbcBulkOperationControl() { }

    /** Acquires namespace/global/control SHARE locks; only publication-current READY is returned. */
    static Snapshot lockForAdmission(Connection connection, String namespaceId, String operationId)
            throws SQLException {
        try (var statement = connection.prepareStatement("""
                select state, generation, descriptor_fingerprint, structural_revision
                  from praxis_bulk.lock_operation_control(?, ?)
                """)) {
            statement.setString(1, namespaceId);
            statement.setString(2, operationId);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) return null;
                var snapshot = new Snapshot(rows.getString(1), rows.getLong(2), rows.getString(3), rows.getString(4));
                if (rows.next()) throw new SQLException("Operation control returned duplicate rows");
                return snapshot;
            }
        }
    }

    /**
     * Acquires SHARE locks across a batch of operation IDs in one query; returns a null snapshot
     * for absent or stale-publication rows while preserving UNCOMPOSED/SUSPENDED rows.
     */
    static Map<String, Snapshot> lockBatchForAdmission(
            Connection connection, String namespaceId, List<String> operationIds) throws SQLException {
        if (operationIds == null || operationIds.isEmpty()) {
            return Map.of();
        }
        Array idsArray = connection.createArrayOf("text", operationIds.toArray(String[]::new));
        try (var statement = connection.prepareStatement("""
                select u.operation_id, c.state, c.generation, c.descriptor_fingerprint, c.structural_revision
                  from unnest(?) as u(operation_id)
                  left join lateral praxis_bulk.lock_operation_control(?, u.operation_id) c on true
                """)) {
            statement.setArray(1, idsArray);
            statement.setString(2, namespaceId);
            Map<String, Snapshot> snapshots = new LinkedHashMap<>();
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    String operationId = rows.getString(1);
                    String state = rows.getString(2);
                    if (snapshots.containsKey(operationId)) {
                        throw new SQLException("Operation control returned duplicate rows for " + operationId);
                    }
                    if (state == null) {
                        if (rows.getObject(3) != null) {
                            throw new SQLException("Operation control returned incomplete row for " + operationId);
                        }
                        snapshots.put(operationId, null);
                        continue;
                    }
                    long generation = rows.getLong(3);
                    if (rows.wasNull()) {
                        throw new SQLException("Operation control returned incomplete row for " + operationId);
                    }
                    snapshots.put(operationId,
                            new Snapshot(state, generation, rows.getString(4), rows.getString(5)));
                }
            }
            return Collections.unmodifiableMap(snapshots);
        } finally {
            try {
                idsArray.free();
            } catch (SQLException ignored) {
                // Best-effort driver cleanup.
            }
        }
    }

    /** Performs the only supported durable control transition through the governance function. */
    static Transition transition(Connection connection, String namespaceId, String operationId,
            long expectedGeneration, Target target, String descriptorFingerprint, String structuralRevision,
            Long expectedPublicationGeneration, String expectedPublicationDigest)
            throws SQLException {
        try (var statement = connection.prepareStatement("""
                select applied, generation from praxis_bulk.transition_operation_control(?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, namespaceId);
            statement.setString(2, operationId);
            statement.setLong(3, expectedGeneration);
            statement.setString(4, target.name());
            statement.setString(5, descriptorFingerprint);
            statement.setString(6, structuralRevision);
            statement.setObject(7, expectedPublicationGeneration, java.sql.Types.BIGINT);
            statement.setString(8, expectedPublicationDigest);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new SQLException("Operation-control transition returned no result");
                var result = new Transition(rows.getBoolean(1), rows.getLong(2));
                if (rows.next()) throw new SQLException("Operation-control transition returned duplicate rows");
                return result;
            }
        }
    }

    /**
     * Atomically suspends the deployment OpenAPI publication and every currently READY operation
     * in {@code deploymentId} (plus the targeted operation row if UNCOMPOSED/SUSPENDED) inside
     * the caller's single PostgreSQL transaction.
     */
    static SuspendTransition suspendWithPublication(
            Connection connection,
            String namespaceId,
            String deploymentId,
            String operationId,
            long expectedGeneration,
            long expectedPublicationGeneration) throws SQLException {
        try (var statement = connection.prepareStatement("""
                select applied, operation_generation, publication_generation
                  from praxis_bulk.suspend_operation_and_publication(?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, namespaceId);
            statement.setString(2, deploymentId);
            statement.setString(3, operationId);
            statement.setLong(4, expectedGeneration);
            statement.setLong(5, expectedPublicationGeneration);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("Atomic suspension returned no result");
                }
                var result = new SuspendTransition(rows.getBoolean(1), rows.getLong(2), rows.getLong(3));
                if (rows.next()) {
                    throw new SQLException("Atomic suspension returned duplicate rows");
                }
                return result;
            }
        }
    }

    enum Target { READY, SUSPENDED }

    record Snapshot(String state, long generation, String descriptorFingerprint, String structuralRevision) {
        boolean ready() { return "READY".equals(state); }
    }

    record Transition(boolean applied, long generation) { }

    record SuspendTransition(boolean applied, long operationGeneration, long publicationGeneration) { }
}
