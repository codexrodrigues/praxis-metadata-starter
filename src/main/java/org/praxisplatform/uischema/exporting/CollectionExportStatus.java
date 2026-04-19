package org.praxisplatform.uischema.exporting;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * Estado canonico do resultado de uma exportacao de colecao.
 */
public enum CollectionExportStatus {
    COMPLETED("completed"),
    DEFERRED("deferred");

    private final String value;

    CollectionExportStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static CollectionExportStatus fromValue(String value) {
        if (value == null || value.isBlank()) {
            return COMPLETED;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (CollectionExportStatus status : values()) {
            if (status.value.equals(normalized) || status.name().equalsIgnoreCase(normalized)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unsupported collection export status: " + value);
    }
}
