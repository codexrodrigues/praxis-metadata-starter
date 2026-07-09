package org.praxisplatform.uischema.options.diagnostics;

/**
 * Public, sanitized description of a provider-backed option source that should be published by a resource registry.
 *
 * <p>
 * The candidate is intentionally small: it identifies the public {@code sourceKey}, the expected public resource path
 * and a non-sensitive catalog key chosen by the host. It must not contain SQL, datasource, tenant, user, credential,
 * HADES locator, bind parameter or other private execution detail.
 * </p>
 */
public record OptionSourcePublicationCandidate(
        String sourceKey,
        String expectedResourcePath,
        String catalogKey
) {
    public OptionSourcePublicationCandidate {
        sourceKey = requirePublicValue(sourceKey, "sourceKey");
        expectedResourcePath = requirePublicValue(expectedResourcePath, "expectedResourcePath");
        catalogKey = normalizeCatalogKey(catalogKey);
    }

    public static OptionSourcePublicationCandidate of(
            String sourceKey,
            String expectedResourcePath,
            String catalogKey
    ) {
        return new OptionSourcePublicationCandidate(sourceKey, expectedResourcePath, catalogKey);
    }

    private static String requirePublicValue(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Option source publication candidate " + fieldName + " is required.");
        }
        return value.trim();
    }

    private static String normalizeCatalogKey(String value) {
        if (value == null || value.isBlank()) {
            return "provider-backed-option-source";
        }
        return value.trim();
    }
}
