package org.praxisplatform.uischema.options;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Detail navigation contract for an entity selected by a lookup.
 */
public record LookupDetailDescriptor(
        String hrefTemplate,
        String routeTemplate,
        String openDetailMode
) {

    public LookupDetailDescriptor {
        hrefTemplate = normalize(hrefTemplate);
        routeTemplate = normalize(routeTemplate);
        openDetailMode = normalize(openDetailMode);
    }

    public boolean isEmpty() {
        return hrefTemplate == null && routeTemplate == null && openDetailMode == null;
    }

    public Map<String, Object> toMetadataMap() {
        Map<String, Object> metadata = new LinkedHashMap<>();
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
