package org.praxisplatform.uischema.action;

/**
 * Standard protocol requirements that must be satisfied for safe command execution.
 */
public record ActionPreconditionPolicy(
        ActionRequirement idempotencyKey,
        ActionRequirement correlationId,
        ActionRequirement resourceVersion,
        ActionResourceVersionTransport resourceVersionTransport,
        String resourceVersionField
) {
    public ActionPreconditionPolicy(
            ActionRequirement idempotencyKey,
            ActionRequirement correlationId,
            ActionRequirement resourceVersion,
            ActionResourceVersionTransport resourceVersionTransport
    ) {
        this(idempotencyKey, correlationId, resourceVersion, resourceVersionTransport, null);
    }

    public ActionPreconditionPolicy {
        idempotencyKey = idempotencyKey == null ? ActionRequirement.NONE : idempotencyKey;
        correlationId = correlationId == null ? ActionRequirement.NONE : correlationId;
        resourceVersion = resourceVersion == null ? ActionRequirement.NONE : resourceVersion;
        resourceVersionTransport = resourceVersionTransport == null
                ? ActionResourceVersionTransport.NONE
                : resourceVersionTransport;
        resourceVersionField = resourceVersionField == null || resourceVersionField.isBlank()
                ? null
                : resourceVersionField.trim();
        if (resourceVersion == ActionRequirement.NONE
                && resourceVersionTransport != ActionResourceVersionTransport.NONE) {
            throw new IllegalArgumentException(
                    "resourceVersionTransport requires a resourceVersion requirement");
        }
        if (resourceVersion != ActionRequirement.NONE
                && resourceVersionTransport == ActionResourceVersionTransport.NONE) {
            throw new IllegalArgumentException(
                    "resourceVersion requirement requires a resourceVersionTransport");
        }
    }
}
