package org.praxisplatform.uischema.stats;

import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.stats.dto.DistributionStatsRequest;
import org.praxisplatform.uischema.stats.dto.GroupByStatsRequest;
import org.praxisplatform.uischema.stats.dto.StatsMetricRequest;
import org.praxisplatform.uischema.stats.dto.TimeSeriesStatsRequest;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StatsEligibilityTest {

    private final StatsEligibility eligibility = new StatsEligibility();

    @Test
    void acceptsGroupByRequestWithMetricsArray() {
        StatsFieldRegistry registry = StatsFieldRegistry.builder()
                .categoricalGroupByBucket("status", "status")
                .metricField("salary", "salary", Set.of(StatsMetric.SUM))
                .build();

        GroupByStatsRequest<TestFilterDTO> request = new GroupByStatsRequest<>(
                new TestFilterDTO(),
                "status",
                null,
                10,
                null,
                List.of(
                        new StatsMetricRequest(StatsMetric.COUNT, null, "total"),
                        new StatsMetricRequest(StatsMetric.SUM, "salary", "salary")
                )
        );

        assertDoesNotThrow(() -> eligibility.validateGroupBy(request, registry, 50));
    }

    @Test
    void acceptsTimeSeriesRequestWithMetricsArray() {
        StatsFieldRegistry registry = StatsFieldRegistry.builder()
                .temporalTimeSeriesField("createdOn", "createdOn")
                .metricField("salary", "salary", Set.of(StatsMetric.SUM))
                .build();

        TimeSeriesStatsRequest<TestFilterDTO> request = new TimeSeriesStatsRequest<>(
                new TestFilterDTO(),
                "createdOn",
                TimeSeriesGranularity.DAY,
                null,
                LocalDate.parse("2026-03-01"),
                LocalDate.parse("2026-03-05"),
                Boolean.TRUE,
                List.of(
                        new StatsMetricRequest(StatsMetric.COUNT, null, "total"),
                        new StatsMetricRequest(StatsMetric.SUM, "salary", "salary")
                )
        );

        assertDoesNotThrow(() -> eligibility.validateTimeSeries(request, registry, 50));
    }

    @Test
    void keepsDistributionMonoMetricInThisPhase() {
        StatsFieldRegistry registry = StatsFieldRegistry.builder()
                .categoricalTermsBucket("status", "status")
                .build();

        DistributionStatsRequest<TestFilterDTO> request = new DistributionStatsRequest<>(
                new TestFilterDTO(),
                "status",
                DistributionMode.TERMS,
                null,
                null,
                null,
                10,
                null
        );

        assertThrows(IllegalArgumentException.class, () -> eligibility.validateDistribution(request, registry, 50));
    }

    @Test
    void rejectsDuplicateMetricAliases() {
        StatsFieldRegistry registry = StatsFieldRegistry.builder()
                .categoricalGroupByBucket("status", "status")
                .metricField("salary", "salary", Set.of(StatsMetric.SUM))
                .build();

        GroupByStatsRequest<TestFilterDTO> request = new GroupByStatsRequest<>(
                new TestFilterDTO(),
                "status",
                null,
                10,
                null,
                List.of(
                        new StatsMetricRequest(StatsMetric.COUNT, null, "value"),
                        new StatsMetricRequest(StatsMetric.SUM, "salary", "value")
                )
        );

        assertThrows(IllegalArgumentException.class, () -> eligibility.validateGroupBy(request, registry, 50));
    }

    static class TestFilterDTO implements GenericFilterDTO {
    }
}
