package org.praxisplatform.uischema.bulk;

import com.fasterxml.jackson.annotation.JsonIgnoreType;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Host-owned, versioned projection of protected proposal intent into a public-safe RS1 shape.
 *
 * <p>This provider is a pure server-side projection. It must not perform I/O or authorization,
 * and its result is not evidence of readiness, capability or permission to execute.</p>
 */
@JsonIgnoreType
public interface BulkProposalProjectionProvider {

    String resourceKey();

    String confirmationOperationId();

    String projectionRevision();

    /** Returns a meaningful non-empty object; an empty object is rejected as fabricated progress. */
    JsonNode projectRedactedIntent(BulkEvaluationSnapshot evaluation);
}
