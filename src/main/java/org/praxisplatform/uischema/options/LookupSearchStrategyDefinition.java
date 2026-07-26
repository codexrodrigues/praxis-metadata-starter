package org.praxisplatform.uischema.options;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Public, provider-neutral interpretation accepted for a lookup search term. */
public record LookupSearchStrategyDefinition(String key, String kind, int minSearchChars, String inputFormat) {
    private static final Set<String> ALLOWED_KINDS = Set.of(
            "business-code", "descriptive-text", "normalized-document");
    private static final Set<String> ALLOWED_INPUT_FORMATS = Set.of("text", "digits");

    /** Compatibility constructor for strategies whose input remains free text. */
    public LookupSearchStrategyDefinition(String key, String kind, int minSearchChars) {
        this(key, kind, minSearchChars, "text");
    }

    public LookupSearchStrategyDefinition {
        key = required(key, "Lookup search strategy key is required.");
        kind = required(kind, "Lookup search strategy kind is required.");
        inputFormat = required(inputFormat, "Lookup search strategy inputFormat is required.");
        if (!ALLOWED_KINDS.contains(kind)) {
            throw new IllegalArgumentException("Unsupported lookup search strategy kind: " + kind);
        }
        if (!ALLOWED_INPUT_FORMATS.contains(inputFormat)) {
            throw new IllegalArgumentException("Unsupported lookup search strategy inputFormat: " + inputFormat);
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
        if (!"text".equals(inputFormat)) {
            metadata.put("inputFormat", inputFormat);
        }
        return metadata;
    }

    /**
     * Normalizes the public term before it reaches a provider.
     *
     * <p>Only {@code normalized-document} has platform-wide normalization: it accepts
     * digits with common visual separators and forwards digits only. Validation of a
     * document's business checksum remains the responsibility of the owning domain.</p>
     */
    public String normalizeSearch(String value) {
        String normalized = required(value, "Lookup search term is required.");
        if ("digits".equals(inputFormat) && !"normalized-document".equals(kind)) {
            if (!normalized.chars().allMatch(LookupSearchStrategyDefinition::isAsciiDigit)) {
                throw new IllegalArgumentException("Lookup search strategy accepts digits only.");
            }
            return normalized;
        }
        if (!"normalized-document".equals(kind)) {
            return normalized;
        }
        StringBuilder digits = new StringBuilder(normalized.length());
        for (int index = 0; index < normalized.length(); index++) {
            char character = normalized.charAt(index);
            if (isAsciiDigit(character)) {
                digits.append(character);
            } else if (!Character.isWhitespace(character) && character != '.' && character != '-') {
                throw new IllegalArgumentException("Normalized document search accepts digits and visual separators only.");
            }
        }
        if (digits.isEmpty()) {
            throw new IllegalArgumentException("Normalized document search must contain at least one digit.");
        }
        return digits.toString();
    }

    private static boolean isAsciiDigit(int character) {
        return character >= '0' && character <= '9';
    }

    private static String required(String value, String message) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(message);
        return value.trim();
    }
}
