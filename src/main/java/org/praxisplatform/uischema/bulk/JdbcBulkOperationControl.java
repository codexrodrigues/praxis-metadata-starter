package org.praxisplatform.uischema.bulk;

import java.sql.Connection;
import java.sql.SQLException;

/** Restricted SQL-function boundary for the durable V5 operation-control row. */
final class JdbcBulkOperationControl {
    private JdbcBulkOperationControl() { }

    /** Acquires the operation-control share lock through the SECURITY DEFINER function. */
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

    /** Performs the only supported durable control transition through the governance function. */
    static Transition transition(Connection connection, String namespaceId, String operationId,
            long expectedGeneration, Target target, String descriptorFingerprint, String structuralRevision)
            throws SQLException {
        try (var statement = connection.prepareStatement("""
                select applied, generation from praxis_bulk.transition_operation_control(?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, namespaceId);
            statement.setString(2, operationId);
            statement.setLong(3, expectedGeneration);
            statement.setString(4, target.name());
            statement.setString(5, descriptorFingerprint);
            statement.setString(6, structuralRevision);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new SQLException("Operation-control transition returned no result");
                var result = new Transition(rows.getBoolean(1), rows.getLong(2));
                if (rows.next()) throw new SQLException("Operation-control transition returned duplicate rows");
                return result;
            }
        }
    }

    enum Target { READY, SUSPENDED }

    record Snapshot(String state, long generation, String descriptorFingerprint, String structuralRevision) {
        boolean ready() { return "READY".equals(state); }
    }

    record Transition(boolean applied, long generation) { }
}
