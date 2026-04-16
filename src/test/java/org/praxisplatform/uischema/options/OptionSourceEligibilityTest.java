package org.praxisplatform.uischema.options;

import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.stats.StatsFieldRegistry;

import java.util.List;

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

    @Test
    void leavesLegacyResourceEntityDescriptorUntouchedWhenLookupMetadataIsNotDeclared() {
        OptionSourceDescriptor descriptor = new OptionSourceDescriptor(
                "departmentLookup",
                OptionSourceType.RESOURCE_ENTITY,
                "/api/departments",
                null,
                null,
                null,
                null,
                null,
                OptionSourcePolicy.defaults()
        );

        assertEquals(descriptor, eligibility.resolveEffectiveDescriptor(descriptor, StatsFieldRegistry.empty()));
    }

    @Test
    void requiresValueLabelAndEntityKeyForRichResourceEntityLookupMetadata() {
        OptionSourceDescriptor missingValue = new OptionSourceDescriptor(
                "company",
                OptionSourceType.RESOURCE_ENTITY,
                "/api/companies",
                null,
                null,
                "legalName",
                null,
                null,
                OptionSourcePolicy.defaults(),
                new EntityLookupDescriptor(
                        "company",
                        "code",
                        List.of(),
                        "status",
                        null,
                        null,
                        List.of("code", "legalName"),
                        null,
                        null,
                        LookupCapabilities.defaults(),
                        null
                )
        );

        assertThrows(IllegalArgumentException.class, () ->
                eligibility.resolveEffectiveDescriptor(missingValue, StatsFieldRegistry.empty()));

        OptionSourceDescriptor missingLabel = new OptionSourceDescriptor(
                "company",
                OptionSourceType.RESOURCE_ENTITY,
                "/api/companies",
                null,
                null,
                null,
                "id",
                null,
                OptionSourcePolicy.defaults(),
                new EntityLookupDescriptor(
                        "company",
                        "code",
                        List.of(),
                        "status",
                        null,
                        null,
                        List.of("code", "legalName"),
                        null,
                        null,
                        LookupCapabilities.defaults(),
                        null
                )
        );

        assertThrows(IllegalArgumentException.class, () ->
                eligibility.resolveEffectiveDescriptor(missingLabel, StatsFieldRegistry.empty()));

        OptionSourceDescriptor missingEntityKey = new OptionSourceDescriptor(
                "company",
                OptionSourceType.RESOURCE_ENTITY,
                "/api/companies",
                null,
                null,
                "legalName",
                "id",
                null,
                OptionSourcePolicy.defaults(),
                new EntityLookupDescriptor(
                        " ",
                        "code",
                        List.of(),
                        "status",
                        null,
                        null,
                        List.of("code", "legalName"),
                        null,
                        null,
                        LookupCapabilities.defaults(),
                        null
                )
        );

        assertThrows(IllegalArgumentException.class, () ->
                eligibility.resolveEffectiveDescriptor(missingEntityKey, StatsFieldRegistry.empty()));
    }
}
