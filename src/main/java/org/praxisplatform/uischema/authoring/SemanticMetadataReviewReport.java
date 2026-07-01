package org.praxisplatform.uischema.authoring;

import java.util.List;

/**
 * Review report for DTO semantic metadata authoring quality.
 */
public record SemanticMetadataReviewReport(
        Class<?> targetType,
        List<SemanticMetadataReviewIssue> issues
) {
    public SemanticMetadataReviewReport {
        issues = issues == null ? List.of() : List.copyOf(issues);
    }

    public boolean hasErrors() {
        return issues.stream().anyMatch(issue -> issue.severity() == SemanticMetadataIssueSeverity.ERROR);
    }
}
