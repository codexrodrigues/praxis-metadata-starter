package org.praxisplatform.uischema.bulk;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

/** Internal operational marker/slot checks; never reads or mutates the global authority. */
final class JdbcBulkCapacityOccupancy {
    private JdbcBulkCapacityOccupancy() { }

    static String lockMarker(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("select praxis_bulk.lock_capacity_marker()");
                var rows = statement.executeQuery()) {
            if (!rows.next()) throw corrupt();
            String state = rows.getString(1);
            if (rows.next()) throw corrupt();
            return state;
        }
    }

    static void requireBinding(Connection connection, BulkExecutionInfrastructure infrastructure,
            JdbcBulkCapacityInstallation.ExpectedBinding expected, boolean active) throws SQLException {
        if (expected == null || !expected.deploymentId().equals(infrastructure.deploymentId()))
            throw new BulkDurableExecutionException(BulkDurableExecutionException.Reason.NOT_EXECUTABLE);
        try (var statement = connection.prepareStatement("""
                select m.database_id,m.deployment_id,m.tenant_id,m.environment,m.binding_id,
                       m.binding_generation,m.attestation_id,m.authority_id,m.authority_epoch,m.state
                from praxis_bulk.praxis_bulk_capacity_marker m
                join praxis_bulk.praxis_bulk_namespace_binding b on b.deployment_id=m.deployment_id
                where m.marker_id=1 and b.namespace_id=?
                """)) {
            statement.setString(1, infrastructure.namespace());
            try (var rows = statement.executeQuery()) {
                if (!rows.next() || !expected.databaseId().equals(rows.getObject(1, UUID.class))
                        || !expected.deploymentId().equals(rows.getString(2))
                        || !expected.tenantId().equals(rows.getString(3))
                        || !expected.environment().equals(rows.getString(4))
                        || !expected.bindingId().equals(rows.getString(5))
                        || expected.generation() != rows.getLong(6)
                        || !expected.attestationId().equals(rows.getObject(7, UUID.class))
                        || !expected.authorityId().equals(rows.getObject(8, UUID.class))
                        || expected.authorityEpoch() != rows.getLong(9)) throw corrupt();
                if (active && !"ACTIVE".equals(rows.getString(10)))
                    throw new BulkDurableExecutionException(BulkDurableExecutionException.Reason.FENCED);
                if (rows.next()) throw corrupt();
            }
        }
    }

    static void requireInstallation(Connection connection, UUID token, String capacityClass) throws SQLException {
        try (var statement = connection.prepareStatement("select 1 from praxis_bulk.praxis_bulk_capacity_installation where token_id=? and capacity_class=?")) {
            statement.setObject(1, token); statement.setString(2, capacityClass);
            try (var rows = statement.executeQuery()) {
                if (!rows.next() || rows.next()) throw new BulkDurableExecutionException(BulkDurableExecutionException.Reason.NOT_EXECUTABLE);
            }
        }
    }

    static void requireActive(Connection connection, BulkExecutionInfrastructure infrastructure,
            JdbcBulkCapacityInstallation.ExpectedBinding expected, UUID executionId, long epoch) throws SQLException {
        requireBinding(connection, infrastructure, expected, true);
        try (var statement = connection.prepareStatement("""
                select 1 from praxis_bulk.praxis_bulk_capacity_slot s
                join praxis_bulk.praxis_bulk_execution e on e.active_token_id=s.token_id
                where e.execution_id=? and e.execution_mode='ASYNC' and s.capacity_class='ACTIVE'
                  and s.current_execution_id=e.execution_id and s.current_owner_epoch=?
                """)) {
            statement.setObject(1, executionId); statement.setLong(2, epoch);
            try (var rows = statement.executeQuery()) { if (!rows.next() || rows.next()) throw corrupt(); }
        }
    }

    private static BulkDurableExecutionException corrupt() {
        return new BulkDurableExecutionException(BulkDurableExecutionException.Reason.CORRUPT);
    }
}
