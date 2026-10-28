package org.praxisplatform.uischema.service.base;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.praxisplatform.uischema.capability.ResourceStructuralCapabilities;
import org.praxisplatform.uischema.exporting.CollectionExportCapability;
import org.praxisplatform.uischema.exporting.CollectionExportFormat;
import org.praxisplatform.uischema.exporting.CollectionExportScope;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.mapper.base.ResourceMapper;
import org.praxisplatform.uischema.options.OptionSourceEligibility;
import org.praxisplatform.uischema.options.service.OptionSourceQueryExecutor;
import org.praxisplatform.uischema.options.OptionSourceRegistry;
import org.praxisplatform.uischema.stats.StatsCapability;
import org.praxisplatform.uischema.stats.StatsEligibility;
import org.praxisplatform.uischema.stats.StatsFieldDescriptor;
import org.praxisplatform.uischema.stats.StatsFieldRegistry;
import org.praxisplatform.uischema.stats.StatsProperties;
import org.praxisplatform.uischema.stats.StatsSupportMode;
import org.praxisplatform.uischema.stats.service.StatsQueryExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResourceStructuralCapabilitiesTest {

    @Test
    void queryAndMutableServicesWithoutPortDoNotOptIn() {
        assertEquals(ResourceStructuralCapabilities.unsupported(),
                mock(BaseResourceQueryService.class, CALLS_REAL_METHODS).getStructuralCapabilities());
        assertEquals(ResourceStructuralCapabilities.unsupported(),
                mock(BaseResourceService.class, CALLS_REAL_METHODS).getStructuralCapabilities());
        assertFalse(new JpaService().getStructuralCapabilities().duplicateDraft());
    }

    @Test
    void directPortAndAggregateInterfaceInheritDefaultTrueWithoutJpa() {
        DirectPortService port = mock(DirectPortService.class, CALLS_REAL_METHODS);
        DirectAggregateService aggregate = mock(DirectAggregateService.class, CALLS_REAL_METHODS);
        for (BaseResourceQueryService<?, ?, ?> service : List.of(port, aggregate)) {
            assertEquals(new ResourceStructuralCapabilities(
                    false, false, false, false, false, false, false, true, null, null),
                    service.getStructuralCapabilities());
        }
        verify(port, never()).duplicateDraft(any());
        verify(aggregate, never()).duplicateDraft(any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void directServiceHonorsExplicitOptIn(boolean supported) {
        DirectAggregateService service = mock(DirectAggregateService.class, CALLS_REAL_METHODS);
        when(service.supportsDuplicateDraft()).thenReturn(supported);
        assertEquals(supported, service.getStructuralCapabilities().duplicateDraft());
        verify(service, never()).duplicateDraft(any());
    }

    @Test
    void jpaAggregateInheritsDefaultTrueWithoutEnablingMissingInfrastructure() {
        ResourceStructuralCapabilities capabilities = new JpaDuplicateService().getStructuralCapabilities();
        assertTrue(capabilities.duplicateDraft());
        assertTrue(capabilities.options());
        assertFalse(capabilities.optionSources());
        assertFalse(capabilities.statsGroupBy());
        assertFalse(capabilities.statsTimeSeries());
        assertFalse(capabilities.statsDistribution());
        assertFalse(capabilities.statsComparison());
        assertEquals(StatsCapability.empty(), capabilities.stats());
        assertTrue(capabilities.export());
        assertEquals(EXPORT, capabilities.exportCapability());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void jpaOverridePreservesOptionsStatsAndExport(boolean supported) {
        JpaDuplicateService service = new JpaDuplicateService() {
            @Override public boolean supportsDuplicateDraft() { return supported; }
        };
        ReflectionTestUtils.setField(service, "statsQueryExecutor", mock(StatsQueryExecutor.class));
        ReflectionTestUtils.setField(service, "statsProperties", new StatsProperties(true, 20, 100, StatsSupportMode.AUTO));
        ReflectionTestUtils.setField(service, "statsEligibility", mock(StatsEligibility.class));
        ReflectionTestUtils.setField(service, "optionSourceQueryExecutor", mock(OptionSourceQueryExecutor.class));
        ReflectionTestUtils.setField(service, "optionSourceEligibility", mock(OptionSourceEligibility.class));

        ResourceStructuralCapabilities capabilities = service.getStructuralCapabilities();
        assertEquals(supported, capabilities.duplicateDraft());
        assertTrue(capabilities.options());
        assertTrue(capabilities.optionSources());
        assertTrue(capabilities.statsGroupBy());
        assertTrue(capabilities.statsTimeSeries());
        assertTrue(capabilities.statsDistribution());
        assertTrue(capabilities.statsComparison());
        assertFalse(capabilities.stats().fields().isEmpty());
        assertTrue(capabilities.export());
        assertEquals(EXPORT, capabilities.exportCapability());
    }

    interface DirectPortService extends BaseResourceQueryService<Response, Long, Filter>,
            DuplicateDraftResourceCommandService<Long, Draft> { }

    interface DirectAggregateService extends DuplicateDraftResourceService<Response, Long, Filter, Draft, Draft, Draft> { }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void controllerBindingProjectsDirectAndJpaOptInThroughCanonicalResolver(boolean supported) throws Exception {
        DirectAggregateService direct = mock(DirectAggregateService.class, CALLS_REAL_METHODS);
        when(direct.supportsDuplicateDraft()).thenReturn(supported);
        JpaDuplicateService jpa = new JpaDuplicateService() {
            @Override public boolean supportsDuplicateDraft() { return supported; }
        };
        for (var service : List.of(direct, jpa)) {
            var controller = new DuplicateController(service);
            var mappings = mock(org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping.class);
            when(mappings.getHandlerMethods()).thenReturn(Map.of(
                    org.springframework.web.servlet.mvc.method.RequestMappingInfo.paths("/records/capabilities").build(),
                    new org.springframework.web.method.HandlerMethod(controller,
                            DuplicateController.class.getMethod("getCollectionCapabilities"))));
            var structural = new org.praxisplatform.uischema.capability.AnnotationDrivenResourceStructuralCapabilityResolver(
                    mappings, mock(org.springframework.context.ApplicationContext.class));
            var resolver = new org.praxisplatform.uischema.capability.OpenApiCanonicalCapabilityResolver(
                    mock(org.praxisplatform.uischema.openapi.OpenApiDocumentService.class), structural);
            var document = new com.fasterxml.jackson.databind.ObjectMapper().readTree("""
                    {"paths":{"/records/{id}/duplicate-draft":{"post":{}}}}
                    """);
            assertEquals(supported, structural.resolve("/records").duplicateDraft());
            assertFalse(structural.resolve("/unknown").duplicateDraft());
            assertEquals(supported, resolver.resolve(document, "/records").get("duplicate-draft"));
            assertEquals(supported, resolver.resolveOperations(document, "/records").get("duplicate-draft").supported());
        }
    }

    @org.praxisplatform.uischema.annotation.ApiResource(value = "/records", resourceKey = "test.records")
    static class DuplicateController extends org.praxisplatform.uischema.controller.base.AbstractDuplicateDraftResourceController<
            Response, Long, Filter, Draft, Draft, Draft> {
        private final DuplicateDraftResourceService<Response, Long, Filter, Draft, Draft, Draft> service;
        DuplicateController(DuplicateDraftResourceService<Response, Long, Filter, Draft, Draft, Draft> service) {
            this.service = service;
        }
        @Override protected DuplicateDraftResourceService<Response, Long, Filter, Draft, Draft, Draft> getService() {
            return service;
        }
        @Override protected Long getResponseId(Response response) { return response.id(); }
    }

    private static final CollectionExportCapability EXPORT = new CollectionExportCapability(
            List.of(CollectionExportFormat.CSV), List.of(CollectionExportScope.ALL), Map.of("ALL", 100), false);

    static class JpaService extends AbstractBaseResourceService<Entity, Response, Long, Filter, Draft, Draft> {
        final OptionSourceRegistry options = OptionSourceRegistry.builder()
                .add(Entity.class, new org.praxisplatform.uischema.options.OptionSourceDescriptor(
                        "categories", org.praxisplatform.uischema.options.OptionSourceType.DISTINCT_DIMENSION,
                        "/records", "category", "category", "category", "category", List.of(),
                        org.praxisplatform.uischema.options.OptionSourcePolicy.defaults()))
                .build();

        JpaService() { super(null, Entity.class); }

        @Override protected ResourceMapper<Entity, Response, Draft, Draft, Long> getResourceMapper() {
            throw new AssertionError("Discovery must not access the mapper or prepare a draft");
        }
        @Override public OptionSourceRegistry getOptionSourceRegistry() { return options; }
        @Override public StatsSupportMode getGroupByStatsSupportMode() { return StatsSupportMode.AUTO; }
        @Override public StatsSupportMode getTimeSeriesStatsSupportMode() { return StatsSupportMode.AUTO; }
        @Override public StatsSupportMode getDistributionStatsSupportMode() { return StatsSupportMode.AUTO; }
        @Override public StatsSupportMode getComparisonStatsSupportMode() { return StatsSupportMode.AUTO; }
        @Override public StatsFieldRegistry getStatsFieldRegistry() {
            return StatsFieldRegistry.of(List.of(
                    StatsFieldDescriptor.categoricalGroupByBucket("category", "category"),
                    StatsFieldDescriptor.temporalTimeSeriesField("date", "date"),
                    StatsFieldDescriptor.categoricalTermsBucket("status", "status")));
        }
        @Override public boolean supportsCollectionExport() { return true; }
        @Override public Optional<CollectionExportCapability> getCollectionExportCapability() { return Optional.of(EXPORT); }
    }

    static class JpaDuplicateService extends JpaService
            implements DuplicateDraftResourceService<Response, Long, Filter, Draft, Draft, Draft> {
        @Override public Draft duplicateDraft(Long sourceId) {
            throw new AssertionError("Discovery must not prepare a draft");
        }
    }

    static class Entity { }
    record Response(Long id) { }
    record Draft(String name) { }
    static class Filter implements GenericFilterDTO { }
}
