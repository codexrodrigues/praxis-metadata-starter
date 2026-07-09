package org.praxisplatform.uischema.options.diagnostics;

/**
 * Sanitized diagnostic result for a provider-backed option source publication check.
 */
public record OptionSourcePublicationDiagnostic(
        String sourceKey,
        String catalogKey,
        OptionSourcePublicationStatus status,
        String expectedResourcePath,
        String publishedResourcePath,
        String message
) {
}
