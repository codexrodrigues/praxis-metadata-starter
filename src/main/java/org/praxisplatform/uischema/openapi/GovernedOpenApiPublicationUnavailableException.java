package org.praxisplatform.uischema.openapi;

/**
 * A governed structural read has no usable published OpenAPI photograph.
 * The private cause is retained for diagnostics; HTTP consumers receive a safe 503 outcome.
 * This does not authorize recapture, publication, or a retry of domain mutations.
 */
public final class GovernedOpenApiPublicationUnavailableException extends IllegalStateException {
    GovernedOpenApiPublicationUnavailableException(IllegalStateException cause) {
        super(cause.getMessage(), cause);
    }
}
