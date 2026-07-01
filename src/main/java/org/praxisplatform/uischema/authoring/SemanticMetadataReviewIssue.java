package org.praxisplatform.uischema.authoring;

/**
 * Finding emitted by the semantic metadata reviewer.
 */
public record SemanticMetadataReviewIssue(
        SemanticMetadataIssueSeverity severity,
        String code,
        String className,
        String fieldName,
        String message
) {
}
