package org.praxisplatform.uischema.stats.service;

import jakarta.persistence.EntityManager;
import org.praxisplatform.uischema.stats.StatsFieldDescriptor;
import org.praxisplatform.uischema.stats.dto.DistributionStatsRequest;
import org.praxisplatform.uischema.stats.dto.DistributionStatsResponse;
import org.praxisplatform.uischema.stats.dto.GroupByStatsRequest;
import org.praxisplatform.uischema.stats.dto.GroupByStatsResponse;
import org.praxisplatform.uischema.stats.dto.TimeSeriesStatsRequest;
import org.praxisplatform.uischema.stats.dto.TimeSeriesStatsResponse;
import org.springframework.data.jpa.domain.Specification;

public interface StatsQueryExecutor {

    <E> GroupByStatsResponse executeGroupBy(
            EntityManager entityManager,
            Class<E> entityClass,
            Specification<E> specification,
            StatsFieldDescriptor groupDescriptor,
            StatsFieldDescriptor metricDescriptor,
            GroupByStatsRequest<?> request,
            int maxBuckets
    );

    <E> TimeSeriesStatsResponse executeTimeSeries(
            EntityManager entityManager,
            Class<E> entityClass,
            Specification<E> specification,
            StatsFieldDescriptor timeDescriptor,
            StatsFieldDescriptor metricDescriptor,
            TimeSeriesStatsRequest<?> request,
            int maxPoints
    );

    <E> DistributionStatsResponse executeDistribution(
            EntityManager entityManager,
            Class<E> entityClass,
            Specification<E> specification,
            StatsFieldDescriptor distributionDescriptor,
            StatsFieldDescriptor metricDescriptor,
            DistributionStatsRequest<?> request,
            int maxBuckets
    );
}
