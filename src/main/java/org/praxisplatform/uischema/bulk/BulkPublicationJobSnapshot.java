package org.praxisplatform.uischema.bulk;

import java.time.Instant;
import java.util.UUID;

/** Point-in-time snapshot of an asynchronous namespace bulk publication or reconciliation job. */
public record BulkPublicationJobSnapshot(
        UUID jobId,
        String namespace,
        BulkPublicationJobIntent intent,
        BulkPublicationJobStatus status,
        String phase,
        int totalOperations,
        int processedCount,
        int succeededCount,
        int skippedCount,
        int failedCount,
        String actor,
        String realm,
        String correlationId,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        BulkNamespaceBatchResult result,
        String errorCode,
        String errorMessage) {
}
