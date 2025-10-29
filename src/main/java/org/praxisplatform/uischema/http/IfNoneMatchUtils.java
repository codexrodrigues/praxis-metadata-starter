package org.praxisplatform.uischema.http;

import java.util.ArrayList;
import java.util.List;

/**
 * Parser and matcher for HTTP If-None-Match headers using strong ETag comparison.
 */
public final class IfNoneMatchUtils {

    private IfNoneMatchUtils() {}

    /**
     * Returns true if the If-None-Match header matches the provided strong ETag.
     * Strong-only comparison: weak validators (W/...) are ignored as non-matching for hash equality.
     */
    public static boolean matches(String ifNoneMatchHeader, String currentETag) {
        if (ifNoneMatchHeader == null || ifNoneMatchHeader.isBlank()) return false;
        String h = ifNoneMatchHeader.trim();
        if ("*".equals(h)) return true;

        for (String part : splitComma(h)) {
            String tag = part.trim();
            if (tag.isEmpty()) continue;
            // Ignore weak ETags for strong comparison
            if (tag.startsWith("W/")) continue;
            if (tag.equals(currentETag)) return true;
        }
        return false;
    }

    private static List<String> splitComma(String header) {
        List<String> out = new ArrayList<>();
        int start = 0;
        int depth = 0; // not strictly needed, but safe for quoted commas
        for (int i = 0; i < header.length(); i++) {
            char c = header.charAt(i);
            if (c == ',') {
                out.add(header.substring(start, i));
                start = i + 1;
            }
        }
        out.add(header.substring(start));
        return out;
    }
}

