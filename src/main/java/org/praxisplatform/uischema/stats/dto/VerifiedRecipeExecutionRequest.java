package org.praxisplatform.uischema.stats.dto;

import java.util.Map;

/**
 * Canonical request payload for executing a governed analytical recipe at
 * {@code POST /stats/recipes/{recipeId}}.
 *
 * <p>
 * Runtime evaluation is fail-closed: only parameter names declared in the recipe's
 * {@code adjustableParams} catalog are accepted, and locked filter criteria enforced by the
 * server can never be widened, cleared, or overridden.
 * </p>
 */
public record VerifiedRecipeExecutionRequest(
        Map<String, Object> parameters,
        String timezone
) {
    public VerifiedRecipeExecutionRequest {
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        timezone = timezone == null || timezone.isBlank() ? null : timezone.trim();
    }

    public VerifiedRecipeExecutionRequest() {
        this(Map.of(), null);
    }
}
