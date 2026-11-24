package org.praxisplatform.uischema.stats.dto;

import org.praxisplatform.uischema.annotation.AnalyticalRecipeGovernanceTier;
import org.praxisplatform.uischema.annotation.AnalyticsOperation;

import java.util.List;
import java.util.Map;

/**
 * Deterministic provenance stamp returned alongside every verified analytical recipe execution.
 */
public record VerifiedRecipeProvenance(
        String recipeId,
        String title,
        String resourceKey,
        String version,
        AnalyticalRecipeGovernanceTier governanceTier,
        String provenanceDigest,
        AnalyticsOperation operation,
        Map<String, Object> lockedFilters,
        Map<String, Object> appliedParameters,
        List<String> adjustableParamNames,
        String endpointPath
) {
    public VerifiedRecipeProvenance {
        lockedFilters = lockedFilters == null ? Map.of() : Map.copyOf(lockedFilters);
        appliedParameters = appliedParameters == null ? Map.of() : Map.copyOf(appliedParameters);
        adjustableParamNames = adjustableParamNames == null ? List.of() : List.copyOf(adjustableParamNames);
    }
}
