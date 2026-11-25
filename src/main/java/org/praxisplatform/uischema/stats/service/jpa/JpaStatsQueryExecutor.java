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
import org.praxisplatform.uischema.id.CompositeIdSupport;
import org.praxisplatform.uischema.id.JpaCriteriaPathSupport;
import org.praxisplatform.uischema.stats.DistributionMode;
import org.praxisplatform.uischema.stats.StatsBucketOrder;
import org.praxisplatform.uischema.stats.StatsDimensionExtraction;
import org.praxisplatform.uischema.stats.StatsEligibility;
import org.praxisplatform.uischema.stats.StatsFieldDescriptor;
import org.praxisplatform.uischema.stats.StatsMetric;
import org.praxisplatform.uischema.stats.StatsProperties;
import org.praxisplatform.uischema.stats.TimeSeriesGranularity;
import org.springframework.dao.DataAccessResourceFailureException;
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
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAccessor;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Arrays;
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
                Math.max(maxBuckets, 500),
                0
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
        return executeGroupBy(
                entityManager,
                entityClass,
                specification,
                dimensions,
                resolvedMetrics,
                request,
                window,
                maxBuckets,
                maxCells,
                0
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
            int maxCells,
            int defaultMinBucketCount
    ) {
        return executeGroupBy(
                entityManager,
                entityClass,
                specification,
                dimensions,
                resolvedMetrics,
                request,
                window,
                maxBuckets,
                maxCells,
                defaultMinBucketCount,
                StatsProperties.DEFAULT_MAX_IN_MEMORY_SAMPLE_ROWS
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
            int maxCells,
            int defaultMinBucketCount,
            int maxInMemorySampleRows
    ) {
        int effectiveMinBucketCount = resolveGroupByMinBucketCount(dimensions, resolvedMetrics, defaultMinBucketCount);
        boolean requiresAdvancedExecution = dimensions.size() > 1
                || dimensions.stream().anyMatch(dim -> dim.extraction().isTemporal())
                || request.hasExplicitDimensions()
                || window != null
                || Boolean.TRUE.equals(request.fillGaps())
                || resolvedMetrics.stream().anyMatch(m -> m.metric().operation() == StatsMetric.AVG_PER_CYCLE_OCCURRENCE
                        || m.metric().operation().isPercentile())
                || dimensions.stream().anyMatch(dim -> isCompositePropertyPath(entityManager, entityClass, dim.descriptor().keyPropertyPath()))
                || resolvedMetrics.stream().anyMatch(m -> m.metric().operation() == StatsMetric.DISTINCT_COUNT
                        && m.descriptor() != null
                        && isCompositePropertyPath(entityManager, entityClass, m.descriptor().keyPropertyPath()));

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
                    maxCells,
                    effectiveMinBucketCount,
                    maxInMemorySampleRows
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
        boolean[] tracksNumericCount = new boolean[resolvedMetrics.size()];
        for (int index = 0; index < resolvedMetrics.size(); index++) {
            ResolvedStatsMetric resolvedMetric = resolvedMetrics.get(index);
            selections.add(resolveValueExpression(cb, root, resolvedMetric).alias(metricTupleAlias(index)));
            if (effectiveMinBucketCount > 0 && isNumericFieldMetric(resolvedMetric)) {
                tracksNumericCount[index] = true;
                Path<Number> metricPath = resolveNumericPath(root, Objects.requireNonNull(resolvedMetric.descriptor()).keyPropertyPath());
                selections.add(cb.count(metricPath).alias(metricNumericCountAlias(index)));
            }
        }
        query.multiselect(selections);
        query.groupBy(hasDistinctLabelPath ? List.of(groupKeyPath, groupLabelPath) : List.of(groupKeyPath));
        query.orderBy(resolveOrder(cb, groupKeyPath, valueExpression, request.orderBy()));

        TypedQuery<Tuple> typedQuery = entityManager.createQuery(query);
        int limit = request.limit() == null ? maxBuckets : Math.min(request.limit(), maxBuckets);
        if (effectiveMinBucketCount <= 0) {
            typedQuery.setMaxResults(limit + 1);
        }

        boolean exposeMultiMetricShape = exposesMultiMetricShape(request.metrics());
        List<Tuple> rawTuples = typedQuery.getResultList();

        Set<Object> suppressedKeys = Set.of();
        if (effectiveMinBucketCount > 0) {
            List<SuppressionCandidate1D<Object>> candidates = new ArrayList<>(rawTuples.size());
            for (Tuple tuple : rawTuples) {
                long count = ((Number) tuple.get("groupCount")).longValue();
                if (count <= 0L) {
                    continue;
                }
                List<Long> positiveNumericCounts = extractSqlAlignedNumericCounts(tuple, tracksNumericCount);
                candidates.add(new SuppressionCandidate1D<>(
                        normalizeStatsKeyValue(tuple.get("groupKey")),
                        count,
                        positiveNumericCounts
                ));
            }
            suppressedKeys = apply1DComplementarySuppression(candidates, effectiveMinBucketCount);
        }

        boolean truncated;
        long totalRows = 0L;
        long suppressedRowSum = 0L;
        List<GroupByBucket> buckets = new ArrayList<>(Math.min(limit, rawTuples.size()));
        if (effectiveMinBucketCount <= 0) {
            truncated = rawTuples.size() > limit;
            int rowsToEmit = Math.min(limit, rawTuples.size());
            for (int i = 0; i < rowsToEmit; i++) {
                Tuple tuple = rawTuples.get(i);
                long count = ((Number) tuple.get("groupCount")).longValue();
                Object rawKey = tuple.get("groupKey");
                Object labelValue = hasDistinctLabelPath ? tuple.get("groupLabel") : rawKey;
                Number value = (Number) tuple.get("groupValue");
                buckets.add(new GroupByBucket(
                        rawKey,
                        labelValue == null ? (rawKey == null ? "null" : String.valueOf(rawKey)) : String.valueOf(labelValue),
                        value,
                        count,
                        exposeMultiMetricShape ? extractMetricValues(tuple, resolvedMetrics) : null
                ));
            }
            if (truncated) {
                totalRows = countMatchingRows(entityManager, entityClass, specification);
            } else {
                for (Tuple tuple : rawTuples) {
                    totalRows += ((Number) tuple.get("groupCount")).longValue();
                }
            }
        } else {
            int visibleCount = 0;
            for (Tuple tuple : rawTuples) {
                long count = ((Number) tuple.get("groupCount")).longValue();
                if (count <= 0L) {
                    continue;
                }
                totalRows += count;
                Object rawKey = tuple.get("groupKey");
                Object normalizedKey = normalizeStatsKeyValue(rawKey);
                if (suppressedKeys.contains(normalizedKey)) {
                    suppressedRowSum += count;
                    continue;
                }
                visibleCount++;
                if (buckets.size() < limit) {
                    Object labelValue = hasDistinctLabelPath ? tuple.get("groupLabel") : rawKey;
                    Number value = (Number) tuple.get("groupValue");
                    buckets.add(new GroupByBucket(
                            rawKey,
                            labelValue == null ? (rawKey == null ? "null" : String.valueOf(rawKey)) : String.valueOf(labelValue),
                            value,
                            count,
                            exposeMultiMetricShape ? extractMetricValues(tuple, resolvedMetrics) : null
                    ));
                }
            }
            truncated = visibleCount > limit;
        }

        boolean suppressionConfigured = effectiveMinBucketCount > 0;
        int suppressedBucketCount = suppressedKeys.size();
        SdcTotals sdcTotals = resolveSdcTotals(
                totalRows,
                suppressedRowSum,
                suppressedBucketCount,
                effectiveMinBucketCount
        );
        return new GroupByStatsResponse(
                groupDescriptor.field(),
                request.primaryMetric(),
                List.copyOf(buckets),
                exposeMultiMetricShape ? request.effectiveMetrics() : null,
                null,
                null,
                suppressionConfigured ? suppressedBucketCount > 0 : Boolean.FALSE,
                suppressionConfigured ? suppressedBucketCount : null,
                suppressionConfigured ? effectiveMinBucketCount : null,
                sdcTotals.populationTotalCount(),
                sdcTotals.suppressedPoolCount(),
                truncated
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
            int maxCells,
            int effectiveMinBucketCount,
            int maxInMemorySampleRows
    ) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root<E> root = query.from(entityClass);

        Predicate predicate = specification == null ? null : specification.toPredicate(root, query, cb);
        if (predicate != null) {
            query.where(predicate);
        }

        List<jakarta.persistence.criteria.Selection<?>> selections = new ArrayList<>();
        Map<String, SelectionSlot> selectionSlotByPropertyPath = new LinkedHashMap<>();
        SelectionSlot[] dimKeySlots = new SelectionSlot[dimensions.size()];
        SelectionSlot[] dimLabelSlots = new SelectionSlot[dimensions.size()];
        boolean[] hasDistinctLabel = new boolean[dimensions.size()];
        boolean canSqlGroupBy = dimensions.stream().allMatch(dim -> dim.extraction() == StatsDimensionExtraction.NONE)
                && resolvedMetrics.stream().allMatch(m -> !m.metric().operation().isPercentile()
                        && m.metric().operation() != StatsMetric.AVG_PER_CYCLE_OCCURRENCE);

        List<Expression<?>> groupByExpressions = new ArrayList<>();
        Set<String> groupedPropertyPaths = new HashSet<>();
        for (int d = 0; d < dimensions.size(); d++) {
            ResolvedStatsDimension dim = dimensions.get(d);
            Path<?> keyPath = resolvePath(root, dim.descriptor().keyPropertyPath());
            if (dim.extraction().requiresTimeComponent() && keyPath.getJavaType() == LocalDate.class) {
                throw new IllegalArgumentException(
                        "Temporal extraction " + dim.extraction() + " requires a timestamp field with time component: " + dim.field()
                );
            }
            dimKeySlots[d] = registerSelectionSlot(root, dim.descriptor().keyPropertyPath(), selections, selectionSlotByPropertyPath);
            if (dimKeySlots[d].compositeType() != null) {
                canSqlGroupBy = false;
            } else if (groupedPropertyPaths.add(dim.descriptor().keyPropertyPath())) {
                groupByExpressions.add(keyPath);
            }
            if (dim.extraction() == StatsDimensionExtraction.NONE
                    && !Objects.equals(dim.descriptor().keyPropertyPath(), dim.descriptor().labelPropertyPath())) {
                hasDistinctLabel[d] = true;
                Path<?> labelPath = resolvePath(root, dim.descriptor().labelPropertyPath());
                dimLabelSlots[d] = registerSelectionSlot(root, dim.descriptor().labelPropertyPath(), selections, selectionSlotByPropertyPath);
                canSqlGroupBy = false;
            }
        }
        for (ResolvedStatsMetric metric : resolvedMetrics) {
            if (metric.descriptor() != null) {
                Path<?> mPath = resolvePath(root, metric.descriptor().keyPropertyPath());
                if (mPath != null && CompositeIdSupport.isCompositeIdType(mPath.getJavaType())) {
                    canSqlGroupBy = false;
                }
                if (metric.metric().operation() != StatsMetric.COUNT
                        && metric.metric().operation() != StatsMetric.DISTINCT_COUNT) {
                    resolveNumericPath(root, metric.descriptor().keyPropertyPath());
                }
            }
        }

        int sqlCountTupleIndex = -1;
        int[] sqlMetricTupleIndices = new int[resolvedMetrics.size()];
        int[] sqlNumericCountTupleIndices = new int[resolvedMetrics.size()];
        Arrays.fill(sqlNumericCountTupleIndices, -1);
        SelectionSlot[] metricSlots = new SelectionSlot[resolvedMetrics.size()];
        List<NumericFieldPlan> numericPlans = List.of();
        if (canSqlGroupBy) {
            sqlCountTupleIndex = selections.size();
            selections.add(cb.count(root));
            for (int m = 0; m < resolvedMetrics.size(); m++) {
                ResolvedStatsMetric resolvedMetric = resolvedMetrics.get(m);
                sqlMetricTupleIndices[m] = selections.size();
                selections.add(resolveValueExpression(cb, root, resolvedMetric));
                if (effectiveMinBucketCount > 0 && isNumericFieldMetric(resolvedMetric)) {
                    sqlNumericCountTupleIndices[m] = selections.size();
                    Path<Number> metricPath = resolveNumericPath(root, Objects.requireNonNull(resolvedMetric.descriptor()).keyPropertyPath());
                    selections.add(cb.count(metricPath));
                }
            }
            query.multiselect(selections);
            query.groupBy(groupByExpressions);
        } else {
            Map<String, Boolean> needSamplesByPath = new LinkedHashMap<>();
            Map<String, SelectionSlot> numericSlotByPath = new LinkedHashMap<>();
            for (int m = 0; m < resolvedMetrics.size(); m++) {
                ResolvedStatsMetric metric = resolvedMetrics.get(m);
                if (metric.descriptor() != null) {
                    String path = metric.descriptor().keyPropertyPath();
                    metricSlots[m] = registerSelectionSlot(root, path, selections, selectionSlotByPropertyPath);
                    if (isNumericFieldMetric(metric)) {
                        boolean needSample = metric.metric().operation().isPercentile();
                        needSamplesByPath.merge(path, needSample, Boolean::logicalOr);
                        numericSlotByPath.putIfAbsent(path, metricSlots[m]);
                    }
                } else {
                    metricSlots[m] = null;
                }
            }
            List<NumericFieldPlan> planList = new ArrayList<>(needSamplesByPath.size());
            for (Map.Entry<String, Boolean> entry : needSamplesByPath.entrySet()) {
                planList.add(new NumericFieldPlan(
                        entry.getKey(),
                        numericSlotByPath.get(entry.getKey()),
                        -1,
                        entry.getValue()
                ));
            }
            numericPlans = List.copyOf(planList);
            query.multiselect(selections);
        }

        ZoneId zoneId = resolveExecutionZone(request.timezone(), window);
        TypedQuery<Tuple> typedQuery = entityManager.createQuery(query);
        List<Tuple> tuples = canSqlGroupBy
                ? typedQuery.getResultList()
                : executeBoundedInMemoryQuery(typedQuery, maxInMemorySampleRows);

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
                Object rawKey = normalizeStatsKeyValue(extractSelectionValue(tuple, dimKeySlots[d]));
                Object key;
                String label;
                if (dim.extraction() == StatsDimensionExtraction.NONE) {
                    key = rawKey;
                    Object rawLabel = hasDistinctLabel[d]
                            ? normalizeStatsKeyValue(extractSelectionValue(tuple, dimLabelSlots[d]))
                            : rawKey;
                    label = rawLabel == null ? (key == null ? "null" : CompositeIdSupport.stringify(key)) : CompositeIdSupport.stringify(rawLabel);
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
            if (canSqlGroupBy) {
                long bucketCount = ((Number) tuple.get(sqlCountTupleIndex)).longValue();
                cell.accumulateSqlGroup(tuple, bucketCount, resolvedMetrics, sqlMetricTupleIndices, sqlNumericCountTupleIndices);
            } else {
                cell.accumulate(tuple, resolvedMetrics, metricSlots, numericPlans);
            }
        }

        boolean usesCellCeiling = dimensions.size() > 1
                || dimensions.stream().anyMatch(dim -> dim.extraction().isTemporal())
                || Boolean.TRUE.equals(request.fillGaps());
        int effectiveMax = usesCellCeiling ? Math.max(maxBuckets, maxCells) : maxBuckets;

        if (Boolean.TRUE.equals(request.fillGaps())) {
            densifyCells(cells, dimensions, observedLabelsByDimension, window, zoneId, effectiveMax, resolvedMetrics);
        }

        Set<CellCoordinateKey> suppressedKeys = effectiveMinBucketCount > 0
                ? suppressCellsAntiDifferentiation(cells, dimensions.size(), effectiveMinBucketCount)
                : Set.of();
        int suppressedBucketCount = suppressedKeys.size();
        long totalRows = 0L;
        long suppressedRowSum = 0L;
        List<MultiDimensionCellAggregate> eligibleCells = new ArrayList<>(cells.size());
        for (Map.Entry<CellCoordinateKey, MultiDimensionCellAggregate> entry : cells.entrySet()) {
            MultiDimensionCellAggregate cell = entry.getValue();
            if (cell.count() > 0L) {
                totalRows += cell.count();
            }
            if (effectiveMinBucketCount > 0 && (cell.count() <= 0L || suppressedKeys.contains(entry.getKey()))) {
                if (suppressedKeys.contains(entry.getKey()) && cell.count() > 0L) {
                    suppressedRowSum += cell.count();
                }
                continue;
            }
            eligibleCells.add(cell);
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

        List<GroupByBucket> buckets = eligibleCells.stream()
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

        boolean suppressionConfigured = effectiveMinBucketCount > 0;
        boolean truncated = eligibleCells.size() > limit;
        SdcTotals sdcTotals = resolveSdcTotals(
                totalRows,
                suppressedRowSum,
                suppressedBucketCount,
                effectiveMinBucketCount
        );
        return new GroupByStatsResponse(
                dimensions.get(0).field(),
                primaryMetric,
                buckets,
                exposeMultiMetricShape ? request.effectiveMetrics() : null,
                responseDimensions,
                window,
                suppressionConfigured ? suppressedBucketCount > 0 : Boolean.FALSE,
                suppressionConfigured ? suppressedBucketCount : null,
                suppressionConfigured ? effectiveMinBucketCount : null,
                sdcTotals.populationTotalCount(),
                sdcTotals.suppressedPoolCount(),
                truncated
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

    private SelectionSlot registerSelectionSlot(
            Root<?> root,
            String propertyPath,
            List<jakarta.persistence.criteria.Selection<?>> selections,
            Map<String, SelectionSlot> selectionSlotByPropertyPath
    ) {
        return selectionSlotByPropertyPath.computeIfAbsent(propertyPath, path -> {
            Path<?> resolved = resolvePath(root, path);
            Class<?> pathType = resolved == null ? null : resolved.getJavaType();
            if (CompositeIdSupport.isCompositeIdType(pathType)) {
                List<CompositeIdSupport.ComponentDescriptor> comps = CompositeIdSupport.descriptors(pathType);
                if (!comps.isEmpty()) {
                    List<Integer> indices = new ArrayList<>(comps.size());
                    for (CompositeIdSupport.ComponentDescriptor comp : comps) {
                        indices.add(selections.size());
                        selections.add(resolved.get(comp.name()));
                    }
                    return new SelectionSlot(pathType, indices);
                }
            }
            int index = selections.size();
            selections.add(resolved);
            return new SelectionSlot(null, List.of(index));
        });
    }

    private static Object extractSelectionValue(Tuple tuple, SelectionSlot slot) {
        if (slot == null) {
            return null;
        }
        if (slot.compositeType() == null) {
            return tuple.get(slot.tupleIndices().get(0));
        }
        List<Object> partValues = new ArrayList<>(slot.tupleIndices().size());
        for (int idx : slot.tupleIndices()) {
            Object part = tuple.get(idx);
            if (part == null) {
                return null;
            }
            partValues.add(part);
        }
        return CompositeIdSupport.fromComponentValues(slot.compositeType(), partValues);
    }

    private record SelectionSlot(Class<?> compositeType, List<Integer> tupleIndices) {
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
        return executeTimeSeries(
                entityManager,
                entityClass,
                specification,
                timeDescriptor,
                resolvedMetrics,
                request,
                maxPoints,
                StatsProperties.defaults().maxTimeSeriesWindowDays(),
                0,
                StatsProperties.DEFAULT_MAX_IN_MEMORY_SAMPLE_ROWS
        );
    }

    @Override
    public <E> TimeSeriesStatsResponse executeTimeSeries(
            EntityManager entityManager,
            Class<E> entityClass,
            Specification<E> specification,
            StatsFieldDescriptor timeDescriptor,
            List<ResolvedStatsMetric> resolvedMetrics,
            TimeSeriesStatsRequest<?> request,
            int maxPoints,
            int maxTimeSeriesWindowDays,
            int defaultMinBucketCount,
            int maxInMemorySampleRows
    ) {
        ZoneId zoneId = resolveTimeSeriesZone(request.timezone());
        TimeSeriesStatsRequest<?> effectiveRequest = request;
        if (request.rollingWindow() != null && request.from() == null && request.to() == null) {
            LocalDate[] bounds = StatsEligibility.resolveRollingWindow(request.rollingWindow(), zoneId, null);
            effectiveRequest = new TimeSeriesStatsRequest<>(
                    request.filter(),
                    request.field(),
                    request.granularity(),
                    request.metric(),
                    bounds[0],
                    bounds[1],
                    request.fillGaps(),
                    request.metrics(),
                    request.timezone(),
                    request.rollingWindow()
            );
        }
        int effectiveMinBucketCount = resolveFieldAndMetricsMinBucketCount(
                timeDescriptor,
                resolvedMetrics,
                defaultMinBucketCount
        );
        if (timeDescriptor.isTemporalInterval()) {
            return executeIntervalTimeSeries(
                    entityManager,
                    entityClass,
                    specification,
                    timeDescriptor,
                    resolvedMetrics,
                    effectiveRequest,
                    maxPoints,
                    maxTimeSeriesWindowDays,
                    effectiveMinBucketCount,
                    maxInMemorySampleRows,
                    zoneId
            );
        }

        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root<E> root = query.from(entityClass);
        Path<?> timePath = resolvePath(root, timeDescriptor.keyPropertyPath());

        List<Predicate> predicates = new ArrayList<>();
        Predicate basePredicate = specification == null ? null : specification.toPredicate(root, query, cb);
        if (basePredicate != null) {
            predicates.add(basePredicate);
        }
        predicates.add(cb.isNotNull(timePath));
        if (effectiveRequest.from() != null) {
            LocalDate fromFloor = normalizeStart(effectiveRequest.from(), effectiveRequest.granularity());
            Predicate fromBound = intervalLowerBoundPredicate(cb, timePath, fromFloor, zoneId);
            if (fromBound != null) {
                predicates.add(fromBound);
            }
        }
        if (effectiveRequest.to() != null) {
            LocalDate toCeiling = bucketEnd(normalizeStart(effectiveRequest.to(), effectiveRequest.granularity()), effectiveRequest.granularity());
            Predicate toBound = intervalUpperBoundPredicate(cb, timePath, toCeiling, zoneId);
            if (toBound != null) {
                predicates.add(toBound);
            }
        }
        query.where(predicates.toArray(Predicate[]::new));

        if (isSqlPushdownTimeSeriesEligible(entityManager, entityClass, cb, timePath, timeDescriptor, resolvedMetrics, zoneId)) {
            return executeSqlPushdownTimeSeries(
                    entityManager,
                    cb,
                    query,
                    root,
                    timePath,
                    timeDescriptor,
                    resolvedMetrics,
                    effectiveRequest,
                    maxPoints,
                    effectiveMinBucketCount,
                    zoneId
            );
        }

        List<jakarta.persistence.criteria.Selection<?>> selections = new ArrayList<>();
        selections.add(timePath.alias("timeValue"));
        List<TimeSeriesNumericPlan> numericPlans = registerTimeSeriesSelections(root, resolvedMetrics, selections);
        query.multiselect(selections);

        List<Tuple> values = executeBoundedInMemoryQuery(entityManager.createQuery(query), maxInMemorySampleRows);
        Map<LocalDate, TimeSeriesBucketAggregate> buckets = new LinkedHashMap<>();
        for (Tuple tuple : values) {
            LocalDate bucketStart = toBucketStart(tuple.get("timeValue"), effectiveRequest.granularity(), zoneId);
            if (bucketStart == null) {
                continue;
            }
            if (effectiveRequest.from() != null && bucketStart.isBefore(normalizeStart(effectiveRequest.from(), effectiveRequest.granularity()))) {
                continue;
            }
            if (effectiveRequest.to() != null && bucketStart.isAfter(normalizeStart(effectiveRequest.to(), effectiveRequest.granularity()))) {
                continue;
            }
            TimeSeriesBucketAggregate aggregate = buckets.computeIfAbsent(
                    bucketStart,
                    ignored -> TimeSeriesBucketAggregate.empty(resolvedMetrics)
            );
            aggregate.accumulate(tuple, resolvedMetrics, numericPlans);
        }

        if (Boolean.TRUE.equals(effectiveRequest.fillGaps())) {
            fillGaps(buckets, effectiveRequest, maxPoints, resolvedMetrics);
        }

        return buildTimeSeriesResponse(
                timeDescriptor,
                resolvedMetrics,
                effectiveRequest,
                buckets,
                maxPoints,
                effectiveMinBucketCount
        );
    }

    private <E> boolean isSqlPushdownTimeSeriesEligible(
            EntityManager entityManager,
            Class<E> entityClass,
            CriteriaBuilder cb,
            Path<?> timePath,
            StatsFieldDescriptor timeDescriptor,
            List<ResolvedStatsMetric> resolvedMetrics,
            ZoneId zoneId
    ) {
        if (timeDescriptor.isTemporalInterval()
                || !(cb instanceof org.hibernate.query.criteria.HibernateCriteriaBuilder)
                || !isPostgresDialect(entityManager)
                || resolvedMetrics == null
                || resolvedMetrics.isEmpty()) {
            return false;
        }
        // Fixed non-UTC ZoneOffsets (e.g. "-03:00") invert sign in PostgreSQL AT TIME ZONE / date_trunc;
        // fall back to Java aggregation for non-UTC fixed offsets while allowing ZoneOffset.UTC / IANA zones.
        if (zoneId instanceof java.time.ZoneOffset && !java.time.ZoneOffset.UTC.equals(zoneId)) {
            return false;
        }
        Class<?> timeJavaType = timePath.getJavaType();
        if (timeJavaType != LocalDate.class
                && timeJavaType != LocalDateTime.class
                && timeJavaType != Instant.class
                && timeJavaType != OffsetDateTime.class
                && timeJavaType != ZonedDateTime.class) {
            return false;
        }
        if (isCompositePropertyPath(entityManager, entityClass, timeDescriptor.keyPropertyPath())) {
            return false;
        }
        for (ResolvedStatsMetric resolvedMetric : resolvedMetrics) {
            StatsMetric op = resolvedMetric.metric().operation();
            if (op.isPercentile() || op == StatsMetric.AVG_PER_CYCLE_OCCURRENCE) {
                return false;
            }
            if (resolvedMetric.descriptor() != null
                    && isCompositePropertyPath(entityManager, entityClass, resolvedMetric.descriptor().keyPropertyPath())) {
                return false;
            }
        }
        return true;
    }

    private boolean isPostgresDialect(EntityManager entityManager) {
        try {
            var sessionFactory = entityManager.getEntityManagerFactory()
                    .unwrap(org.hibernate.engine.spi.SessionFactoryImplementor.class);
            if (sessionFactory == null) {
                return false;
            }
            String dialectName = sessionFactory.getJdbcServices().getDialect().getClass().getSimpleName();
            return dialectName != null && dialectName.toLowerCase(java.util.Locale.ROOT).contains("postgres");
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static String sanitizeSqlZoneId(ZoneId zoneId) {
        if (zoneId == null || java.time.ZoneOffset.UTC.equals(zoneId)) {
            return "UTC";
        }
        String id = zoneId.getId().trim();
        if ("Z".equalsIgnoreCase(id)) {
            return "UTC";
        }
        if (!id.matches("^[A-Za-z0-9_/+:-]+$")) {
            throw new IllegalArgumentException("Unsupported timezone identifier for SQL time-series: " + id);
        }
        return id;
    }

    private <E> TimeSeriesStatsResponse executeSqlPushdownTimeSeries(
            EntityManager entityManager,
            CriteriaBuilder cb,
            CriteriaQuery<Tuple> query,
            Root<E> root,
            Path<?> timePath,
            StatsFieldDescriptor timeDescriptor,
            List<ResolvedStatsMetric> resolvedMetrics,
            TimeSeriesStatsRequest<?> request,
            int maxPoints,
            int effectiveMinBucketCount,
            ZoneId zoneId
    ) {
        org.hibernate.query.criteria.HibernateCriteriaBuilder hcb =
                (org.hibernate.query.criteria.HibernateCriteriaBuilder) cb;
        String truncUnit = switch (request.granularity()) {
            case DAY -> "day";
            case WEEK -> "week";
            case MONTH -> "month";
        };
        Class<?> timeJavaType = timePath.getJavaType();
        Expression<?> bucketExpression;
        if (timeJavaType == LocalDate.class || timeJavaType == LocalDateTime.class) {
            bucketExpression = hcb.sql(
                    "date_trunc('" + truncUnit + "', cast(? as timestamp))",
                    LocalDateTime.class,
                    timePath
            );
        } else {
            String safeZoneId = sanitizeSqlZoneId(zoneId);
            bucketExpression = hcb.sql(
                    "date_trunc('" + truncUnit + "', ?, '" + safeZoneId + "')",
                    Instant.class,
                    timePath
            );
        }

        List<jakarta.persistence.criteria.Selection<?>> selections = new ArrayList<>();
        selections.add(bucketExpression.alias("bucketStart"));
        selections.add(cb.count(root).alias("bucketCount"));
        int[] sqlMetricTupleIndices = new int[resolvedMetrics.size()];
        int[] sqlNumericCountTupleIndices = new int[resolvedMetrics.size()];
        Arrays.fill(sqlNumericCountTupleIndices, -1);
        for (int index = 0; index < resolvedMetrics.size(); index++) {
            ResolvedStatsMetric resolvedMetric = resolvedMetrics.get(index);
            sqlMetricTupleIndices[index] = selections.size();
            selections.add(resolveValueExpression(cb, root, resolvedMetric).alias(metricTupleAlias(index)));
            if (effectiveMinBucketCount > 0 && isNumericFieldMetric(resolvedMetric)) {
                sqlNumericCountTupleIndices[index] = selections.size();
                Path<Number> metricPath = resolveNumericPath(root, Objects.requireNonNull(resolvedMetric.descriptor()).keyPropertyPath());
                selections.add(cb.count(metricPath).alias(metricNumericCountAlias(index)));
            }
        }
        query.multiselect(selections);
        query.groupBy(bucketExpression);
        query.orderBy(cb.asc(bucketExpression));

        List<Tuple> tuples = entityManager.createQuery(query).getResultList();
        Map<LocalDate, TimeSeriesBucketAggregate> buckets = new LinkedHashMap<>();
        for (Tuple tuple : tuples) {
            LocalDate bucketStart = toBucketStart(tuple.get("bucketStart"), request.granularity(), zoneId);
            if (bucketStart == null) {
                continue;
            }
            if (request.from() != null && bucketStart.isBefore(normalizeStart(request.from(), request.granularity()))) {
                continue;
            }
            if (request.to() != null && bucketStart.isAfter(normalizeStart(request.to(), request.granularity()))) {
                continue;
            }
            long rowCount = ((Number) tuple.get("bucketCount")).longValue();
            buckets.put(
                    bucketStart,
                    TimeSeriesBucketAggregate.fromSqlGroup(
                            tuple,
                            rowCount,
                            resolvedMetrics,
                            sqlMetricTupleIndices,
                            sqlNumericCountTupleIndices
                    )
            );
        }

        if (Boolean.TRUE.equals(request.fillGaps())) {
            fillGaps(buckets, request, maxPoints, resolvedMetrics);
        }

        return buildTimeSeriesResponse(
                timeDescriptor,
                resolvedMetrics,
                request,
                buckets,
                maxPoints,
                effectiveMinBucketCount
        );
    }

    private <E> TimeSeriesStatsResponse executeIntervalTimeSeries(
            EntityManager entityManager,
            Class<E> entityClass,
            Specification<E> specification,
            StatsFieldDescriptor timeDescriptor,
            List<ResolvedStatsMetric> resolvedMetrics,
            TimeSeriesStatsRequest<?> request,
            int maxPoints,
            int maxTimeSeriesWindowDays,
            int effectiveMinBucketCount,
            int maxInMemorySampleRows,
            ZoneId zoneId
    ) {
        if ((request.from() == null) != (request.to() == null)) {
            throw new IllegalArgumentException(
                    "Temporal interval time-series requires either both from and to dates or neither: " + timeDescriptor.field()
            );
        }
        LocalDate effectiveFrom;
        LocalDate effectiveTo;
        if (request.from() == null && request.to() == null) {
            LocalDate[] defaults = org.praxisplatform.uischema.stats.StatsEligibility.resolveDefaultIntervalWindow(
                    request.granularity(),
                    maxPoints,
                    maxTimeSeriesWindowDays,
                    zoneId
            );
            effectiveFrom = defaults[0];
            effectiveTo = defaults[1];
        } else {
            effectiveFrom = request.from();
            effectiveTo = request.to();
            if (effectiveFrom.isAfter(effectiveTo)) {
                throw new IllegalArgumentException("Time-series range is invalid: from must be on or before to.");
            }
            if (maxTimeSeriesWindowDays > 0) {
                long windowDays = ChronoUnit.DAYS.between(effectiveFrom, effectiveTo) + 1;
                if (windowDays > maxTimeSeriesWindowDays) {
                    throw new IllegalArgumentException(
                            "Maximum time-series window exceeded: " + maxTimeSeriesWindowDays + " days."
                    );
                }
            }
        }

        LocalDate startCursor = normalizeStart(effectiveFrom, request.granularity());
        LocalDate endCursor = normalizeStart(effectiveTo, request.granularity());
        List<LocalDate> bucketStarts = new ArrayList<>();
        LocalDate cursor = startCursor;
        while (!cursor.isAfter(endCursor)) {
            bucketStarts.add(cursor);
            if (bucketStarts.size() > maxPoints) {
                throw new IllegalArgumentException("Maximum number of time-series points exceeded: " + maxPoints);
            }
            cursor = nextBucket(cursor, request.granularity());
        }

        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root<E> root = query.from(entityClass);
        Path<?> startPath = resolvePath(root, timeDescriptor.startPropertyPath());
        Path<?> endPath = resolvePath(root, timeDescriptor.endPropertyPath());

        List<Predicate> predicates = new ArrayList<>();
        Predicate basePredicate = specification == null ? null : specification.toPredicate(root, query, cb);
        if (basePredicate != null) {
            predicates.add(basePredicate);
        }
        Predicate sqlWindowStartBound = intervalUpperBoundPredicate(cb, startPath, effectiveTo, zoneId);
        if (sqlWindowStartBound != null) {
            predicates.add(sqlWindowStartBound);
        }
        Predicate sqlWindowEndBound = intervalLowerBoundPredicate(cb, endPath, effectiveFrom, zoneId);
        if (sqlWindowEndBound != null) {
            predicates.add(cb.or(cb.isNull(endPath), sqlWindowEndBound));
        }
        if (!predicates.isEmpty()) {
            query.where(predicates.toArray(Predicate[]::new));
        }

        List<jakarta.persistence.criteria.Selection<?>> selections = new ArrayList<>();
        selections.add(startPath.alias("startTimeValue"));
        selections.add(endPath.alias("endTimeValue"));
        List<TimeSeriesNumericPlan> numericPlans = registerTimeSeriesSelections(root, resolvedMetrics, selections);
        query.multiselect(selections);

        List<Tuple> values = executeBoundedInMemoryQuery(entityManager.createQuery(query), maxInMemorySampleRows);
        Map<LocalDate, TimeSeriesBucketAggregate> buckets = new LinkedHashMap<>();
        if (Boolean.TRUE.equals(request.fillGaps())) {
            for (LocalDate bucketStart : bucketStarts) {
                buckets.put(bucketStart, TimeSeriesBucketAggregate.empty(resolvedMetrics));
            }
        }

        for (Tuple tuple : values) {
            LocalDate startDate = toLocalDate(tuple.get("startTimeValue"), zoneId);
            if (startDate == null || startDate.isAfter(effectiveTo)) {
                continue;
            }
            LocalDate endDate = toLocalDate(tuple.get("endTimeValue"), zoneId);
            if (endDate != null && (endDate.isBefore(effectiveFrom) || endDate.isBefore(startDate))) {
                continue;
            }
            // Intersecta exatamente [startDate, endDate] com [effectiveFrom, effectiveTo] antes do bucketing.
            LocalDate clampedStart = startDate.isBefore(effectiveFrom) ? effectiveFrom : startDate;
            LocalDate clampedEnd = (endDate == null || endDate.isAfter(effectiveTo)) ? effectiveTo : endDate;
            if (clampedEnd.isBefore(clampedStart)) {
                continue;
            }
            for (LocalDate bucketStart : bucketStarts) {
                LocalDate bStart = bucketStart.isBefore(effectiveFrom) ? effectiveFrom : bucketStart;
                LocalDate bEnd = bucketEnd(bucketStart, request.granularity());
                if (bEnd.isAfter(effectiveTo)) {
                    bEnd = effectiveTo;
                }
                if (!clampedStart.isAfter(bEnd) && !clampedEnd.isBefore(bStart)) {
                    TimeSeriesBucketAggregate aggregate = buckets.computeIfAbsent(
                            bucketStart,
                            ignored -> TimeSeriesBucketAggregate.empty(resolvedMetrics)
                    );
                    aggregate.accumulate(tuple, resolvedMetrics, numericPlans);
                }
            }
        }

        return buildTimeSeriesResponse(
                timeDescriptor,
                resolvedMetrics,
                request,
                buckets,
                maxPoints,
                effectiveMinBucketCount
        );
    }

    private List<TimeSeriesNumericPlan> registerTimeSeriesSelections(
            Root<?> root,
            List<ResolvedStatsMetric> resolvedMetrics,
            List<jakarta.persistence.criteria.Selection<?>> selections
    ) {
        Map<String, TimeSeriesNumericPlan> uniquePlans = new LinkedHashMap<>();
        for (int index = 0; index < resolvedMetrics.size(); index++) {
            ResolvedStatsMetric resolvedMetric = resolvedMetrics.get(index);
            if (resolvedMetric.descriptor() == null) {
                continue;
            }
            boolean isNumeric = isNumericFieldMetric(resolvedMetric);
            Path<?> metricPath = isNumeric
                    ? resolveNumericPath(root, resolvedMetric.descriptor().keyPropertyPath())
                    : resolvePath(root, resolvedMetric.descriptor().keyPropertyPath());
            selections.add(metricPath.alias(metricTupleAlias(index)));
            if (isNumeric) {
                String path = resolvedMetric.descriptor().keyPropertyPath();
                boolean needSamples = resolvedMetric.metric().operation().isPercentile();
                TimeSeriesNumericPlan existing = uniquePlans.get(path);
                if (existing == null) {
                    uniquePlans.put(path, new TimeSeriesNumericPlan(path, metricTupleAlias(index), needSamples));
                } else if (needSamples && !existing.needSamples()) {
                    uniquePlans.put(path, new TimeSeriesNumericPlan(path, existing.tupleAlias(), true));
                }
            }
        }
        return List.copyOf(uniquePlans.values());
    }

    private TimeSeriesStatsResponse buildTimeSeriesResponse(
            StatsFieldDescriptor timeDescriptor,
            List<ResolvedStatsMetric> resolvedMetrics,
            TimeSeriesStatsRequest<?> request,
            Map<LocalDate, TimeSeriesBucketAggregate> buckets,
            int maxPoints,
            int effectiveMinBucketCount
    ) {
        Set<LocalDate> suppressedDates = effectiveMinBucketCount > 0
                ? applyTimeSeriesSuppression(buckets, effectiveMinBucketCount)
                : Set.of();

        boolean fillGaps = Boolean.TRUE.equals(request.fillGaps());
        boolean exposeMultiMetricShape = exposesMultiMetricShape(request.metrics());
        List<Map.Entry<LocalDate, TimeSeriesBucketAggregate>> eligibleEntries = buckets.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .filter(entry -> fillGaps || !suppressedDates.contains(entry.getKey()))
                .toList();
        boolean truncated = eligibleEntries.size() > maxPoints;
        List<TimeSeriesPoint> points = eligibleEntries.stream()
                .limit(maxPoints)
                .map(entry -> {
                    LocalDate date = entry.getKey();
                    if (suppressedDates.contains(date)) {
                        return new TimeSeriesPoint(
                                date,
                                bucketEnd(date, request.granularity()),
                                date.toString(),
                                null,
                                null,
                                null,
                                Boolean.TRUE
                        );
                    }
                    return new TimeSeriesPoint(
                            date,
                            bucketEnd(date, request.granularity()),
                            date.toString(),
                            entry.getValue().primaryValue(request.primaryMetric()),
                            entry.getValue().count(),
                            exposeMultiMetricShape ? entry.getValue().values() : null,
                            null
                    );
                })
                .toList();

        boolean suppressionConfigured = effectiveMinBucketCount > 0;
        int suppressedBucketCount = suppressedDates.size();
        Long populationTotalCount = null;
        Long suppressedPoolCount = null;
        if (!timeDescriptor.isTemporalInterval()) {
            long totalRows = 0L;
            long suppressedRowSum = 0L;
            for (Map.Entry<LocalDate, TimeSeriesBucketAggregate> entry : buckets.entrySet()) {
                long bucketCount = entry.getValue().count();
                if (bucketCount > 0L) {
                    totalRows += bucketCount;
                    if (suppressedDates.contains(entry.getKey())) {
                        suppressedRowSum += bucketCount;
                    }
                }
            }
            SdcTotals sdcTotals = resolveSdcTotals(
                    totalRows,
                    suppressedRowSum,
                    suppressedBucketCount,
                    effectiveMinBucketCount
            );
            populationTotalCount = sdcTotals.populationTotalCount();
            suppressedPoolCount = sdcTotals.suppressedPoolCount();
        }

        return new TimeSeriesStatsResponse(
                timeDescriptor.field(),
                request.granularity(),
                request.primaryMetric(),
                points,
                exposeMultiMetricShape ? request.effectiveMetrics() : null,
                suppressionConfigured ? suppressedBucketCount > 0 : Boolean.FALSE,
                suppressionConfigured ? suppressedBucketCount : null,
                suppressionConfigured ? effectiveMinBucketCount : null,
                populationTotalCount,
                suppressedPoolCount,
                truncated
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
        return executeDistribution(
                entityManager,
                entityClass,
                specification,
                distributionDescriptor,
                metricDescriptor,
                request,
                maxBuckets,
                0,
                StatsProperties.DEFAULT_MAX_IN_MEMORY_SAMPLE_ROWS
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
            int maxBuckets,
            int defaultMinBucketCount
    ) {
        return executeDistribution(
                entityManager,
                entityClass,
                specification,
                distributionDescriptor,
                metricDescriptor,
                request,
                maxBuckets,
                defaultMinBucketCount,
                StatsProperties.DEFAULT_MAX_IN_MEMORY_SAMPLE_ROWS
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
            int maxBuckets,
            int defaultMinBucketCount,
            int maxInMemorySampleRows
    ) {
        int effectiveMinBucketCount = resolveEffectiveMinBucketCount(distributionDescriptor, metricDescriptor, defaultMinBucketCount);
        if (request.mode() == DistributionMode.HISTOGRAM) {
            return executeHistogramDistribution(
                    entityManager,
                    entityClass,
                    specification,
                    distributionDescriptor,
                    request,
                    maxBuckets,
                    effectiveMinBucketCount,
                    maxInMemorySampleRows
            );
        }
        if (request.metric() != null && request.metric().operation() != null && request.metric().operation().isPercentile()) {
            return executePercentileTermsDistribution(
                    entityManager,
                    entityClass,
                    specification,
                    distributionDescriptor,
                    metricDescriptor,
                    request,
                    maxBuckets,
                    effectiveMinBucketCount,
                    maxInMemorySampleRows
            );
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

        boolean trackNumericCount = effectiveMinBucketCount > 0
                && metricDescriptor != null
                && request.metric() != null
                && isNumericFieldMetricOperation(request.metric().operation());
        List<jakarta.persistence.criteria.Selection<?>> selections = new ArrayList<>();
        selections.add(groupPath.alias("bucketKey"));
        selections.add(valueExpression.alias("bucketValue"));
        selections.add(countExpression.alias("bucketCount"));
        if (trackNumericCount) {
            Path<Number> metricPath = resolveNumericPath(root, metricDescriptor.keyPropertyPath());
            selections.add(cb.count(metricPath).alias("metricNumericCount"));
        }
        query.multiselect(selections);
        query.groupBy(groupPath);
        query.orderBy(resolveOrder(cb, groupPath, valueExpression, request.orderBy()));

        TypedQuery<Tuple> typedQuery = entityManager.createQuery(query);
        int limit = request.limit() == null ? maxBuckets : Math.min(request.limit(), maxBuckets);
        if (effectiveMinBucketCount <= 0) {
            typedQuery.setMaxResults(limit);
        }

        List<Tuple> rawTuples = typedQuery.getResultList();
        Set<Object> suppressedKeys = Set.of();
        if (effectiveMinBucketCount > 0) {
            List<SuppressionCandidate1D<Object>> candidates = new ArrayList<>(rawTuples.size());
            for (Tuple tuple : rawTuples) {
                long count = ((Number) tuple.get("bucketCount")).longValue();
                if (count <= 0L) {
                    continue;
                }
                List<Long> positiveNumericCounts = trackNumericCount
                        ? toPositiveNumericCountList((Number) tuple.get("metricNumericCount"))
                        : List.of();
                candidates.add(new SuppressionCandidate1D<>(
                        normalizeStatsKeyValue(tuple.get("bucketKey")),
                        count,
                        positiveNumericCounts
                ));
            }
            suppressedKeys = apply1DComplementarySuppression(candidates, effectiveMinBucketCount);
        }

        List<DistributionBucket> buckets = new ArrayList<>(Math.min(limit, rawTuples.size()));
        for (Tuple tuple : rawTuples) {
            long count = ((Number) tuple.get("bucketCount")).longValue();
            Object rawKey = tuple.get("bucketKey");
            Object normalizedKey = normalizeStatsKeyValue(rawKey);
            if (effectiveMinBucketCount > 0 && (count <= 0L || suppressedKeys.contains(normalizedKey))) {
                continue;
            }
            if (buckets.size() >= limit) {
                break;
            }
            Number value = (Number) tuple.get("bucketValue");
            buckets.add(new DistributionBucket(
                    null,
                    null,
                    rawKey,
                    rawKey == null ? "null" : String.valueOf(rawKey),
                    value,
                    count
            ));
        }

        boolean suppressionConfigured = effectiveMinBucketCount > 0;
        int suppressedBucketCount = suppressedKeys.size();
        return new DistributionStatsResponse(
                distributionDescriptor.field(),
                request.mode(),
                request.metric(),
                List.copyOf(buckets),
                suppressionConfigured ? suppressedBucketCount > 0 : Boolean.FALSE,
                suppressionConfigured ? suppressedBucketCount : null,
                suppressionConfigured ? effectiveMinBucketCount : null
        );
    }

    private <E> DistributionStatsResponse executePercentileTermsDistribution(
            EntityManager entityManager,
            Class<E> entityClass,
            Specification<E> specification,
            StatsFieldDescriptor distributionDescriptor,
            StatsFieldDescriptor metricDescriptor,
            DistributionStatsRequest<?> request,
            int maxBuckets,
            int effectiveMinBucketCount,
            int maxInMemorySampleRows
    ) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root<E> root = query.from(entityClass);
        Path<?> groupPath = resolvePath(root, distributionDescriptor.keyPropertyPath());
        Path<Number> metricPath = resolveNumericPath(root, Objects.requireNonNull(metricDescriptor).keyPropertyPath());

        Predicate predicate = specification == null ? null : specification.toPredicate(root, query, cb);
        if (predicate != null) {
            query.where(predicate);
        }
        query.multiselect(groupPath.alias("bucketKey"), metricPath.alias("metricValue"));
        List<Tuple> tuples = executeBoundedInMemoryQuery(entityManager.createQuery(query), maxInMemorySampleRows);

        Map<Object, CellAggregateValue> aggregates = new LinkedHashMap<>();
        for (Tuple tuple : tuples) {
            Object key = normalizeStatsKeyValue(tuple.get("bucketKey"));
            CellAggregateValue aggregate = aggregates.computeIfAbsent(key, ignored -> CellAggregateValue.empty(true));
            aggregate.accumulate(request.metric().operation(), tuple.get("metricValue"));
        }

        Set<Object> suppressedKeys = Set.of();
        if (effectiveMinBucketCount > 0) {
            List<SuppressionCandidate1D<Object>> candidates = new ArrayList<>(aggregates.size());
            for (Map.Entry<Object, CellAggregateValue> entry : aggregates.entrySet()) {
                long count = entry.getValue().rowCount;
                if (count <= 0L) {
                    continue;
                }
                List<Long> positiveNumericCounts = entry.getValue().numericCount() > 0L
                        ? List.of(entry.getValue().numericCount())
                        : List.of();
                candidates.add(new SuppressionCandidate1D<>(entry.getKey(), count, positiveNumericCounts));
            }
            suppressedKeys = apply1DComplementarySuppression(candidates, effectiveMinBucketCount);
        }

        StatsBucketOrder effectiveOrder = request.orderBy() == null ? StatsBucketOrder.VALUE_DESC : request.orderBy();
        Comparator<DistributionBucket> keyComparator = (left, right) -> compareCoordinateKey(left.key(), right.key());
        Comparator<DistributionBucket> comparator;
        if (effectiveOrder == StatsBucketOrder.KEY_ASC) {
            comparator = keyComparator;
        } else if (effectiveOrder == StatsBucketOrder.KEY_DESC) {
            comparator = keyComparator.reversed();
        } else {
            Comparator<DistributionBucket> valueComparator = (left, right) -> {
                Number leftVal = left.value();
                Number rightVal = right.value();
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
                return effectiveOrder == StatsBucketOrder.VALUE_DESC ? -cmp : cmp;
            };
            comparator = valueComparator.thenComparing(keyComparator);
        }

        List<DistributionBucket> eligible = new ArrayList<>(aggregates.size());
        for (Map.Entry<Object, CellAggregateValue> entry : aggregates.entrySet()) {
            long count = entry.getValue().rowCount;
            if (effectiveMinBucketCount > 0 && (count <= 0L || suppressedKeys.contains(entry.getKey()))) {
                continue;
            }
            eligible.add(new DistributionBucket(
                    null,
                    null,
                    entry.getKey(),
                    entry.getKey() == null ? "null" : String.valueOf(entry.getKey()),
                    entry.getValue().value(request.metric().operation(), null),
                    count
            ));
        }

        int limit = request.limit() == null ? maxBuckets : Math.min(request.limit(), maxBuckets);
        List<DistributionBucket> buckets = eligible.stream()
                .sorted(comparator)
                .limit(limit)
                .toList();

        boolean suppressionConfigured = effectiveMinBucketCount > 0;
        int suppressedBucketCount = suppressedKeys.size();
        return new DistributionStatsResponse(
                distributionDescriptor.field(),
                request.mode(),
                request.metric(),
                buckets,
                suppressionConfigured ? suppressedBucketCount > 0 : Boolean.FALSE,
                suppressionConfigured ? suppressedBucketCount : null,
                suppressionConfigured ? effectiveMinBucketCount : null
        );
    }

    private <E> DistributionStatsResponse executeHistogramDistribution(
            EntityManager entityManager,
            Class<E> entityClass,
            Specification<E> specification,
            StatsFieldDescriptor descriptor,
            DistributionStatsRequest<?> request,
            int maxBuckets,
            int effectiveMinBucketCount,
            int maxInMemorySampleRows
    ) {
        BigDecimal requestedBucketSize = toBigDecimal(request.bucketSize());
        if (request.bucketSize() != null && (requestedBucketSize == null || requestedBucketSize.compareTo(BigDecimal.ZERO) <= 0)) {
            throw new IllegalArgumentException("Histogram distribution requires a positive bucketSize.");
        }

        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        @SuppressWarnings({"rawtypes", "unchecked"})
        CriteriaQuery query = cb.createQuery(numberPathJavaType(entityManager, entityClass, descriptor.keyPropertyPath()));
        Root<E> root = query.from(entityClass);
        Path<?> valuePath = resolvePath(root, descriptor.keyPropertyPath());

        Predicate predicate = specification == null ? null : specification.toPredicate(root, query, cb);
        Predicate notNullPredicate = cb.isNotNull(valuePath);
        query.where(predicate == null ? notNullPredicate : cb.and(predicate, notNullPredicate));
        query.select(valuePath);

        @SuppressWarnings("unchecked")
        List<Object> values = executeBoundedInMemoryQuery(entityManager.createQuery(query), maxInMemorySampleRows);

        List<BigDecimal> numericValues = new ArrayList<>(values.size());
        BigDecimal min = null;
        BigDecimal max = null;
        for (Object value : values) {
            BigDecimal numeric = toBigDecimal(value);
            if (numeric == null) {
                continue;
            }
            numericValues.add(numeric);
            min = min == null ? numeric : min.min(numeric);
            max = max == null ? numeric : max.max(numeric);
        }

        boolean suppressionConfigured = effectiveMinBucketCount > 0;
        if (numericValues.isEmpty()) {
            return new DistributionStatsResponse(
                    descriptor.field(),
                    request.mode(),
                    request.metric(),
                    List.of(),
                    Boolean.FALSE,
                    suppressionConfigured ? 0 : null,
                    suppressionConfigured ? effectiveMinBucketCount : null
            );
        }

        BigDecimal bucketSize;
        if (request.bucketSize() != null) {
            bucketSize = toBigDecimal(request.bucketSize());
            if (bucketSize == null || bucketSize.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("Histogram distribution requires a positive bucketSize.");
            }
        } else {
            int effectiveBucketCount = request.bucketCount() != null && request.bucketCount() > 0
                    ? request.bucketCount()
                    : 10;
            bucketSize = computeNiceHistogramBucketSize(min, max, effectiveBucketCount);
        }

        Map<BigDecimal, Long> buckets = new LinkedHashMap<>();
        for (BigDecimal numeric : numericValues) {
            BigDecimal start = canonicalHistogramBoundary(
                    numeric.divide(bucketSize, 0, java.math.RoundingMode.FLOOR).multiply(bucketSize)
            );
            buckets.merge(start, 1L, Long::sum);
        }

        Set<BigDecimal> suppressedStarts = Set.of();
        if (effectiveMinBucketCount > 0) {
            List<SuppressionCandidate1D<BigDecimal>> candidates = new ArrayList<>(buckets.size());
            for (Map.Entry<BigDecimal, Long> entry : buckets.entrySet()) {
                if (entry.getValue() > 0L) {
                    candidates.add(new SuppressionCandidate1D<>(entry.getKey(), entry.getValue(), List.of()));
                }
            }
            suppressedStarts = apply1DComplementarySuppression(candidates, effectiveMinBucketCount);
        }

        Map<BigDecimal, Long> eligibleBuckets = new LinkedHashMap<>(buckets.size());
        for (Map.Entry<BigDecimal, Long> entry : buckets.entrySet()) {
            long count = entry.getValue();
            if (effectiveMinBucketCount > 0 && (count <= 0L || suppressedStarts.contains(entry.getKey()))) {
                continue;
            }
            eligibleBuckets.put(entry.getKey(), count);
        }

        Comparator<Map.Entry<BigDecimal, Long>> comparator = resolveHistogramOrder(request.orderBy());
        Stream<Map.Entry<BigDecimal, Long>> stream = eligibleBuckets.entrySet().stream().sorted(comparator);

        int effectiveLimit = request.limit() == null ? maxBuckets : Math.min(request.limit(), maxBuckets);
        if (request.bucketCount() != null) {
            effectiveLimit = Math.min(effectiveLimit, request.bucketCount());
        }

        List<DistributionBucket> distributionBuckets = stream
                .limit(effectiveLimit)
                .map(entry -> {
                    BigDecimal from = entry.getKey();
                    BigDecimal to = canonicalHistogramBoundary(from.add(bucketSize));
                    long count = entry.getValue();
                    return new DistributionBucket(from, to, from, formatHistogramLabel(from, to), count, count);
                })
                .toList();

        int suppressedBucketCount = suppressedStarts.size();
        return new DistributionStatsResponse(
                descriptor.field(),
                request.mode(),
                request.metric(),
                distributionBuckets,
                suppressionConfigured ? suppressedBucketCount > 0 : Boolean.FALSE,
                suppressionConfigured ? suppressedBucketCount : null,
                suppressionConfigured ? effectiveMinBucketCount : null
        );
    }

    @Override
    public <E> Map<Object, Long> resolveComparisonPositiveNumericCounts(
            EntityManager entityManager,
            Class<E> entityClass,
            Specification<E> specification,
            StatsFieldDescriptor groupDescriptor,
            List<ResolvedStatsMetric> resolvedMetrics
    ) {
        if (resolvedMetrics == null || resolvedMetrics.isEmpty()) {
            return Map.of();
        }
        List<ResolvedStatsMetric> numericMetrics = resolvedMetrics.stream()
                .filter(this::isNumericFieldMetric)
                .toList();
        if (numericMetrics.isEmpty()) {
            return Map.of();
        }

        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root<E> root = query.from(entityClass);
        Path<?> groupPath = resolvePath(root, groupDescriptor.keyPropertyPath());
        Predicate predicate = specification == null ? null : specification.toPredicate(root, query, cb);
        if (predicate != null) {
            query.where(predicate);
        }

        List<jakarta.persistence.criteria.Selection<?>> selections = new ArrayList<>();
        selections.add(groupPath.alias("groupKey"));
        for (int index = 0; index < numericMetrics.size(); index++) {
            Path<Number> metricPath = resolveNumericPath(root, numericMetrics.get(index).descriptor().keyPropertyPath());
            selections.add(cb.count(metricPath).alias(metricNumericCountAlias(index)));
        }
        query.multiselect(selections);
        query.groupBy(groupPath);

        Map<Object, Long> result = new LinkedHashMap<>();
        for (Tuple tuple : entityManager.createQuery(query).getResultList()) {
            Object key = normalizeStatsKeyValue(tuple.get("groupKey"));
            long minPositive = Long.MAX_VALUE;
            for (int index = 0; index < numericMetrics.size(); index++) {
                Number numCount = (Number) tuple.get(metricNumericCountAlias(index));
                long count = numCount == null ? 0L : numCount.longValue();
                if (count > 0L && count < minPositive) {
                    minPositive = count;
                }
            }
            if (minPositive < Long.MAX_VALUE) {
                result.put(key, minPositive);
            }
        }
        return Map.copyOf(result);
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

    private Comparator<Map.Entry<BigDecimal, Long>> resolveHistogramOrder(StatsBucketOrder order) {
        StatsBucketOrder effective = order == null ? StatsBucketOrder.KEY_ASC : order;
        if (effective == StatsBucketOrder.KEY_DESC) {
            return Map.Entry.<BigDecimal, Long>comparingByKey().reversed();
        }
        if (effective == StatsBucketOrder.VALUE_ASC) {
            return Map.Entry.<BigDecimal, Long>comparingByValue().thenComparing(Map.Entry.comparingByKey());
        }
        if (effective == StatsBucketOrder.VALUE_DESC) {
            return Map.Entry.<BigDecimal, Long>comparingByValue().reversed()
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

    private static String metricNumericCountAlias(int index) {
        return "metricNumericCount" + index;
    }

    private boolean isNumericFieldMetric(ResolvedStatsMetric metric) {
        return metric != null
                && metric.descriptor() != null
                && isNumericFieldMetricOperation(metric.metric().operation());
    }

    private boolean isNumericFieldMetricOperation(StatsMetric operation) {
        return operation != null
                && operation != StatsMetric.COUNT
                && operation != StatsMetric.DISTINCT_COUNT;
    }

    private <T> List<T> executeBoundedInMemoryQuery(TypedQuery<T> typedQuery, int maxInMemorySampleRows) {
        int effectiveCap = maxInMemorySampleRows <= 0
                ? StatsProperties.DEFAULT_MAX_IN_MEMORY_SAMPLE_ROWS
                : maxInMemorySampleRows;
        typedQuery.setMaxResults(effectiveCap + 1);
        List<T> rows = typedQuery.getResultList();
        if (rows.size() > effectiveCap) {
            throw new IllegalArgumentException(
                    "Maximum in-memory stats sample rows exceeded (" + effectiveCap + "). Apply narrower filters to reduce the dataset."
            );
        }
        return rows;
    }

    private List<Long> extractSqlAlignedNumericCounts(Tuple tuple, boolean[] tracksNumericCount) {
        List<Long> counts = new ArrayList<>(tracksNumericCount.length);
        boolean anyTracked = false;
        for (int index = 0; index < tracksNumericCount.length; index++) {
            if (!tracksNumericCount[index]) {
                counts.add(0L);
                continue;
            }
            anyTracked = true;
            Number count = (Number) tuple.get(metricNumericCountAlias(index));
            counts.add(count == null ? 0L : Math.max(0L, count.longValue()));
        }
        return anyTracked ? counts : List.of();
    }

    private List<Long> toPositiveNumericCountList(Number count) {
        if (count == null || count.longValue() <= 0L) {
            return List.of();
        }
        return List.of(count.longValue());
    }

    private <E> long countMatchingRows(
            EntityManager entityManager,
            Class<E> entityClass,
            Specification<E> specification
    ) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> countQuery = cb.createQuery(Long.class);
        Root<E> root = countQuery.from(entityClass);
        Predicate predicate = specification == null ? null : specification.toPredicate(root, countQuery, cb);
        if (predicate != null) {
            countQuery.where(predicate);
        }
        countQuery.select(cb.count(root));
        Long total = entityManager.createQuery(countQuery).getSingleResult();
        return total == null ? 0L : total;
    }

    private record SdcTotals(Long populationTotalCount, Long suppressedPoolCount) {
    }

    private static SdcTotals resolveSdcTotals(
            long totalRows,
            long suppressedRowSum,
            int suppressedBucketCount,
            int effectiveMinBucketCount
    ) {
        if (effectiveMinBucketCount <= 0) {
            return new SdcTotals(totalRows, null);
        }
        if (suppressedBucketCount <= 0) {
            if (totalRows == 0L) {
                return new SdcTotals(0L, 0L);
            }
            if (totalRows < effectiveMinBucketCount) {
                return new SdcTotals(null, null);
            }
            return new SdcTotals(totalRows, 0L);
        }
        if (totalRows < effectiveMinBucketCount
                || suppressedRowSum < effectiveMinBucketCount
                || suppressedRowSum < (long) suppressedBucketCount + effectiveMinBucketCount - 1L) {
            return new SdcTotals(null, null);
        }
        return new SdcTotals(totalRows, suppressedRowSum);
    }

    private BigDecimal computeNiceHistogramBucketSize(BigDecimal min, BigDecimal max, int effectiveBucketCount) {
        BigDecimal span = max.subtract(min);
        if (span.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ONE;
        }
        int bucketCount = Math.max(1, effectiveBucketCount);
        double rawStep = span.doubleValue() / bucketCount;
        if (!Double.isFinite(rawStep) || rawStep <= 0d) {
            return BigDecimal.ONE;
        }
        int exponent = (int) Math.floor(Math.log10(rawStep));
        double base = Math.pow(10.0d, exponent);
        double fraction = rawStep / base;
        int niceFactor;
        if (fraction <= 1.0d + 1e-9d) {
            niceFactor = 1;
        } else if (fraction <= 2.0d + 1e-9d) {
            niceFactor = 2;
        } else if (fraction <= 5.0d + 1e-9d) {
            niceFactor = 5;
        } else {
            niceFactor = 10;
        }
        BigDecimal factor = BigDecimal.valueOf(niceFactor);
        BigDecimal power = BigDecimal.TEN.pow(Math.abs(exponent));
        BigDecimal step = exponent >= 0
                ? factor.multiply(power)
                : factor.divide(power);
        return canonicalHistogramBoundary(step);
    }

    private record SuppressionCandidate1D<K>(K key, long rowCount, List<Long> positiveNumericCounts) {
        long effectiveStrength() {
            long strength = rowCount;
            if (positiveNumericCounts != null) {
                for (Long num : positiveNumericCounts) {
                    if (num != null && num > 0L && num < strength) {
                        strength = num;
                    }
                }
            }
            return strength;
        }
    }

    private static boolean violatesMinBucketCount(long rowCount, List<Long> positiveNumericCounts, int minBucketCount) {
        if (minBucketCount <= 0) {
            return false;
        }
        if (rowCount < minBucketCount) {
            return true;
        }
        if (positiveNumericCounts != null) {
            for (Long numericCount : positiveNumericCounts) {
                if (numericCount != null && numericCount > 0L && numericCount < minBucketCount) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void addAlignedNumericCounts(long[] targetSums, List<Long> counts) {
        if (targetSums == null || targetSums.length == 0 || counts == null || counts.isEmpty()) {
            return;
        }
        int bound = Math.min(targetSums.length, counts.size());
        for (int i = 0; i < bound; i++) {
            Long value = counts.get(i);
            if (value != null && value > 0L) {
                targetSums[i] += value;
            }
        }
    }

    private static boolean hasUnderprotectedNumericMetric(long[] suppressedNumericSums, int effectiveMinBucketCount) {
        if (suppressedNumericSums == null || effectiveMinBucketCount <= 0) {
            return false;
        }
        for (long sum : suppressedNumericSums) {
            if (sum > 0L && sum < effectiveMinBucketCount) {
                return true;
            }
        }
        return false;
    }

    private <K> Set<K> apply1DComplementarySuppression(
            List<SuppressionCandidate1D<K>> candidates,
            int effectiveMinBucketCount
    ) {
        if (effectiveMinBucketCount <= 0 || candidates == null || candidates.isEmpty()) {
            return Set.of();
        }
        int numericWidth = 0;
        for (SuppressionCandidate1D<K> candidate : candidates) {
            if (candidate.positiveNumericCounts() != null) {
                numericWidth = Math.max(numericWidth, candidate.positiveNumericCounts().size());
            }
        }
        Set<K> suppressed = new java.util.LinkedHashSet<>();
        List<SuppressionCandidate1D<K>> visible = new ArrayList<>();
        long suppressedRowSum = 0L;
        long[] suppressedNumericSums = new long[numericWidth];
        for (SuppressionCandidate1D<K> candidate : candidates) {
            if (candidate.rowCount() <= 0L) {
                continue;
            }
            if (violatesMinBucketCount(candidate.rowCount(), candidate.positiveNumericCounts(), effectiveMinBucketCount)) {
                suppressed.add(candidate.key());
                suppressedRowSum += candidate.rowCount();
                addAlignedNumericCounts(suppressedNumericSums, candidate.positiveNumericCounts());
            } else {
                visible.add(candidate);
            }
        }
        while (!suppressed.isEmpty()
                && !visible.isEmpty()
                && (suppressed.size() == 1
                    || suppressedRowSum < effectiveMinBucketCount
                    || hasUnderprotectedNumericMetric(suppressedNumericSums, effectiveMinBucketCount))) {
            SuppressionCandidate1D<K> partner = visible.stream()
                    .min((left, right) -> {
                        int strengthCmp = Long.compare(left.effectiveStrength(), right.effectiveStrength());
                        if (strengthCmp != 0) {
                            return strengthCmp;
                        }
                        return compareCoordinateKey(left.key(), right.key());
                    })
                    .orElse(null);
            if (partner == null) {
                break;
            }
            visible.remove(partner);
            suppressed.add(partner.key());
            suppressedRowSum += partner.rowCount();
            addAlignedNumericCounts(suppressedNumericSums, partner.positiveNumericCounts());
        }
        return suppressed;
    }

    private Set<LocalDate> applyTimeSeriesSuppression(
            Map<LocalDate, TimeSeriesBucketAggregate> buckets,
            int effectiveMinBucketCount
    ) {
        List<SuppressionCandidate1D<LocalDate>> candidates = new ArrayList<>();
        for (Map.Entry<LocalDate, TimeSeriesBucketAggregate> entry : buckets.entrySet()) {
            TimeSeriesBucketAggregate bucket = entry.getValue();
            if (bucket.count() > 0L) {
                candidates.add(new SuppressionCandidate1D<>(
                        entry.getKey(),
                        bucket.count(),
                        bucket.positiveNumericCounts()
                ));
            }
        }
        return apply1DComplementarySuppression(candidates, effectiveMinBucketCount);
    }

    private Set<CellCoordinateKey> suppressCellsAntiDifferentiation(
            Map<CellCoordinateKey, MultiDimensionCellAggregate> cells,
            int dimensionCount,
            int effectiveMinBucketCount
    ) {
        if (effectiveMinBucketCount <= 0 || cells.isEmpty()) {
            return Set.of();
        }
        if (dimensionCount <= 1) {
            List<SuppressionCandidate1D<CellCoordinateKey>> candidates = new ArrayList<>(cells.size());
            for (Map.Entry<CellCoordinateKey, MultiDimensionCellAggregate> entry : cells.entrySet()) {
                MultiDimensionCellAggregate cell = entry.getValue();
                if (cell.count() > 0L) {
                    candidates.add(new SuppressionCandidate1D<>(
                            entry.getKey(),
                            cell.count(),
                            cell.positiveNumericCounts()
                    ));
                }
            }
            return apply1DComplementarySuppression(candidates, effectiveMinBucketCount);
        }

        List<SuppressionCandidate1D<CellCoordinateKey>> nonEmptyEntries = new ArrayList<>(cells.size());
        Map<CellCoordinateKey, SuppressionCandidate1D<CellCoordinateKey>> candidateByKey = new LinkedHashMap<>(cells.size());
        Set<CellCoordinateKey> suppressed = new java.util.LinkedHashSet<>();
        int numericWidth = 0;
        long globalSuppressedRowSum = 0L;
        for (Map.Entry<CellCoordinateKey, MultiDimensionCellAggregate> entry : cells.entrySet()) {
            MultiDimensionCellAggregate cell = entry.getValue();
            if (cell.count() <= 0L) {
                continue;
            }
            List<Long> numericCounts = cell.positiveNumericCounts();
            numericWidth = Math.max(numericWidth, numericCounts.size());
            SuppressionCandidate1D<CellCoordinateKey> candidate = new SuppressionCandidate1D<>(
                    entry.getKey(),
                    cell.count(),
                    numericCounts
            );
            nonEmptyEntries.add(candidate);
            candidateByKey.put(entry.getKey(), candidate);
            if (violatesMinBucketCount(cell.count(), candidate.positiveNumericCounts(), effectiveMinBucketCount)) {
                suppressed.add(entry.getKey());
            }
        }
        if (suppressed.isEmpty()) {
            return Set.of();
        }

        long[] globalSuppressedNumericSums = new long[numericWidth];
        for (CellCoordinateKey key : suppressed) {
            SuppressionCandidate1D<CellCoordinateKey> c = candidateByKey.get(key);
            if (c != null) {
                globalSuppressedRowSum += c.rowCount();
                addAlignedNumericCounts(globalSuppressedNumericSums, c.positiveNumericCounts());
            }
        }

        @SuppressWarnings("unchecked")
        Map<CellCoordinateKey, List<SuppressionCandidate1D<CellCoordinateKey>>>[] slicesByAxis =
                new Map[dimensionCount];
        for (int axis = 0; axis < dimensionCount; axis++) {
            slicesByAxis[axis] = new LinkedHashMap<>();
        }
        for (SuppressionCandidate1D<CellCoordinateKey> entry : nonEmptyEntries) {
            List<Object> coords = entry.key().coordinates();
            for (int axis = 0; axis < dimensionCount; axis++) {
                List<Object> marginalCoords = new ArrayList<>(dimensionCount - 1);
                for (int d = 0; d < dimensionCount; d++) {
                    if (d != axis) {
                        marginalCoords.add(coords.get(d));
                    }
                }
                slicesByAxis[axis]
                        .computeIfAbsent(new CellCoordinateKey(marginalCoords), ignored -> new ArrayList<>())
                        .add(entry);
            }
        }

        boolean globalAdded;
        do {
            boolean changed;
            do {
                changed = false;
                for (int axis = 0; axis < dimensionCount; axis++) {
                    for (List<SuppressionCandidate1D<CellCoordinateKey>> slice : slicesByAxis[axis].values()) {
                        int suppressedInSlice = 0;
                        long suppressedSliceRowSum = 0L;
                        long[] suppressedSliceNumericSums = new long[numericWidth];
                        SuppressionCandidate1D<CellCoordinateKey> smallestAvailable = null;
                        for (SuppressionCandidate1D<CellCoordinateKey> entry : slice) {
                            if (suppressed.contains(entry.key())) {
                                suppressedInSlice++;
                                suppressedSliceRowSum += entry.rowCount();
                                addAlignedNumericCounts(suppressedSliceNumericSums, entry.positiveNumericCounts());
                            } else if (smallestAvailable == null
                                    || compareCellStrength(entry, smallestAvailable) < 0) {
                                smallestAvailable = entry;
                            }
                        }
                        if (suppressedInSlice > 0
                                && smallestAvailable != null
                                && (suppressedInSlice == 1
                                    || suppressedSliceRowSum < effectiveMinBucketCount
                                    || hasUnderprotectedNumericMetric(suppressedSliceNumericSums, effectiveMinBucketCount))) {
                            if (suppressed.add(smallestAvailable.key())) {
                                globalSuppressedRowSum += smallestAvailable.rowCount();
                                addAlignedNumericCounts(globalSuppressedNumericSums, smallestAvailable.positiveNumericCounts());
                                changed = true;
                            }
                        }
                    }
                }
            } while (changed);

            globalAdded = false;
            if (!suppressed.isEmpty()
                    && (suppressed.size() == 1
                        || globalSuppressedRowSum < effectiveMinBucketCount
                        || hasUnderprotectedNumericMetric(globalSuppressedNumericSums, effectiveMinBucketCount))) {
                SuppressionCandidate1D<CellCoordinateKey> smallestGlobal = null;
                for (SuppressionCandidate1D<CellCoordinateKey> entry : nonEmptyEntries) {
                    if (!suppressed.contains(entry.key())
                            && (smallestGlobal == null || compareCellStrength(entry, smallestGlobal) < 0)) {
                        smallestGlobal = entry;
                    }
                }
                if (smallestGlobal != null && suppressed.add(smallestGlobal.key())) {
                    globalSuppressedRowSum += smallestGlobal.rowCount();
                    addAlignedNumericCounts(globalSuppressedNumericSums, smallestGlobal.positiveNumericCounts());
                    globalAdded = true;
                }
            }
        } while (globalAdded);

        return suppressed;
    }

    private int compareCellStrength(
            SuppressionCandidate1D<CellCoordinateKey> left,
            SuppressionCandidate1D<CellCoordinateKey> right
    ) {
        int strengthCmp = Long.compare(left.effectiveStrength(), right.effectiveStrength());
        if (strengthCmp != 0) {
            return strengthCmp;
        }
        return compareKeyLists(left.key().coordinates(), right.key().coordinates());
    }

    private Map<String, Number> extractMetricValues(Tuple tuple, List<ResolvedStatsMetric> resolvedMetrics) {
        Map<String, Number> values = new LinkedHashMap<>();
        for (int index = 0; index < resolvedMetrics.size(); index++) {
            values.put(resolvedMetrics.get(index).alias(), (Number) tuple.get(metricTupleAlias(index)));
        }
        return values;
    }

    private int resolveGroupByMinBucketCount(
            List<ResolvedStatsDimension> dimensions,
            List<ResolvedStatsMetric> resolvedMetrics,
            int defaultMinBucketCount
    ) {
        int effective = 0;
        for (ResolvedStatsDimension dimension : dimensions) {
            effective = Math.max(effective, dimension.descriptor().resolveMinBucketCount(defaultMinBucketCount));
        }
        if (resolvedMetrics != null) {
            for (ResolvedStatsMetric metric : resolvedMetrics) {
                if (metric.descriptor() != null && metric.descriptor().minBucketCount() != null) {
                    effective = Math.max(effective, Math.max(0, metric.descriptor().minBucketCount()));
                }
            }
        }
        return effective;
    }

    private int resolveFieldAndMetricsMinBucketCount(
            StatsFieldDescriptor primaryDescriptor,
            List<ResolvedStatsMetric> resolvedMetrics,
            int defaultMinBucketCount
    ) {
        int effective = primaryDescriptor.resolveMinBucketCount(defaultMinBucketCount);
        if (resolvedMetrics != null) {
            for (ResolvedStatsMetric metric : resolvedMetrics) {
                if (metric.descriptor() != null && metric.descriptor().minBucketCount() != null) {
                    effective = Math.max(effective, Math.max(0, metric.descriptor().minBucketCount()));
                }
            }
        }
        return effective;
    }

    private int resolveEffectiveMinBucketCount(
            StatsFieldDescriptor primaryDescriptor,
            StatsFieldDescriptor metricDescriptor,
            int defaultMinBucketCount
    ) {
        int effective = primaryDescriptor.resolveMinBucketCount(defaultMinBucketCount);
        if (metricDescriptor != null && metricDescriptor.minBucketCount() != null) {
            effective = Math.max(effective, Math.max(0, metricDescriptor.minBucketCount()));
        }
        return effective;
    }

    private ZoneId resolveTimeSeriesZone(String timezone) {
        if (timezone == null || timezone.isBlank()) {
            return ZoneOffset.UTC;
        }
        return ZoneId.of(timezone.trim());
    }

    /**
     * Limite superior SQL inclusivo para {@code windowEnd} no {@code zoneId} efetivo.
     *
     * <p>Instantes ({@link Instant}, {@link OffsetDateTime}, {@link ZonedDateTime}, {@link java.sql.Timestamp},
     * {@link java.util.Date}) convertem o teto do calendario de {@code zoneId} para UTC/instante real;
     * {@link LocalDateTime} nao carrega offset proprio e e avaliado diretamente na linha de tempo de
     * {@code zoneId} em alinhamento com {@link StatsDimensionExtraction#toZonedDateTime(Object, ZoneId)}.</p>
     */
    private Predicate intervalUpperBoundPredicate(
            CriteriaBuilder cb,
            Path<?> path,
            LocalDate windowEnd,
            ZoneId zoneId
    ) {
        Class<?> type = path.getJavaType();
        if (type == LocalDate.class) {
            return cb.lessThanOrEqualTo(path.as(LocalDate.class), windowEnd);
        }
        ZonedDateTime upperExclusive = windowEnd.plusDays(1).atStartOfDay(zoneId);
        if (type == LocalDateTime.class) {
            return cb.lessThan(path.as(LocalDateTime.class), windowEnd.plusDays(1).atStartOfDay());
        }
        if (type == Instant.class) {
            return cb.lessThan(path.as(Instant.class), upperExclusive.toInstant());
        }
        if (type == OffsetDateTime.class) {
            return cb.lessThan(path.as(OffsetDateTime.class), upperExclusive.toOffsetDateTime());
        }
        if (type == ZonedDateTime.class) {
            return cb.lessThan(path.as(ZonedDateTime.class), upperExclusive);
        }
        if (type == java.sql.Timestamp.class) {
            return cb.lessThan(path.as(java.sql.Timestamp.class), java.sql.Timestamp.from(upperExclusive.toInstant()));
        }
        if (type == java.sql.Date.class) {
            return cb.lessThanOrEqualTo(path.as(java.sql.Date.class), java.sql.Date.valueOf(windowEnd));
        }
        if (java.util.Date.class.isAssignableFrom(type)) {
            return cb.lessThan(path.as(java.util.Date.class), java.util.Date.from(upperExclusive.toInstant()));
        }
        return null;
    }

    /**
     * Limite inferior SQL inclusivo para {@code windowStart} no {@code zoneId} efetivo,
     * mantendo semantica consistente entre predicados SQL e bucketing via {@link #toLocalDate(Object, ZoneId)}.
     */
    private Predicate intervalLowerBoundPredicate(
            CriteriaBuilder cb,
            Path<?> path,
            LocalDate windowStart,
            ZoneId zoneId
    ) {
        Class<?> type = path.getJavaType();
        if (type == LocalDate.class) {
            return cb.greaterThanOrEqualTo(path.as(LocalDate.class), windowStart);
        }
        ZonedDateTime lowerInclusive = windowStart.atStartOfDay(zoneId);
        if (type == LocalDateTime.class) {
            return cb.greaterThanOrEqualTo(path.as(LocalDateTime.class), windowStart.atStartOfDay());
        }
        if (type == Instant.class) {
            return cb.greaterThanOrEqualTo(path.as(Instant.class), lowerInclusive.toInstant());
        }
        if (type == OffsetDateTime.class) {
            return cb.greaterThanOrEqualTo(path.as(OffsetDateTime.class), lowerInclusive.toOffsetDateTime());
        }
        if (type == ZonedDateTime.class) {
            return cb.greaterThanOrEqualTo(path.as(ZonedDateTime.class), lowerInclusive);
        }
        if (type == java.sql.Timestamp.class) {
            return cb.greaterThanOrEqualTo(path.as(java.sql.Timestamp.class), java.sql.Timestamp.from(lowerInclusive.toInstant()));
        }
        if (type == java.sql.Date.class) {
            return cb.greaterThanOrEqualTo(path.as(java.sql.Date.class), java.sql.Date.valueOf(windowStart));
        }
        if (java.util.Date.class.isAssignableFrom(type)) {
            return cb.greaterThanOrEqualTo(path.as(java.util.Date.class), java.util.Date.from(lowerInclusive.toInstant()));
        }
        return null;
    }

    private LocalDate toBucketStart(Object value, TimeSeriesGranularity granularity, ZoneId zoneId) {
        if (value == null) {
            return null;
        }
        LocalDate date = toLocalDate(value, zoneId);
        return date == null ? null : normalizeStart(date, granularity);
    }

    private LocalDate toLocalDate(Object value, ZoneId zoneId) {
        if (value == null) {
            return null;
        }
        ZonedDateTime zoned = StatsDimensionExtraction.toZonedDateTime(value, zoneId);
        return zoned == null ? null : zoned.toLocalDate();
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
        return JpaCriteriaPathSupport.resolvePath(root, propertyPath, true);
    }

    private <E> boolean isCompositePropertyPath(EntityManager entityManager, Class<E> entityClass, String propertyPath) {
        if (propertyPath == null || propertyPath.isBlank()) {
            return false;
        }
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> probe = cb.createTupleQuery();
        Root<E> root = probe.from(entityClass);
        Path<?> path = resolvePath(root, propertyPath);
        return path != null && CompositeIdSupport.isCompositeIdType(path.getJavaType());
    }

    private Object normalizeStatsKeyValue(Object value) {
        if (value == null) {
            return null;
        }
        if (CompositeIdSupport.isCompositeIdValue(value)) {
            return CompositeIdSupport.stringify(value);
        }
        return value;
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

    private BigDecimal toBigDecimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof java.math.BigInteger integer) {
            return new BigDecimal(integer);
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return BigDecimal.valueOf(((Number) value).longValue());
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        return null;
    }

    private BigDecimal canonicalHistogramBoundary(BigDecimal value) {
        if (value.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 0
                ? stripped.setScale(0, java.math.RoundingMode.UNNECESSARY)
                : stripped;
    }

    private String formatHistogramLabel(BigDecimal from, BigDecimal to) {
        return from.toPlainString() + " - " + to.toPlainString();
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

    private record NumericFieldPlan(
            String propertyPath,
            SelectionSlot slot,
            int sqlNumericIndex,
            boolean needSamples
    ) {
    }

    private record TimeSeriesNumericPlan(
            String propertyPath,
            String tupleAlias,
            boolean needSamples
    ) {
    }

    private static final class FieldSampleAccumulator {
        private long numericCount;
        private double sum;
        private Double min;
        private Double max;
        private double[] samples;
        private int sampleSize;
        private boolean sorted;

        void add(double numeric, boolean needSamples) {
            numericCount++;
            sum += numeric;
            min = min == null ? numeric : Math.min(min, numeric);
            max = max == null ? numeric : Math.max(max, numeric);
            if (needSamples) {
                if (samples == null) {
                    samples = new double[8];
                } else if (sampleSize == samples.length) {
                    samples = Arrays.copyOf(samples, samples.length * 2);
                }
                samples[sampleSize++] = numeric;
                sorted = false;
            }
        }

        Double percentile(double quantile) {
            if (sampleSize == 0 || samples == null) {
                return null;
            }
            if (sampleSize == 1) {
                return samples[0];
            }
            if (!sorted) {
                Arrays.sort(samples, 0, sampleSize);
                sorted = true;
            }
            double pos = quantile * (sampleSize - 1);
            int lower = (int) Math.floor(pos);
            int upper = (int) Math.ceil(pos);
            if (lower == upper) {
                return samples[lower];
            }
            double weight = pos - lower;
            return samples[lower] * (1.0d - weight) + samples[upper] * weight;
        }
    }

    private static final class CellAggregateValue {
        private long rowCount;
        private final Set<Object> distinctValues = new HashSet<>();
        private final boolean hasMetricField;
        private final FieldSampleAccumulator sharedNumeric;
        private FieldSampleAccumulator ownNumeric;

        private Number sqlAggregatedValue;
        private long sqlNumericCount = -1L;
        private boolean hasSqlAggregatedValue;

        private CellAggregateValue(boolean hasMetricField, FieldSampleAccumulator sharedNumeric) {
            this.hasMetricField = hasMetricField;
            this.sharedNumeric = sharedNumeric;
        }

        static CellAggregateValue empty(boolean hasMetricField) {
            return new CellAggregateValue(hasMetricField, null);
        }

        static CellAggregateValue empty(boolean hasMetricField, FieldSampleAccumulator sharedNumeric) {
            return new CellAggregateValue(hasMetricField, sharedNumeric);
        }

        private FieldSampleAccumulator numericAccumulator(boolean createIfMissing) {
            if (sharedNumeric != null) {
                return sharedNumeric;
            }
            if (ownNumeric == null && createIfMissing) {
                ownNumeric = new FieldSampleAccumulator();
            }
            return ownNumeric;
        }

        void setSqlAggregateValue(long bucketRowCount, long sqlNumericCount, Number aggregatedValue) {
            this.rowCount = bucketRowCount;
            this.sqlNumericCount = sqlNumericCount;
            this.sqlAggregatedValue = aggregatedValue;
            this.hasSqlAggregatedValue = true;
        }

        void incrementRowCount() {
            rowCount++;
        }

        void accumulateDistinct(Object value) {
            rowCount++;
            if (value != null) {
                distinctValues.add(value);
            }
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
            numericAccumulator(true).add(number.doubleValue(), metric.isPercentile());
        }

        long numericCount() {
            if (hasSqlAggregatedValue) {
                return sqlNumericCount;
            }
            FieldSampleAccumulator acc = numericAccumulator(false);
            return acc == null ? 0L : acc.numericCount;
        }

        Number value(StatsMetric metric, Long cycleOccurrences) {
            if (cycleOccurrences != null && cycleOccurrences <= 0L) {
                return null;
            }
            if (hasSqlAggregatedValue) {
                return sqlAggregatedValue;
            }
            if (metric == StatsMetric.COUNT) {
                return rowCount;
            }
            if (metric == StatsMetric.DISTINCT_COUNT) {
                return (long) distinctValues.size();
            }
            FieldSampleAccumulator acc = numericAccumulator(false);
            if (metric == StatsMetric.SUM) {
                return (acc == null || acc.numericCount == 0L) ? null : acc.sum;
            }
            if (metric == StatsMetric.AVG) {
                return (acc == null || acc.numericCount == 0L) ? null : acc.sum / acc.numericCount;
            }
            if (metric == StatsMetric.MIN) {
                return acc == null ? null : acc.min;
            }
            if (metric == StatsMetric.MAX) {
                return acc == null ? null : acc.max;
            }
            if (metric.isPercentile()) {
                return acc == null ? null : acc.percentile(metric.percentileQuantile());
            }
            if (metric == StatsMetric.AVG_PER_CYCLE_OCCURRENCE) {
                if (cycleOccurrences == null || cycleOccurrences <= 0L) {
                    return null;
                }
                double numerator = hasMetricField
                        ? (acc == null ? 0d : acc.sum)
                        : (double) rowCount;
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
        private final Map<String, FieldSampleAccumulator> numericAccumulators;

        private MultiDimensionCellAggregate(
                List<Object> keys,
                List<String> labels,
                List<ResolvedStatsMetric> metrics,
                Map<String, CellAggregateValue> aggregates,
                Map<String, FieldSampleAccumulator> numericAccumulators
        ) {
            this.keys = keys;
            this.labels = labels;
            this.metrics = metrics;
            this.aggregates = aggregates;
            this.numericAccumulators = numericAccumulators;
        }

        static MultiDimensionCellAggregate empty(
                List<Object> keys,
                List<String> labels,
                List<ResolvedStatsMetric> metrics
        ) {
            Map<String, FieldSampleAccumulator> numericAccumulators = new LinkedHashMap<>();
            Map<String, CellAggregateValue> aggregates = new LinkedHashMap<>();
            for (ResolvedStatsMetric metric : metrics) {
                boolean hasField = metric.descriptor() != null;
                StatsMetric op = metric.metric().operation();
                FieldSampleAccumulator shared = null;
                if (hasField && op != StatsMetric.COUNT && op != StatsMetric.DISTINCT_COUNT) {
                    shared = numericAccumulators.computeIfAbsent(
                            metric.descriptor().keyPropertyPath(),
                            ignored -> new FieldSampleAccumulator()
                    );
                }
                aggregates.put(metric.alias(), CellAggregateValue.empty(hasField, shared));
            }
            return new MultiDimensionCellAggregate(
                    List.copyOf(keys),
                    List.copyOf(labels),
                    List.copyOf(metrics),
                    aggregates,
                    numericAccumulators
            );
        }

        void accumulate(
                Tuple tuple,
                List<ResolvedStatsMetric> metrics,
                SelectionSlot[] metricSlots,
                List<NumericFieldPlan> numericPlans
        ) {
            count++;
            for (NumericFieldPlan plan : numericPlans) {
                Object val = plan.slot() == null ? null : extractSelectionValue(tuple, plan.slot());
                if (val instanceof Number number) {
                    FieldSampleAccumulator acc = numericAccumulators.get(plan.propertyPath());
                    if (acc != null) {
                        acc.add(number.doubleValue(), plan.needSamples());
                    }
                }
            }
            for (int index = 0; index < metrics.size(); index++) {
                ResolvedStatsMetric metric = metrics.get(index);
                CellAggregateValue cellValue = aggregates.get(metric.alias());
                if (metric.metric().operation() == StatsMetric.DISTINCT_COUNT) {
                    SelectionSlot slot = metricSlots[index];
                    Object value = slot == null ? null : extractSelectionValue(tuple, slot);
                    cellValue.accumulateDistinct(value);
                } else {
                    cellValue.incrementRowCount();
                }
            }
        }

        void accumulateSqlGroup(
                Tuple tuple,
                long bucketRowCount,
                List<ResolvedStatsMetric> metrics,
                int[] sqlMetricTupleIndices,
                int[] sqlNumericCountTupleIndices
        ) {
            this.count = bucketRowCount;
            for (int index = 0; index < metrics.size(); index++) {
                ResolvedStatsMetric metric = metrics.get(index);
                Number aggVal = (Number) tuple.get(sqlMetricTupleIndices[index]);
                long numericCount = sqlNumericCountTupleIndices[index] >= 0
                        ? ((Number) tuple.get(sqlNumericCountTupleIndices[index])).longValue()
                        : -1L;
                aggregates.get(metric.alias()).setSqlAggregateValue(bucketRowCount, numericCount, aggVal);
            }
        }

        List<Long> positiveNumericCounts() {
            List<Long> aligned = new ArrayList<>(metrics.size());
            boolean anyNumeric = false;
            for (ResolvedStatsMetric metric : metrics) {
                StatsMetric op = metric.metric().operation();
                if (metric.descriptor() != null && op != StatsMetric.COUNT && op != StatsMetric.DISTINCT_COUNT) {
                    anyNumeric = true;
                    aligned.add(Math.max(0L, aggregates.get(metric.alias()).numericCount()));
                } else {
                    aligned.add(0L);
                }
            }
            return anyNumeric ? aligned : List.of();
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
        private final Set<Object> distinctValues = new HashSet<>();
        private final FieldSampleAccumulator sharedNumeric;
        private Number sqlAggregatedValue;
        private boolean hasSqlAggregatedValue;

        private AggregateValue(FieldSampleAccumulator sharedNumeric) {
            this.sharedNumeric = sharedNumeric;
        }

        static AggregateValue empty(FieldSampleAccumulator sharedNumeric) {
            return new AggregateValue(sharedNumeric);
        }

        void setSqlAggregateValue(long bucketRowCount, Number aggregatedValue) {
            this.count = bucketRowCount;
            this.sqlAggregatedValue = aggregatedValue;
            this.hasSqlAggregatedValue = true;
        }

        void incrementCount() {
            count++;
        }

        void accumulateDistinct(Object value) {
            count++;
            if (value != null) {
                distinctValues.add(value);
            }
        }

        Number value(StatsMetric metric) {
            if (hasSqlAggregatedValue) {
                return sqlAggregatedValue;
            }
            if (metric == StatsMetric.COUNT) {
                return count;
            }
            if (metric == StatsMetric.DISTINCT_COUNT) {
                return distinctValues.size();
            }
            if (metric == StatsMetric.SUM) {
                if (count == 0L) {
                    return 0d;
                }
                return (sharedNumeric == null || sharedNumeric.numericCount == 0L) ? null : sharedNumeric.sum;
            }
            if (sharedNumeric == null || sharedNumeric.numericCount == 0L) {
                return null;
            }
            if (metric == StatsMetric.AVG) {
                return sharedNumeric.sum / sharedNumeric.numericCount;
            }
            if (metric == StatsMetric.MIN) {
                return sharedNumeric.min;
            }
            if (metric.isPercentile()) {
                return sharedNumeric.percentile(metric.percentileQuantile());
            }
            return sharedNumeric.max;
        }
    }

    private static final class TimeSeriesBucketAggregate {
        private long count;
        private final List<ResolvedStatsMetric> metrics;
        private final Map<String, AggregateValue> aggregates;
        private final Map<String, FieldSampleAccumulator> numericAccumulators;
        private final long[] sqlNumericCounts;

        private TimeSeriesBucketAggregate(
                List<ResolvedStatsMetric> metrics,
                Map<String, AggregateValue> aggregates,
                Map<String, FieldSampleAccumulator> numericAccumulators,
                long count,
                long[] sqlNumericCounts
        ) {
            this.metrics = metrics;
            this.aggregates = aggregates;
            this.numericAccumulators = numericAccumulators;
            this.count = count;
            this.sqlNumericCounts = sqlNumericCounts;
        }

        static TimeSeriesBucketAggregate empty(List<ResolvedStatsMetric> metrics) {
            Map<String, FieldSampleAccumulator> numericAccumulators = new LinkedHashMap<>();
            Map<String, AggregateValue> aggregates = new LinkedHashMap<>();
            for (ResolvedStatsMetric metric : metrics) {
                FieldSampleAccumulator shared = null;
                if (metric.descriptor() != null
                        && metric.metric().operation() != StatsMetric.COUNT
                        && metric.metric().operation() != StatsMetric.DISTINCT_COUNT) {
                    shared = numericAccumulators.computeIfAbsent(
                            metric.descriptor().keyPropertyPath(),
                            ignored -> new FieldSampleAccumulator()
                    );
                }
                aggregates.put(metric.alias(), AggregateValue.empty(shared));
            }
            return new TimeSeriesBucketAggregate(List.copyOf(metrics), aggregates, numericAccumulators, 0L, null);
        }

        static TimeSeriesBucketAggregate fromSqlGroup(
                Tuple tuple,
                long rowCount,
                List<ResolvedStatsMetric> metrics,
                int[] sqlMetricTupleIndices,
                int[] sqlNumericCountTupleIndices
        ) {
            Map<String, AggregateValue> aggregates = new LinkedHashMap<>();
            long[] numericCounts = new long[metrics.size()];
            boolean anyTracked = false;
            for (int index = 0; index < metrics.size(); index++) {
                ResolvedStatsMetric metric = metrics.get(index);
                Number aggVal = (Number) tuple.get(sqlMetricTupleIndices[index]);
                AggregateValue agg = AggregateValue.empty(null);
                agg.setSqlAggregateValue(rowCount, aggVal);
                aggregates.put(metric.alias(), agg);
                if (sqlNumericCountTupleIndices[index] >= 0) {
                    anyTracked = true;
                    Number numericCount = (Number) tuple.get(sqlNumericCountTupleIndices[index]);
                    numericCounts[index] = numericCount == null ? 0L : Math.max(0L, numericCount.longValue());
                }
            }
            return new TimeSeriesBucketAggregate(
                    List.copyOf(metrics),
                    aggregates,
                    Map.of(),
                    rowCount,
                    anyTracked ? numericCounts : null
            );
        }

        void accumulate(Tuple tuple, List<ResolvedStatsMetric> metrics, List<TimeSeriesNumericPlan> numericPlans) {
            count++;
            for (TimeSeriesNumericPlan plan : numericPlans) {
                Object val = tuple.get(plan.tupleAlias());
                if (val instanceof Number number) {
                    FieldSampleAccumulator acc = numericAccumulators.get(plan.propertyPath());
                    if (acc != null) {
                        acc.add(number.doubleValue(), plan.needSamples());
                    }
                }
            }
            for (int index = 0; index < metrics.size(); index++) {
                ResolvedStatsMetric metric = metrics.get(index);
                AggregateValue agg = aggregates.get(metric.alias());
                if (metric.metric().operation() == StatsMetric.DISTINCT_COUNT) {
                    Object value = metric.descriptor() == null ? null : tuple.get(metricTupleAlias(index));
                    agg.accumulateDistinct(value);
                } else {
                    agg.incrementCount();
                }
            }
        }

        List<Long> positiveNumericCounts() {
            if (sqlNumericCounts != null) {
                List<Long> counts = new ArrayList<>(sqlNumericCounts.length);
                for (long nc : sqlNumericCounts) {
                    counts.add(Math.max(0L, nc));
                }
                return counts;
            }
            if (numericAccumulators.isEmpty()) {
                return List.of();
            }
            List<Long> counts = new ArrayList<>(numericAccumulators.size());
            for (FieldSampleAccumulator acc : numericAccumulators.values()) {
                counts.add(Math.max(0L, acc.numericCount));
            }
            return counts;
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
