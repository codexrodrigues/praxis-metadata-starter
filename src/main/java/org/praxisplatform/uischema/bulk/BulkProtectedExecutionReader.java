package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.annotation.JsonIgnoreType;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

/** Route-bound execution correlation used only inside an authorized read composition. */
@JsonIgnoreType
final class BulkProtectedExecutionReader {
    private BulkProtectedExecutionReader() { }

    @JsonIgnoreType
    record Located(UUID proposalId, String creatorSubjectId, String inputFingerprint,
            String evaluationFingerprint) {
        @Override public String toString() { return "BulkProtectedExecutionLocation[protected]"; }
    }

    /** Resolves protected correlation, not permission. No returned value may be published directly. */
    static Located locateForAuthorizedComposition(Connection connection, String namespaceId,
            String resourceKey, String operationId, UUID executionId) throws SQLException {
        try (var statement = connection.prepareStatement("""
                select proposal_id, subject_id, input_fingerprint, evaluation_fingerprint
                from praxis_bulk.praxis_bulk_execution
                where execution_id=? and namespace_id=? and resource_key=? and operation_id=?
                """)) {
            statement.setObject(1, executionId);
            statement.setString(2, namespaceId);
            statement.setString(3, resourceKey);
            statement.setString(4, operationId);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) return null;
                Located result = new Located(rows.getObject(1, UUID.class), rows.getString(2),
                        rows.getString(3), rows.getString(4));
                if (result.proposalId() == null || result.creatorSubjectId() == null
                        || result.inputFingerprint() == null || result.evaluationFingerprint() == null
                        || rows.next()) {
                    throw new BulkDurableExecutionException(BulkDurableExecutionException.Reason.CORRUPT);
                }
                return result;
            }
        }
    }
}
