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
