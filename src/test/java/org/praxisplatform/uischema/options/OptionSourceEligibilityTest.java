package org.praxisplatform.uischema.options;

import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.stats.StatsFieldRegistry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OptionSourceEligibilityTest {

    private final OptionSourceEligibility eligibility = new OptionSourceEligibility();

    @Test
    void enrichesPropertyPathFromStatsRegistry() {
        OptionSourceDescriptor descriptor = new OptionSourceDescriptor(
                "payrollProfile",
                OptionSourceType.DISTINCT_DIMENSION,
                "/api/payroll",
                null,
                null,
                null,
                null,
                null,
                OptionSourcePolicy.defaults()
        );

        OptionSourceDescriptor effective = eligibility.resolveEffectiveDescriptor(
                descriptor,
                StatsFieldRegistry.builder()
                        .categoricalGroupByBucket("payrollProfile", "payrollProfile")
                        .build()
        );

        assertEquals("payrollProfile", effective.propertyPath());
    }

    @Test
    void acceptsDistinctDimensionBackedByDistinctCountMetricField() {
        OptionSourceDescriptor descriptor = new OptionSourceDescriptor(
                "payrollProfile",
                OptionSourceType.DISTINCT_DIMENSION,
                "/api/payroll",
                null,
                null,
                null,
                null,
                null,
                OptionSourcePolicy.defaults()
        );

        OptionSourceDescriptor effective = eligibility.resolveEffectiveDescriptor(
                descriptor,
                StatsFieldRegistry.builder()
                        .distinctCountField("payrollProfile", "payrollProfile")
                        .build()
        );

        assertEquals("payrollProfile", effective.propertyPath());
    }

    @Test
    void rejectsDerivedSourceWithoutPropertyPathOrStatsBridge() {
        OptionSourceDescriptor descriptor = new OptionSourceDescriptor(
                "payrollProfile",
                OptionSourceType.DISTINCT_DIMENSION,
                "/api/payroll",
                null,
                null,
                null,
                null,
                null,
                OptionSourcePolicy.defaults()
        );

        assertThrows(IllegalArgumentException.class, () ->
                eligibility.resolveEffectiveDescriptor(descriptor, StatsFieldRegistry.empty()));
    }
}
