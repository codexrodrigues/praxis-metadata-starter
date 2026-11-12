package org.praxisplatform.uischema.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.praxisplatform.uischema.annotation.AiControlledUseMode;
import org.praxisplatform.uischema.annotation.AiTrainingUseMode;
import org.praxisplatform.uischema.annotation.AiUsagePolicy;
import org.praxisplatform.uischema.annotation.AiVisibilityMode;
import org.praxisplatform.uischema.annotation.DomainClassification;
import org.praxisplatform.uischema.annotation.DomainDataCategory;
import org.praxisplatform.uischema.annotation.DomainGovernance;
import org.praxisplatform.uischema.annotation.DomainGovernanceKind;
import org.praxisplatform.uischema.annotation.DomainGovernancePreset;
import org.praxisplatform.uischema.annotation.DomainLineage;
import org.praxisplatform.uischema.annotation.DomainLineageTransformation;
import org.praxisplatform.uischema.extension.ResolverUtils;
import org.springframework.util.ClassUtils;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Resolvedor canonico e deterministico de {@link DomainLineage} e {@link DomainGovernance}
 * compartilhado entre OpenAPI ({@code CustomOpenApiResolver}), catalogo semantico
 * ({@code SemanticDomainCatalogService}), revisor estatico ({@code SemanticMetadataReviewer})
 * e exportacao governada ({@code ResourceExportSupport}) ({@code P44–P50}, {@code P60}, {@code P64}).
 */
public final class DomainLineageResolver {

    public static final int MAX_LINEAGE_DEPTH = 8;
    public static final int MAX_LINEAGE_SOURCES = 16;

    private static final Map<String, Integer> CLASSIFICATION_RANK = Map.of(
            "public", 0,
            "internal", 1,
            "confidential", 2,
            "restricted", 3
    );

    private DomainLineageResolver() {
    }

    /**
     * Resultado imutavel da resolucao de linhagem e governanca de um membro.
     */
    public record ResolvedLineage(
            String attributeId,
            Class<?> sourceClass,
            String sourceResourceKey,
            List<String> sourcePaths,
            DomainLineageTransformation transformation,
            ObjectNode originGovernance,
            ObjectNode effectiveGovernance,
            boolean weakeningAttempted,
            String weakeningReason
    ) {
    }

    /**
     * Converte uma anotacao {@link DomainGovernance} (incluindo expansao de {@link DomainGovernancePreset})
     * para o {@link ObjectNode} canonico usado por {@link DomainSchemaGovernance}.
     */
    public static ObjectNode toGovernanceNode(DomainGovernance governance) {
        if (governance == null) {
            return null;
        }
        DomainGovernancePreset preset = governance.preset();
        boolean hasPreset = preset != null && preset != DomainGovernancePreset.NONE;
        DomainGovernanceKind effectiveKind = hasPreset && governance.kind() == DomainGovernanceKind.PRIVACY
                ? preset.kind()
                : governance.kind();
        DomainClassification effectiveClassification = hasPreset && governance.classification() == DomainClassification.INTERNAL
                ? preset.classification()
                : governance.classification();
        DomainDataCategory effectiveCategory = hasPreset && governance.dataCategory() == DomainDataCategory.OPERATIONAL
                ? preset.dataCategory()
                : governance.dataCategory();
        List<String> effectiveTags = governance.complianceTags() != null && governance.complianceTags().length > 0
                ? List.of(governance.complianceTags())
                : (hasPreset ? preset.complianceTags() : List.of());
        boolean effectiveExportable = !governance.exportable()
                ? false
                : (hasPreset ? preset.exportable() : true);

        AiUsagePolicy ai = governance.aiUsage();
        boolean isDefaultAiUsage = ai.visibility() == AiVisibilityMode.ALLOW
                && ai.trainingUse() == AiTrainingUseMode.DENY
                && ai.ruleAuthoring() == AiControlledUseMode.REVIEW_REQUIRED
                && ai.reasoningUse() == AiControlledUseMode.ALLOW;

        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("annotationType", effectiveKind.wireValue());
        node.put("classification", effectiveClassification.wireValue());
        node.put("dataCategory", effectiveCategory.wireValue());
        ArrayNode tags = node.putArray("complianceTags");
        for (String tag : effectiveTags) {
            if (tag != null && !tag.isBlank()) {
                tags.add(tag.trim());
            }
        }
        node.put("exportable", effectiveExportable);
        ObjectNode aiUsage = node.putObject("aiUsage");
        aiUsage.put("visibility", (hasPreset && isDefaultAiUsage ? preset.aiVisibility() : ai.visibility()).wireValue());
        aiUsage.put("trainingUse", (hasPreset && isDefaultAiUsage ? preset.aiTrainingUse() : ai.trainingUse()).wireValue());
        aiUsage.put("ruleAuthoring", (hasPreset && isDefaultAiUsage ? preset.aiRuleAuthoring() : ai.ruleAuthoring()).wireValue());
        aiUsage.put("reasoningUse", (hasPreset && isDefaultAiUsage ? preset.aiReasoningUse() : ai.reasoningUse()).wireValue());
        if (governance.reason() != null && !governance.reason().isBlank()) {
            node.put("reason", governance.reason().trim());
        }
        node.put("source", "java.annotation");
        node.put("confidence", governance.confidence());
        return node;
    }

    /**
     * Resolve a governanca em nivel de classe (interfaces + hierarquia de superclasses) para {@code type}.
     */
    public static ObjectNode resolveTypeGovernance(Class<?> type) {
        if (type == null || type == Object.class || type == Void.class) {
            return null;
        }
        ObjectNode combined = null;
        List<Class<?>> hierarchy = new ArrayList<>();
        for (Class<?> c = type; c != null && c != Object.class && c != Record.class; c = c.getSuperclass()) {
            hierarchy.add(c);
        }
        Collections.reverse(hierarchy);
        for (Class<?> contract : ClassUtils.getAllInterfacesForClass(type)) {
            DomainGovernance g = ResolverUtils.getAnnotation(DomainGovernance.class, contract.getAnnotations());
            if (g != null) {
                combined = DomainSchemaGovernance.mergeOrCopy(combined, toGovernanceNode(g));
            }
        }
        for (Class<?> c : hierarchy) {
            DomainGovernance g = ResolverUtils.getAnnotation(DomainGovernance.class, c.getAnnotations());
            if (g != null) {
                combined = DomainSchemaGovernance.mergeOrCopy(combined, toGovernanceNode(g));
            }
        }
        return combined;
    }

    /**
     * Resolve uma declaracao {@link DomainLineage} combinada opcionalmente com {@link DomainGovernance}
     * local do membro e da classe proprietaria.
     */
    public static ResolvedLineage resolve(
            DomainLineage lineage,
            DomainGovernance localMemberGovernance,
            ObjectNode ownerTypeGovernance
    ) {
        return resolveInternal(lineage, localMemberGovernance, ownerTypeGovernance, new LinkedHashSet<>(), 0);
    }

    private static ResolvedLineage resolveInternal(
            DomainLineage lineage,
            DomainGovernance localMemberGovernance,
            ObjectNode ownerTypeGovernance,
            Set<String> visited,
            int depth
    ) {
        if (lineage == null) {
            throw new IllegalArgumentException("DomainLineage must not be null");
        }
        if (depth > MAX_LINEAGE_DEPTH) {
            throw new IllegalArgumentException("Domain lineage resolution exceeds maximum depth (" + MAX_LINEAGE_DEPTH + ")");
        }

        Class<?> sourceClass = lineage.sourceClass() == null ? Void.class : lineage.sourceClass();
        String sourceResourceKey = normalize(lineage.sourceResourceKey());
        String explicitAttributeId = normalize(lineage.attributeId());
        DomainLineageTransformation transformation = lineage.transformation() == null
                ? DomainLineageTransformation.DIRECT
                : lineage.transformation();

        String[] rawPaths = lineage.sourcePaths() == null ? new String[0] : lineage.sourcePaths();
        if (rawPaths.length > MAX_LINEAGE_SOURCES) {
            throw new IllegalArgumentException(
                    "Domain lineage exceeds maximum allowed sourcePaths (" + MAX_LINEAGE_SOURCES + "): " + rawPaths.length);
        }
        List<String> sourcePaths = new ArrayList<>(rawPaths.length);
        for (String rawPath : rawPaths) {
            String normalizedPath = normalize(rawPath);
            if (normalizedPath.isEmpty()) {
                throw new IllegalArgumentException("@DomainLineage sourcePaths must not contain blank entries");
            }
            sourcePaths.add(normalizedPath);
        }

        if (sourceClass != Void.class && sourcePaths.isEmpty()) {
            throw new IllegalArgumentException(
                    "@DomainLineage with sourceClass " + sourceClass.getSimpleName() + " must declare at least one sourcePath");
        }
        if (sourceClass == Void.class && explicitAttributeId.isEmpty()) {
            throw new IllegalArgumentException(
                    "@DomainLineage without sourceClass (external/non-JPA projection) must declare an explicit attributeId");
        }

        ObjectNode originGovernance = null;
        if (sourceClass != Void.class) {
            for (String path : sourcePaths) {
                ObjectNode pathGovernance = resolveSourcePathGovernance(sourceClass, path, visited, depth + 1);
                if (pathGovernance == null) {
                    throw new IllegalArgumentException(
                            "Referenced @DomainLineage source '" + sourceClass.getSimpleName() + "." + path
                                    + "' has no @DomainGovernance declaration");
                }
                originGovernance = DomainSchemaGovernance.mergeOrCopy(originGovernance, pathGovernance);
            }
        }

        ObjectNode localMemberNode = toGovernanceNode(localMemberGovernance);
        if (originGovernance == null && localMemberNode == null && ownerTypeGovernance == null) {
            throw new IllegalArgumentException(
                    "@DomainLineage for attribute '" + explicitAttributeId
                            + "' has no resolvable origin governance and no local @DomainGovernance");
        }

        boolean weakeningAttempted = false;
        String weakeningReason = null;
        if (originGovernance != null && localMemberGovernance != null) {
            String violation = detectWeakeningAttempt(originGovernance, localMemberGovernance);
            if (violation != null) {
                weakeningAttempted = true;
                weakeningReason = violation;
            }
        }

        String attributeId = synthesizeAttributeId(explicitAttributeId, sourceResourceKey, sourceClass, sourcePaths);

        ObjectNode effective = null;
        if (ownerTypeGovernance != null) {
            effective = DomainSchemaGovernance.mergeOrCopy(effective, ownerTypeGovernance);
        }
        if (originGovernance != null) {
            effective = DomainSchemaGovernance.mergeOrCopy(effective, originGovernance);
        }
        if (localMemberNode != null) {
            effective = DomainSchemaGovernance.mergeOrCopy(effective, localMemberNode);
        }

        ObjectNode enriched = effective.deepCopy();
        String sourceKind = localMemberNode != null ? "java.lineage+annotation" : "java.lineage";
        enriched.put("source", sourceKind);
        if (localMemberGovernance != null && !normalize(localMemberGovernance.reason()).isEmpty()) {
            enriched.put("reason", normalize(localMemberGovernance.reason()));
        } else if (originGovernance != null && originGovernance.hasNonNull("reason")) {
            enriched.put("reason", originGovernance.path("reason").asText());
        }

        ObjectNode lineageNode = enriched.putObject("lineage");
        if (!attributeId.isEmpty()) {
            lineageNode.put("attributeId", attributeId);
        }
        if (sourceClass != Void.class) {
            lineageNode.put("sourceClass", sourceClass.getName());
            lineageNode.put("sourceSimpleName", sourceClass.getSimpleName());
        }
        if (!sourceResourceKey.isEmpty()) {
            lineageNode.put("sourceResourceKey", sourceResourceKey);
        }
        ArrayNode pathsArray = lineageNode.putArray("sourcePaths");
        for (String path : sourcePaths) {
            pathsArray.add(path);
        }
        lineageNode.put("transformation", transformation.wireValue());
        lineageNode.put("provenance", "declared_lineage");
        if (weakeningAttempted) {
            lineageNode.put("weakeningAttempted", true);
            if (weakeningReason != null && !weakeningReason.isBlank()) {
                lineageNode.put("weakeningReason", weakeningReason);
            }
        }

        return new ResolvedLineage(
                attributeId,
                sourceClass,
                sourceResourceKey,
                List.copyOf(sourcePaths),
                transformation,
                originGovernance,
                enriched,
                weakeningAttempted,
                weakeningReason
        );
    }

    /**
     * Converte o {@link ObjectNode} de governanca (com eventual bloco {@code lineage}) em um
     * {@code Map<String, Object>} pronto para publicacao em {@code property.getExtensions().put("x-domain-governance", ...)}.
     */
    public static Map<String, Object> toExtensionMap(ObjectNode governanceNode) {
        if (governanceNode == null) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("annotationType", governanceNode.path("annotationType").asText("privacy"));
        result.put("classification", governanceNode.path("classification").asText("internal"));
        result.put("dataCategory", governanceNode.path("dataCategory").asText("operational"));

        List<String> tags = new ArrayList<>();
        JsonNode tagsNode = governanceNode.path("complianceTags");
        if (tagsNode.isArray()) {
            tagsNode.forEach(item -> {
                if (item != null && item.isTextual() && !item.asText().isBlank()) {
                    tags.add(item.asText());
                }
            });
        }
        result.put("complianceTags", List.copyOf(tags));
        result.put("exportable", DomainSchemaGovernance.isExportable(governanceNode));

        JsonNode aiNode = governanceNode.path("aiUsage");
        Map<String, Object> aiMap = new LinkedHashMap<>();
        aiMap.put("visibility", aiNode.path("visibility").asText("allow"));
        aiMap.put("trainingUse", aiNode.path("trainingUse").asText("deny"));
        aiMap.put("ruleAuthoring", aiNode.path("ruleAuthoring").asText("review_required"));
        aiMap.put("reasoningUse", aiNode.path("reasoningUse").asText("allow"));
        result.put("aiUsage", Collections.unmodifiableMap(aiMap));

        result.put("reason", governanceNode.path("reason").asText(""));
        result.put("source", governanceNode.path("source").asText("java.annotation"));
        result.put("confidence", governanceNode.path("confidence").asDouble(1.0d));

        JsonNode lineageNode = governanceNode.path("lineage");
        if (lineageNode.isObject()) {
            Map<String, Object> lineageMap = new LinkedHashMap<>();
            if (lineageNode.hasNonNull("attributeId")) {
                lineageMap.put("attributeId", lineageNode.path("attributeId").asText());
            }
            if (lineageNode.hasNonNull("sourceClass")) {
                lineageMap.put("sourceClass", lineageNode.path("sourceClass").asText());
            }
            if (lineageNode.hasNonNull("sourceSimpleName")) {
                lineageMap.put("sourceSimpleName", lineageNode.path("sourceSimpleName").asText());
            }
            if (lineageNode.hasNonNull("sourceResourceKey")) {
                lineageMap.put("sourceResourceKey", lineageNode.path("sourceResourceKey").asText());
            }
            List<String> paths = new ArrayList<>();
            JsonNode sourcePathsNode = lineageNode.path("sourcePaths");
            if (sourcePathsNode.isArray()) {
                sourcePathsNode.forEach(item -> {
                    if (item != null && item.isTextual()) {
                        paths.add(item.asText());
                    }
                });
            }
            lineageMap.put("sourcePaths", List.copyOf(paths));
            lineageMap.put("transformation", lineageNode.path("transformation").asText("direct"));
            lineageMap.put("provenance", lineageNode.path("provenance").asText("declared_lineage"));
            if (lineageNode.path("weakeningAttempted").asBoolean(false)) {
                lineageMap.put("weakeningAttempted", true);
                if (lineageNode.hasNonNull("weakeningReason")) {
                    lineageMap.put("weakeningReason", lineageNode.path("weakeningReason").asText());
                }
            }
            result.put("lineage", Collections.unmodifiableMap(lineageMap));
        }

        return Collections.unmodifiableMap(result);
    }

    private static ObjectNode resolveSourcePathGovernance(
            Class<?> rootSourceClass,
            String dottedPath,
            Set<String> visited,
            int depth
    ) {
        if (depth > MAX_LINEAGE_DEPTH) {
            throw new IllegalArgumentException("Domain lineage resolution exceeds maximum depth (" + MAX_LINEAGE_DEPTH + ")");
        }
        String visitKey = rootSourceClass.getName() + "#" + dottedPath;
        if (!visited.add(visitKey)) {
            throw new IllegalArgumentException("Cyclic @DomainLineage reference detected at " + visitKey);
        }
        try {
            String[] segments = dottedPath.split("\\.");
            if (segments.length > MAX_LINEAGE_DEPTH) {
                throw new IllegalArgumentException(
                        "Domain lineage sourcePath '" + dottedPath + "' exceeds maximum segment depth (" + MAX_LINEAGE_DEPTH + ")");
            }
            Class<?> currentClass = rootSourceClass;
            ObjectNode accumulated = null;
            for (int i = 0; i < segments.length; i++) {
                String segment = normalize(segments[i]);
                if (segment.isEmpty()) {
                    throw new IllegalArgumentException(
                            "Invalid @DomainLineage sourcePath '" + dottedPath + "' on " + rootSourceClass.getName());
                }
                if (currentClass == null || currentClass == Object.class || currentClass == Void.class) {
                    throw new IllegalArgumentException(
                            "Unresolved @DomainLineage sourcePath '" + dottedPath + "' on " + rootSourceClass.getName()
                                    + " (cannot traverse segment '" + segment + "')");
                }
                PropertyTarget prop = findPropertyTarget(currentClass, segment);
                if (prop == null) {
                    throw new IllegalArgumentException(
                            "Unresolved @DomainLineage sourcePath '" + dottedPath + "' on " + rootSourceClass.getName()
                                    + " (missing property '" + segment + "' on " + currentClass.getName() + ")");
                }

                ObjectNode classGov = resolveTypeGovernance(currentClass);
                ObjectNode memberGov = null;
                for (DomainGovernance g : prop.governanceAnnotations()) {
                    memberGov = DomainSchemaGovernance.mergeOrCopy(memberGov, toGovernanceNode(g));
                }

                DomainLineage chainedLineage = prop.lineageAnnotation();
                ObjectNode chainedGov = null;
                if (chainedLineage != null) {
                    ResolvedLineage resolvedChained = resolveInternal(
                            chainedLineage,
                            null,
                            classGov,
                            visited,
                            depth + 1
                    );
                    chainedGov = resolvedChained.effectiveGovernance();
                }

                ObjectNode stepGov = DomainSchemaGovernance.mergeOrCopy(classGov, chainedGov);
                stepGov = DomainSchemaGovernance.mergeOrCopy(stepGov, memberGov);
                accumulated = DomainSchemaGovernance.mergeOrCopy(accumulated, stepGov);

                currentClass = prop.propertyType();
            }
            return accumulated;
        } finally {
            visited.remove(visitKey);
        }
    }

    private record PropertyTarget(
            Class<?> propertyType,
            List<DomainGovernance> governanceAnnotations,
            DomainLineage lineageAnnotation
    ) {
    }

    private static PropertyTarget findPropertyTarget(Class<?> ownerType, String propertyName) {
        Class<?> propertyType = null;
        List<AnnotatedElement> elements = new ArrayList<>();

        for (Class<?> c = ownerType; c != null && c != Object.class && c != Record.class; c = c.getSuperclass()) {
            if (c.isRecord() && c.getRecordComponents() != null) {
                for (RecordComponent rc : c.getRecordComponents()) {
                    if (rc.getName().equals(propertyName)) {
                        if (propertyType == null) {
                            propertyType = rc.getType();
                        }
                        elements.add(rc);
                        if (rc.getAccessor() != null) {
                            elements.add(rc.getAccessor());
                        }
                    }
                }
            }
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.isSynthetic()) {
                    continue;
                }
                if (f.getName().equals(propertyName)) {
                    if (propertyType == null) {
                        propertyType = f.getType();
                    }
                    elements.add(f);
                }
            }
            addMatchingAccessorMethods(c, propertyName, elements);
            if (propertyType == null && !elements.isEmpty()) {
                for (AnnotatedElement el : elements) {
                    if (el instanceof Method m) {
                        propertyType = m.getReturnType();
                        break;
                    }
                }
            }
        }

        for (Class<?> iface : ClassUtils.getAllInterfacesForClass(ownerType)) {
            addMatchingAccessorMethods(iface, propertyName, elements);
            if (propertyType == null && !elements.isEmpty()) {
                for (AnnotatedElement el : elements) {
                    if (el instanceof Method m) {
                        propertyType = m.getReturnType();
                        break;
                    }
                }
            }
        }

        if (elements.isEmpty()) {
            return null;
        }

        List<DomainGovernance> govList = new ArrayList<>();
        DomainLineage lineage = null;
        for (AnnotatedElement el : elements) {
            Annotation[] anns = el.getAnnotations();
            DomainGovernance g = ResolverUtils.getAnnotation(DomainGovernance.class, anns);
            if (g != null) {
                govList.add(g);
            }
            if (lineage == null) {
                lineage = ResolverUtils.getAnnotation(DomainLineage.class, anns);
            }
        }
        Collections.reverse(govList);
        return new PropertyTarget(propertyType, List.copyOf(govList), lineage);
    }

    private static void addMatchingAccessorMethods(Class<?> type, String propertyName, List<AnnotatedElement> elements) {
        String capitalized = propertyName.isEmpty()
                ? ""
                : Character.toUpperCase(propertyName.charAt(0)) + propertyName.substring(1);
        for (String candidate : List.of(propertyName, "get" + capitalized, "is" + capitalized)) {
            try {
                Method m = type.getDeclaredMethod(candidate);
                if (m.getParameterCount() == 0 && !Modifier.isStatic(m.getModifiers()) && !m.isSynthetic() && m.getReturnType() != void.class) {
                    if (candidate.startsWith("is") && m.getReturnType() != boolean.class && m.getReturnType() != Boolean.class) {
                        continue;
                    }
                    elements.add(m);
                }
            } catch (NoSuchMethodException ignored) {
                // Continue scanning other candidates.
            }
        }
    }

    private static String detectWeakeningAttempt(ObjectNode originGovernance, DomainGovernance localGovernance) {
        String originClass = originGovernance.path("classification").asText("internal");
        int originRank = CLASSIFICATION_RANK.getOrDefault(originClass, 1);

        DomainGovernancePreset preset = localGovernance.preset();
        boolean hasPreset = preset != null && preset != DomainGovernancePreset.NONE;

        if (localGovernance.classification() == DomainClassification.PUBLIC && originRank > 0) {
            return "Local @DomainGovernance declares classification = PUBLIC while @DomainLineage origin classification is '"
                    + originClass + "'";
        }
        if (localGovernance.classification() != DomainClassification.INTERNAL) {
            int localExplicitRank = CLASSIFICATION_RANK.getOrDefault(localGovernance.classification().wireValue(), 1);
            if (localExplicitRank < originRank) {
                return "Local @DomainGovernance downgrades classification to '"
                        + localGovernance.classification().wireValue()
                        + "' below @DomainLineage origin ('" + originClass + "')";
            }
        } else if (hasPreset) {
            int presetRank = CLASSIFICATION_RANK.getOrDefault(preset.classification().wireValue(), 1);
            if (presetRank < originRank) {
                return "Local @DomainGovernance preset '" + preset.name() + "' has lower classification ('"
                        + preset.classification().wireValue() + "') than @DomainLineage origin ('" + originClass + "')";
            }
        }
        if (localGovernance.aiUsage().trainingUse() == AiTrainingUseMode.ALLOW
                && "deny".equals(originGovernance.path("aiUsage").path("trainingUse").asText("deny"))
                && originRank >= 2) {
            return "Local @DomainGovernance enables AI training (trainingUse = ALLOW) on a confidential/restricted @DomainLineage origin";
        }
        if (localGovernance.aiUsage().visibility() == AiVisibilityMode.MASK
                && "deny".equals(originGovernance.path("aiUsage").path("visibility").asText("allow"))) {
            return "Local @DomainGovernance weakens AI visibility from DENY to MASK on @DomainLineage origin";
        }
        if (localGovernance.aiUsage().ruleAuthoring() == AiControlledUseMode.ALLOW
                && !"allow".equals(originGovernance.path("aiUsage").path("ruleAuthoring").asText("review_required"))) {
            return "Local @DomainGovernance weakens AI ruleAuthoring to ALLOW on @DomainLineage origin";
        }
        return null;
    }

    private static String synthesizeAttributeId(
            String explicitAttributeId,
            String sourceResourceKey,
            Class<?> sourceClass,
            List<String> sourcePaths
    ) {
        if (!explicitAttributeId.isEmpty()) {
            return explicitAttributeId;
        }
        if (sourcePaths.size() == 1) {
            String prefix = !sourceResourceKey.isEmpty()
                    ? sourceResourceKey
                    : (sourceClass != null && sourceClass != Void.class ? toKebabCase(sourceClass.getSimpleName()) : "");
            if (!prefix.isEmpty()) {
                return prefix + "." + sourcePaths.getFirst();
            }
        }
        return "";
    }

    private static String toKebabCase(String simpleName) {
        if (simpleName == null || simpleName.isBlank()) {
            return "";
        }
        return simpleName.trim()
                .replaceAll("([a-z0-9])([A-Z])", "$1-$2")
                .toLowerCase(Locale.ROOT);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
