package org.praxisplatform.uischema.openapi;

/** Shared immutable patterns for structural path comparison, never a route authority. */
final class OpenApiPathPatterns {
    static final java.util.regex.Pattern SLASHES = java.util.regex.Pattern.compile("/+");
    static final java.util.regex.Pattern PARAMETER = java.util.regex.Pattern.compile("\\{[^}/]+}");
    private OpenApiPathPatterns() { }
}
