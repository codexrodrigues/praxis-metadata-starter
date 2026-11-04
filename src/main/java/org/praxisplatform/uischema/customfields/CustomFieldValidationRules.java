package org.praxisplatform.uischema.customfields;

import java.util.Map;

/**
 * Canonical admission contract for {@code CustomFieldDescriptor.validationRules}.
 * Authoring services must apply this contract before storing or activating a definition,
 * so the same expression is admissible when the descriptor is subsequently published.
 */
public final class CustomFieldValidationRules {
    private CustomFieldValidationRules() { }

    /**
     * Validates a detached definition using the descriptor's closed roots, dialect and limits.
     * This adapter has no temporal context: contextual operators are rejected at admission.
     *
     * @param rules {@code jsonLogic} with an optional non-blank {@code message}; null means no rule
     * @throws IllegalArgumentException when the definition violates the descriptor contract
     * @throws org.praxisplatform.rules.jsonlogic.PraxisJsonLogicException when the expression is invalid
     */
    public static void validate(Map<String, Object> rules) {
        CustomFieldJsonLogic.validateDefinition(rules == null || rules.isEmpty()
                ? Map.of() : CustomFieldJsonLogic.snapshot(rules));
    }
}
