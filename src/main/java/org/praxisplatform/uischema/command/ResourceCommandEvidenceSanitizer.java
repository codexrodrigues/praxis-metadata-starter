package org.praxisplatform.uischema.command;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Removes private host details from command evidence before it becomes observable.
 */
public final class ResourceCommandEvidenceSanitizer {

    private static final Set<String> DEFAULT_FORBIDDEN_TOKENS = Set.of(
            "authorization",
            "cookie",
            "package",
            "password",
            "procedure",
            "rowid",
            "secret",
            "session",
            "sql",
            "token"
    );

    private final Set<String> forbiddenTokens;

    private ResourceCommandEvidenceSanitizer(Set<String> forbiddenTokens) {
        this.forbiddenTokens = forbiddenTokens == null || forbiddenTokens.isEmpty()
                ? DEFAULT_FORBIDDEN_TOKENS
                : Set.copyOf(forbiddenTokens);
    }

    public static ResourceCommandEvidenceSanitizer defaults() {
        return new ResourceCommandEvidenceSanitizer(DEFAULT_FORBIDDEN_TOKENS);
    }

    public static ResourceCommandEvidenceSanitizer withForbiddenTokens(Set<String> forbiddenTokens) {
        return new ResourceCommandEvidenceSanitizer(forbiddenTokens);
    }

    public Map<String, Object> sanitize(Map<String, Object> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> sanitized = new LinkedHashMap<>();
        evidence.forEach((key, value) -> {
            if (!isForbidden(key) && !isForbiddenValue(value)) {
                sanitized.put(key, value);
            }
        });
        return Map.copyOf(sanitized);
    }

    private boolean isForbidden(String key) {
        if (key == null || key.isBlank()) {
            return true;
        }
        String normalized = key.toLowerCase(Locale.ROOT);
        return forbiddenTokens.stream().anyMatch(normalized::contains);
    }

    private boolean isForbiddenValue(Object value) {
        if (!(value instanceof String text)) {
            return false;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        return forbiddenTokens.stream().anyMatch(normalized::contains);
    }
}
