package org.praxisplatform.uischema.action;

/**
 * Governed interaction semantics for one workflow action.
 */
public record ActionInteractionPolicy(
        ActionInteractionMode mode,
        ActionRiskLevel riskLevel,
        boolean confirmationRequired,
        boolean reversible
) {
    public ActionInteractionPolicy {
        mode = mode == null ? ActionInteractionMode.FORM : mode;
        riskLevel = riskLevel == null ? ActionRiskLevel.MEDIUM : riskLevel;
    }
}
