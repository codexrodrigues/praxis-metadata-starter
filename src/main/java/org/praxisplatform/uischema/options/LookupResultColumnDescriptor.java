package org.praxisplatform.uischema.options;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Column definition rendered inside the advanced lookup selection dialog.
 */
public record LookupResultColumnDescriptor(
        String field,
        String label,
        String kind,
        String width
) {

    private static final Set<String> ALLOWED_KINDS = Set.of(
            "code", "label", "description", "status", "disabledReason", "custom"
    );

    public LookupResultColumnDescriptor(String field, String kind) {
        this(field, null, kind, null);
    }

    public LookupResultColumnDescriptor(String field, String label, String kind) {
        this(field, label, kind, null);
    }

    public LookupResultColumnDescriptor {
        field = normalize(field);
        if (field == null) {
            throw new IllegalArgumentException("Lookup result column field is required.");
        }
        label = normalize(label);
        kind = normalize(kind);
        if (kind != null && !ALLOWED_KINDS.contains(kind)) {
            throw new IllegalArgumentException("Unsupported lookup result column kind: " + kind);
        }
        width = normalize(width);
    }

    public Map<String, Object> toMetadataMap() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("field", field);
        if (label != null) {
            metadata.put("label", label);
        }
        if (kind != null) {
            metadata.put("kind", kind);
        }
        if (width != null) {
            metadata.put("width", width);
        }
        return metadata;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
