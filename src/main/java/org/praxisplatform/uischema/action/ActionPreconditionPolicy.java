package org.praxisplatform.uischema.action;

/**
 * Standard protocol requirements that must be satisfied for safe command execution.
 */
public record ActionPreconditionPolicy(
        ActionRequirement idempotencyKey,
        ActionRequirement correlationId,
        ActionRequirement resourceVersion,
        ActionResourceVersionTransport resourceVersionTransport,
        String resourceVersionField,
        String resourceVersionTargetResourceKey,
        String resourceVersionTargetIdField
) {
    public ActionPreconditionPolicy(
            ActionRequirement idempotencyKey,
            ActionRequirement correlationId,
            ActionRequirement resourceVersion,
            ActionResourceVersionTransport resourceVersionTransport
    ) {
        this(idempotencyKey, correlationId, resourceVersion, resourceVersionTransport, null, null, null);
    }

    public ActionPreconditionPolicy(
            ActionRequirement idempotencyKey,
            ActionRequirement correlationId,
            ActionRequirement resourceVersion,
            ActionResourceVersionTransport resourceVersionTransport,
            String resourceVersionField
    ) {
        this(idempotencyKey, correlationId, resourceVersion, resourceVersionTransport,
                resourceVersionField, null, null);
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
        resourceVersionTargetResourceKey = normalize(resourceVersionTargetResourceKey);
        resourceVersionTargetIdField = normalize(resourceVersionTargetIdField);
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
        if ((resourceVersionTargetResourceKey == null) != (resourceVersionTargetIdField == null)) {
            throw new IllegalArgumentException(
                    "cross-resource version preconditions require target resourceKey and id field together");
        }
        if (resourceVersionTargetResourceKey != null
                && resourceVersionTransport != ActionResourceVersionTransport.IF_MATCH) {
            throw new IllegalArgumentException(
                    "cross-resource version preconditions require IF_MATCH transport");
        }
    }

    boolean hasCrossResourceVersionTarget() {
        return resourceVersionTargetResourceKey != null;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
