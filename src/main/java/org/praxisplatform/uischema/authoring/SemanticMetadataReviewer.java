package org.praxisplatform.uischema.authoring;

import io.swagger.v3.oas.annotations.media.Schema;
import org.praxisplatform.uischema.annotation.DomainGovernance;
import org.praxisplatform.uischema.extension.annotation.UISchema;
import org.praxisplatform.uischema.extension.annotation.UISchemaPreset;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Lints DTO metadata without fabricating domain text.
 *
 * <p>
 * This reviewer is intentionally advisory. It catches common drift such as
 * missing domain descriptions, descriptions copied from UI labels and public
 * leakage of host-private context fields. It does not generate replacement
 * descriptions.
 * </p>
 */
public class SemanticMetadataReviewer {

    public SemanticMetadataReviewReport review(Class<?> targetType) {
        if (targetType == null) {
            return new SemanticMetadataReviewReport(null, List.of());
        }

        List<SemanticMetadataReviewIssue> issues = new ArrayList<>();
        Class<?> currentType = targetType;
        while (currentType != null && currentType != Object.class) {
            for (Field field : currentType.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                reviewField(targetType, field, issues);
            }
            currentType = currentType.getSuperclass();
        }
        return new SemanticMetadataReviewReport(targetType, issues);
    }

    private void reviewField(Class<?> targetType, Field field, List<SemanticMetadataReviewIssue> issues) {
        Schema schema = field.getAnnotation(Schema.class);
        UISchema uiSchema = field.getAnnotation(UISchema.class);
        DomainGovernance governance = field.getAnnotation(DomainGovernance.class);

        String description = schema == null ? "" : normalize(schema.description());
        String label = uiSchema == null ? "" : normalize(uiSchema.label());

        if (description.isBlank()) {
            issues.add(issue(
                    SemanticMetadataIssueSeverity.ERROR,
                    "schema-description-missing",
                    targetType,
                    field,
                    "Field requires explicit business-domain @Schema(description)."
            ));
        } else if (!label.isBlank() && sameMeaning(description, label)) {
            issues.add(issue(
                    SemanticMetadataIssueSeverity.ERROR,
                    "schema-description-copies-ui-label",
                    targetType,
                    field,
                    "@Schema(description) appears to repeat the UI label instead of explaining business meaning."
            ));
        } else if (sameMeaning(description, splitCamelCase(field.getName()))) {
            issues.add(issue(
                    SemanticMetadataIssueSeverity.ERROR,
                    "schema-description-derived-from-field-name",
                    targetType,
                    field,
                    "@Schema(description) appears derived from the Java field name."
            ));
        }

        if (looksLikePrivateContextField(field.getName()) && governance == null) {
            issues.add(issue(
                    SemanticMetadataIssueSeverity.WARNING,
                    "public-private-context-field-without-governance",
                    targetType,
                    field,
                    "Field name looks like private tenant/user/session context. Add governance or move it out of the public DTO."
            ));
        }

        if (uiSchema != null && uiSchema.preset() != UISchemaPreset.NONE && description.isBlank()) {
            issues.add(issue(
                    SemanticMetadataIssueSeverity.ERROR,
                    "preset-without-domain-description",
                    targetType,
                    field,
                    "UI presentation preset does not replace explicit domain documentation."
            ));
        }
    }

    private SemanticMetadataReviewIssue issue(
            SemanticMetadataIssueSeverity severity,
            String code,
            Class<?> targetType,
            Field field,
            String message
    ) {
        return new SemanticMetadataReviewIssue(
                severity,
                code,
                targetType.getName(),
                field.getName(),
                message
        );
    }

    private boolean looksLikePrivateContextField(String fieldName) {
        String normalized = normalizeToken(fieldName);
        return normalized.equals("tenantid")
                || normalized.equals("userid")
                || normalized.equals("principalid")
                || normalized.equals("sessionid")
                || normalized.equals("contextid")
                || normalized.equals("requestcontext")
                || normalized.equals("securitycontext");
    }

    private boolean sameMeaning(String left, String right) {
        return !left.isBlank() && normalizeToken(left).equals(normalizeToken(right));
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private String normalizeToken(String value) {
        return normalize(value)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]", "");
    }

    private String splitCamelCase(String value) {
        return normalize(value)
                .replaceAll("([a-z0-9])([A-Z])", "$1 $2")
                .replace('_', ' ')
                .replace('-', ' ');
    }
}
