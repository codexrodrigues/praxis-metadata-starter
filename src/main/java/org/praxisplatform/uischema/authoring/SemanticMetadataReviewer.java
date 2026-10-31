package org.praxisplatform.uischema.authoring;

import io.swagger.v3.oas.annotations.media.Schema;
import org.praxisplatform.uischema.annotation.DomainGovernance;
import org.praxisplatform.uischema.extension.annotation.UISchema;
import org.praxisplatform.uischema.extension.annotation.UISchemaPreset;
import org.praxisplatform.uischema.fact.ResourceFact;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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

    private static final Set<String> SENSITIVE_FIELD_TOKENS = Set.of(
            "cpf", "cnpj", "pis", "pasep", "nit", "ctps", "cnh", "passaporte",
            "senha", "password", "secret", "token", "apikey", "clientsecret", "privatekey",
            "cartao", "creditcard", "cvv", "contabancaria", "chavepix"
    );

    public SemanticMetadataReviewReport review(Class<?> targetType) {
        if (targetType == null) {
            return new SemanticMetadataReviewReport(null, List.of());
        }

        List<SemanticMetadataReviewIssue> issues = new ArrayList<>();
        java.util.Set<String> reviewedPropertyNames = new java.util.HashSet<>();
        Class<?> currentType = targetType;
        while (currentType != null && currentType != Object.class && currentType != Record.class) {
            for (Field field : currentType.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                    continue;
                }
                reviewedPropertyNames.add(field.getName());
                reviewField(targetType, currentType, field, issues);
            }
            for (Method method : currentType.getDeclaredMethods()) {
                if (Modifier.isStatic(method.getModifiers()) || method.isSynthetic() || method.isBridge() || method.getParameterCount() != 0) {
                    continue;
                }
                String propName = extractGetterPropertyName(method);
                if (propName == null || reviewedPropertyNames.contains(propName)) {
                    continue;
                }
                if (method.isAnnotationPresent(Schema.class)
                        || method.isAnnotationPresent(UISchema.class)
                        || method.isAnnotationPresent(DomainGovernance.class)
                        || method.isAnnotationPresent(org.praxisplatform.uischema.annotation.MicroVisualization.class)) {
                    reviewedPropertyNames.add(propName);
                    reviewGetterProperty(targetType, propName, method, issues);
                }
            }
            reviewResourceFactsOnType(targetType, currentType, issues);
            currentType = currentType.getSuperclass();
        }
        return new SemanticMetadataReviewReport(targetType, issues);
    }

    public SemanticMetadataReviewReport reviewResourceFacts(Class<?> targetType) {
        if (targetType == null) {
            return new SemanticMetadataReviewReport(null, List.of());
        }

        List<SemanticMetadataReviewIssue> issues = new ArrayList<>();
        Class<?> currentType = targetType;
        while (currentType != null && currentType != Object.class && currentType != Record.class) {
            reviewResourceFactsOnType(targetType, currentType, issues);
            currentType = currentType.getSuperclass();
        }
        return new SemanticMetadataReviewReport(targetType, issues);
    }

    private void reviewResourceFactsOnType(
            Class<?> targetType,
            Class<?> currentType,
            List<SemanticMetadataReviewIssue> issues
    ) {
        for (ResourceFact fact : currentType.getDeclaredAnnotationsByType(ResourceFact.class)) {
            reviewResourceFact(targetType, "@ResourceFact(" + normalize(fact.key()) + ")", fact, issues);
        }
        for (Method method : currentType.getDeclaredMethods()) {
            if (method.isSynthetic() || method.isBridge()) {
                continue;
            }
            for (ResourceFact fact : method.getDeclaredAnnotationsByType(ResourceFact.class)) {
                String factKey = normalize(fact.key());
                String memberName = factKey.isBlank() ? method.getName() : method.getName() + "@ResourceFact(" + factKey + ")";
                reviewResourceFact(targetType, memberName, fact, issues);
            }
        }
    }

    private void reviewResourceFact(
            Class<?> targetType,
            String memberName,
            ResourceFact fact,
            List<SemanticMetadataReviewIssue> issues
    ) {
        String label = normalize(fact.label());
        String description = normalize(fact.description());

        if (description.isBlank()) {
            issues.add(new SemanticMetadataReviewIssue(
                    SemanticMetadataIssueSeverity.ERROR,
                    "resource-fact-description-missing",
                    targetType.getName(),
                    memberName,
                    "@ResourceFact requires explicit business-domain description."
            ));
        }

        if (label.isBlank()) {
            issues.add(new SemanticMetadataReviewIssue(
                    SemanticMetadataIssueSeverity.ERROR,
                    "resource-fact-label-missing",
                    targetType.getName(),
                    memberName,
                    "@ResourceFact requires a short UI label (<= 36 chars, no trailing period)."
            ));
        } else if (label.length() > 36 || label.endsWith(".")) {
            issues.add(new SemanticMetadataReviewIssue(
                    SemanticMetadataIssueSeverity.ERROR,
                    "resource-fact-label-looks-like-sentence",
                    targetType.getName(),
                    memberName,
                    "@ResourceFact(label) exceeds 36 chars or ends with a period; move detailed explanation to description."
            ));
        }

        for (org.praxisplatform.uischema.annotation.Threshold threshold : fact.thresholds()) {
            boolean minUnset = Double.isNaN(threshold.min());
            boolean maxUnset = Double.isNaN(threshold.max());
            if (minUnset && maxUnset) {
                issues.add(new SemanticMetadataReviewIssue(
                        SemanticMetadataIssueSeverity.ERROR,
                        "resource-fact-threshold-bounds-missing",
                        targetType.getName(),
                        memberName,
                        "@ResourceFact threshold must declare at least min or max."
                ));
            } else if (!minUnset && !maxUnset && threshold.min() > threshold.max()) {
                issues.add(new SemanticMetadataReviewIssue(
                        SemanticMetadataIssueSeverity.ERROR,
                        "resource-fact-threshold-range-inverted",
                        targetType.getName(),
                        memberName,
                        "@ResourceFact threshold min (" + threshold.min() + ") cannot exceed max (" + threshold.max() + ")."
                ));
            }
            if (normalize(threshold.tone()).isBlank()) {
                issues.add(new SemanticMetadataReviewIssue(
                        SemanticMetadataIssueSeverity.ERROR,
                        "resource-fact-threshold-tone-missing",
                        targetType.getName(),
                        memberName,
                        "@ResourceFact threshold requires a non-blank semantic tone."
                ));
            }
        }
    }

    private void reviewField(Class<?> targetType, Class<?> declaringType, Field field, List<SemanticMetadataReviewIssue> issues) {
        Schema schema = findMemberAnnotation(declaringType, field, Schema.class);
        var ignored = findMemberAnnotation(declaringType, field, com.fasterxml.jackson.annotation.JsonIgnore.class);
        // Internal state must be excluded from both wire JSON and documentation.
        // Hiding a schema alone must not exempt an undocumented public property.
        if (schema != null && schema.hidden() && ignored != null && ignored.value()) return;
        UISchema uiSchema = findMemberAnnotation(declaringType, field, UISchema.class);
        DomainGovernance governance = findMemberAnnotation(declaringType, field, DomainGovernance.class);

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

        if (!label.isBlank() && (label.length() > 36 || label.endsWith("."))) {
            issues.add(issue(
                    SemanticMetadataIssueSeverity.ERROR,
                    "ui-label-looks-like-sentence",
                    targetType,
                    field,
                    "@UISchema(label) exceeds 36 chars or ends with a period; keep label concise and move explanation to helpText or @Schema(description)."
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
        } else if (looksLikeSensitiveOrCredentialField(field.getName()) && governance == null) {
            issues.add(issue(
                    SemanticMetadataIssueSeverity.WARNING,
                    "sensitive-field-without-governance",
                    targetType,
                    field,
                    "Field name looks like PII/sensitive/credential data. Declare @DomainGovernance explicitly for catalog and policy sidecar consumption."
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

    private String extractGetterPropertyName(Method method) {
        String name = method.getName();
        if (name.startsWith("get") && name.length() > 3 && Character.isUpperCase(name.charAt(3)) && !name.equals("getClass")) {
            return Character.toLowerCase(name.charAt(3)) + name.substring(4);
        }
        if (name.startsWith("is") && name.length() > 2 && Character.isUpperCase(name.charAt(2))
                && (method.getReturnType() == boolean.class || method.getReturnType() == Boolean.class)) {
            return Character.toLowerCase(name.charAt(2)) + name.substring(3);
        }
        return null;
    }

    private void reviewGetterProperty(Class<?> targetType, String propertyName, Method getter, List<SemanticMetadataReviewIssue> issues) {
        Schema schema = getter.getAnnotation(Schema.class);
        var ignored = getter.getAnnotation(com.fasterxml.jackson.annotation.JsonIgnore.class);
        if (schema != null && schema.hidden() && ignored != null && ignored.value()) {
            return;
        }
        UISchema uiSchema = getter.getAnnotation(UISchema.class);
        String description = schema == null ? "" : normalize(schema.description());
        String label = uiSchema == null ? "" : normalize(uiSchema.label());

        if (description.isBlank()) {
            issues.add(new SemanticMetadataReviewIssue(
                    SemanticMetadataIssueSeverity.ERROR,
                    "schema-description-missing",
                    targetType.getName(),
                    propertyName,
                    "Computed getter property requires explicit business-domain @Schema(description)."
            ));
        } else if (!label.isBlank() && sameMeaning(description, label)) {
            issues.add(new SemanticMetadataReviewIssue(
                    SemanticMetadataIssueSeverity.ERROR,
                    "schema-description-copies-ui-label",
                    targetType.getName(),
                    propertyName,
                    "@Schema(description) appears to repeat the UI label instead of explaining business meaning."
            ));
        }
        if (!label.isBlank() && (label.length() > 36 || label.endsWith("."))) {
            issues.add(new SemanticMetadataReviewIssue(
                    SemanticMetadataIssueSeverity.ERROR,
                    "ui-label-looks-like-sentence",
                    targetType.getName(),
                    propertyName,
                    "@UISchema(label) exceeds 36 chars or ends with a period; keep label concise and move explanation to helpText or @Schema(description)."
            ));
        }
    }

    private <A extends Annotation> A findMemberAnnotation(Class<?> declaringType, Field field, Class<A> annotationType) {
        A direct = field.getAnnotation(annotationType);
        if (direct != null) {
            return direct;
        }
        if (declaringType.isRecord() && declaringType.getRecordComponents() != null) {
            for (RecordComponent component : declaringType.getRecordComponents()) {
                if (component.getName().equals(field.getName())) {
                    A fromComponent = component.getAnnotation(annotationType);
                    if (fromComponent != null) {
                        return fromComponent;
                    }
                    Method accessor = component.getAccessor();
                    if (accessor != null) {
                        A fromAccessor = accessor.getAnnotation(annotationType);
                        if (fromAccessor != null) {
                            return fromAccessor;
                        }
                    }
                }
            }
        }
        try {
            Method accessor = declaringType.getDeclaredMethod(field.getName());
            A fromMethod = accessor.getAnnotation(annotationType);
            if (fromMethod != null) {
                return fromMethod;
            }
        } catch (NoSuchMethodException ignored) {
            // Not a record-style accessor; check standard JavaBean getter below.
        }
        String capitalized = field.getName().isEmpty()
                ? ""
                : Character.toUpperCase(field.getName().charAt(0)) + field.getName().substring(1);
        for (String prefix : List.of("get", "is")) {
            try {
                Method getter = declaringType.getDeclaredMethod(prefix + capitalized);
                A fromGetter = getter.getAnnotation(annotationType);
                if (fromGetter != null) {
                    return fromGetter;
                }
            } catch (NoSuchMethodException ignored) {
                // No getter with this prefix.
            }
        }
        return null;
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

    private boolean looksLikeSensitiveOrCredentialField(String fieldName) {
        String normalized = normalizeToken(fieldName);
        if (SENSITIVE_FIELD_TOKENS.contains(normalized)) {
            return true;
        }
        for (String token : List.of("cpf", "cnpj", "pasep", "senha", "password", "secret", "apikey", "clientsecret", "privatekey", "creditcard", "chavepix")) {
            if (normalized.contains(token)) {
                return true;
            }
        }
        return false;
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
