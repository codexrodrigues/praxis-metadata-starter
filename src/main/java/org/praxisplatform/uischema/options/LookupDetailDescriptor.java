package org.praxisplatform.uischema.options;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Detail navigation contract for an entity selected by a lookup.
 */
public record LookupDetailDescriptor(
        String hrefTemplate,
        String routeTemplate,
        String openDetailMode,
        String kind,
        String surfaceId,
        String presentation,
        String preferredWidget,
        String mode
) {

    public LookupDetailDescriptor(String hrefTemplate, String routeTemplate, String openDetailMode) {
        this(hrefTemplate, routeTemplate, openDetailMode, null, null, null, null, null);
    }

    public LookupDetailDescriptor {
        hrefTemplate = normalize(hrefTemplate);
        routeTemplate = normalize(routeTemplate);
        openDetailMode = normalize(openDetailMode);
        kind = normalize(kind);
        surfaceId = normalize(surfaceId);
        presentation = normalize(presentation);
        preferredWidget = normalize(preferredWidget);
        mode = normalize(mode);
    }

    public boolean isEmpty() {
        return hrefTemplate == null
                && routeTemplate == null
                && openDetailMode == null
                && kind == null
                && surfaceId == null
                && presentation == null
                && preferredWidget == null
                && mode == null;
    }

    public Map<String, Object> toMetadataMap() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (kind != null) {
            metadata.put("kind", kind);
        } else if (surfaceId != null) {
            metadata.put("kind", "surface");
        }
        if (surfaceId != null) {
            metadata.put("surfaceId", surfaceId);
        }
        if (presentation != null) {
            metadata.put("presentation", presentation);
        }
        if (preferredWidget != null) {
            metadata.put("preferredWidget", preferredWidget);
        }
        if (mode != null) {
            metadata.put("mode", mode);
        }
        if (hrefTemplate != null) {
            metadata.put("hrefTemplate", hrefTemplate);
        }
        if (routeTemplate != null) {
            metadata.put("routeTemplate", routeTemplate);
        }
        if (openDetailMode != null) {
            metadata.put("openDetailMode", openDetailMode);
        }
        return metadata;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
