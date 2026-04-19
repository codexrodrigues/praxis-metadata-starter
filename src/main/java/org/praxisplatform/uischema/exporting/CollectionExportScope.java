package org.praxisplatform.uischema.exporting;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * Escopo canonico de exportacao de uma colecao.
 */
public enum CollectionExportScope {
    AUTO("auto"),
    SELECTED("selected"),
    FILTERED("filtered"),
    CURRENT_PAGE("currentPage"),
    ALL("all");

    private final String value;

    CollectionExportScope(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static CollectionExportScope fromValue(String value) {
        if (value == null || value.isBlank()) {
            return AUTO;
        }
        String normalized = value.trim();
        for (CollectionExportScope scope : values()) {
            if (scope.value.equals(normalized) || scope.name().equalsIgnoreCase(normalized)) {
                return scope;
            }
        }
        String upper = normalized.replace('-', '_').toUpperCase(Locale.ROOT);
        for (CollectionExportScope scope : values()) {
            if (scope.name().equals(upper)) {
                return scope;
            }
        }
        throw new IllegalArgumentException("Unsupported collection export scope: " + value);
    }
}
