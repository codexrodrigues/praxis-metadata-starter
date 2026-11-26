package org.praxisplatform.uischema.bulk;

import java.time.Instant;
import java.util.Objects;

/**
 * Read-only momentary snapshot of one declared bulk confirmation control row and its current
 * deployment OpenAPI publication fence. Not authorization, admission, or a promise of readiness.
 */
public record BulkOperationControlSnapshot(
        String namespaceId,
        String operationId,
        String state,
        Long generation,
        String publicationState,
        Long publicationGeneration,
        Instant publicationUpdatedAt) {
    public BulkOperationControlSnapshot {
        Objects.requireNonNull(namespaceId, "namespaceId");
        Objects.requireNonNull(operationId, "operationId");
    }

    public boolean ready() {
        return "READY".equals(state);
    }

    public boolean present() {
        return state != null && generation != null;
    }
}
