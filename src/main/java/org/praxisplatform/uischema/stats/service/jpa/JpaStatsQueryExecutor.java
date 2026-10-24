package org.praxisplatform.uischema.stats.service.jpa;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.praxisplatform.uischema.stats.DistributionMode;
import org.praxisplatform.uischema.stats.StatsBucketOrder;
import org.praxisplatform.uischema.stats.StatsDimensionExtraction;
import org.praxisplatform.uischema.stats.StatsFieldDescriptor;
import org.praxisplatform.uischema.stats.StatsMetric;
import org.praxisplatform.uischema.stats.TimeSeriesGranularity;
import org.praxisplatform.uischema.stats.dto.ComparisonPeriodWindow;
import org.praxisplatform.uischema.stats.dto.DistributionBucket;
import org.praxisplatform.uischema.stats.dto.DistributionStatsRequest;
import org.praxisplatform.uischema.stats.dto.DistributionStatsResponse;
import org.praxisplatform.uischema.stats.dto.GroupByBucket;
import org.praxisplatform.uischema.stats.dto.GroupByStatsRequest;
import org.praxisplatform.uischema.stats.dto.GroupByStatsResponse;
import org.praxisplatform.uischema.stats.dto.StatsDimensionRequest;
import org.praxisplatform.uischema.stats.dto.StatsMetricRequest;
import org.praxisplatform.uischema.stats.dto.TimeSeriesPoint;
import org.praxisplatform.uischema.stats.dto.TimeSeriesStatsRequest;
import org.praxisplatform.uischema.stats.dto.TimeSeriesStatsResponse;
import org.praxisplatform.uischema.stats.service.ResolvedStatsDimension;
import org.praxisplatform.uischema.stats.service.ResolvedStatsMetric;
import org.praxisplatform.uischema.stats.service.StatsQueryExecutor;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAccessor;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * JPA Criteria implementation for filtered stats.
 */
public class JpaStatsQueryExecutor implements StatsQueryExecutor {

    @Override
    public <E> GroupByStatsResponse executeGroupBy(
            EntityManager entityManager,
            Class<E> entityClass,
            Specification<E> specification,
            StatsFieldDescriptor groupDescriptor,
            List<ResolvedStatsMetric> resolvedMetrics,
            GroupByStatsRequest<?> request,
            int maxBuckets
    ) {
        List<ResolvedStatsDimension> dimensions = List.of(
                new ResolvedStatsDimension(
                        new StatsDimensionRequest(groupDescriptor.field(), StatsDimensionExtraction.NONE, groupDescriptor.field()),
                        groupDescriptor
                )
        );
        return executeGroupBy(
                entityManager,
                entityClass,
                specification,
                dimensions,
                resolvedMetrics,
                request,
                null,
                maxBuckets,
                Math.max(maxBuckets, 500)
        );
    }

    @Override
    public <E> GroupByStatsResponse executeGroupBy(
            EntityManager entityManager,
            Class<E> entityClass,
            Specification<E> specification,
            List<ResolvedStatsDimension> dimensions,
            List<ResolvedStatsMetric> resolvedMetrics,
            GroupByStatsRequest<?> request,
            ComparisonPeriodWindow window,
            int maxBuckets,
            int maxCells
    ) {
        boolean requiresAdvancedExecution = dimensions.size() > 1
                || dimensions.stream().anyMatch(dim -> dim.extraction().isTemporal())
                || request.hasExplicitDimensions()
                || window != null
                || Boolean.TRUE.equals(request.fillGaps())
                || resolvedMetrics.stream().anyMatch(m -> m.metric().operation() == StatsMetric.AVG_PER_CYCLE_OCCURRENCE);

        if (requiresAdvancedExecution) {
            return executeAdvancedGroupBy(
                    entityManager,
                    entityClass,
                    specification,
                    dimensions,
                    resolvedMetrics,
                    request,
                    window,
                    maxBuckets,
                    maxCells
            );
        }

        StatsFieldDescriptor groupDescriptor = dimensions.get(0).descriptor();
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root<E> root = query.from(entityClass);
        Path<?> groupKeyPath = resolvePath(root, groupDescriptor.keyPropertyPath());
        boolean hasDistinctLabelPath = !Objects.equals(groupDescriptor.keyPropertyPath(), groupDescriptor.labelPropertyPath());
        Path<?> groupLabelPath = hasDistinctLabelPath
                ? resolvePath(root, groupDescriptor.labelPropertyPath())
                : groupKeyPath;
        Expression<Long> countExpression = cb.count(root);
        ResolvedStatsMetric primaryMetric = primaryMetric(request, resolvedMetrics);
        Expression<? extends Number> valueExpression = resolveValueExpression(cb, root, primaryMetric);

        Predicate predicate = specification == null ? null : specification.toPredicate(root, query, cb);
        if (predicate != null) {
            query.where(predicate);
        }

        List<jakarta.persistence.criteria.Selection<?>> selections = new ArrayList<>();
        selections.add(groupKeyPath.alias("groupKey"));
        if (hasDistinctLabelPath) {
            selections.add(groupLabelPath.alias("groupLabel"));
        }
        selections.add(valueExpression.alias("groupValue"));
        selections.add(countExpression.alias("groupCount"));
        for (int index = 0; index < resolvedMetrics.size(); index++) {
            ResolvedStatsMetric resolvedMetric = resolvedMetrics.get(index);
            selections.add(resolveValueExpression(cb, root, resolvedMetric).alias(metricTupleAlias(index)));
        }
        query.multiselect(selections);
        query.groupBy(hasDistinctLabelPath ? List.of(groupKeyPath, groupLabelPath) : List.of(groupKeyPath));
        query.orderBy(resolveOrder(cb, groupKeyPath, valueExpression, request.orderBy()));

        TypedQuery<Tuple> typedQuery = entityManager.createQuery(query);
        int limit = request.limit() == null ? maxBuckets : Math.min(request.limit(), maxBuckets);
        typedQuery.setMaxResults(limit);

        boolean exposeMultiMetricShape = exposesMultiMetricShape(request.metrics());
        List<GroupByBucket> buckets = typedQuery.getResultList().stream()
                .map(tuple -> {
                    Object key = tuple.get("groupKey");
                    Object labelValue = hasDistinctLabelPath ? tuple.get("groupLabel") : key;
                    Number value = (Number) tuple.get("groupValue");
                    long count = ((Number) tuple.get("groupCount")).longValue();
                    return new GroupByBucket(
                            key,
                            labelValue == null ? (key == null ? "null" : String.valueOf(key)) : String.valueOf(labelValue),
                            value,
                            count,
                            exposeMultiMetricShape ? extractMetricValues(tuple, resolvedMetrics) : null
                    );
                })
                .toList();

        return new GroupByStatsResponse(
                groupDescriptor.field(),
                request.primaryMetric(),
                buckets,
                exposeMultiMetricShape ? request.effectiveMetrics() : null
        );
    }

    private <E> GroupByStatsResponse executeAdvancedGroupBy(
            EntityManager entityManager,
            Class<E> entityClass,
            Specification<E> specification,
            List<ResolvedStatsDimension> dimensions,
            List<ResolvedStatsMetric> resolvedMetrics,
            GroupByStatsRequest<?> request,
            ComparisonPeriodWindow window,
            int maxBuckets,
            int maxCells
    ) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root<E> root = query.from(entityClass);

        Predicate predicate = specification == null ? null : specification.toPredicate(root, query, cb);
        if (predicate != null) {
            query.where(predicate);
        }

        List<jakarta.persistence.criteria.Selection<?>> selections = new ArrayList<>();
        Map<String, Integer> selectionIndexByPropertyPath = new LinkedHashMap<>();
        int[] dimKeySelectionIndex = new int[dimensions.size()];
        int[] dimLabelSelectionIndex = new int[dimensions.size()];
        boolean[] hasDistinctLabel = new boolean[dimensions.size()];
        for (int d = 0; d < dimensions.size(); d++) {
            ResolvedStatsDimension dim = dimensions.get(d);
            Path<?> keyPath = resolvePath(root, dim.descriptor().keyPropertyPath());
            if (dim.extraction().requiresTimeComponent() && keyPath.getJavaType() == LocalDate.class) {
                throw new IllegalArgumentException(
                        "Temporal extraction " + dim.extraction() + " requires a timestamp field with time component: " + dim.field()
                );
            }
            dimKeySelectionIndex[d] = registerSelection(root, dim.descriptor().keyPropertyPath(), selections, selectionIndexByPropertyPath);
            if (dim.extraction() == StatsDimensionExtraction.NONE
                    && !Objects.equals(dim.descriptor().keyPropertyPath(), dim.descriptor().labelPropertyPath())) {
                hasDistinctLabel[d] = true;
                dimLabelSelectionIndex[d] = registerSelection(root, dim.descriptor().labelPropertyPath(), selections, selectionIndexByPropertyPath);
            }
        }
        int[] metricSelectionIndex = new int[resolvedMetrics.size()];
        for (int m = 0; m < resolvedMetrics.size(); m++) {
            ResolvedStatsMetric metric = resolvedMetrics.get(m);
            if (metric.descriptor() != null) {
                metricSelectionIndex[m] = registerSelection(root, metric.descriptor().keyPropertyPath(), selections, selectionIndexByPropertyPath);
            } else {
                metricSelectionIndex[m] = -1;
            }
        }
        query.multiselect(selections);

        ZoneId zoneId = resolveExecutionZone(request.timezone(), window);
        List<Tuple> tuples = entityManager.createQuery(query).getResultList();

        Map<CellCoordinateKey, MultiDimensionCellAggregate> cells = new LinkedHashMap<>();
        List<Map<Object, String>> observedLabelsByDimension = new ArrayList<>(dimensions.size());
        for (int d = 0; d < dimensions.size(); d++) {
            observedLabelsByDimension.add(new LinkedHashMap<>());
        }

        for (Tuple tuple : tuples) {
            List<Object> keys = new ArrayList<>(dimensions.size());
            List<String> labels = new ArrayList<>(dimensions.size());
            for (int d = 0; d < dimensions.size(); d++) {
                ResolvedStatsDimension dim = dimensions.get(d);
                Object rawKey = tuple.get(dimKeySelectionIndex[d]);
                Object key;
                String label;
                if (dim.extraction() == StatsDimensionExtraction.NONE) {
                    key = rawKey;
                    Object rawLabel = hasDistinctLabel[d] ? tuple.get(dimLabelSelectionIndex[d]) : rawKey;
                    label = rawLabel == null ? (key == null ? "null" : String.valueOf(key)) : String.valueOf(rawLabel);
                } else {
                    if (rawKey == null) {
                        key = null;
                        label = "null";
                    } else {
                        key = dim.extraction().extractKey(rawKey, zoneId);
                        label = dim.extraction().formatLabel(key);
                    }
                }
                keys.add(key);
                labels.add(label);
                observedLabelsByDimension.get(d).putIfAbsent(key, label);
            }
            CellCoordinateKey coordinateKey = new CellCoordinateKey(keys);
            MultiDimensionCellAggregate cell = cells.computeIfAbsent(
                    coordinateKey,
                    k -> MultiDimensionCellAggregate.empty(keys, labels, resolvedMetrics)
            );
            cell.accumulate(tuple, resolvedMetrics, metricSelectionIndex);
        }

        boolean usesCellCeiling = dimensions.size() > 1
                || dimensions.stream().anyMatch(dim -> dim.extraction().isTemporal())
                || Boolean.TRUE.equals(request.fillGaps());
        int effectiveMax = usesCellCeiling ? Math.max(maxBuckets, maxCells) : maxBuckets;

        if (Boolean.TRUE.equals(request.fillGaps())) {
            densifyCells(cells, dimensions, observedLabelsByDimension, window, zoneId, effectiveMax, resolvedMetrics);
        }

        Map<CellCoordinateKey, Long> cycleOccurrences = computeCycleOccurrencesIfNeeded(
                dimensions,
                resolvedMetrics,
                window,
                zoneId,
                Boolean.TRUE.equals(request.fillGaps())
        );

        StatsMetricRequest primaryMetric = request.primaryMetric();
        StatsBucketOrder effectiveOrder = resolveEffectiveGroupByOrder(request, dimensions);
        Comparator<MultiDimensionCellAggregate> comparator = buildCellComparator(
                primaryMetric,
                effectiveOrder,
                cycleOccurrences
        );

        int limit = request.limit() == null ? effectiveMax : Math.min(request.limit(), effectiveMax);
        boolean exposeMultiMetricShape = exposesMultiMetricShape(request.metrics());
        boolean exposeDimensionMetadata = request.hasExplicitDimensions()
                || dimensions.size() > 1
                || dimensions.stream().anyMatch(dim -> dim.extraction().isTemporal());

        List<GroupByBucket> buckets = cells.values().stream()
                .sorted(comparator)
                .limit(limit)
                .map(cell -> {
                    Long occurrences = cycleOccurrences == null
                            ? null
                            : cycleOccurrences.getOrDefault(new CellCoordinateKey(cell.keys()), 0L);
                    Number primaryValue = cell.primaryValue(primaryMetric, occurrences);
                    Map<String, Number> metricValues = exposeMultiMetricShape
                            ? cell.values(occurrences)
                            : null;
                    Object compositeKey = cell.keys().size() == 1
                            ? cell.keys().get(0)
                            : joinKeys(cell.keys());
                    String compositeLabel = cell.labels().size() == 1
                            ? cell.labels().get(0)
                            : String.join(" | ", cell.labels());
                    if (!exposeDimensionMetadata) {
                        return new GroupByBucket(
                                compositeKey,
                                compositeLabel,
                                primaryValue,
                                cell.count(),
                                metricValues
                        );
                    }
                    Map<String, Object> coordinates = new LinkedHashMap<>();
                    for (int d = 0; d < dimensions.size(); d++) {
                        coordinates.put(dimensions.get(d).alias(), cell.keys().get(d));
                    }
                    return new GroupByBucket(
                            compositeKey,
                            compositeLabel,
                            primaryValue,
                            cell.count(),
                            metricValues,
                            Collections.unmodifiableList(new ArrayList<>(cell.keys())),
                            Collections.unmodifiableList(new ArrayList<>(cell.labels())),
                            Collections.unmodifiableMap(coordinates)
                    );
                })
                .toList();

        List<StatsDimensionRequest> responseDimensions = exposeDimensionMetadata
                ? dimensions.stream().map(ResolvedStatsDimension::request).toList()
                : null;

        return new GroupByStatsResponse(
                dimensions.get(0).field(),
                primaryMetric,
                buckets,
                exposeMultiMetricShape ? request.effectiveMetrics() : null,
                responseDimensions,
                window
        );
    }

    private void densifyCells(
            Map<CellCoordinateKey, MultiDimensionCellAggregate> cells,
            List<ResolvedStatsDimension> dimensions,
            List<Map<Object, String>> observedLabelsByDimension,
            ComparisonPeriodWindow window,
            ZoneId zoneId,
            int maxCells,
            List<ResolvedStatsMetric> resolvedMetrics
    ) {
        LocalDate from = window == null ? null : window.from();
        LocalDate to = window == null ? null : window.to();

        List<List<Object>> domainKeysPerDimension = new ArrayList<>(dimensions.size());
        List<Map<Object, String>> domainLabelsPerDimension = new ArrayList<>(dimensions.size());

        long expectedCartesianCells = 1L;
        for (int d = 0; d < dimensions.size(); d++) {
            ResolvedStatsDimension dim = dimensions.get(d);
            if (dim.extraction().isTemporal()) {
                List<Object> domainKeys = dim.extraction().domainKeys(from, to, zoneId);
                if (domainKeys.isEmpty()) {
                    return;
                }
                Map<Object, String> labelMap = new LinkedHashMap<>();
                for (Object key : domainKeys) {
                    labelMap.put(key, dim.extraction().formatLabel(key));
                }
                domainKeysPerDimension.add(domainKeys);
                domainLabelsPerDimension.add(labelMap);
                expectedCartesianCells = Math.multiplyExact(expectedCartesianCells, domainKeys.size());
            } else {
                Map<Object, String> observed = observedLabelsByDimension.get(d);
                if (observed.isEmpty()) {
                    return;
                }
                List<Object> sortedKeys = new ArrayList<>(observed.keySet());
                sortedKeys.sort(this::compareCoordinateKey);
                domainKeysPerDimension.add(sortedKeys);
                domainLabelsPerDimension.add(observed);
                expectedCartesianCells = Math.multiplyExact(expectedCartesianCells, sortedKeys.size());
            }
            if (expectedCartesianCells > maxCells) {
                throw new IllegalArgumentException("Maximum number of stats cells exceeded: " + maxCells);
            }
        }

        populateCartesianCells(
                0,
                dimensions.size(),
                domainKeysPerDimension,
                domainLabelsPerDimension,
                new ArrayList<>(dimensions.size()),
                new ArrayList<>(dimensions.size()),
                cells,
                resolvedMetrics
        );
    }

    private void populateCartesianCells(
            int depth,
            int totalDimensions,
            List<List<Object>> domainKeysPerDimension,
            List<Map<Object, String>> domainLabelsPerDimension,
            List<Object> currentKeys,
            List<String> currentLabels,
            Map<CellCoordinateKey, MultiDimensionCellAggregate> cells,
            List<ResolvedStatsMetric> resolvedMetrics
    ) {
        if (depth == totalDimensions) {
            List<Object> keyCopy = Collections.unmodifiableList(new ArrayList<>(currentKeys));
            List<String> labelCopy = Collections.unmodifiableList(new ArrayList<>(currentLabels));
            cells.putIfAbsent(
                    new CellCoordinateKey(keyCopy),
                    MultiDimensionCellAggregate.empty(keyCopy, labelCopy, resolvedMetrics)
            );
            return;
        }
        List<Object> keysForDim = domainKeysPerDimension.get(depth);
        Map<Object, String> labelsForDim = domainLabelsPerDimension.get(depth);
        for (Object key : keysForDim) {
            currentKeys.add(key);
            currentLabels.add(labelsForDim.getOrDefault(key, key == null ? "null" : String.valueOf(key)));
            populateCartesianCells(
                    depth + 1,
                    totalDimensions,
                    domainKeysPerDimension,
                    domainLabelsPerDimension,
                    currentKeys,
                    currentLabels,
                    cells,
                    resolvedMetrics
            );
            currentKeys.remove(currentKeys.size() - 1);
            currentLabels.remove(currentLabels.size() - 1);
        }
    }

    private int registerSelection(
            Root<?> root,
            String propertyPath,
            List<jakarta.persistence.criteria.Selection<?>> selections,
            Map<String, Integer> selectionIndexByPropertyPath
    ) {
        return selectionIndexByPropertyPath.computeIfAbsent(propertyPath, path -> {
            int index = selections.size();
            selections.add(resolvePath(root, path));
            return index;
        });
    }

    private Map<CellCoordinateKey, Long> computeCycleOccurrencesIfNeeded(
            List<ResolvedStatsDimension> dimensions,
            List<ResolvedStatsMetric> resolvedMetrics,
            ComparisonPeriodWindow window,
            ZoneId zoneId,
            boolean fillGaps
    ) {
        boolean needed = resolvedMetrics.stream()
                .anyMatch(m -> m.metric().operation() == StatsMetric.AVG_PER_CYCLE_OCCURRENCE)
                || (fillGaps && dimensions.stream().anyMatch(d -> d.extraction().isCyclic()));
        if (!needed || window == null || window.from() == null || window.to() == null) {
            return null;
        }

        List<Integer> temporalDimensionIndices = new ArrayList<>();
        boolean hasHourlyDimension = false;
        for (int d = 0; d < dimensions.size(); d++) {
            StatsDimensionExtraction extraction = dimensions.get(d).extraction();
            if (extraction.isTemporal()) {
                temporalDimensionIndices.add(d);
                if (extraction.requiresTimeComponent()) {
                    hasHourlyDimension = true;
                }
            }
        }
        if (temporalDimensionIndices.isEmpty()) {
            return null;
        }

        Map<CellCoordinateKey, Long> temporalOccurrences = new HashMap<>();
        if (hasHourlyDimension) {
            Instant cursor = window.from().atStartOfDay(zoneId).toInstant();
            Instant endExclusive = window.to().plusDays(1).atStartOfDay(zoneId).toInstant();
            while (cursor.isBefore(endExclusive)) {
                ZonedDateTime zoned = cursor.atZone(zoneId);
                List<Object> temporalKeys = new ArrayList<>(temporalDimensionIndices.size());
                for (int idx : temporalDimensionIndices) {
                    temporalKeys.add(dimensions.get(idx).extraction().extractKey(zoned, zoneId));
                }
                temporalOccurrences.merge(new CellCoordinateKey(temporalKeys), 1L, Long::sum);
                cursor = cursor.plusSeconds(3600);
            }
        } else {
            LocalDate cursor = window.from();
            while (!cursor.isAfter(window.to())) {
                List<Object> temporalKeys = new ArrayList<>(temporalDimensionIndices.size());
                for (int idx : temporalDimensionIndices) {
                    temporalKeys.add(dimensions.get(idx).extraction().extractKey(cursor, zoneId));
                }
                temporalOccurrences.merge(new CellCoordinateKey(temporalKeys), 1L, Long::sum);
                cursor = cursor.plusDays(1);
            }
        }

        if (temporalDimensionIndices.size() == dimensions.size()) {
            return temporalOccurrences;
        }

        return new TemporalProjectedOccurrenceMap(temporalDimensionIndices, temporalOccurrences);
    }

    private StatsBucketOrder resolveEffectiveGroupByOrder(
            GroupByStatsRequest<?> request,
            List<ResolvedStatsDimension> dimensions
    ) {
        if (request.orderBy() != null) {
            return request.orderBy();
        }
        if (Boolean.TRUE.equals(request.fillGaps())
                || dimensions.stream().anyMatch(dim -> dim.extraction().isTemporal())) {
            return StatsBucketOrder.KEY_ASC;
        }
        return StatsBucketOrder.VALUE_DESC;
    }

    private Comparator<MultiDimensionCellAggregate> buildCellComparator(
            StatsMetricRequest primaryMetric,
            StatsBucketOrder order,
            Map<CellCoordinateKey, Long> cycleOccurrences
    ) {
        Comparator<MultiDimensionCellAggregate> keyComparator = (left, right) ->
                compareKeyLists(left.keys(), right.keys());
        if (order == StatsBucketOrder.KEY_ASC) {
            return keyComparator;
        }
        if (order == StatsBucketOrder.KEY_DESC) {
            return keyComparator.reversed();
        }
        Comparator<MultiDimensionCellAggregate> valueComparator = (left, right) -> {
            Long leftOcc = cycleOccurrences == null
                    ? null
                    : cycleOccurrences.getOrDefault(new CellCoordinateKey(left.keys()), 0L);
            Long rightOcc = cycleOccurrences == null
                    ? null
                    : cycleOccurrences.getOrDefault(new CellCoordinateKey(right.keys()), 0L);
            Number leftVal = left.primaryValue(primaryMetric, leftOcc);
            Number rightVal = right.primaryValue(primaryMetric, rightOcc);
            if (leftVal == null && rightVal == null) {
                return 0;
            }
            if (leftVal == null) {
                return 1;
            }
            if (rightVal == null) {
                return -1;
            }
            int cmp = new BigDecimal(leftVal.toString()).compareTo(new BigDecimal(rightVal.toString()));
            return order == StatsBucketOrder.VALUE_DESC ? -cmp : cmp;
        };
        return valueComparator.thenComparing(keyComparator);
    }

    private int compareKeyLists(List<Object> left, List<Object> right) {
        int size = Math.min(left.size(), right.size());
        for (int i = 0; i < size; i++) {
            int cmp = compareCoordinateKey(left.get(i), right.get(i));
            if (cmp != 0) {
                return cmp;
            }
        }
        return Integer.compare(left.size(), right.size());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private int compareCoordinateKey(Object left, Object right) {
        if (left == right) {
            return 0;
        }
        if (left == null) {
            return -1;
        }
        if (right == null) {
            return 1;
        }
        if (left instanceof Number leftNum && right instanceof Number rightNum) {
            return new BigDecimal(leftNum.toString()).compareTo(new BigDecimal(rightNum.toString()));
        }
        if (left instanceof Comparable comparable && left.getClass().isInstance(right)) {
            return comparable.compareTo(right);
        }
        return String.valueOf(left).compareTo(String.valueOf(right));
    }

    private ZoneId resolveExecutionZone(String timezone, ComparisonPeriodWindow window) {
        if (window != null && window.timezone() != null && !window.timezone().isBlank()) {
            return ZoneId.of(window.timezone().trim());
        }
        if (timezone != null && !timezone.isBlank()) {
            return ZoneId.of(timezone.trim());
        }
        return ZoneOffset.UTC;
    }

    private String joinKeys(List<Object> keys) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) {
                sb.append('|');
            }
            Object k = keys.get(i);
            sb.append(k == null ? "null" : k);
        }
        return sb.toString();
    }

    private static String dimKeyAlias(int index) {
        return "dimKey" + index;
    }

    private static String dimLabelAlias(int index) {
        return "dimLabel" + index;
    }

    @Override
    public <E> TimeSeriesStatsResponse executeTimeSeries(
            EntityManager entityManager,
            Class<E> entityClass,
            Specification<E> specification,
            StatsFieldDescriptor timeDescriptor,
            List<ResolvedStatsMetric> resolvedMetrics,
            TimeSeriesStatsRequest<?> request,
            int maxPoints
    ) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root<E> root = query.from(entityClass);
        Path<?> timePath = resolvePath(root, timeDescriptor.keyPropertyPath());

        Predicate predicate = specification == null ? null : specification.toPredicate(root, query, cb);
        if (predicate != null) {
            query.where(predicate);
        }
        List<jakarta.persistence.criteria.Selection<?>> selections = new ArrayList<>();
        selections.add(timePath.alias("timeValue"));
        for (int index = 0; index < resolvedMetrics.size(); index++) {
            ResolvedStatsMetric resolvedMetric = resolvedMetrics.get(index);
            if (resolvedMetric.descriptor() == null) {
                continue;
            }
            selections.add(resolvePath(root, resolvedMetric.descriptor().keyPropertyPath()).alias(metricTupleAlias(index)));
        }
        query.multiselect(selections);

        List<Tuple> values = entityManager.createQuery(query).getResultList();
        Map<LocalDate, TimeSeriesBucketAggregate> buckets = new LinkedHashMap<>();
        for (Tuple tuple : values) {
            LocalDate bucketStart = toBucketStart(tuple.get("timeValue"), request.granularity());
            if (bucketStart == null) {
                continue;
            }
            if (request.from() != null && bucketStart.isBefore(normalizeStart(request.from(), request.granularity()))) {
                continue;
            }
            if (request.to() != null && bucketStart.isAfter(normalizeStart(request.to(), request.granularity()))) {
                continue;
            }
            TimeSeriesBucketAggregate aggregate = buckets.computeIfAbsent(
                    bucketStart,
                    ignored -> TimeSeriesBucketAggregate.empty(resolvedMetrics)
            );
            aggregate.accumulate(tuple, resolvedMetrics);
        }

        if (Boolean.TRUE.equals(request.fillGaps())) {
            fillGaps(buckets, request, maxPoints, resolvedMetrics);
        }

        boolean exposeMultiMetricShape = exposesMultiMetricShape(request.metrics());
        List<TimeSeriesPoint> points = buckets.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .limit(maxPoints)
                .map(entry -> new TimeSeriesPoint(
                        entry.getKey(),
                        bucketEnd(entry.getKey(), request.granularity()),
                        entry.getKey().toString(),
                        entry.getValue().primaryValue(request.primaryMetric()),
                        entry.getValue().count(),
                        exposeMultiMetricShape ? entry.getValue().values() : null
                ))
                .toList();

        return new TimeSeriesStatsResponse(
                timeDescriptor.field(),
                request.granularity(),
                request.primaryMetric(),
                points,
                exposeMultiMetricShape ? request.effectiveMetrics() : null
        );
    }

    @Override
    public <E> DistributionStatsResponse executeDistribution(
            EntityManager entityManager,
            Class<E> entityClass,
            Specification<E> specification,
            StatsFieldDescriptor distributionDescriptor,
            StatsFieldDescriptor metricDescriptor,
            DistributionStatsRequest<?> request,
            int maxBuckets
    ) {
        if (request.mode() == DistributionMode.HISTOGRAM) {
            return executeHistogramDistribution(entityManager, entityClass, specification, distributionDescriptor, request, maxBuckets);
        }

        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root<E> root = query.from(entityClass);
        Path<?> groupPath = resolvePath(root, distributionDescriptor.keyPropertyPath());
        Expression<Long> countExpression = cb.count(root);
        Expression<? extends Number> valueExpression = resolveValueExpression(cb, root, request.metric(), metricDescriptor);

        Predicate predicate = specification == null ? null : specification.toPredicate(root, query, cb);
        if (predicate != null) {
            query.where(predicate);
        }

        query.multiselect(groupPath.alias("bucketKey"), valueExpression.alias("bucketValue"), countExpression.alias("bucketCount"));
        query.groupBy(groupPath);
        query.orderBy(resolveOrder(cb, groupPath, valueExpression, request.orderBy()));

        TypedQuery<Tuple> typedQuery = entityManager.createQuery(query);
        int limit = request.limit() == null ? maxBuckets : Math.min(request.limit(), maxBuckets);
        typedQuery.setMaxResults(limit);

        List<DistributionBucket> buckets = typedQuery.getResultList().stream()
                .map(tuple -> {
                    Object key = tuple.get("bucketKey");
                    Number value = (Number) tuple.get("bucketValue");
                    long count = ((Number) tuple.get("bucketCount")).longValue();
                    return new DistributionBucket(null, null, key, key == null ? "null" : String.valueOf(key), value, count);
                })
                .toList();

        return new DistributionStatsResponse(distributionDescriptor.field(), request.mode(), request.metric(), buckets);
    }

    private <E> DistributionStatsResponse executeHistogramDistribution(
            EntityManager entityManager,
            Class<E> entityClass,
            Specification<E> specification,
            StatsFieldDescriptor descriptor,
            DistributionStatsRequest<?> request,
            int maxBuckets
    ) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        @SuppressWarnings({"rawtypes", "unchecked"})
        CriteriaQuery query = cb.createQuery(numberPathJavaType(entityManager, entityClass, descriptor.keyPropertyPath()));
        Root<E> root = query.from(entityClass);
        Path<?> valuePath = resolvePath(root, descriptor.keyPropertyPath());

        Predicate predicate = specification == null ? null : specification.toPredicate(root, query, cb);
        if (predicate != null) {
            query.where(predicate);
        }
        query.select(valuePath);

        @SuppressWarnings("unchecked")
        List<Object> values = entityManager.createQuery(query).getResultList();

        double bucketSize = request.bucketSize().doubleValue();
        Map<Double, Long> buckets = new LinkedHashMap<>();
        for (Object value : values) {
            Double numeric = toDouble(value);
            if (numeric == null) {
                continue;
            }
            double start = Math.floor(numeric / bucketSize) * bucketSize;
            buckets.merge(start, 1L, Long::sum);
        }

        Comparator<Map.Entry<Double, Long>> comparator = resolveHistogramOrder(request.orderBy());
        Stream<Map.Entry<Double, Long>> stream = buckets.entrySet().stream().sorted(comparator);

        int effectiveLimit = request.limit() == null ? maxBuckets : Math.min(request.limit(), maxBuckets);
        if (request.bucketCount() != null) {
            effectiveLimit = Math.min(effectiveLimit, request.bucketCount());
        }

        List<DistributionBucket> distributionBuckets = stream
                .limit(effectiveLimit)
                .map(entry -> {
                    double from = entry.getKey();
                    double to = from + bucketSize;
                    long count = entry.getValue();
                    return new DistributionBucket(from, to, from, formatHistogramLabel(from, to), count, count);
                })
                .toList();

        return new DistributionStatsResponse(descriptor.field(), request.mode(), request.metric(), distributionBuckets);
    }

    private Order resolveOrder(
            CriteriaBuilder cb,
            Expression<?> keyExpression,
            Expression<? extends Number> valueExpression,
            StatsBucketOrder order
    ) {
        StatsBucketOrder effective = order == null ? StatsBucketOrder.VALUE_DESC : order;
        if (effective == StatsBucketOrder.KEY_ASC) {
            return cb.asc(keyExpression);
        }
        if (effective == StatsBucketOrder.KEY_DESC) {
            return cb.desc(keyExpression);
        }
        if (effective == StatsBucketOrder.VALUE_ASC) {
            return cb.asc(valueExpression);
        }
        return cb.desc(valueExpression);
    }

    private Expression<? extends Number> resolveValueExpression(
            CriteriaBuilder cb,
            Root<?> root,
            ResolvedStatsMetric resolvedMetric
    ) {
        StatsMetricRequest metric = resolvedMetric.metric();
        if (metric.operation() == StatsMetric.COUNT) {
            return cb.count(root);
        }
        if (metric.operation() == StatsMetric.DISTINCT_COUNT) {
            Path<?> metricPath = resolvePath(root, Objects.requireNonNull(resolvedMetric.descriptor()).keyPropertyPath());
            return cb.countDistinct(metricPath);
        }
        Path<Number> metricPath = resolveNumericPath(root, Objects.requireNonNull(resolvedMetric.descriptor()).keyPropertyPath());
        if (metric.operation() == StatsMetric.SUM) {
            return cb.sum(metricPath);
        }
        if (metric.operation() == StatsMetric.AVG) {
            return cb.avg(metricPath);
        }
        if (metric.operation() == StatsMetric.MIN) {
            return cb.min(metricPath);
        }
        return cb.max(metricPath);
    }

    private Expression<? extends Number> resolveValueExpression(
            CriteriaBuilder cb,
            Root<?> root,
            StatsMetricRequest metric,
            StatsFieldDescriptor metricDescriptor
    ) {
        return resolveValueExpression(cb, root, new ResolvedStatsMetric(metric, metricDescriptor));
    }

    private Comparator<Map.Entry<Double, Long>> resolveHistogramOrder(StatsBucketOrder order) {
        StatsBucketOrder effective = order == null ? StatsBucketOrder.KEY_ASC : order;
        if (effective == StatsBucketOrder.KEY_DESC) {
            return Map.Entry.<Double, Long>comparingByKey().reversed();
        }
        if (effective == StatsBucketOrder.VALUE_ASC) {
            return Map.Entry.<Double, Long>comparingByValue().thenComparing(Map.Entry.comparingByKey());
        }
        if (effective == StatsBucketOrder.VALUE_DESC) {
            return Map.Entry.<Double, Long>comparingByValue().reversed()
                    .thenComparing(Map.Entry.comparingByKey());
        }
        return Map.Entry.comparingByKey();
    }

    private void fillGaps(
            Map<LocalDate, TimeSeriesBucketAggregate> buckets,
            TimeSeriesStatsRequest<?> request,
            int maxPoints,
            List<ResolvedStatsMetric> resolvedMetrics
    ) {
        LocalDate start = request.from() != null ? normalizeStart(request.from(), request.granularity()) : buckets.keySet().stream().min(LocalDate::compareTo).orElse(null);
        LocalDate end = request.to() != null ? normalizeStart(request.to(), request.granularity()) : buckets.keySet().stream().max(LocalDate::compareTo).orElse(null);
        if (start == null || end == null) {
            return;
        }
        long estimated;
        if (request.granularity() == TimeSeriesGranularity.DAY) {
            estimated = java.time.temporal.ChronoUnit.DAYS.between(start, end) + 1;
        } else if (request.granularity() == TimeSeriesGranularity.WEEK) {
            estimated = java.time.temporal.ChronoUnit.WEEKS.between(start, end) + 1;
        } else {
            estimated = java.time.temporal.ChronoUnit.MONTHS.between(start, end) + 1;
        }
        if (estimated > maxPoints) {
            throw new IllegalArgumentException("Maximum number of time-series points exceeded: " + maxPoints);
        }
        LocalDate cursor = start;
        while (!cursor.isAfter(end)) {
            buckets.putIfAbsent(cursor, TimeSeriesBucketAggregate.empty(resolvedMetrics));
            cursor = nextBucket(cursor, request.granularity());
        }
    }

    private ResolvedStatsMetric primaryMetric(
            GroupByStatsRequest<?> request,
            List<ResolvedStatsMetric> resolvedMetrics
    ) {
        String primaryAlias = request.primaryMetric().effectiveAlias();
        return resolvedMetrics.stream()
                .filter(metric -> primaryAlias.equals(metric.alias()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Primary stats metric could not be resolved."));
    }

    private boolean exposesMultiMetricShape(List<StatsMetricRequest> metrics) {
        return metrics != null && !metrics.isEmpty();
    }

    private static String metricTupleAlias(int index) {
        return "metricValue" + index;
    }

    private Map<String, Number> extractMetricValues(Tuple tuple, List<ResolvedStatsMetric> resolvedMetrics) {
        Map<String, Number> values = new LinkedHashMap<>();
        for (int index = 0; index < resolvedMetrics.size(); index++) {
            values.put(resolvedMetrics.get(index).alias(), (Number) tuple.get(metricTupleAlias(index)));
        }
        return values;
    }

    private LocalDate toBucketStart(Object value, TimeSeriesGranularity granularity) {
        if (value == null) {
            return null;
        }
        LocalDate date = toLocalDate(value);
        return date == null ? null : normalizeStart(date, granularity);
    }

    private LocalDate toLocalDate(Object value) {
        if (value instanceof LocalDate localDate) {
            return localDate;
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime.toLocalDate();
        }
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime.withOffsetSameInstant(ZoneOffset.UTC).toLocalDate();
        }
        if (value instanceof ZonedDateTime zonedDateTime) {
            return zonedDateTime.withZoneSameInstant(ZoneOffset.UTC).toLocalDate();
        }
        if (value instanceof Instant instant) {
            return instant.atZone(ZoneOffset.UTC).toLocalDate();
        }
        if (value instanceof java.sql.Date sqlDate) {
            return sqlDate.toLocalDate();
        }
        if (value instanceof java.util.Date date) {
            return Instant.ofEpochMilli(date.getTime()).atZone(ZoneOffset.UTC).toLocalDate();
        }
        if (value instanceof TemporalAccessor temporalAccessor) {
            try {
                return LocalDate.from(temporalAccessor);
            } catch (Exception ignored) {
                return null;
            }
        }
        if (value instanceof CharSequence text) {
            return parseLocalDate(text.toString());
        }
        return null;
    }

    private LocalDate parseLocalDate(String value) {
        String text = value == null ? "" : value.trim();
        if (text.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(text);
        } catch (Exception ignored) {
            // Try richer ISO temporal formats below.
        }
        try {
            return OffsetDateTime.parse(text).withOffsetSameInstant(ZoneOffset.UTC).toLocalDate();
        } catch (Exception ignored) {
            // Try instant/local date-time formats below.
        }
        try {
            return Instant.parse(text).atZone(ZoneOffset.UTC).toLocalDate();
        } catch (Exception ignored) {
            // Try local date-time format below.
        }
        try {
            return LocalDateTime.parse(text).toLocalDate();
        } catch (Exception ignored) {
            return null;
        }
    }

    private LocalDate normalizeStart(LocalDate date, TimeSeriesGranularity granularity) {
        if (granularity == TimeSeriesGranularity.DAY) {
            return date;
        }
        if (granularity == TimeSeriesGranularity.WEEK) {
            return date.with(java.time.DayOfWeek.MONDAY);
        }
        return date.withDayOfMonth(1);
    }

    private LocalDate bucketEnd(LocalDate start, TimeSeriesGranularity granularity) {
        if (granularity == TimeSeriesGranularity.DAY) {
            return start;
        }
        if (granularity == TimeSeriesGranularity.WEEK) {
            return start.plusDays(6);
        }
        return start.with(TemporalAdjusters.lastDayOfMonth());
    }

    private LocalDate nextBucket(LocalDate current, TimeSeriesGranularity granularity) {
        if (granularity == TimeSeriesGranularity.DAY) {
            return current.plusDays(1);
        }
        if (granularity == TimeSeriesGranularity.WEEK) {
            return current.plusWeeks(1);
        }
        return current.plusMonths(1);
    }

    private Path<?> resolvePath(Root<?> root, String propertyPath) {
        String[] parts = propertyPath.split("\\.");
        Path<?> path = root;
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (i == parts.length - 1) {
                path = path.get(part);
            } else if (path instanceof From<?, ?> fromPath) {
                path = getOrCreateLeftJoin(fromPath, part);
            } else {
                throw new IllegalArgumentException("Unable to resolve stats property path: " + propertyPath);
            }
        }
        return path;
    }

    private Join<?, ?> getOrCreateLeftJoin(From<?, ?> fromPath, String attributeName) {
        for (Join<?, ?> existing : fromPath.getJoins()) {
            if (existing.getAttribute() != null
                    && attributeName.equals(existing.getAttribute().getName())
                    && existing.getJoinType() == JoinType.LEFT) {
                return existing;
            }
        }
        return fromPath.join(attributeName, JoinType.LEFT);
    }

    @SuppressWarnings("unchecked")
    private Path<Number> resolveNumericPath(Root<?> root, String propertyPath) {
        Path<?> path = resolvePath(root, propertyPath);
        if (!Number.class.isAssignableFrom(path.getJavaType())) {
            throw new IllegalArgumentException("Stats metric field must be numeric: " + propertyPath);
        }
        return (Path<Number>) path;
    }

    private <E> Class<?> numberPathJavaType(EntityManager entityManager, Class<E> entityClass, String propertyPath) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> probe = cb.createTupleQuery();
        Root<E> root = probe.from(entityClass);
        Class<?> javaType = resolvePath(root, propertyPath).getJavaType();
        if (!Number.class.isAssignableFrom(javaType)) {
            throw new IllegalArgumentException("Histogram distribution requires a numeric field: " + propertyPath);
        }
        return javaType;
    }

    private Double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return null;
    }

    private String formatHistogramLabel(double from, double to) {
        return stripTrailingZero(from) + " - " + stripTrailingZero(to);
    }

    private String stripTrailingZero(double value) {
        if (value == Math.rint(value)) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }

    private record CellCoordinateKey(List<Object> coordinates) {
        CellCoordinateKey {
            coordinates = Collections.unmodifiableList(new ArrayList<>(coordinates));
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof CellCoordinateKey other)) {
                return false;
            }
            return Objects.equals(this.coordinates, other.coordinates);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(this.coordinates);
        }
    }

    private static final class TemporalProjectedOccurrenceMap extends java.util.AbstractMap<CellCoordinateKey, Long> {
        private final List<Integer> temporalDimensionIndices;
        private final Map<CellCoordinateKey, Long> temporalOccurrences;

        TemporalProjectedOccurrenceMap(
                List<Integer> temporalDimensionIndices,
                Map<CellCoordinateKey, Long> temporalOccurrences
        ) {
            this.temporalDimensionIndices = temporalDimensionIndices;
            this.temporalOccurrences = temporalOccurrences;
        }

        @Override
        public Long get(Object key) {
            return getOrDefault(key, 0L);
        }

        @Override
        public Long getOrDefault(Object key, Long defaultValue) {
            if (!(key instanceof CellCoordinateKey cellKey)) {
                return defaultValue;
            }
            List<Object> projected = new ArrayList<>(temporalDimensionIndices.size());
            for (int idx : temporalDimensionIndices) {
                projected.add(cellKey.coordinates().get(idx));
            }
            return temporalOccurrences.getOrDefault(new CellCoordinateKey(projected), defaultValue);
        }

        @Override
        public Set<Entry<CellCoordinateKey, Long>> entrySet() {
            return temporalOccurrences.entrySet();
        }
    }

    private static final class CellAggregateValue {
        private long rowCount;
        private long numericCount;
        private double sum;
        private Double min;
        private Double max;
        private final Set<Object> distinctValues = new HashSet<>();
        private final boolean hasMetricField;

        private CellAggregateValue(boolean hasMetricField) {
            this.hasMetricField = hasMetricField;
        }

        static CellAggregateValue empty(boolean hasMetricField) {
            return new CellAggregateValue(hasMetricField);
        }

        void accumulate(StatsMetric metric, Object value) {
            rowCount++;
            if (metric == StatsMetric.COUNT) {
                return;
            }
            if (metric == StatsMetric.DISTINCT_COUNT) {
                if (value != null) {
                    distinctValues.add(value);
                }
                return;
            }
            if (metric == StatsMetric.AVG_PER_CYCLE_OCCURRENCE && !hasMetricField) {
                return;
            }
            if (!(value instanceof Number number)) {
                return;
            }
            numericCount++;
            double numeric = number.doubleValue();
            sum += numeric;
            min = min == null ? numeric : Math.min(min, numeric);
            max = max == null ? numeric : Math.max(max, numeric);
        }

        Number value(StatsMetric metric, Long cycleOccurrences) {
            if (cycleOccurrences != null && cycleOccurrences <= 0L) {
                return null;
            }
            if (metric == StatsMetric.COUNT) {
                return rowCount;
            }
            if (metric == StatsMetric.DISTINCT_COUNT) {
                return (long) distinctValues.size();
            }
            if (metric == StatsMetric.SUM) {
                return numericCount == 0 ? null : sum;
            }
            if (metric == StatsMetric.AVG) {
                return numericCount == 0 ? null : sum / numericCount;
            }
            if (metric == StatsMetric.MIN) {
                return min;
            }
            if (metric == StatsMetric.MAX) {
                return max;
            }
            if (metric == StatsMetric.AVG_PER_CYCLE_OCCURRENCE) {
                if (cycleOccurrences == null || cycleOccurrences <= 0L) {
                    return null;
                }
                double numerator = hasMetricField ? sum : (double) rowCount;
                return numerator / cycleOccurrences.doubleValue();
            }
            return rowCount;
        }
    }

    private static final class MultiDimensionCellAggregate {
        private final List<Object> keys;
        private final List<String> labels;
        private long count;
        private final List<ResolvedStatsMetric> metrics;
        private final Map<String, CellAggregateValue> aggregates;

        private MultiDimensionCellAggregate(
                List<Object> keys,
                List<String> labels,
                List<ResolvedStatsMetric> metrics,
                Map<String, CellAggregateValue> aggregates
        ) {
            this.keys = keys;
            this.labels = labels;
            this.metrics = metrics;
            this.aggregates = aggregates;
        }

        static MultiDimensionCellAggregate empty(
                List<Object> keys,
                List<String> labels,
                List<ResolvedStatsMetric> metrics
        ) {
            Map<String, CellAggregateValue> aggregates = new LinkedHashMap<>();
            for (ResolvedStatsMetric metric : metrics) {
                aggregates.put(metric.alias(), CellAggregateValue.empty(metric.descriptor() != null));
            }
            return new MultiDimensionCellAggregate(
                    List.copyOf(keys),
                    List.copyOf(labels),
                    List.copyOf(metrics),
                    aggregates
            );
        }

        void accumulate(Tuple tuple, List<ResolvedStatsMetric> metrics, int[] metricSelectionIndex) {
            count++;
            for (int index = 0; index < metrics.size(); index++) {
                ResolvedStatsMetric metric = metrics.get(index);
                int selIdx = metricSelectionIndex[index];
                Object value = selIdx < 0 ? null : tuple.get(selIdx);
                aggregates.get(metric.alias()).accumulate(metric.metric().operation(), value);
            }
        }

        Number primaryValue(StatsMetricRequest primaryMetric, Long cycleOccurrences) {
            return aggregates.get(primaryMetric.effectiveAlias()).value(primaryMetric.operation(), cycleOccurrences);
        }

        Map<String, Number> values(Long cycleOccurrences) {
            Map<String, Number> values = new LinkedHashMap<>();
            for (ResolvedStatsMetric metric : metrics) {
                values.put(
                        metric.alias(),
                        aggregates.get(metric.alias()).value(metric.metric().operation(), cycleOccurrences)
                );
            }
            return values;
        }

        List<Object> keys() {
            return keys;
        }

        List<String> labels() {
            return labels;
        }

        long count() {
            return count;
        }
    }

    private static final class AggregateValue {
        private long count;
        private double sum;
        private Double min;
        private Double max;
        private final Set<Object> distinctValues = new HashSet<>();

        static AggregateValue empty() {
            return new AggregateValue();
        }

        void accumulate(StatsMetric metric, Object value) {
            count++;
            if (metric == StatsMetric.COUNT) {
                return;
            }
            if (metric == StatsMetric.DISTINCT_COUNT) {
                if (value != null) {
                    distinctValues.add(value);
                }
                return;
            }
            if (value == null) {
                return;
            }
            if (!(value instanceof Number number)) {
                return;
            }
            double numeric = number.doubleValue();
            sum += numeric;
            min = min == null ? numeric : Math.min(min, numeric);
            max = max == null ? numeric : Math.max(max, numeric);
        }

        Number value(StatsMetric metric) {
            if (metric == StatsMetric.COUNT) {
                return count;
            }
            if (metric == StatsMetric.DISTINCT_COUNT) {
                return distinctValues.size();
            }
            if (metric == StatsMetric.SUM) {
                return sum;
            }
            if (metric == StatsMetric.AVG) {
                return count == 0 ? 0d : sum / count;
            }
            if (metric == StatsMetric.MIN) {
                return min == null ? 0d : min;
            }
            return max == null ? 0d : max;
        }

        long count() {
            return count;
        }
    }

    private static final class TimeSeriesBucketAggregate {
        private long count;
        private final List<ResolvedStatsMetric> metrics;
        private final Map<String, AggregateValue> aggregates;

        private TimeSeriesBucketAggregate(List<ResolvedStatsMetric> metrics, Map<String, AggregateValue> aggregates) {
            this.metrics = metrics;
            this.aggregates = aggregates;
        }

        static TimeSeriesBucketAggregate empty(List<ResolvedStatsMetric> metrics) {
            Map<String, AggregateValue> aggregates = new LinkedHashMap<>();
            for (ResolvedStatsMetric metric : metrics) {
                aggregates.put(metric.alias(), AggregateValue.empty());
            }
            return new TimeSeriesBucketAggregate(List.copyOf(metrics), aggregates);
        }

        void accumulate(Tuple tuple, List<ResolvedStatsMetric> metrics) {
            count++;
            for (int index = 0; index < metrics.size(); index++) {
                ResolvedStatsMetric metric = metrics.get(index);
                Object value = metric.descriptor() == null ? null : tuple.get(metricTupleAlias(index));
                aggregates.get(metric.alias()).accumulate(metric.metric().operation(), value);
            }
        }

        Number primaryValue(StatsMetricRequest primaryMetric) {
            return aggregates.get(primaryMetric.effectiveAlias()).value(primaryMetric.operation());
        }

        Map<String, Number> values() {
            Map<String, Number> values = new LinkedHashMap<>();
            for (ResolvedStatsMetric metric : metrics) {
                values.put(metric.alias(), aggregates.get(metric.alias()).value(metric.metric().operation()));
            }
            return values;
        }

        long count() {
            return count;
        }
    }
}
