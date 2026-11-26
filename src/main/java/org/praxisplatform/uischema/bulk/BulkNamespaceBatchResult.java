package org.praxisplatform.uischema.bulk;

import java.time.Instant;
import java.util.List;

/** Aggregate outcome of a namespace-scoped bulk publication or reconciliation batch. */
public record BulkNamespaceBatchResult(
        String namespace,
        String intent,
        Long publicationGeneration,
        int totalOperations,
        int processedCount,
        int succeededCount,
        int skippedCount,
        int failedCount,
        List<ItemOutcome> items,
        Instant executedAt) {

    public BulkNamespaceBatchResult {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public enum ItemStatus {
        PUBLISHED,
        RECONCILED,
        SKIPPED_ALREADY_READY,
        SKIPPED_NOT_READY,
        FAILED
    }

    public record ItemOutcome(
            String operationId,
            Long previousGeneration,
            Long resultingGeneration,
            String resultingState,
            ItemStatus status,
            String message) {
    }
}
