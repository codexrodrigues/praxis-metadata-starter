package org.praxisplatform.uischema.bulk;

import java.util.List;
import java.util.Objects;

/** Common field intentions for an entire selected set; omission preserves other fields. */
public record BulkUniformEvaluationRequest<WI,F>(BulkExecutionMode executionMode,
        BulkSelection<WI,F> selection, List<BulkFieldChange> changes) {
    public BulkUniformEvaluationRequest {
        Objects.requireNonNull(executionMode, "executionMode is required");
        Objects.requireNonNull(selection, "selection is required");
        changes = BulkContractChecks.changes(changes);
    }
}
