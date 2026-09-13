package org.praxisplatform.uischema.bulk;

import java.util.List;
import java.util.Objects;

/** Distinct intentions keyed by wire identity, never by row position. */
public record BulkItemEvaluationRequest<WI>(BulkExecutionMode executionMode, List<BulkItemChange<WI>> items) {
    public BulkItemEvaluationRequest {
        Objects.requireNonNull(executionMode, "executionMode is required");
        items = BulkContractChecks.nonEmpty(items);
        BulkContractChecks.unique(items.stream().map(BulkItemChange::id).toList());
    }
}
