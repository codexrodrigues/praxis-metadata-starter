package org.praxisplatform.uischema.concurrency;

/**
 * Server-resolved isolation scope for a persisted resource version.
 *
 * <p>The value is an opaque, stable key assembled from trusted runtime context (for example,
 * tenant and environment). It is never inferred from the resource payload and must not contain
 * line breaks because it participates in the canonical ETag signature payload.</p>
 */
public record ResourceVersionScope(String value) {

    public static final ResourceVersionScope GLOBAL = new ResourceVersionScope("global");

    public ResourceVersionScope {
        if (value == null || value.isBlank() || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("Resource version scope must be non-blank and single-line.");
        }
        value = value.trim();
    }
}
