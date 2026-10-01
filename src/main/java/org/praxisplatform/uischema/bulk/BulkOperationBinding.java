package org.praxisplatform.uischema.bulk;

import org.praxisplatform.uischema.action.ActionCollectionAtomicity;
import org.springframework.web.method.HandlerMethod;

import java.util.Objects;

/** Immutable structural binding between a bulk confirmation, evaluation and optional unit update source. */
public final class BulkOperationBinding {
    private final String resourceKey;
    private final String confirmationOperationId;
    private final String evaluationOperationId;
    private final BulkMode mode;
    private final ActionCollectionAtomicity atomicity;
    private final HandlerMethod confirmationHandler;
    private final HandlerMethod evaluationHandler;
    private final String updateSourceOperationId;
    private final HandlerMethod updateSourceHandler;
    private final java.util.Set<String> protectedUpdateFields;

    BulkOperationBinding(String resourceKey, String confirmationOperationId, String evaluationOperationId,
            BulkMode mode, ActionCollectionAtomicity atomicity,
            HandlerMethod confirmationHandler, HandlerMethod evaluationHandler,
            String updateSourceOperationId, HandlerMethod updateSourceHandler,
            java.util.Set<String> protectedUpdateFields) {
        this.updateSourceOperationId = updateSourceOperationId;
        this.updateSourceHandler = updateSourceHandler;
        this.protectedUpdateFields = java.util.Set.copyOf(protectedUpdateFields);
        if ((updateSourceOperationId == null) != (updateSourceHandler == null))
            throw new IllegalArgumentException("Unit update identity and handler must be present together");
        if (mode != BulkMode.DOMAIN_COMMAND && updateSourceHandler == null)
            throw new IllegalArgumentException("Bulk update requires its unit update source");
        if (updateSourceOperationId != null) canonical(updateSourceOperationId, "updateSourceOperationId");
        if (updateSourceHandler != null && !confirmationHandler.getBeanType().equals(updateSourceHandler.getBeanType()))
            throw new IllegalArgumentException("Unit update source must belong to the same resource controller");
        this.resourceKey = canonical(resourceKey, "resourceKey");
        this.confirmationOperationId = canonical(confirmationOperationId, "confirmationOperationId");
        this.evaluationOperationId = canonical(evaluationOperationId, "evaluationOperationId");
        this.mode = Objects.requireNonNull(mode, "mode");
        this.atomicity = Objects.requireNonNull(atomicity, "atomicity");
        if (atomicity == ActionCollectionAtomicity.NOT_APPLICABLE)
            throw new IllegalArgumentException("Bulk operation atomicity must be ATOMIC or PER_ITEM");
        this.confirmationHandler = Objects.requireNonNull(confirmationHandler, "confirmationHandler");
        this.evaluationHandler = Objects.requireNonNull(evaluationHandler, "evaluationHandler");
        if (confirmationHandler.equals(evaluationHandler))
            throw new IllegalArgumentException("Evaluation and confirmation handlers must be distinct");
        if (!confirmationHandler.getBeanType().equals(evaluationHandler.getBeanType()))
            throw new IllegalArgumentException("Evaluation and confirmation handlers must belong to one resource controller");
    }

    public String resourceKey() { return resourceKey; }
    public String confirmationOperationId() { return confirmationOperationId; }
    public String evaluationOperationId() { return evaluationOperationId; }
    public BulkMode mode() { return mode; }
    public ActionCollectionAtomicity atomicity() { return atomicity; }
    public HandlerMethod confirmationHandler() { return confirmationHandler; }
    public HandlerMethod evaluationHandler() { return evaluationHandler; }

    public java.util.Optional<String> updateSourceOperationId() { return java.util.Optional.ofNullable(updateSourceOperationId); }
    public java.util.Optional<HandlerMethod> updateSourceHandler() { return java.util.Optional.ofNullable(updateSourceHandler); }
    public java.util.Set<String> protectedUpdateFields() { return protectedUpdateFields; }

    private static String canonical(String value, String name) {
        if (value == null || value.isBlank() || !value.equals(value.strip())
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " must be canonical nonblank text");
        }
        return value;
    }
}
