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
import jakarta.persistence.criteria.Selection;
import org.praxisplatform.uischema.dto.OptionDTO;
import org.praxisplatform.uischema.options.EntityLookupDescriptor;
import org.praxisplatform.uischema.options.LookupFilterDefinition;
import org.praxisplatform.uischema.options.LookupFilterRequest;
import org.praxisplatform.uischema.options.LookupFilteringDescriptor;
import org.praxisplatform.uischema.options.LookupDetailDescriptor;
import org.praxisplatform.uischema.options.LookupSelectionPolicy;
import org.praxisplatform.uischema.options.LookupSortOption;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;

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
            List<LookupFilterRequest> filters,
            String sortKey,
            Pageable pageable,
            Collection<Object> includeIds
    ) {
        ensureSupported(descriptor);

        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root<E> root = query.from(entityClass);
        Path<?> valuePath = resolveValuePath(root, descriptor);
        Path<?> labelPath = resolveLabelPath(root, descriptor);
        boolean richResourceEntity = isRichResourceEntity(descriptor);
        LookupSortOption explicitSortOption = resolveSortOptionOrNull(descriptor, sortKey);
        LookupSortOption defaultSortOption = resolveDefaultSortOption(descriptor);
        Path<?> metadataSortPath = resolveMetadataSortPath(root, descriptor, pageable.getSort(), explicitSortOption, defaultSortOption);
        boolean relaxDistinctForMetadataSort = metadataSortPath != null;

        Predicate predicate = applyPredicate(specification, root, query, cb);
        Predicate notNullPredicate = cb.isNotNull(valuePath);
        Predicate searchPredicate = buildSearchPredicate(cb, root, labelPath, descriptor, search);
        Predicate structuredFilterPredicate = buildStructuredFilterPredicate(cb, root, descriptor, filters);
        Predicate mergedPredicate = mergePredicates(
                cb,
                mergePredicates(cb, mergePredicates(cb, predicate, notNullPredicate), searchPredicate),
                structuredFilterPredicate
        );
        if (mergedPredicate != null) {
            query.where(mergedPredicate);
        }

        if (richResourceEntity) {
            applyEntityLookupSelections(query, root, valuePath, labelPath, descriptor, metadataSortPath);
        } else {
            applyOptionSelections(query, valuePath, labelPath);
        }
        query.distinct(!relaxDistinctForMetadataSort);
        query.orderBy(resolveOrders(cb, root, valuePath, labelPath, descriptor, pageable.getSort(), sortKey));

        TypedQuery<Tuple> typedQuery = entityManager.createQuery(query);
        typedQuery.setFirstResult((int) pageable.getOffset());
        typedQuery.setMaxResults(pageable.getPageSize());

        List<OptionDTO<Object>> pageContent = typedQuery.getResultList().stream()
                .map(tuple -> richResourceEntity ? toEntityLookupOption(tuple, descriptor) : toOption(tuple))
                .filter(option -> option.id() != null)
                .toList();
        pageContent = dedupeOptions(pageContent);

        long total = countDistinct(entityManager, entityClass, specification, descriptor, search, filters);
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
        boolean richResourceEntity = isRichResourceEntity(descriptor);
        Class<?> valueType = valuePath.getJavaType();

        List<Object> coercedIds = ids.stream()
                .filter(Objects::nonNull)
                .map(Object::toString)
                .map(raw -> coerceValue(raw, valueType))
                .toList();

        if (richResourceEntity) {
            applyEntityLookupSelections(query, root, valuePath, labelPath, descriptor, null);
        } else {
            applyOptionSelections(query, valuePath, labelPath);
        }
        query.where(valuePath.in(coercedIds)).distinct(true);

        Map<String, OptionDTO<Object>> byKey = entityManager.createQuery(query)
                .getResultList()
                .stream()
                .map(tuple -> richResourceEntity ? toEntityLookupOption(tuple, descriptor) : toOption(tuple))
                .collect(Collectors.toMap(option -> stringify(option.id()), option -> option, (left, right) -> left, LinkedHashMap::new));

        return ids.stream()
                .map(id -> byKey.get(stringify(id)))
                .filter(Objects::nonNull)
                .toList();
    }

    private void ensureSupported(OptionSourceDescriptor descriptor) {
        if (descriptor.type() == OptionSourceType.DISTINCT_DIMENSION
                || descriptor.type() == OptionSourceType.CATEGORICAL_BUCKET
                || isRichResourceEntity(descriptor)) {
            return;
        }
        if (descriptor.type() != OptionSourceType.DISTINCT_DIMENSION
                && descriptor.type() != OptionSourceType.CATEGORICAL_BUCKET) {
            throw new UnsupportedOperationException("Option source type not implemented: " + descriptor.type());
        }
    }

    private boolean isRichResourceEntity(OptionSourceDescriptor descriptor) {
        return descriptor.type() == OptionSourceType.RESOURCE_ENTITY && descriptor.entityLookup() != null;
    }

    private <E> long countDistinct(
            EntityManager entityManager,
            Class<E> entityClass,
            Specification<E> specification,
            OptionSourceDescriptor descriptor,
            String search,
            List<LookupFilterRequest> filters
    ) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> countQuery = cb.createQuery(Long.class);
        Root<E> root = countQuery.from(entityClass);
        Path<?> valuePath = resolveValuePath(root, descriptor);

        Predicate predicate = applyPredicate(specification, root, countQuery, cb);
        Predicate notNullPredicate = cb.isNotNull(valuePath);
        Predicate searchPredicate = buildSearchPredicate(cb, root, resolveLabelPath(root, descriptor), descriptor, search);
        Predicate structuredFilterPredicate = buildStructuredFilterPredicate(cb, root, descriptor, filters);
        Predicate mergedPredicate = mergePredicates(
                cb,
                mergePredicates(cb, mergePredicates(cb, predicate, notNullPredicate), searchPredicate),
                structuredFilterPredicate
        );
        if (mergedPredicate != null) {
            countQuery.where(mergedPredicate);
        }

        countQuery.select(cb.countDistinct(valuePath));
        return entityManager.createQuery(countQuery).getSingleResult();
    }

    private Predicate buildSearchPredicate(
            CriteriaBuilder cb,
            Root<?> root,
            Path<?> labelPath,
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
        List<Path<?>> searchPaths = resolveSearchPaths(root, labelPath, descriptor);
        String lowered = normalized.toLowerCase(Locale.ROOT);
        List<Predicate> predicates = searchPaths.stream()
                .map(path -> buildSearchPredicateForPath(cb, path, descriptor, lowered))
                .filter(Objects::nonNull)
                .toList();
        if (predicates.isEmpty()) {
            return null;
        }
        return cb.or(predicates.toArray(Predicate[]::new));
    }

    private Predicate buildSearchPredicateForPath(
            CriteriaBuilder cb,
            Path<?> path,
            OptionSourceDescriptor descriptor,
            String lowered
    ) {
        Expression<String> text = cb.lower(path.as(String.class));
        return switch (descriptor.policy().searchMode()) {
            case "none" -> null;
            case "exact" -> cb.equal(text, lowered);
            case "starts-with" -> cb.like(text, lowered + "%");
            default -> cb.like(text, "%" + lowered + "%");
        };
    }

    private List<Path<?>> resolveSearchPaths(Root<?> root, Path<?> labelPath, OptionSourceDescriptor descriptor) {
        EntityLookupDescriptor lookup = descriptor.entityLookup();
        LookupFilteringDescriptor filtering = lookup == null ? null : lookup.filtering();
        if (filtering != null && !filtering.quickFilterFields().isEmpty()) {
            List<Path<?>> paths = new ArrayList<>();
            for (String searchPath : filtering.quickFilterFields()) {
                paths.add(resolvePath(root, searchPath));
            }
            return paths;
        }
        if (lookup == null || lookup.searchPropertyPaths().isEmpty()) {
            return List.of(labelPath);
        }
        List<Path<?>> paths = new ArrayList<>();
        for (String searchPath : lookup.searchPropertyPaths()) {
            Path<?> resolvedPath = resolvePath(root, searchPath);
            paths.add(resolvedPath);
        }
        return paths;
    }

    private Order[] resolveOrders(
            CriteriaBuilder cb,
            Root<?> root,
            Path<?> valuePath,
            Path<?> labelPath,
            OptionSourceDescriptor descriptor,
            Sort sort,
            String sortKey
    ) {
        LookupSortOption sortOption = resolveSortOptionOrNull(descriptor, sortKey);
        if (sortOption != null) {
            Path<?> sortPath = resolvePath(root, sortOption.field());
            return new Order[]{
                    "desc".equals(sortOption.direction()) ? cb.desc(sortPath) : cb.asc(sortPath)
            };
        }
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
        LookupSortOption defaultSortOption = resolveDefaultSortOption(descriptor);
        if (defaultSortOption != null) {
            Path<?> sortPath = resolvePath(root, defaultSortOption.field());
            return new Order[]{
                    "desc".equals(defaultSortOption.direction()) ? cb.desc(sortPath) : cb.asc(sortPath)
            };
        }
        Path<?> defaultSortPath = "id".equalsIgnoreCase(descriptor.policy().defaultSort()) ? valuePath : labelPath;
        return new Order[]{cb.asc(defaultSortPath)};
    }

    private LookupSortOption resolveSortOptionOrNull(OptionSourceDescriptor descriptor, String sortKey) {
        if (sortKey == null || sortKey.isBlank()) {
            return null;
        }
        LookupFilteringDescriptor filtering = descriptor.entityLookup() == null ? null : descriptor.entityLookup().filtering();
        if (filtering == null || filtering.sortOptions().isEmpty()) {
            return null;
        }
        return filtering.sortOptions().stream()
                .filter(option -> sortKey.equals(option.key()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported entity lookup sort key: " + sortKey));
    }

    private LookupSortOption resolveDefaultSortOption(OptionSourceDescriptor descriptor) {
        LookupFilteringDescriptor filtering = descriptor.entityLookup() == null ? null : descriptor.entityLookup().filtering();
        if (filtering == null || filtering.defaultSort() == null) {
            return null;
        }
        return resolveSortOptionOrNull(descriptor, filtering.defaultSort());
    }

    private Path<?> resolveMetadataSortPath(
            Root<?> root,
            OptionSourceDescriptor descriptor,
            Sort sort,
            LookupSortOption explicitSortOption,
            LookupSortOption defaultSortOption
    ) {
        LookupSortOption effectiveSortOption = explicitSortOption != null ? explicitSortOption : defaultSortOption;
        if (effectiveSortOption != null) {
            return resolvePath(root, effectiveSortOption.field());
        }
        if (sort != null && sort.isSorted()) {
            return null;
        }
        return null;
    }

    private Predicate buildStructuredFilterPredicate(
            CriteriaBuilder cb,
            Root<?> root,
            OptionSourceDescriptor descriptor,
            List<LookupFilterRequest> filters
    ) {
        if (filters == null || filters.isEmpty()) {
            return null;
        }
        EntityLookupDescriptor lookup = descriptor.entityLookup();
        LookupFilteringDescriptor filtering = lookup == null ? null : lookup.filtering();
        if (filtering == null || filtering.availableFilters().isEmpty()) {
            throw new IllegalArgumentException("Structured filters are not supported for option source: " + descriptor.key());
        }
        Map<String, LookupFilterDefinition> available = filtering.availableFilters().stream()
                .collect(Collectors.toMap(LookupFilterDefinition::field, definition -> definition, (left, right) -> left, LinkedHashMap::new));
        List<Predicate> predicates = new ArrayList<>();
        for (LookupFilterRequest filter : filters) {
            LookupFilterDefinition definition = available.get(filter.field());
            if (definition == null) {
                throw new IllegalArgumentException("Unsupported entity lookup filter field: " + filter.field());
            }
            if (!definition.operators().contains(filter.operator())) {
                throw new IllegalArgumentException(
                        "Unsupported entity lookup filter operator '%s' for field '%s'.".formatted(
                                filter.operator(),
                                filter.field()
                        )
                );
            }
            predicates.add(buildPredicateForStructuredFilter(cb, resolvePath(root, definition.field()), definition, filter));
        }
        return predicates.isEmpty() ? null : cb.and(predicates.toArray(Predicate[]::new));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Predicate buildPredicateForStructuredFilter(
            CriteriaBuilder cb,
            Path<?> path,
            LookupFilterDefinition definition,
            LookupFilterRequest filter
    ) {
        return switch (filter.operator()) {
            case "contains" -> cb.like(cb.lower(path.as(String.class)), "%" + requiredTextValue(filter).toLowerCase(Locale.ROOT) + "%");
            case "startsWith" -> cb.like(cb.lower(path.as(String.class)), requiredTextValue(filter).toLowerCase(Locale.ROOT) + "%");
            case "equals" -> {
                Object coerced = coerceValue(requiredSingleValue(filter), path.getJavaType());
                if ("text".equals(definition.type())) {
                    yield cb.equal(cb.lower(path.as(String.class)), String.valueOf(coerced).toLowerCase(Locale.ROOT));
                }
                yield cb.equal(path, coerced);
            }
            case "in" -> {
                List<Object> values = requireMultipleValues(filter).stream()
                        .map(value -> coerceValue(value == null ? null : String.valueOf(value), path.getJavaType()))
                        .toList();
                yield path.in(values);
            }
            case "before" -> cb.lessThan((Expression<? extends Comparable>) path, (Comparable) coerceComparableValue(filter, path));
            case "after" -> cb.greaterThan((Expression<? extends Comparable>) path, (Comparable) coerceComparableValue(filter, path));
            case "between" -> {
                List<Object> values = requireBetweenValues(filter);
                Comparable from = (Comparable) coerceValue(values.get(0) == null ? null : String.valueOf(values.get(0)), path.getJavaType());
                Comparable to = (Comparable) coerceValue(values.get(1) == null ? null : String.valueOf(values.get(1)), path.getJavaType());
                yield cb.between((Expression<? extends Comparable>) path, from, to);
            }
            case "gt" -> cb.greaterThan((Expression<? extends Comparable>) path, (Comparable) coerceComparableValue(filter, path));
            case "gte" -> cb.greaterThanOrEqualTo((Expression<? extends Comparable>) path, (Comparable) coerceComparableValue(filter, path));
            case "lt" -> cb.lessThan((Expression<? extends Comparable>) path, (Comparable) coerceComparableValue(filter, path));
            case "lte" -> cb.lessThanOrEqualTo((Expression<? extends Comparable>) path, (Comparable) coerceComparableValue(filter, path));
            default -> throw new IllegalArgumentException("Unsupported entity lookup filter operator: " + filter.operator());
        };
    }

    private Comparable<?> coerceComparableValue(LookupFilterRequest filter, Path<?> path) {
        Object value = coerceValue(requiredSingleValue(filter), path.getJavaType());
        if (!(value instanceof Comparable<?> comparable)) {
            throw new IllegalArgumentException("Entity lookup filter value is not comparable for field: " + filter.field());
        }
        return comparable;
    }

    private String requiredTextValue(LookupFilterRequest filter) {
        String value = requiredSingleValue(filter);
        if (value.isBlank()) {
            throw new IllegalArgumentException("Entity lookup filter value must not be blank for field: " + filter.field());
        }
        return value;
    }

    private String requiredSingleValue(LookupFilterRequest filter) {
        List<Object> values = filter.values();
        if (values.isEmpty() || values.get(0) == null) {
            throw new IllegalArgumentException("Entity lookup filter value is required for field: " + filter.field());
        }
        return String.valueOf(values.get(0));
    }

    private List<Object> requireMultipleValues(LookupFilterRequest filter) {
        List<Object> values = filter.values().stream()
                .filter(Objects::nonNull)
                .toList();
        if (values.isEmpty()) {
            throw new IllegalArgumentException("Entity lookup filter values are required for field: " + filter.field());
        }
        return values;
    }

    private List<Object> requireBetweenValues(LookupFilterRequest filter) {
        List<Object> values = filter.values();
        if (values.size() < 2 || values.get(0) == null || values.get(1) == null) {
            throw new IllegalArgumentException("Entity lookup between filter requires two values for field: " + filter.field());
        }
        return values;
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

    private OptionDTO<Object> toEntityLookupOption(Tuple tuple, OptionSourceDescriptor descriptor) {
        Object value = tuple.get("optionValue");
        Object label = tupleValue(tuple, "optionLabel");
        EntityLookupDescriptor lookup = descriptor.entityLookup();
        Map<String, Object> extra = new LinkedHashMap<>();

        putIfNotNull(extra, "code", tupleValue(tuple, "lookupCode"));
        putIfNotBlank(extra, "description", buildDescription(tuple, lookup.descriptionPropertyPaths().size()));
        Object status = tupleValue(tuple, "lookupStatus");
        putIfNotNull(extra, "status", status);
        boolean selectable = resolveSelectable(tuple, status, lookup);
        extra.put("selectable", selectable);
        putIfNotNull(extra, "disabledReason", tupleValue(tuple, "lookupDisabledReason"));
        putIfNotBlank(extra, "detailHref", resolveTemplate(lookup.detail(), value, true));
        putIfNotBlank(extra, "detailRoute", resolveTemplate(lookup.detail(), value, false));
        putIfNotBlank(extra, "resourcePath", descriptor.resourcePath());
        putIfNotBlank(extra, "entityKey", lookup.entityKey());

        return new OptionDTO<>(value, stringify(label != null ? label : value), extra);
    }

    private Object tupleValue(Tuple tuple, String alias) {
        try {
            return tuple.get(alias);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private String buildDescription(Tuple tuple, int descriptionCount) {
        if (descriptionCount <= 0) {
            return null;
        }
        return java.util.stream.IntStream.range(0, descriptionCount)
                .mapToObj(index -> tupleValue(tuple, "lookupDescription" + index))
                .map(this::stringifyNullable)
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.joining(" - "));
    }

    private boolean resolveSelectable(Tuple tuple, Object status, EntityLookupDescriptor lookup) {
        Object selectableValue = tupleValue(tuple, "lookupSelectable");
        if (selectableValue instanceof Boolean booleanValue) {
            return booleanValue;
        }
        Object disabledValue = tupleValue(tuple, "lookupDisabled");
        if (disabledValue instanceof Boolean booleanValue && booleanValue) {
            return false;
        }
        LookupSelectionPolicy policy = lookup.selectionPolicy();
        if (policy == null) {
            return true;
        }
        String statusText = stringifyNullable(status);
        if (statusText != null && policy.blockedStatuses().contains(statusText)) {
            return false;
        }
        if (statusText != null && !policy.allowedStatuses().isEmpty()) {
            return policy.allowedStatuses().contains(statusText);
        }
        return true;
    }

    private String resolveTemplate(LookupDetailDescriptor detail, Object id, boolean href) {
        if (detail == null) {
            return null;
        }
        String template = href ? detail.hrefTemplate() : detail.routeTemplate();
        if (template == null || template.isBlank()) {
            return null;
        }
        return template.replace("{id}", stringify(id));
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

    private List<OptionDTO<Object>> dedupeOptions(List<OptionDTO<Object>> options) {
        if (options == null || options.isEmpty()) {
            return List.of();
        }
        Map<String, OptionDTO<Object>> ordered = new LinkedHashMap<>();
        options.forEach(option -> ordered.putIfAbsent(stringify(option.id()), option));
        return List.copyOf(ordered.values());
    }

    private String stringify(Object value) {
        return value == null ? "null" : String.valueOf(value);
    }

    private String stringifyNullable(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private void putIfNotNull(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private void putIfNotBlank(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
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

    private void applyEntityLookupSelections(
            CriteriaQuery<Tuple> query,
            Root<?> root,
            Path<?> valuePath,
            Path<?> labelPath,
            OptionSourceDescriptor descriptor,
            Path<?> metadataSortPath
    ) {
        EntityLookupDescriptor lookup = descriptor.entityLookup();
        List<Selection<?>> selections = new ArrayList<>();
        List<Path<?>> selectedPaths = new ArrayList<>();
        selections.add(valuePath.alias("optionValue"));
        selectedPaths.add(valuePath);
        selections.add(labelPath.alias("optionLabel"));
        selectedPaths.add(labelPath);
        addSelection(selections, selectedPaths, root, lookup.codePropertyPath(), "lookupCode");
        addSelection(selections, selectedPaths, root, lookup.statusPropertyPath(), "lookupStatus");
        addSelection(selections, selectedPaths, root, lookup.disabledPropertyPath(), "lookupDisabled");
        addSelection(selections, selectedPaths, root, lookup.disabledReasonPropertyPath(), "lookupDisabledReason");
        LookupSelectionPolicy policy = lookup.selectionPolicy();
        if (policy != null) {
            addSelection(selections, selectedPaths, root, policy.selectablePropertyPath(), "lookupSelectable");
            if (lookup.statusPropertyPath() == null) {
                addSelection(selections, selectedPaths, root, policy.statusPropertyPath(), "lookupStatus");
            }
        }
        for (int index = 0; index < lookup.descriptionPropertyPaths().size(); index++) {
            addSelection(selections, selectedPaths, root, lookup.descriptionPropertyPaths().get(index), "lookupDescription" + index);
        }
        if (metadataSortPath != null && selectedPaths.stream().noneMatch(path -> samePath(path, metadataSortPath))) {
            selections.add(metadataSortPath.alias("lookupSortField"));
        }
        query.multiselect(selections);
    }

    private void addSelection(
            List<Selection<?>> selections,
            List<Path<?>> selectedPaths,
            Root<?> root,
            String propertyPath,
            String alias
    ) {
        if (propertyPath != null && !propertyPath.isBlank()) {
            Path<?> resolvedPath = resolvePath(root, propertyPath);
            if (selectedPaths.stream().anyMatch(path -> samePath(path, resolvedPath))) {
                return;
            }
            selections.add(resolvedPath.alias(alias));
            selectedPaths.add(resolvedPath);
        }
    }

    private boolean samePath(Path<?> left, Path<?> right) {
        return left == right || (left != null && right != null && left.toString().equals(right.toString()));
    }

    private Object coerceValue(String raw, Class<?> targetType) {
        if (raw == null) {
            return null;
        }
        if (targetType == null || targetType == Object.class || targetType == String.class) {
            return raw;
        }
        if (targetType == BigDecimal.class) {
            return new BigDecimal(raw);
        }
        if (targetType == BigInteger.class) {
            return new BigInteger(raw);
        }
        if (targetType == Short.class || targetType == short.class) {
            return Short.valueOf(raw);
        }
        if (targetType == Byte.class || targetType == byte.class) {
            return Byte.valueOf(raw);
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
        if (targetType == UUID.class) {
            return UUID.fromString(raw);
        }
        if (targetType == LocalDate.class) {
            return LocalDate.parse(raw);
        }
        if (targetType == LocalDateTime.class) {
            return LocalDateTime.parse(raw);
        }
        if (targetType == Instant.class) {
            return Instant.parse(raw);
        }
        if (targetType == OffsetDateTime.class) {
            return OffsetDateTime.parse(raw);
        }
        if (targetType == ZonedDateTime.class) {
            return ZonedDateTime.parse(raw);
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
