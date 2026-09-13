package org.praxisplatform.uischema.openapi;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/** Shared content selection for documentary projection and strict JSON request binding. */
public final class OpenApiContentSupport {
    private OpenApiContentSupport() { }

    /** Documentary preference: application/json, wildcard, then first declared media type. */
    public static String preferredMediaType(JsonNode content) {
        if (content == null || content.isMissingNode()) return null;
        if (!content.path("application/json").isMissingNode()) return "application/json";
        if (!content.path("*/*").isMissingNode()) return "*/*";
        var names = content.fieldNames();
        return names.hasNext() ? names.next() : null;
    }

    public static JsonNode preferredContent(JsonNode content) {
        String mediaType = preferredMediaType(content);
        if (mediaType != null) return content.path(mediaType);
        if (content == null) return null;
        var values = content.elements();
        return values.hasNext() ? values.next() : content;
    }

    /** JSON binding cannot infer a concrete JSON contract from wildcard or XML content. */
    static String requireJsonMediaType(JsonNode content) {
        if (content == null || !content.isObject()) {
            throw new IllegalStateException("Canonical request must declare JSON content");
        }
        List<String> candidates = new ArrayList<>();
        content.fieldNames().forEachRemaining(name -> {
            if ("application/json".equals(name) || (name.startsWith("application/") && name.endsWith("+json")
                    && name.indexOf('*') < 0 && name.indexOf(';') < 0 && name.indexOf(' ') < 0)) {
                candidates.add(name);
            }
        });
        if (candidates.size() != 1) {
            throw new IllegalStateException("Canonical request must declare one unambiguous JSON media type");
        }
        return candidates.get(0);
    }
}
