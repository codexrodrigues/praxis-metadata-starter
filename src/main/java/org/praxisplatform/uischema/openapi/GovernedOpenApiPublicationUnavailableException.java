package org.praxisplatform.uischema.openapi;

/**
 * A governed structural read has no usable published OpenAPI photograph.
 * The private cause is retained for diagnostics; HTTP consumers receive a safe 503 outcome.
 * This does not authorize recapture, publication, or a retry of domain mutations.
 */
public final class GovernedOpenApiPublicationUnavailableException extends IllegalStateException {
    GovernedOpenApiPublicationUnavailableException(RuntimeException cause) {
        super(cause.getMessage(), cause);
        observe(cause);
    }

    /** Code-only private observation; diagnostics must never change the original refusal. */
    private static void observe(Throwable failure) {
        try {
            var seen = java.util.Collections.newSetFromMap(
                    new java.util.IdentityHashMap<Throwable, Boolean>());
            var chain = new java.util.ArrayList<String>();
            for (Throwable current = failure; current != null && seen.add(current) && chain.size() < 12;
                    current = current.getCause()) {
                String type = current.getClass().getName();
                boolean canonical = type.startsWith("java.") || type.startsWith("org.springframework.")
                        || type.startsWith("org.postgresql.") || type.startsWith("org.hibernate.")
                        || type.startsWith("org.praxisplatform.uischema.");
                String state = current instanceof java.sql.SQLException sql ? sql.getSQLState() : null;
                chain.add((canonical ? type : "OTHER")
                        + (state != null && state.matches("[0-9A-Z]{5}") ? ":" + state : ""));
            }
            org.slf4j.LoggerFactory.getLogger(GovernedOpenApiPublicationUnavailableException.class)
                    .warn("GOVERNED_OPENAPI_PUBLICATION_UNAVAILABLE causeTypes={}", chain);
        } catch (Throwable ignoredDiagnosticFailure) {
            // No exception message, SQL, payload, identity, URL or Throwable is sent to the logger.
        }
    }
}
