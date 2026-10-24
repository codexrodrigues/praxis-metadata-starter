package org.praxisplatform.uischema.stats;

import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.stats.dto.DistributionStatsRequest;
import org.praxisplatform.uischema.stats.dto.GroupByStatsRequest;
import org.praxisplatform.uischema.stats.dto.StatsDimensionRequest;
import org.praxisplatform.uischema.stats.dto.StatsMetricRequest;
import org.praxisplatform.uischema.stats.dto.TimeSeriesStatsRequest;
import org.praxisplatform.uischema.stats.service.ResolvedStatsDimension;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
                .metricField("salary", "salary", Set.of(StatsMetric.SUM, StatsMetric.DISTINCT_COUNT))
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
    void acceptsDistinctCountWithGovernedMetricField() {
        StatsFieldRegistry registry = StatsFieldRegistry.builder()
                .categoricalGroupByBucket("status", "status")
                .distinctCountField("profile", "profile")
                .build();

        GroupByStatsRequest<TestFilterDTO> request = new GroupByStatsRequest<>(
                new TestFilterDTO(),
                "status",
                new StatsMetricRequest(StatsMetric.DISTINCT_COUNT, "profile", "profiles"),
                10,
                null
        );

        assertDoesNotThrow(() -> eligibility.validateGroupBy(request, registry, 50));
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

    @Test
    void acceptsMultiDimensionalCategoricalCrossTab() {
        StatsFieldRegistry registry = StatsFieldRegistry.builder()
                .categoricalGroupByBucket("department", "department")
                .categoricalGroupByBucket("status", "status")
                .metricField("salary", "salary", Set.of(StatsMetric.SUM))
                .build();

        GroupByStatsRequest<TestFilterDTO> request = new GroupByStatsRequest<>(
                new TestFilterDTO(),
                null,
                null,
                50,
                StatsBucketOrder.KEY_ASC,
                List.of(
                        new StatsMetricRequest(StatsMetric.COUNT, null, "count"),
                        new StatsMetricRequest(StatsMetric.SUM, "salary", "totalSalary")
                ),
                List.of(
                        new StatsDimensionRequest("department", StatsDimensionExtraction.NONE, "department"),
                        new StatsDimensionRequest("status", StatsDimensionExtraction.NONE, "status")
                ),
                null,
                null,
                null,
                null,
                true,
                null
        );

        List<ResolvedStatsDimension> dimensions = eligibility.validateGroupByDimensions(
                request,
                registry,
                50,
                2,
                500,
                366
        );
        assertEquals(2, dimensions.size());
        assertEquals("department", dimensions.get(0).alias());
        assertEquals("status", dimensions.get(1).alias());
    }

    @Test
    void acceptsMultiDimensionalTemporalCyclicMatrixWithCycleOccurrenceMetric() {
        StatsFieldRegistry registry = StatsFieldRegistry.builder()
                .temporalTimeSeriesField("occurredAt", "occurredAt")
                .metricField("salary", "salary", Set.of(StatsMetric.SUM, StatsMetric.AVG))
                .build();

        GroupByStatsRequest<TestFilterDTO> request = new GroupByStatsRequest<>(
                new TestFilterDTO(),
                null,
                null,
                168,
                StatsBucketOrder.KEY_ASC,
                List.of(
                        new StatsMetricRequest(StatsMetric.COUNT, null, "count"),
                        new StatsMetricRequest(StatsMetric.AVG_PER_CYCLE_OCCURRENCE, null, "avgCountPerSlot"),
                        new StatsMetricRequest(StatsMetric.AVG_PER_CYCLE_OCCURRENCE, "salary", "avgSalaryPerSlot")
                ),
                List.of(
                        new StatsDimensionRequest("occurredAt", StatsDimensionExtraction.DAY_OF_WEEK, "weekday"),
                        new StatsDimensionRequest("occurredAt", StatsDimensionExtraction.HOUR_OF_DAY, "hour")
                ),
                "America/Sao_Paulo",
                ComparisonPeriodPreset.LAST_30_DAYS,
                null,
                null,
                true,
                null
        );

        List<ResolvedStatsDimension> dimensions = eligibility.validateGroupByDimensions(
                request,
                registry,
                10,
                2,
                500,
                366
        );
        assertEquals(2, dimensions.size());
        assertEquals(StatsDimensionExtraction.DAY_OF_WEEK, dimensions.get(0).extraction());
        assertEquals(StatsDimensionExtraction.HOUR_OF_DAY, dimensions.get(1).extraction());
    }

    @Test
    void rejectsTemporalExtractionWithoutTimezoneOrWithInvalidTimezone() {
        StatsFieldRegistry registry = StatsFieldRegistry.builder()
                .temporalTimeSeriesField("occurredAt", "occurredAt")
                .build();

        GroupByStatsRequest<TestFilterDTO> missingTimezone = new GroupByStatsRequest<>(
                new TestFilterDTO(),
                null,
                new StatsMetricRequest(StatsMetric.COUNT, null, "count"),
                24,
                StatsBucketOrder.KEY_ASC,
                List.of(new StatsMetricRequest(StatsMetric.COUNT, null, "count")),
                List.of(new StatsDimensionRequest("occurredAt", StatsDimensionExtraction.HOUR_OF_DAY, "hour")),
                null,
                null,
                null,
                null,
                true,
                null
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> eligibility.validateGroupByDimensions(missingTimezone, registry, 50, 2, 500, 366)
        );

        GroupByStatsRequest<TestFilterDTO> invalidTimezone = new GroupByStatsRequest<>(
                new TestFilterDTO(),
                null,
                new StatsMetricRequest(StatsMetric.COUNT, null, "count"),
                24,
                StatsBucketOrder.KEY_ASC,
                List.of(new StatsMetricRequest(StatsMetric.COUNT, null, "count")),
                List.of(new StatsDimensionRequest("occurredAt", StatsDimensionExtraction.HOUR_OF_DAY, "hour")),
                "Mars/Phobos",
                null,
                null,
                null,
                true,
                null
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> eligibility.validateGroupByDimensions(invalidTimezone, registry, 50, 2, 500, 366)
        );
    }

    @Test
    void rejectsAvgPerCycleOccurrenceWithoutTemporalWindowOrExtraction() {
        StatsFieldRegistry registry = StatsFieldRegistry.builder()
                .temporalTimeSeriesField("occurredAt", "occurredAt")
                .categoricalGroupByBucket("status", "status")
                .build();

        GroupByStatsRequest<TestFilterDTO> missingWindow = new GroupByStatsRequest<>(
                new TestFilterDTO(),
                null,
                new StatsMetricRequest(StatsMetric.AVG_PER_CYCLE_OCCURRENCE, null, "avgSlot"),
                24,
                StatsBucketOrder.KEY_ASC,
                List.of(new StatsMetricRequest(StatsMetric.AVG_PER_CYCLE_OCCURRENCE, null, "avgSlot")),
                List.of(new StatsDimensionRequest("occurredAt", StatsDimensionExtraction.HOUR_OF_DAY, "hour")),
                "UTC",
                null,
                null,
                null,
                true,
                null
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> eligibility.validateGroupByDimensions(missingWindow, registry, 50, 2, 500, 366)
        );
    }

    @Test
    void rejectsDimensionsExceedingMaxDimensionsOrConflictingWithField() {
        StatsFieldRegistry registry = StatsFieldRegistry.builder()
                .categoricalGroupByBucket("department", "department")
                .categoricalGroupByBucket("status", "status")
                .temporalTimeSeriesField("occurredAt", "occurredAt")
                .build();

        GroupByStatsRequest<TestFilterDTO> threeDimensions = new GroupByStatsRequest<>(
                new TestFilterDTO(),
                null,
                new StatsMetricRequest(StatsMetric.COUNT, null, "count"),
                50,
                StatsBucketOrder.KEY_ASC,
                List.of(new StatsMetricRequest(StatsMetric.COUNT, null, "count")),
                List.of(
                        new StatsDimensionRequest("department", StatsDimensionExtraction.NONE, "department"),
                        new StatsDimensionRequest("status", StatsDimensionExtraction.NONE, "status"),
                        new StatsDimensionRequest("occurredAt", StatsDimensionExtraction.DAY_OF_WEEK, "weekday")
                ),
                "UTC",
                null,
                null,
                null,
                false,
                null
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> eligibility.validateGroupByDimensions(threeDimensions, registry, 50, 2, 500, 366)
        );

        GroupByStatsRequest<TestFilterDTO> conflictingField = new GroupByStatsRequest<>(
                new TestFilterDTO(),
                "status",
                new StatsMetricRequest(StatsMetric.COUNT, null, "count"),
                50,
                StatsBucketOrder.KEY_ASC,
                List.of(new StatsMetricRequest(StatsMetric.COUNT, null, "count")),
                List.of(new StatsDimensionRequest("department", StatsDimensionExtraction.NONE, "department")),
                null,
                null,
                null,
                null,
                false,
                null
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> eligibility.validateGroupByDimensions(conflictingField, registry, 50, 2, 500, 366)
        );
    }

    static class TestFilterDTO implements GenericFilterDTO {
    }
}
