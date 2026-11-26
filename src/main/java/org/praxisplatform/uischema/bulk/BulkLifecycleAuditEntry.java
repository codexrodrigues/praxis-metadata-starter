package org.praxisplatform.uischema.bulk;

import java.time.Instant;
import java.util.UUID;

/** Immutable persistent audit entry stored in {@code praxis_bulk.praxis_bulk_lifecycle_audit}. */
public record BulkLifecycleAuditEntry(
        UUID auditId,
        String namespaceId,
        String deploymentId,
        String operationId,
        String intent,
        String actor,
        String realm,
        Long previousGeneration,
        long resultingGeneration,
        String resultingState,
        String correlationId,
        Instant executedAt) {
}
