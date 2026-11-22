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
    private static final org.praxisplatform.uischema.validation.JsonLogicExpressionValidator JSON_LOGIC_VALIDATOR =
            new org.praxisplatform.uischema.validation.JsonLogicExpressionValidator();

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
            currentType = currentType.getSuperclass();
        }

        reviewResourceFactsHierarchy(
                targetType,
                resolveOwnerApiResourceKey(targetType),
                allPropertyNames,
                new java.util.LinkedHashSet<>(),
                false,
                issues
        );

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
        reviewTableView(targetType, allPropertyNames, issues);
        reviewUiFormLayout(targetType, issues);
        reviewDomainVisualizers(targetType, allPropertyNames, issues);
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
                if (source == null) {
                    continue;
                }
                org.praxisplatform.uischema.options.OptionSourceType type = source.type();
                if (type != org.praxisplatform.uischema.options.OptionSourceType.DISTINCT_DIMENSION
                        && type != org.praxisplatform.uischema.options.OptionSourceType.CATEGORICAL_BUCKET
                        && type != org.praxisplatform.uischema.options.OptionSourceType.RESOURCE_ENTITY
                        && type != org.praxisplatform.uischema.options.OptionSourceType.LIGHT_LOOKUP) {
                    continue;
                }
                java.util.Map<String, java.util.Set<String>> matchedSectionsByProp = new java.util.LinkedHashMap<>();
                collectRestrictedOptionSourceMatches(
                        aliasToRestrictedProperty, "descriptor", matchedSectionsByProp,
                        source.key(),
                        source.filterField(),
                        source.propertyPath(),
                        source.labelPropertyPath(),
                        source.valuePropertyPath()
                );

                org.praxisplatform.uischema.options.EntityLookupDescriptor lookup = source.entityLookup();
                if (lookup != null) {
                    collectRestrictedOptionSourceMatches(
                            aliasToRestrictedProperty, "codePropertyPath", matchedSectionsByProp,
                            lookup.codePropertyPath());
                    collectRestrictedOptionSourceMatches(
                            aliasToRestrictedProperty, "statusPropertyPath", matchedSectionsByProp,
                            lookup.statusPropertyPath());
                    if (lookup.descriptionPropertyPaths() != null) {
                        collectRestrictedOptionSourceMatches(
                                aliasToRestrictedProperty, "descriptionPropertyPaths", matchedSectionsByProp,
                                lookup.descriptionPropertyPaths().toArray(String[]::new));
                    }
                    if (lookup.searchPropertyPaths() != null) {
                        collectRestrictedOptionSourceMatches(
                                aliasToRestrictedProperty, "searchPropertyPaths", matchedSectionsByProp,
                                lookup.searchPropertyPaths().toArray(String[]::new));
                    }
                    org.praxisplatform.uischema.options.LookupDisplayDescriptor display = lookup.display();
                    if (display != null) {
                        collectRestrictedOptionSourceMatches(
                                aliasToRestrictedProperty, "display.primaryPropertyPath", matchedSectionsByProp,
                                display.primaryPropertyPath());
                        collectRestrictedOptionSourceMatches(
                                aliasToRestrictedProperty, "display.avatarPropertyPath", matchedSectionsByProp,
                                display.avatarPropertyPath());
                        if (display.secondaryPropertyPaths() != null) {
                            collectRestrictedOptionSourceMatches(
                                    aliasToRestrictedProperty, "display.secondaryPropertyPaths", matchedSectionsByProp,
                                    display.secondaryPropertyPaths().toArray(String[]::new));
                        }
                        if (display.badgePropertyPaths() != null) {
                            collectRestrictedOptionSourceMatches(
                                    aliasToRestrictedProperty, "display.badgePropertyPaths", matchedSectionsByProp,
                                    display.badgePropertyPaths().toArray(String[]::new));
                        }
                        if (display.fields() != null) {
                            for (org.praxisplatform.uischema.options.LookupDisplayFieldDescriptor field : display.fields()) {
                                if (field != null) {
                                    collectRestrictedOptionSourceMatches(
                                            aliasToRestrictedProperty, "display.fields", matchedSectionsByProp,
                                            field.key(), field.propertyPath());
                                }
                            }
                        }
                    }
                    org.praxisplatform.uischema.options.LookupDialogDescriptor dialog = lookup.dialog();
                    if (dialog != null && dialog.resultColumns() != null) {
                        for (org.praxisplatform.uischema.options.LookupResultColumnDescriptor col : dialog.resultColumns()) {
                            if (col != null) {
                                collectRestrictedOptionSourceMatches(
                                        aliasToRestrictedProperty, "dialog.resultColumns", matchedSectionsByProp,
                                        col.field());
                            }
                        }
                    }
                }

                org.praxisplatform.uischema.options.LookupFilteringDescriptor filtering = source.effectiveFiltering();
                if (filtering != null) {
                    if (filtering.availableFilters() != null) {
                        for (org.praxisplatform.uischema.options.LookupFilterDefinition filterDef : filtering.availableFilters()) {
                            if (filterDef != null) {
                                collectRestrictedOptionSourceMatches(
                                        aliasToRestrictedProperty, "filtering.availableFilters", matchedSectionsByProp,
                                        filterDef.field(), filterDef.effectiveEntityPropertyPath());
                            }
                        }
                    }
                    if (filtering.quickFilterFields() != null) {
                        collectRestrictedOptionSourceMatches(
                                aliasToRestrictedProperty, "filtering.quickFilterFields", matchedSectionsByProp,
                                filtering.quickFilterFields().toArray(String[]::new));
                    }
                    if (filtering.sortOptions() != null) {
                        for (org.praxisplatform.uischema.options.LookupSortOption sortOption : filtering.sortOptions()) {
                            if (sortOption != null) {
                                collectRestrictedOptionSourceMatches(
                                        aliasToRestrictedProperty, "filtering.sortOptions", matchedSectionsByProp,
                                        sortOption.field());
                            }
                        }
                    }
                }

                for (var matchEntry : matchedSectionsByProp.entrySet()) {
                    String matchedProp = matchEntry.getKey();
                    com.fasterxml.jackson.databind.node.ObjectNode gov = restrictedProperties.get(matchedProp);
                    issues.add(new SemanticMetadataReviewIssue(
                            SemanticMetadataIssueSeverity.ERROR,
                            "restricted-field-in-analytics-or-option-source",
                            targetType.getName(),
                            matchedProp,
                            "Property '" + matchedProp + "' is governed as restricted/sensitive (classification="
                                    + gov.path("classification").asText()
                                    + ", dataCategory=" + gov.path("dataCategory").asText()
                                    + ") and cannot be exposed in " + type
                                    + " option source '" + source.key() + "' ("
                                    + String.join(", ", matchEntry.getValue()) + ")."
                    ));
                }
            }
        }
    }

    private void collectRestrictedOptionSourceMatches(
            java.util.Map<String, String> aliasToRestrictedProperty,
            String section,
            java.util.Map<String, java.util.Set<String>> matchedSectionsByProp,
            String... candidates
    ) {
        if (candidates == null) {
            return;
        }
        for (String candidate : candidates) {
            String matchedProp = matchRestrictedProperty(aliasToRestrictedProperty, candidate);
            if (matchedProp != null) {
                matchedSectionsByProp
                        .computeIfAbsent(matchedProp, ignored -> new java.util.LinkedHashSet<>())
                        .add(section);
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
            String detailPresentation = normalize(ref.detailPresentation());
            if (!detailPresentation.isBlank()
                    && !Set.of("drawer", "modal").contains(detailPresentation.toLowerCase(Locale.ROOT))) {
                issues.add(new SemanticMetadataReviewIssue(
                        SemanticMetadataIssueSeverity.ERROR,
                        "resource-reference-detail-presentation-invalid",
                        targetType.getName(),
                        propName,
                        "@ResourceReference.detailPresentation '" + detailPresentation + "' on '" + propName
                                + "' must be one of [drawer, modal]."
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
        String ownerResourceKey = resolveOwnerApiResourceKey(targetType);
        Class<?> responseDto = resolveControllerResponseDtoType(targetType);
        boolean controllerHost = findTypeAnnotation(targetType, org.praxisplatform.uischema.annotation.ApiResource.class) != null
                || findTypeAnnotation(targetType, org.springframework.web.bind.annotation.RestController.class) != null
                || findTypeAnnotation(targetType, org.springframework.stereotype.Controller.class) != null;
        Set<String> knownDtoProperties = responseDto != null && responseDto != targetType
                ? collectDtoPropertyNames(responseDto)
                : (controllerHost ? Set.of() : collectDtoPropertyNames(targetType));
        Set<String> seenFactKeys = new java.util.LinkedHashSet<>();
        reviewResourceFactsHierarchy(targetType, ownerResourceKey, knownDtoProperties, seenFactKeys, true, issues);
        if (responseDto != null && responseDto != targetType) {
            reviewResourceFactsHierarchy(responseDto, ownerResourceKey, knownDtoProperties, seenFactKeys, false, issues);
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
            reviewLegalTreatmentBindingsOnType(controllerClass, currentType, issues);
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
                        String[] tokens = org.praxisplatform.uischema.schema.ApiResourceQuickFilterResolver
                                .isCollectionOrArrayField(targetField)
                                ? rawVal.split(",", -1)
                                : new String[]{rawVal};
                        validateRangeOperationArity(
                                controllerClass,
                                memberName,
                                fieldName,
                                effectiveFilterDto,
                                targetField,
                                tokens.length,
                                issues
                        );
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
                            validateQuickFilterResolvedValueToken(
                                    controllerClass,
                                    memberName,
                                    effectiveFilterDto,
                                    targetField,
                                    valueType,
                                    token,
                                    issues
                            );
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
                    if (effectiveFilterDto != null && descriptor.explicitCriteria() != null) {
                        for (java.util.Map.Entry<String, Object> rawEntry : descriptor.explicitCriteria().entrySet()) {
                            String rawFieldName = normalize(rawEntry.getKey());
                            Object preCoercionValue = rawEntry.getValue();
                            if (rawFieldName.isBlank() || preCoercionValue == null) {
                                continue;
                            }
                            Field rawTargetField = filterableFields.get(rawFieldName);
                            if (rawTargetField == null) {
                                rawTargetField = allFilterFields.get(rawFieldName);
                            }
                            if (rawTargetField != null
                                    && org.praxisplatform.uischema.schema.ApiResourceQuickFilterResolver
                                            .isCollectionOrArrayField(rawTargetField)
                                    && !(preCoercionValue instanceof java.util.Collection<?>)
                                    && !preCoercionValue.getClass().isArray()) {
                                issues.add(new SemanticMetadataReviewIssue(
                                        SemanticMetadataIssueSeverity.ERROR,
                                        "quick-filter-criterion-cardinality-mismatch",
                                        controllerClass.getName(),
                                        memberName,
                                        memberName + " supplies scalar value for collection field '" + rawFieldName
                                                + "' on " + effectiveFilterDto.getSimpleName() + "."
                                ));
                            }
                        }
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
                        for (java.util.Map.Entry<?, ?> entry : filterMap.entrySet()) {
                            String fieldName = normalize(String.valueOf(entry.getKey()));
                            Object rawValObj = entry.getValue();
                            if (fieldName.isBlank() || rawValObj == null || rawValObj instanceof java.util.Map<?, ?>) {
                                issues.add(new SemanticMetadataReviewIssue(
                                        SemanticMetadataIssueSeverity.ERROR,
                                        "quick-filter-expression-malformed",
                                        controllerClass.getName(),
                                        memberName,
                                        memberName + " declares invalid filter entry for '" + fieldName + "'."
                                ));
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
                            if (targetField != null) {
                                validateProgrammaticQuickFilterEntryValue(
                                        controllerClass,
                                        memberName,
                                        fieldName,
                                        effectiveFilterDto,
                                        targetField,
                                        rawValObj,
                                        issues
                                );
                            }
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
            if (effectiveResponseDto == null
                    && dtoIdentity == null
                    && (controllerIdentity == null
                        || normalize(controllerIdentity.keyField()).isBlank()
                        || normalize(controllerIdentity.titleField()).isBlank())) {
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

        Class<?> effectiveCreateDto = resolveControllerCreateDtoType(controllerClass);
        Class<?> effectiveUpdateDto = resolveControllerUpdateDtoType(controllerClass);
        String ownerResourceKey = apiResource == null ? "" : normalize(apiResource.resourceKey());
        Set<String> responseProperties = collectDtoPropertyNames(effectiveResponseDto);
        Set<String> seenFactKeys = new java.util.LinkedHashSet<>();
        reviewResourceFactsHierarchy(
                controllerClass,
                ownerResourceKey,
                responseProperties,
                seenFactKeys,
                false,
                issues
        );
        if (effectiveResponseDto != null && effectiveResponseDto != controllerClass) {
            reviewResourceFactsHierarchy(
                    effectiveResponseDto,
                    ownerResourceKey,
                    responseProperties,
                    seenFactKeys,
                    false,
                    issues
            );
        }
        reviewReactiveDeterminations(
                controllerClass,
                effectiveResponseDto,
                effectiveCreateDto,
                effectiveUpdateDto,
                issues
        );
        reviewBulkResourceOperations(
                controllerClass,
                effectiveResponseDto,
                effectiveCreateDto,
                effectiveUpdateDto,
                issues
        );

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
     * Reviews all {@link org.praxisplatform.uischema.options.OptionSourceDescriptor} entries in an
     * {@link org.praxisplatform.uischema.options.OptionSourceRegistry}, validating each source's
     * {@code filtering.availableFilters} against the corresponding {@code FilterDTO} in
     * {@code filterDtosBySourceKey} when provided.
     */
    public SemanticMetadataReviewReport reviewOptionSourceRegistry(
            org.praxisplatform.uischema.options.OptionSourceRegistry registry,
            java.util.Map<String, Class<?>> filterDtosBySourceKey
    ) {
        if (registry == null || registry.isEmpty()) {
            return new SemanticMetadataReviewReport(org.praxisplatform.uischema.options.OptionSourceRegistry.class, List.of());
        }
        List<SemanticMetadataReviewIssue> issues = new ArrayList<>();
        for (org.praxisplatform.uischema.options.OptionSourceDescriptor descriptor : registry.descriptors()) {
            reviewOptionSourceDescriptorInto(descriptor, issues);
            if (descriptor != null && filterDtosBySourceKey != null) {
                Class<?> filterDto = filterDtosBySourceKey.get(descriptor.key());
                if (filterDto != null) {
                    reviewOptionSourceFilteringAgainstFilterDto(descriptor, filterDto, issues);
                }
            }
        }
        return new SemanticMetadataReviewReport(org.praxisplatform.uischema.options.OptionSourceRegistry.class, issues);
    }

    /**
     * Reviews all {@link org.praxisplatform.uischema.options.OptionSourceDescriptor} entries in an
     * {@link org.praxisplatform.uischema.options.OptionSourceRegistry} against a shared {@code filterDtoClass}.
     */
    public SemanticMetadataReviewReport reviewOptionSourceRegistry(
            org.praxisplatform.uischema.options.OptionSourceRegistry registry,
            Class<?> filterDtoClass
    ) {
        if (registry == null || registry.isEmpty()) {
            return new SemanticMetadataReviewReport(org.praxisplatform.uischema.options.OptionSourceRegistry.class, List.of());
        }
        List<SemanticMetadataReviewIssue> issues = new ArrayList<>();
        for (org.praxisplatform.uischema.options.OptionSourceDescriptor descriptor : registry.descriptors()) {
            reviewOptionSourceDescriptorInto(descriptor, issues);
            if (descriptor != null && filterDtoClass != null) {
                reviewOptionSourceFilteringAgainstFilterDto(descriptor, filterDtoClass, issues);
            }
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
        return reviewOptionSourceDescriptor(descriptor, null);
    }

    /**
     * Reviews a single {@link org.praxisplatform.uischema.options.OptionSourceDescriptor} and
     * validates its {@code filtering.availableFilters} against {@code filterDtoClass} when provided.
     */
    public SemanticMetadataReviewReport reviewOptionSourceDescriptor(
            org.praxisplatform.uischema.options.OptionSourceDescriptor descriptor,
            Class<?> filterDtoClass
    ) {
        if (descriptor == null) {
            return new SemanticMetadataReviewReport(org.praxisplatform.uischema.options.OptionSourceDescriptor.class, List.of());
        }
        List<SemanticMetadataReviewIssue> issues = new ArrayList<>();
        reviewOptionSourceDescriptorInto(descriptor, issues);
        if (filterDtoClass != null) {
            reviewOptionSourceFilteringAgainstFilterDto(descriptor, filterDtoClass, issues);
        }
        return new SemanticMetadataReviewReport(org.praxisplatform.uischema.options.OptionSourceDescriptor.class, issues);
    }

    private void reviewOptionSourceFilteringAgainstFilterDto(
            org.praxisplatform.uischema.options.OptionSourceDescriptor descriptor,
            Class<?> filterDtoClass,
            List<SemanticMetadataReviewIssue> issues
    ) {
        if (descriptor == null || filterDtoClass == null) {
            return;
        }
        org.praxisplatform.uischema.options.LookupFilteringDescriptor filtering = descriptor.effectiveFiltering();
        if (filtering == null || filtering.availableFilters() == null || filtering.availableFilters().isEmpty()) {
            return;
        }
        String memberPrefix = normalize(descriptor.key()).isBlank() ? "<unnamed>" : normalize(descriptor.key());
        String typeName = normalize(descriptor.resourcePath()).isBlank()
                ? filterDtoClass.getName()
                : normalize(descriptor.resourcePath());

        java.util.Set<String> allFilterProps = collectDtoPropertyNames(filterDtoClass);
        java.util.Map<String, org.praxisplatform.uischema.filter.annotation.Filterable> filterables =
                collectFilterableAnnotations(filterDtoClass);
        java.util.Set<String> declaredFilterFields = new java.util.LinkedHashSet<>();
        for (org.praxisplatform.uischema.options.LookupFilterDefinition def : filtering.availableFilters()) {
            if (def != null && def.field() != null) {
                declaredFilterFields.add(def.field());
            }
        }

        for (org.praxisplatform.uischema.options.LookupFilterDefinition def : filtering.availableFilters()) {
            if (def == null) {
                continue;
            }
            String fieldName = def.field();
            if (!allFilterProps.contains(fieldName)) {
                issues.add(new SemanticMetadataReviewIssue(
                        SemanticMetadataIssueSeverity.ERROR,
                        "option-source-filter-field-unknown",
                        typeName,
                        memberPrefix + ".filtering.availableFilters." + fieldName,
                        "Option source '" + memberPrefix + "' declares availableFilter '" + fieldName
                                + "' which does not exist on FilterDTO " + filterDtoClass.getSimpleName() + "."
                ));
                continue;
            }
            org.praxisplatform.uischema.filter.annotation.Filterable filterable = filterables.get(fieldName);
            if (filterable == null) {
                issues.add(new SemanticMetadataReviewIssue(
                        SemanticMetadataIssueSeverity.ERROR,
                        "option-source-filter-field-not-filterable",
                        typeName,
                        memberPrefix + ".filtering.availableFilters." + fieldName,
                        "Option source '" + memberPrefix + "' declares availableFilter '" + fieldName
                                + "' which lacks @Filterable on FilterDTO " + filterDtoClass.getSimpleName() + "."
                ));
            } else {
                String relation = normalize(filterable.relation());
                if (!relation.isBlank()
                        && def.targetPropertyPath() != null
                        && !relation.equals(def.targetPropertyPath())) {
                    issues.add(new SemanticMetadataReviewIssue(
                            SemanticMetadataIssueSeverity.ERROR,
                            "option-source-filter-relation-mismatch",
                            typeName,
                            memberPrefix + ".filtering.availableFilters." + fieldName,
                            "Option source '" + memberPrefix + "' filter '" + fieldName
                                    + "' targets '" + def.targetPropertyPath()
                                    + "' which does not match @Filterable(relation = \"" + relation + "\") on "
                                    + filterDtoClass.getSimpleName() + "."
                    ));
                }
            }
            if (def.dependencyFilterMap() != null) {
                for (String depKey : def.dependencyFilterMap().keySet()) {
                    if (def.dependsOn() == null || !def.dependsOn().contains(depKey)) {
                        issues.add(new SemanticMetadataReviewIssue(
                                SemanticMetadataIssueSeverity.ERROR,
                                "option-source-filter-dependency-undeclared",
                                typeName,
                                memberPrefix + ".filtering.availableFilters." + fieldName,
                                "Option source '" + memberPrefix + "' filter '" + fieldName
                                        + "' maps dependency '" + depKey + "' not declared in dependsOn."
                        ));
                    }
                }
            }
            if (def.dependsOn() != null) {
                for (String dep : def.dependsOn()) {
                    if (!allFilterProps.contains(dep) && !declaredFilterFields.contains(dep)) {
                        issues.add(new SemanticMetadataReviewIssue(
                                SemanticMetadataIssueSeverity.ERROR,
                                "option-source-filter-dependency-unknown",
                                typeName,
                                memberPrefix + ".filtering.availableFilters." + fieldName,
                                "Option source '" + memberPrefix + "' filter '" + fieldName
                                        + "' declares dependsOn '" + dep + "' which does not exist on "
                                        + filterDtoClass.getSimpleName() + "."
                        ));
                    }
                }
            }
        }
    }

    private java.util.Map<String, org.praxisplatform.uischema.filter.annotation.Filterable> collectFilterableAnnotations(
            Class<?> dtoClass
    ) {
        java.util.Map<String, org.praxisplatform.uischema.filter.annotation.Filterable> result =
                new java.util.LinkedHashMap<>();
        if (dtoClass == null) {
            return result;
        }
        for (Class<?> curr = dtoClass; curr != null && curr != Object.class && curr != Record.class; curr = curr.getSuperclass()) {
            if (curr.isRecord() && curr.getRecordComponents() != null) {
                for (RecordComponent rc : curr.getRecordComponents()) {
                    org.praxisplatform.uischema.filter.annotation.Filterable ann =
                            rc.getAnnotation(org.praxisplatform.uischema.filter.annotation.Filterable.class);
                    if (ann != null) {
                        result.putIfAbsent(rc.getName(), ann);
                    }
                }
            }
            for (Field field : curr.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                    continue;
                }
                org.praxisplatform.uischema.filter.annotation.Filterable ann =
                        field.getAnnotation(org.praxisplatform.uischema.filter.annotation.Filterable.class);
                if (ann != null) {
                    result.putIfAbsent(field.getName(), ann);
                }
            }
            for (Method method : curr.getDeclaredMethods()) {
                if (Modifier.isStatic(method.getModifiers())
                        || method.isSynthetic()
                        || method.isBridge()
                        || method.getParameterCount() != 0
                        || method.getReturnType() == void.class) {
                    continue;
                }
                String propName = resolveGetterPropertyNameForFilter(curr, method);
                if (propName != null) {
                    org.praxisplatform.uischema.filter.annotation.Filterable ann =
                            method.getAnnotation(org.praxisplatform.uischema.filter.annotation.Filterable.class);
                    if (ann != null) {
                        result.putIfAbsent(propName, ann);
                    }
                }
            }
        }
        return result;
    }

    private static String resolveGetterPropertyNameForFilter(Class<?> owner, Method method) {
        String name = method.getName();
        if ("getClass".equals(name)) {
            return null;
        }
        if (owner.isRecord()) {
            return name;
        }
        if (name.startsWith("get") && name.length() > 3) {
            return Character.toLowerCase(name.charAt(3)) + name.substring(4);
        }
        if (name.startsWith("is") && name.length() > 2
                && (method.getReturnType() == boolean.class || method.getReturnType() == Boolean.class)) {
            return Character.toLowerCase(name.charAt(2)) + name.substring(3);
        }
        return null;
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
            UISchema uiSchema = findMemberAnnotation(effectiveFilterDto, declaredField, UISchema.class);
            if (uiSchema != null
                    && uiSchema.controlType() == org.praxisplatform.uischema.FieldControlType.INLINE_RELATIVE_PERIOD) {
                targetField = declaredField;
            }
        }
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
            validateRangeOperationArity(
                    controllerClass,
                    memberName,
                    fieldName,
                    effectiveFilterDto,
                    targetField,
                    valueLength,
                    issues
            );

            Class<?> valueType = resolveFilterFieldScalarType(targetField);
            if (hasBool) {
                boolean containsFalse = false;
                for (boolean b : criterion.bool()) {
                    containsFalse |= !b;
                }
                if (containsFalse) {
                    checkPresenceOperationFalse(
                            controllerClass,
                            memberName,
                            fieldName,
                            effectiveFilterDto,
                            targetField,
                            "uses bool = false",
                            issues
                    );
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
                        validateQuickFilterResolvedValueToken(
                                controllerClass,
                                memberName,
                                effectiveFilterDto,
                                targetField,
                                valueType,
                                Long.toString(num),
                                issues
                        );
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
                } else {
                    for (double d : criterion.decimal()) {
                        if (!Double.isFinite(d)) {
                            issues.add(new SemanticMetadataReviewIssue(
                                    SemanticMetadataIssueSeverity.ERROR,
                                    "quick-filter-numeric-value-invalid",
                                    controllerClass.getName(),
                                    memberName,
                                    memberName + " @FilterCriterion('" + fieldName
                                            + "') contains non-finite decimal value."
                            ));
                        }
                    }
                }
            } else {
                for (String rawToken : criterion.values()) {
                    if (rawToken != null && !rawToken.trim().isEmpty()) {
                        validateQuickFilterResolvedValueToken(
                                controllerClass,
                                memberName,
                                effectiveFilterDto,
                                targetField,
                                valueType,
                                rawToken.trim(),
                                issues
                        );
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
                if (!org.praxisplatform.uischema.schema.ApiResourceQuickFilterResolver.isCollectionOrArrayField(targetField)
                        || col.isEmpty()) {
                    issues.add(new SemanticMetadataReviewIssue(
                            SemanticMetadataIssueSeverity.ERROR,
                            "quick-filter-expression-malformed",
                            controllerClass.getName(),
                            memberName,
                            memberName + " declares invalid collection value for '" + fieldName + "' in '" + expr + "'."
                    ));
                    continue;
                }
                validateRangeOperationArity(
                        controllerClass,
                        memberName,
                        fieldName,
                        effectiveFilterDto,
                        targetField,
                        col.size(),
                        issues
                );
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
                    validateQuickFilterResolvedValueToken(
                            controllerClass,
                            memberName,
                            effectiveFilterDto,
                            targetField,
                            valueType,
                            token,
                            issues
                    );
                }
            } else {
                if (org.praxisplatform.uischema.schema.ApiResourceQuickFilterResolver.isCollectionOrArrayField(targetField)) {
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
                validateRangeOperationArity(
                        controllerClass,
                        memberName,
                        fieldName,
                        effectiveFilterDto,
                        targetField,
                        1,
                        issues
                );
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
                validateQuickFilterResolvedValueToken(
                        controllerClass,
                        memberName,
                        effectiveFilterDto,
                        targetField,
                        valueType,
                        token,
                        issues
                );
            }
        }
    }

    private boolean isRelativePeriodPresetField(Class<?> effectiveFilterDto, Field field) {
        if (field == null) {
            return false;
        }
        if (findMemberAnnotation(effectiveFilterDto, field, org.praxisplatform.uischema.filter.annotation.Filterable.class) != null) {
            return false;
        }
        UISchema uiSchema = findMemberAnnotation(effectiveFilterDto, field, UISchema.class);
        return uiSchema != null
                && uiSchema.controlType() == org.praxisplatform.uischema.FieldControlType.INLINE_RELATIVE_PERIOD;
    }

    private void checkPresenceOperationFalse(
            Class<?> controllerClass,
            String memberName,
            String fieldName,
            Class<?> effectiveFilterDto,
            Field targetField,
            String contextLabel,
            List<SemanticMetadataReviewIssue> issues
    ) {
        org.praxisplatform.uischema.filter.annotation.Filterable targetFilterable =
                findMemberAnnotation(effectiveFilterDto, targetField, org.praxisplatform.uischema.filter.annotation.Filterable.class);
        String operation = targetFilterable == null ? "" : String.valueOf(targetFilterable.operation());
        boolean presenceOperation = "IS_TRUE".equals(operation) || "IS_FALSE".equals(operation)
                || "IS_NULL".equals(operation) || "IS_NOT_NULL".equals(operation);
        if (presenceOperation) {
            issues.add(new SemanticMetadataReviewIssue(
                    SemanticMetadataIssueSeverity.ERROR,
                    "quick-filter-presence-operation-false",
                    controllerClass.getName(),
                    memberName,
                    memberName + " '" + fieldName + "' " + contextLabel + " on @Filterable(operation = "
                            + operation + "); the operation ignores false values (IS_TRUE/IS_FALSE apply on any non-null"
                            + " value and IS_NULL/IS_NOT_NULL become no-op), so the quick filter would not express"
                            + " the intended slice. Target the complementary field/operation instead."
            ));
        }
    }

    private void validateRangeOperationArity(
            Class<?> controllerClass,
            String memberName,
            String fieldName,
            Class<?> effectiveFilterDto,
            Field targetField,
            int elementCount,
            List<SemanticMetadataReviewIssue> issues
    ) {
        org.praxisplatform.uischema.filter.annotation.Filterable targetFilterable =
                findMemberAnnotation(effectiveFilterDto, targetField, org.praxisplatform.uischema.filter.annotation.Filterable.class);
        if (targetFilterable == null) {
            return;
        }
        org.praxisplatform.uischema.filter.annotation.Filterable.FilterOperation operation = targetFilterable.operation();
        boolean isRangeOperation =
                operation == org.praxisplatform.uischema.filter.annotation.Filterable.FilterOperation.BETWEEN
                        || operation == org.praxisplatform.uischema.filter.annotation.Filterable.FilterOperation.NOT_BETWEEN
                        || operation == org.praxisplatform.uischema.filter.annotation.Filterable.FilterOperation.OUTSIDE_RANGE
                        || operation == org.praxisplatform.uischema.filter.annotation.Filterable.FilterOperation.BETWEEN_EXCLUSIVE;
        if (isRangeOperation
                && !org.praxisplatform.uischema.schema.ApiResourceQuickFilterResolver
                        .isCollectionOrArrayField(targetField)) {
            issues.add(new SemanticMetadataReviewIssue(
                    SemanticMetadataIssueSeverity.ERROR,
                    "quick-filter-criterion-cardinality-mismatch",
                    controllerClass.getName(),
                    memberName,
                    memberName + " criterion targeting '" + fieldName + "' uses range operation "
                            + operation.name() + ", which requires a Collection/List field on "
                            + effectiveFilterDto.getSimpleName() + " (found "
                            + targetField.getType().getSimpleName() + ")."
            ));
            return;
        }
        if ((operation == org.praxisplatform.uischema.filter.annotation.Filterable.FilterOperation.BETWEEN
                || operation == org.praxisplatform.uischema.filter.annotation.Filterable.FilterOperation.NOT_BETWEEN
                || operation == org.praxisplatform.uischema.filter.annotation.Filterable.FilterOperation.OUTSIDE_RANGE)
                && elementCount > 2) {
            issues.add(new SemanticMetadataReviewIssue(
                    SemanticMetadataIssueSeverity.ERROR,
                    "quick-filter-criterion-cardinality-mismatch",
                    controllerClass.getName(),
                    memberName,
                    memberName + " supplies " + elementCount + " values for field '" + fieldName
                            + "' with @Filterable(operation = " + operation
                            + "); range operations accept at most 2 bounds ([min, max])."
            ));
        } else if (operation == org.praxisplatform.uischema.filter.annotation.Filterable.FilterOperation.BETWEEN_EXCLUSIVE
                && elementCount != 2) {
            issues.add(new SemanticMetadataReviewIssue(
                    SemanticMetadataIssueSeverity.ERROR,
                    "quick-filter-criterion-cardinality-mismatch",
                    controllerClass.getName(),
                    memberName,
                    memberName + " supplies " + elementCount + " values for field '" + fieldName
                            + "' with @Filterable(operation = BETWEEN_EXCLUSIVE); expected exactly 2 bounds ([min, max])."
            ));
        }
    }

    private void validateProgrammaticQuickFilterEntryValue(
            Class<?> controllerClass,
            String memberName,
            String fieldName,
            Class<?> effectiveFilterDto,
            Field targetField,
            Object rawValObj,
            List<SemanticMetadataReviewIssue> issues
    ) {
        boolean isFieldCollection = org.praxisplatform.uischema.schema.ApiResourceQuickFilterResolver
                .isCollectionOrArrayField(targetField);
        java.util.List<Object> elements = new java.util.ArrayList<>();
        boolean valueIsCollection = false;
        if (rawValObj instanceof java.util.Collection<?> col) {
            valueIsCollection = true;
            elements.addAll(col);
        } else if (rawValObj.getClass().isArray()) {
            valueIsCollection = true;
            int len = java.lang.reflect.Array.getLength(rawValObj);
            for (int i = 0; i < len; i++) {
                elements.add(java.lang.reflect.Array.get(rawValObj, i));
            }
        } else {
            elements.add(rawValObj);
        }

        if (valueIsCollection && elements.isEmpty()) {
            issues.add(new SemanticMetadataReviewIssue(
                    SemanticMetadataIssueSeverity.ERROR,
                    "quick-filter-expression-malformed",
                    controllerClass.getName(),
                    memberName,
                    memberName + " declares empty collection for field '" + fieldName + "'."
            ));
            return;
        }
        if ((isFieldCollection && !valueIsCollection) || (!isFieldCollection && elements.size() > 1)) {
            issues.add(new SemanticMetadataReviewIssue(
                    SemanticMetadataIssueSeverity.ERROR,
                    "quick-filter-criterion-cardinality-mismatch",
                    controllerClass.getName(),
                    memberName,
                    memberName + " supplies incompatible cardinality for field '" + fieldName
                            + "' on " + effectiveFilterDto.getSimpleName() + "."
            ));
            return;
        }
        validateRangeOperationArity(
                controllerClass,
                memberName,
                fieldName,
                effectiveFilterDto,
                targetField,
                elements.size(),
                issues
        );

        Class<?> valueType = resolveFilterFieldScalarType(targetField);
        boolean booleanTarget = valueType == Boolean.class || valueType == boolean.class;
        for (Object item : elements) {
            if (item == null || item instanceof java.util.Collection<?> || item instanceof java.util.Map<?, ?>) {
                issues.add(new SemanticMetadataReviewIssue(
                        SemanticMetadataIssueSeverity.ERROR,
                        "quick-filter-expression-malformed",
                        controllerClass.getName(),
                        memberName,
                        memberName + " contains invalid filter value for field '" + fieldName + "'."
                ));
                continue;
            }
            if ((item instanceof Double d && !Double.isFinite(d))
                    || (item instanceof Float f && !Float.isFinite(f))) {
                issues.add(new SemanticMetadataReviewIssue(
                        SemanticMetadataIssueSeverity.ERROR,
                        "quick-filter-numeric-value-invalid",
                        controllerClass.getName(),
                        memberName,
                        memberName + " contains non-finite numeric value for field '" + fieldName + "'."
                ));
                continue;
            }
            if (booleanTarget && item instanceof Boolean b && !b) {
                checkPresenceOperationFalse(
                        controllerClass,
                        memberName,
                        fieldName,
                        effectiveFilterDto,
                        targetField,
                        "uses false",
                        issues
                );
            }
            String token = String.valueOf(item).trim();
            if (token.isEmpty()) {
                issues.add(new SemanticMetadataReviewIssue(
                        SemanticMetadataIssueSeverity.ERROR,
                        "quick-filter-expression-malformed",
                        controllerClass.getName(),
                        memberName,
                        memberName + " contains empty filter value for field '" + fieldName + "'."
                ));
                continue;
            }
            validateQuickFilterResolvedValueToken(
                    controllerClass,
                    memberName,
                    effectiveFilterDto,
                    targetField,
                    valueType,
                    token,
                    issues
            );
        }
    }

    private void validateQuickFilterResolvedValueToken(
            Class<?> controllerClass,
            String memberName,
            Class<?> effectiveFilterDto,
            Field targetField,
            Class<?> valueType,
            String token,
            List<SemanticMetadataReviewIssue> issues
    ) {
        if (isRelativePeriodPresetField(effectiveFilterDto, targetField)) {
            if (!org.praxisplatform.uischema.filter.relativeperiod.RelativePeriodContract.SUPPORTED_PRESETS.contains(token)) {
                issues.add(new SemanticMetadataReviewIssue(
                        SemanticMetadataIssueSeverity.ERROR,
                        "quick-filter-temporal-value-invalid",
                        controllerClass.getName(),
                        memberName,
                        memberName + " value '" + token + "' is not a supported relative period preset ("
                                + org.praxisplatform.uischema.filter.relativeperiod.RelativePeriodContract.SUPPORTED_PRESETS
                                + ")."
                ));
            }
            return;
        }
        if (!(token.startsWith("${") && token.endsWith("}"))) {
            UISchema targetUi = findMemberAnnotation(effectiveFilterDto, targetField, UISchema.class);
            if (targetUi == null) {
                targetUi = findGetterAnnotation(effectiveFilterDto, targetField.getName(), UISchema.class);
            }
            java.util.Set<String> allowedStaticValues = org.praxisplatform.uischema.util.OpenApiUiUtils
                    .extractStaticOptionValues(targetUi, JSON_MAPPER);
            if (!allowedStaticValues.isEmpty() && !allowedStaticValues.contains(token)) {
                issues.add(new SemanticMetadataReviewIssue(
                        SemanticMetadataIssueSeverity.ERROR,
                        "quick-filter-option-value-invalid",
                        controllerClass.getName(),
                        memberName,
                        memberName + " value '" + token + "' is not present in static @UISchema options of "
                                + effectiveFilterDto.getSimpleName() + "." + targetField.getName()
                                + " " + allowedStaticValues + "."
                ));
            }
        }
        validateQuickFilterValueToken(controllerClass, memberName, valueType, token, issues);
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
                    if (!isCanonicalLocalDatePeriodToken(token)) {
                        java.time.LocalDate.parse(token);
                    }
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

    private boolean isCanonicalLocalDatePeriodToken(String token) {
        return token != null && (
                token.matches("^\\d{4}$")
                        || token.matches("^\\d{4}-(0[1-9]|1[0-2])$")
                        || token.matches("^\\d{4}-Q[1-4]$")
                        || token.matches("^\\d{4}-S[1-2]$")
        );
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

    private static final String FACT_KEY_PATTERN = "^[a-zA-Z][a-zA-Z0-9_.-]*$";
    private static final String ICON_SNAKE_CASE_PATTERN = "^[a-z0-9]+(_[a-z0-9]+)*$";
    private static final Set<String> ALLOWED_FACT_AND_THRESHOLD_TONES =
            org.praxisplatform.uischema.annotation.QuickFilterTone.allowedWireValues();
    private static final Set<String> PROPERTY_BACKED_FACT_SOURCE_KINDS = Set.of("field", "property", "column");

    private void reviewResourceFactsHierarchy(
            Class<?> targetType,
            String ownerResourceKey,
            Set<String> knownDtoProperties,
            Set<String> seenFactKeys,
            boolean includeLegalTreatment,
            List<SemanticMetadataReviewIssue> issues
    ) {
        Class<?> currentType = targetType;
        while (currentType != null && currentType != Object.class && currentType != Record.class) {
            reviewResourceFactsOnType(
                    targetType,
                    currentType,
                    ownerResourceKey,
                    knownDtoProperties,
                    seenFactKeys,
                    includeLegalTreatment,
                    issues
            );
            currentType = currentType.getSuperclass();
        }
    }

    private void reviewResourceFactsOnType(
            Class<?> targetType,
            Class<?> currentType,
            String ownerResourceKey,
            Set<String> knownDtoProperties,
            Set<String> seenFactKeys,
            boolean includeLegalTreatment,
            List<SemanticMetadataReviewIssue> issues
    ) {
        for (ResourceFact fact : currentType.getDeclaredAnnotationsByType(ResourceFact.class)) {
            reviewResourceFact(
                    targetType,
                    "@ResourceFact(" + normalize(fact.key()) + ")",
                    fact,
                    ownerResourceKey,
                    knownDtoProperties,
                    seenFactKeys,
                    issues
            );
        }
        for (Method method : currentType.getDeclaredMethods()) {
            if (method.isSynthetic() || method.isBridge()) {
                continue;
            }
            for (ResourceFact fact : method.getDeclaredAnnotationsByType(ResourceFact.class)) {
                String factKey = normalize(fact.key());
                String memberName = factKey.isBlank() ? method.getName() : method.getName() + "@ResourceFact(" + factKey + ")";
                reviewResourceFact(
                        targetType,
                        memberName,
                        fact,
                        ownerResourceKey,
                        knownDtoProperties,
                        seenFactKeys,
                        issues
                );
            }
        }
        if (includeLegalTreatment) {
            reviewLegalTreatmentBindingsOnType(targetType, currentType, issues);
        }
    }

    private void reviewLegalTreatmentBindingsOnType(
            Class<?> targetType,
            Class<?> currentType,
            List<SemanticMetadataReviewIssue> issues
    ) {
        for (Method method : currentType.getDeclaredMethods()) {
            if (method.isSynthetic() || method.isBridge()) {
                continue;
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
                String actionIcon = normalize(action.icon());
                if (!actionIcon.isBlank() && !isValidSnakeCaseIcon(actionIcon)) {
                    issues.add(new SemanticMetadataReviewIssue(
                            SemanticMetadataIssueSeverity.ERROR,
                            "workflow-action-icon-not-snake-case",
                            targetType.getName(),
                            memberName,
                            "@WorkflowAction(icon='" + actionIcon
                                    + "') must be a lowercase snake_case Material Icons ligature."
                    ));
                }
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
            String ownerResourceKey,
            Set<String> knownDtoProperties,
            Set<String> seenFactKeys,
            List<SemanticMetadataReviewIssue> issues
    ) {
        String factKey = normalize(fact.key());
        String explicitResourceKey = normalize(fact.resourceKey());
        String effectiveResourceKey = explicitResourceKey.isBlank() ? normalize(ownerResourceKey) : explicitResourceKey;
        String label = normalize(fact.label());
        String description = normalize(fact.description());

        if (factKey.isBlank()) {
            issues.add(new SemanticMetadataReviewIssue(
                    SemanticMetadataIssueSeverity.ERROR,
                    "resource-fact-key-missing",
                    targetType.getName(),
                    memberName,
                    "@ResourceFact requires a non-blank key."
            ));
        } else {
            if (!factKey.matches(FACT_KEY_PATTERN)) {
                issues.add(new SemanticMetadataReviewIssue(
                        SemanticMetadataIssueSeverity.ERROR,
                        "resource-fact-key-invalid",
                        targetType.getName(),
                        memberName,
                        "@ResourceFact(key) '" + factKey + "' must start with a letter and contain only letters, digits, '_', '.', or '-'."
                ));
            }
            if (seenFactKeys != null && !seenFactKeys.add(factKey.toLowerCase(Locale.ROOT))) {
                issues.add(new SemanticMetadataReviewIssue(
                        SemanticMetadataIssueSeverity.ERROR,
                        "resource-fact-duplicate-key",
                        targetType.getName(),
                        memberName,
                        "@ResourceFact(key) '" + factKey + "' is duplicated on " + targetType.getSimpleName() + "."
                ));
            }
        }

        if (effectiveResourceKey.isBlank()) {
            issues.add(new SemanticMetadataReviewIssue(
                    SemanticMetadataIssueSeverity.ERROR,
                    "resource-fact-resource-key-missing",
                    targetType.getName(),
                    memberName,
                    "@ResourceFact requires resourceKey when not inherited from an enclosing @ApiResource."
            ));
        } else if (!explicitResourceKey.isBlank() && !normalize(ownerResourceKey).isBlank()
                && !explicitResourceKey.equals(normalize(ownerResourceKey))) {
            issues.add(new SemanticMetadataReviewIssue(
                    SemanticMetadataIssueSeverity.ERROR,
                    "resource-fact-resource-key-mismatch",
                    targetType.getName(),
                    memberName,
                    "@ResourceFact(resourceKey='" + explicitResourceKey + "') conflicts with @ApiResource(resourceKey='"
                            + normalize(ownerResourceKey) + "')."
            ));
        }

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

        reviewFactVocabulary(
                targetType, memberName, "resource-fact-data-type-invalid", "dataType",
                fact.dataType(), org.praxisplatform.uischema.fact.ResourceFactDataType.isValidWireValue(fact.dataType()),
                org.praxisplatform.uischema.fact.ResourceFactDataType.allowedWireValues(), issues
        );
        String sourceKind = normalize(fact.sourceKind()).toLowerCase(Locale.ROOT);
        reviewFactVocabulary(
                targetType, memberName, "resource-fact-source-kind-invalid", "sourceKind",
                fact.sourceKind(), org.praxisplatform.uischema.fact.ResourceFactSourceKind.isValidWireValue(fact.sourceKind()),
                org.praxisplatform.uischema.fact.ResourceFactSourceKind.allowedWireValues(), issues
        );
        reviewFactVocabulary(
                targetType, memberName, "resource-fact-authority-invalid", "authority",
                fact.authority(), org.praxisplatform.uischema.fact.ResourceFactAuthority.isValidWireValue(fact.authority()),
                org.praxisplatform.uischema.fact.ResourceFactAuthority.allowedWireValues(), issues
        );
        reviewFactVocabulary(
                targetType, memberName, "resource-fact-evaluation-phase-invalid", "evaluationPhase",
                fact.evaluationPhase(), org.praxisplatform.uischema.fact.ResourceFactEvaluationPhase.isValidWireValue(fact.evaluationPhase()),
                org.praxisplatform.uischema.fact.ResourceFactEvaluationPhase.allowedWireValues(), issues
        );
        reviewFactVocabulary(
                targetType, memberName, "resource-fact-missing-value-policy-invalid", "missingValuePolicy",
                fact.missingValuePolicy(), org.praxisplatform.uischema.fact.ResourceFactMissingValuePolicy.isValidWireValue(fact.missingValuePolicy()),
                org.praxisplatform.uischema.fact.ResourceFactMissingValuePolicy.allowedWireValues(), issues
        );
        reviewFactVocabulary(
                targetType, memberName, "resource-fact-scope-invalid", "scope",
                fact.scope(), org.praxisplatform.uischema.fact.ResourceFactScope.isValidWireValue(fact.scope()),
                org.praxisplatform.uischema.fact.ResourceFactScope.allowedWireValues(), issues
        );

        String factTone = normalize(fact.tone());
        if (!factTone.isBlank() && !isAllowedTone(factTone)) {
            issues.add(new SemanticMetadataReviewIssue(
                    SemanticMetadataIssueSeverity.ERROR,
                    "resource-fact-tone-invalid",
                    targetType.getName(),
                    memberName,
                    "@ResourceFact(tone='" + factTone + "') must be one of " + ALLOWED_FACT_AND_THRESHOLD_TONES + "."
            ));
        }

        String factIcon = normalize(fact.icon());
        if (!factIcon.isBlank() && !isValidSnakeCaseIcon(factIcon)) {
            issues.add(new SemanticMetadataReviewIssue(
                    SemanticMetadataIssueSeverity.ERROR,
                    "resource-fact-icon-not-snake-case",
                    targetType.getName(),
                    memberName,
                    "@ResourceFact(icon='" + factIcon + "') must be a lowercase snake_case Material Icons ligature."
            ));
        }

        if (!factKey.isBlank() && PROPERTY_BACKED_FACT_SOURCE_KINDS.contains(sourceKind)
                && knownDtoProperties != null && !knownDtoProperties.isEmpty()
                && !knownDtoProperties.contains(factKey)) {
            issues.add(new SemanticMetadataReviewIssue(
                    SemanticMetadataIssueSeverity.ERROR,
                    "resource-fact-source-field-unknown",
                    targetType.getName(),
                    memberName,
                    "@ResourceFact(key='" + factKey + "', sourceKind='" + sourceKind
                            + "') does not match any property on the backing DTO."
            ));
        }

        for (org.praxisplatform.uischema.annotation.Threshold threshold : fact.thresholds()) {
            boolean minUnset = Double.isNaN(threshold.min());
            boolean maxUnset = Double.isNaN(threshold.max());
            boolean valueUnset = Double.isNaN(threshold.value());
            boolean equalsUnset = normalize(threshold.equalsValue()).isBlank();
            if (minUnset && maxUnset && valueUnset && equalsUnset) {
                issues.add(new SemanticMetadataReviewIssue(
                        SemanticMetadataIssueSeverity.ERROR,
                        "resource-fact-threshold-bounds-missing",
                        targetType.getName(),
                        memberName,
                        "@ResourceFact threshold must declare at least min, max, value, or equalsValue."
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
            String thresholdTone = normalize(threshold.tone());
            if (thresholdTone.isBlank()) {
                issues.add(new SemanticMetadataReviewIssue(
                        SemanticMetadataIssueSeverity.ERROR,
                        "resource-fact-threshold-tone-missing",
                        targetType.getName(),
                        memberName,
                        "@ResourceFact threshold requires a non-blank semantic tone."
                ));
            } else if (!isAllowedTone(thresholdTone)) {
                issues.add(new SemanticMetadataReviewIssue(
                        SemanticMetadataIssueSeverity.ERROR,
                        "resource-fact-threshold-tone-invalid",
                        targetType.getName(),
                        memberName,
                        "@ResourceFact threshold tone '" + thresholdTone + "' must be one of " + ALLOWED_FACT_AND_THRESHOLD_TONES + "."
                ));
            }
        }
    }

    private void reviewFactVocabulary(
            Class<?> targetType,
            String memberName,
            String issueCode,
            String attributeName,
            String rawValue,
            boolean valid,
            Set<String> allowedValues,
            List<SemanticMetadataReviewIssue> issues
    ) {
        if (valid) {
            return;
        }
        issues.add(new SemanticMetadataReviewIssue(
                SemanticMetadataIssueSeverity.ERROR,
                issueCode,
                targetType.getName(),
                memberName,
                "@ResourceFact(" + attributeName + "='" + normalize(rawValue) + "') must be one of " + allowedValues + "."
        ));
    }

    private void reviewReactiveDeterminations(
            Class<?> controllerClass,
            Class<?> responseDto,
            Class<?> createDto,
            Class<?> updateDto,
            List<SemanticMetadataReviewIssue> issues
    ) {
        Method[] methods = org.springframework.util.ReflectionUtils.getUniqueDeclaredMethods(
                controllerClass,
                method -> !method.isBridge() && !method.isSynthetic()
        );
        Set<String> declaredOperationIds = new java.util.LinkedHashSet<>();
        List<Method> determinationMethods = new ArrayList<>();
        for (Method method : methods) {
            io.swagger.v3.oas.annotations.Operation operation =
                    org.springframework.core.annotation.AnnotatedElementUtils.findMergedAnnotation(
                            method,
                            io.swagger.v3.oas.annotations.Operation.class
                    );
            if (operation != null) {
                String opId = normalize(operation.operationId());
                if (!opId.isBlank()) {
                    declaredOperationIds.add(opId);
                }
            }
            if (org.springframework.core.annotation.AnnotatedElementUtils.findMergedAnnotation(
                    method,
                    org.praxisplatform.uischema.annotation.ReactiveDetermination.class
            ) != null) {
                determinationMethods.add(method);
            }
        }
        if (determinationMethods.isEmpty()) {
            return;
        }

        Set<String> formProperties = new java.util.LinkedHashSet<>();
        formProperties.addAll(collectDtoPropertyNames(createDto));
        formProperties.addAll(collectDtoPropertyNames(updateDto));
        formProperties.addAll(collectDtoPropertyNames(responseDto));
        Set<String> seenDeterminationIds = new java.util.LinkedHashSet<>();

        for (Method method : determinationMethods) {
            org.praxisplatform.uischema.annotation.ReactiveDetermination determination =
                    org.springframework.core.annotation.AnnotatedElementUtils.findMergedAnnotation(
                            method,
                            org.praxisplatform.uischema.annotation.ReactiveDetermination.class
                    );
            if (determination == null) {
                continue;
            }
            String detId = normalize(determination.id());
            String memberName = detId.isBlank()
                    ? method.getName() + "@ReactiveDetermination"
                    : method.getName() + "@ReactiveDetermination(" + detId + ")";

            io.swagger.v3.oas.annotations.Operation methodOp =
                    org.springframework.core.annotation.AnnotatedElementUtils.findMergedAnnotation(
                            method,
                            io.swagger.v3.oas.annotations.Operation.class
                    );
            if (methodOp == null || normalize(methodOp.operationId()).isBlank()) {
                issues.add(new SemanticMetadataReviewIssue(
                        SemanticMetadataIssueSeverity.ERROR,
                        "reactive-determination-operation-id-missing",
                        controllerClass.getName(),
                        memberName,
                        "@ReactiveDetermination method must declare @Operation(operationId = ...)."
                ));
            }

            if (detId.isBlank() || containsWhitespace(detId)) {
                issues.add(new SemanticMetadataReviewIssue(
                        SemanticMetadataIssueSeverity.ERROR,
                        "reactive-determination-id-invalid",
                        controllerClass.getName(),
                        memberName,
                        "@ReactiveDetermination(id) must be non-blank and contain no whitespace."
                ));
            } else if (!seenDeterminationIds.add(detId)) {
                issues.add(new SemanticMetadataReviewIssue(
                        SemanticMetadataIssueSeverity.ERROR,
                        "reactive-determination-duplicate-id",
                        controllerClass.getName(),
                        memberName,
                        "@ReactiveDetermination(id='" + detId + "') is duplicated on " + controllerClass.getSimpleName() + "."
                ));
            }

            validateDeterminationScopes(controllerClass, memberName, determination, declaredOperationIds, issues);

            Class<?> requestDto = resolveRequestBodyDtoType(method, controllerClass);
            Set<String> requestDtoProperties = collectDtoPropertyNames(requestDto);
            Class<?> methodResponseDto = resolveUnwrappedMethodReturnDtoType(method, controllerClass);
            Set<String> responseDtoProperties = collectDtoPropertyNames(methodResponseDto);

            boolean inferredInputs = determination.inputs().length == 0;
            if (inferredInputs && requestDtoProperties.isEmpty()) {
                issues.add(new SemanticMetadataReviewIssue(
                        SemanticMetadataIssueSeverity.ERROR,
                        "reactive-determination-inputs-empty",
                        controllerClass.getName(),
                        memberName,
                        "@ReactiveDetermination must declare at least one @DeterminationInput or accept a DTO @RequestBody."
                ));
            }
            if (determination.outputs().length == 0 && responseDtoProperties.isEmpty()) {
                issues.add(new SemanticMetadataReviewIssue(
                        SemanticMetadataIssueSeverity.ERROR,
                        "reactive-determination-outputs-empty",
                        controllerClass.getName(),
                        memberName,
                        "@ReactiveDetermination must declare at least one @DeterminationOutput or return a DTO ResponseEntity."
                ));
            }

            boolean hasAutoTriggerInput = inferredInputs && !requestDtoProperties.isEmpty();
            for (org.praxisplatform.uischema.annotation.DeterminationInput input : determination.inputs()) {
                if (input.triggerSource()) {
                    hasAutoTriggerInput = true;
                }
                validateDeterminationInput(
                        controllerClass, memberName, input, formProperties, requestDtoProperties, issues
                );
            }
            for (org.praxisplatform.uischema.annotation.DeterminationOutput output : determination.outputs()) {
                validateDeterminationOutput(
                        controllerClass, memberName, output, formProperties, responseDtoProperties, issues
                );
            }

            if (determination.sourceFields().length == 0
                    && determination.triggerMode().requiresSourcePaths()
                    && !hasAutoTriggerInput) {
                issues.add(new SemanticMetadataReviewIssue(
                        SemanticMetadataIssueSeverity.ERROR,
                        "reactive-determination-trigger-sources-empty",
                        controllerClass.getName(),
                        memberName,
                        "@ReactiveDetermination with triggerMode=" + determination.triggerMode()
                                + " requires explicit sourceFields or at least one triggerSource=true input."
                ));
            }
            for (String sourceField : determination.sourceFields()) {
                String pointer = normalizeDeterminationPointer(sourceField);
                if (pointer == null) {
                    issues.add(new SemanticMetadataReviewIssue(
                            SemanticMetadataIssueSeverity.ERROR,
                            "reactive-determination-source-field-unknown",
                            controllerClass.getName(),
                            memberName,
                            "@ReactiveDetermination sourceField '" + normalize(sourceField) + "' is not a valid JSON Pointer or property name."
                    ));
                    continue;
                }
                String rootProperty = extractJsonPointerRootProperty(pointer);
                if (!formProperties.isEmpty() && !formProperties.contains(rootProperty)) {
                    issues.add(new SemanticMetadataReviewIssue(
                            SemanticMetadataIssueSeverity.ERROR,
                            "reactive-determination-source-field-unknown",
                            controllerClass.getName(),
                            memberName,
                            "@ReactiveDetermination sourceField '" + sourceField + "' (root property '" + rootProperty
                                    + "') does not match any property on CreateDTO/UpdateDTO/ResponseDTO."
                    ));
                }
            }
        }
    }

    private void validateDeterminationScopes(
            Class<?> controllerClass,
            String memberName,
            org.praxisplatform.uischema.annotation.ReactiveDetermination determination,
            Set<String> declaredOperationIds,
            List<SemanticMetadataReviewIssue> issues
    ) {
        String createOpId = normalize(determination.createOperationId());
        String updateOpId = normalize(determination.updateOperationId());
        boolean hasCreate = !createOpId.isBlank();
        boolean hasUpdate = !updateOpId.isBlank();
        boolean hasScopes = determination.scopes().length > 0;

        if (hasScopes && (hasCreate || hasUpdate)) {
            issues.add(new SemanticMetadataReviewIssue(
                    SemanticMetadataIssueSeverity.ERROR,
                    "reactive-determination-scope-conflict",
                    controllerClass.getName(),
                    memberName,
                    "@ReactiveDetermination cannot combine scopes() with createOperationId/updateOperationId."
            ));
        }

        boolean inferredCreate = false;
        boolean inferredUpdate = false;
        if (!hasScopes) {
            if (!hasCreate) {
                String resolvedCreate = resolveControllerDefaultOperationId(controllerClass, true);
                if (!resolvedCreate.isBlank()) {
                    createOpId = resolvedCreate;
                    hasCreate = true;
                    inferredCreate = true;
                }
            }
            if (!hasUpdate) {
                String resolvedUpdate = resolveControllerDefaultOperationId(controllerClass, false);
                if (!resolvedUpdate.isBlank()) {
                    updateOpId = resolvedUpdate;
                    hasUpdate = true;
                    inferredUpdate = true;
                }
            }
        }

        if (!hasCreate && !hasUpdate && !hasScopes) {
            issues.add(new SemanticMetadataReviewIssue(
                    SemanticMetadataIssueSeverity.ERROR,
                    "reactive-determination-scope-missing",
                    controllerClass.getName(),
                    memberName,
                    "@ReactiveDetermination must declare createOperationId/updateOperationId or @DeterminationScope entries."
            ));
            return;
        }
        if (hasCreate && hasUpdate && createOpId.equals(updateOpId)) {
            issues.add(new SemanticMetadataReviewIssue(
                    SemanticMetadataIssueSeverity.ERROR,
                    "reactive-determination-scope-conflict",
                    controllerClass.getName(),
                    memberName,
                    "@ReactiveDetermination createOperationId and updateOperationId must be distinct."
            ));
        }
        if (hasCreate && !inferredCreate && !declaredOperationIds.contains(createOpId)) {
            issues.add(new SemanticMetadataReviewIssue(
                    SemanticMetadataIssueSeverity.ERROR,
                    "reactive-determination-target-operation-unknown",
                    controllerClass.getName(),
                    memberName,
                    "@ReactiveDetermination createOperationId='" + createOpId + "' was not found among controller @Operation(operationId) declarations."
            ));
        }
        if (hasUpdate && !inferredUpdate && !declaredOperationIds.contains(updateOpId)) {
            issues.add(new SemanticMetadataReviewIssue(
                    SemanticMetadataIssueSeverity.ERROR,
                    "reactive-determination-target-operation-unknown",
                    controllerClass.getName(),
                    memberName,
                    "@ReactiveDetermination updateOperationId='" + updateOpId + "' was not found among controller @Operation(operationId) declarations."
            ));
        }
        for (org.praxisplatform.uischema.annotation.DeterminationScope scope : determination.scopes()) {
            String schemaOpId = normalize(scope.schemaOperationId());
            if (schemaOpId.isBlank() || !declaredOperationIds.contains(schemaOpId)) {
                issues.add(new SemanticMetadataReviewIssue(
                        SemanticMetadataIssueSeverity.ERROR,
                        "reactive-determination-target-operation-unknown",
                        controllerClass.getName(),
                        memberName,
                        "@DeterminationScope schemaOperationId='" + schemaOpId + "' was not found among controller @Operation(operationId) declarations."
                ));
            }
        }
    }

    private void validateDeterminationInput(
            Class<?> controllerClass,
            String memberName,
            org.praxisplatform.uischema.annotation.DeterminationInput input,
            Set<String> formProperties,
            Set<String> requestDtoProperties,
            List<SemanticMetadataReviewIssue> issues
    ) {
        String val = normalize(input.value());
        String field = normalize(input.field());
        String request = normalize(input.request());
        if ((!val.isBlank() && (!field.isBlank() || !request.isBlank()))
                || (val.isBlank() && (field.isBlank() || request.isBlank()))) {
            issues.add(new SemanticMetadataReviewIssue(
                    SemanticMetadataIssueSeverity.ERROR,
                    "reactive-determination-input-mapping-invalid",
                    controllerClass.getName(),
                    memberName,
                    "@DeterminationInput must either specify value() alone or both field() and request()."
            ));
            return;
        }
        String rawFieldPath = val.isBlank() ? field : val;
        String rawRequestPath = val.isBlank() ? request : val;
        validateDeterminationPointerAndProperty(
                controllerClass, memberName, rawFieldPath, formProperties,
                "reactive-determination-input-field-unknown", "input field path", issues
        );
        validateDeterminationPointerAndProperty(
                controllerClass, memberName, rawRequestPath, requestDtoProperties,
                "reactive-determination-input-request-field-unknown", "input request path", issues
        );
    }

    private void validateDeterminationOutput(
            Class<?> controllerClass,
            String memberName,
            org.praxisplatform.uischema.annotation.DeterminationOutput output,
            Set<String> formProperties,
            Set<String> responseDtoProperties,
            List<SemanticMetadataReviewIssue> issues
    ) {
        String val = normalize(output.value());
        String response = normalize(output.response());
        String field = normalize(output.field());
        if ((!val.isBlank() && (!response.isBlank() || !field.isBlank()))
                || (val.isBlank() && (response.isBlank() || field.isBlank()))) {
            issues.add(new SemanticMetadataReviewIssue(
                    SemanticMetadataIssueSeverity.ERROR,
                    "reactive-determination-output-mapping-invalid",
                    controllerClass.getName(),
                    memberName,
                    "@DeterminationOutput must either specify value() alone or both response() and field()."
            ));
            return;
        }
        String rawResponsePath = val.isBlank() ? response : val;
        String rawFieldPath = val.isBlank() ? field : val;
        validateDeterminationPointerAndProperty(
                controllerClass, memberName, rawFieldPath, formProperties,
                "reactive-determination-output-field-unknown", "output field path", issues
        );
        validateDeterminationPointerAndProperty(
                controllerClass, memberName, rawResponsePath, responseDtoProperties,
                "reactive-determination-output-response-field-unknown", "output response path", issues
        );
    }

    private void validateDeterminationPointerAndProperty(
            Class<?> controllerClass,
            String memberName,
            String rawPath,
            Set<String> targetProperties,
            String unknownFieldCode,
            String pathRole,
            List<SemanticMetadataReviewIssue> issues
    ) {
        String pointer = normalizeDeterminationPointer(rawPath);
        if (pointer == null) {
            issues.add(new SemanticMetadataReviewIssue(
                    SemanticMetadataIssueSeverity.ERROR,
                    "reactive-determination-json-pointer-invalid",
                    controllerClass.getName(),
                    memberName,
                    "Invalid JSON Pointer '" + rawPath + "' for " + pathRole + "."
            ));
            return;
        }
        String rootProperty = extractJsonPointerRootProperty(pointer);
        if (targetProperties != null && !targetProperties.isEmpty() && !targetProperties.contains(rootProperty)) {
            issues.add(new SemanticMetadataReviewIssue(
                    SemanticMetadataIssueSeverity.ERROR,
                    unknownFieldCode,
                    controllerClass.getName(),
                    memberName,
                    "@ReactiveDetermination " + pathRole + " '" + rawPath + "' (root property '" + rootProperty
                            + "') does not exist on target DTO."
            ));
        }
    }

    private void reviewBulkEditableProperty(
            Class<?> targetType,
            String memberName,
            org.praxisplatform.uischema.annotation.BulkEditable bulkEditable,
            Schema schema,
            List<SemanticMetadataReviewIssue> issues
    ) {
        if (bulkEditable == null) {
            return;
        }
        org.praxisplatform.uischema.bulk.BulkMode[] modes = bulkEditable.modes();
        boolean hasDomainCommand = false;
        if (modes != null) {
            for (org.praxisplatform.uischema.bulk.BulkMode mode : modes) {
                if (mode == org.praxisplatform.uischema.bulk.BulkMode.DOMAIN_COMMAND) {
                    hasDomainCommand = true;
                    break;
                }
            }
        }
        if (modes == null || modes.length == 0 || hasDomainCommand) {
            issues.add(new SemanticMetadataReviewIssue(
                    SemanticMetadataIssueSeverity.ERROR,
                    "bulk-editable-invalid-mode",
                    targetType.getName(),
                    memberName,
                    "@BulkEditable supports only UNIFORM_UPDATE and/or PER_ITEM_UPDATE and must declare at least one mode."
            ));
        }
        if (bulkEditable.allowClear() && (schema == null || !schema.nullable())) {
            issues.add(new SemanticMetadataReviewIssue(
                    SemanticMetadataIssueSeverity.ERROR,
                    "bulk-editable-clear-requires-nullable-schema",
                    targetType.getName(),
                    memberName,
                    "@BulkEditable(allowClear = true) requires explicit @Schema(nullable = true) on the property."
            ));
        }
    }

    private void reviewBulkResourceOperations(
            Class<?> controllerClass,
            Class<?> responseDto,
            Class<?> createDto,
            Class<?> updateDto,
            List<SemanticMetadataReviewIssue> issues
    ) {
        org.praxisplatform.uischema.bulk.BulkResourceOperations bulkOps =
                findTypeAnnotation(controllerClass, org.praxisplatform.uischema.bulk.BulkResourceOperations.class);
        if (bulkOps == null) {
            return;
        }
        checkRequiredBulkOperationId(controllerClass, "proposalOperationId",
                org.praxisplatform.uischema.bulk.BulkControllerOperationIds.lifecycleOperationId(
                        controllerClass, bulkOps, org.praxisplatform.uischema.bulk.BulkResourceOperation.Role.PROPOSAL), issues);
        checkRequiredBulkOperationId(controllerClass, "proposalResultsOperationId",
                org.praxisplatform.uischema.bulk.BulkControllerOperationIds.lifecycleOperationId(
                        controllerClass, bulkOps, org.praxisplatform.uischema.bulk.BulkResourceOperation.Role.PROPOSAL_RESULTS), issues);
        checkRequiredBulkOperationId(controllerClass, "executionOperationId",
                org.praxisplatform.uischema.bulk.BulkControllerOperationIds.lifecycleOperationId(
                        controllerClass, bulkOps, org.praxisplatform.uischema.bulk.BulkResourceOperation.Role.EXECUTION), issues);
        checkRequiredBulkOperationId(controllerClass, "executionResultsOperationId",
                org.praxisplatform.uischema.bulk.BulkControllerOperationIds.lifecycleOperationId(
                        controllerClass, bulkOps, org.praxisplatform.uischema.bulk.BulkResourceOperation.Role.EXECUTION_RESULTS), issues);
        checkRequiredBulkOperationId(controllerClass, "cancelOperationId",
                org.praxisplatform.uischema.bulk.BulkControllerOperationIds.lifecycleOperationId(
                        controllerClass, bulkOps, org.praxisplatform.uischema.bulk.BulkResourceOperation.Role.CANCEL), issues);

        Set<String> knownProperties = new java.util.LinkedHashSet<>();
        knownProperties.addAll(collectDtoPropertyNames(responseDto));
        knownProperties.addAll(collectDtoPropertyNames(createDto));
        knownProperties.addAll(collectDtoPropertyNames(updateDto));
        Set<String> bulkEditableUpdateFields = collectBulkEditablePropertyNames(updateDto);
        Set<String> seenProtectedFields = new java.util.LinkedHashSet<>();

        for (String protectedField : bulkOps.protectedUpdateFields()) {
            String normalizedField = normalize(protectedField);
            if (normalizedField.isBlank()) {
                issues.add(new SemanticMetadataReviewIssue(
                        SemanticMetadataIssueSeverity.ERROR,
                        "bulk-protected-field-blank",
                        controllerClass.getName(),
                        "@BulkResourceOperations.protectedUpdateFields",
                        "@BulkResourceOperations(protectedUpdateFields) must not contain blank field names."
                ));
                continue;
            }
            if (!seenProtectedFields.add(normalizedField)) {
                issues.add(new SemanticMetadataReviewIssue(
                        SemanticMetadataIssueSeverity.ERROR,
                        "bulk-protected-field-duplicate",
                        controllerClass.getName(),
                        "@BulkResourceOperations.protectedUpdateFields",
                        "@BulkResourceOperations(protectedUpdateFields) repeats '" + normalizedField + "'."
                ));
            }
            if (!knownProperties.isEmpty() && !knownProperties.contains(normalizedField)) {
                issues.add(new SemanticMetadataReviewIssue(
                        SemanticMetadataIssueSeverity.ERROR,
                        "bulk-protected-field-unknown",
                        controllerClass.getName(),
                        "@BulkResourceOperations.protectedUpdateFields",
                        "@BulkResourceOperations(protectedUpdateFields) references unknown property '" + normalizedField + "'."
                ));
            }
            if (bulkEditableUpdateFields.contains(normalizedField)) {
                issues.add(new SemanticMetadataReviewIssue(
                        SemanticMetadataIssueSeverity.ERROR,
                        "bulk-protected-field-conflicts-with-bulk-editable",
                        controllerClass.getName(),
                        "@BulkResourceOperations.protectedUpdateFields",
                        "Property '" + normalizedField + "' cannot be both @BulkEditable on UpdateDTO and listed in protectedUpdateFields."
                ));
            }
        }
    }

    private void checkRequiredBulkOperationId(
            Class<?> controllerClass,
            String attributeName,
            String operationId,
            List<SemanticMetadataReviewIssue> issues
    ) {
        if (normalize(operationId).isBlank()) {
            issues.add(new SemanticMetadataReviewIssue(
                    SemanticMetadataIssueSeverity.ERROR,
                    "bulk-resource-operations-operation-id-missing",
                    controllerClass.getName(),
                    "@BulkResourceOperations." + attributeName,
                    "@BulkResourceOperations requires non-blank " + attributeName + "."
            ));
        }
    }

    private String resolveOwnerApiResourceKey(Class<?> targetType) {
        org.praxisplatform.uischema.annotation.ApiResource apiResource =
                findTypeAnnotation(targetType, org.praxisplatform.uischema.annotation.ApiResource.class);
        return apiResource == null ? "" : normalize(apiResource.resourceKey());
    }

    private Class<?> resolveControllerCreateDtoType(Class<?> controllerClass) {
        Class<?> fromCreateUpdate = resolveControllerGenericTypeArg(
                controllerClass,
                org.praxisplatform.uischema.controller.base.AbstractCreateUpdateResourceController.class,
                3
        );
        if (fromCreateUpdate != null) {
            return fromCreateUpdate;
        }
        return resolveControllerGenericTypeArg(
                controllerClass,
                org.praxisplatform.uischema.controller.base.AbstractCreateResourceController.class,
                3
        );
    }

    private Class<?> resolveControllerUpdateDtoType(Class<?> controllerClass) {
        return resolveControllerGenericTypeArg(
                controllerClass,
                org.praxisplatform.uischema.controller.base.AbstractCreateUpdateResourceController.class,
                4
        );
    }

    private Class<?> resolveControllerGenericTypeArg(Class<?> controllerClass, Class<?> baseClass, int argIndex) {
        if (controllerClass == null || !baseClass.isAssignableFrom(controllerClass)) {
            return null;
        }
        Class<?>[] args = org.springframework.core.GenericTypeResolver.resolveTypeArguments(controllerClass, baseClass);
        if (args != null && args.length > argIndex && args[argIndex] != null && args[argIndex] != Object.class) {
            return args[argIndex];
        }
        return null;
    }

    private Class<?> resolveRequestBodyDtoType(Method method, Class<?> controllerClass) {
        java.lang.reflect.Parameter[] params = method.getParameters();
        for (int i = 0; i < params.length; i++) {
            if (params[i].isAnnotationPresent(org.springframework.web.bind.annotation.RequestBody.class)
                    || org.springframework.core.annotation.AnnotatedElementUtils.hasAnnotation(
                    params[i], org.springframework.web.bind.annotation.RequestBody.class)) {
                return unwrapContainerType(org.springframework.core.ResolvableType.forMethodParameter(method, i, controllerClass));
            }
        }
        return null;
    }

    private Class<?> resolveUnwrappedMethodReturnDtoType(Method method, Class<?> controllerClass) {
        return unwrapContainerType(org.springframework.core.ResolvableType.forMethodReturnType(method, controllerClass));
    }

    private Class<?> unwrapContainerType(org.springframework.core.ResolvableType type) {
        org.springframework.core.ResolvableType current = type;
        for (int depth = 0; depth < 4 && current != null; depth++) {
            Class<?> raw = current.resolve();
            if (raw == null) {
                return null;
            }
            if (org.springframework.http.ResponseEntity.class.isAssignableFrom(raw)
                    || org.springframework.http.HttpEntity.class.isAssignableFrom(raw)
                    || org.praxisplatform.uischema.rest.response.RestApiResponse.class.isAssignableFrom(raw)) {
                current = current.getGeneric(0);
                continue;
            }
            return raw;
        }
        return null;
    }

    private Set<String> collectDtoPropertyNames(Class<?> dtoType) {
        if (!isComplexBeanOrRecordDto(dtoType)) {
            return Set.of();
        }
        Set<String> names = new java.util.LinkedHashSet<>();
        Class<?> currentType = dtoType;
        while (currentType != null && currentType != Object.class && currentType != Record.class) {
            if (currentType.isRecord()) {
                for (RecordComponent component : currentType.getRecordComponents()) {
                    var ignored = component.getDeclaredAnnotation(com.fasterxml.jackson.annotation.JsonIgnore.class);
                    if (ignored != null && ignored.value()) {
                        continue;
                    }
                    names.add(component.getName());
                    var jsonProp = component.getDeclaredAnnotation(com.fasterxml.jackson.annotation.JsonProperty.class);
                    if (jsonProp != null && !normalize(jsonProp.value()).isBlank()) {
                        names.add(normalize(jsonProp.value()));
                    }
                }
            }
            for (Field field : currentType.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                    continue;
                }
                var ignored = findMemberAnnotation(currentType, field, com.fasterxml.jackson.annotation.JsonIgnore.class);
                if (ignored != null && ignored.value()) {
                    continue;
                }
                names.add(field.getName());
                var jsonProp = findMemberAnnotation(currentType, field, com.fasterxml.jackson.annotation.JsonProperty.class);
                if (jsonProp != null && !normalize(jsonProp.value()).isBlank()) {
                    names.add(normalize(jsonProp.value()));
                }
            }
            for (Method method : currentType.getDeclaredMethods()) {
                if (Modifier.isStatic(method.getModifiers()) || method.isSynthetic()
                        || method.isBridge() || method.getParameterCount() != 0) {
                    continue;
                }
                String propName = extractGetterPropertyName(method);
                if (propName == null) {
                    continue;
                }
                var ignored = method.getDeclaredAnnotation(com.fasterxml.jackson.annotation.JsonIgnore.class);
                if (ignored != null && ignored.value()) {
                    continue;
                }
                names.add(propName);
                var jsonProp = method.getDeclaredAnnotation(com.fasterxml.jackson.annotation.JsonProperty.class);
                if (jsonProp != null && !normalize(jsonProp.value()).isBlank()) {
                    names.add(normalize(jsonProp.value()));
                }
            }
            currentType = currentType.getSuperclass();
        }
        return names;
    }

    private Set<String> collectBulkEditablePropertyNames(Class<?> dtoType) {
        if (!isComplexBeanOrRecordDto(dtoType)) {
            return Set.of();
        }
        Set<String> names = new java.util.LinkedHashSet<>();
        Class<?> currentType = dtoType;
        while (currentType != null && currentType != Object.class && currentType != Record.class) {
            if (currentType.isRecord()) {
                for (RecordComponent component : currentType.getRecordComponents()) {
                    if (component.getDeclaredAnnotation(org.praxisplatform.uischema.annotation.BulkEditable.class) != null) {
                        names.add(component.getName());
                    }
                }
            }
            for (Field field : currentType.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) && !field.isSynthetic()
                        && findMemberAnnotation(
                        currentType, field, org.praxisplatform.uischema.annotation.BulkEditable.class) != null) {
                    names.add(field.getName());
                }
            }
            for (Method method : currentType.getDeclaredMethods()) {
                if (Modifier.isStatic(method.getModifiers()) || method.isSynthetic()
                        || method.isBridge() || method.getParameterCount() != 0) {
                    continue;
                }
                String propName = extractGetterPropertyName(method);
                if (propName != null
                        && method.getDeclaredAnnotation(org.praxisplatform.uischema.annotation.BulkEditable.class) != null) {
                    names.add(propName);
                }
            }
            currentType = currentType.getSuperclass();
        }
        return names;
    }

    private static boolean isComplexBeanOrRecordDto(Class<?> type) {
        if (type == null || type == Object.class || type == void.class || type == Void.class
                || type.isPrimitive() || type.isArray() || type.isEnum() || type.isInterface()) {
            return false;
        }
        if (java.util.Map.class.isAssignableFrom(type)
                || Iterable.class.isAssignableFrom(type)
                || CharSequence.class.isAssignableFrom(type)
                || Number.class.isAssignableFrom(type)
                || Boolean.class == type
                || Character.class == type
                || com.fasterxml.jackson.databind.JsonNode.class.isAssignableFrom(type)) {
            return false;
        }
        String name = type.getName();
        return !name.startsWith("java.")
                && !name.startsWith("jakarta.")
                && !name.startsWith("org.springframework.");
    }

    private static String normalizeDeterminationPointer(String rawPath) {
        if (rawPath == null) {
            return null;
        }
        String trimmed = rawPath.trim();
        if (trimmed.isEmpty() || containsWhitespace(trimmed)) {
            return null;
        }
        String pointer = trimmed.startsWith("/") ? trimmed : "/" + trimmed;
        if (pointer.length() < 2 || pointer.endsWith("/") || pointer.contains("//")) {
            return null;
        }
        return pointer;
    }

    private static String extractJsonPointerRootProperty(String normalizedPointer) {
        String tail = normalizedPointer.substring(1);
        int slashIdx = tail.indexOf('/');
        String root = slashIdx >= 0 ? tail.substring(0, slashIdx) : tail;
        return root.replace("~1", "/").replace("~0", "~");
    }

    private static boolean isValidSnakeCaseIcon(String icon) {
        return icon != null && icon.matches(ICON_SNAKE_CASE_PATTERN);
    }

    private static boolean isAllowedTone(String tone) {
        return org.praxisplatform.uischema.annotation.QuickFilterTone.isValidWireValue(tone);
    }

    private static boolean containsWhitespace(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isWhitespace(value.charAt(i))) {
                return true;
            }
        }
        return false;
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
        reviewUiSchemaOptions(targetType, field.getName(), uiSchema, issues);
        reviewUiSchemaStructuralContracts(targetType, field.getName(), uiSchema, issues);
        reviewFilterableProperty(
                targetType,
                field.getName(),
                findMemberAnnotation(declaringType, field, org.praxisplatform.uischema.filter.annotation.Filterable.class),
                issues
        );
        reviewBulkEditableProperty(
                targetType,
                field.getName(),
                findMemberAnnotation(declaringType, field, org.praxisplatform.uischema.annotation.BulkEditable.class),
                schema,
                issues
        );
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
        reviewUiSchemaOptions(targetType, propertyName, uiSchema, issues);
        reviewUiSchemaStructuralContracts(targetType, propertyName, uiSchema, issues);
        reviewFilterableProperty(
                targetType,
                propertyName,
                findGetterAnnotation(targetType, propertyName, org.praxisplatform.uischema.filter.annotation.Filterable.class),
                issues
        );
        reviewBulkEditableProperty(
                targetType,
                propertyName,
                findGetterAnnotation(targetType, propertyName, org.praxisplatform.uischema.annotation.BulkEditable.class),
                schema,
                issues
        );
    }

    private void reviewUiSchemaOptions(
            Class<?> targetType,
            String propertyName,
            UISchema uiSchema,
            List<SemanticMetadataReviewIssue> issues
    ) {
        if (uiSchema == null) {
            return;
        }
        org.praxisplatform.uischema.annotation.UiOption[] optionItems = uiSchema.optionItems();
        Class<?> optionEnum = uiSchema.optionEnum();
        String legacyOptions = normalize(uiSchema.options());

        boolean hasOptionItems = optionItems != null && optionItems.length > 0;
        boolean hasOptionEnum = optionEnum != null && optionEnum != Void.class;
        boolean hasLegacyOptions = !legacyOptions.isBlank();

        int sourceCount = (hasOptionItems ? 1 : 0) + (hasOptionEnum ? 1 : 0) + (hasLegacyOptions ? 1 : 0);
        if (sourceCount > 1) {
            issues.add(new SemanticMetadataReviewIssue(
                    SemanticMetadataIssueSeverity.ERROR,
                    "uischema-options-source-conflict",
                    targetType.getName(),
                    propertyName,
                    "@UISchema declares multiple static option sources simultaneously (optionItems, optionEnum, options); choose exactly one."
            ));
        }

        if (hasOptionEnum && (!optionEnum.isEnum()
                || optionEnum.getEnumConstants() == null
                || optionEnum.getEnumConstants().length == 0)) {
            issues.add(new SemanticMetadataReviewIssue(
                    SemanticMetadataIssueSeverity.ERROR,
                    "uischema-option-enum-invalid",
                    targetType.getName(),
                    propertyName,
                    "@UISchema(optionEnum) must reference a non-empty Java enum class: " + optionEnum.getName() + "."
            ));
        } else if (hasOptionEnum && org.praxisplatform.uischema.option.PraxisUiOptionEnum.class.isAssignableFrom(optionEnum)) {
            Set<String> seenEnumValues = new java.util.LinkedHashSet<>();
            for (Object constant : optionEnum.getEnumConstants()) {
                org.praxisplatform.uischema.option.PraxisUiOptionEnum opt =
                        (org.praxisplatform.uischema.option.PraxisUiOptionEnum) constant;
                String optValue = normalize(opt.value());
                String optLabel = normalize(opt.label());
                String optIcon = normalize(opt.icon());
                if (optValue.isBlank()) {
                    issues.add(new SemanticMetadataReviewIssue(
                            SemanticMetadataIssueSeverity.ERROR,
                            "uischema-option-value-blank",
                            targetType.getName(),
                            propertyName,
                            "PraxisUiOptionEnum constant in " + optionEnum.getSimpleName() + " must declare a non-blank value."
                    ));
                } else if (!seenEnumValues.add(optValue)) {
                    issues.add(new SemanticMetadataReviewIssue(
                            SemanticMetadataIssueSeverity.ERROR,
                            "uischema-option-duplicate-value",
                            targetType.getName(),
                            propertyName,
                            "Duplicate PraxisUiOptionEnum value '" + optValue + "' in " + optionEnum.getSimpleName() + "."
                    ));
                }
                if (optLabel.isBlank()) {
                    issues.add(new SemanticMetadataReviewIssue(
                            SemanticMetadataIssueSeverity.ERROR,
                            "uischema-option-label-blank",
                            targetType.getName(),
                            propertyName,
                            "PraxisUiOptionEnum '" + optValue + "' in " + optionEnum.getSimpleName()
                                    + " must declare a non-blank label."
                    ));
                } else if (optLabel.length() > 36 || optLabel.endsWith(".")) {
                    issues.add(new SemanticMetadataReviewIssue(
                            SemanticMetadataIssueSeverity.ERROR,
                            "uischema-option-label-looks-like-sentence",
                            targetType.getName(),
                            propertyName,
                            "PraxisUiOptionEnum '" + optValue + "' label '" + optLabel
                                    + "' exceeds 36 chars or ends with a period."
                    ));
                }
                if (!optIcon.isBlank() && !isValidSnakeCaseIcon(optIcon)) {
                    issues.add(new SemanticMetadataReviewIssue(
                            SemanticMetadataIssueSeverity.ERROR,
                            "uischema-option-icon-invalid",
                            targetType.getName(),
                            propertyName,
                            "PraxisUiOptionEnum '" + optValue + "' icon '" + optIcon
                                    + "' must be a lowercase snake_case Material Icons ligature."
                    ));
                }
            }
        }

        if (hasOptionItems) {
            Set<String> seenValues = new java.util.LinkedHashSet<>();
            for (int i = 0; i < optionItems.length; i++) {
                org.praxisplatform.uischema.annotation.UiOption item = optionItems[i];
                if (item == null) {
                    continue;
                }
                String value = normalize(item.value());
                String label = normalize(item.label());
                String icon = normalize(item.icon());
                if (value.isBlank()) {
                    issues.add(new SemanticMetadataReviewIssue(
                            SemanticMetadataIssueSeverity.ERROR,
                            "uischema-option-value-blank",
                            targetType.getName(),
                            propertyName,
                            "@UiOption at index " + i + " must declare a non-blank value."
                    ));
                } else if (!seenValues.add(value)) {
                    issues.add(new SemanticMetadataReviewIssue(
                            SemanticMetadataIssueSeverity.ERROR,
                            "uischema-option-duplicate-value",
                            targetType.getName(),
                            propertyName,
                            "Duplicate @UiOption value '" + value + "' in @UISchema(optionItems)."
                    ));
                }
                if (label.isBlank()) {
                    issues.add(new SemanticMetadataReviewIssue(
                            SemanticMetadataIssueSeverity.ERROR,
                            "uischema-option-label-blank",
                            targetType.getName(),
                            propertyName,
                            "@UiOption('" + value + "') must declare a non-blank label."
                    ));
                } else if (label.endsWith(".")) {
                    issues.add(new SemanticMetadataReviewIssue(
                            SemanticMetadataIssueSeverity.ERROR,
                            "uischema-option-label-looks-like-sentence",
                            targetType.getName(),
                            propertyName,
                            "@UiOption('" + value + "') label '" + label + "' must not end with a period."
                    ));
                }
                if (!icon.isBlank() && !icon.matches("^[a-z0-9]+(_[a-z0-9]+)*$")) {
                    issues.add(new SemanticMetadataReviewIssue(
                            SemanticMetadataIssueSeverity.ERROR,
                            "uischema-option-icon-invalid",
                            targetType.getName(),
                            propertyName,
                            "@UiOption('" + value + "') icon '" + icon
                                    + "' must be a lowercase snake_case Material Icons ligature."
                    ));
                }
            }
        }

        if (hasLegacyOptions) {
            if (legacyOptions.startsWith("[") || legacyOptions.startsWith("{")) {
                com.fasterxml.jackson.databind.node.ArrayNode parsed =
                        org.praxisplatform.uischema.util.OpenApiUiUtils.parseLegacyOptionsJsonArray(legacyOptions, JSON_MAPPER);
                if (parsed == null || parsed.isEmpty()) {
                    issues.add(new SemanticMetadataReviewIssue(
                            SemanticMetadataIssueSeverity.ERROR,
                            "uischema-options-malformed-json",
                            targetType.getName(),
                            propertyName,
                            "@UISchema(options) contains malformed or empty JSON array; migrate to optionItems = {@UiOption(...)}."
                    ));
                    return;
                }
                String valueKey = normalize(uiSchema.valueField()).isBlank() ? "value" : normalize(uiSchema.valueField());
                String displayKey = normalize(uiSchema.displayField()).isBlank() ? "label" : normalize(uiSchema.displayField());
                Set<String> seenValues = new java.util.LinkedHashSet<>();
                for (com.fasterxml.jackson.databind.JsonNode el : parsed) {
                    if (!el.isObject()) {
                        continue;
                    }
                    String val = normalize(el.path(valueKey).asText(""));
                    String lbl = normalize(el.path(displayKey).asText(""));
                    if (val.isBlank()) {
                        issues.add(new SemanticMetadataReviewIssue(
                                SemanticMetadataIssueSeverity.ERROR,
                                "uischema-options-missing-value-key",
                                targetType.getName(),
                                propertyName,
                                "@UISchema(options) item is missing required '" + valueKey
                                        + "' key (migrate to @UiOption(value = ..., label = ...)): " + el
                        ));
                    } else if (!seenValues.add(val)) {
                        issues.add(new SemanticMetadataReviewIssue(
                                SemanticMetadataIssueSeverity.ERROR,
                                "uischema-option-duplicate-value",
                                targetType.getName(),
                                propertyName,
                                "Duplicate option value '" + val + "' in @UISchema(options)."
                        ));
                    }
                    if (lbl.isBlank()) {
                        issues.add(new SemanticMetadataReviewIssue(
                                SemanticMetadataIssueSeverity.ERROR,
                                "uischema-option-label-blank",
                                targetType.getName(),
                                propertyName,
                                "@UISchema(options) item for value '" + val + "' is missing non-blank '" + displayKey + "'."
                        ));
                    }
                }
            } else {
                com.fasterxml.jackson.databind.node.ArrayNode pipeParsed =
                        org.praxisplatform.uischema.util.OpenApiUiUtils.parsePipeDelimitedOptions(legacyOptions, JSON_MAPPER);
                if (pipeParsed == null || pipeParsed.isEmpty()) {
                    issues.add(new SemanticMetadataReviewIssue(
                            SemanticMetadataIssueSeverity.ERROR,
                            "uischema-options-malformed-json",
                            targetType.getName(),
                            propertyName,
                            "@UISchema(options) could not be parsed; migrate to optionItems = {@UiOption(...)}."
                    ));
                }
            }
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

    private void reviewTableView(
            Class<?> targetType,
            Set<String> allPropertyNames,
            List<SemanticMetadataReviewIssue> issues
    ) {
        org.praxisplatform.uischema.annotation.TableView tableView =
                findTypeAnnotation(targetType, org.praxisplatform.uischema.annotation.TableView.class);
        if (tableView == null) {
            return;
        }
        String[] columns = tableView.columns();
        if (columns == null || columns.length == 0) {
            issues.add(new SemanticMetadataReviewIssue(
                    SemanticMetadataIssueSeverity.ERROR,
                    "tableview-columns-empty",
                    targetType.getName(),
                    targetType.getSimpleName(),
                    "@TableView(columns) must declare at least one property name."
            ));
            return;
        }
        Set<String> seenColumns = new java.util.LinkedHashSet<>();
        for (String rawCol : columns) {
            String col = normalize(rawCol);
            if (col.isBlank()) {
                issues.add(new SemanticMetadataReviewIssue(
                        SemanticMetadataIssueSeverity.ERROR,
                        "tableview-column-blank",
                        targetType.getName(),
                        targetType.getSimpleName(),
                        "@TableView(columns) must not contain blank property names."
                ));
                continue;
            }
            if (!seenColumns.add(col)) {
                issues.add(new SemanticMetadataReviewIssue(
                        SemanticMetadataIssueSeverity.ERROR,
                        "tableview-column-duplicate",
                        targetType.getName(),
                        col,
                        "@TableView(columns) declares duplicate property '" + col + "'."
                ));
            }
            if (!allPropertyNames.contains(col)) {
                issues.add(new SemanticMetadataReviewIssue(
                        SemanticMetadataIssueSeverity.ERROR,
                        "tableview-column-unknown",
                        targetType.getName(),
                        col,
                        "@TableView(columns) references unknown DTO property '" + col + "'."
                ));
            }
        }
        Set<String> seenStart = validatePinnedColumns(
                targetType, "pinnedStart", tableView.pinnedStart(), seenColumns, allPropertyNames, issues);
        Set<String> seenEnd = validatePinnedColumns(
                targetType, "pinnedEnd", tableView.pinnedEnd(), seenColumns, allPropertyNames, issues);
        for (String col : seenStart) {
            if (seenEnd.contains(col)) {
                issues.add(new SemanticMetadataReviewIssue(
                        SemanticMetadataIssueSeverity.ERROR,
                        "tableview-pinned-conflict",
                        targetType.getName(),
                        col,
                        "@TableView pins '" + col + "' in both pinnedStart and pinnedEnd."
                ));
            }
        }
    }

    private Set<String> validatePinnedColumns(
            Class<?> targetType,
            String attributeName,
            String[] rawCols,
            Set<String> declaredColumns,
            Set<String> allPropertyNames,
            List<SemanticMetadataReviewIssue> issues
    ) {
        Set<String> seen = new java.util.LinkedHashSet<>();
        if (rawCols == null) {
            return seen;
        }
        for (String raw : rawCols) {
            String col = normalize(raw);
            if (col.isBlank()) {
                issues.add(new SemanticMetadataReviewIssue(
                        SemanticMetadataIssueSeverity.ERROR,
                        "tableview-pinned-blank",
                        targetType.getName(),
                        attributeName,
                        "@TableView(" + attributeName + ") must not contain blank entries."
                ));
                continue;
            }
            if (!seen.add(col)) {
                issues.add(new SemanticMetadataReviewIssue(
                        SemanticMetadataIssueSeverity.ERROR,
                        "tableview-pinned-duplicate",
                        targetType.getName(),
                        col,
                        "@TableView(" + attributeName + ") declares duplicate property '" + col + "'."
                ));
            }
            if (!allPropertyNames.contains(col)) {
                issues.add(new SemanticMetadataReviewIssue(
                        SemanticMetadataIssueSeverity.ERROR,
                        "tableview-pinned-unknown",
                        targetType.getName(),
                        col,
                        "@TableView(" + attributeName + ") references unknown DTO property '" + col + "'."
                ));
            } else if (!declaredColumns.contains(col)) {
                issues.add(new SemanticMetadataReviewIssue(
                        SemanticMetadataIssueSeverity.ERROR,
                        "tableview-pinned-not-in-columns",
                        targetType.getName(),
                        col,
                        "@TableView(" + attributeName + ") references '" + col + "' which is absent from columns()."
                ));
            }
        }
        return seen;
    }

    private void reviewUiFormLayout(Class<?> targetType, List<SemanticMetadataReviewIssue> issues) {
        org.praxisplatform.uischema.annotation.UiFormLayout layout =
                findTypeAnnotation(targetType, org.praxisplatform.uischema.annotation.UiFormLayout.class);
        if (layout == null) {
            return;
        }
        Set<String> seenIds = new java.util.LinkedHashSet<>();
        for (org.praxisplatform.uischema.annotation.UiSection section : layout.sections()) {
            String sectionId = normalize(section.id());
            String title = normalize(section.title());
            String icon = normalize(section.icon());
            if (sectionId.isBlank()) {
                issues.add(new SemanticMetadataReviewIssue(
                        SemanticMetadataIssueSeverity.ERROR,
                        "form-layout-section-id-blank",
                        targetType.getName(),
                        "@UiFormLayout",
                        "@UiSection must declare a non-blank id."
                ));
            } else if (!seenIds.add(sectionId)) {
                issues.add(new SemanticMetadataReviewIssue(
                        SemanticMetadataIssueSeverity.ERROR,
                        "form-layout-section-duplicate-id",
                        targetType.getName(),
                        sectionId,
                        "Duplicate @UiSection id '" + sectionId + "' in @UiFormLayout."
                ));
            }
            if (title.isBlank()) {
                issues.add(new SemanticMetadataReviewIssue(
                        SemanticMetadataIssueSeverity.ERROR,
                        "form-layout-section-title-blank",
                        targetType.getName(),
                        sectionId.isBlank() ? "@UiSection" : sectionId,
                        "@UiSection must declare a non-blank title."
                ));
            }
            if (!icon.isBlank() && !isValidSnakeCaseIcon(icon)) {
                issues.add(new SemanticMetadataReviewIssue(
                        SemanticMetadataIssueSeverity.ERROR,
                        "form-layout-section-icon-invalid",
                        targetType.getName(),
                        sectionId.isBlank() ? "@UiSection" : sectionId,
                        "@UiSection(icon='" + icon + "') must be a lowercase snake_case Material Icons ligature."
                ));
            }
        }
    }

    private void reviewDomainVisualizers(
            Class<?> targetType,
            Set<String> allPropertyNames,
            List<SemanticMetadataReviewIssue> issues
    ) {
        var progression = findTypeAnnotation(targetType, org.praxisplatform.uischema.annotation.ResourceProgression.class);
        if (progression != null) {
            validateVisualizerField(targetType, "@ResourceProgression", "currentLevelField", progression.currentLevelField(), allPropertyNames, issues);
            validateVisualizerField(targetType, "@ResourceProgression", "currentStepField", progression.currentStepField(), allPropertyNames, issues);
            validateVisualizerField(targetType, "@ResourceProgression", "maxLevelField", progression.maxLevelField(), allPropertyNames, issues);
            validateVisualizerField(targetType, "@ResourceProgression", "nextStepField", progression.nextStepField(), allPropertyNames, issues);
            validateVisualizerField(targetType, "@ResourceProgression", "nextEligibleDateField", progression.nextEligibleDateField(), allPropertyNames, issues);
            validateVisualizerField(targetType, "@ResourceProgression", "remainingDaysField", progression.remainingDaysField(), allPropertyNames, issues);
            validateVisualizerField(targetType, "@ResourceProgression", "scoreField", progression.scoreField(), allPropertyNames, issues);
            validateVisualizerField(targetType, "@ResourceProgression", "statusField", progression.statusField(), allPropertyNames, issues);
        }
        var timeline = findTypeAnnotation(targetType, org.praxisplatform.uischema.annotation.ResourceTimeline.class);
        if (timeline != null) {
            validateVisualizerField(targetType, "@ResourceTimeline", "startDateField", timeline.startDateField(), allPropertyNames, issues);
            validateVisualizerField(targetType, "@ResourceTimeline", "endDateField", timeline.endDateField(), allPropertyNames, issues);
            validateVisualizerField(targetType, "@ResourceTimeline", "referenceDateField", timeline.referenceDateField(), allPropertyNames, issues);
            validateVisualizerField(targetType, "@ResourceTimeline", "statusField", timeline.statusField(), allPropertyNames, issues);
            validateVisualizerField(targetType, "@ResourceTimeline", "categoryField", timeline.categoryField(), allPropertyNames, issues);
            validateVisualizerField(targetType, "@ResourceTimeline", "labelField", timeline.labelField(), allPropertyNames, issues);
            validateVisualizerField(targetType, "@ResourceTimeline", "durationField", timeline.durationField(), allPropertyNames, issues);
            validateVisualizerField(targetType, "@ResourceTimeline", "actorField", timeline.actorField(), allPropertyNames, issues);
        }
        var schedule = findTypeAnnotation(targetType, org.praxisplatform.uischema.annotation.ResourceSchedule.class);
        if (schedule != null) {
            validateVisualizerField(targetType, "@ResourceSchedule", "startTimeField", schedule.startTimeField(), allPropertyNames, issues);
            validateVisualizerField(targetType, "@ResourceSchedule", "endTimeField", schedule.endTimeField(), allPropertyNames, issues);
            validateVisualizerField(targetType, "@ResourceSchedule", "timezoneField", schedule.timezoneField(), allPropertyNames, issues);
            validateVisualizerField(targetType, "@ResourceSchedule", "recurrenceField", schedule.recurrenceField(), allPropertyNames, issues);
            validateVisualizerField(targetType, "@ResourceSchedule", "dayOfWeekField", schedule.dayOfWeekField(), allPropertyNames, issues);
            validateVisualizerField(targetType, "@ResourceSchedule", "shiftField", schedule.shiftField(), allPropertyNames, issues);
            validateVisualizerField(targetType, "@ResourceSchedule", "capacityField", schedule.capacityField(), allPropertyNames, issues);
            validateVisualizerField(targetType, "@ResourceSchedule", "allocatedField", schedule.allocatedField(), allPropertyNames, issues);
            validateVisualizerField(targetType, "@ResourceSchedule", "statusField", schedule.statusField(), allPropertyNames, issues);
        }
    }

    private void validateVisualizerField(
            Class<?> targetType,
            String annotationName,
            String attrName,
            String rawFieldName,
            Set<String> allPropertyNames,
            List<SemanticMetadataReviewIssue> issues
    ) {
        String fieldName = normalize(rawFieldName);
        if (!fieldName.isBlank() && !allPropertyNames.contains(fieldName)) {
            issues.add(new SemanticMetadataReviewIssue(
                    SemanticMetadataIssueSeverity.ERROR,
                    "resource-visualizer-field-unknown",
                    targetType.getName(),
                    fieldName,
                    annotationName + "(" + attrName + "='" + fieldName + "') references unknown DTO property."
            ));
        }
    }

    private void reviewUiSchemaStructuralContracts(
            Class<?> targetType,
            String propertyName,
            UISchema uiSchema,
            List<SemanticMetadataReviewIssue> issues
    ) {
        if (uiSchema == null) {
            return;
        }
        int colSpan = uiSchema.colSpan();
        if (colSpan < 0 || colSpan > 12) {
            issues.add(new SemanticMetadataReviewIssue(
                    SemanticMetadataIssueSeverity.ERROR,
                    "uischema-colspan-invalid",
                    targetType.getName(),
                    propertyName,
                    "@UISchema(colSpan=" + colSpan + ") must be 0 (auto) or between 1 and 12."
            ));
        }
        String conditionalReadOnly = normalize(uiSchema.conditionalReadOnly());
        if (!conditionalReadOnly.isBlank()) {
            try {
                Object parsed = JSON_MAPPER.readValue(conditionalReadOnly, Object.class);
                JSON_LOGIC_VALIDATOR.validateCondition(parsed, "conditionalReadOnly", true);
            } catch (Exception ex) {
                issues.add(new SemanticMetadataReviewIssue(
                        SemanticMetadataIssueSeverity.ERROR,
                        "uischema-conditional-readonly-invalid",
                        targetType.getName(),
                        propertyName,
                        "@UISchema(conditionalReadOnly) must be a valid Json Logic expression: " + ex.getMessage()
                ));
            }
        }
    }

    private void reviewFilterableProperty(
            Class<?> targetType,
            String propertyName,
            org.praxisplatform.uischema.filter.annotation.Filterable filterable,
            List<SemanticMetadataReviewIssue> issues
    ) {
        if (filterable == null) {
            return;
        }
        if (filterable.operation() == org.praxisplatform.uischema.filter.annotation.Filterable.FilterOperation.EFFECTIVE_ON_DATE
                && (normalize(filterable.relation()).isBlank() || normalize(filterable.endRelation()).isBlank())) {
            issues.add(new SemanticMetadataReviewIssue(
                    SemanticMetadataIssueSeverity.ERROR,
                    "effective-on-date-missing-relations",
                    targetType.getName(),
                    propertyName,
                    "@Filterable(operation = EFFECTIVE_ON_DATE) requires explicit non-blank relation and endRelation."
            ));
        }
    }

    private String resolveControllerDefaultOperationId(Class<?> controllerClass, boolean create) {
        Method[] methods = org.springframework.util.ReflectionUtils.getUniqueDeclaredMethods(
                controllerClass,
                method -> !method.isBridge() && !method.isSynthetic()
        );
        for (Method candidate : methods) {
            if (org.springframework.core.annotation.AnnotatedElementUtils.hasAnnotation(
                    candidate, org.praxisplatform.uischema.annotation.ReactiveDetermination.class)
                    || org.springframework.core.annotation.AnnotatedElementUtils.hasAnnotation(
                    candidate, org.praxisplatform.uischema.annotation.WorkflowAction.class)
                    || org.springframework.core.annotation.AnnotatedElementUtils.hasAnnotation(
                    candidate, org.praxisplatform.uischema.annotation.UiSurface.class)) {
                continue;
            }
            if (create && isCanonicalCreateHandler(candidate)) {
                return effectiveHandlerOperationId(candidate);
            }
            if (!create && isCanonicalUpdateHandler(candidate)) {
                return effectiveHandlerOperationId(candidate);
            }
        }
        return "";
    }

    private static boolean isCanonicalCreateHandler(Method method) {
        var postMapping = org.springframework.core.annotation.AnnotatedElementUtils.findMergedAnnotation(
                method, org.springframework.web.bind.annotation.PostMapping.class);
        if (postMapping != null) {
            return hasRootPath(postMapping.value(), postMapping.path());
        }
        var requestMapping = org.springframework.core.annotation.AnnotatedElementUtils.findMergedAnnotation(
                method, org.springframework.web.bind.annotation.RequestMapping.class);
        if (requestMapping != null) {
            for (var rm : requestMapping.method()) {
                if (rm == org.springframework.web.bind.annotation.RequestMethod.POST) {
                    return hasRootPath(requestMapping.value(), requestMapping.path());
                }
            }
        }
        return false;
    }

    private static boolean isCanonicalUpdateHandler(Method method) {
        var putMapping = org.springframework.core.annotation.AnnotatedElementUtils.findMergedAnnotation(
                method, org.springframework.web.bind.annotation.PutMapping.class);
        if (putMapping != null) {
            return hasUpdateIdPath(putMapping.value(), putMapping.path());
        }
        var requestMapping = org.springframework.core.annotation.AnnotatedElementUtils.findMergedAnnotation(
                method, org.springframework.web.bind.annotation.RequestMapping.class);
        if (requestMapping != null) {
            for (var rm : requestMapping.method()) {
                if (rm == org.springframework.web.bind.annotation.RequestMethod.PUT) {
                    return hasUpdateIdPath(requestMapping.value(), requestMapping.path());
                }
            }
        }
        return false;
    }

    private static boolean hasRootPath(String[] values, String[] paths) {
        List<String> all = new ArrayList<>();
        if (values != null) java.util.Collections.addAll(all, values);
        if (paths != null) java.util.Collections.addAll(all, paths);
        return all.isEmpty() || all.stream().anyMatch(p -> p == null || p.isBlank() || "/".equals(p.trim()));
    }

    private static boolean hasUpdateIdPath(String[] values, String[] paths) {
        List<String> all = new ArrayList<>();
        if (values != null) java.util.Collections.addAll(all, values);
        if (paths != null) java.util.Collections.addAll(all, paths);
        return all.stream().anyMatch(p -> p != null && ("/{id}".equals(p.trim()) || "{id}".equals(p.trim())));
    }

    private String effectiveHandlerOperationId(Method method) {
        io.swagger.v3.oas.annotations.Operation op =
                org.springframework.core.annotation.AnnotatedElementUtils.findMergedAnnotation(
                        method, io.swagger.v3.oas.annotations.Operation.class);
        if (op != null && !normalize(op.operationId()).isBlank()) {
            return normalize(op.operationId());
        }
        return method.getName();
    }
}
