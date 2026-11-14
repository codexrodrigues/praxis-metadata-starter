package org.praxisplatform.uischema.surface;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.beans.Introspector;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.annotation.DomainClassification;
import org.praxisplatform.uischema.annotation.DomainDataCategory;
import org.praxisplatform.uischema.annotation.DomainGovernance;
import org.praxisplatform.uischema.annotation.DomainLineage;
import org.praxisplatform.uischema.annotation.MicroVisualization;
import org.praxisplatform.uischema.annotation.ResourceIdentity;
import org.praxisplatform.uischema.annotation.ResourceReference;
import org.praxisplatform.uischema.extension.annotation.UISchema;
import org.praxisplatform.uischema.schema.DomainLineageResolver;
import org.praxisplatform.uischema.schema.DomainSchemaGovernance;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

/**
 * Projects a cached, metadata-driven summary map for contextual item Quick View reads.
 *
 * <p>Selection follows the canonical {@code @ResourceIdentity}, {@code @UISchema(quickViewVisible)},
 * {@code @ResourceReference(valueField)}, and {@code @MicroVisualization} contract while enforcing
 * {@code @UISchema(visibleForAuthorities)} fail-closed and delegating host-specific masking or
 * privacy rules through {@link ContextualSurfaceFieldSanitizer}.
 * </p>
 */
public final class ContextualSurfaceSummaryProjector {

    private static final int FALLBACK_SUMMARY_LIMIT = 8;
    private static final Pattern EXPRESSION_IDENTIFIER_PATTERN =
            Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");

    private static final ConcurrentHashMap<PlanKey, ProjectionPlan> PLAN_CACHE =
            new ConcurrentHashMap<>();

    private ContextualSurfaceSummaryProjector() {
    }

    public enum FieldRole {
        IDENTITY_KEY,
        IDENTITY_HEADER,
        IDENTITY_METADATA,
        SUMMARY_ITEM,
        COMPANION_VALUE
    }

    public record FieldDescriptor(
            String name,
            Class<?> valueType,
            UISchema uiSchema,
            ResourceReference resourceReference,
            DomainGovernance domainGovernance,
            DomainLineage domainLineage,
            ObjectNode effectiveGovernance,
            Set<FieldRole> roles
    ) {
        public FieldDescriptor {
            roles = roles == null || roles.isEmpty()
                    ? Set.of()
                    : Collections.unmodifiableSet(EnumSet.copyOf(roles));
        }

        public boolean isIdentityHeader() {
            return roles.contains(FieldRole.IDENTITY_KEY) || roles.contains(FieldRole.IDENTITY_HEADER);
        }

        public boolean isIdentityMetadata() {
            return roles.contains(FieldRole.IDENTITY_METADATA);
        }

        public boolean hasResourceReference() {
            return resourceReference != null;
        }

        public String effectiveDataCategory() {
            if (effectiveGovernance == null) {
                return null;
            }
            JsonNode node = effectiveGovernance.path("dataCategory");
            if (node == null || node.isMissingNode() || node.isNull()) {
                return null;
            }
            String text = node.asText(null);
            return StringUtils.hasText(text) ? text.trim() : null;
        }

        public boolean hasDataCategory(DomainDataCategory category) {
            if (category == null) {
                return false;
            }
            String current = effectiveDataCategory();
            return current != null && (category.wireValue().equalsIgnoreCase(current)
                    || category.name().equalsIgnoreCase(current));
        }

        public String effectiveClassification() {
            if (effectiveGovernance == null) {
                return null;
            }
            JsonNode node = effectiveGovernance.path("classification");
            if (node == null || node.isMissingNode() || node.isNull()) {
                return null;
            }
            String text = node.asText(null);
            return StringUtils.hasText(text) ? text.trim() : null;
        }

        public boolean hasClassification(DomainClassification classification) {
            if (classification == null) {
                return false;
            }
            String current = effectiveClassification();
            return current != null && (classification.wireValue().equalsIgnoreCase(current)
                    || classification.name().equalsIgnoreCase(current));
        }

        public String effectiveAttributeId() {
            return domainLineage != null && StringUtils.hasText(domainLineage.attributeId())
                    ? domainLineage.attributeId().trim()
                    : null;
        }
    }

    public sealed interface FieldDecision permits FieldDecision.Keep, FieldDecision.Omit {
        record Keep(Object value) implements FieldDecision {
        }

        record Omit() implements FieldDecision {
        }

        static FieldDecision keep(Object value) {
            return new Keep(value);
        }

        static FieldDecision omit() {
            return new Omit();
        }
    }

    @FunctionalInterface
    public interface ContextualSurfaceFieldSanitizer {
        FieldDecision sanitize(FieldDescriptor field, Object rawValue, Set<String> principalAuthorities);
    }

    public static ProjectionPlan planFor(
            Class<?> controllerClass,
            Class<?> dtoClass,
            String fallbackIdField
    ) {
        Objects.requireNonNull(dtoClass, "dtoClass must not be null");
        Class<?> userControllerClass = controllerClass != null ? ClassUtils.getUserClass(controllerClass) : null;
        Class<?> userDtoClass = ClassUtils.getUserClass(dtoClass);
        String normalizedFallbackId = StringUtils.hasText(fallbackIdField) ? fallbackIdField.trim() : "";
        return PLAN_CACHE.computeIfAbsent(
                new PlanKey(userControllerClass, userDtoClass, normalizedFallbackId),
                ContextualSurfaceSummaryProjector::buildPlan
        );
    }

    public static final class ProjectionPlan {
        private final List<PlannedField> fields;

        private ProjectionPlan(List<PlannedField> fields) {
            this.fields = List.copyOf(fields);
        }

        public List<FieldDescriptor> fields() {
            return fields.stream().map(PlannedField::descriptor).toList();
        }

        public Map<String, Object> project(
                Object dto,
                Set<String> principalAuthorities,
                ContextualSurfaceFieldSanitizer sanitizer
        ) {
            if (dto == null) {
                return Map.of();
            }
            Set<String> authorities = principalAuthorities == null ? Set.of() : principalAuthorities;
            Map<String, Object> projected = new LinkedHashMap<>();
            for (PlannedField planned : fields) {
                FieldDescriptor descriptor = planned.descriptor();
                if (!isVisibleForAuthorities(descriptor.uiSchema(), authorities)) {
                    continue;
                }
                Object rawValue = planned.readValue(dto);
                if (rawValue == null) {
                    continue;
                }
                if (sanitizer != null) {
                    FieldDecision decision = sanitizer.sanitize(descriptor, rawValue, authorities);
                    if (decision instanceof FieldDecision.Keep(Object sanitizedValue) && sanitizedValue != null) {
                        projected.put(descriptor.name(), sanitizedValue);
                    }
                } else {
                    projected.put(descriptor.name(), rawValue);
                }
            }
            return Collections.unmodifiableMap(projected);
        }

        private static boolean isVisibleForAuthorities(UISchema uiSchema, Set<String> authorities) {
            if (uiSchema == null || uiSchema.visibleForAuthorities().length == 0) {
                return true;
            }
            if (authorities.isEmpty()) {
                return false;
            }
            return !Collections.disjoint(Arrays.asList(uiSchema.visibleForAuthorities()), authorities);
        }
    }

    private record PlanKey(Class<?> controllerClass, Class<?> dtoClass, String fallbackIdField) {
    }

    private record PlannedField(
            FieldDescriptor descriptor,
            Field field,
            Method getter
    ) {
        private Object readValue(Object target) {
            try {
                if (getter != null) {
                    return getter.invoke(target);
                }
                if (field != null) {
                    return field.get(target);
                }
                return null;
            } catch (IllegalAccessException | InvocationTargetException ex) {
                throw new IllegalStateException(
                        "Unable to read contextual summary field '" + descriptor.name() + "' from "
                                + target.getClass().getName(),
                        ex
                );
            }
        }
    }

    private static ProjectionPlan buildPlan(PlanKey key) {
        Class<?> dtoClass = key.dtoClass();
        ObjectNode ownerGovernance = DomainLineageResolver.resolveTypeGovernance(dtoClass);
        List<PropertyCandidate> properties = introspectProperties(dtoClass);

        Map<String, PropertyCandidate> byWireName = new LinkedHashMap<>();
        Map<String, PropertyCandidate> byJavaName = new LinkedHashMap<>();
        Map<String, PropertyCandidate> byLowerName = new LinkedHashMap<>();
        for (PropertyCandidate candidate : properties) {
            if (candidate.ignored || (!candidate.hasReadableAccessor())) {
                continue;
            }
            byWireName.put(candidate.wireName, candidate);
            byJavaName.put(candidate.javaName, candidate);
            byLowerName.putIfAbsent(candidate.wireName.toLowerCase(Locale.ROOT), candidate);
            byLowerName.putIfAbsent(candidate.javaName.toLowerCase(Locale.ROOT), candidate);
        }

        Map<PropertyCandidate, EnumSet<FieldRole>> selectedRoles = new LinkedHashMap<>();
        ResourceIdentity identity = resolveResourceIdentity(key.controllerClass(), dtoClass);

        String keyField = identity != null && StringUtils.hasText(identity.keyField())
                ? identity.keyField().trim()
                : (StringUtils.hasText(key.fallbackIdField()) ? key.fallbackIdField() : "id");
        addRole(findCandidate(keyField, byWireName, byJavaName, byLowerName), FieldRole.IDENTITY_KEY, selectedRoles);

        if (identity != null) {
            addRole(findCandidate(identity.titleField(), byWireName, byJavaName, byLowerName),
                    FieldRole.IDENTITY_HEADER, selectedRoles);
            addRole(findCandidate(identity.displayLabelField(), byWireName, byJavaName, byLowerName),
                    FieldRole.IDENTITY_HEADER, selectedRoles);
            addRole(findCandidate(identity.subtitleField(), byWireName, byJavaName, byLowerName),
                    FieldRole.IDENTITY_HEADER, selectedRoles);
            addRole(findCandidate(identity.avatarField(), byWireName, byJavaName, byLowerName),
                    FieldRole.IDENTITY_HEADER, selectedRoles);
            for (String metadataField : identity.metadataFields()) {
                addRole(findCandidate(metadataField, byWireName, byJavaName, byLowerName),
                        FieldRole.IDENTITY_METADATA, selectedRoles);
            }
        }

        List<PropertyCandidate> explicitQuickView = properties.stream()
                .filter(c -> !c.ignored && c.hasReadableAccessor() && c.uiSchema != null && c.uiSchema.quickViewVisible())
                .sorted(Comparator
                        .comparingInt((PropertyCandidate c) -> c.uiSchema.quickViewOrder() > 0
                                ? c.uiSchema.quickViewOrder()
                                : Integer.MAX_VALUE)
                        .thenComparingInt(c -> c.uiSchema.order() > 0 ? c.uiSchema.order() : Integer.MAX_VALUE)
                        .thenComparingInt(c -> c.declarationIndex))
                .toList();

        if (!explicitQuickView.isEmpty()) {
            for (PropertyCandidate candidate : explicitQuickView) {
                addRole(candidate, FieldRole.SUMMARY_ITEM, selectedRoles);
            }
        } else {
            properties.stream()
                    .filter(c -> !c.ignored
                            && c.hasReadableAccessor()
                            && c.uiSchema != null
                            && !c.uiSchema.tableHidden()
                            && !selectedRoles.containsKey(c))
                    .sorted(Comparator
                            .comparingInt((PropertyCandidate c) -> c.uiSchema.order() > 0
                                    ? c.uiSchema.order()
                                    : Integer.MAX_VALUE)
                            .thenComparingInt(c -> c.declarationIndex))
                    .limit(FALLBACK_SUMMARY_LIMIT)
                    .forEach(candidate -> addRole(candidate, FieldRole.SUMMARY_ITEM, selectedRoles));
        }

        List<PropertyCandidate> primarySnapshot = new ArrayList<>(selectedRoles.keySet());
        for (PropertyCandidate candidate : primarySnapshot) {
            if (candidate.resourceReference != null && StringUtils.hasText(candidate.resourceReference.valueField())) {
                addRole(
                        findCandidate(candidate.resourceReference.valueField(), byWireName, byJavaName, byLowerName),
                        FieldRole.COMPANION_VALUE,
                        selectedRoles
                );
            }
            if (candidate.microVisualization != null) {
                for (String token : extractMicroVisualizationIdentifiers(candidate.microVisualization)) {
                    PropertyCandidate referenced = byWireName.get(token);
                    if (referenced == null) {
                        referenced = byJavaName.get(token);
                    }
                    addRole(referenced, FieldRole.COMPANION_VALUE, selectedRoles);
                }
            }
        }

        List<PlannedField> plannedFields = new ArrayList<>(selectedRoles.size());
        for (Map.Entry<PropertyCandidate, EnumSet<FieldRole>> entry : selectedRoles.entrySet()) {
            PropertyCandidate candidate = entry.getKey();
            candidate.prepareAccessors();
            ObjectNode effectiveGovernance = resolveEffectiveGovernance(
                    candidate.domainLineage,
                    candidate.domainGovernance,
                    ownerGovernance
            );
            FieldDescriptor descriptor = new FieldDescriptor(
                    candidate.wireName,
                    candidate.valueType(),
                    candidate.uiSchema,
                    candidate.resourceReference,
                    candidate.domainGovernance,
                    candidate.domainLineage,
                    effectiveGovernance,
                    entry.getValue()
            );
            plannedFields.add(new PlannedField(descriptor, candidate.field, candidate.getter));
        }

        return new ProjectionPlan(plannedFields);
    }

    private static void addRole(
            PropertyCandidate candidate,
            FieldRole role,
            Map<PropertyCandidate, EnumSet<FieldRole>> selectedRoles
    ) {
        if (candidate == null) {
            return;
        }
        selectedRoles.computeIfAbsent(candidate, ignored -> EnumSet.noneOf(FieldRole.class)).add(role);
    }

    private static PropertyCandidate findCandidate(
            String rawName,
            Map<String, PropertyCandidate> byWireName,
            Map<String, PropertyCandidate> byJavaName,
            Map<String, PropertyCandidate> byLowerName
    ) {
        if (!StringUtils.hasText(rawName)) {
            return null;
        }
        String name = rawName.trim();
        PropertyCandidate exact = byWireName.get(name);
        if (exact != null) {
            return exact;
        }
        exact = byJavaName.get(name);
        if (exact != null) {
            return exact;
        }
        return byLowerName.get(name.toLowerCase(Locale.ROOT));
    }

    private static ResourceIdentity resolveResourceIdentity(Class<?> controllerClass, Class<?> dtoClass) {
        ResourceIdentity onDto = AnnotatedElementUtils.findMergedAnnotation(dtoClass, ResourceIdentity.class);
        if (onDto != null && hasAnyIdentityField(onDto)) {
            return onDto;
        }
        if (controllerClass != null) {
            ApiResource apiResource = AnnotatedElementUtils.findMergedAnnotation(controllerClass, ApiResource.class);
            if (apiResource != null && hasAnyIdentityField(apiResource.identity())) {
                return apiResource.identity();
            }
        }
        return onDto;
    }

    private static boolean hasAnyIdentityField(ResourceIdentity identity) {
        return identity != null && (StringUtils.hasText(identity.keyField())
                || StringUtils.hasText(identity.titleField())
                || StringUtils.hasText(identity.subtitleField())
                || StringUtils.hasText(identity.displayLabelField())
                || StringUtils.hasText(identity.avatarField())
                || identity.metadataFields().length > 0);
    }

    private static ObjectNode resolveEffectiveGovernance(
            DomainLineage domainLineage,
            DomainGovernance domainGovernance,
            ObjectNode ownerGovernance
    ) {
        ObjectNode memberNode = DomainLineageResolver.toGovernanceNode(domainGovernance);
        ObjectNode fallback = memberNode != null
                ? DomainSchemaGovernance.mergeOrCopy(ownerGovernance, memberNode)
                : (ownerGovernance != null ? ownerGovernance.deepCopy() : null);
        if (domainLineage == null) {
            return fallback;
        }
        try {
            DomainLineageResolver.ResolvedLineage resolved =
                    DomainLineageResolver.resolve(domainLineage, domainGovernance, ownerGovernance);
            if (resolved != null && resolved.effectiveGovernance() != null) {
                return resolved.effectiveGovernance().deepCopy();
            }
        } catch (RuntimeException ignored) {
            // Partial stubs or unavailable external lineage sources retain direct governance.
        }
        return fallback;
    }

    private static Set<String> extractMicroVisualizationIdentifiers(MicroVisualization mv) {
        Set<String> tokens = new LinkedHashSet<>();
        collectIdentifiers(mv.valueExpr(), tokens);
        collectIdentifiers(mv.targetExpr(), tokens);
        collectIdentifiers(mv.totalExpr(), tokens);
        collectIdentifiers(mv.baselineExpr(), tokens);
        collectIdentifiers(mv.minExpr(), tokens);
        collectIdentifiers(mv.maxExpr(), tokens);
        collectIdentifiers(mv.valueSuffixExpr(), tokens);
        collectIdentifiers(mv.pointsExpr(), tokens);
        collectIdentifiers(mv.segmentsExpr(), tokens);
        collectIdentifiers(mv.itemsExpr(), tokens);
        collectIdentifiers(mv.toneExpr(), tokens);
        collectIdentifiers(mv.thresholdsExpr(), tokens);
        collectIdentifiers(mv.ariaLabelExpr(), tokens);
        collectIdentifiers(mv.fallbackTextExpr(), tokens);
        collectIdentifiers(mv.titleExpr(), tokens);
        collectIdentifiers(mv.targetLabelExpr(), tokens);
        return tokens;
    }

    private static void collectIdentifiers(String expr, Set<String> sink) {
        if (!StringUtils.hasText(expr)) {
            return;
        }
        Matcher matcher = EXPRESSION_IDENTIFIER_PATTERN.matcher(expr);
        while (matcher.find()) {
            sink.add(matcher.group());
        }
    }

    private static List<PropertyCandidate> introspectProperties(Class<?> dtoClass) {
        List<Class<?>> hierarchy = new ArrayList<>();
        for (Class<?> current = dtoClass; current != null && current != Object.class; current = current.getSuperclass()) {
            hierarchy.add(current);
        }
        Collections.reverse(hierarchy);

        List<PropertyCandidate> ordered = new ArrayList<>();
        Map<String, PropertyCandidate> byLowerKey = new LinkedHashMap<>();

        if (dtoClass.isRecord() && dtoClass.getRecordComponents() != null) {
            for (RecordComponent component : dtoClass.getRecordComponents()) {
                PropertyCandidate candidate = findOrCreate(component.getName(), ordered, byLowerKey);
                candidate.recordComponent = component;
                candidate.getter = component.getAccessor();
                candidate.mergeFromElement(component);
                if (component.getAccessor() != null) {
                    candidate.mergeFromElement(component.getAccessor());
                }
            }
        }

        for (Class<?> clazz : hierarchy) {
            for (Field field : clazz.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                    continue;
                }
                PropertyCandidate candidate = findOrCreate(field.getName(), ordered, byLowerKey);
                candidate.field = field;
                candidate.mergeFromElement(field);
            }
            for (Method method : clazz.getDeclaredMethods()) {
                if (!isPropertyGetter(method)) {
                    continue;
                }
                String derivedName = deriveGetterPropertyName(method);
                JsonProperty jp = findDirectOrMetaAnnotation(method, JsonProperty.class);
                String explicitName = jp != null && StringUtils.hasText(jp.value())
                        ? jp.value().trim()
                        : null;
                PropertyCandidate candidate = explicitName != null
                        ? byLowerKey.get(explicitName.toLowerCase(Locale.ROOT))
                        : null;
                if (candidate == null) {
                    candidate = byLowerKey.get(derivedName.toLowerCase(Locale.ROOT));
                }
                if (candidate == null) {
                    candidate = findOrCreate(derivedName, ordered, byLowerKey);
                }
                candidate.getter = method;
                candidate.mergeFromElement(method);
                if (explicitName != null) {
                    byLowerKey.putIfAbsent(explicitName.toLowerCase(Locale.ROOT), candidate);
                }
            }
        }
        return ordered;
    }

    private static PropertyCandidate findOrCreate(
            String javaName,
            List<PropertyCandidate> ordered,
            Map<String, PropertyCandidate> byLowerKey
    ) {
        String lower = javaName.toLowerCase(Locale.ROOT);
        PropertyCandidate existing = byLowerKey.get(lower);
        if (existing != null) {
            return existing;
        }
        PropertyCandidate created = new PropertyCandidate(javaName, ordered.size());
        ordered.add(created);
        byLowerKey.put(lower, created);
        return created;
    }

    private static boolean isPropertyGetter(Method method) {
        if (method.isSynthetic() || method.isBridge()) {
            return false;
        }
        int modifiers = method.getModifiers();
        if (!Modifier.isPublic(modifiers) || Modifier.isStatic(modifiers)) {
            return false;
        }
        if (method.getParameterCount() != 0 || method.getReturnType() == Void.TYPE) {
            return false;
        }
        String name = method.getName();
        if ("getClass".equals(name)) {
            return false;
        }
        if (name.startsWith("get") && name.length() > 3) {
            return true;
        }
        if (name.startsWith("is") && name.length() > 2) {
            Class<?> returnType = method.getReturnType();
            return returnType == boolean.class || returnType == Boolean.class;
        }
        return false;
    }

    private static String deriveGetterPropertyName(Method method) {
        String name = method.getName();
        if (name.startsWith("get") && name.length() > 3) {
            return Introspector.decapitalize(name.substring(3));
        }
        if (name.startsWith("is") && name.length() > 2) {
            return Introspector.decapitalize(name.substring(2));
        }
        return name;
    }

    private static <A extends Annotation> A findDirectOrMetaAnnotation(
            AnnotatedElement element,
            Class<A> annotationType
    ) {
        return AnnotatedElementUtils.findMergedAnnotation(element, annotationType);
    }

    private static final class PropertyCandidate {
        private final String javaName;
        private final int declarationIndex;
        private String wireName;
        private RecordComponent recordComponent;
        private Field field;
        private Method getter;
        private boolean ignored;
        private UISchema uiSchema;
        private ResourceReference resourceReference;
        private MicroVisualization microVisualization;
        private DomainGovernance domainGovernance;
        private DomainLineage domainLineage;

        private PropertyCandidate(String javaName, int declarationIndex) {
            this.javaName = javaName;
            this.wireName = javaName;
            this.declarationIndex = declarationIndex;
        }

        private void mergeFromElement(AnnotatedElement element) {
            JsonIgnore jsonIgnore = findDirectOrMetaAnnotation(element, JsonIgnore.class);
            if (jsonIgnore != null) {
                ignored = jsonIgnore.value();
            }
            JsonProperty jsonProperty = findDirectOrMetaAnnotation(element, JsonProperty.class);
            if (jsonProperty != null && StringUtils.hasText(jsonProperty.value())) {
                wireName = jsonProperty.value().trim();
            }
            UISchema ui = findDirectOrMetaAnnotation(element, UISchema.class);
            if (ui != null) {
                uiSchema = ui;
            }
            ResourceReference ref = findDirectOrMetaAnnotation(element, ResourceReference.class);
            if (ref != null) {
                resourceReference = ref;
            }
            MicroVisualization mv = findDirectOrMetaAnnotation(element, MicroVisualization.class);
            if (mv != null) {
                microVisualization = mv;
            }
            DomainGovernance gov = findDirectOrMetaAnnotation(element, DomainGovernance.class);
            if (gov != null) {
                domainGovernance = gov;
            }
            DomainLineage lineage = findDirectOrMetaAnnotation(element, DomainLineage.class);
            if (lineage != null) {
                domainLineage = lineage;
            }
        }

        private boolean hasReadableAccessor() {
            return getter != null || field != null;
        }

        private Class<?> valueType() {
            if (getter != null) {
                return getter.getReturnType();
            }
            if (field != null) {
                return field.getType();
            }
            return recordComponent != null ? recordComponent.getType() : Object.class;
        }

        private void prepareAccessors() {
            if (getter != null) {
                getter.trySetAccessible();
            }
            if (field != null) {
                field.trySetAccessible();
            }
        }
    }
}
