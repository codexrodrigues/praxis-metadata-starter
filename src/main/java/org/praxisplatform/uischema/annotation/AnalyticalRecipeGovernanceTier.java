package org.praxisplatform.uischema.annotation;

/**
 * Governance tier assigned to a {@link VerifiedAnalyticalRecipe}.
 *
 * <p>
 * Allows hosts and AI assistants to distinguish formally certified institutional indicators
 * from operational recipes and exploratory analytical definitions.
 * </p>
 */
public enum AnalyticalRecipeGovernanceTier {

    /** Formally certified recipe backed by verified institutional filters and aggregations. */
    CERTIFIED("CERTIFIED"),

    /** Operational recipe curated by the domain team for recurring management views. */
    OPERATIONAL("OPERATIONAL"),

    /** Exploratory pre-configured recipe without formal certification sign-off. */
    EXPLORATORY("EXPLORATORY");

    private final String wireValue;

    AnalyticalRecipeGovernanceTier(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}
