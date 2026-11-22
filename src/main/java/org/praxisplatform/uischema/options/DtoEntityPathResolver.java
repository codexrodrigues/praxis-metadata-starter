package org.praxisplatform.uischema.options;

import jakarta.persistence.AssociationOverride;
import jakarta.persistence.AssociationOverrides;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Embedded;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Transient;
import org.praxisplatform.uischema.annotation.DomainLineage;
import org.praxisplatform.uischema.annotation.DomainLineageTransformation;
import org.praxisplatform.uischema.annotation.ResourceIdentity;
import org.praxisplatform.uischema.annotation.ResourceReference;
import org.praxisplatform.uischema.extension.annotation.UISchema;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ClassUtils;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Deterministic, fail-closed resolver mapping {@code ResponseDTO} fields (including dotted
 * sub-DTO paths, {@link DomainLineage}, and {@link ResourceReference}) to singular JPA entity
 * property paths.
 */
public final class DtoEntityPathResolver {

    private static final int MAX_PATH_DEPTH = 8;

    private DtoEntityPathResolver() {
    }

    record DtoPropertyMatch(
            String normalizedPath,
            Class<?> propertyType,
            List<AnnotatedElement> elements
    ) {
        <A extends Annotation> A findAnnotation(Class<A> annotationType) {
            for (AnnotatedElement element : elements) {
                A merged = AnnotatedElementUtils.findMergedAnnotation(element, annotationType);
                if (merged != null) {
                    return merged;
                }
            }
            return null;
        }

        UISchema findUiSchema() {
            return findAnnotation(UISchema.class);
        }
    }

    private record EntityMember(
            String name,
            Class<?> type,
            List<AnnotatedElement> elements
    ) {
        <A extends Annotation> A findAnnotation(Class<A> annotationType) {
            for (AnnotatedElement element : elements) {
                A merged = AnnotatedElementUtils.findMergedAnnotation(element, annotationType);
                if (merged != null) {
                    return merged;
                }
            }
            return null;
        }

        boolean isTransientOrCollection() {
            for (AnnotatedElement element : elements) {
                if (element.isAnnotationPresent(Transient.class)
                        || element.isAnnotationPresent(OneToMany.class)
                        || element.isAnnotationPresent(ManyToMany.class)
                        || element.isAnnotationPresent(ElementCollection.class)) {
                    return true;
                }
                if (element instanceof Field field && Modifier.isTransient(field.getModifiers())) {
                    return true;
                }
            }
            return type == null
                    || type.isArray()
                    || Collection.class.isAssignableFrom(type)
                    || Map.class.isAssignableFrom(type);
        }

        boolean isSingularAssociation() {
            if (isTransientOrCollection()) {
                return false;
            }
            ManyToOne manyToOne = findAnnotation(ManyToOne.class);
            if (manyToOne != null) {
                return true;
            }
            OneToOne oneToOne = findAnnotation(OneToOne.class);
            return oneToOne != null && (oneToOne.mappedBy() == null || oneToOne.mappedBy().isBlank());
        }

        boolean isEmbedded() {
            if (isTransientOrCollection()) {
                return false;
            }
            return findAnnotation(Embedded.class) != null
                    || findAnnotation(EmbeddedId.class) != null
                    || (type != null && type.isAnnotationPresent(Embeddable.class));
        }
    }

    private record SingularAssociationCandidate(
            String associationPath,
            String name,
            Class<?> targetType,
            List<String> joinColumnNamesLower,
            String mapsIdValue
    ) {
    }

    static DtoPropertyMatch resolveDtoProperty(Class<?> dtoClass, String dottedPath) {
        Objects.requireNonNull(dtoClass, "dtoClass is required");
        String normalizedPath = normalize(dottedPath);
        if (normalizedPath == null) {
            throw new IllegalArgumentException("propertyPath is required");
        }
        String[] segments = normalizedPath.split("\\.", -1);
        if (segments.length > MAX_PATH_DEPTH) {
            throw new IllegalArgumentException(
                    "Property '" + normalizedPath + "' on DTO class " + dtoClass.getName()
                            + " exceeds maximum path depth (" + MAX_PATH_DEPTH + ")");
        }
        Class<?> currentType = dtoClass;
        DtoPropertyMatch match = null;
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            if (segment == null || segment.isBlank() || !isTraversableContainer(currentType)) {
                throw new IllegalArgumentException(
                        "Property '" + normalizedPath + "' not found on DTO class " + dtoClass.getName());
            }
            match = findDirectDtoProperty(currentType, segment, normalizedPath);
            if (match == null) {
                throw new IllegalArgumentException(
                        "Property '" + normalizedPath + "' not found on DTO class " + dtoClass.getName());
            }
            currentType = match.propertyType();
        }
        return match;
    }

    static boolean hasDtoProperty(Class<?> dtoClass, String dottedPath) {
        if (dtoClass == null || dottedPath == null || dottedPath.isBlank()) {
            return false;
        }
        try {
            resolveDtoProperty(dtoClass, dottedPath);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    /**
     * Resolves a DTO property path to a persistent singular JPA property path on {@code entityClass}.
     * Fails closed with {@link IllegalArgumentException} on derived/aggregated/multi-source lineage,
     * missing/transient/plural entity paths, or ambiguous FK associations.
     */
    public static String resolveEntityPropertyPath(
            Class<?> dtoClass,
            Class<?> entityClass,
            String dtoFieldPath,
            String entityKey,
            String identityKeyField
    ) {
        Objects.requireNonNull(dtoClass, "dtoClass is required");
        Objects.requireNonNull(entityClass, "entityClass is required");
        DtoPropertyMatch dtoMatch = resolveDtoProperty(dtoClass, dtoFieldPath);
        String normalizedDtoPath = dtoMatch.normalizedPath();

        String effectiveIdentityKeyField = normalize(identityKeyField);
        if (effectiveIdentityKeyField == null) {
            ResourceIdentity identity = findResourceIdentity(dtoClass);
            if (identity != null) {
                effectiveIdentityKeyField = normalize(identity.keyField());
            }
        }
        String normalizedEntityKey = normalize(entityKey);

        DomainLineage lineage = dtoMatch.findAnnotation(DomainLineage.class);
        ResourceReference ref = dtoMatch.findAnnotation(ResourceReference.class);
        String refValueField = ref != null ? normalize(ref.valueField()) : null;
        String refResourceKey = ref != null ? normalize(ref.resourceKey()) : null;
        boolean selfKeyRef = refValueField == null || refValueField.equals(effectiveIdentityKeyField);

        if (lineage != null) {
            DomainLineageTransformation transformation = lineage.transformation() == null
                    ? DomainLineageTransformation.DIRECT
                    : lineage.transformation();
            if (transformation == DomainLineageTransformation.DERIVED
                    || transformation == DomainLineageTransformation.AGGREGATED
                    || transformation == DomainLineageTransformation.EXTERNAL_PROJECTION
                    || transformation == DomainLineageTransformation.FILTER_PREDICATE) {
                throw new IllegalArgumentException(
                        "Cannot resolve JPA entity path for DTO field '" + dtoClass.getName() + "."
                                + normalizedDtoPath + "' with non-projectable @DomainLineage transformation "
                                + transformation + ".");
            }

            String[] rawPaths = lineage.sourcePaths() == null ? new String[0] : lineage.sourcePaths();
            if (rawPaths.length != 1 || rawPaths[0] == null || rawPaths[0].isBlank()) {
                throw new IllegalArgumentException(
                        "Cannot resolve JPA entity path for DTO field '" + dtoClass.getName() + "."
                                + normalizedDtoPath + "': @DomainLineage must declare exactly 1 non-blank sourcePath (found "
                                + rawPaths.length + ").");
            }
            String sourcePath = rawPaths[0].trim();
            Class<?> sourceClass = lineage.sourceClass() == null ? Void.class : lineage.sourceClass();
            String sourceResourceKey = normalize(lineage.sourceResourceKey());

            if (sourceClass != Void.class && sourceClass.isAssignableFrom(entityClass)) {
                if (!selfKeyRef) {
                    if (refResourceKey != null && normalizedEntityKey != null && !refResourceKey.equals(normalizedEntityKey)) {
                        throw new IllegalArgumentException(
                                "Cannot resolve JPA entity path for DTO field '" + dtoClass.getName() + "."
                                        + normalizedDtoPath + "': @DomainLineage.sourceClass targets root entity "
                                        + entityClass.getSimpleName() + " while @ResourceReference.resourceKey targets '"
                                        + refResourceKey + "'.");
                    }
                    return resolveRelatedFkPath(dtoClass, entityClass, normalizedDtoPath, sourcePath, sourceClass, refValueField);
                }
                assertValidSingularEntityPath(entityClass, sourcePath, normalizedDtoPath);
                return sourcePath;
            }

            if (sourceClass != Void.class) {
                return resolveRelatedFkPath(dtoClass, entityClass, normalizedDtoPath, sourcePath, sourceClass, refValueField);
            }

            if (!selfKeyRef) {
                return resolveRelatedFkPath(dtoClass, entityClass, normalizedDtoPath, sourcePath, null, refValueField);
            }
            if (sourceResourceKey != null && normalizedEntityKey != null && !sourceResourceKey.equals(normalizedEntityKey)) {
                throw new IllegalArgumentException(
                        "Cannot resolve JPA entity path for DTO field '" + dtoClass.getName() + "."
                                + normalizedDtoPath + "': @DomainLineage declares foreign sourceResourceKey '"
                                + sourceResourceKey + "' without sourceClass or @ResourceReference(valueField).");
            }
            assertValidSingularEntityPath(entityClass, sourcePath, normalizedDtoPath);
            return sourcePath;
        }

        if (ref != null && refValueField != null && !selfKeyRef) {
            throw new IllegalArgumentException(
                    "DTO field '" + dtoClass.getName() + "." + normalizedDtoPath
                            + "' declares @ResourceReference(valueField = '" + refValueField
                            + "') without @DomainLineage(sourcePaths = {...}).");
        }

        assertValidSingularEntityPath(entityClass, normalizedDtoPath, normalizedDtoPath);
        return normalizedDtoPath;
    }

    public static boolean isValidSingularEntityPath(Class<?> entityClass, String entityPath) {
        if (entityClass == null || entityPath == null || entityPath.isBlank()) {
            return false;
        }
        try {
            assertValidSingularEntityPath(entityClass, entityPath, entityPath);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    public static void assertValidSingularEntityPath(Class<?> entityClass, String entityPath, String contextLabel) {
        Objects.requireNonNull(entityClass, "entityClass is required");
        String normalizedPath = normalize(entityPath);
        if (normalizedPath == null) {
            throw new IllegalArgumentException(
                    "Entity property path is blank for '" + contextLabel + "' on " + entityClass.getName());
        }
        String[] segments = normalizedPath.split("\\.", -1);
        if (segments.length > MAX_PATH_DEPTH) {
            throw new IllegalArgumentException(
                    "Entity property path '" + normalizedPath + "' on " + entityClass.getName()
                            + " exceeds maximum depth (" + MAX_PATH_DEPTH + ")");
        }
        Class<?> currentType = entityClass;
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            if (segment == null || segment.isBlank() || !isTraversableContainer(currentType)) {
                throw new IllegalArgumentException(
                        "Invalid entity property path '" + normalizedPath + "' (for DTO field '" + contextLabel
                                + "') on " + entityClass.getName());
            }
            EntityMember member = findEntityMember(currentType, segment);
            if (member == null || member.isTransientOrCollection()) {
                throw new IllegalArgumentException(
                        "Entity property path '" + normalizedPath + "' (for DTO field '" + contextLabel
                                + "') is missing, @Transient, or traverses a collection on " + entityClass.getName());
            }
            if (i < segments.length - 1 && !member.isSingularAssociation() && !member.isEmbedded()) {
                if (isScalarValueType(member.type())) {
                    throw new IllegalArgumentException(
                            "Entity property path '" + normalizedPath + "' (for DTO field '" + contextLabel
                                    + "') cannot traverse scalar segment '" + segment + "' on " + entityClass.getName());
                }
            }
            currentType = member.type();
        }
    }

    private static String resolveRelatedFkPath(
            Class<?> dtoClass,
            Class<?> entityClass,
            String dtoFieldPath,
            String targetSourcePath,
            Class<?> targetTypeFilter,
            String refValueField
    ) {
        List<SingularAssociationCandidate> allAssociations = collectSingularAssociations(entityClass);
        List<SingularAssociationCandidate> candidates = new ArrayList<>();
        for (SingularAssociationCandidate candidate : allAssociations) {
            if (targetTypeFilter != null
                    && !targetTypeFilter.isAssignableFrom(candidate.targetType())
                    && !candidate.targetType().isAssignableFrom(targetTypeFilter)) {
                continue;
            }
            if (!isValidSingularEntityPath(candidate.targetType(), targetSourcePath)) {
                continue;
            }
            candidates.add(candidate);
        }

        if (refValueField != null) {
            FkMatchSignals signals = extractFkMatchSignals(dtoClass, entityClass, refValueField);
            List<SingularAssociationCandidate> narrowed = new ArrayList<>();
            for (SingularAssociationCandidate candidate : candidates) {
                if (matchesFkSignals(candidate, signals)) {
                    narrowed.add(candidate);
                }
            }
            candidates = narrowed;
        }

        if (candidates.isEmpty()) {
            throw new IllegalArgumentException(
                    "Unable to resolve @ManyToOne/@OneToOne FK path on " + entityClass.getName()
                            + " for DTO field '" + dtoClass.getName() + "." + dtoFieldPath + "' (sourcePath='"
                            + targetSourcePath + "', targetType="
                            + (targetTypeFilter == null ? "<any>" : targetTypeFilter.getSimpleName())
                            + ", valueField=" + (refValueField == null ? "<none>" : refValueField) + ").");
        }
        if (candidates.size() > 1) {
            List<String> names = candidates.stream().map(SingularAssociationCandidate::associationPath).toList();
            throw new IllegalArgumentException(
                    "Ambiguous FK association on " + entityClass.getName() + " for DTO field '"
                            + dtoClass.getName() + "." + dtoFieldPath + "' (sourcePath='" + targetSourcePath
                            + "'): multiple candidates " + names
                            + ". Disambiguate with @ResourceReference(valueField = ...).");
        }
        return candidates.getFirst().associationPath() + "." + targetSourcePath;
    }

    private record FkMatchSignals(
            String refValueField,
            String fkLineageAssociationPrefix,
            Set<String> expectedColumnNamesLower,
            String fallbackSnakeTokenLower
    ) {
    }

    private static FkMatchSignals extractFkMatchSignals(
            Class<?> dtoClass,
            Class<?> entityClass,
            String refValueField
    ) {
        String fkLineageAssociationPrefix = null;
        Set<String> expectedColumnNamesLower = new LinkedHashSet<>();

        try {
            DtoPropertyMatch refMatch = resolveDtoProperty(dtoClass, refValueField);
            DomainLineage fkLineage = refMatch.findAnnotation(DomainLineage.class);
            if (fkLineage != null
                    && fkLineage.sourcePaths() != null
                    && fkLineage.sourcePaths().length == 1
                    && fkLineage.sourcePaths()[0] != null
                    && !fkLineage.sourcePaths()[0].isBlank()) {
                Class<?> fkSourceClass = fkLineage.sourceClass() == null ? Void.class : fkLineage.sourceClass();
                if (fkSourceClass == Void.class || fkSourceClass.isAssignableFrom(entityClass)) {
                    String fkPath = fkLineage.sourcePaths()[0].trim();
                    int dot = fkPath.indexOf('.');
                    fkLineageAssociationPrefix = dot > 0 ? fkPath.substring(0, dot) : fkPath;
                    String physicalCol = resolveScalarColumnNameLower(entityClass, fkPath);
                    if (physicalCol != null) {
                        expectedColumnNamesLower.add(physicalCol);
                    }
                }
            }
        } catch (IllegalArgumentException ignored) {
            // refValueField may exist on entity only or be absent on DTO
        }

        String entityPhysicalCol = resolveScalarColumnNameLower(entityClass, refValueField);
        if (entityPhysicalCol != null) {
            expectedColumnNamesLower.add(entityPhysicalCol);
        }

        int lastDot = refValueField.lastIndexOf('.');
        String leaf = lastDot >= 0 && lastDot + 1 < refValueField.length()
                ? refValueField.substring(lastDot + 1)
                : refValueField;
        String fallbackSnake = toSnakeCase(leaf).toLowerCase(Locale.ROOT);
        return new FkMatchSignals(refValueField, fkLineageAssociationPrefix, Set.copyOf(expectedColumnNamesLower), fallbackSnake);
    }

    private static boolean matchesFkSignals(SingularAssociationCandidate candidate, FkMatchSignals signals) {
        String ref = signals.refValueField();
        if (ref.equals(candidate.name())
                || ref.startsWith(candidate.name() + ".")
                || ref.equals(candidate.associationPath())
                || ref.startsWith(candidate.associationPath() + ".")) {
            return true;
        }
        if (candidate.mapsIdValue() != null
                && (candidate.mapsIdValue().equals(ref) || ref.endsWith("." + candidate.mapsIdValue()))) {
            return true;
        }
        if (signals.fkLineageAssociationPrefix() != null
                && (signals.fkLineageAssociationPrefix().equals(candidate.name())
                || signals.fkLineageAssociationPrefix().equals(candidate.associationPath()))) {
            return true;
        }
        for (String expectedCol : signals.expectedColumnNamesLower()) {
            if (candidate.joinColumnNamesLower().contains(expectedCol)) {
                return true;
            }
        }
        return !signals.fallbackSnakeTokenLower().isBlank()
                && candidate.joinColumnNamesLower().contains(signals.fallbackSnakeTokenLower());
    }

    private static List<SingularAssociationCandidate> collectSingularAssociations(Class<?> entityClass) {
        List<SingularAssociationCandidate> result = new ArrayList<>();
        Set<String> seenPaths = new LinkedHashSet<>();

        for (EntityMember member : collectEntityMembers(entityClass)) {
            if (member.isSingularAssociation()) {
                String path = member.name();
                if (seenPaths.add(path)) {
                    result.add(toAssociationCandidate(path, member, Map.of()));
                }
            } else if (member.isEmbedded()) {
                Map<String, List<String>> overrides = collectAssociationOverrides(member);
                for (EntityMember embeddedMember : collectEntityMembers(member.type())) {
                    if (embeddedMember.isSingularAssociation()) {
                        String path = member.name() + "." + embeddedMember.name();
                        if (seenPaths.add(path)) {
                            List<String> overriddenCols = overrides.get(embeddedMember.name());
                            result.add(toAssociationCandidate(path, embeddedMember,
                                    overriddenCols == null ? Map.of() : Map.of(embeddedMember.name(), overriddenCols)));
                        }
                    }
                }
            }
        }
        return result;
    }

    private static SingularAssociationCandidate toAssociationCandidate(
            String associationPath,
            EntityMember member,
            Map<String, List<String>> associationOverrides
    ) {
        List<String> joinCols = new ArrayList<>();
        List<String> overridden = associationOverrides.get(member.name());
        if (overridden != null && !overridden.isEmpty()) {
            joinCols.addAll(overridden);
        } else {
            JoinColumn single = member.findAnnotation(JoinColumn.class);
            if (single != null && single.name() != null && !single.name().isBlank()) {
                joinCols.add(single.name().trim().toLowerCase(Locale.ROOT));
            }
            JoinColumns multi = member.findAnnotation(JoinColumns.class);
            if (multi != null && multi.value() != null) {
                for (JoinColumn jc : multi.value()) {
                    if (jc != null && jc.name() != null && !jc.name().isBlank()) {
                        joinCols.add(jc.name().trim().toLowerCase(Locale.ROOT));
                    }
                }
            }
        }
        if (joinCols.isEmpty()) {
            String targetIdName = resolveTargetIdPropertyName(member.type());
            joinCols.add((toSnakeCase(member.name()) + "_" + toSnakeCase(targetIdName)).toLowerCase(Locale.ROOT));
        }
        MapsId mapsId = member.findAnnotation(MapsId.class);
        String mapsIdValue = mapsId != null && mapsId.value() != null && !mapsId.value().isBlank()
                ? mapsId.value().trim()
                : null;
        return new SingularAssociationCandidate(
                associationPath,
                member.name(),
                member.type(),
                List.copyOf(new LinkedHashSet<>(joinCols)),
                mapsIdValue
        );
    }

    private static Map<String, List<String>> collectAssociationOverrides(EntityMember embeddedMember) {
        Map<String, List<String>> overrides = new LinkedHashMap<>();
        AssociationOverride single = embeddedMember.findAnnotation(AssociationOverride.class);
        if (single != null) {
            recordAssociationOverride(overrides, single);
        }
        AssociationOverrides multi = embeddedMember.findAnnotation(AssociationOverrides.class);
        if (multi != null && multi.value() != null) {
            for (AssociationOverride item : multi.value()) {
                if (item != null) {
                    recordAssociationOverride(overrides, item);
                }
            }
        }
        return overrides;
    }

    private static void recordAssociationOverride(Map<String, List<String>> overrides, AssociationOverride item) {
        if (item.name() == null || item.name().isBlank() || item.joinColumns() == null) {
            return;
        }
        List<String> names = new ArrayList<>();
        for (JoinColumn jc : item.joinColumns()) {
            if (jc != null && jc.name() != null && !jc.name().isBlank()) {
                names.add(jc.name().trim().toLowerCase(Locale.ROOT));
            }
        }
        if (!names.isEmpty()) {
            overrides.put(item.name().trim(), List.copyOf(names));
        }
    }

    private static String resolveTargetIdPropertyName(Class<?> targetEntityType) {
        for (EntityMember member : collectEntityMembers(targetEntityType)) {
            if (member.findAnnotation(Id.class) != null || member.findAnnotation(EmbeddedId.class) != null) {
                return member.name();
            }
        }
        return "id";
    }

    private static String resolveScalarColumnNameLower(Class<?> entityClass, String dottedPath) {
        if (entityClass == null || dottedPath == null || dottedPath.isBlank()) {
            return null;
        }
        String[] segments = dottedPath.split("\\.", -1);
        Class<?> current = entityClass;
        Map<String, String> attributeOverrides = Map.of();
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            if (segment == null || segment.isBlank() || !isTraversableContainer(current)) {
                return null;
            }
            EntityMember member = findEntityMember(current, segment);
            if (member == null || member.isTransientOrCollection()) {
                return null;
            }
            if (i == segments.length - 1) {
                if (member.isSingularAssociation() || member.isEmbedded()) {
                    return null;
                }
                String overridden = attributeOverrides.get(segment);
                if (overridden != null) {
                    return overridden.toLowerCase(Locale.ROOT);
                }
                Column col = member.findAnnotation(Column.class);
                if (col != null && col.name() != null && !col.name().isBlank()) {
                    return col.name().trim().toLowerCase(Locale.ROOT);
                }
                return toSnakeCase(segment).toLowerCase(Locale.ROOT);
            }
            if (member.isEmbedded()) {
                attributeOverrides = collectAttributeOverrides(member);
                current = member.type();
            } else if (member.isSingularAssociation()) {
                attributeOverrides = Map.of();
                current = member.type();
            } else {
                return null;
            }
        }
        return null;
    }

    private static Map<String, String> collectAttributeOverrides(EntityMember embeddedMember) {
        Map<String, String> overrides = new LinkedHashMap<>();
        AttributeOverride single = embeddedMember.findAnnotation(AttributeOverride.class);
        if (single != null && !single.name().isBlank() && !single.column().name().isBlank()) {
            overrides.put(single.name().trim(), single.column().name().trim());
        }
        AttributeOverrides multi = embeddedMember.findAnnotation(AttributeOverrides.class);
        if (multi != null && multi.value() != null) {
            for (AttributeOverride item : multi.value()) {
                if (item != null && !item.name().isBlank() && !item.column().name().isBlank()) {
                    overrides.put(item.name().trim(), item.column().name().trim());
                }
            }
        }
        return overrides;
    }

    private static List<EntityMember> collectEntityMembers(Class<?> entityClass) {
        Map<String, List<AnnotatedElement>> elementsByName = new LinkedHashMap<>();
        Map<String, Class<?>> typesByName = new LinkedHashMap<>();

        for (Class<?> curr = entityClass; curr != null && curr != Object.class && curr != Record.class; curr = curr.getSuperclass()) {
            for (Field field : curr.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                    continue;
                }
                typesByName.putIfAbsent(field.getName(), field.getType());
                elementsByName.computeIfAbsent(field.getName(), ignored -> new ArrayList<>()).add(field);
            }
            for (Method method : curr.getDeclaredMethods()) {
                String propName = resolveGetterPropertyName(curr, method);
                if (propName == null) {
                    continue;
                }
                typesByName.putIfAbsent(propName, method.getReturnType());
                elementsByName.computeIfAbsent(propName, ignored -> new ArrayList<>()).add(method);
            }
        }
        List<EntityMember> members = new ArrayList<>(elementsByName.size());
        elementsByName.forEach((name, elements) ->
                members.add(new EntityMember(name, typesByName.get(name), List.copyOf(elements))));
        return members;
    }

    private static EntityMember findEntityMember(Class<?> entityClass, String propertyName) {
        List<AnnotatedElement> elements = new ArrayList<>();
        Class<?> type = null;
        for (Class<?> curr = entityClass; curr != null && curr != Object.class && curr != Record.class; curr = curr.getSuperclass()) {
            try {
                Field field = curr.getDeclaredField(propertyName);
                if (!Modifier.isStatic(field.getModifiers()) && !field.isSynthetic()) {
                    if (type == null) {
                        type = field.getType();
                    }
                    elements.add(field);
                }
            } catch (NoSuchFieldException ignored) {
                // continue walking hierarchy
            }
            addMatchingAccessorMethods(curr, propertyName, elements);
            if (type == null) {
                for (AnnotatedElement el : elements) {
                    if (el instanceof Method m) {
                        type = m.getReturnType();
                        break;
                    }
                }
            }
        }
        if (elements.isEmpty()) {
            return null;
        }
        return new EntityMember(propertyName, type, List.copyOf(elements));
    }

    private static DtoPropertyMatch findDirectDtoProperty(Class<?> ownerType, String propertyName, String fullPath) {
        Class<?> propertyType = null;
        List<AnnotatedElement> elements = new ArrayList<>();

        for (Class<?> curr = ownerType; curr != null && curr != Object.class && curr != Record.class; curr = curr.getSuperclass()) {
            if (curr.isRecord() && curr.getRecordComponents() != null) {
                for (RecordComponent rc : curr.getRecordComponents()) {
                    if (propertyName.equals(rc.getName())) {
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
            try {
                Field field = curr.getDeclaredField(propertyName);
                if (!Modifier.isStatic(field.getModifiers()) && !field.isSynthetic()) {
                    if (propertyType == null) {
                        propertyType = field.getType();
                    }
                    elements.add(field);
                }
            } catch (NoSuchFieldException ignored) {
                // continue walking hierarchy
            }
            addMatchingAccessorMethods(curr, propertyName, elements);
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
        return new DtoPropertyMatch(fullPath, propertyType, List.copyOf(elements));
    }

    private static void addMatchingAccessorMethods(Class<?> type, String propertyName, List<AnnotatedElement> elements) {
        if (propertyName == null || propertyName.isEmpty()) {
            return;
        }
        String cap = Character.toUpperCase(propertyName.charAt(0)) + propertyName.substring(1);
        List<String> candidates = type.isRecord()
                ? List.of(propertyName, "get" + cap, "is" + cap)
                : List.of("get" + cap, "is" + cap);
        for (String candidate : candidates) {
            try {
                Method m = type.getDeclaredMethod(candidate);
                if (m.getParameterCount() != 0
                        || Modifier.isStatic(m.getModifiers())
                        || m.isSynthetic()
                        || m.isBridge()
                        || m.getReturnType() == void.class
                        || "getClass".equals(m.getName())) {
                    continue;
                }
                if (candidate.startsWith("is")
                        && m.getReturnType() != boolean.class
                        && m.getReturnType() != Boolean.class) {
                    continue;
                }
                elements.add(m);
            } catch (NoSuchMethodException ignored) {
                // continue scanning candidates
            }
        }
    }

    private static String resolveGetterPropertyName(Class<?> owner, Method method) {
        if (Modifier.isStatic(method.getModifiers())
                || method.isSynthetic()
                || method.isBridge()
                || method.getParameterCount() != 0
                || method.getReturnType() == void.class) {
            return null;
        }
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

    private static ResourceIdentity findResourceIdentity(Class<?> dtoClass) {
        for (Class<?> curr = dtoClass; curr != null && curr != Object.class; curr = curr.getSuperclass()) {
            ResourceIdentity ann = curr.getAnnotation(ResourceIdentity.class);
            if (ann != null) {
                return ann;
            }
        }
        return null;
    }

    private static boolean isTraversableContainer(Class<?> type) {
        return type != null
                && !type.isPrimitive()
                && type != Object.class
                && type != Void.class
                && type != void.class
                && !type.isArray()
                && !Collection.class.isAssignableFrom(type)
                && !Map.class.isAssignableFrom(type);
    }

    private static boolean isScalarValueType(Class<?> type) {
        return type == null
                || type.isPrimitive()
                || type.isEnum()
                || type == String.class
                || Number.class.isAssignableFrom(type)
                || Boolean.class == type
                || Character.class == type
                || java.time.temporal.Temporal.class.isAssignableFrom(type)
                || java.util.Date.class.isAssignableFrom(type)
                || java.util.UUID.class == type;
    }

    private static String toSnakeCase(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(input.length() + 6);
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0 && (Character.isLowerCase(input.charAt(i - 1))
                        || Character.isDigit(input.charAt(i - 1))
                        || (i + 1 < input.length() && Character.isLowerCase(input.charAt(i + 1))))) {
                    sb.append('_');
                }
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
