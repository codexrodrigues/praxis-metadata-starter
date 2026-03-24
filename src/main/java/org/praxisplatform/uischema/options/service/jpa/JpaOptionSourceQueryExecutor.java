package org.praxisplatform.uischema.options.service.jpa;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.praxisplatform.uischema.dto.OptionDTO;
import org.praxisplatform.uischema.options.OptionSourceDescriptor;
import org.praxisplatform.uischema.options.OptionSourceType;
import org.praxisplatform.uischema.options.service.OptionSourceQueryExecutor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * JPA executor for metadata-driven option sources.
 */
@Component
public class JpaOptionSourceQueryExecutor implements OptionSourceQueryExecutor {

    @Override
    public <E> Page<OptionDTO<Object>> filterOptions(
            EntityManager entityManager,
            Class<E> entityClass,
            Specification<E> specification,
            OptionSourceDescriptor descriptor,
            String search,
            Pageable pageable,
            Collection<Object> includeIds
    ) {
        ensureSupported(descriptor);

        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root<E> root = query.from(entityClass);
        Path<?> valuePath = resolveValuePath(root, descriptor);
        Path<?> labelPath = resolveLabelPath(root, descriptor);

        Predicate predicate = applyPredicate(specification, root, query, cb);
        Predicate notNullPredicate = cb.isNotNull(valuePath);
        Predicate searchPredicate = buildSearchPredicate(cb, labelPath, descriptor, search);
        Predicate mergedPredicate = mergePredicates(cb, mergePredicates(cb, predicate, notNullPredicate), searchPredicate);
        if (mergedPredicate != null) {
            query.where(mergedPredicate);
        }

        applyOptionSelections(query, valuePath, labelPath);
        query.distinct(true);
        query.orderBy(resolveOrders(cb, valuePath, labelPath, descriptor, pageable.getSort()));

        TypedQuery<Tuple> typedQuery = entityManager.createQuery(query);
        typedQuery.setFirstResult((int) pageable.getOffset());
        typedQuery.setMaxResults(pageable.getPageSize());

        List<OptionDTO<Object>> pageContent = typedQuery.getResultList().stream()
                .map(this::toOption)
                .filter(option -> option.id() != null)
                .toList();

        long total = countDistinct(entityManager, entityClass, specification, descriptor, search);
        List<OptionDTO<Object>> merged = mergeIncludedOptions(
                pageContent,
                byIdsOptions(entityManager, entityClass, descriptor, includeIds == null ? List.of() : includeIds)
        );

        return new PageImpl<>(merged, pageable, Math.max(total, merged.size()));
    }

    @Override
    public <E> List<OptionDTO<Object>> byIdsOptions(
            EntityManager entityManager,
            Class<E> entityClass,
            OptionSourceDescriptor descriptor,
            Collection<Object> ids
    ) {
        ensureSupported(descriptor);
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }

        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root<E> root = query.from(entityClass);
        Path<?> valuePath = resolveValuePath(root, descriptor);
        Path<?> labelPath = resolveLabelPath(root, descriptor);
        Class<?> valueType = valuePath.getJavaType();

        List<Object> coercedIds = ids.stream()
                .filter(Objects::nonNull)
                .map(Object::toString)
                .map(raw -> coerceValue(raw, valueType))
                .toList();

        applyOptionSelections(query, valuePath, labelPath);
        query.where(valuePath.in(coercedIds)).distinct(true);

        Map<String, OptionDTO<Object>> byKey = entityManager.createQuery(query)
                .getResultList()
                .stream()
                .map(this::toOption)
                .collect(Collectors.toMap(option -> stringify(option.id()), option -> option, (left, right) -> left, LinkedHashMap::new));

        return ids.stream()
                .map(id -> byKey.get(stringify(id)))
                .filter(Objects::nonNull)
                .toList();
    }

    private void ensureSupported(OptionSourceDescriptor descriptor) {
        if (descriptor.type() != OptionSourceType.DISTINCT_DIMENSION
                && descriptor.type() != OptionSourceType.CATEGORICAL_BUCKET) {
            throw new UnsupportedOperationException("Option source type not implemented: " + descriptor.type());
        }
    }

    private <E> long countDistinct(
            EntityManager entityManager,
            Class<E> entityClass,
            Specification<E> specification,
            OptionSourceDescriptor descriptor,
            String search
    ) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> countQuery = cb.createQuery(Long.class);
        Root<E> root = countQuery.from(entityClass);
        Path<?> valuePath = resolveValuePath(root, descriptor);

        Predicate predicate = applyPredicate(specification, root, countQuery, cb);
        Predicate notNullPredicate = cb.isNotNull(valuePath);
        Predicate searchPredicate = buildSearchPredicate(cb, resolveLabelPath(root, descriptor), descriptor, search);
        Predicate mergedPredicate = mergePredicates(cb, mergePredicates(cb, predicate, notNullPredicate), searchPredicate);
        if (mergedPredicate != null) {
            countQuery.where(mergedPredicate);
        }

        countQuery.select(cb.countDistinct(valuePath));
        return entityManager.createQuery(countQuery).getSingleResult();
    }

    private Predicate buildSearchPredicate(
            CriteriaBuilder cb,
            Path<?> valuePath,
            OptionSourceDescriptor descriptor,
            String search
    ) {
        if (search == null || search.isBlank() || !descriptor.policy().allowSearch()) {
            return null;
        }
        String normalized = search.trim();
        if (normalized.length() < descriptor.policy().minSearchChars()) {
            return null;
        }
        Expression<String> text = cb.lower(valuePath.as(String.class));
        String lowered = normalized.toLowerCase(Locale.ROOT);
        return switch (descriptor.policy().searchMode()) {
            case "none" -> null;
            case "exact" -> cb.equal(text, lowered);
            case "starts-with" -> cb.like(text, lowered + "%");
            default -> cb.like(text, "%" + lowered + "%");
        };
    }

    private Order[] resolveOrders(
            CriteriaBuilder cb,
            Path<?> valuePath,
            Path<?> labelPath,
            OptionSourceDescriptor descriptor,
            Sort sort
    ) {
        if (sort != null && sort.isSorted()) {
            List<Order> mapped = sort.stream()
                    .filter(order -> "label".equals(order.getProperty()) || "id".equals(order.getProperty()))
                    .map(order -> {
                        Path<?> sortPath = "id".equals(order.getProperty()) ? valuePath : labelPath;
                        return order.isAscending() ? cb.asc(sortPath) : cb.desc(sortPath);
                    })
                    .toList();
            if (!mapped.isEmpty()) {
                return mapped.toArray(Order[]::new);
            }
        }
        Path<?> defaultSortPath = "id".equalsIgnoreCase(descriptor.policy().defaultSort()) ? valuePath : labelPath;
        return new Order[]{cb.asc(defaultSortPath)};
    }

    private Predicate mergePredicates(CriteriaBuilder cb, Predicate left, Predicate right) {
        if (left == null) return right;
        if (right == null) return left;
        return cb.and(left, right);
    }

    private <E> Predicate applyPredicate(
            Specification<E> specification,
            Root<E> root,
            CriteriaQuery<?> query,
            CriteriaBuilder cb
    ) {
        return specification == null ? null : specification.toPredicate(root, query, cb);
    }

    private OptionDTO<Object> toOption(Object value, Object label) {
        return new OptionDTO<>(value, stringify(label != null ? label : value), null);
    }

    private OptionDTO<Object> toOption(Tuple tuple) {
        Object value = tuple.get("optionValue");
        Object label = null;
        try {
            label = tuple.get("optionLabel");
        } catch (IllegalArgumentException ignored) {
            // Same-path option sources select only the value column and reuse it as label.
        }
        return toOption(value, label);
    }

    private List<OptionDTO<Object>> mergeIncludedOptions(
            List<OptionDTO<Object>> pageContent,
            List<OptionDTO<Object>> included
    ) {
        if (included == null || included.isEmpty()) {
            return pageContent;
        }
        Map<String, OptionDTO<Object>> ordered = new LinkedHashMap<>();
        included.forEach(option -> ordered.put(stringify(option.id()), option));
        pageContent.forEach(option -> ordered.putIfAbsent(stringify(option.id()), option));
        return List.copyOf(ordered.values());
    }

    private String stringify(Object value) {
        return value == null ? "null" : String.valueOf(value);
    }

    private Path<?> resolveValuePath(Root<?> root, OptionSourceDescriptor descriptor) {
        return resolvePath(root, descriptor.valuePropertyPath() != null ? descriptor.valuePropertyPath() : descriptor.propertyPath());
    }

    private Path<?> resolveLabelPath(Root<?> root, OptionSourceDescriptor descriptor) {
        return resolvePath(root, descriptor.labelPropertyPath() != null ? descriptor.labelPropertyPath() : descriptor.propertyPath());
    }

    private void applyOptionSelections(CriteriaQuery<Tuple> query, Path<?> valuePath, Path<?> labelPath) {
        if (samePath(valuePath, labelPath)) {
            query.multiselect(valuePath.alias("optionValue"));
            return;
        }
        query.multiselect(valuePath.alias("optionValue"), labelPath.alias("optionLabel"));
    }

    private boolean samePath(Path<?> left, Path<?> right) {
        return left == right || (left != null && right != null && left.toString().equals(right.toString()));
    }

    private Object coerceValue(String raw, Class<?> targetType) {
        if (targetType == null || targetType == Object.class || targetType == String.class) {
            return raw;
        }
        if (targetType == Long.class || targetType == long.class) {
            return Long.valueOf(raw);
        }
        if (targetType == Integer.class || targetType == int.class) {
            return Integer.valueOf(raw);
        }
        if (targetType == Double.class || targetType == double.class) {
            return Double.valueOf(raw);
        }
        if (targetType == Float.class || targetType == float.class) {
            return Float.valueOf(raw);
        }
        if (targetType == Boolean.class || targetType == boolean.class) {
            return Boolean.valueOf(raw);
        }
        if (Enum.class.isAssignableFrom(targetType)) {
            @SuppressWarnings({"rawtypes", "unchecked"})
            Object enumValue = Enum.valueOf((Class<? extends Enum>) targetType.asSubclass(Enum.class), raw);
            return enumValue;
        }
        return raw;
    }

    private Path<?> resolvePath(Root<?> root, String propertyPath) {
        String[] parts = propertyPath.split("\\.");
        Path<?> path = root;
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (i == parts.length - 1) {
                path = path.get(part);
            } else if (path instanceof Root<?> rootPath) {
                path = rootPath.join(part, JoinType.LEFT);
            } else if (path instanceof From<?, ?> fromPath) {
                path = fromPath.join(part, JoinType.LEFT);
            } else {
                throw new IllegalArgumentException("Unable to resolve option source property path: " + propertyPath);
            }
        }
        return path;
    }
}
