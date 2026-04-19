package org.praxisplatform.uischema.exporting;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * Formatos canonicos aceitos pela operacao de exportacao de colecao.
 */
public enum CollectionExportFormat {
    CSV("csv"),
    JSON("json"),
    EXCEL("excel"),
    PDF("pdf"),
    PRINT("print");

    private final String value;

    CollectionExportFormat(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static CollectionExportFormat fromValue(String value) {
        if (value == null || value.isBlank()) {
            return CSV;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (CollectionExportFormat format : values()) {
            if (format.value.equals(normalized) || format.name().equalsIgnoreCase(normalized)) {
                return format;
            }
        }
        throw new IllegalArgumentException("Unsupported collection export format: " + value);
    }
}
