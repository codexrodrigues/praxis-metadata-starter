package org.praxisplatform.uischema.stats.dto;

/**
 * Canonical response envelope for {@code POST /stats/recipes/{recipeId}}.
 * Contains the verified provenance stamp and exactly one typed statistical payload matching
 * the recipe's {@code operation}.
 */
public record VerifiedRecipeExecutionResponse(
        VerifiedRecipeProvenance provenance,
        GroupByStatsResponse groupBy,
        TimeSeriesStatsResponse timeSeries,
        DistributionStatsResponse distribution,
        ComparisonStatsResponse comparison
) { }
