package org.praxisplatform.uischema.id;

import jakarta.persistence.Embedded;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.Bindable;
import jakarta.persistence.metamodel.ManagedType;
import jakarta.persistence.metamodel.PluralAttribute;
import jakarta.persistence.metamodel.SingularAttribute;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Shared JPA Criteria path navigation and composite-key predicate/order support for
 * {@link org.praxisplatform.uischema.filter.specification.GenericSpecificationsBuilder},
 * {@link org.praxisplatform.uischema.options.service.jpa.JpaOptionSourceQueryExecutor},
 * {@link org.praxisplatform.uischema.stats.service.jpa.JpaStatsQueryExecutor}, and
 * {@link org.praxisplatform.uischema.service.base.AbstractBaseQueryResourceService}.
 */
public final class JpaCriteriaPathSupport {

    private JpaCriteriaPathSupport() {
    }

    /**
     * Resolves a dot-separated property path from {@code root}, distinguishing joinable JPA
     * associations ({@code @ManyToOne}, {@code @OneToMany}, etc.) from {@code @Embedded} and
     * {@code @EmbeddedId} compound paths (which must be navigated via {@link Path#get(String)}
     * rather than {@link From#join(String, JoinType)}).
     */
    public static Path<?> resolvePath(Root<?> root, String propertyPath, boolean reuseLeftJoins) {
        if (propertyPath == null || propertyPath.isBlank()) {
            throw new IllegalArgumentException("Property path must not be null or blank.");
        }
        String[] parts = propertyPath.split("\\.");
        Path<?> path = root;
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (i == parts.length - 1) {
                path = path.get(part);
            } else if (path instanceof From<?, ?> fromPath && isJoinableAssociation(fromPath, part)) {
                path = reuseLeftJoins
                        ? getOrCreateLeftJoin(fromPath, part)
                        : fromPath.join(part, JoinType.LEFT);
            } else {
                path = path.get(part);
            }
        }
        return path;
    }

    /**
     * Resolves {@code propertyPath} from {@code root} when non-blank and mapped in the entity's
     * JPA metamodel, returning {@code null} (without creating stray joins) when any path segment
     * is absent or non-navigable. When {@code root} has no JPA metamodel (such as bare mocks in
     * unit tests), falls back to {@link #resolvePath(Root, String, boolean)} defensively.
     */
    public static Path<?> tryResolvePath(Root<?> root, String propertyPath, boolean reuseLeftJoins) {
        if (root == null || propertyPath == null || propertyPath.isBlank()) {
            return null;
        }
        ManagedType<?> rootManagedType = resolveRootManagedType(root);
        if (rootManagedType != null && !hasMappedPropertyPath(rootManagedType, propertyPath)) {
            return null;
        }
        try {
            return resolvePath(root, propertyPath, reuseLeftJoins);
        } catch (IllegalArgumentException | IllegalStateException ignored) {
            return null;
        }
    }

    public static boolean hasMappedPropertyPath(Root<?> root, String propertyPath) {
        if (root == null || propertyPath == null || propertyPath.isBlank()) {
            return false;
        }
        ManagedType<?> rootManagedType = resolveRootManagedType(root);
        return rootManagedType != null && hasMappedPropertyPath(rootManagedType, propertyPath);
    }

    private static ManagedType<?> resolveRootManagedType(Root<?> root) {
        try {
            Bindable<?> model = root.getModel();
            if (model instanceof ManagedType<?> managedType) {
                return managedType;
            }
        } catch (RuntimeException ignored) {
            // Bare mocks in unit tests may throw or omit metamodel
        }
        return null;
    }

    private static boolean hasMappedPropertyPath(ManagedType<?> rootManagedType, String propertyPath) {
        if (rootManagedType == null || propertyPath == null || propertyPath.isBlank()) {
            return false;
        }
        String[] parts = propertyPath.split("\\.", -1);
        ManagedType<?> current = rootManagedType;
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.isBlank() || current == null) {
                return false;
            }
            Attribute<?, ?> attr = findAttribute(current, part);
            if (attr == null) {
                return false;
            }
            if (i == parts.length - 1) {
                return true;
            }
            current = resolveNextManagedType(attr);
        }
        return false;
    }

    private static Attribute<?, ?> findAttribute(ManagedType<?> managedType, String name) {
        for (Attribute<?, ?> attribute : managedType.getAttributes()) {
            if (name.equals(attribute.getName())) {
                return attribute;
            }
        }
        return null;
    }

    private static ManagedType<?> resolveNextManagedType(Attribute<?, ?> attr) {
        if (attr instanceof SingularAttribute<?, ?> singular) {
            return singular.getType() instanceof ManagedType<?> managedType ? managedType : null;
        }
        if (attr instanceof PluralAttribute<?, ?, ?> plural) {
            return plural.getElementType() instanceof ManagedType<?> managedType ? managedType : null;
        }
        return null;
    }

    public static Join<?, ?> getOrCreateLeftJoin(From<?, ?> fromPath, String attributeName) {
        for (Join<?, ?> existing : fromPath.getJoins()) {
            if (existing.getAttribute() != null
                    && attributeName.equals(existing.getAttribute().getName())
                    && existing.getJoinType() == JoinType.LEFT) {
                return existing;
            }
        }
        return fromPath.join(attributeName, JoinType.LEFT);
    }

    public static Join<?, ?> getOrCreateInnerJoin(From<?, ?> fromPath, String attributeName) {
        for (Join<?, ?> existing : fromPath.getJoins()) {
            if (existing.getAttribute() != null
                    && attributeName.equals(existing.getAttribute().getName())
                    && existing.getJoinType() == JoinType.INNER) {
                return existing;
            }
        }
        return fromPath.join(attributeName, JoinType.INNER);
    }

    /**
     * Returns {@code true} when {@code fromPath} exposes a real JPA metamodel or Java class
     * (distinguishing real JPA criteria roots from bare unit-test mocks).
     */
    public static boolean hasMetamodelOrJavaType(From<?, ?> fromPath) {
        if (fromPath == null) {
            return false;
        }
        try {
            if (fromPath.getModel() != null) {
                return true;
            }
        } catch (RuntimeException ignored) {
            // Ignore mock exceptions
        }
        try {
            return fromPath.getJavaType() != null;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /**
     * Returns {@code true} when any intermediate association along {@code propertyPath}
     * traverses a plural/collection attribute ({@code @OneToMany}, {@code @ManyToMany},
     * {@code @ElementCollection}), which would multiply parent rows if joined directly on the root query.
     */
    public static boolean traversesCollectionAssociation(From<?, ?> root, String propertyPath) {
        if (root == null || propertyPath == null || propertyPath.isBlank() || !propertyPath.contains(".")) {
            return false;
        }
        if (!hasMetamodelOrJavaType(root)) {
            return false;
        }
        String[] parts = propertyPath.split("\\.");
        ManagedType<?> currentManagedType = null;
        Class<?> currentJavaType = null;
        try {
            Bindable<?> model = root.getModel();
            if (model instanceof ManagedType<?> mt) {
                currentManagedType = mt;
            }
        } catch (RuntimeException ignored) {
            // Fall through to reflection
        }
        try {
            currentJavaType = root.getJavaType();
        } catch (RuntimeException ignored) {
            // Ignore
        }

        for (int i = 0; i < parts.length - 1; i++) {
            String part = parts[i];
            if (currentManagedType != null) {
                try {
                    Attribute<?, ?> attr = currentManagedType.getAttribute(part);
                    if (attr != null) {
                        if (attr.isCollection()) {
                            return true;
                        }
                        if (attr instanceof Bindable<?> bindable && bindable.getBindableJavaType() != null) {
                            currentJavaType = bindable.getBindableJavaType();
                        } else {
                            currentJavaType = attr.getJavaType();
                        }
                        currentManagedType = null;
                        continue;
                    }
                } catch (RuntimeException ignored) {
                    currentManagedType = null;
                }
            }
            if (currentJavaType != null) {
                Field field = findFieldInHierarchy(currentJavaType, part);
                if (field == null) {
                    return false;
                }
                if (Collection.class.isAssignableFrom(field.getType())
                        || java.util.Map.class.isAssignableFrom(field.getType())) {
                    return true;
                }
                currentJavaType = field.getType();
            } else {
                return false;
            }
        }
        return false;
    }

    /**
     * Resolves {@code propertyPath} inside a correlated {@code EXISTS} subquery rooted at {@code outerRoot},
     * using {@code INNER JOIN} across associations so plural collections never multiply outer query rows.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static Path<?> resolveCorrelatedSubqueryPath(
            jakarta.persistence.criteria.Subquery<?> subquery,
            Root<?> outerRoot,
            String propertyPath
    ) {
        if (subquery == null || outerRoot == null || propertyPath == null || propertyPath.isBlank()) {
            return null;
        }
        Root correlatedRoot = subquery.correlate((Root) outerRoot);
        if (correlatedRoot == null) {
            return null;
        }
        String[] parts = propertyPath.split("\\.");
        Path<?> path = correlatedRoot;
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (i == parts.length - 1) {
                path = path.get(part);
            } else if (path instanceof From<?, ?> fromPath && isJoinableAssociation(fromPath, part)) {
                path = getOrCreateInnerJoin(fromPath, part);
            } else {
                path = path.get(part);
            }
        }
        return path;
    }

    private static Field findFieldInHierarchy(Class<?> ownerClass, String fieldName) {
        Class<?> current = ownerClass;
        while (current != null && current != Object.class) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    public static boolean isJoinableAssociation(From<?, ?> fromPath, String attributeName) {
        if (fromPath == null || attributeName == null || attributeName.isBlank()) {
            return false;
        }
        Class<?> ownerJavaType = null;
        try {
            ownerJavaType = fromPath.getJavaType();
        } catch (RuntimeException ignored) {
            // Mocked From in unit tests may not declare javaType
        }
        if (ownerJavaType != null && isEmbeddedField(ownerJavaType, attributeName)) {
            return false;
        }
        try {
            Bindable<?> model = fromPath.getModel();
            if (model instanceof ManagedType<?> managedType) {
                Attribute<?, ?> attr = managedType.getAttribute(attributeName);
                if (attr != null) {
                    return attr.isAssociation() || attr.isCollection();
                }
            } else if (fromPath instanceof Join<?, ?> join && join.getAttribute() != null) {
                Attribute.PersistentAttributeType pat = join.getAttribute().getPersistentAttributeType();
                if (pat == Attribute.PersistentAttributeType.EMBEDDED) {
                    return false;
                }
            }
        } catch (RuntimeException ignored) {
            // Fall through when metamodel is unavailable or mocked
        }
        return true;
    }

    private static boolean isEmbeddedField(Class<?> ownerClass, String fieldName) {
        Class<?> current = ownerClass;
        while (current != null && current != Object.class) {
            try {
                Field field = current.getDeclaredField(fieldName);
                if (field.isAnnotationPresent(EmbeddedId.class)) {
                    CompositeIdSupport.registerCompositeIdType(field.getType());
                    return true;
                }
                if (field.isAnnotationPresent(Embedded.class)
                        || CompositeIdSupport.isEmbeddableValueType(field.getType())
                        || CompositeIdSupport.isCompositeIdType(field.getType())) {
                    return true;
                }
                return false;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        return false;
    }

    public static org.springframework.data.domain.Sort expandCompositeSort(
            Class<?> entityClass,
            org.springframework.data.domain.Sort sort
    ) {
        if (sort == null || !sort.isSorted() || entityClass == null) {
            return sort;
        }
        CompositeIdSupport.registerEntityCompositeIdTypes(entityClass);
        List<org.springframework.data.domain.Sort.Order> expanded = new ArrayList<>();
        boolean changed = false;
        for (org.springframework.data.domain.Sort.Order order : sort) {
            String property = order.getProperty();
            Class<?> targetType = resolvePropertyJavaType(entityClass, property);
            if (CompositeIdSupport.isCompositeIdType(targetType)) {
                List<String> compNames = CompositeIdSupport.componentNames(targetType);
                if (!compNames.isEmpty()) {
                    changed = true;
                    for (String comp : compNames) {
                        org.springframework.data.domain.Sort.Order compOrder =
                                new org.springframework.data.domain.Sort.Order(
                                        order.getDirection(),
                                        property + "." + comp,
                                        order.getNullHandling()
                                );
                        if (order.isIgnoreCase()) {
                            compOrder = compOrder.ignoreCase();
                        }
                        expanded.add(compOrder);
                    }
                    continue;
                }
            }
            expanded.add(order);
        }
        return changed ? org.springframework.data.domain.Sort.by(expanded) : sort;
    }

    private static Class<?> resolvePropertyJavaType(Class<?> rootClass, String propertyPath) {
        if (rootClass == null || propertyPath == null || propertyPath.isBlank()) {
            return null;
        }
        String[] parts = propertyPath.split("\\.");
        Class<?> current = rootClass;
        for (String part : parts) {
            Field field = findDeclaredFieldInHierarchy(current, part);
            if (field == null) {
                return null;
            }
            if (field.isAnnotationPresent(EmbeddedId.class)) {
                CompositeIdSupport.registerCompositeIdType(field.getType());
            }
            current = field.getType();
        }
        return current;
    }

    private static Field findDeclaredFieldInHierarchy(Class<?> type, String fieldName) {
        Class<?> current = type;
        while (current != null && current != Object.class) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    public static Predicate buildEqualPredicate(CriteriaBuilder cb, Path<?> path, Object rawValue) {
        Class<?> pathType = safeJavaType(path);
        if (CompositeIdSupport.isCompositeIdType(pathType)) {
            Object composite = CompositeIdSupport.coerceIfComposite(rawValue, pathType);
            List<CompositeIdSupport.ComponentDescriptor> components = CompositeIdSupport.descriptors(pathType);
            if (!components.isEmpty() && composite != null) {
                List<Predicate> componentPredicates = new ArrayList<>(components.size());
                for (CompositeIdSupport.ComponentDescriptor comp : components) {
                    componentPredicates.add(cb.equal(path.get(comp.name()), comp.read(composite)));
                }
                return cb.and(componentPredicates.toArray(Predicate[]::new));
            }
            return cb.equal(path, composite);
        }
        return cb.equal(path, rawValue);
    }

    public static Predicate buildNotEqualPredicate(CriteriaBuilder cb, Path<?> path, Object rawValue) {
        Class<?> pathType = safeJavaType(path);
        if (CompositeIdSupport.isCompositeIdType(pathType)) {
            return cb.not(buildEqualPredicate(cb, path, rawValue));
        }
        return cb.notEqual(path, rawValue);
    }

    public static Predicate buildInPredicate(CriteriaBuilder cb, Path<?> path, Collection<?> rawValues) {
        if (rawValues == null || rawValues.isEmpty()) {
            return cb.disjunction();
        }
        Class<?> pathType = safeJavaType(path);
        if (CompositeIdSupport.isCompositeIdType(pathType)) {
            List<Predicate> disjunctions = rawValues.stream()
                    .filter(Objects::nonNull)
                    .map(val -> buildEqualPredicate(cb, path, val))
                    .toList();
            if (disjunctions.isEmpty()) {
                return cb.disjunction();
            }
            return disjunctions.size() == 1
                    ? disjunctions.get(0)
                    : cb.or(disjunctions.toArray(Predicate[]::new));
        }
        CriteriaBuilder.In<Object> inClause = cb.in(path);
        for (Object val : rawValues) {
            inClause.value(val);
        }
        return inClause;
    }

    public static Predicate buildNotInPredicate(CriteriaBuilder cb, Path<?> path, Collection<?> rawValues) {
        if (rawValues == null || rawValues.isEmpty()) {
            return cb.conjunction();
        }
        return cb.not(buildInPredicate(cb, path, rawValues));
    }

    public static Predicate buildIsNullPredicate(CriteriaBuilder cb, Path<?> path) {
        Class<?> pathType = safeJavaType(path);
        if (CompositeIdSupport.isCompositeIdType(pathType)) {
            List<CompositeIdSupport.ComponentDescriptor> components = CompositeIdSupport.descriptors(pathType);
            if (!components.isEmpty()) {
                List<Predicate> predicates = components.stream()
                        .map(comp -> cb.isNull(path.get(comp.name())))
                        .toList();
                return cb.and(predicates.toArray(Predicate[]::new));
            }
        }
        return cb.isNull(path);
    }

    public static Predicate buildIsNotNullPredicate(CriteriaBuilder cb, Path<?> path) {
        Class<?> pathType = safeJavaType(path);
        if (CompositeIdSupport.isCompositeIdType(pathType)) {
            List<CompositeIdSupport.ComponentDescriptor> components = CompositeIdSupport.descriptors(pathType);
            if (!components.isEmpty()) {
                List<Predicate> predicates = components.stream()
                        .map(comp -> cb.isNotNull(path.get(comp.name())))
                        .toList();
                return cb.and(predicates.toArray(Predicate[]::new));
            }
        }
        return cb.isNotNull(path);
    }

    public static List<Order> buildOrders(CriteriaBuilder cb, Path<?> path, boolean ascending) {
        Class<?> pathType = safeJavaType(path);
        if (CompositeIdSupport.isCompositeIdType(pathType)) {
            List<CompositeIdSupport.ComponentDescriptor> components = CompositeIdSupport.descriptors(pathType);
            if (!components.isEmpty()) {
                return components.stream()
                        .map(comp -> {
                            Path<?> compPath = path.get(comp.name());
                            return ascending ? cb.asc(compPath) : cb.desc(compPath);
                        })
                        .toList();
            }
        }
        return List.of(ascending ? cb.asc(path) : cb.desc(path));
    }

    private static Class<?> safeJavaType(Path<?> path) {
        if (path == null) {
            return null;
        }
        try {
            return path.getJavaType();
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
