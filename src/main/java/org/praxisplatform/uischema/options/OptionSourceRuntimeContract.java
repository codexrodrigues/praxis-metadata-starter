package org.praxisplatform.uischema.options;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Public runtime contract projected for an option source.
 */
public record OptionSourceRuntimeContract(
        String filterEndpoint,
        String byIdsEndpoint,
        OptionSourceSelectedReloadPolicy selectedReloadPolicy,
        OptionSourceInvalidSortPolicy invalidSortPolicy
) {
    public OptionSourceRuntimeContract {
        filterEndpoint = normalize(filterEndpoint);
        byIdsEndpoint = normalize(byIdsEndpoint);
        selectedReloadPolicy = selectedReloadPolicy == null
                ? inferSelectedReloadPolicy(byIdsEndpoint)
                : selectedReloadPolicy;
        invalidSortPolicy = invalidSortPolicy == null
                ? OptionSourceInvalidSortPolicy.REJECT
                : invalidSortPolicy;
    }

    public static OptionSourceRuntimeContract canonical(String resourcePath, String sourceKey) {
        String base = normalize(resourcePath);
        if (base == null || sourceKey == null || sourceKey.isBlank()) {
            return new OptionSourceRuntimeContract(null, null, null, null);
        }
        String optionSourceBase = base + "/option-sources/" + sourceKey.trim();
        return new OptionSourceRuntimeContract(
                optionSourceBase + "/options/filter",
                optionSourceBase + "/options/by-ids",
                OptionSourceSelectedReloadPolicy.REQUIRED,
                OptionSourceInvalidSortPolicy.REJECT
        );
    }

    public OptionSourceRuntimeContract withSelectedReloadPolicy(OptionSourceSelectedReloadPolicy policy) {
        return new OptionSourceRuntimeContract(filterEndpoint, byIdsEndpoint, policy, invalidSortPolicy);
    }

    public OptionSourceRuntimeContract withInvalidSortPolicy(OptionSourceInvalidSortPolicy policy) {
        return new OptionSourceRuntimeContract(filterEndpoint, byIdsEndpoint, selectedReloadPolicy, policy);
    }

    public Map<String, Object> toMetadataMap() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (filterEndpoint != null) {
            metadata.put("filterEndpoint", filterEndpoint);
        }
        if (byIdsEndpoint != null) {
            metadata.put("byIdsEndpoint", byIdsEndpoint);
        }
        metadata.put("selectedReloadPolicy", selectedReloadPolicy.wireValue());
        metadata.put("invalidSortPolicy", invalidSortPolicy.wireValue());
        return metadata;
    }

    private static OptionSourceSelectedReloadPolicy inferSelectedReloadPolicy(String byIdsEndpoint) {
        return byIdsEndpoint == null || byIdsEndpoint.isBlank()
                ? OptionSourceSelectedReloadPolicy.UNSUPPORTED_WITH_WAIVER
                : OptionSourceSelectedReloadPolicy.REQUIRED;
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        if (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
