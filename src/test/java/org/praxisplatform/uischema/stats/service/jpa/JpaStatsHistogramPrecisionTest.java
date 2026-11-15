package org.praxisplatform.uischema.stats.service.jpa;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.stats.DistributionMode;
import org.praxisplatform.uischema.stats.StatsBucketOrder;
import org.praxisplatform.uischema.stats.StatsFieldDescriptor;
import org.praxisplatform.uischema.stats.StatsMetric;
import org.praxisplatform.uischema.stats.dto.DistributionBucket;
import org.praxisplatform.uischema.stats.dto.DistributionStatsRequest;
import org.praxisplatform.uischema.stats.dto.DistributionStatsResponse;
import org.praxisplatform.uischema.stats.dto.StatsMetricRequest;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings({"unchecked", "rawtypes"})
class JpaStatsHistogramPrecisionTest {

    static class DummyMetricEntity {
        BigDecimal aliquota;
    }

    @Test
    void histogramUsesExactBigDecimalBoundariesAndAppliesIsNotNullPredicate() {
        EntityManager entityManager = mock(EntityManager.class);
        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        CriteriaQuery<Tuple> probeQuery = mock(CriteriaQuery.class);
        CriteriaQuery<BigDecimal> valueQuery = mock(CriteriaQuery.class);
        Root<DummyMetricEntity> probeRoot = mock(Root.class);
        Root<DummyMetricEntity> root = mock(Root.class);
        Path probePath = mock(Path.class);
        Path valuePath = mock(Path.class);
        Predicate notNullPredicate = mock(Predicate.class);
        TypedQuery<BigDecimal> typedQuery = mock(TypedQuery.class);

        when(entityManager.getCriteriaBuilder()).thenReturn(cb);
        when(cb.createTupleQuery()).thenReturn(probeQuery);
        when(probeQuery.from(DummyMetricEntity.class)).thenReturn(probeRoot);
        when(probeRoot.get("aliquota")).thenReturn(probePath);
        when(probePath.getJavaType()).thenReturn(BigDecimal.class);

        when(cb.createQuery(BigDecimal.class)).thenReturn((CriteriaQuery) valueQuery);
        when(valueQuery.from(DummyMetricEntity.class)).thenReturn(root);
        when(root.get("aliquota")).thenReturn(valuePath);
        when(cb.isNotNull(valuePath)).thenReturn(notNullPredicate);
        when(valueQuery.where(notNullPredicate)).thenReturn(valueQuery);
        when(valueQuery.select(valuePath)).thenReturn(valueQuery);
        when(entityManager.createQuery(valueQuery)).thenReturn(typedQuery);
        when(typedQuery.getResultList()).thenReturn(Arrays.asList(
                new BigDecimal("0.30"),
                new BigDecimal("0.35"),
                new BigDecimal("0.40")
        ));

        JpaStatsQueryExecutor executor = new JpaStatsQueryExecutor();
        StatsFieldDescriptor descriptor = StatsFieldDescriptor.histogramField("aliquota", "aliquota", Set.of(StatsMetric.COUNT));
        DistributionStatsRequest<?> request = new DistributionStatsRequest<>(
                null,
                "aliquota",
                DistributionMode.HISTOGRAM,
                new StatsMetricRequest(StatsMetric.COUNT, null),
                new BigDecimal("0.1"),
                10,
                10,
                StatsBucketOrder.KEY_ASC
        );

        DistributionStatsResponse response = executor.executeDistribution(
                entityManager,
                DummyMetricEntity.class,
                null,
                descriptor,
                descriptor,
                request,
                25
        );

        verify(cb).isNotNull(valuePath);
        verify(valueQuery).where(notNullPredicate);
        List<DistributionBucket> buckets = response.buckets();
        assertEquals(2, buckets.size());
        assertEquals(new BigDecimal("0.3"), buckets.get(0).from());
        assertEquals(new BigDecimal("0.4"), buckets.get(0).to());
        assertEquals("0.3 - 0.4", buckets.get(0).label());
        assertEquals(2L, buckets.get(0).count());
        assertEquals(new BigDecimal("0.4"), buckets.get(1).from());
        assertEquals(new BigDecimal("0.5"), buckets.get(1).to());
        assertEquals("0.4 - 0.5", buckets.get(1).label());
        assertEquals(1L, buckets.get(1).count());
    }
}
