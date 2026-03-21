package org.praxisplatform.uischema.stats.service.jpa;

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
import org.praxisplatform.uischema.stats.StatsBucketOrder;
import org.praxisplatform.uischema.stats.StatsFieldDescriptor;
import org.praxisplatform.uischema.stats.TimeSeriesGranularity;
import org.praxisplatform.uischema.stats.DistributionMode;
import org.praxisplatform.uischema.stats.dto.DistributionBucket;
import org.praxisplatform.uischema.stats.dto.DistributionStatsRequest;
import org.praxisplatform.uischema.stats.dto.DistributionStatsResponse;
import org.praxisplatform.uischema.stats.dto.GroupByBucket;
import org.praxisplatform.uischema.stats.dto.GroupByStatsRequest;
import org.praxisplatform.uischema.stats.dto.GroupByStatsResponse;
import org.praxisplatform.uischema.stats.dto.TimeSeriesPoint;
import org.praxisplatform.uischema.stats.dto.TimeSeriesStatsRequest;
import org.praxisplatform.uischema.stats.dto.TimeSeriesStatsResponse;
import org.praxisplatform.uischema.stats.service.StatsQueryExecutor;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAccessor;
import java.time.temporal.TemporalAdjusters;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
            StatsFieldDescriptor metricDescriptor,
            GroupByStatsRequest<?> request,
            int maxBuckets
    ) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root<E> root = query.from(entityClass);
        Path<?> groupPath = resolvePath(root, groupDescriptor.propertyPath());
        Expression<Long> countExpression = cb.count(root);
        Expression<? extends Number> valueExpression = resolveValueExpression(cb, root, request.metric(), metricDescriptor);

        Predicate predicate = specification == null ? null : specification.toPredicate(root, query, cb);
        if (predicate != null) {
            query.where(predicate);
        }

        query.multiselect(groupPath.alias("groupKey"), valueExpression.alias("groupValue"), countExpression.alias("groupCount"));
        query.groupBy(groupPath);
        query.orderBy(resolveOrder(cb, groupPath, valueExpression, request.orderBy()));

        TypedQuery<Tuple> typedQuery = entityManager.createQuery(query);
        int limit = request.limit() == null ? maxBuckets : Math.min(request.limit(), maxBuckets);
        typedQuery.setMaxResults(limit);

        List<GroupByBucket> buckets = typedQuery.getResultList().stream()
                .map(tuple -> {
                    Object key = tuple.get("groupKey");
                    Number value = (Number) tuple.get("groupValue");
                    long count = ((Number) tuple.get("groupCount")).longValue();
                    return new GroupByBucket(key, key == null ? "null" : String.valueOf(key), value, count);
                })
                .toList();

        return new GroupByStatsResponse(groupDescriptor.field(), request.metric(), buckets);
    }

    @Override
    public <E> TimeSeriesStatsResponse executeTimeSeries(
            EntityManager entityManager,
            Class<E> entityClass,
            Specification<E> specification,
            StatsFieldDescriptor timeDescriptor,
            StatsFieldDescriptor metricDescriptor,
            TimeSeriesStatsRequest<?> request,
            int maxPoints
    ) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root<E> root = query.from(entityClass);
        Path<?> timePath = resolvePath(root, timeDescriptor.propertyPath());
        Path<?> metricPath = metricDescriptor == null ? null : resolvePath(root, metricDescriptor.propertyPath());

        Predicate predicate = specification == null ? null : specification.toPredicate(root, query, cb);
        if (predicate != null) {
            query.where(predicate);
        }
        if (metricPath == null) {
            query.multiselect(timePath.alias("timeValue"));
        } else {
            query.multiselect(timePath.alias("timeValue"), metricPath.alias("metricValue"));
        }

        List<Tuple> values = entityManager.createQuery(query).getResultList();
        Map<LocalDate, AggregateValue> buckets = new LinkedHashMap<>();
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
            Number metricValue = metricPath == null ? null : (Number) tuple.get("metricValue");
            buckets.computeIfAbsent(bucketStart, ignored -> AggregateValue.empty())
                    .accumulate(request.metric().operation(), metricValue);
        }

        if (Boolean.TRUE.equals(request.fillGaps())) {
            fillGaps(buckets, request, maxPoints);
        }

        List<TimeSeriesPoint> points = buckets.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .limit(maxPoints)
                .map(entry -> new TimeSeriesPoint(
                        entry.getKey(),
                        bucketEnd(entry.getKey(), request.granularity()),
                        entry.getKey().toString(),
                        entry.getValue().value(request.metric().operation()),
                        entry.getValue().count()
                ))
                .toList();

        return new TimeSeriesStatsResponse(timeDescriptor.field(), request.granularity(), request.metric(), points);
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
        Path<?> groupPath = resolvePath(root, distributionDescriptor.propertyPath());
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
        CriteriaQuery query = cb.createQuery(numberPathJavaType(entityManager, entityClass, descriptor.propertyPath()));
        Root<E> root = query.from(entityClass);
        Path<?> valuePath = resolvePath(root, descriptor.propertyPath());

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
            org.praxisplatform.uischema.stats.dto.StatsMetricRequest metric,
            StatsFieldDescriptor metricDescriptor
    ) {
        if (metric.operation() == org.praxisplatform.uischema.stats.StatsMetric.COUNT) {
            return cb.count(root);
        }
        Path<Number> metricPath = resolveNumericPath(root, metricDescriptor.propertyPath());
        if (metric.operation() == org.praxisplatform.uischema.stats.StatsMetric.SUM) {
            return cb.sum(metricPath);
        }
        if (metric.operation() == org.praxisplatform.uischema.stats.StatsMetric.AVG) {
            return cb.avg(metricPath);
        }
        if (metric.operation() == org.praxisplatform.uischema.stats.StatsMetric.MIN) {
            return cb.min(metricPath);
        }
        return cb.max(metricPath);
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
                    .thenComparing(Map.Entry.<Double, Long>comparingByKey());
        }
        return Map.Entry.comparingByKey();
    }

    private void fillGaps(Map<LocalDate, AggregateValue> buckets, TimeSeriesStatsRequest<?> request, int maxPoints) {
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
            buckets.putIfAbsent(cursor, AggregateValue.empty());
            cursor = nextBucket(cursor, request.granularity());
        }
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
        return null;
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
            } else if (path instanceof Root<?> rootPath) {
                path = rootPath.join(part, JoinType.LEFT);
            } else if (path instanceof From<?, ?> fromPath) {
                path = fromPath.join(part, JoinType.LEFT);
            } else {
                throw new IllegalArgumentException("Unable to resolve stats property path: " + propertyPath);
            }
        }
        return path;
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

    private static final class AggregateValue {
        private long count;
        private double sum;
        private Double min;
        private Double max;

        static AggregateValue empty() {
            return new AggregateValue();
        }

        void accumulate(org.praxisplatform.uischema.stats.StatsMetric metric, Number value) {
            count++;
            if (metric == org.praxisplatform.uischema.stats.StatsMetric.COUNT) {
                return;
            }
            if (value == null) {
                return;
            }
            double numeric = value.doubleValue();
            sum += numeric;
            min = min == null ? numeric : Math.min(min, numeric);
            max = max == null ? numeric : Math.max(max, numeric);
        }

        Number value(org.praxisplatform.uischema.stats.StatsMetric metric) {
            if (metric == org.praxisplatform.uischema.stats.StatsMetric.COUNT) {
                return count;
            }
            if (metric == org.praxisplatform.uischema.stats.StatsMetric.SUM) {
                return sum;
            }
            if (metric == org.praxisplatform.uischema.stats.StatsMetric.AVG) {
                return count == 0 ? 0d : sum / count;
            }
            if (metric == org.praxisplatform.uischema.stats.StatsMetric.MIN) {
                return min == null ? 0d : min;
            }
            return max == null ? 0d : max;
        }

        long count() {
            return count;
        }
    }
}
