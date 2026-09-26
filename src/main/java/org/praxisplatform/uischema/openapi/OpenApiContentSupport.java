package org.praxisplatform.uischema.openapi;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Shared content selection for documentary projection and strict JSON operation binding. */
public final class OpenApiContentSupport {
    private static final Pattern JSON_MEDIA_TYPE = Pattern.compile("application/json", Pattern.CASE_INSENSITIVE);
    private static final Pattern JSON_SUFFIX_MEDIA_TYPE = Pattern.compile(
            "application/[!#$%&'*+.^_`|~0-9A-Za-z-]+\\+json", Pattern.CASE_INSENSITIVE);

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
            throw new IllegalStateException("Canonical operation must declare JSON content");
        }
        List<String> candidates = new ArrayList<>();
        content.fieldNames().forEachRemaining(name -> {
            if (JSON_MEDIA_TYPE.matcher(name).matches() || JSON_SUFFIX_MEDIA_TYPE.matcher(name).matches()) {
                candidates.add(name);
            }
        });
        if (candidates.size() != 1) {
            throw new IllegalStateException("Canonical operation must declare one unambiguous JSON media type");
        }
        return candidates.get(0);
    }
}
