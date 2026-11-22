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
import org.praxisplatform.uischema.id.CompositeIdSupport;
import org.praxisplatform.uischema.id.JpaCriteriaPathSupport;
import org.praxisplatform.uischema.options.EntityLookupDescriptor;
import org.praxisplatform.uischema.options.EntityLookupOptionMapper;
import org.praxisplatform.uischema.options.LookupFilterDefinition;
import org.praxisplatform.uischema.options.LookupFilterRequest;
import org.praxisplatform.uischema.options.LookupFilteringDescriptor;
import org.praxisplatform.uischema.options.LookupSearchStrategyDefinition;
import org.praxisplatform.uischema.options.LookupDisplayDescriptor;
import org.praxisplatform.uischema.options.LookupDisplayFieldDescriptor;
import org.praxisplatform.uischema.options.LookupSelectionPolicy;
import org.praxisplatform.uischema.options.LookupSortOption;
import org.praxisplatform.uischema.options.OptionSourceDescriptor;
import org.praxisplatform.uischema.options.OptionSourceType;
import org.praxisplatform.uischema.options.service.OptionSourceQueryExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.util.Collection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * JPA executor for metadata-driven option sources.
 */
public class JpaOptionSourceQueryExecutor implements OptionSourceQueryExecutor {

    private static final Logger log = LoggerFactory.getLogger(JpaOptionSourceQueryExecutor.class);
    private static final int MAX_SEARCH_TOKENS = 8;
    private final Set<String> loggedUnmappedPaths = ConcurrentHashMap.newKeySet();

    private record ResolvedLabel(Path<?> path, String entityPropertyPath) {
    }

    @Override
    public <E> Page<OptionDTO<Object>> filterOptions(
            EntityManager entityManager,
            Class<E> entityClass,
            Specification<E> specification,
            Object filterPayload,
            OptionSourceDescriptor descriptor,
            String search,
            List<LookupFilterRequest> filters,
            String sortKey,
            Pageable pageable,
            Collection<Object> includeIds
    ) {
        return filterOptions(
                entityManager,
                entityClass,
                specification,
                filterPayload,
                descriptor,
                search,
                null,
                filters,
                sortKey,
                pageable,
                includeIds
        );
    }

    @Override
    public <E> Page<OptionDTO<Object>> filterOptions(
            EntityManager entityManager,
            Class<E> entityClass,
            Specification<E> specification,
            Object filterPayload,
            OptionSourceDescriptor descriptor,
            String search,
            String searchStrategy,
            List<LookupFilterRequest> filters,
            String sortKey,
            Pageable pageable,
            Collection<Object> includeIds
    ) {
        ensureSupported(descriptor);
        LookupSearchStrategyDefinition resolvedStrategy = resolveEffectiveSearchStrategy(descriptor, search, searchStrategy);
        String effectiveSearch = resolvedStrategy != null && search != null
                ? resolvedStrategy.normalizeSearch(search)
                : search;

        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root<E> root = query.from(entityClass);
        Path<?> valuePath = resolveValuePath(root, descriptor);
        ResolvedLabel resolvedLabel = resolveLabel(root, descriptor);
        Path<?> labelPath = resolvedLabel.path();
        boolean richResourceEntity = isRichResourceEntity(descriptor);
        LookupSortOption explicitSortOption = resolveSortOptionOrNull(descriptor, sortKey);
        LookupSortOption defaultSortOption = resolveDefaultSortOption(descriptor);
        Path<?> metadataSortPath = resolveMetadataSortPath(root, descriptor, pageable.getSort(), explicitSortOption, defaultSortOption);
        boolean relaxDistinctForMetadataSort = metadataSortPath != null;

        Predicate predicate = applyPredicate(specification, root, query, cb);
        Predicate notNullPredicate = JpaCriteriaPathSupport.buildIsNotNullPredicate(cb, valuePath);
        Predicate searchPredicate = buildSearchPredicate(cb, root, labelPath, descriptor, resolvedStrategy, effectiveSearch);
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
            applyEntityLookupSelections(cb, query, root, valuePath, labelPath, descriptor, metadataSortPath);
        } else {
            applyOptionSelections(query, valuePath, labelPath);
        }
        query.distinct(!relaxDistinctForMetadataSort);
        query.orderBy(resolveOrders(cb, root, valuePath, labelPath, descriptor, pageable.getSort(), sortKey));

        TypedQuery<Tuple> typedQuery = entityManager.createQuery(query);
        typedQuery.setFirstResult((int) pageable.getOffset());
        typedQuery.setMaxResults(pageable.getPageSize());

        Class<?> valueType = valuePath.getJavaType();
        List<OptionDTO<Object>> pageContent = typedQuery.getResultList().stream()
                .map(tuple -> richResourceEntity
                        ? toEntityLookupOption(tuple, descriptor, valueType, entityClass, resolvedLabel.entityPropertyPath())
                        : toOption(tuple, valueType, descriptor, entityClass, resolvedLabel.entityPropertyPath()))
                .filter(option -> option.id() != null)
                .toList();
        pageContent = dedupeOptions(pageContent);

        long total = countDistinct(entityManager, entityClass, specification, descriptor, resolvedStrategy, effectiveSearch, filters);
        List<OptionDTO<Object>> merged = mergeIncludedOptions(
                pageContent,
                byIdsOptions(
                        entityManager,
                        entityClass,
                        specification,
                        filterPayload,
                        descriptor,
                        filters,
                        includeIds == null ? List.of() : includeIds
                )
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
        return byIdsOptions(entityManager, entityClass, null, null, descriptor, ids);
    }

    @Override
    public <E> List<OptionDTO<Object>> byIdsOptions(
            EntityManager entityManager,
            Class<E> entityClass,
            Specification<E> specification,
            Object filterPayload,
            OptionSourceDescriptor descriptor,
            List<LookupFilterRequest> filters,
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
        ResolvedLabel resolvedLabel = resolveLabel(root, descriptor);
        Path<?> labelPath = resolvedLabel.path();
        boolean richResourceEntity = isRichResourceEntity(descriptor);
        Class<?> valueType = valuePath.getJavaType();

        List<Object> coercedIds = ids.stream()
                .filter(Objects::nonNull)
                .map(Object::toString)
                .map(raw -> coerceValue(raw, valueType))
                .toList();

        if (richResourceEntity) {
            applyEntityLookupSelections(cb, query, root, valuePath, labelPath, descriptor, null);
        } else {
            applyOptionSelections(query, valuePath, labelPath);
        }
        Predicate idPredicate = JpaCriteriaPathSupport.buildInPredicate(cb, valuePath, coercedIds);
        Predicate filterPredicate = applyPredicate(specification, root, query, cb);
        Predicate structuredFilterPredicate = buildStructuredFilterPredicate(cb, root, descriptor, filters);
        Predicate mergedPredicate = mergePredicates(cb, mergePredicates(cb, idPredicate, filterPredicate), structuredFilterPredicate);
        query.where(mergedPredicate).distinct(true);

        Map<String, OptionDTO<Object>> byKey = entityManager.createQuery(query)
                .getResultList()
                .stream()
                .map(tuple -> richResourceEntity
                        ? toEntityLookupOption(tuple, descriptor, valueType, entityClass, resolvedLabel.entityPropertyPath())
                        : toOption(tuple, valueType, descriptor, entityClass, resolvedLabel.entityPropertyPath()))
                .collect(Collectors.toMap(option -> stringify(option.id()), option -> option, (left, right) -> left, LinkedHashMap::new));

        return ids.stream()
                .map(id -> byKey.get(stringify(id)))
                .filter(Objects::nonNull)
                .toList();
    }

    private void ensureSupported(OptionSourceDescriptor descriptor) {
        if (descriptor.type() == OptionSourceType.DISTINCT_DIMENSION
                || descriptor.type() == OptionSourceType.CATEGORICAL_BUCKET
                || descriptor.type() == OptionSourceType.LIGHT_LOOKUP
                || isRichResourceEntity(descriptor)) {
            return;
        }
        if (descriptor.type() != OptionSourceType.DISTINCT_DIMENSION
                && descriptor.type() != OptionSourceType.CATEGORICAL_BUCKET
                && descriptor.type() != OptionSourceType.LIGHT_LOOKUP) {
            throw new UnsupportedOperationException("Option source type not implemented: " + descriptor.type());
        }
    }

    private boolean isRichResourceEntity(OptionSourceDescriptor descriptor) {
        return descriptor.type() == OptionSourceType.RESOURCE_ENTITY && descriptor.entityLookup() != null;
    }

    private LookupSearchStrategyDefinition resolveEffectiveSearchStrategy(
            OptionSourceDescriptor descriptor,
            String search,
            String searchStrategy
    ) {
        LookupFilteringDescriptor filtering = descriptor.effectiveFiltering();
        if (filtering != null) {
            return filtering.resolveSearchStrategy(searchStrategy, search);
        }
        if (searchStrategy != null && !searchStrategy.isBlank()) {
            throw new IllegalArgumentException("Lookup searchStrategy is not declared for this option source.");
        }
        return null;
    }

    private <E> long countDistinct(
            EntityManager entityManager,
            Class<E> entityClass,
            Specification<E> specification,
            OptionSourceDescriptor descriptor,
            LookupSearchStrategyDefinition searchStrategy,
            String search,
            List<LookupFilterRequest> filters
    ) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> probeQuery = cb.createTupleQuery();
        Root<E> probeRoot = probeQuery.from(entityClass);
        Class<?> valueType = resolveValuePath(probeRoot, descriptor).getJavaType();
        if (CompositeIdSupport.isCompositeIdType(valueType)) {
            Path<?> valuePath = resolveValuePath(probeRoot, descriptor);
            Predicate predicate = applyPredicate(specification, probeRoot, probeQuery, cb);
            Predicate notNullPredicate = JpaCriteriaPathSupport.buildIsNotNullPredicate(cb, valuePath);
            Predicate searchPredicate = buildSearchPredicate(cb, probeRoot, resolveLabelPath(probeRoot, descriptor), descriptor, searchStrategy, search);
            Predicate structuredFilterPredicate = buildStructuredFilterPredicate(cb, probeRoot, descriptor, filters);
            Predicate mergedPredicate = mergePredicates(
                    cb,
                    mergePredicates(cb, mergePredicates(cb, predicate, notNullPredicate), searchPredicate),
                    structuredFilterPredicate
            );
            if (mergedPredicate != null) {
                probeQuery.where(mergedPredicate);
            }
            List<Selection<?>> compSelections = CompositeIdSupport.descriptors(valueType).stream()
                    .<Selection<?>>map(comp -> valuePath.get(comp.name()))
                    .toList();
            probeQuery.multiselect(compSelections).distinct(true);
            return entityManager.createQuery(probeQuery).getResultList().size();
        }

        CriteriaQuery<Long> countQuery = cb.createQuery(Long.class);
        Root<E> root = countQuery.from(entityClass);
        Path<?> valuePath = resolveValuePath(root, descriptor);

        Predicate predicate = applyPredicate(specification, root, countQuery, cb);
        Predicate notNullPredicate = JpaCriteriaPathSupport.buildIsNotNullPredicate(cb, valuePath);
        Predicate searchPredicate = buildSearchPredicate(cb, root, resolveLabelPath(root, descriptor), descriptor, searchStrategy, search);
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
            LookupSearchStrategyDefinition searchStrategy,
            String search
    ) {
        if (search == null || search.isBlank() || !descriptor.policy().allowSearch()
                || "none".equals(descriptor.policy().searchMode())) {
            return null;
        }
        String normalized = search.trim();
        int minSearchChars = searchStrategy != null ? searchStrategy.minSearchChars() : descriptor.policy().minSearchChars();
        if (normalized.length() < minSearchChars) {
            return null;
        }
        List<Path<?>> searchPaths = resolveSearchPaths(root, labelPath, descriptor);
        String searchMode = descriptor.policy().searchMode();
        boolean isDocumentStrategy = searchStrategy != null && "normalized-document".equals(searchStrategy.kind());
        boolean isDigitsFormat = searchStrategy != null && "digits".equals(searchStrategy.inputFormat());
        boolean isBusinessCode = searchStrategy != null && "business-code".equals(searchStrategy.kind());
        boolean tokenize = !"exact".equals(searchMode)
                && !"none".equals(searchMode)
                && !isDocumentStrategy
                && !isDigitsFormat
                && !isBusinessCode;

        String lowered = normalized.toLowerCase(Locale.ROOT);
        List<String> tokens = tokenize
                ? Arrays.stream(lowered.split("\\s+"))
                        .filter(token -> !token.isBlank())
                        .collect(Collectors.toCollection(LinkedHashSet::new))
                        .stream()
                        .limit(MAX_SEARCH_TOKENS)
                        .toList()
                : List.of(lowered);
        if (tokens.isEmpty()) {
            return null;
        }

        List<Predicate> perTokenPredicates = new ArrayList<>(tokens.size());
        for (String token : tokens) {
            List<Predicate> pathPredicates = searchPaths.stream()
                    .map(path -> buildSearchPredicateForPath(cb, path, descriptor, searchStrategy, token))
                    .filter(Objects::nonNull)
                    .toList();
            if (pathPredicates.isEmpty()) {
                if (tokens.size() == 1) {
                    return null;
                }
                return cb.disjunction();
            }
            perTokenPredicates.add(pathPredicates.size() == 1
                    ? pathPredicates.getFirst()
                    : cb.or(pathPredicates.toArray(Predicate[]::new)));
        }
        return perTokenPredicates.size() == 1
                ? perTokenPredicates.getFirst()
                : cb.and(perTokenPredicates.toArray(Predicate[]::new));
    }

    private Predicate buildSearchPredicateForPath(
            CriteriaBuilder cb,
            Path<?> path,
            OptionSourceDescriptor descriptor,
            LookupSearchStrategyDefinition searchStrategy,
            String lowered
    ) {
        Class<?> javaType = path.getJavaType();
        if (javaType != null && javaType != String.class) {
            Expression<String> text = cb.lower(cb.concat(path.as(String.class), ""));
            return switch (descriptor.policy().searchMode()) {
                case "none" -> null;
                case "exact" -> {
                    try {
                        Object coerced = coerceValue(lowered, javaType);
                        yield coerced == null ? null : cb.equal(path, coerced);
                    } catch (RuntimeException ignored) {
                        yield null;
                    }
                }
                case "starts-with" -> cb.like(text, escapeLike(lowered) + "%", '\\');
                default -> {
                    try {
                        Object coerced = coerceValue(lowered, javaType);
                        if (coerced != null) {
                            yield cb.or(cb.equal(path, coerced), cb.like(text, "%" + escapeLike(lowered) + "%", '\\'));
                        }
                    } catch (RuntimeException ignored) {
                        // Fall through to string concat comparison when the search token is not a full literal of javaType.
                    }
                    yield cb.like(text, "%" + escapeLike(lowered) + "%", '\\');
                }
            };
        }
        Expression<String> text = cb.lower(path.as(String.class));
        if (searchStrategy != null && "normalized-document".equals(searchStrategy.kind())) {
            String searchMode = descriptor.policy().searchMode();
            if ("none".equals(searchMode)) {
                return null;
            }
            Expression<String> normalizedText = normalizeDocumentExpression(cb, text);
            Predicate normalizedPredicate = matchSearchExpression(cb, normalizedText, searchMode, lowered);
            Predicate rawPredicate = matchSearchExpression(cb, text, searchMode, lowered);
            return cb.or(normalizedPredicate, rawPredicate);
        }
        return matchSearchExpression(cb, text, descriptor.policy().searchMode(), lowered);
    }

    private Expression<String> normalizeDocumentExpression(CriteriaBuilder cb, Expression<String> lowerTextExpr) {
        Expression<String> current = lowerTextExpr;
        for (String separator : List.of(".", "-", "/", " ")) {
            current = cb.function("replace", String.class, current, cb.literal(separator), cb.literal(""));
        }
        return current;
    }

    private Predicate matchSearchExpression(
            CriteriaBuilder cb,
            Expression<String> text,
            String searchMode,
            String lowered
    ) {
        return switch (searchMode) {
            case "none" -> null;
            case "exact" -> cb.equal(text, lowered);
            case "starts-with" -> cb.like(text, escapeLike(lowered) + "%", '\\');
            default -> cb.like(text, "%" + escapeLike(lowered) + "%", '\\');
        };
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    private List<Path<?>> resolveSearchPaths(Root<?> root, Path<?> labelPath, OptionSourceDescriptor descriptor) {
        EntityLookupDescriptor lookup = descriptor.entityLookup();
        if (lookup == null || lookup.searchPropertyPaths().isEmpty()) {
            return List.of(labelPath);
        }
        List<Path<?>> paths = new ArrayList<>();
        for (String searchPath : lookup.searchPropertyPaths()) {
            Path<?> resolvedPath = tryResolveDescriptorPath(root, descriptor, searchPath, "searchPropertyPaths");
            if (resolvedPath != null) {
                paths.add(resolvedPath);
            }
        }
        if (labelPath != null && paths.stream().noneMatch(path -> samePath(path, labelPath))) {
            paths.add(labelPath);
        }
        return paths.isEmpty() ? List.of(labelPath) : paths;
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
            Path<?> sortPath = tryResolveDescriptorPath(root, descriptor, sortOption.field(), "sortOption:" + sortOption.key());
            Path<?> effectiveSortPath = sortPath != null ? sortPath : labelPath;
            return JpaCriteriaPathSupport.buildOrders(cb, effectiveSortPath, !"desc".equals(sortOption.direction()))
                    .toArray(Order[]::new);
        }
        if (sort != null && sort.isSorted()) {
            List<Order> mapped = sort.stream()
                    .filter(order -> "label".equals(order.getProperty()) || "id".equals(order.getProperty()))
                    .flatMap(order -> {
                        Path<?> sortPath = "id".equals(order.getProperty()) ? valuePath : labelPath;
                        return JpaCriteriaPathSupport.buildOrders(cb, sortPath, order.isAscending()).stream();
                    })
                    .toList();
            if (!mapped.isEmpty()) {
                return mapped.toArray(Order[]::new);
            }
        }
        LookupSortOption defaultSortOption = resolveDefaultSortOption(descriptor);
        if (defaultSortOption != null) {
            Path<?> sortPath = tryResolveDescriptorPath(root, descriptor, defaultSortOption.field(), "defaultSortOption:" + defaultSortOption.key());
            Path<?> effectiveSortPath = sortPath != null ? sortPath : labelPath;
            return JpaCriteriaPathSupport.buildOrders(cb, effectiveSortPath, !"desc".equals(defaultSortOption.direction()))
                    .toArray(Order[]::new);
        }
        Path<?> defaultSortPath = "id".equalsIgnoreCase(descriptor.policy().defaultSort()) ? valuePath : labelPath;
        return JpaCriteriaPathSupport.buildOrders(cb, defaultSortPath, true).toArray(Order[]::new);
    }

    private LookupSortOption resolveSortOptionOrNull(OptionSourceDescriptor descriptor, String sortKey) {
        if (sortKey == null || sortKey.isBlank()) {
            return null;
        }
        LookupFilteringDescriptor filtering = descriptor.effectiveFiltering();
        if (filtering == null || filtering.sortOptions().isEmpty()) {
            return null;
        }
        return filtering.sortOptions().stream()
                .filter(option -> sortKey.equals(option.key()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported entity lookup sort key: " + sortKey));
    }

    private LookupSortOption resolveDefaultSortOption(OptionSourceDescriptor descriptor) {
        LookupFilteringDescriptor filtering = descriptor.effectiveFiltering();
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
            return tryResolveDescriptorPath(root, descriptor, effectiveSortOption.field(), "metadataSort:" + effectiveSortOption.key());
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
        LookupFilteringDescriptor filtering = descriptor.effectiveFiltering();
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
            Path<?> resolvedPath = resolvePath(root, definition.effectiveEntityPropertyPath());
            predicates.add(buildPredicateForStructuredFilter(cb, resolvedPath, definition, filter));
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
            case "contains" -> cb.like(cb.lower(path.as(String.class)),
                    "%" + escapeLike(requiredTextValue(filter).toLowerCase(Locale.ROOT)) + "%", '\\');
            case "startsWith" -> cb.like(cb.lower(path.as(String.class)),
                    escapeLike(requiredTextValue(filter).toLowerCase(Locale.ROOT)) + "%", '\\');
            case "equals" -> {
                Object coerced = coerceValue(requiredSingleValue(filter), path.getJavaType());
                if ("text".equals(definition.type())) {
                    yield cb.equal(cb.lower(path.as(String.class)), String.valueOf(coerced).toLowerCase(Locale.ROOT));
                }
                yield JpaCriteriaPathSupport.buildEqualPredicate(cb, path, coerced);
            }
            case "in" -> {
                List<Object> values = requireMultipleValues(filter).stream()
                        .map(value -> coerceValue(value == null ? null : String.valueOf(value), path.getJavaType()))
                        .toList();
                yield JpaCriteriaPathSupport.buildInPredicate(cb, path, values);
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

    private OptionDTO<Object> toOption(
            Tuple tuple,
            Class<?> valueType,
            OptionSourceDescriptor descriptor,
            Class<?> entityClass,
            String labelGovernancePath
    ) {
        Object value = extractOptionValue(tuple, valueType);
        Object label = null;
        try {
            label = tuple.get("optionLabel");
        } catch (IllegalArgumentException ignored) {
            // Same-path option sources select only the value column and reuse it as label.
        }
        return EntityLookupOptionMapper.mapFromResolved(
                descriptor,
                value,
                label,
                labelGovernancePath,
                null,
                entityClass,
                EntityLookupOptionMapper.GovernanceMaskingMode.AUTO
        );
    }

    private OptionDTO<Object> toEntityLookupOption(
            Tuple tuple,
            OptionSourceDescriptor descriptor,
            Class<?> valueType,
            Class<?> entityClass,
            String labelGovernancePath
    ) {
        Object value = extractOptionValue(tuple, valueType);
        Object label = tupleValue(tuple, "optionLabel");
        EntityLookupDescriptor lookup = descriptor.entityLookup();

        Object code = tupleValue(tuple, "lookupCode");
        List<Object> descriptions = new ArrayList<>(lookup.descriptionPropertyPaths().size());
        for (int index = 0; index < lookup.descriptionPropertyPaths().size(); index++) {
            descriptions.add(tupleValue(tuple, "lookupDescription" + index));
        }
        Object status = tupleValue(tuple, "lookupStatus");
        Object disabledObj = tupleValue(tuple, "lookupDisabled");
        Boolean disabled = disabledObj instanceof Boolean b ? b : null;
        String disabledReason = stringifyNullable(tupleValue(tuple, "lookupDisabledReason"));
        Object selectableObj = tupleValue(tuple, "lookupSelectable");
        Boolean selectable = selectableObj instanceof Boolean b ? b : null;

        LookupDisplayDescriptor display = lookup.display();
        List<Object> badgeValues = List.of();
        List<Object> richFieldValues = List.of();
        if (display != null) {
            if (!display.badgePropertyPaths().isEmpty()) {
                List<Object> badges = new ArrayList<>(display.badgePropertyPaths().size());
                for (int index = 0; index < display.badgePropertyPaths().size(); index++) {
                    badges.add(tupleValue(tuple, "lookupBadge" + index));
                }
                badgeValues = badges;
            }
            if (!display.fields().isEmpty()) {
                List<Object> richValues = new ArrayList<>(display.fields().size());
                for (int index = 0; index < display.fields().size(); index++) {
                    richValues.add(tupleValue(tuple, "lookupRichField" + index));
                }
                richFieldValues = richValues;
            }
        }

        EntityLookupOptionMapper.ResolvedLookupValues resolvedValues =
                new EntityLookupOptionMapper.ResolvedLookupValues(
                        code, descriptions, status, disabled, disabledReason, selectable, badgeValues, richFieldValues);

        return EntityLookupOptionMapper.mapFromResolved(
                descriptor,
                value,
                label,
                labelGovernancePath,
                resolvedValues,
                entityClass,
                EntityLookupOptionMapper.GovernanceMaskingMode.AUTO
        );
    }

    private Object extractOptionValue(Tuple tuple, Class<?> valueType) {
        if (CompositeIdSupport.isCompositeIdType(valueType)) {
            List<CompositeIdSupport.ComponentDescriptor> components = CompositeIdSupport.descriptors(valueType);
            if (!components.isEmpty()) {
                List<Object> componentValues = new ArrayList<>(components.size());
                for (int i = 0; i < components.size(); i++) {
                    componentValues.add(tuple.get("optionValue__" + i));
                }
                return CompositeIdSupport.fromComponentValues(valueType, componentValues);
            }
        }
        return tuple.get("optionValue");
    }

    private Object tupleValue(Tuple tuple, String alias) {
        try {
            return tuple.get(alias);
        } catch (IllegalArgumentException ignored) {
            return compositeTupleValue(tuple, alias);
        }
    }

    private String compositeTupleValue(Tuple tuple, String alias) {
        List<String> parts = new ArrayList<>();
        for (int index = 0; ; index++) {
            try {
                Object part = tuple.get(alias + "__" + index);
                if (part == null) {
                    return null;
                }
                parts.add(String.valueOf(part).replace("%", "%25").replace(":", "%3A"));
            } catch (IllegalArgumentException ignored) {
                break;
            }
        }
        return parts.isEmpty() ? null : String.join(CompositeIdSupport.SEPARATOR, parts);
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
        return CompositeIdSupport.stringify(value);
    }

    private String stringifyNullable(Object value) {
        return CompositeIdSupport.stringifyNullable(value);
    }

    private Path<?> resolveValuePath(Root<?> root, OptionSourceDescriptor descriptor) {
        return resolvePath(root, descriptor.valuePropertyPath() != null ? descriptor.valuePropertyPath() : descriptor.propertyPath());
    }

    private ResolvedLabel resolveLabel(Root<?> root, OptionSourceDescriptor descriptor) {
        String preferredLabel = descriptor.labelPropertyPath() != null ? descriptor.labelPropertyPath() : descriptor.propertyPath();
        ResolvedPathWithName resolved = tryResolveDescriptorPathWithName(root, descriptor, preferredLabel, "labelPropertyPath");
        if (resolved != null) {
            return new ResolvedLabel(resolved.path(), resolved.entityPropertyPath());
        }
        if (descriptor.labelPropertyPath() != null
                && descriptor.propertyPath() != null
                && !descriptor.propertyPath().equals(descriptor.labelPropertyPath())) {
            ResolvedPathWithName fallback = tryResolveDescriptorPathWithName(root, descriptor, descriptor.propertyPath(), "propertyPath");
            if (fallback != null) {
                return new ResolvedLabel(fallback.path(), fallback.entityPropertyPath());
            }
        }
        EntityLookupDescriptor lookup = descriptor.entityLookup();
        if (lookup != null) {
            if (lookup.display() != null && lookup.display().primaryPropertyPath() != null) {
                ResolvedPathWithName primaryFallback = tryResolveDescriptorPathWithName(
                        root, descriptor, lookup.display().primaryPropertyPath(), "display.primaryPropertyPath");
                if (primaryFallback != null) {
                    return new ResolvedLabel(primaryFallback.path(), primaryFallback.entityPropertyPath());
                }
            }
            for (String descPath : lookup.descriptionPropertyPaths()) {
                ResolvedPathWithName descFallback = tryResolveDescriptorPathWithName(
                        root, descriptor, descPath, "descriptionPropertyPaths");
                if (descFallback != null) {
                    return new ResolvedLabel(descFallback.path(), descFallback.entityPropertyPath());
                }
            }
        }
        String valueProp = descriptor.valuePropertyPath() != null ? descriptor.valuePropertyPath() : descriptor.propertyPath();
        return new ResolvedLabel(resolveValuePath(root, descriptor), valueProp);
    }

    private Path<?> resolveLabelPath(Root<?> root, OptionSourceDescriptor descriptor) {
        return resolveLabel(root, descriptor).path();
    }

    private record ResolvedPathWithName(Path<?> path, String entityPropertyPath) {
    }

    private Path<?> tryResolveDescriptorPath(
            Root<?> root,
            OptionSourceDescriptor descriptor,
            String propertyPath,
            String usageContext
    ) {
        ResolvedPathWithName resolved = tryResolveDescriptorPathWithName(root, descriptor, propertyPath, usageContext);
        return resolved == null ? null : resolved.path();
    }

    private ResolvedPathWithName tryResolveDescriptorPathWithName(
            Root<?> root,
            OptionSourceDescriptor descriptor,
            String propertyPath,
            String usageContext
    ) {
        if (propertyPath == null || propertyPath.isBlank()) {
            return null;
        }
        Path<?> direct = JpaCriteriaPathSupport.tryResolvePath(root, propertyPath, true);
        if (direct != null) {
            return new ResolvedPathWithName(direct, propertyPath);
        }
        EntityLookupDescriptor lookup = descriptor == null ? null : descriptor.entityLookup();
        if (lookup != null && lookup.display() != null) {
            for (LookupDisplayFieldDescriptor field : lookup.display().fields()) {
                if (propertyPath.equals(field.key())
                        && field.propertyPath() != null
                        && !propertyPath.equals(field.propertyPath())) {
                    Path<?> mapped = JpaCriteriaPathSupport.tryResolvePath(root, field.propertyPath(), true);
                    if (mapped != null) {
                        return new ResolvedPathWithName(mapped, field.propertyPath());
                    }
                }
            }
        }
        logUnmappedPathOnce(root, propertyPath, usageContext);
        return null;
    }

    private void applyOptionSelections(CriteriaQuery<Tuple> query, Path<?> valuePath, Path<?> labelPath) {
        List<Selection<?>> selections = new ArrayList<>();
        List<Path<?>> selectedPaths = new ArrayList<>();
        List<String> selectedAliases = new ArrayList<>();
        addValueSelections(selections, selectedPaths, selectedAliases, valuePath);
        if (!samePath(valuePath, labelPath)) {
            selections.add(labelPath.alias("optionLabel"));
        }
        query.multiselect(selections);
    }

    private void applyEntityLookupSelections(
            CriteriaBuilder cb,
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
        List<String> selectedAliases = new ArrayList<>();
        addValueSelections(selections, selectedPaths, selectedAliases, valuePath);
        selections.add(labelPath.alias("optionLabel"));
        selectedPaths.add(labelPath);
        selectedAliases.add("optionLabel");
        addSelection(cb, selections, selectedPaths, selectedAliases, root, descriptor, lookup.codePropertyPath(), "lookupCode");
        addSelection(cb, selections, selectedPaths, selectedAliases, root, descriptor, lookup.statusPropertyPath(), "lookupStatus");
        addSelection(cb, selections, selectedPaths, selectedAliases, root, descriptor, lookup.disabledPropertyPath(), "lookupDisabled");
        addSelection(cb, selections, selectedPaths, selectedAliases, root, descriptor, lookup.disabledReasonPropertyPath(), "lookupDisabledReason");
        LookupSelectionPolicy policy = lookup.selectionPolicy();
        if (policy != null) {
            addSelection(cb, selections, selectedPaths, selectedAliases, root, descriptor, policy.selectablePropertyPath(), "lookupSelectable");
            if (!selectedAliases.contains("lookupStatus")) {
                addSelection(cb, selections, selectedPaths, selectedAliases, root, descriptor, policy.statusPropertyPath(), "lookupStatus");
            }
        }
        for (int index = 0; index < lookup.descriptionPropertyPaths().size(); index++) {
            addSelection(cb, selections, selectedPaths, selectedAliases, root, descriptor, lookup.descriptionPropertyPaths().get(index), "lookupDescription" + index);
        }
        LookupDisplayDescriptor display = lookup.display();
        if (display != null) {
            for (int index = 0; index < display.fields().size(); index++) {
                addSelection(cb, selections, selectedPaths, selectedAliases, root, descriptor, display.fields().get(index).propertyPath(), "lookupRichField" + index);
            }
            for (int index = 0; index < display.badgePropertyPaths().size(); index++) {
                addSelection(cb, selections, selectedPaths, selectedAliases, root, descriptor, display.badgePropertyPaths().get(index), "lookupBadge" + index);
            }
        }
        if (metadataSortPath != null && selectedPaths.stream().noneMatch(path -> samePath(path, metadataSortPath))) {
            selections.add(metadataSortPath.alias("lookupSortField"));
        }
        query.multiselect(selections);
    }

    private void addValueSelections(
            List<Selection<?>> selections,
            List<Path<?>> selectedPaths,
            List<String> selectedAliases,
            Path<?> valuePath
    ) {
        Class<?> valueType = valuePath.getJavaType();
        if (CompositeIdSupport.isCompositeIdType(valueType)) {
            List<CompositeIdSupport.ComponentDescriptor> components = CompositeIdSupport.descriptors(valueType);
            if (!components.isEmpty()) {
                for (int i = 0; i < components.size(); i++) {
                    Path<?> compPath = valuePath.get(components.get(i).name());
                    String alias = "optionValue__" + i;
                    selections.add(compPath.alias(alias));
                    selectedPaths.add(compPath);
                    selectedAliases.add(alias);
                }
                return;
            }
        }
        selections.add(valuePath.alias("optionValue"));
        selectedPaths.add(valuePath);
        selectedAliases.add("optionValue");
    }

    private void addSelection(
            CriteriaBuilder cb,
            List<Selection<?>> selections,
            List<Path<?>> selectedPaths,
            List<String> selectedAliases,
            Root<?> root,
            String propertyPath,
            String alias
    ) {
        addSelection(cb, selections, selectedPaths, selectedAliases, root, null, propertyPath, alias);
    }

    private void addSelection(
            CriteriaBuilder cb,
            List<Selection<?>> selections,
            List<Path<?>> selectedPaths,
            List<String> selectedAliases,
            Root<?> root,
            OptionSourceDescriptor descriptor,
            String propertyPath,
            String alias
    ) {
        if (propertyPath != null && !propertyPath.isBlank()) {
            if (selectedAliases.contains(alias)) {
                return;
            }
            Path<?> resolvedPath = tryResolveDescriptorPath(root, descriptor, propertyPath, "selection:" + alias);
            if (resolvedPath == null) {
                return;
            }
            Class<?> pathType = resolvedPath.getJavaType();
            if (CompositeIdSupport.isCompositeIdType(pathType)) {
                List<CompositeIdSupport.ComponentDescriptor> components = CompositeIdSupport.descriptors(pathType);
                if (!components.isEmpty()) {
                    for (int i = 0; i < components.size(); i++) {
                        Path<?> compPath = resolvedPath.get(components.get(i).name());
                        String compAlias = alias + "__" + i;
                        selections.add(cb.coalesce(compPath, compPath).alias(compAlias));
                        selectedPaths.add(compPath);
                        selectedAliases.add(compAlias);
                    }
                    selectedPaths.add(resolvedPath);
                    selectedAliases.add(alias);
                    return;
                }
            }
            // Hibernate reuses Path instances. alias() mutates that instance, so the same
            // property in code/description/display must get independent selection nodes.
            // COALESCE(x,x) is value/type/null preserving and leaves predicates/sort paths untouched.
            selections.add(cb.coalesce(resolvedPath, resolvedPath).alias(alias));
            selectedPaths.add(resolvedPath);
            selectedAliases.add(alias);
        }
    }

    private boolean samePath(Path<?> left, Path<?> right) {
        return left == right || (left != null && right != null && left.toString().equals(right.toString()));
    }

    private Object coerceValue(String raw, Class<?> targetType) {
        return CompositeIdSupport.coerceScalar(raw, targetType);
    }

    private Path<?> tryResolvePath(Root<?> root, String propertyPath, String usageContext) {
        if (propertyPath == null || propertyPath.isBlank()) {
            return null;
        }
        Path<?> resolved = JpaCriteriaPathSupport.tryResolvePath(root, propertyPath, true);
        if (resolved == null) {
            logUnmappedPathOnce(root, propertyPath, usageContext);
        }
        return resolved;
    }

    private void logUnmappedPathOnce(Root<?> root, String propertyPath, String usageContext) {
        String entityName = resolveEntityName(root);
        String dedupeKey = entityName + "|" + usageContext + "|" + propertyPath;
        if (loggedUnmappedPaths.add(dedupeKey)) {
            log.debug(
                    "Ignoring non-JPA option-source property path '{}' for entity {} in {}",
                    propertyPath,
                    entityName,
                    usageContext
            );
        }
    }

    private static String resolveEntityName(Root<?> root) {
        if (root == null) {
            return "<null>";
        }
        try {
            if (root.getModel() != null && root.getModel().getName() != null) {
                return root.getModel().getName();
            }
        } catch (RuntimeException ignored) {
            // Bare mocks may omit metamodel
        }
        try {
            if (root.getJavaType() != null) {
                return root.getJavaType().getSimpleName();
            }
        } catch (RuntimeException ignored) {
            // Bare mocks may omit Java type
        }
        return "<unknown>";
    }

    private Path<?> resolvePath(Root<?> root, String propertyPath) {
        return JpaCriteriaPathSupport.resolvePath(root, propertyPath, true);
    }
}
