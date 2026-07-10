package org.praxisplatform.uischema.command;

import java.util.Map;

/**
 * Exception used by host providers to return a public, governed command failure.
 */
public class ResourceCommandExecutionException extends RuntimeException {

    private final ResourceCommandOutcome outcome;
    private final String publicMessage;
    private final Map<String, Object> evidence;

    public ResourceCommandExecutionException(
            ResourceCommandOutcome outcome,
            String publicMessage,
            Map<String, Object> evidence
    ) {
        super(publicMessage);
        this.outcome = outcome == null ? ResourceCommandOutcome.UNEXPECTED_SANITIZED : outcome;
        this.publicMessage = publicMessage == null ? "Command execution failed." : publicMessage;
        this.evidence = evidence == null ? Map.of() : Map.copyOf(evidence);
    }

    public ResourceCommandOutcome outcome() {
        return outcome;
    }

    public String publicMessage() {
        return publicMessage;
    }

    public Map<String, Object> evidence() {
        return evidence;
    }
}
