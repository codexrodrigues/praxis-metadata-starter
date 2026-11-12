package org.praxisplatform.uischema.authoring;

import io.swagger.v3.oas.annotations.media.Schema;
import org.praxisplatform.uischema.annotation.DomainGovernance;
import org.praxisplatform.uischema.annotation.DomainLineage;
import org.praxisplatform.uischema.extension.annotation.UISchema;
import org.praxisplatform.uischema.extension.annotation.UISchemaPreset;
import org.praxisplatform.uischema.fact.ResourceFact;
import org.praxisplatform.uischema.options.OptionSourceDescriptor;
import org.praxisplatform.uischema.options.OptionSourceEligibility;
import org.praxisplatform.uischema.schema.DomainLineageResolver;
import org.praxisplatform.uischema.stats.StatsFieldRegistry;

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
            "cpf", "cnpj", "pis", "pasep", "nis", "nit", "ctps", "cnh", "passaporte", "tituloeleitoral",
            "senha", "password", "secret", "token", "apikey", "clientsecret", "privatekey",
            "cartao", "creditcard", "cvv", "contabancaria", "chavepix",
            "raca", "etnia", "deficiente", "deffisica", "defvisual", "defauditiva", "defmental",
            "defintelectual", "obsdeficiencia", "tipodeficiencia", "gruposanguineo", "cid",
            "molestia", "laudo", "filiacaosindical", "biometria",
            "remuneracao", "subsidio", "provento", "pensao", "consignado"
    );

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private static final Set<String> AUDIT_EXCLUDED_IDENTITY_FIELDS = Set.of(
            "id", "createdAt", "updatedAt", "createdBy", "updatedBy",
            "version", "rowVersion", "resourceVersion",
            "criadoEm", "atualizadoEm", "criadoPor", "atualizadoPor", "auditoria"
    );

    public SemanticMetadataReviewReport review(Class<?> targetType) {
        return review(targetType, List.of(), null);
    }

    /**
     * Reviews the DTO enforcing blocking {@link SemanticMetadataIssueSeverity#ERROR} when
     * private-context or sensitive/LGPD/credential properties lack explicit {@link DomainGovernance}.
     */
    public SemanticMetadataReviewReport reviewStrictGovernance(Class<?> targetType) {
        return reviewStrictGovernance(targetType, List.of(), null);
    }

    /**
     * Reviews the DTO and its resource-owned sources enforcing blocking {@link SemanticMetadataIssueSeverity#ERROR}
     * when private-context or sensitive/LGPD/credential properties lack explicit {@link DomainGovernance}.
     */
    public SemanticMetadataReviewReport reviewStrictGovernance(
            Class<?> targetType,
            Iterable<OptionSourceDescriptor> sources,
            StatsFieldRegistry statsFields
    ) {
        SemanticMetadataReviewReport base = review(targetType, sources, statsFields);
        List<SemanticMetadataReviewIssue> promoted = base.issues().stream()
                .map(item -> ("public-private-context-field-without-governance".equals(item.code())
                        || "sensitive-field-without-governance".equals(item.code()))
                        ? new SemanticMetadataReviewIssue(
                                SemanticMetadataIssueSeverity.ERROR,
                                item.code(),
                                item.className(),
                                item.fieldName(),
                                item.message())
                        : item)
                .toList();
        return new SemanticMetadataReviewReport(base.targetType(), promoted);
    }

    /** Reviews the DTO and its resource-owned sources against the same eligibility used at execution. */
    public SemanticMetadataReviewReport review(Class<?> targetType, Iterable<OptionSourceDescriptor> sources,
            StatsFieldRegistry statsFields) {
        if (targetType == null) {
            return new SemanticMetadataReviewReport(null, List.of());
        }

        List<SemanticMetadataReviewIssue> issues = new ArrayList<>();
        java.util.Map<String, Field> fieldsByName = new java.util.LinkedHashMap<>();
        java.util.Set<String> allPropertyNames = new java.util.LinkedHashSet<>();
        java.util.Map<String, Method> annotatedGetterProperties = new java.util.LinkedHashMap<>();

        Class<?> currentType = targetType;
        while (currentType != null && currentType != Object.class && currentType != Record.class) {
            for (Field field : currentType.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                    continue;
                }
                fieldsByName.putIfAbsent(field.getName(), field);
                allPropertyNames.add(field.getName());
            }
            for (Method method : currentType.getDeclaredMethods()) {
                if (Modifier.isStatic(method.getModifiers()) || method.isSynthetic() || method.isBridge() || method.getParameterCount() != 0) {
                    continue;
                }
                String propName = extractGetterPropertyName(method);
                if (propName == null) {
                    continue;
                }
                allPropertyNames.add(propName);
                if (hasSemanticAnnotation(method)) {
                    annotatedGetterProperties.putIfAbsent(propName, method);
                }
            }
            reviewResourceFactsOnType(targetType, currentType, issues);
            currentType = currentType.getSuperclass();
        }

        java.util.Set<String> quickViewVisibleFields = new java.util.LinkedHashSet<>();
        java.util.Set<String> hiddenTechnicalFields = new java.util.LinkedHashSet<>();
        java.util.Set<String> nonScalarFields = new java.util.LinkedHashSet<>();
        java.util.Map<String, org.praxisplatform.uischema.annotation.ResourceReference> resourceReferencesByProperty =
                new java.util.LinkedHashMap<>();
        java.util.Map<String, java.util.Set<String>> groupsByLowerName = new java.util.LinkedHashMap<>();
        java.util.Map<String, String> firstPropertyByLowerGroup = new java.util.LinkedHashMap<>();

        for (Field field : fieldsByName.values()) {
            String propName = field.getName();
            if (java.util.Collection.class.isAssignableFrom(field.getType())
                    || java.util.Map.class.isAssignableFrom(field.getType())) {
                nonScalarFields.add(propName);
            }
            UISchema ui = findMemberAnnotation(targetType, field, UISchema.class);
            Schema schema = findMemberAnnotation(targetType, field, Schema.class);
            com.fasterxml.jackson.annotation.JsonIgnore jsonIgnore =
                    findMemberAnnotation(targetType, field, com.fasterxml.jackson.annotation.JsonIgnore.class);
            if ((ui != null && ui.readOnly() && ui.formHidden() && ui.tableHidden())
                    || (schema != null && schema.hidden())
                    || jsonIgnore != null) {
                hiddenTechnicalFields.add(propName);
            }
            if (ui != null) {
                if (ui.quickViewVisible()) {
                    quickViewVisibleFields.add(propName);
                }
                String group = normalize(ui.group());
                if (!group.isBlank()) {
                    String groupKey = normalizeGroupKey(group);
                    groupsByLowerName.computeIfAbsent(groupKey, ignored -> new java.util.LinkedHashSet<>()).add(group);
                    firstPropertyByLowerGroup.putIfAbsent(groupKey, propName);
                }
            }
            org.praxisplatform.uischema.annotation.ResourceReference ref =
                    findMemberAnnotation(targetType, field, org.praxisplatform.uischema.annotation.ResourceReference.class);
            if (ref != null) {
                resourceReferencesByProperty.putIfAbsent(propName, ref);
            }
            reviewField(targetType, targetType, field, issues);
        }

        for (var entry : annotatedGetterProperties.entrySet()) {
            String propName = entry.getKey();
            if (fieldsByName.containsKey(propName)) {
                continue;
            }
            if (java.util.Collection.class.isAssignableFrom(entry.getValue().getReturnType())
                    || java.util.Map.class.isAssignableFrom(entry.getValue().getReturnType())) {
                nonScalarFields.add(propName);
            }
            UISchema ui = findGetterAnnotation(targetType, propName, UISchema.class);
            Schema schema = findGetterAnnotation(targetType, propName, Schema.class);
            com.fasterxml.jackson.annotation.JsonIgnore jsonIgnore =
                    findGetterAnnotation(targetType, propName, com.fasterxml.jackson.annotation.JsonIgnore.class);
            if ((ui != null && ui.readOnly() && ui.formHidden() && ui.tableHidden())
                    || (schema != null && schema.hidden())
                    || jsonIgnore != null) {
                hiddenTechnicalFields.add(propName);
            }
            if (ui != null) {
                if (ui.quickViewVisible()) {
                    quickViewVisibleFields.add(propName);
                }
                String group = normalize(ui.group());
                if (!group.isBlank()) {
                    String groupKey = normalizeGroupKey(group);
                    groupsByLowerName.computeIfAbsent(groupKey, ignored -> new java.util.LinkedHashSet<>()).add(group);
                    firstPropertyByLowerGroup.putIfAbsent(groupKey, propName);
                }
            }
            org.praxisplatform.uischema.annotation.ResourceReference ref =
                    findGetterAnnotation(targetType, propName, org.praxisplatform.uischema.annotation.ResourceReference.class);
            if (ref != null) {
                resourceReferencesByProperty.putIfAbsent(propName, ref);
            }
            reviewGetterProperty(targetType, propName, issues);
        }

        for (var groupEntry : groupsByLowerName.entrySet()) {
            if (groupEntry.getValue().size() > 1) {
                issues.add(new SemanticMetadataReviewIssue(
                        SemanticMetadataIssueSeverity.ERROR,
                        "uischema-group-case-divergence",
                        targetType.getName(),
                        firstPropertyByLowerGroup.get(groupEntry.getKey()),
                        "@UISchema(group) declares case-divergent variants " + groupEntry.getValue()
                                + " on " + targetType.getSimpleName() + "; unify into a single canonical group name."
                ));
            }
        }

        boolean isFilterDto = org.praxisplatform.uischema.filter.dto.GenericFilterDTO.class.isAssignableFrom(targetType)
                || targetType.getSimpleName().endsWith("FilterDTO")
                || targetType.getSimpleName().endsWith("Filter");
        if (!isFilterDto) {
            List<String> sortedGroupKeys = new ArrayList<>(groupsByLowerName.keySet());
            java.util.Collections.sort(sortedGroupKeys);
            for (String groupKey : sortedGroupKeys) {
                if (groupKey.contains(" ") || groupKey.length() < 4) {
                    continue;
                }
                List<String> collidingSpecificGroups = new ArrayList<>();
                for (String otherKey : sortedGroupKeys) {
                    if (!otherKey.equals(groupKey) && otherKey.startsWith(groupKey + " ")) {
                        collidingSpecificGroups.add(groupsByLowerName.get(otherKey).iterator().next());
                    }
                }
                if (!collidingSpecificGroups.isEmpty()) {
                    String genericGroup = groupsByLowerName.get(groupKey).iterator().next();
                    issues.add(new SemanticMetadataReviewIssue(
                            SemanticMetadataIssueSeverity.ERROR,
                            "uischema-group-prefix-collision",
                            targetType.getName(),
                            firstPropertyByLowerGroup.get(groupKey),
                            "@UISchema(group) declares generic single-word group '" + genericGroup
                                    + "' that collides by prefix with " + collidingSpecificGroups
                                    + " on " + targetType.getSimpleName() + "; unify into the specific group name."
                    ));
                }
            }
        }

        reviewQuickViewAndResourceIdentity(
                targetType,
                allPropertyNames,
                quickViewVisibleFields,
                hiddenTechnicalFields,
                nonScalarFields,
                resourceReferencesByProperty,
                issues
        );
        var eligibility = new OptionSourceEligibility();
        for (OptionSourceDescriptor source : sources) {
            try {
                eligibility.resolveEffectiveDescriptor(source, statsFields);
            } catch (IllegalArgumentException ex) {
                issues.add(new SemanticMetadataReviewIssue(SemanticMetadataIssueSeverity.ERROR,
                        "option-source-ineligible", targetType.getName(), source == null ? null : source.key(),
                        ex.getMessage()));
            }
        }
        reviewRestrictedFieldsInAnalyticsAndOptionSources(
                targetType,
                fieldsByName,
                annotatedGetterProperties,
                sources,
                statsFields,
                issues
        );
        return new SemanticMetadataReviewReport(targetType, issues);
    }

    private void reviewRestrictedFieldsInAnalyticsAndOptionSources(
            Class<?> targetType,
            java.util.Map<String, Field> fieldsByName,
            java.util.Map<String, Method> annotatedGetterProperties,
            Iterable<OptionSourceDescriptor> sources,
            StatsFieldRegistry statsFields,
            List<SemanticMetadataReviewIssue> issues
    ) {
        if ((sources == null || !sources.iterator().hasNext()) && (statsFields == null || statsFields.isEmpty())) {
            return;
        }
        java.util.Map<String, com.fasterxml.jackson.databind.node.ObjectNode> restrictedProperties =
                new java.util.LinkedHashMap<>();
        java.util.Map<String, String> aliasToRestrictedProperty = new java.util.LinkedHashMap<>();
        com.fasterxml.jackson.databind.node.ObjectNode typeGov = DomainLineageResolver.resolveTypeGovernance(targetType);

        for (var entry : fieldsByName.entrySet()) {
            String propName = entry.getKey();
            Field field = entry.getValue();
            DomainGovernance memberGov = findMemberAnnotation(targetType, field, DomainGovernance.class);
            DomainLineage lineage = findMemberAnnotation(targetType, field, DomainLineage.class);
            org.praxisplatform.uischema.filter.annotation.Filterable filterable =
                    findMemberAnnotation(targetType, field, org.praxisplatform.uischema.filter.annotation.Filterable.class);
            com.fasterxml.jackson.databind.node.ObjectNode effective =
                    resolveEffectiveGovernanceQuietly(lineage, memberGov, typeGov);
            if (isRestrictedForAggregation(effective)) {
                restrictedProperties.put(propName, effective);
                registerRestrictedAliases(aliasToRestrictedProperty, propName, lineage, filterable);
            }
        }
        for (String propName : annotatedGetterProperties.keySet()) {
            if (restrictedProperties.containsKey(propName)) {
                continue;
            }
            DomainGovernance memberGov = findGetterAnnotation(targetType, propName, DomainGovernance.class);
            DomainLineage lineage = findGetterAnnotation(targetType, propName, DomainLineage.class);
            org.praxisplatform.uischema.filter.annotation.Filterable filterable =
                    findGetterAnnotation(targetType, propName, org.praxisplatform.uischema.filter.annotation.Filterable.class);
            com.fasterxml.jackson.databind.node.ObjectNode effective =
                    resolveEffectiveGovernanceQuietly(lineage, memberGov, typeGov);
            if (isRestrictedForAggregation(effective)) {
                restrictedProperties.put(propName, effective);
                registerRestrictedAliases(aliasToRestrictedProperty, propName, lineage, filterable);
            }
        }
        if (restrictedProperties.isEmpty()) {
            return;
        }

        if (statsFields != null && !statsFields.isEmpty()) {
            for (org.praxisplatform.uischema.stats.StatsFieldDescriptor descriptor : statsFields.descriptors()) {
                if (descriptor == null || (!descriptor.groupByEligible() && !descriptor.distributionTermsEligible())) {
                    continue;
                }
                String matchedProp = matchRestrictedProperty(
                        aliasToRestrictedProperty,
                        descriptor.field(),
                        descriptor.keyPropertyPath(),
                        descriptor.labelPropertyPath()
                );
                if (matchedProp != null) {
                    com.fasterxml.jackson.databind.node.ObjectNode gov = restrictedProperties.get(matchedProp);
                    issues.add(new SemanticMetadataReviewIssue(
                            SemanticMetadataIssueSeverity.ERROR,
                            "restricted-field-in-analytics-or-option-source",
                            targetType.getName(),
                            matchedProp,
                            "Property '" + matchedProp + "' is governed as restricted/sensitive (classification="
                                    + gov.path("classification").asText()
                                    + ", dataCategory=" + gov.path("dataCategory").asText()
                                    + ") and cannot be exposed as a categorical group-by or terms-distribution bucket in StatsFieldRegistry ('"
                                    + descriptor.field() + "')."
                    ));
                }
            }
        }

        if (sources != null) {
            for (OptionSourceDescriptor source : sources) {
                if (source == null || (source.type() != org.praxisplatform.uischema.options.OptionSourceType.DISTINCT_DIMENSION
                        && source.type() != org.praxisplatform.uischema.options.OptionSourceType.CATEGORICAL_BUCKET)) {
                    continue;
                }
                String matchedProp = matchRestrictedProperty(
                        aliasToRestrictedProperty,
                        source.key(),
                        source.filterField(),
                        source.propertyPath(),
                        source.labelPropertyPath(),
                        source.valuePropertyPath()
                );
                if (matchedProp != null) {
                    com.fasterxml.jackson.databind.node.ObjectNode gov = restrictedProperties.get(matchedProp);
                    issues.add(new SemanticMetadataReviewIssue(
                            SemanticMetadataIssueSeverity.ERROR,
                            "restricted-field-in-analytics-or-option-source",
                            targetType.getName(),
                            matchedProp,
                            "Property '" + matchedProp + "' is governed as restricted/sensitive (classification="
                                    + gov.path("classification").asText()
                                    + ", dataCategory=" + gov.path("dataCategory").asText()
                                    + ") and cannot be exposed as a " + source.type()
                                    + " option source ('" + source.key() + "')."
                    ));
                }
            }
        }
    }

    private static final Set<String> GENERIC_HOMONYM_LEAF_NAMES = Set.of(
            "id", "codigo", "nome", "descricao", "titulo", "tipo", "situacao", "status",
            "valor", "data", "numero", "sigla", "chave", "observacao", "ativo",
            "categoria", "classe", "nivel", "referencia", "padrao", "regime", "origem", "motivo"
    );

    private void registerRestrictedAliases(
            java.util.Map<String, String> aliasToRestrictedProperty,
            String propName,
            DomainLineage lineage,
            org.praxisplatform.uischema.filter.annotation.Filterable filterable
    ) {
        aliasToRestrictedProperty.put(propName, propName);
        if (lineage != null && lineage.sourcePaths() != null && lineage.sourcePaths().length == 1) {
            registerSinglePathAlias(aliasToRestrictedProperty, propName, lineage.sourcePaths()[0]);
        }
        if (filterable != null && (filterable.relations() == null || filterable.relations().length == 0)) {
            registerSinglePathAlias(aliasToRestrictedProperty, propName, filterable.relation());
        }
    }

    private void registerSinglePathAlias(
            java.util.Map<String, String> aliasToRestrictedProperty,
            String propName,
            String rawPath
    ) {
        String normalized = normalize(rawPath);
        if (normalized.isBlank()) {
            return;
        }
        int lastDot = normalized.lastIndexOf('.');
        if (lastDot < 0) {
            if (!GENERIC_HOMONYM_LEAF_NAMES.contains(normalizeToken(normalized))) {
                aliasToRestrictedProperty.putIfAbsent(normalized, propName);
            }
            return;
        }
        aliasToRestrictedProperty.putIfAbsent(normalized, propName);
        if (lastDot + 1 < normalized.length()) {
            String leaf = normalized.substring(lastDot + 1);
            if (!GENERIC_HOMONYM_LEAF_NAMES.contains(normalizeToken(leaf))) {
                aliasToRestrictedProperty.putIfAbsent(leaf, propName);
            }
        }
    }

    private com.fasterxml.jackson.databind.node.ObjectNode resolveEffectiveGovernanceQuietly(
            DomainLineage lineage,
            DomainGovernance memberGov,
            com.fasterxml.jackson.databind.node.ObjectNode typeGov
    ) {
        if (lineage != null) {
            try {
                DomainLineageResolver.ResolvedLineage resolved =
                        DomainLineageResolver.resolve(lineage, memberGov, typeGov);
                return resolved != null ? resolved.effectiveGovernance() : null;
            } catch (IllegalArgumentException ignored) {
                // Fallback below when @DomainLineage is invalid
            }
        }
        com.fasterxml.jackson.databind.node.ObjectNode memberNode =
                memberGov != null ? DomainLineageResolver.toGovernanceNode(memberGov) : null;
        try {
            return org.praxisplatform.uischema.schema.DomainSchemaGovernance.mergeOrCopy(typeGov, memberNode);
        } catch (IllegalArgumentException ignored) {
            return memberNode != null ? memberNode : typeGov;
        }
    }

    private boolean isRestrictedForAggregation(com.fasterxml.jackson.databind.node.ObjectNode gov) {
        if (gov == null) {
            return false;
        }
        String classification = gov.path("classification").asText("");
        String dataCategory = gov.path("dataCategory").asText("");
        String aiVisibility = gov.path("aiUsage").path("visibility").asText("");
        boolean exportable = gov.path("exportable").asBoolean(true);
        return "restricted".equals(classification)
                || "sensitive_personal".equals(dataCategory)
                || "credential".equals(dataCategory)
                || "deny".equals(aiVisibility)
                || ("personal".equals(dataCategory) && !exportable);
    }

    private String matchRestrictedProperty(java.util.Map<String, String> aliasToRestrictedProperty, String... candidates) {
        if (candidates == null) {
            return null;
        }
        for (String candidate : candidates) {
            String normalized = normalize(candidate);
            if (normalized.isBlank()) {
                continue;
            }
            String direct = aliasToRestrictedProperty.get(normalized);
            if (direct != null) {
                return direct;
            }
            int lastDot = normalized.lastIndexOf('.');
            if (lastDot >= 0 && lastDot + 1 < normalized.length()) {
                String leaf = normalized.substring(lastDot + 1);
                if (!GENERIC_HOMONYM_LEAF_NAMES.contains(normalizeToken(leaf))) {
                    String byLeaf = aliasToRestrictedProperty.get(leaf);
                    if (byLeaf != null) {
                        return byLeaf;
                    }
                }
            }
        }
        return null;
    }

    private boolean hasSemanticAnnotation(Method method) {
        Annotation[] annotations = method.getAnnotations();
        return org.praxisplatform.uischema.extension.ResolverUtils.getAnnotation(Schema.class, annotations) != null
                || org.praxisplatform.uischema.extension.ResolverUtils.getAnnotation(UISchema.class, annotations) != null
                || org.praxisplatform.uischema.extension.ResolverUtils.getAnnotation(DomainGovernance.class, annotations) != null
                || org.praxisplatform.uischema.extension.ResolverUtils.getAnnotation(DomainLineage.class, annotations) != null
                || org.praxisplatform.uischema.extension.ResolverUtils.getAnnotation(org.praxisplatform.uischema.annotation.MicroVisualization.class, annotations) != null
                || org.praxisplatform.uischema.extension.ResolverUtils.getAnnotation(org.praxisplatform.uischema.annotation.ResourceReference.class, annotations) != null;
    }

    private void reviewQuickViewAndResourceIdentity(
            Class<?> targetType,
            Set<String> reviewedPropertyNames,
            Set<String> quickViewVisibleFields,
            Set<String> hiddenTechnicalFields,
            Set<String> nonScalarFields,
            java.util.Map<String, org.praxisplatform.uischema.annotation.ResourceReference> resourceReferencesByProperty,
            List<SemanticMetadataReviewIssue> issues
    ) {
        for (var entry : resourceReferencesByProperty.entrySet()) {
            String propName = entry.getKey();
            org.praxisplatform.uischema.annotation.ResourceReference ref = entry.getValue();
            String valueField = normalize(ref.valueField());
            if (!valueField.isBlank() && !reviewedPropertyNames.contains(valueField)) {
                issues.add(new SemanticMetadataReviewIssue(
                        SemanticMetadataIssueSeverity.ERROR,
                        "resource-reference-value-field-missing",
                        targetType.getName(),
                        propName,
                        "@ResourceReference.valueField '" + valueField + "' does not exist on " + targetType.getSimpleName() + "."
                ));
            }
            if (normalize(ref.previewSurfaceId()).isBlank()) {
                issues.add(new SemanticMetadataReviewIssue(
                        SemanticMetadataIssueSeverity.ERROR,
                        "resource-reference-preview-surface-missing",
                        targetType.getName(),
                        propName,
                        "@ResourceReference on '" + propName + "' must declare a non-blank previewSurfaceId for Quick View 360° preview."
                ));
            }
        }

        org.praxisplatform.uischema.annotation.ResourceIdentity identity = findTypeAnnotation(
                targetType,
                org.praxisplatform.uischema.annotation.ResourceIdentity.class
        );
        if (identity == null) {
            return;
        }
        if (normalize(identity.keyField()).isBlank() || normalize(identity.titleField()).isBlank()) {
            issues.add(new SemanticMetadataReviewIssue(
                    SemanticMetadataIssueSeverity.ERROR,
                    "resource-identity-missing",
                    targetType.getName(),
                    "@ResourceIdentity",
                    "@ResourceIdentity on " + targetType.getSimpleName()
                            + " must declare non-blank keyField and titleField."
            ));
        }
        if (normalize(identity.subtitleField()).isBlank()) {
            issues.add(new SemanticMetadataReviewIssue(
                    SemanticMetadataIssueSeverity.ERROR,
                    "resource-identity-subtitle-missing",
                    targetType.getName(),
                    "@ResourceIdentity.subtitleField",
                    "@ResourceIdentity on " + targetType.getSimpleName()
                            + " must declare a non-blank subtitleField for Quick View 360° framing."
            ));
        }
        java.util.Set<String> headerIdentityFields = new java.util.LinkedHashSet<>();
        java.util.Set<String> identitySummaryFields = new java.util.LinkedHashSet<>();
        if (checkIdentityFieldExists(targetType, "keyField", identity.keyField(), reviewedPropertyNames, issues)) {
            String normalizedKey = normalize(identity.keyField());
            headerIdentityFields.add(normalizedKey);
            identitySummaryFields.add(normalizedKey);
        }
        if (checkIdentityFieldExists(targetType, "titleField", identity.titleField(), reviewedPropertyNames, issues)) {
            String normalizedTitle = normalize(identity.titleField());
            headerIdentityFields.add(normalizedTitle);
            identitySummaryFields.add(normalizedTitle);
        }
        if (checkIdentityFieldExists(targetType, "subtitleField", identity.subtitleField(), reviewedPropertyNames, issues)) {
            String normalizedSubtitle = normalize(identity.subtitleField());
            headerIdentityFields.add(normalizedSubtitle);
            identitySummaryFields.add(normalizedSubtitle);
        }
        if (checkIdentityFieldExists(targetType, "avatarField", identity.avatarField(), reviewedPropertyNames, issues)) {
            String normalizedAvatar = normalize(identity.avatarField());
            headerIdentityFields.add(normalizedAvatar);
            identitySummaryFields.add(normalizedAvatar);
        }
        checkIdentityFieldExists(targetType, "displayLabelField", identity.displayLabelField(), reviewedPropertyNames, issues);
        java.util.Set<String> seenMetadataFields = new java.util.LinkedHashSet<>();
        int declaredMetadataCount = 0;
        if (identity.metadataFields() != null) {
            for (String metaField : identity.metadataFields()) {
                String normalizedMeta = normalize(metaField);
                if (!normalizedMeta.isBlank()) {
                    declaredMetadataCount++;
                    if (!seenMetadataFields.add(normalizedMeta)) {
                        issues.add(new SemanticMetadataReviewIssue(
                                SemanticMetadataIssueSeverity.ERROR,
                                "resource-identity-metadata-duplicate",
                                targetType.getName(),
                                normalizedMeta,
                                "@ResourceIdentity.metadataFields contains duplicate property '"
                                        + normalizedMeta + "' on " + targetType.getSimpleName() + "."
                        ));
                    }
                }
                if (checkIdentityFieldExists(targetType, "metadataFields", metaField, reviewedPropertyNames, issues)) {
                    if (headerIdentityFields.contains(normalizedMeta)) {
                        issues.add(new SemanticMetadataReviewIssue(
                                SemanticMetadataIssueSeverity.ERROR,
                                "resource-identity-metadata-repeats-header-field",
                                targetType.getName(),
                                normalizedMeta,
                                "@ResourceIdentity.metadataFields must not repeat a header identity field (keyField/titleField/subtitleField/avatarField)."
                        ));
                    }
                    identitySummaryFields.add(normalizedMeta);
                }
            }
        }
        for (String quickViewField : quickViewVisibleFields) {
            if (identitySummaryFields.contains(quickViewField)) {
                issues.add(new SemanticMetadataReviewIssue(
                        SemanticMetadataIssueSeverity.ERROR,
                        "quick-view-repeats-identity-field",
                        targetType.getName(),
                        quickViewField,
                        "@UISchema(quickViewVisible = true) must not repeat a field already declared in @ResourceIdentity(keyField/titleField/subtitleField/avatarField/metadataFields)."
                ));
            }
        }

        java.util.Set<String> respBizFields = new java.util.LinkedHashSet<>();
        for (String prop : reviewedPropertyNames) {
            if (!AUDIT_EXCLUDED_IDENTITY_FIELDS.contains(prop)) {
                respBizFields.add(prop);
            }
        }
        java.util.Set<String> availableForMetadata = new java.util.LinkedHashSet<>(respBizFields);
        for (String meta : seenMetadataFields) {
            if (reviewedPropertyNames.contains(meta)) {
                availableForMetadata.add(meta);
            }
        }
        availableForMetadata.removeAll(hiddenTechnicalFields);
        availableForMetadata.removeAll(nonScalarFields);
        availableForMetadata.removeAll(headerIdentityFields);
        availableForMetadata.removeAll(quickViewVisibleFields);
        int minMetadataCols = Math.min(2, Math.max(1, availableForMetadata.size()));
        if (declaredMetadataCount < minMetadataCols || declaredMetadataCount > 3) {
            issues.add(new SemanticMetadataReviewIssue(
                    SemanticMetadataIssueSeverity.ERROR,
                    "resource-identity-metadata-count-out-of-range",
                    targetType.getName(),
                    "@ResourceIdentity.metadataFields",
                    "@ResourceIdentity.metadataFields count on " + targetType.getSimpleName()
                            + " must be between " + minMetadataCols + " and 3 (found " + declaredMetadataCount + ")."
            ));
        }

        java.util.Set<String> availableForQuickView = new java.util.LinkedHashSet<>(respBizFields);
        availableForQuickView.removeAll(hiddenTechnicalFields);
        availableForQuickView.removeAll(nonScalarFields);
        availableForQuickView.removeAll(identitySummaryFields);
        int minQuickViewCols = Math.min(4, availableForQuickView.size());
        if (quickViewVisibleFields.size() < minQuickViewCols || quickViewVisibleFields.size() > 8) {
            issues.add(new SemanticMetadataReviewIssue(
                    SemanticMetadataIssueSeverity.ERROR,
                    "quick-view-field-count-out-of-range",
                    targetType.getName(),
                    "@UISchema.quickViewVisible",
                    "Quick View visible field count on " + targetType.getSimpleName()
                            + " must be between " + minQuickViewCols + " and 8 (found " + quickViewVisibleFields.size() + ")."
            ));
        }
    }

    private boolean checkIdentityFieldExists(
            Class<?> targetType,
            String attributeName,
            String fieldName,
            Set<String> reviewedPropertyNames,
            List<SemanticMetadataReviewIssue> issues
    ) {
        String normalized = normalize(fieldName);
        if (normalized.isBlank()) {
            return false;
        }
        if (!reviewedPropertyNames.contains(normalized)) {
            issues.add(new SemanticMetadataReviewIssue(
                    SemanticMetadataIssueSeverity.ERROR,
                    "resource-identity-field-missing",
                    targetType.getName(),
                    "@ResourceIdentity." + attributeName,
                    "@ResourceIdentity." + attributeName + " references unknown property '" + normalized + "' on " + targetType.getSimpleName() + "."
            ));
            return false;
        }
        return true;
    }

    private <A extends Annotation> A findTypeAnnotation(Class<?> targetType, Class<A> annotationType) {
        Class<?> current = targetType;
        while (current != null && current != Object.class && current != Record.class) {
            A found = org.praxisplatform.uischema.extension.ResolverUtils.getAnnotation(annotationType, current.getAnnotations());
            if (found != null) {
                return found;
            }
            for (Class<?> iface : current.getInterfaces()) {
                A fromIface = org.praxisplatform.uischema.extension.ResolverUtils.getAnnotation(annotationType, iface.getAnnotations());
                if (fromIface != null) {
                    return fromIface;
                }
            }
            current = current.getSuperclass();
        }
        return null;
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

    /**
     * Reviews a resource controller class for {@link org.praxisplatform.uischema.annotation.ApiResource},
     * {@link org.praxisplatform.uischema.annotation.QuickFilter} alignment against its bound {@code FilterDTO},
     * item-level {@code VIEW}/{@code READ_PROJECTION} {@link org.praxisplatform.uischema.annotation.UiSurface}
     * presence for Quick View 360°, {@link org.praxisplatform.uischema.annotation.ResourceIdentity} parity
     * against its bound {@code ResponseDTO}, and {@link ResourceFact} / legal-treatment annotations.
     */
    public SemanticMetadataReviewReport reviewResourceController(Class<?> controllerClass) {
        return reviewResourceController(controllerClass, null, resolveControllerFilterDtoType(controllerClass));
    }

    /**
     * Reviews a resource controller class against an explicit {@code filterDtoClass}.
     */
    public SemanticMetadataReviewReport reviewResourceController(Class<?> controllerClass, Class<?> filterDtoClass) {
        return reviewResourceController(controllerClass, null, filterDtoClass);
    }

    /**
     * Reviews a resource controller class against explicit {@code responseDtoClass} and {@code filterDtoClass}.
     */
    public SemanticMetadataReviewReport reviewResourceController(
            Class<?> controllerClass,
            Class<?> responseDtoClass,
            Class<?> filterDtoClass
    ) {
        if (controllerClass == null) {
            return new SemanticMetadataReviewReport(null, List.of());
        }

        List<SemanticMetadataReviewIssue> issues = new ArrayList<>();
        boolean hasItemDetailSurface = false;
        java.util.Set<String> readSurfaceIds = new java.util.LinkedHashSet<>();
        Class<?> currentType = controllerClass;
        while (currentType != null && currentType != Object.class && currentType != Record.class) {
            reviewResourceFactsOnType(controllerClass, currentType, issues);
            for (Method method : currentType.getDeclaredMethods()) {
                if (method.isSynthetic() || method.isBridge()) {
                    continue;
                }
                for (org.praxisplatform.uischema.annotation.UiSurface surface :
                        method.getDeclaredAnnotationsByType(org.praxisplatform.uischema.annotation.UiSurface.class)) {
                    if (surface.kind() == org.praxisplatform.uischema.surface.SurfaceKind.VIEW
                            || surface.kind() == org.praxisplatform.uischema.surface.SurfaceKind.READ_PROJECTION) {
                        String surfaceId = normalize(surface.id());
                        if (!surfaceId.isBlank()) {
                            readSurfaceIds.add(surfaceId);
                        }
                        if (surface.scope() == org.praxisplatform.uischema.surface.SurfaceScope.ITEM
                                && normalize(surface.relatedChildResourceKey()).isBlank()) {
                            hasItemDetailSurface = true;
                        }
                    }
                }
            }
            currentType = currentType.getSuperclass();
        }

        org.praxisplatform.uischema.annotation.ApiResource apiResource =
                findTypeAnnotation(controllerClass, org.praxisplatform.uischema.annotation.ApiResource.class);
        if (apiResource == null) {
            issues.add(new SemanticMetadataReviewIssue(
                    SemanticMetadataIssueSeverity.ERROR,
                    "api-resource-annotation-missing",
                    controllerClass.getName(),
                    "@ApiResource",
                    "Resource controller must declare @ApiResource."
            ));
        } else {
            org.praxisplatform.uischema.annotation.QuickFilter[] quickFilters = apiResource.quickFilters();
            java.util.List<org.praxisplatform.uischema.schema.QuickFilterDescriptor<?>> programmaticQuickFilters =
                    org.praxisplatform.uischema.schema.ApiResourceQuickFilterResolver.extractProgrammaticDescriptors(
                            controllerClass,
                            null
                    );
            boolean hasUninstantiableProgrammaticOverride =
                    programmaticQuickFilters.isEmpty()
                            && org.praxisplatform.uischema.schema.ApiResourceQuickFilterResolver.overridesDefineQuickFilters(controllerClass);
            int totalQuickFilterCount = (quickFilters == null ? 0 : quickFilters.length) + programmaticQuickFilters.size();
            if (totalQuickFilterCount < 2 && !hasUninstantiableProgrammaticOverride) {
                issues.add(new SemanticMetadataReviewIssue(
                        SemanticMetadataIssueSeverity.ERROR,
                        "quick-filter-count-insufficient",
                        controllerClass.getName(),
                        "@ApiResource.quickFilters",
                        "@ApiResource must declare at least 2 @QuickFilter entries (or defineQuickFilters() descriptors) for operational discovery (found "
                                + totalQuickFilterCount + ")."
                ));
            }
            Class<?> effectiveFilterDto = filterDtoClass != null
                    ? filterDtoClass
                    : resolveControllerFilterDtoType(controllerClass);
            java.util.Map<String, Field> allFilterFields = effectiveFilterDto != null
                    ? collectAllInstanceFields(effectiveFilterDto)
                    : java.util.Map.of();
            java.util.Map<String, Field> filterableFields = effectiveFilterDto != null
                    ? collectFilterableFields(effectiveFilterDto)
                    : java.util.Map.of();
            com.fasterxml.jackson.databind.node.ObjectNode filterDtoTypeGov = effectiveFilterDto != null
                    ? DomainLineageResolver.resolveTypeGovernance(effectiveFilterDto)
                    : null;
            Set<String> seenQuickFilterIds = new java.util.LinkedHashSet<>();
            int defaultActiveCount = 0;

            if (quickFilters != null) {
                for (org.praxisplatform.uischema.annotation.QuickFilter qf : quickFilters) {
                    String qfId = normalize(qf.id());
                    String memberName = qfId.isBlank() ? "@QuickFilter" : "@QuickFilter(" + qfId + ")";
                    validateQuickFilterCommonMetadata(
                            controllerClass,
                            memberName,
                            qfId,
                            normalize(qf.label()),
                            normalize(qf.icon()),
                            seenQuickFilterIds,
                            issues
                    );
                    if (qf.defaultActive()) {
                        defaultActiveCount++;
                    }

                    org.praxisplatform.uischema.annotation.FilterCriterion[] criteria = qf.criteria();
                    boolean hasCriteria = criteria != null && criteria.length > 0;
                    String expr = normalize(qf.filter());
                    boolean hasLegacyFilter = !expr.isBlank();

                    if (hasCriteria && hasLegacyFilter) {
                        issues.add(new SemanticMetadataReviewIssue(
                                SemanticMetadataIssueSeverity.ERROR,
                                "quick-filter-criteria-and-filter-conflict",
                                controllerClass.getName(),
                                memberName,
                                memberName + " cannot declare both criteria and deprecated filter expression simultaneously."
                        ));
                        continue;
                    }
                    if (hasCriteria) {
                        validateQuickFilterCriteria(
                                controllerClass,
                                memberName,
                                criteria,
                                effectiveFilterDto,
                                allFilterFields,
                                filterableFields,
                                filterDtoTypeGov,
                                issues
                        );
                        continue;
                    }
                    if (!hasLegacyFilter) {
                        issues.add(new SemanticMetadataReviewIssue(
                                SemanticMetadataIssueSeverity.ERROR,
                                "quick-filter-expression-malformed",
                                controllerClass.getName(),
                                memberName,
                                memberName + " must declare non-empty criteria = @FilterCriterion(...) (or legacy filter expression)."
                        ));
                        continue;
                    }
                    if (expr.startsWith("{") || expr.endsWith("}")) {
                        validateJsonQuickFilterExpression(
                                controllerClass,
                                memberName,
                                expr,
                                effectiveFilterDto,
                                allFilterFields,
                                filterableFields,
                                filterDtoTypeGov,
                                issues
                        );
                        continue;
                    }
                    String[] clauses = expr.split("&", -1);
                    for (String rawClause : clauses) {
                        String clause = rawClause.trim();
                        int eq = clause.indexOf('=');
                        if (eq <= 0 || eq == clause.length() - 1) {
                            issues.add(new SemanticMetadataReviewIssue(
                                    SemanticMetadataIssueSeverity.ERROR,
                                    "quick-filter-expression-malformed",
                                    controllerClass.getName(),
                                    memberName,
                                    memberName + " declares malformed filter clause '" + clause
                                            + "' in '" + expr + "'; expected 'fieldName=value'."
                            ));
                            continue;
                        }
                        if (effectiveFilterDto == null) {
                            continue;
                        }
                        String fieldName = clause.substring(0, eq).trim();
                        String rawVal = clause.substring(eq + 1).trim();
                        Field targetField = validateAndResolveQuickFilterTargetField(
                                controllerClass,
                                memberName,
                                fieldName,
                                effectiveFilterDto,
                                allFilterFields,
                                filterableFields,
                                filterDtoTypeGov,
                                issues
                        );
                        if (targetField == null) {
                            continue;
                        }
                        Class<?> valueType = resolveFilterFieldScalarType(targetField);
                        String[] tokens = java.util.Collection.class.isAssignableFrom(targetField.getType())
                                ? rawVal.split(",", -1)
                                : new String[]{rawVal};
                        for (String rawToken : tokens) {
                            String token = rawToken.trim();
                            if (token.isEmpty()) {
                                issues.add(new SemanticMetadataReviewIssue(
                                        SemanticMetadataIssueSeverity.ERROR,
                                        "quick-filter-expression-malformed",
                                        controllerClass.getName(),
                                        memberName,
                                        memberName + " contains an empty value token in '" + clause + "'."
                                ));
                                continue;
                            }
                            validateQuickFilterValueToken(controllerClass, memberName, valueType, token, issues);
                        }
                    }
                }
            }

            if (!programmaticQuickFilters.isEmpty()) {
                java.util.List<java.util.Map<String, Object>> compiledAll =
                        org.praxisplatform.uischema.schema.ApiResourceQuickFilterResolver.resolveControllerQuickFilters(
                                controllerClass,
                                null,
                                effectiveFilterDto
                        );
                java.util.Map<String, java.util.Map<String, Object>> compiledById = new java.util.LinkedHashMap<>();
                for (java.util.Map<String, Object> item : compiledAll) {
                    if (item.get("id") instanceof String id) {
                        compiledById.put(id, item);
                    }
                }
                for (org.praxisplatform.uischema.schema.QuickFilterDescriptor<?> descriptor : programmaticQuickFilters) {
                    String qfId = normalize(descriptor.id());
                    String memberName = qfId.isBlank() ? "defineQuickFilters()" : "defineQuickFilters(" + qfId + ")";
                    validateQuickFilterCommonMetadata(
                            controllerClass,
                            memberName,
                            qfId,
                            normalize(descriptor.label()),
                            normalize(descriptor.icon()),
                            seenQuickFilterIds,
                            issues
                    );
                    if (descriptor.defaultActive()) {
                        defaultActiveCount++;
                    }
                    java.util.Map<String, Object> compiledEntry = compiledById.get(qfId);
                    Object filterPayload = compiledEntry != null ? compiledEntry.get("filter") : null;
                    if (!(filterPayload instanceof java.util.Map<?, ?> filterMap) || filterMap.isEmpty()) {
                        issues.add(new SemanticMetadataReviewIssue(
                                SemanticMetadataIssueSeverity.ERROR,
                                "quick-filter-expression-malformed",
                                controllerClass.getName(),
                                memberName,
                                memberName + " produced an empty filter payload."
                        ));
                        continue;
                    }
                    if (effectiveFilterDto != null) {
                        for (Object rawKey : filterMap.keySet()) {
                            String fieldName = normalize(String.valueOf(rawKey));
                            validateAndResolveQuickFilterTargetField(
                                    controllerClass,
                                    memberName,
                                    fieldName,
                                    effectiveFilterDto,
                                    allFilterFields,
                                    filterableFields,
                                    filterDtoTypeGov,
                                    issues
                            );
                        }
                    }
                }
            }

            if (defaultActiveCount > 1) {
                issues.add(new SemanticMetadataReviewIssue(
                        SemanticMetadataIssueSeverity.ERROR,
                        "quick-filter-multiple-defaults",
                        controllerClass.getName(),
                        "@ApiResource.quickFilters",
                        controllerClass.getSimpleName() + " declares " + defaultActiveCount
                                + " quick filters with defaultActive=true; at most one default quick filter is allowed per resource."
                ));
            }
        }

        if (!hasItemDetailSurface) {
            issues.add(new SemanticMetadataReviewIssue(
                    SemanticMetadataIssueSeverity.ERROR,
                    "item-detail-surface-missing",
                    controllerClass.getName(),
                    "@UiSurface",
                    "Controller must declare at least one self-item @UiSurface with scope=ITEM, kind=VIEW or READ_PROJECTION, and blank relatedChildResourceKey for Quick View 360°."
            ));
        }

        Class<?> effectiveResponseDto = responseDtoClass != null
                ? responseDtoClass
                : resolveControllerResponseDtoType(controllerClass);
        org.praxisplatform.uischema.annotation.ResourceIdentity standaloneCtrlIdentity =
                findTypeAnnotation(controllerClass, org.praxisplatform.uischema.annotation.ResourceIdentity.class);
        org.praxisplatform.uischema.annotation.ResourceIdentity nestedCtrlIdentity =
                apiResource != null && isPopulatedIdentity(apiResource.identity())
                        ? apiResource.identity()
                        : null;
        org.praxisplatform.uischema.annotation.ResourceIdentity controllerIdentity =
                standaloneCtrlIdentity != null ? standaloneCtrlIdentity : nestedCtrlIdentity;
        org.praxisplatform.uischema.annotation.ResourceIdentity dtoIdentity =
                effectiveResponseDto != null
                        ? findTypeAnnotation(effectiveResponseDto, org.praxisplatform.uischema.annotation.ResourceIdentity.class)
                        : null;
        if (dtoIdentity != null && !isPopulatedIdentity(dtoIdentity)) {
            dtoIdentity = null;
        }

        boolean enforceIdentityContract = responseDtoClass != null
                || org.praxisplatform.uischema.controller.base.AbstractResourceQueryController.class.isAssignableFrom(controllerClass)
                || controllerIdentity != null
                || dtoIdentity != null;

        if (enforceIdentityContract) {
            if (standaloneCtrlIdentity != null
                    && nestedCtrlIdentity != null
                    && !sameResourceIdentity(standaloneCtrlIdentity, nestedCtrlIdentity)) {
                issues.add(new SemanticMetadataReviewIssue(
                        SemanticMetadataIssueSeverity.ERROR,
                        "resource-identity-controller-dto-mismatch",
                        controllerClass.getName(),
                        "@ResourceIdentity",
                        "Controller declares conflicting @ResourceIdentity between @ApiResource(identity = ...) and type-level @ResourceIdentity."
                ));
            }
            if (controllerIdentity == null
                    || normalize(controllerIdentity.keyField()).isBlank()
                    || normalize(controllerIdentity.titleField()).isBlank()) {
                issues.add(new SemanticMetadataReviewIssue(
                        SemanticMetadataIssueSeverity.ERROR,
                        "resource-identity-missing",
                        controllerClass.getName(),
                        "@ResourceIdentity",
                        "Resource controller must declare @ResourceIdentity with non-blank keyField and titleField."
                ));
            }
            if (effectiveResponseDto != null
                    && (dtoIdentity == null
                        || normalize(dtoIdentity.keyField()).isBlank()
                        || normalize(dtoIdentity.titleField()).isBlank())) {
                issues.add(new SemanticMetadataReviewIssue(
                        SemanticMetadataIssueSeverity.ERROR,
                        "resource-identity-missing",
                        controllerClass.getName(),
                        effectiveResponseDto.getSimpleName() + ".@ResourceIdentity",
                        "ResponseDTO '" + effectiveResponseDto.getSimpleName()
                                + "' must declare @ResourceIdentity with non-blank keyField and titleField."
                ));
            }
            if (controllerIdentity != null
                    && dtoIdentity != null
                    && !sameResourceIdentity(controllerIdentity, dtoIdentity)) {
                issues.add(new SemanticMetadataReviewIssue(
                        SemanticMetadataIssueSeverity.ERROR,
                        "resource-identity-controller-dto-mismatch",
                        controllerClass.getName(),
                        "@ResourceIdentity",
                        "@ResourceIdentity on " + controllerClass.getSimpleName()
                                + " does not match @ResourceIdentity on "
                                + effectiveResponseDto.getSimpleName() + "."
                ));
            }
            String titleField = dtoIdentity != null && !normalize(dtoIdentity.titleField()).isBlank()
                    ? normalize(dtoIdentity.titleField())
                    : (controllerIdentity != null ? normalize(controllerIdentity.titleField()) : "");
            String resourceKey = apiResource != null ? normalize(apiResource.resourceKey()) : "";
            if (effectiveResponseDto != null && !titleField.isBlank() && !resourceKey.isBlank()) {
                org.praxisplatform.uischema.annotation.ResourceReference titleRef = findPropertyAnnotation(
                        effectiveResponseDto,
                        titleField,
                        org.praxisplatform.uischema.annotation.ResourceReference.class
                );
                if (titleRef == null || !resourceKey.equals(normalize(titleRef.resourceKey()))) {
                    issues.add(new SemanticMetadataReviewIssue(
                            SemanticMetadataIssueSeverity.ERROR,
                            "title-field-self-reference-missing",
                            controllerClass.getName(),
                            effectiveResponseDto.getSimpleName() + "." + titleField,
                            "Title field '" + titleField + "' on " + effectiveResponseDto.getSimpleName()
                                    + " must declare @ResourceReference(resourceKey = \"" + resourceKey
                                    + "\", ...) for Quick View 360° self-navigation."
                    ));
                } else if (!readSurfaceIds.isEmpty()) {
                    String surfId = normalize(titleRef.surfaceId()).isBlank() ? "detail" : normalize(titleRef.surfaceId());
                    String prevSurfId = normalize(titleRef.previewSurfaceId());
                    if (!readSurfaceIds.contains(surfId)
                            || (!prevSurfId.isBlank() && !readSurfaceIds.contains(prevSurfId))) {
                        issues.add(new SemanticMetadataReviewIssue(
                                SemanticMetadataIssueSeverity.ERROR,
                                "resource-reference-surface-unresolved",
                                controllerClass.getName(),
                                effectiveResponseDto.getSimpleName() + "." + titleField,
                                "@ResourceReference on '" + effectiveResponseDto.getSimpleName() + "." + titleField
                                        + "' references surfaceId='" + surfId + "' / previewSurfaceId='" + prevSurfId
                                        + "' not declared in controller read surfaces " + readSurfaceIds + "."
                        ));
                    }
                }
            }
        }

        return new SemanticMetadataReviewReport(controllerClass, issues);
    }

    /**
     * Reviews all {@link org.praxisplatform.uischema.options.OptionSourceDescriptor} entries in an
     * {@link org.praxisplatform.uischema.options.OptionSourceRegistry}, verifying that every
     * {@code RESOURCE_ENTITY} option source declares a complete {@code EntityLookupDescriptor} with
     * non-null subcontracts ({@code selectionPolicy}, {@code capabilities}, {@code detail},
     * {@code display}, {@code filtering}), identity/search paths, and surface-based detail navigation
     * (rejecting legacy route/href detail contracts).
     */
    public SemanticMetadataReviewReport reviewOptionSourceRegistry(
            org.praxisplatform.uischema.options.OptionSourceRegistry registry
    ) {
        if (registry == null || registry.isEmpty()) {
            return new SemanticMetadataReviewReport(org.praxisplatform.uischema.options.OptionSourceRegistry.class, List.of());
        }
        List<SemanticMetadataReviewIssue> issues = new ArrayList<>();
        for (org.praxisplatform.uischema.options.OptionSourceDescriptor descriptor : registry.descriptors()) {
            reviewOptionSourceDescriptorInto(descriptor, issues);
        }
        return new SemanticMetadataReviewReport(org.praxisplatform.uischema.options.OptionSourceRegistry.class, issues);
    }

    /**
     * Reviews a single {@link org.praxisplatform.uischema.options.OptionSourceDescriptor} for
     * canonical {@code RESOURCE_ENTITY} lookup completeness and surface detail navigation.
     */
    public SemanticMetadataReviewReport reviewOptionSourceDescriptor(
            org.praxisplatform.uischema.options.OptionSourceDescriptor descriptor
    ) {
        if (descriptor == null) {
            return new SemanticMetadataReviewReport(org.praxisplatform.uischema.options.OptionSourceDescriptor.class, List.of());
        }
        List<SemanticMetadataReviewIssue> issues = new ArrayList<>();
        reviewOptionSourceDescriptorInto(descriptor, issues);
        return new SemanticMetadataReviewReport(org.praxisplatform.uischema.options.OptionSourceDescriptor.class, issues);
    }

    private void reviewOptionSourceDescriptorInto(
            org.praxisplatform.uischema.options.OptionSourceDescriptor descriptor,
            List<SemanticMetadataReviewIssue> issues
    ) {
        if (descriptor == null) {
            return;
        }
        String memberName = normalize(descriptor.key()).isBlank() ? "<unnamed>" : normalize(descriptor.key());
        String typeName = normalize(descriptor.resourcePath()).isBlank()
                ? org.praxisplatform.uischema.options.OptionSourceDescriptor.class.getName()
                : normalize(descriptor.resourcePath());

        if (descriptor.type() != org.praxisplatform.uischema.options.OptionSourceType.RESOURCE_ENTITY) {
            return;
        }

        org.praxisplatform.uischema.options.EntityLookupDescriptor lookup = descriptor.entityLookup();
        if (lookup == null) {
            issues.add(new SemanticMetadataReviewIssue(
                    SemanticMetadataIssueSeverity.ERROR,
                    "option-source-entity-lookup-missing",
                    typeName,
                    memberName,
                    "RESOURCE_ENTITY option source '" + memberName + "' must declare a non-null EntityLookupDescriptor."
            ));
            return;
        }

        if ((normalize(descriptor.labelPropertyPath()).isBlank() && normalize(lookup.codePropertyPath()).isBlank())
                || lookup.descriptionPropertyPaths() == null
                || lookup.descriptionPropertyPaths().isEmpty()) {
            issues.add(new SemanticMetadataReviewIssue(
                    SemanticMetadataIssueSeverity.ERROR,
                    "option-source-lookup-identity-paths-missing",
                    typeName,
                    memberName,
                    "RESOURCE_ENTITY option source '" + memberName
                            + "' must declare non-blank labelPropertyPath/codePropertyPath and non-empty descriptionPropertyPaths on EntityLookupDescriptor."
            ));
        }

        if (lookup.searchPropertyPaths() == null || lookup.searchPropertyPaths().isEmpty()) {
            issues.add(new SemanticMetadataReviewIssue(
                    SemanticMetadataIssueSeverity.ERROR,
                    "option-source-lookup-search-paths-empty",
                    typeName,
                    memberName,
                    "RESOURCE_ENTITY option source '" + memberName
                            + "' must declare non-empty searchPropertyPaths on EntityLookupDescriptor."
            ));
        }

        if (lookup.selectionPolicy() == null || lookup.selectionPolicy().isEmpty()) {
            issues.add(new SemanticMetadataReviewIssue(
                    SemanticMetadataIssueSeverity.ERROR,
                    "option-source-lookup-subcontract-missing",
                    typeName,
                    memberName + ".selectionPolicy",
                    "RESOURCE_ENTITY option source '" + memberName
                            + "' must declare a non-empty LookupSelectionPolicy."
            ));
        }

        if (lookup.capabilities() == null) {
            issues.add(new SemanticMetadataReviewIssue(
                    SemanticMetadataIssueSeverity.ERROR,
                    "option-source-lookup-subcontract-missing",
                    typeName,
                    memberName + ".capabilities",
                    "RESOURCE_ENTITY option source '" + memberName
                            + "' must declare non-null LookupCapabilities."
            ));
        }

        if (lookup.display() == null || lookup.display().isEmpty()) {
            issues.add(new SemanticMetadataReviewIssue(
                    SemanticMetadataIssueSeverity.ERROR,
                    "option-source-lookup-subcontract-missing",
                    typeName,
                    memberName + ".display",
                    "RESOURCE_ENTITY option source '" + memberName
                            + "' must declare a non-empty LookupDisplayDescriptor."
            ));
        }

        if (descriptor.effectiveFiltering() == null || descriptor.effectiveFiltering().isEmpty()) {
            issues.add(new SemanticMetadataReviewIssue(
                    SemanticMetadataIssueSeverity.ERROR,
                    "option-source-lookup-subcontract-missing",
                    typeName,
                    memberName + ".filtering",
                    "RESOURCE_ENTITY option source '" + memberName
                            + "' must declare a non-empty LookupFilteringDescriptor."
            ));
        }

        org.praxisplatform.uischema.options.LookupDetailDescriptor detail = lookup.detail();
        if (detail == null || detail.isEmpty()) {
            issues.add(new SemanticMetadataReviewIssue(
                    SemanticMetadataIssueSeverity.ERROR,
                    "option-source-lookup-subcontract-missing",
                    typeName,
                    memberName + ".detail",
                    "RESOURCE_ENTITY option source '" + memberName
                            + "' must declare a non-empty LookupDetailDescriptor."
            ));
        } else {
            if (detail.routeTemplate() != null
                    || detail.hrefTemplate() != null
                    || "route".equalsIgnoreCase(detail.openDetailMode())) {
                issues.add(new SemanticMetadataReviewIssue(
                        SemanticMetadataIssueSeverity.ERROR,
                        "option-source-lookup-detail-legacy-route",
                        typeName,
                        memberName + ".detail",
                        "RESOURCE_ENTITY option source '" + memberName
                                + "' uses legacy route/href detail navigation; use surface-based LookupDetailDescriptor (kind='surface', surfaceId, presentation, resourceKey)."
                ));
            }
            if (!"surface".equals(detail.kind())
                    || normalize(detail.surfaceId()).isBlank()
                    || (!"drawer".equals(detail.presentation()) && !"modal".equals(detail.presentation()))
                    || normalize(detail.resourceKey()).isBlank()) {
                issues.add(new SemanticMetadataReviewIssue(
                        SemanticMetadataIssueSeverity.ERROR,
                        "option-source-lookup-detail-surface-incomplete",
                        typeName,
                        memberName + ".detail",
                        "RESOURCE_ENTITY option source '" + memberName
                                + "' must declare complete surface detail navigation (kind='surface', non-blank surfaceId, presentation in ['drawer','modal'], and non-blank resourceKey)."
                ));
            }
        }
    }

    private void validateQuickFilterCommonMetadata(
            Class<?> controllerClass,
            String memberName,
            String qfId,
            String qfLabel,
            String qfIcon,
            Set<String> seenQuickFilterIds,
            List<SemanticMetadataReviewIssue> issues
    ) {
        if (qfId.isBlank()) {
            issues.add(new SemanticMetadataReviewIssue(
                    SemanticMetadataIssueSeverity.ERROR,
                    "quick-filter-id-blank",
                    controllerClass.getName(),
                    memberName,
                    "@QuickFilter must declare a non-blank id."
            ));
        } else if (!seenQuickFilterIds.add(qfId)) {
            issues.add(new SemanticMetadataReviewIssue(
                    SemanticMetadataIssueSeverity.ERROR,
                    "quick-filter-id-duplicate",
                    controllerClass.getName(),
                    memberName,
                    "Duplicate @QuickFilter id '" + qfId + "' on " + controllerClass.getSimpleName() + "."
            ));
        }

        if (qfLabel.isBlank()) {
            issues.add(new SemanticMetadataReviewIssue(
                    SemanticMetadataIssueSeverity.ERROR,
                    "quick-filter-label-blank",
                    controllerClass.getName(),
                    memberName,
                    memberName + " must declare a concise non-blank UI label."
            ));
        } else if (qfLabel.length() > 36 || qfLabel.endsWith(".")) {
            issues.add(new SemanticMetadataReviewIssue(
                    SemanticMetadataIssueSeverity.ERROR,
                    "quick-filter-label-sentence-like",
                    controllerClass.getName(),
                    memberName,
                    memberName + " label '" + qfLabel
                            + "' must be a short UI badge label (<= 36 chars, without trailing period)."
            ));
        }

        if (!qfIcon.isBlank() && !qfIcon.matches("^[a-z0-9]+(_[a-z0-9]+)*$")) {
            issues.add(new SemanticMetadataReviewIssue(
                    SemanticMetadataIssueSeverity.ERROR,
                    "quick-filter-icon-not-snake-case",
                    controllerClass.getName(),
                    memberName,
                    memberName + " icon '" + qfIcon
                            + "' must be a lowercase snake_case Material Icons ligature."
            ));
        }
    }

    private Field validateAndResolveQuickFilterTargetField(
            Class<?> controllerClass,
            String memberName,
            String fieldName,
            Class<?> effectiveFilterDto,
            java.util.Map<String, Field> allFilterFields,
            java.util.Map<String, Field> filterableFields,
            com.fasterxml.jackson.databind.node.ObjectNode filterDtoTypeGov,
            List<SemanticMetadataReviewIssue> issues
    ) {
        Field declaredField = allFilterFields.get(fieldName);
        if (declaredField == null) {
            issues.add(new SemanticMetadataReviewIssue(
                    SemanticMetadataIssueSeverity.ERROR,
                    "quick-filter-target-field-missing",
                    controllerClass.getName(),
                    memberName,
                    memberName + " references field '" + fieldName
                            + "' which does not exist on "
                            + effectiveFilterDto.getSimpleName() + "."
            ));
            return null;
        }
        Field targetField = filterableFields.get(fieldName);
        if (targetField == null) {
            issues.add(new SemanticMetadataReviewIssue(
                    SemanticMetadataIssueSeverity.ERROR,
                    "quick-filter-target-field-not-filterable",
                    controllerClass.getName(),
                    memberName,
                    memberName + " references field '" + fieldName
                            + "' on " + effectiveFilterDto.getSimpleName()
                            + " which is missing @Filterable."
            ));
            return null;
        }
        DomainGovernance memberGov = findMemberAnnotation(effectiveFilterDto, targetField, DomainGovernance.class);
        DomainLineage lineage = findMemberAnnotation(effectiveFilterDto, targetField, DomainLineage.class);
        com.fasterxml.jackson.databind.node.ObjectNode effectiveGov =
                resolveEffectiveGovernanceQuietly(lineage, memberGov, filterDtoTypeGov);
        if (effectiveGov != null && "credential".equals(effectiveGov.path("dataCategory").asText(""))) {
            issues.add(new SemanticMetadataReviewIssue(
                    SemanticMetadataIssueSeverity.ERROR,
                    "quick-filter-target-field-governance-denied",
                    controllerClass.getName(),
                    memberName,
                    memberName + " references credential-classified field '" + fieldName
                            + "' on " + effectiveFilterDto.getSimpleName()
                            + " which cannot be exposed in @QuickFilter."
            ));
            return null;
        }
        return targetField;
    }

    private void validateQuickFilterCriteria(
            Class<?> controllerClass,
            String memberName,
            org.praxisplatform.uischema.annotation.FilterCriterion[] criteria,
            Class<?> effectiveFilterDto,
            java.util.Map<String, Field> allFilterFields,
            java.util.Map<String, Field> filterableFields,
            com.fasterxml.jackson.databind.node.ObjectNode filterDtoTypeGov,
            List<SemanticMetadataReviewIssue> issues
    ) {
        Set<String> seenFields = new java.util.LinkedHashSet<>();
        for (org.praxisplatform.uischema.annotation.FilterCriterion criterion : criteria) {
            if (criterion == null) {
                continue;
            }
            String fieldName = normalize(criterion.field());
            if (fieldName.isBlank()) {
                issues.add(new SemanticMetadataReviewIssue(
                        SemanticMetadataIssueSeverity.ERROR,
                        "quick-filter-criterion-field-blank",
                        controllerClass.getName(),
                        memberName,
                        memberName + " declares a @FilterCriterion with blank field."
                ));
                continue;
            }
            if (!seenFields.add(fieldName)) {
                issues.add(new SemanticMetadataReviewIssue(
                        SemanticMetadataIssueSeverity.ERROR,
                        "quick-filter-criterion-field-duplicate",
                        controllerClass.getName(),
                        memberName,
                        memberName + " repeats @FilterCriterion field '" + fieldName + "'."
                ));
                continue;
            }

            boolean hasValues = criterion.values() != null && criterion.values().length > 0;
            boolean hasBool = criterion.bool() != null && criterion.bool().length > 0;
            boolean hasNumber = criterion.number() != null && criterion.number().length > 0;
            boolean hasDecimal = criterion.decimal() != null && criterion.decimal().length > 0;
            int populatedCount = (hasValues ? 1 : 0) + (hasBool ? 1 : 0) + (hasNumber ? 1 : 0) + (hasDecimal ? 1 : 0);
            if (populatedCount == 0) {
                issues.add(new SemanticMetadataReviewIssue(
                        SemanticMetadataIssueSeverity.ERROR,
                        "quick-filter-criterion-value-missing",
                        controllerClass.getName(),
                        memberName,
                        memberName + " @FilterCriterion('" + fieldName
                                + "') must populate one of values, bool, number, or decimal."
                ));
                continue;
            }
            if (populatedCount > 1) {
                issues.add(new SemanticMetadataReviewIssue(
                        SemanticMetadataIssueSeverity.ERROR,
                        "quick-filter-criterion-multiple-value-types",
                        controllerClass.getName(),
                        memberName,
                        memberName + " @FilterCriterion('" + fieldName
                                + "') populates multiple value attributes simultaneously; choose exactly one."
                ));
                continue;
            }

            int valueLength = hasValues
                    ? criterion.values().length
                    : hasBool
                            ? criterion.bool().length
                            : hasNumber
                                    ? criterion.number().length
                                    : criterion.decimal().length;

            if (hasValues) {
                for (String rawToken : criterion.values()) {
                    if (rawToken == null || rawToken.trim().isEmpty()) {
                        issues.add(new SemanticMetadataReviewIssue(
                                SemanticMetadataIssueSeverity.ERROR,
                                "quick-filter-expression-malformed",
                                controllerClass.getName(),
                                memberName,
                                memberName + " @FilterCriterion('" + fieldName + "') contains a blank value."
                        ));
                    }
                }
            }

            if (effectiveFilterDto == null) {
                continue;
            }
            Field targetField = validateAndResolveQuickFilterTargetField(
                    controllerClass,
                    memberName,
                    fieldName,
                    effectiveFilterDto,
                    allFilterFields,
                    filterableFields,
                    filterDtoTypeGov,
                    issues
            );
            if (targetField == null) {
                continue;
            }

            boolean isCollection = org.praxisplatform.uischema.schema.ApiResourceQuickFilterResolver.isCollectionOrArrayField(targetField);
            if (!isCollection && valueLength > 1) {
                issues.add(new SemanticMetadataReviewIssue(
                        SemanticMetadataIssueSeverity.ERROR,
                        "quick-filter-criterion-cardinality-mismatch",
                        controllerClass.getName(),
                        memberName,
                        memberName + " @FilterCriterion('" + fieldName + "') supplies " + valueLength
                                + " values for scalar field on " + effectiveFilterDto.getSimpleName() + "."
                ));
                continue;
            }

            Class<?> valueType = resolveFilterFieldScalarType(targetField);
            if (hasBool) {
                org.praxisplatform.uischema.filter.annotation.Filterable targetFilterable =
                        targetField.getAnnotation(org.praxisplatform.uischema.filter.annotation.Filterable.class);
                String operation = targetFilterable == null ? "" : String.valueOf(targetFilterable.operation());
                boolean presenceOperation = "IS_TRUE".equals(operation) || "IS_FALSE".equals(operation)
                        || "IS_NULL".equals(operation) || "IS_NOT_NULL".equals(operation);
                boolean containsFalse = false;
                for (boolean b : criterion.bool()) {
                    containsFalse |= !b;
                }
                if (presenceOperation && containsFalse) {
                    issues.add(new SemanticMetadataReviewIssue(
                            SemanticMetadataIssueSeverity.ERROR,
                            "quick-filter-presence-operation-false",
                            controllerClass.getName(),
                            memberName,
                            memberName + " @FilterCriterion('" + fieldName + "') uses bool = false on @Filterable(operation = "
                                    + operation + "); the operation ignores the value (IS_TRUE/IS_FALSE apply on any non-null"
                                    + " value and IS_NULL/IS_NOT_NULL become no-op), so the quick filter would not express"
                                    + " the intended slice. Target the complementary field/operation instead."
                    ));
                }
                if (valueType != Boolean.class && valueType != boolean.class) {
                    issues.add(new SemanticMetadataReviewIssue(
                            SemanticMetadataIssueSeverity.ERROR,
                            "quick-filter-boolean-value-invalid",
                            controllerClass.getName(),
                            memberName,
                            memberName + " @FilterCriterion('" + fieldName + "') uses bool on non-boolean field of type "
                                    + valueType.getSimpleName() + "."
                    ));
                }
            } else if (hasNumber) {
                if (!isIntegralValueType(valueType) && !isDecimalValueType(valueType)) {
                    issues.add(new SemanticMetadataReviewIssue(
                            SemanticMetadataIssueSeverity.ERROR,
                            "quick-filter-numeric-value-invalid",
                            controllerClass.getName(),
                            memberName,
                            memberName + " @FilterCriterion('" + fieldName + "') uses number on non-numeric field of type "
                                    + valueType.getSimpleName() + "."
                    ));
                } else {
                    for (long num : criterion.number()) {
                        validateQuickFilterValueToken(controllerClass, memberName, valueType, Long.toString(num), issues);
                    }
                }
            } else if (hasDecimal) {
                if (!isDecimalValueType(valueType)) {
                    issues.add(new SemanticMetadataReviewIssue(
                            SemanticMetadataIssueSeverity.ERROR,
                            "quick-filter-numeric-value-invalid",
                            controllerClass.getName(),
                            memberName,
                            memberName + " @FilterCriterion('" + fieldName + "') uses decimal on non-decimal field of type "
                                    + valueType.getSimpleName() + "."
                    ));
                }
            } else {
                for (String rawToken : criterion.values()) {
                    if (rawToken != null && !rawToken.trim().isEmpty()) {
                        validateQuickFilterValueToken(controllerClass, memberName, valueType, rawToken.trim(), issues);
                    }
                }
            }
        }
    }

    private boolean isIntegralValueType(Class<?> valueType) {
        return valueType == Integer.class || valueType == int.class
                || valueType == Long.class || valueType == long.class
                || valueType == Short.class || valueType == short.class
                || valueType == Byte.class || valueType == byte.class
                || valueType == java.math.BigInteger.class;
    }

    private boolean isDecimalValueType(Class<?> valueType) {
        return valueType == Double.class || valueType == double.class
                || valueType == Float.class || valueType == float.class
                || valueType == java.math.BigDecimal.class
                || valueType == Number.class;
    }

    private void validateJsonQuickFilterExpression(
            Class<?> controllerClass,
            String memberName,
            String expr,
            Class<?> effectiveFilterDto,
            java.util.Map<String, Field> allFilterFields,
            java.util.Map<String, Field> filterableFields,
            com.fasterxml.jackson.databind.node.ObjectNode filterDtoTypeGov,
            List<SemanticMetadataReviewIssue> issues
    ) {
        if (!expr.startsWith("{") || !expr.endsWith("}")) {
            issues.add(new SemanticMetadataReviewIssue(
                    SemanticMetadataIssueSeverity.ERROR,
                    "quick-filter-expression-malformed",
                    controllerClass.getName(),
                    memberName,
                    memberName + " declares malformed JSON filter expression '" + expr + "'."
            ));
            return;
        }
        java.util.Map<String, Object> parsed;
        try {
            parsed = JSON_MAPPER.readValue(
                    expr,
                    new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {}
            );
        } catch (Exception ex) {
            issues.add(new SemanticMetadataReviewIssue(
                    SemanticMetadataIssueSeverity.ERROR,
                    "quick-filter-expression-malformed",
                    controllerClass.getName(),
                    memberName,
                    memberName + " declares malformed JSON filter expression '" + expr + "'."
            ));
            return;
        }
        if (parsed == null || parsed.isEmpty()) {
            issues.add(new SemanticMetadataReviewIssue(
                    SemanticMetadataIssueSeverity.ERROR,
                    "quick-filter-expression-malformed",
                    controllerClass.getName(),
                    memberName,
                    memberName + " declares empty JSON filter expression '" + expr + "'."
            ));
            return;
        }
        for (java.util.Map.Entry<String, Object> entry : parsed.entrySet()) {
            String fieldName = normalize(entry.getKey());
            Object rawValObj = entry.getValue();
            if (fieldName.isBlank() || rawValObj == null || rawValObj instanceof java.util.Map<?, ?>) {
                issues.add(new SemanticMetadataReviewIssue(
                        SemanticMetadataIssueSeverity.ERROR,
                        "quick-filter-expression-malformed",
                        controllerClass.getName(),
                        memberName,
                        memberName + " declares invalid JSON filter entry for '" + fieldName + "' in '" + expr + "'."
                ));
                continue;
            }
            if (effectiveFilterDto == null) {
                continue;
            }
            Field targetField = validateAndResolveQuickFilterTargetField(
                    controllerClass,
                    memberName,
                    fieldName,
                    effectiveFilterDto,
                    allFilterFields,
                    filterableFields,
                    filterDtoTypeGov,
                    issues
            );
            if (targetField == null) {
                continue;
            }
            Class<?> valueType = resolveFilterFieldScalarType(targetField);
            if (rawValObj instanceof java.util.Collection<?> col) {
                if (!java.util.Collection.class.isAssignableFrom(targetField.getType()) || col.isEmpty()) {
                    issues.add(new SemanticMetadataReviewIssue(
                            SemanticMetadataIssueSeverity.ERROR,
                            "quick-filter-expression-malformed",
                            controllerClass.getName(),
                            memberName,
                            memberName + " declares invalid collection value for '" + fieldName + "' in '" + expr + "'."
                    ));
                    continue;
                }
                for (Object item : col) {
                    if (item == null || item instanceof java.util.Collection<?> || item instanceof java.util.Map<?, ?>) {
                        issues.add(new SemanticMetadataReviewIssue(
                                SemanticMetadataIssueSeverity.ERROR,
                                "quick-filter-expression-malformed",
                                controllerClass.getName(),
                                memberName,
                                memberName + " contains an invalid collection element in '" + expr + "'."
                        ));
                        continue;
                    }
                    String token = String.valueOf(item).trim();
                    if (token.isEmpty()) {
                        issues.add(new SemanticMetadataReviewIssue(
                                SemanticMetadataIssueSeverity.ERROR,
                                "quick-filter-expression-malformed",
                                controllerClass.getName(),
                                memberName,
                                memberName + " contains an empty value token in '" + expr + "'."
                        ));
                        continue;
                    }
                    validateQuickFilterValueToken(controllerClass, memberName, valueType, token, issues);
                }
            } else {
                if (java.util.Collection.class.isAssignableFrom(targetField.getType())) {
                    issues.add(new SemanticMetadataReviewIssue(
                            SemanticMetadataIssueSeverity.ERROR,
                            "quick-filter-expression-malformed",
                            controllerClass.getName(),
                            memberName,
                            memberName + " declares scalar value for collection field '" + fieldName
                                    + "' in JSON filter expression '" + expr + "'; expected JSON array."
                    ));
                    continue;
                }
                String token = String.valueOf(rawValObj).trim();
                if (token.isEmpty()) {
                    issues.add(new SemanticMetadataReviewIssue(
                            SemanticMetadataIssueSeverity.ERROR,
                            "quick-filter-expression-malformed",
                            controllerClass.getName(),
                            memberName,
                            memberName + " contains an empty value token in '" + expr + "'."
                    ));
                    continue;
                }
                validateQuickFilterValueToken(controllerClass, memberName, valueType, token, issues);
            }
        }
    }

    private void validateQuickFilterValueToken(
            Class<?> controllerClass,
            String memberName,
            Class<?> valueType,
            String token,
            List<SemanticMetadataReviewIssue> issues
    ) {
        if (valueType.isEnum()) {
            @SuppressWarnings({"unchecked", "rawtypes"})
            Class<? extends Enum> enumType = (Class<? extends Enum>) valueType;
            try {
                Enum.valueOf(enumType, token);
            } catch (IllegalArgumentException ex) {
                issues.add(new SemanticMetadataReviewIssue(
                        SemanticMetadataIssueSeverity.ERROR,
                        "quick-filter-enum-value-invalid",
                        controllerClass.getName(),
                        memberName,
                        memberName + " value '" + token
                                + "' is not a valid constant of " + enumType.getSimpleName() + "."
                ));
            }
        } else if (valueType == Boolean.class || valueType == boolean.class) {
            if (!"true".equalsIgnoreCase(token) && !"false".equalsIgnoreCase(token)) {
                issues.add(new SemanticMetadataReviewIssue(
                        SemanticMetadataIssueSeverity.ERROR,
                        "quick-filter-boolean-value-invalid",
                        controllerClass.getName(),
                        memberName,
                        memberName + " value '" + token + "' is not a valid boolean literal ('true' or 'false')."
                ));
            }
        } else if (valueType == Integer.class || valueType == int.class
                || valueType == Long.class || valueType == long.class
                || valueType == Short.class || valueType == short.class
                || valueType == Byte.class || valueType == byte.class
                || valueType == java.math.BigInteger.class) {
            try {
                if (valueType == Integer.class || valueType == int.class) {
                    Integer.parseInt(token);
                } else if (valueType == Long.class || valueType == long.class) {
                    Long.parseLong(token);
                } else if (valueType == Short.class || valueType == short.class) {
                    Short.parseShort(token);
                } else if (valueType == Byte.class || valueType == byte.class) {
                    Byte.parseByte(token);
                } else {
                    new java.math.BigInteger(token);
                }
            } catch (NumberFormatException ex) {
                issues.add(new SemanticMetadataReviewIssue(
                        SemanticMetadataIssueSeverity.ERROR,
                        "quick-filter-numeric-value-invalid",
                        controllerClass.getName(),
                        memberName,
                        memberName + " value '" + token + "' is not a valid integral literal for "
                                + valueType.getSimpleName() + "."
                ));
            }
        } else if (Number.class.isAssignableFrom(valueType)
                || valueType == double.class
                || valueType == float.class) {
            try {
                new java.math.BigDecimal(token);
            } catch (NumberFormatException ex) {
                issues.add(new SemanticMetadataReviewIssue(
                        SemanticMetadataIssueSeverity.ERROR,
                        "quick-filter-numeric-value-invalid",
                        controllerClass.getName(),
                        memberName,
                        memberName + " value '" + token + "' is not a valid numeric literal for "
                                + valueType.getSimpleName() + "."
                ));
            }
        } else if (valueType == java.time.LocalDate.class
                || valueType == java.time.LocalDateTime.class
                || valueType == java.time.Instant.class
                || valueType == java.time.OffsetDateTime.class) {
            try {
                if (valueType == java.time.LocalDate.class) {
                    java.time.LocalDate.parse(token);
                } else if (valueType == java.time.LocalDateTime.class) {
                    java.time.LocalDateTime.parse(token);
                } else if (valueType == java.time.Instant.class) {
                    java.time.Instant.parse(token);
                } else {
                    java.time.OffsetDateTime.parse(token);
                }
            } catch (java.time.format.DateTimeParseException ex) {
                issues.add(new SemanticMetadataReviewIssue(
                        SemanticMetadataIssueSeverity.ERROR,
                        "quick-filter-temporal-value-invalid",
                        controllerClass.getName(),
                        memberName,
                        memberName + " value '" + token + "' is not a valid ISO-8601 temporal literal for "
                                + valueType.getSimpleName() + "."
                ));
            }
        }
    }

    private java.util.Map<String, Field> collectAllInstanceFields(Class<?> type) {
        java.util.Map<String, Field> fields = new java.util.LinkedHashMap<>();
        Class<?> current = type;
        while (current != null && current != Object.class && current != Record.class) {
            for (Field field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                    continue;
                }
                fields.putIfAbsent(field.getName(), field);
            }
            current = current.getSuperclass();
        }
        return fields;
    }

    private java.util.Map<String, Field> collectFilterableFields(Class<?> filterDtoClass) {
        java.util.Map<String, Field> filterableFields = new java.util.LinkedHashMap<>();
        Class<?> current = filterDtoClass;
        while (current != null && current != Object.class && current != Record.class) {
            for (Field field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                    continue;
                }
                if (findMemberAnnotation(filterDtoClass, field, org.praxisplatform.uischema.filter.annotation.Filterable.class) != null) {
                    filterableFields.putIfAbsent(field.getName(), field);
                }
            }
            current = current.getSuperclass();
        }
        return filterableFields;
    }

    private Class<?> resolveFilterFieldScalarType(Field field) {
        Class<?> rawType = field.getType();
        if (java.util.Collection.class.isAssignableFrom(rawType)
                && field.getGenericType() instanceof java.lang.reflect.ParameterizedType pt
                && pt.getActualTypeArguments().length == 1
                && pt.getActualTypeArguments()[0] instanceof Class<?> elemClass) {
            return elemClass;
        }
        return rawType;
    }

    private Class<?> resolveControllerResponseDtoType(Class<?> controllerClass) {
        if (controllerClass == null) {
            return null;
        }
        if (org.praxisplatform.uischema.controller.base.AbstractResourceQueryController.class.isAssignableFrom(controllerClass)) {
            Class<?>[] args = org.springframework.core.GenericTypeResolver.resolveTypeArguments(
                    controllerClass,
                    org.praxisplatform.uischema.controller.base.AbstractResourceQueryController.class
            );
            if (args != null && args.length >= 1 && args[0] != null) {
                return args[0];
            }
        }
        java.lang.reflect.Type current = controllerClass.getGenericSuperclass();
        while (current != null && current != Object.class) {
            if (current instanceof java.lang.reflect.ParameterizedType pt) {
                for (java.lang.reflect.Type arg : pt.getActualTypeArguments()) {
                    if (arg instanceof Class<?> cls
                            && !org.praxisplatform.uischema.filter.dto.GenericFilterDTO.class.isAssignableFrom(cls)
                            && !cls.getSimpleName().endsWith("FilterDTO")
                            && !cls.getSimpleName().endsWith("Filter")
                            && !Number.class.isAssignableFrom(cls)
                            && cls != String.class
                            && cls != java.util.UUID.class
                            && !cls.isAnnotationPresent(jakarta.persistence.Entity.class)) {
                        return cls;
                    }
                }
                if (pt.getRawType() instanceof Class<?> rawCls) {
                    current = rawCls.getGenericSuperclass();
                } else {
                    break;
                }
            } else if (current instanceof Class<?> cls) {
                current = cls.getGenericSuperclass();
            } else {
                break;
            }
        }
        return null;
    }

    private Class<?> resolveControllerFilterDtoType(Class<?> controllerClass) {
        if (controllerClass == null) {
            return null;
        }
        if (org.praxisplatform.uischema.controller.base.AbstractResourceQueryController.class.isAssignableFrom(controllerClass)) {
            Class<?>[] args = org.springframework.core.GenericTypeResolver.resolveTypeArguments(
                    controllerClass,
                    org.praxisplatform.uischema.controller.base.AbstractResourceQueryController.class
            );
            if (args != null && args.length >= 3 && args[2] != null) {
                return args[2];
            }
        }
        java.lang.reflect.Type current = controllerClass.getGenericSuperclass();
        while (current != null && current != Object.class) {
            if (current instanceof java.lang.reflect.ParameterizedType pt) {
                for (java.lang.reflect.Type arg : pt.getActualTypeArguments()) {
                    if (arg instanceof Class<?> cls
                            && (org.praxisplatform.uischema.filter.dto.GenericFilterDTO.class.isAssignableFrom(cls)
                                || cls.getSimpleName().endsWith("FilterDTO")
                                || cls.getSimpleName().endsWith("Filter"))) {
                        return cls;
                    }
                }
                if (pt.getRawType() instanceof Class<?> rawCls) {
                    current = rawCls.getGenericSuperclass();
                } else {
                    break;
                }
            } else if (current instanceof Class<?> cls) {
                current = cls.getGenericSuperclass();
            } else {
                break;
            }
        }
        return null;
    }

    private boolean isPopulatedIdentity(org.praxisplatform.uischema.annotation.ResourceIdentity identity) {
        return identity != null
                && (!normalize(identity.keyField()).isBlank()
                    || !normalize(identity.titleField()).isBlank()
                    || !normalize(identity.subtitleField()).isBlank()
                    || !normalize(identity.avatarField()).isBlank()
                    || !normalize(identity.displayLabelField()).isBlank()
                    || (identity.metadataFields() != null && identity.metadataFields().length > 0));
    }

    private boolean sameResourceIdentity(
            org.praxisplatform.uischema.annotation.ResourceIdentity left,
            org.praxisplatform.uischema.annotation.ResourceIdentity right
    ) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        return normalize(left.keyField()).equals(normalize(right.keyField()))
                && normalize(left.titleField()).equals(normalize(right.titleField()))
                && normalize(left.subtitleField()).equals(normalize(right.subtitleField()))
                && normalize(left.avatarField()).equals(normalize(right.avatarField()))
                && normalize(left.displayLabelField()).equals(normalize(right.displayLabelField()))
                && normalizeStringArray(left.metadataFields()).equals(normalizeStringArray(right.metadataFields()));
    }

    private List<String> normalizeStringArray(String[] values) {
        if (values == null || values.length == 0) {
            return List.of();
        }
        List<String> result = new ArrayList<>(values.length);
        for (String value : values) {
            String normalized = normalize(value);
            if (!normalized.isBlank()) {
                result.add(normalized);
            }
        }
        return result;
    }

    private <A extends Annotation> A findPropertyAnnotation(
            Class<?> targetType,
            String propertyName,
            Class<A> annotationType
    ) {
        if (targetType == null || propertyName == null || propertyName.isBlank()) {
            return null;
        }
        A fromGetter = findGetterAnnotation(targetType, propertyName, annotationType);
        if (fromGetter != null) {
            return fromGetter;
        }
        Class<?> current = targetType;
        while (current != null && current != Object.class && current != Record.class) {
            try {
                Field declaredField = current.getDeclaredField(propertyName);
                return findMemberAnnotation(targetType, declaredField, annotationType);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
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
            org.praxisplatform.uischema.annotation.UiSurface surface =
                    method.getDeclaredAnnotation(org.praxisplatform.uischema.annotation.UiSurface.class);
            if (surface != null) {
                String surfaceId = normalize(surface.id());
                String memberName = surfaceId.isBlank() ? method.getName() : method.getName() + "@UiSurface(" + surfaceId + ")";
                reviewLegalTreatmentBinding(
                        targetType,
                        memberName,
                        "@UiSurface",
                        surface.legalPurpose(),
                        surface.legalBasisRef(),
                        issues
                );
            }
            org.praxisplatform.uischema.annotation.WorkflowAction action =
                    method.getDeclaredAnnotation(org.praxisplatform.uischema.annotation.WorkflowAction.class);
            if (action != null) {
                String actionId = normalize(action.id());
                String memberName = actionId.isBlank() ? method.getName() : method.getName() + "@WorkflowAction(" + actionId + ")";
                reviewLegalTreatmentBinding(
                        targetType,
                        memberName,
                        "@WorkflowAction",
                        action.legalPurpose(),
                        action.legalBasisRef(),
                        issues
                );
            }
        }
    }

    private void reviewLegalTreatmentBinding(
            Class<?> targetType,
            String memberName,
            String annotationName,
            org.praxisplatform.uischema.annotation.LegalTreatmentPurpose legalPurpose,
            String legalBasisRef,
            List<SemanticMetadataReviewIssue> issues
    ) {
        boolean purposeSpecified = legalPurpose != null && legalPurpose.isSpecified();
        boolean basisPresent = !normalize(legalBasisRef).isBlank();
        if (basisPresent && !purposeSpecified) {
            issues.add(new SemanticMetadataReviewIssue(
                    SemanticMetadataIssueSeverity.ERROR,
                    "legal-basis-without-legal-purpose",
                    targetType.getName(),
                    memberName,
                    annotationName + " declares legalBasisRef ('" + normalize(legalBasisRef)
                            + "') while legalPurpose remains UNSPECIFIED."
            ));
        } else if (purposeSpecified && !basisPresent) {
            issues.add(new SemanticMetadataReviewIssue(
                    SemanticMetadataIssueSeverity.ERROR,
                    "legal-purpose-without-legal-basis-ref",
                    targetType.getName(),
                    memberName,
                    annotationName + " declares legalPurpose=" + legalPurpose.name()
                            + " without an explicit legalBasisRef normative reference."
            ));
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
        DomainGovernance memberGovernance = findMemberAnnotation(declaringType, field, DomainGovernance.class);
        DomainLineage lineage = findMemberAnnotation(declaringType, field, DomainLineage.class);
        DomainGovernance governance = memberGovernance != null
                ? memberGovernance
                : findTypeAnnotation(targetType, DomainGovernance.class);

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

        reviewGovernanceAndLineage(
                targetType,
                field.getName(),
                governance,
                memberGovernance,
                lineage,
                "Field name",
                issues
        );

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

    private void reviewGetterProperty(Class<?> targetType, String propertyName, List<SemanticMetadataReviewIssue> issues) {
        Schema schema = findGetterAnnotation(targetType, propertyName, Schema.class);
        var ignored = findGetterAnnotation(targetType, propertyName, com.fasterxml.jackson.annotation.JsonIgnore.class);
        if (schema != null && schema.hidden() && ignored != null && ignored.value()) {
            return;
        }
        UISchema uiSchema = findGetterAnnotation(targetType, propertyName, UISchema.class);
        DomainGovernance memberGovernance = findGetterAnnotation(targetType, propertyName, DomainGovernance.class);
        DomainLineage lineage = findGetterAnnotation(targetType, propertyName, DomainLineage.class);
        DomainGovernance governance = memberGovernance != null
                ? memberGovernance
                : findTypeAnnotation(targetType, DomainGovernance.class);
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
        } else if (sameMeaning(description, splitCamelCase(propertyName))) {
            issues.add(new SemanticMetadataReviewIssue(
                    SemanticMetadataIssueSeverity.ERROR,
                    "schema-description-derived-from-field-name",
                    targetType.getName(),
                    propertyName,
                    "@Schema(description) appears derived from the Java property name."
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
        reviewGovernanceAndLineage(
                targetType,
                propertyName,
                governance,
                memberGovernance,
                lineage,
                "Computed getter property name",
                issues
        );
        if (uiSchema != null && uiSchema.preset() != UISchemaPreset.NONE && description.isBlank()) {
            issues.add(new SemanticMetadataReviewIssue(
                    SemanticMetadataIssueSeverity.ERROR,
                    "preset-without-domain-description",
                    targetType.getName(),
                    propertyName,
                    "UI presentation preset does not replace explicit domain documentation."
            ));
        }
    }

    private void reviewGovernanceAndLineage(
            Class<?> targetType,
            String propertyName,
            DomainGovernance effectiveOrTypeGovernance,
            DomainGovernance memberGovernance,
            DomainLineage lineage,
            String memberPrefix,
            List<SemanticMetadataReviewIssue> issues
    ) {
        if (lineage != null) {
            try {
                DomainLineageResolver.ResolvedLineage resolved = DomainLineageResolver.resolve(
                        lineage,
                        memberGovernance,
                        DomainLineageResolver.resolveTypeGovernance(targetType)
                );
                if (resolved.weakeningAttempted()) {
                    issues.add(new SemanticMetadataReviewIssue(
                            SemanticMetadataIssueSeverity.ERROR,
                            "lineage-governance-weakened",
                            targetType.getName(),
                            propertyName,
                            resolved.weakeningReason()
                    ));
                }
                if (memberGovernance != null) {
                    reviewDeclaredGovernance(targetType, propertyName, memberGovernance, issues);
                }
                reviewResolvedGovernanceNode(targetType, propertyName, resolved.effectiveGovernance(), issues);
                return;
            } catch (IllegalArgumentException ex) {
                issues.add(new SemanticMetadataReviewIssue(
                        SemanticMetadataIssueSeverity.ERROR,
                        "invalid-domain-lineage",
                        targetType.getName(),
                        propertyName,
                        ex.getMessage()
                ));
                if (effectiveOrTypeGovernance != null) {
                    reviewDeclaredGovernance(targetType, propertyName, effectiveOrTypeGovernance, issues);
                }
                return;
            }
        }

        if (looksLikePrivateContextField(propertyName) && effectiveOrTypeGovernance == null) {
            issues.add(new SemanticMetadataReviewIssue(
                    SemanticMetadataIssueSeverity.WARNING,
                    "public-private-context-field-without-governance",
                    targetType.getName(),
                    propertyName,
                    memberPrefix + " looks like private tenant/user/session context. Add governance or move it out of the public DTO."
            ));
        } else if (looksLikeSensitiveOrCredentialField(propertyName) && effectiveOrTypeGovernance == null) {
            issues.add(new SemanticMetadataReviewIssue(
                    SemanticMetadataIssueSeverity.WARNING,
                    "sensitive-field-without-governance",
                    targetType.getName(),
                    propertyName,
                    memberPrefix + " looks like PII/sensitive/credential data. Declare @DomainGovernance explicitly for catalog and policy sidecar consumption."
            ));
        } else if (effectiveOrTypeGovernance != null) {
            int beforeCount = issues.size();
            reviewDeclaredGovernance(targetType, propertyName, effectiveOrTypeGovernance, issues);
            com.fasterxml.jackson.databind.node.ObjectNode typeGov = DomainLineageResolver.resolveTypeGovernance(targetType);
            if (typeGov != null && memberGovernance != null) {
                com.fasterxml.jackson.databind.node.ObjectNode memberNode =
                        DomainLineageResolver.toGovernanceNode(memberGovernance);
                com.fasterxml.jackson.databind.node.ObjectNode merged =
                        org.praxisplatform.uischema.schema.DomainSchemaGovernance.mergeOrCopy(typeGov, memberNode);
                boolean alreadyFlaggedTraining = issues.subList(beforeCount, issues.size()).stream()
                        .anyMatch(i -> "sensitive-field-allows-ai-training".equals(i.code()));
                if (!alreadyFlaggedTraining
                        && merged != null
                        && "restricted".equals(merged.path("classification").asText(""))
                        && memberGovernance.aiUsage().trainingUse() == org.praxisplatform.uischema.annotation.AiTrainingUseMode.ALLOW) {
                    issues.add(new SemanticMetadataReviewIssue(
                            SemanticMetadataIssueSeverity.ERROR,
                            "sensitive-field-allows-ai-training",
                            targetType.getName(),
                            propertyName,
                            "Sensitive/LGPD/restricted property must not allow AI training (trainingUse = ALLOW)."
                    ));
                }
            }
        }
    }

    private void reviewResolvedGovernanceNode(
            Class<?> targetType,
            String propertyName,
            com.fasterxml.jackson.databind.node.ObjectNode governanceNode,
            List<SemanticMetadataReviewIssue> issues
    ) {
        if (governanceNode == null) {
            return;
        }
        String classification = governanceNode.path("classification").asText("internal");
        String category = governanceNode.path("dataCategory").asText("operational");
        String trainingUse = governanceNode.path("aiUsage").path("trainingUse").asText("deny");

        boolean sensitiveOrCredential = looksLikeSensitiveOrCredentialField(propertyName)
                || "sensitive_personal".equals(category)
                || "credential".equals(category);
        if (sensitiveOrCredential && "public".equals(classification)) {
            issues.add(new SemanticMetadataReviewIssue(
                    SemanticMetadataIssueSeverity.ERROR,
                    "sensitive-field-public-classification",
                    targetType.getName(),
                    propertyName,
                    "Sensitive/LGPD/credential property cannot resolve to classification = PUBLIC."
            ));
        }
        if ((sensitiveOrCredential || "restricted".equals(classification)) && "allow".equals(trainingUse)) {
            issues.add(new SemanticMetadataReviewIssue(
                    SemanticMetadataIssueSeverity.ERROR,
                    "sensitive-field-allows-ai-training",
                    targetType.getName(),
                    propertyName,
                    "Sensitive/LGPD/restricted property must not allow AI training (trainingUse = ALLOW)."
            ));
        }
    }

    private void reviewDeclaredGovernance(
            Class<?> targetType,
            String propertyName,
            DomainGovernance governance,
            List<SemanticMetadataReviewIssue> issues
    ) {
        var preset = governance.preset();
        boolean hasPreset = preset != null && preset != org.praxisplatform.uischema.annotation.DomainGovernancePreset.NONE;
        var effectiveClassification = hasPreset && governance.classification() == org.praxisplatform.uischema.annotation.DomainClassification.INTERNAL
                ? preset.classification() : governance.classification();
        var effectiveCategory = hasPreset && governance.dataCategory() == org.praxisplatform.uischema.annotation.DomainDataCategory.OPERATIONAL
                ? preset.dataCategory() : governance.dataCategory();
        var ai = governance.aiUsage();
        boolean isDefaultAiUsage = ai.visibility() == org.praxisplatform.uischema.annotation.AiVisibilityMode.ALLOW
                && ai.trainingUse() == org.praxisplatform.uischema.annotation.AiTrainingUseMode.DENY
                && ai.ruleAuthoring() == org.praxisplatform.uischema.annotation.AiControlledUseMode.REVIEW_REQUIRED
                && ai.reasoningUse() == org.praxisplatform.uischema.annotation.AiControlledUseMode.ALLOW;
        var effectiveTraining = hasPreset && isDefaultAiUsage ? preset.aiTrainingUse() : ai.trainingUse();

        boolean sensitiveOrCredential = looksLikeSensitiveOrCredentialField(propertyName)
                || effectiveCategory == org.praxisplatform.uischema.annotation.DomainDataCategory.SENSITIVE_PERSONAL
                || effectiveCategory == org.praxisplatform.uischema.annotation.DomainDataCategory.CREDENTIAL;
        if (sensitiveOrCredential && effectiveClassification == org.praxisplatform.uischema.annotation.DomainClassification.PUBLIC) {
            issues.add(new SemanticMetadataReviewIssue(
                    SemanticMetadataIssueSeverity.ERROR,
                    "sensitive-field-public-classification",
                    targetType.getName(),
                    propertyName,
                    "Sensitive/LGPD/credential property cannot declare @DomainGovernance(classification = PUBLIC)."
            ));
        }
        if ((sensitiveOrCredential || effectiveClassification == org.praxisplatform.uischema.annotation.DomainClassification.RESTRICTED)
                && effectiveTraining == org.praxisplatform.uischema.annotation.AiTrainingUseMode.ALLOW) {
            issues.add(new SemanticMetadataReviewIssue(
                    SemanticMetadataIssueSeverity.ERROR,
                    "sensitive-field-allows-ai-training",
                    targetType.getName(),
                    propertyName,
                    "Sensitive/LGPD/restricted property must not allow AI training (trainingUse = ALLOW)."
            ));
        }
    }

    private <A extends Annotation> A findMemberAnnotation(Class<?> targetType, Field field, Class<A> annotationType) {
        String propertyName = field.getName();
        Class<?> current = targetType != null ? targetType : field.getDeclaringClass();
        while (current != null && current != Object.class && current != Record.class) {
            A fromGetter = findDeclaredAccessorAnnotation(current, propertyName, annotationType);
            if (fromGetter != null) {
                return fromGetter;
            }
            if (current == field.getDeclaringClass()) {
                A direct = org.praxisplatform.uischema.extension.ResolverUtils.getAnnotation(annotationType, field.getAnnotations());
                if (direct != null) {
                    return direct;
                }
                if (current.isRecord() && current.getRecordComponents() != null) {
                    for (RecordComponent component : current.getRecordComponents()) {
                        if (component.getName().equals(propertyName)) {
                            A fromComponent = org.praxisplatform.uischema.extension.ResolverUtils.getAnnotation(annotationType, component.getAnnotations());
                            if (fromComponent != null) {
                                return fromComponent;
                            }
                            Method accessor = component.getAccessor();
                            if (accessor != null) {
                                A fromAccessor = org.praxisplatform.uischema.extension.ResolverUtils.getAnnotation(annotationType, accessor.getAnnotations());
                                if (fromAccessor != null) {
                                    return fromAccessor;
                                }
                            }
                        }
                    }
                }
            }
            current = current.getSuperclass();
        }
        return org.praxisplatform.uischema.extension.ResolverUtils.getAnnotation(annotationType, field.getAnnotations());
    }

    private <A extends Annotation> A findGetterAnnotation(Class<?> targetType, String propertyName, Class<A> annotationType) {
        Class<?> current = targetType;
        while (current != null && current != Object.class && current != Record.class) {
            A found = findDeclaredAccessorAnnotation(current, propertyName, annotationType);
            if (found != null) {
                return found;
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private <A extends Annotation> A findDeclaredAccessorAnnotation(Class<?> type, String propertyName, Class<A> annotationType) {
        try {
            Method accessor = type.getDeclaredMethod(propertyName);
            A fromMethod = org.praxisplatform.uischema.extension.ResolverUtils.getAnnotation(annotationType, accessor.getAnnotations());
            if (fromMethod != null) {
                return fromMethod;
            }
        } catch (NoSuchMethodException ignored) {
            // Not a record-style accessor; check standard JavaBean getter below.
        }
        String capitalized = propertyName.isEmpty()
                ? ""
                : Character.toUpperCase(propertyName.charAt(0)) + propertyName.substring(1);
        for (String prefix : List.of("get", "is")) {
            try {
                Method getter = type.getDeclaredMethod(prefix + capitalized);
                A fromGetter = org.praxisplatform.uischema.extension.ResolverUtils.getAnnotation(annotationType, getter.getAnnotations());
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
        for (String token : List.of(
                "cpf", "cnpj", "pasep", "senha", "password", "secret", "apikey", "clientsecret",
                "privatekey", "creditcard", "chavepix", "gruposanguineo", "deficiencia",
                "tituloeleitoral", "biometria", "filiacaosindical")) {
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

    private String normalizeGroupKey(String value) {
        String decomposed = java.text.Normalizer.normalize(normalize(value), java.text.Normalizer.Form.NFD);
        return decomposed
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
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
