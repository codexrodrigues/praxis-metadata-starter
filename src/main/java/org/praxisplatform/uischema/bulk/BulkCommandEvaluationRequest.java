package org.praxisplatform.uischema.bulk;

import java.util.Objects;

/** Shared domain parameters, kept separate from protected selection and execution bindings. */
public record BulkCommandEvaluationRequest<P,WI,F>(BulkExecutionMode executionMode,
        BulkSelection<WI,F> selection, P parameters) {
    public BulkCommandEvaluationRequest {
        Objects.requireNonNull(executionMode, "executionMode is required");
        Objects.requireNonNull(selection, "selection is required");
        Objects.requireNonNull(parameters, "parameters are required");
    }
}
