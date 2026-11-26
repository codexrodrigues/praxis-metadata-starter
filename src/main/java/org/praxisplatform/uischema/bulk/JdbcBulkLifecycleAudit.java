package org.praxisplatform.uischema.bulk;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Restricted SQL-function boundary for V21 append-only bulk lifecycle audit records. */
final class JdbcBulkLifecycleAudit {
    private JdbcBulkLifecycleAudit() { }

    static Instant record(
            Connection connection,
            UUID auditId,
            String namespaceId,
            String deploymentId,
            String operationId,
            String intent,
            BulkLifecycleAuditContext audit,
            Long previousGeneration,
            long resultingGeneration,
            String resultingState) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(auditId, "auditId");
        Objects.requireNonNull(audit, "audit");
        if (connection.getAutoCommit()) {
            throw new SQLException("Lifecycle audit recording requires a caller-owned transaction");
        }
        try (var statement = connection.prepareStatement("""
                select praxis_bulk.record_lifecycle_audit(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setObject(1, auditId);
            statement.setString(2, namespaceId);
            statement.setString(3, deploymentId);
            statement.setString(4, operationId);
            statement.setString(5, intent);
            statement.setString(6, audit.actor());
            statement.setString(7, audit.realm());
            if (previousGeneration == null) {
                statement.setNull(8, Types.BIGINT);
            } else {
                statement.setLong(8, previousGeneration);
            }
            statement.setLong(9, resultingGeneration);
            statement.setString(10, resultingState);
            statement.setString(11, audit.correlationId());
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("Lifecycle audit recording returned no timestamp");
                }
                Instant executedAt = rows.getObject(1, OffsetDateTime.class).toInstant();
                if (rows.next()) {
                    throw new SQLException("Lifecycle audit recording returned duplicate rows");
                }
                return executedAt;
            }
        }
    }

    static List<BulkLifecycleAuditEntry> read(Connection connection, String namespaceId, int limit)
            throws SQLException {
        Objects.requireNonNull(connection, "connection");
        if (connection.getAutoCommit()) {
            throw new SQLException("Lifecycle audit read requires a caller-owned transaction");
        }
        try (var statement = connection.prepareStatement("""
                select audit_id, namespace_id, deployment_id, operation_id, intent, actor, realm,
                       previous_generation, resulting_generation, resulting_state, correlation_id, executed_at
                  from praxis_bulk.read_lifecycle_audit(?, ?)
                """)) {
            statement.setString(1, namespaceId);
            statement.setInt(2, limit);
            List<BulkLifecycleAuditEntry> entries = new ArrayList<>();
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    Long previousGeneration = rows.getObject(8, Long.class);
                    entries.add(new BulkLifecycleAuditEntry(
                            rows.getObject(1, UUID.class),
                            rows.getString(2),
                            rows.getString(3),
                            rows.getString(4),
                            rows.getString(5),
                            rows.getString(6),
                            rows.getString(7),
                            previousGeneration,
                            rows.getLong(9),
                            rows.getString(10),
                            rows.getString(11),
                            rows.getObject(12, OffsetDateTime.class).toInstant()));
                }
            }
            return List.copyOf(entries);
        }
    }
}
