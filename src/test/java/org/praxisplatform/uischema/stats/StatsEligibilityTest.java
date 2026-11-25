package org.praxisplatform.uischema.stats;

import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.stats.dto.DistributionStatsRequest;
import org.praxisplatform.uischema.stats.dto.GroupByStatsRequest;
import org.praxisplatform.uischema.stats.dto.RollingWindowRequest;
import org.praxisplatform.uischema.stats.dto.StatsDimensionRequest;
import org.praxisplatform.uischema.stats.dto.StatsMetricRequest;
import org.praxisplatform.uischema.stats.dto.TimeSeriesStatsRequest;
import org.praxisplatform.uischema.stats.service.ResolvedStatsDimension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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

    @Test
    void supportsP25AndP75OnNumericMeasures() {
        StatsFieldRegistry registry = StatsFieldRegistry.builder()
                .categoricalGroupByBucket("department", "department")
                .numericMeasureField("salary", "salary")
                .build();

        GroupByStatsRequest<TestFilterDTO> boxplotRequest = new GroupByStatsRequest<>(
                new TestFilterDTO(),
                "department",
                null,
                10,
                StatsBucketOrder.KEY_ASC,
                List.of(
                        new StatsMetricRequest(StatsMetric.MIN, "salary", "min"),
                        new StatsMetricRequest(StatsMetric.PERCENTILE_25, "salary", "p25"),
                        new StatsMetricRequest(StatsMetric.MEDIAN, "salary", "median"),
                        new StatsMetricRequest(StatsMetric.PERCENTILE_75, "salary", "p75"),
                        new StatsMetricRequest(StatsMetric.MAX, "salary", "max"),
                        new StatsMetricRequest(StatsMetric.COUNT, null, "count")
                )
        );

        assertDoesNotThrow(() -> eligibility.validateGroupBy(boxplotRequest, registry, 50));
    }

    @Test
    void enforcesTemporalIntervalRulesTimeSeriesWindowDaysAndWeekAlignment() {
        StatsFieldRegistry registry = StatsFieldRegistry.builder()
                .temporalTimeSeriesField("createdOn", "createdOn")
                .temporalIntervalTimeSeriesField("activePeriod", "startedAt", "endedAt")
                .build();

        TimeSeriesStatsRequest<TestFilterDTO> intervalMissingBounds = new TimeSeriesStatsRequest<>(
                new TestFilterDTO(),
                "activePeriod",
                TimeSeriesGranularity.MONTH,
                new StatsMetricRequest(StatsMetric.COUNT, null, "active"),
                LocalDate.of(2025, 1, 1),
                null,
                Boolean.TRUE
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> eligibility.validateTimeSeries(intervalMissingBounds, registry, 24, 366)
        );

        TimeSeriesStatsRequest<TestFilterDTO> intervalWindowTooLarge = new TimeSeriesStatsRequest<>(
                new TestFilterDTO(),
                "activePeriod",
                TimeSeriesGranularity.MONTH,
                new StatsMetricRequest(StatsMetric.COUNT, null, "active"),
                LocalDate.of(2024, 1, 1),
                LocalDate.of(2025, 6, 30),
                Boolean.TRUE,
                null,
                "UTC"
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> eligibility.validateTimeSeries(intervalWindowTooLarge, registry, 24, 366)
        );

        TimeSeriesStatsRequest<TestFilterDTO> invalidTimezone = new TimeSeriesStatsRequest<>(
                new TestFilterDTO(),
                "activePeriod",
                TimeSeriesGranularity.DAY,
                new StatsMetricRequest(StatsMetric.COUNT, null, "active"),
                LocalDate.of(2025, 1, 1),
                LocalDate.of(2025, 1, 10),
                Boolean.TRUE,
                null,
                "Invalid/Zone"
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> eligibility.validateTimeSeries(invalidTimezone, registry, 24, 366)
        );

        TimeSeriesStatsRequest<TestFilterDTO> pointInTimeMonthOverYearAllowed = new TimeSeriesStatsRequest<>(
                new TestFilterDTO(),
                "createdOn",
                TimeSeriesGranularity.MONTH,
                new StatsMetricRequest(StatsMetric.COUNT, null, "hires"),
                LocalDate.of(2024, 1, 1),
                LocalDate.of(2025, 6, 30),
                Boolean.FALSE
        );
        assertDoesNotThrow(() -> eligibility.validateTimeSeries(pointInTimeMonthOverYearAllowed, registry, 24, 366));

        // Sunday 2026-03-08 to Monday 2026-03-09 produces 2 Monday-aligned week buckets; maxPoints=1 must reject
        TimeSeriesStatsRequest<TestFilterDTO> twoAlignedWeeks = new TimeSeriesStatsRequest<>(
                new TestFilterDTO(),
                "createdOn",
                TimeSeriesGranularity.WEEK,
                new StatsMetricRequest(StatsMetric.COUNT, null, "hires"),
                LocalDate.of(2026, 3, 8),
                LocalDate.of(2026, 3, 9),
                Boolean.TRUE
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> eligibility.validateTimeSeries(twoAlignedWeeks, registry, 1, 366)
        );

        GroupByStatsRequest<TestFilterDTO> intervalInGroupBy = new GroupByStatsRequest<>(
                new TestFilterDTO(),
                "activePeriod",
                new StatsMetricRequest(StatsMetric.COUNT, null, "count"),
                10,
                null
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> eligibility.validateGroupBy(intervalInGroupBy, registry, 50)
        );

        TimeSeriesStatsRequest<TestFilterDTO> intervalDefaultWindowAllowed = new TimeSeriesStatsRequest<>(
                new TestFilterDTO(),
                "activePeriod",
                TimeSeriesGranularity.MONTH,
                new StatsMetricRequest(StatsMetric.COUNT, null, "active"),
                null,
                null,
                Boolean.TRUE
        );
        assertDoesNotThrow(
                () -> eligibility.validateTimeSeries(intervalDefaultWindowAllowed, registry, 24, 366)
        );

        StatsFieldRegistry salaryRegistry = StatsFieldRegistry.builder()
                .numericHistogramMeasureField("salary", "salary")
                .build();
        DistributionStatsRequest<TestFilterDTO> autoHistogramAllowed = new DistributionStatsRequest<>(
                new TestFilterDTO(),
                "salary",
                DistributionMode.HISTOGRAM,
                new StatsMetricRequest(StatsMetric.COUNT, null),
                null,
                5,
                10,
                StatsBucketOrder.KEY_ASC
        );
        assertDoesNotThrow(() -> eligibility.validateDistribution(autoHistogramAllowed, salaryRegistry, 50));

        DistributionStatsRequest<TestFilterDTO> zeroBucketSizeRejected = new DistributionStatsRequest<>(
                new TestFilterDTO(),
                "salary",
                DistributionMode.HISTOGRAM,
                new StatsMetricRequest(StatsMetric.COUNT, null),
                java.math.BigDecimal.ZERO,
                5,
                10,
                StatsBucketOrder.KEY_ASC
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> eligibility.validateDistribution(zeroBucketSizeRejected, salaryRegistry, 50)
        );
    }

    @Test
    void validatesRollingWindowAndPerGranularityTimeSeriesWindows() {
        StatsFieldRegistry registry = StatsFieldRegistry.builder()
                .temporalTimeSeriesField("createdOn", "createdOn")
                .build();
        Clock fixedClock = Clock.fixed(Instant.parse("2026-11-24T12:00:00Z"), ZoneOffset.UTC);

        // Rolling window 36 months resolves [2023-12-01, 2026-11-24] and bypasses 366-day limit on MONTH point-in-time
        TimeSeriesStatsRequest<TestFilterDTO> rolling36Months = new TimeSeriesStatsRequest<>(
                new TestFilterDTO(),
                "createdOn",
                TimeSeriesGranularity.MONTH,
                new StatsMetricRequest(StatsMetric.COUNT, null, "entries"),
                null,
                null,
                Boolean.TRUE,
                null,
                "America/Sao_Paulo",
                new RollingWindowRequest(RollingWindowUnit.MONTHS, 36)
        );
        assertDoesNotThrow(() -> eligibility.validateTimeSeries(
                rolling36Months,
                registry,
                100,
                366,
                156,
                120,
                fixedClock
        ));

        LocalDate[] bounds = StatsEligibility.resolveRollingWindow(
                new RollingWindowRequest(RollingWindowUnit.MONTHS, 36),
                ZoneOffset.UTC,
                fixedClock
        );
        assertEquals(LocalDate.of(2023, 12, 1), bounds[0]);
        assertEquals(LocalDate.of(2026, 11, 24), bounds[1]);

        // Rejects combining rollingWindow with explicit from/to
        TimeSeriesStatsRequest<TestFilterDTO> conflictingBounds = new TimeSeriesStatsRequest<>(
                new TestFilterDTO(),
                "createdOn",
                TimeSeriesGranularity.MONTH,
                new StatsMetricRequest(StatsMetric.COUNT, null, "entries"),
                LocalDate.of(2025, 1, 1),
                null,
                Boolean.TRUE,
                null,
                "UTC",
                new RollingWindowRequest(RollingWindowUnit.MONTHS, 12)
        );
        assertThrows(IllegalArgumentException.class, () -> eligibility.validateTimeSeries(
                conflictingBounds, registry, 100, 366, 156, 120, fixedClock));

        // Rejects invalid RollingWindowRequest bounds
        assertThrows(IllegalArgumentException.class, () -> new RollingWindowRequest(null, 12));
        assertThrows(IllegalArgumentException.class, () -> new RollingWindowRequest(RollingWindowUnit.MONTHS, 0));
        assertThrows(IllegalArgumentException.class, () -> new RollingWindowRequest(RollingWindowUnit.MONTHS, 121));

        // Rejects WEEK exceeding maxTimeSeriesWindowWeeks and MONTH exceeding maxTimeSeriesWindowMonths
        TimeSeriesStatsRequest<TestFilterDTO> tooManyWeeks = new TimeSeriesStatsRequest<>(
                new TestFilterDTO(),
                "createdOn",
                TimeSeriesGranularity.WEEK,
                new StatsMetricRequest(StatsMetric.COUNT, null, "entries"),
                null,
                null,
                Boolean.TRUE,
                null,
                "UTC",
                new RollingWindowRequest(RollingWindowUnit.WEEKS, 10)
        );
        assertThrows(IllegalArgumentException.class, () -> eligibility.validateTimeSeries(
                tooManyWeeks, registry, 100, 366, 8, 120, fixedClock));

        TimeSeriesStatsRequest<TestFilterDTO> tooManyMonths = new TimeSeriesStatsRequest<>(
                new TestFilterDTO(),
                "createdOn",
                TimeSeriesGranularity.MONTH,
                new StatsMetricRequest(StatsMetric.COUNT, null, "entries"),
                null,
                null,
                Boolean.TRUE,
                null,
                "UTC",
                new RollingWindowRequest(RollingWindowUnit.MONTHS, 36)
        );
        assertThrows(IllegalArgumentException.class, () -> eligibility.validateTimeSeries(
                tooManyMonths, registry, 100, 366, 156, 24, fixedClock));
    }

    static class TestFilterDTO implements GenericFilterDTO {
    }
}
