package org.praxisplatform.uischema.stats.dto;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Canonical request payload for executing a governed analytical recipe at
 * {@code POST /stats/recipes/{recipeId}}.
 *
 * <p>
 * Runtime evaluation enforces strict separation between {@code parameters} and
 * {@code contextFilters}: {@code parameters} is fail-closed against the recipe's
 * {@code adjustableParams} catalog, whereas optional {@code contextFilters} carries ambient
 * scope or cross-filter criteria from the host cockpit, filtered strictly against the resource's
 * {@code FilterDTO} fields and always subordinated to explicit {@code parameters} and
 * server-side {@code lockedFilters}.
 * </p>
 */
public record VerifiedRecipeExecutionRequest(
        Map<String, Object> parameters,
        String timezone,
        Map<String, Object> contextFilters
) {
    public VerifiedRecipeExecutionRequest {
        parameters = sanitizeMap(parameters);
        timezone = timezone == null || timezone.isBlank() ? null : timezone.trim();
        contextFilters = sanitizeMap(contextFilters);
    }

    public VerifiedRecipeExecutionRequest(Map<String, Object> parameters, String timezone) {
        this(parameters, timezone, Map.of());
    }

    public VerifiedRecipeExecutionRequest() {
        this(Map.of(), null, Map.of());
    }

    private static Map<String, Object> sanitizeMap(Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> sanitized = new LinkedHashMap<>();
        raw.forEach((key, value) -> {
            if (key != null && !key.isBlank() && value != null) {
                sanitized.put(key.trim(), value);
            }
        });
        return sanitized.isEmpty() ? Map.of() : Collections.unmodifiableMap(sanitized);
    }
}
