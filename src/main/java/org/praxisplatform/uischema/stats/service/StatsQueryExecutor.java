package org.praxisplatform.uischema.stats.service;

import jakarta.persistence.EntityManager;
import org.praxisplatform.uischema.stats.StatsCapability;
import org.praxisplatform.uischema.stats.StatsFieldDescriptor;
import org.praxisplatform.uischema.stats.StatsProperties;
import org.praxisplatform.uischema.stats.dto.ComparisonPeriodWindow;
import org.praxisplatform.uischema.stats.dto.DistributionStatsRequest;
import org.praxisplatform.uischema.stats.dto.DistributionStatsResponse;
import org.praxisplatform.uischema.stats.dto.GroupByStatsRequest;
import org.praxisplatform.uischema.stats.dto.GroupByStatsResponse;
import org.praxisplatform.uischema.stats.dto.TimeSeriesStatsRequest;
import org.praxisplatform.uischema.stats.dto.TimeSeriesStatsResponse;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;
import java.util.Map;

public interface StatsQueryExecutor {

    <E> GroupByStatsResponse executeGroupBy(
            EntityManager entityManager,
            Class<E> entityClass,
            Specification<E> specification,
            StatsFieldDescriptor groupDescriptor,
            List<ResolvedStatsMetric> resolvedMetrics,
            GroupByStatsRequest<?> request,
            int maxBuckets
    );

    default <E> GroupByStatsResponse executeGroupBy(
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
                dimensions.get(0).descriptor(),
                resolvedMetrics,
                request,
                maxBuckets
        );
    }

    default <E> GroupByStatsResponse executeGroupBy(
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
                maxCells
        );
    }

    default <E> GroupByStatsResponse executeGroupBy(
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
                defaultMinBucketCount
        );
    }

    <E> TimeSeriesStatsResponse executeTimeSeries(
            EntityManager entityManager,
            Class<E> entityClass,
            Specification<E> specification,
            StatsFieldDescriptor timeDescriptor,
            List<ResolvedStatsMetric> resolvedMetrics,
            TimeSeriesStatsRequest<?> request,
            int maxPoints
    );

    default <E> TimeSeriesStatsResponse executeTimeSeries(
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
        return executeTimeSeries(
                entityManager,
                entityClass,
                specification,
                timeDescriptor,
                resolvedMetrics,
                request,
                maxPoints
        );
    }

    <E> DistributionStatsResponse executeDistribution(
            EntityManager entityManager,
            Class<E> entityClass,
            Specification<E> specification,
            StatsFieldDescriptor distributionDescriptor,
            StatsFieldDescriptor metricDescriptor,
            DistributionStatsRequest<?> request,
            int maxBuckets
    );

    default <E> DistributionStatsResponse executeDistribution(
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
                maxBuckets
        );
    }

    default <E> DistributionStatsResponse executeDistribution(
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
        return executeDistribution(
                entityManager,
                entityClass,
                specification,
                distributionDescriptor,
                metricDescriptor,
                request,
                maxBuckets,
                defaultMinBucketCount
        );
    }

    /**
     * Calcula o menor {@code COUNT(metricField)} positivo por chave de grupo para os campos numericos
     * solicitados em {@code /stats/comparison}, evitando vazamento quando ha poucos nao-nulos.
     */
    default <E> Map<Object, Long> resolveComparisonPositiveNumericCounts(
            EntityManager entityManager,
            Class<E> entityClass,
            Specification<E> specification,
            StatsFieldDescriptor groupDescriptor,
            List<ResolvedStatsMetric> resolvedMetrics
    ) {
        return Map.of();
    }
}
