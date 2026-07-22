package org.praxisplatform.uischema.options;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Public, provider-neutral interpretation accepted for a lookup search term. */
public record LookupSearchStrategyDefinition(String key, String kind, int minSearchChars) {
    private static final Set<String> ALLOWED_KINDS = Set.of(
            "business-code", "descriptive-text", "normalized-document");

    public LookupSearchStrategyDefinition {
        key = required(key, "Lookup search strategy key is required.");
        kind = required(kind, "Lookup search strategy kind is required.");
        if (!ALLOWED_KINDS.contains(kind)) {
            throw new IllegalArgumentException("Unsupported lookup search strategy kind: " + kind);
        }
        if (minSearchChars < 1) {
            throw new IllegalArgumentException("Lookup search strategy minSearchChars must be at least 1.");
        }
    }

    public Map<String, Object> toMetadataMap() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("key", key);
        metadata.put("kind", kind);
        metadata.put("minSearchChars", minSearchChars);
        return metadata;
    }

    private static String required(String value, String message) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(message);
        return value.trim();
    }
}
