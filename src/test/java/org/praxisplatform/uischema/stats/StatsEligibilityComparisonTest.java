package org.praxisplatform.uischema.stats;

import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.stats.dto.ComparisonPeriodRequest;
import org.praxisplatform.uischema.stats.dto.ComparisonStatsRequest;
import org.praxisplatform.uischema.stats.dto.StatsMetricRequest;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StatsEligibilityComparisonTest {
    private final StatsEligibility eligibility = new StatsEligibility();
    private final StatsFieldRegistry registry = StatsFieldRegistry.of(List.of(
            StatsFieldDescriptor.categoricalGroupByBucket("department", "department.id"),
            StatsFieldDescriptor.timeSeriesField("createdAt", "createdAt"),
            StatsFieldDescriptor.metricField("amount", "amount", Set.of(StatsMetric.SUM))
    ));

    @Test
    void acceptsTheInitialComparisonMetricSet() {
        assertDoesNotThrow(() -> eligibility.validateComparison(request(new StatsMetricRequest(StatsMetric.COUNT, null)), registry, 20));
        assertDoesNotThrow(() -> eligibility.validateComparison(request(new StatsMetricRequest(StatsMetric.SUM, "amount")), registry, 20));
    }

    @Test
    void rejectsUnsupportedComparisonMetrics() {
        assertThrows(IllegalArgumentException.class, () -> eligibility.validateComparison(
                request(new StatsMetricRequest(StatsMetric.AVG, "amount")), registry, 20
        ));
    }

    private ComparisonStatsRequest<Filter> request(StatsMetricRequest metric) {
        return new ComparisonStatsRequest<>(
                new Filter(), "department", "createdAt", List.of(metric),
                new ComparisonPeriodRequest(ComparisonPeriodPreset.LAST_7_DAYS, null, null, "UTC", null),
                10, StatsBucketOrder.VALUE_DESC
        );
    }

    static class Filter implements GenericFilterDTO { }
}
