package org.praxisplatform.uischema.command;

/**
 * Stable public outcomes for a governed resource command.
 */
public enum ResourceCommandOutcome {
    SUCCESS(null),
    ACCEPTED(null),
    VALIDATION_FAILED(ResourceCommandErrorCategory.VALIDATION),
    PERMISSION_DENIED(ResourceCommandErrorCategory.PERMISSION),
    NOT_FOUND(ResourceCommandErrorCategory.NOT_FOUND),
    CONFLICT_DUPLICATE(ResourceCommandErrorCategory.CONFLICT_DUPLICATE),
    CONFLICT_DEPENDENCY(ResourceCommandErrorCategory.CONFLICT_DEPENDENCY),
    PRECONDITION_FAILED(ResourceCommandErrorCategory.PRECONDITION),
    UNEXPECTED_SANITIZED(ResourceCommandErrorCategory.UNEXPECTED_SANITIZED);

    private final ResourceCommandErrorCategory errorCategory;

    ResourceCommandOutcome(ResourceCommandErrorCategory errorCategory) {
        this.errorCategory = errorCategory;
    }

    public boolean successful() {
        return errorCategory == null;
    }

    public ResourceCommandErrorCategory errorCategory() {
        return errorCategory;
    }
}
