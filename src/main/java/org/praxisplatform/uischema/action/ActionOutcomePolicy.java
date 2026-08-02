package org.praxisplatform.uischema.action;

/**
 * Public result semantics for a workflow action.
 */
public record ActionOutcomePolicy(
        ActionOutcomeMode mode,
        ActionCollectionAtomicity atomicity
) {
    public ActionOutcomePolicy {
        mode = mode == null ? ActionOutcomeMode.SINGLE : mode;
        atomicity = atomicity == null ? ActionCollectionAtomicity.NOT_APPLICABLE : atomicity;
    }
}
