package org.praxisplatform.uischema.analytics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.action.ActionDefinition;
import org.praxisplatform.uischema.action.ActionDefinitionRegistry;
import org.praxisplatform.uischema.annotation.AiControlledUseMode;
import org.praxisplatform.uischema.annotation.AiUsagePolicy;
import org.praxisplatform.uischema.annotation.AiVisibilityMode;
import org.praxisplatform.uischema.annotation.AnalyticalRecipeGovernanceTier;
import org.praxisplatform.uischema.annotation.AnalyticalRecipeParameter;
import org.praxisplatform.uischema.annotation.AnalyticsDimensionBinding;
import org.praxisplatform.uischema.annotation.AnalyticsIntent;
import org.praxisplatform.uischema.annotation.AnalyticsMetricBinding;
import org.praxisplatform.uischema.annotation.AnalyticsOperation;
import org.praxisplatform.uischema.annotation.AnalyticsProjection;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.annotation.DomainGovernance;
import org.praxisplatform.uischema.annotation.DomainGovernancePreset;
import org.praxisplatform.uischema.annotation.FilterCriterion;
import org.praxisplatform.uischema.annotation.UiAnalytics;
import org.praxisplatform.uischema.annotation.VerifiedAnalyticalRecipe;
import org.praxisplatform.uischema.capability.AvailabilityDecision;
import org.praxisplatform.uischema.capability.CapabilityService;
import org.praxisplatform.uischema.controller.base.AbstractResourceQueryController;
import org.praxisplatform.uischema.domain.DomainCatalogResponse;
import org.praxisplatform.uischema.domain.SemanticDomainCatalogService;
import org.praxisplatform.uischema.filter.annotation.Filterable;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.openapi.CanonicalOperationRef;
import org.praxisplatform.uischema.openapi.CanonicalOperationResolver;
import org.praxisplatform.uischema.options.OptionSourceRegistry;
import org.praxisplatform.uischema.service.base.BaseResourceQueryService;
import org.praxisplatform.uischema.stats.ComparisonPeriodPreset;
import org.praxisplatform.uischema.stats.StatsFieldDescriptor;
import org.praxisplatform.uischema.stats.StatsFieldRegistry;
import org.praxisplatform.uischema.stats.StatsMetric;
import org.praxisplatform.uischema.stats.dto.GroupByBucket;
import org.praxisplatform.uischema.stats.dto.GroupByStatsRequest;
import org.praxisplatform.uischema.stats.dto.GroupByStatsResponse;
import org.praxisplatform.uischema.stats.dto.VerifiedRecipeExecutionRequest;
import org.praxisplatform.uischema.surface.SurfaceDefinitionRegistry;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class VerifiedAnalyticalRecipeTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    @SuppressWarnings("unchecked")
    void publishesVerifiedRecipesInOpenApiAndValidatesAgainstSchema() throws Exception {
        WorkforceController controller = new WorkforceController(mockStatsService());
        RequestMappingHandlerMapping handlerMapping = mock(RequestMappingHandlerMapping.class);
        CanonicalOperationResolver operationResolver = mock(CanonicalOperationResolver.class);

        HandlerMethod groupByHm = new HandlerMethod(
                controller,
                AbstractResourceQueryController.class.getDeclaredMethod("groupByStats", GroupByStatsRequest.class)
        );
        RequestMappingInfo rmi = RequestMappingInfo.paths("/api/workforce/assignments/stats/group-by").build();
        when(handlerMapping.getHandlerMethods()).thenReturn(Map.of(rmi, groupByHm));
        when(operationResolver.resolve(any(HandlerMethod.class), any(RequestMappingInfo.class)))
                .thenReturn(new CanonicalOperationRef("workforce", "groupByStats",
                        "/api/workforce/assignments/stats/group-by", "post"));

        VerifiedAnalyticalRecipeResolver recipeResolver = new VerifiedAnalyticalRecipeResolver(
                handlerMapping, OBJECT_MAPPER, null, List.of());
        UiAnalyticsOpenApiCustomizer customizer = new UiAnalyticsOpenApiCustomizer(
                handlerMapping, operationResolver, new UiAnalyticsAnnotationMapper(), recipeResolver);

        Operation operation = new Operation();
        OpenAPI openAPI = new OpenAPI().paths(new Paths().addPathItem(
                "/api/workforce/assignments/stats/group-by", new PathItem().post(operation)));

        customizer.customise(openAPI);

        Map<String, Object> xUi = (Map<String, Object>) openAPI.getPaths()
                .get("/api/workforce/assignments/stats/group-by")
                .getPost()
                .getExtensions()
                .get("x-ui");
        assertNotNull(xUi);
        Map<String, Object> analytics = (Map<String, Object>) xUi.get("analytics");
        assertNotNull(analytics);
        List<Map<String, Object>> recipes = (List<Map<String, Object>>) analytics.get("verifiedRecipes");
        assertNotNull(recipes);
        assertEquals(2, recipes.size());
        assertEquals("active-headcount-by-department", recipes.get(0).get("id"));
        assertEquals("programmatic-unit-distribution", recipes.get(1).get("id"));

        // Validate x-ui.analytics against canonical draft 2020-12 JSON schema
        String schemaJson = Files.readString(Path.of("docs/spec/x-ui-analytics.schema.json"));
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        JsonSchema schema = factory.getSchema(schemaJson);
        JsonNode instanceNode = OBJECT_MAPPER.valueToTree(analytics);
        Set<ValidationMessage> errors = schema.validate(instanceNode);
        assertTrue(errors.isEmpty(), () -> "Schema validation errors: " + errors);
    }

    @Test
    void executesRecipeServerWinsAndRejectsUndeclaredParamsFailClosed() throws Exception {
        AtomicReference<GroupByStatsRequest<WorkforceFilterDto>> captured = new AtomicReference<>();
        WorkforceController controller = new WorkforceController(mockStatsServiceWithCapture(captured));
        ReflectionTestUtils.setField(controller, "objectMapper", OBJECT_MAPPER);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(get("/api/workforce/assignments/stats/recipes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.data[0].id").value("active-headcount-by-department"))
                .andExpect(jsonPath("$.data[0].lockedFilters.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data[0].provenanceDigest").isNotEmpty());

        // Valid invocation supplying bounded adjustableParams
        mockMvc.perform(post("/api/workforce/assignments/stats/recipes/active-headcount-by-department")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "parameters": {
                                    "departmentCode": "ENG",
                                    "limit": 5
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.provenance.recipeId").value("active-headcount-by-department"))
                .andExpect(jsonPath("$.data.provenance.lockedFilters.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.provenance.appliedParameters.departmentCode").value("ENG"))
                .andExpect(jsonPath("$.data.provenance.appliedParameters.limit").value(5))
                .andExpect(jsonPath("$.data.groupBy.field").value("departmentCode"))
                .andExpect(jsonPath("$.data.groupBy.buckets[0].key").value("ENG"));

        GroupByStatsRequest<WorkforceFilterDto> executed = captured.get();
        assertNotNull(executed);
        assertEquals("ACTIVE", executed.filter().getStatus());
        assertEquals("ENG", executed.filter().getDepartmentCode());
        assertEquals(5, executed.limit());

        // Fail-closed: attempting to inject an undeclared/locked parameter must return 400
        mockMvc.perform(post("/api/workforce/assignments/stats/recipes/active-headcount-by-department")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "parameters": {
                                    "status": "TERMINATED"
                                  }
                                }
                                """))
                .andExpect(status().isBadRequest());

        // Unknown recipe -> 404
        mockMvc.perform(post("/api/workforce/assignments/stats/recipes/non-existent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void publishesVerifiedRecipesIntoSemanticDomainCatalog() {
        WorkforceController controller = new WorkforceController(mockStatsService());
        RequestMappingHandlerMapping handlerMapping = mock(RequestMappingHandlerMapping.class);
        VerifiedAnalyticalRecipeResolver recipeResolver = new VerifiedAnalyticalRecipeResolver(
                handlerMapping, OBJECT_MAPPER, null, List.of());

        ActionDefinitionRegistry actionRegistry = mock(ActionDefinitionRegistry.class);
        when(actionRegistry.findByResourceKey("workforce.assignments")).thenReturn(List.of(
                new ActionDefinition(
                        "filter",
                        "workforce.assignments",
                        "/api/workforce/assignments",
                        "workforce",
                        org.praxisplatform.uischema.action.ActionScope.COLLECTION,
                        "Filtrar",
                        "Filtrar registros de alocacao",
                        new CanonicalOperationRef("workforce", "filter", "/api/workforce/assignments/filter", "post"),
                        null,
                        null,
                        0,
                        null,
                        List.of(),
                        List.of(),
                        List.of("workforce"),
                        null
                )
        ));
        SurfaceDefinitionRegistry surfaceRegistry = mock(SurfaceDefinitionRegistry.class);
        when(surfaceRegistry.findByResourceKey("workforce.assignments")).thenReturn(List.of());

        VerifiedAnalyticalRecipeResolver spyResolver = org.mockito.Mockito.spy(recipeResolver);
        List<ResolvedVerifiedAnalyticalRecipe> resolved = recipeResolver.resolveByController(
                WorkforceController.class, controller);
        when(spyResolver.resolveByResourceKey("workforce.assignments")).thenReturn(resolved);

        SemanticDomainCatalogService catalogService = new SemanticDomainCatalogService(
                actionRegistry,
                surfaceRegistry,
                OptionSourceRegistry.empty(),
                null,
                null,
                Clock.fixed(Instant.parse("2026-11-23T12:00:00Z"), ZoneOffset.UTC),
                "workforce-service",
                "Workforce Service",
                "1.0.0",
                null,
                null,
                null,
                null,
                null,
                null,
                spyResolver
        );

        DomainCatalogResponse response = catalogService.findByResourceKey("workforce.assignments");
        assertTrue(response.nodes().stream().anyMatch(n ->
                "verified_analytical_recipe".equals(n.nodeType())
                        && n.nodeKey().equals("workforce.assignments.analyticalRecipe.active-headcount-by-department")));
        assertTrue(response.edges().stream().anyMatch(e ->
                "has_analytical_recipe".equals(e.edgeType())));
        assertTrue(response.bindings().stream().anyMatch(b ->
                "verified_analytical_recipe".equals(b.bindingType())));
        assertFalse(response.evidence().isEmpty());
    }

    @Test
    void failsFastOnInvalidRecipeDeclarations() {
        DuplicateRecipeController duplicate = new DuplicateRecipeController(mockStatsService());
        VerifiedAnalyticalRecipeResolver resolver = new VerifiedAnalyticalRecipeResolver(null, OBJECT_MAPPER, null, List.of());

        assertThrows(IllegalStateException.class,
                () -> resolver.resolveByController(DuplicateRecipeController.class, duplicate));

        LockedConflictRecipeController conflict = new LockedConflictRecipeController(mockStatsService());
        assertThrows(IllegalStateException.class,
                () -> resolver.resolveByController(LockedConflictRecipeController.class, conflict));

        UnregisteredStatsFieldController unregistered = new UnregisteredStatsFieldController(mockStatsService());
        assertThrows(IllegalStateException.class,
                () -> resolver.resolveByController(UnregisteredStatsFieldController.class, unregistered));

        InvalidComparisonPeriodController invalidComparison = new InvalidComparisonPeriodController(mockStatsService());
        assertThrows(IllegalStateException.class,
                () -> resolver.resolveByController(InvalidComparisonPeriodController.class, invalidComparison));
    }

    @Test
    void enforcesTimezoneAuditFractionalRejectionWindowedGroupByAndNonEmptyRegistry() {
        WorkforceController controller = new WorkforceController(mockStatsService());
        VerifiedAnalyticalRecipeResolver resolver = new VerifiedAnalyticalRecipeResolver(null, OBJECT_MAPPER, null, List.of());
        ResolvedVerifiedAnalyticalRecipe groupByRecipe = resolver.resolveByController(WorkforceController.class, controller)
                .stream()
                .filter(r -> r.id().equals("active-headcount-by-department"))
                .findFirst()
                .orElseThrow();
        StatsFieldRegistry registry = controller.resolveStatsFieldRegistry();

        // 1) Unpermitted timezone override -> IllegalArgumentException
        assertThrows(IllegalArgumentException.class, () -> resolver.compileExecution(
                groupByRecipe,
                WorkforceFilterDto.class,
                registry,
                new VerifiedRecipeExecutionRequest(Map.of(), "America/Recife")));

        // 2) Fractional decimal supplied for integer parameter -> IllegalArgumentException
        assertThrows(IllegalArgumentException.class, () -> resolver.compileExecution(
                groupByRecipe,
                WorkforceFilterDto.class,
                registry,
                new VerifiedRecipeExecutionRequest(Map.of("limit", 10.9), null)));

        // 3) Empty StatsFieldRegistry during compileExecution -> IllegalStateException (fail-closed)
        assertThrows(IllegalStateException.class, () -> resolver.compileExecution(
                groupByRecipe,
                WorkforceFilterDto.class,
                StatsFieldRegistry.empty(),
                new VerifiedRecipeExecutionRequest()));

        // 4) Plain GROUP_BY keeps periodPreset null when periodField is blank
        var plainCompiled = resolver.compileExecution(
                groupByRecipe, WorkforceFilterDto.class, registry, new VerifiedRecipeExecutionRequest());
        assertNull(plainCompiled.groupByRequest().periodPreset());

        // 5) Windowed GROUP_BY with periodField propagates default periodPreset and validates invalid ZoneId
        StatsFieldRegistry windowedRegistry = StatsFieldRegistry.builder()
                .add(StatsFieldDescriptor.groupByBucket("departmentCode", "departmentCode", Set.of(StatsMetric.COUNT)))
                .add(StatsFieldDescriptor.timeSeriesField("effectiveDate", "effectiveDate"))
                .build();
        WindowedGroupByController windowedCtrl = new WindowedGroupByController(mockStatsService(windowedRegistry));
        ResolvedVerifiedAnalyticalRecipe windowedRecipe = resolver.resolveByController(WindowedGroupByController.class, windowedCtrl)
                .get(0);
        var windowedCompiled = resolver.compileExecution(
                windowedRecipe, WorkforceFilterDto.class, windowedRegistry, new VerifiedRecipeExecutionRequest());
        assertEquals(ComparisonPeriodPreset.LAST_30_DAYS, windowedCompiled.groupByRequest().periodPreset());

        assertThrows(IllegalArgumentException.class, () -> resolver.compileExecution(
                windowedRecipe,
                WorkforceFilterDto.class,
                windowedRegistry,
                new VerifiedRecipeExecutionRequest(Map.of(), "Invalid/Timezone_Name")));
    }

    @SuppressWarnings("unchecked")
    private BaseResourceQueryService<WorkforceDto, Long, WorkforceFilterDto> mockStatsService() {
        StatsFieldRegistry registry = StatsFieldRegistry.builder()
                .add(StatsFieldDescriptor.groupByBucket("departmentCode", "departmentCode", Set.of(StatsMetric.COUNT)))
                .add(StatsFieldDescriptor.groupByBucket("unitCode", "unitCode", Set.of(StatsMetric.COUNT)))
                .build();
        return mockStatsService(registry);
    }

    @SuppressWarnings("unchecked")
    private BaseResourceQueryService<WorkforceDto, Long, WorkforceFilterDto> mockStatsService(StatsFieldRegistry registry) {
        return mockStatsServiceWithCapture(new AtomicReference<>(), registry);
    }

    @SuppressWarnings("unchecked")
    private BaseResourceQueryService<WorkforceDto, Long, WorkforceFilterDto> mockStatsServiceWithCapture(
            AtomicReference<GroupByStatsRequest<WorkforceFilterDto>> captured
    ) {
        StatsFieldRegistry registry = StatsFieldRegistry.builder()
                .add(StatsFieldDescriptor.groupByBucket("departmentCode", "departmentCode", Set.of(StatsMetric.COUNT)))
                .add(StatsFieldDescriptor.groupByBucket("unitCode", "unitCode", Set.of(StatsMetric.COUNT)))
                .build();
        return mockStatsServiceWithCapture(captured, registry);
    }

    @SuppressWarnings("unchecked")
    private BaseResourceQueryService<WorkforceDto, Long, WorkforceFilterDto> mockStatsServiceWithCapture(
            AtomicReference<GroupByStatsRequest<WorkforceFilterDto>> captured,
            StatsFieldRegistry registry
    ) {
        BaseResourceQueryService<WorkforceDto, Long, WorkforceFilterDto> service = mock(BaseResourceQueryService.class);
        when(service.getStatsFieldRegistry()).thenReturn(registry);
        when(service.groupByStats(any())).thenAnswer(inv -> {
            GroupByStatsRequest<WorkforceFilterDto> req = inv.getArgument(0);
            captured.set(req);
            return new GroupByStatsResponse(
                    req.field(),
                    req.primaryMetric(),
                    List.of(new GroupByBucket("ENG", "Engineering", 12L, 12L))
            );
        });
        return service;
    }

    @ApiResource(value = "/api/workforce/assignments", resourceKey = "workforce.assignments")
    @UiAnalytics(projections = {
            @AnalyticsProjection(
                    id = "headcount-overview",
                    intent = AnalyticsIntent.RANKING,
                    sourceOperation = AnalyticsOperation.GROUP_BY,
                    primaryDimension = @AnalyticsDimensionBinding(field = "departmentCode"),
                    primaryMetrics = { @AnalyticsMetricBinding(field = "id", aggregation = "count") }
            )
    })
    @VerifiedAnalyticalRecipe(
            id = "active-headcount-by-department",
            title = "Active headcount by department",
            description = "Counts active assignments per department with locked ACTIVE status.",
            intent = "headcount-by-department",
            operation = AnalyticsOperation.GROUP_BY,
            field = "departmentCode",
            metric = StatsMetric.COUNT,
            limit = 10,
            lockedCriteria = { @FilterCriterion(field = "status", values = "ACTIVE") },
            adjustableParams = {
                    @AnalyticalRecipeParameter(name = "departmentCode", dataType = "string"),
                    @AnalyticalRecipeParameter(name = "limit", dataType = "integer", minValue = "1", maxValue = "50")
            },
            governanceTier = AnalyticalRecipeGovernanceTier.CERTIFIED,
            order = 1
    )
    static class WorkforceController extends AbstractResourceQueryController<WorkforceDto, Long, WorkforceFilterDto> {
        private final BaseResourceQueryService<WorkforceDto, Long, WorkforceFilterDto> service;

        WorkforceController(BaseResourceQueryService<WorkforceDto, Long, WorkforceFilterDto> service) {
            this.service = service;
        }

        @Override
        protected BaseResourceQueryService<WorkforceDto, Long, WorkforceFilterDto> getService() {
            return service;
        }

        @Override
        protected Long getResponseId(WorkforceDto dto) {
            return dto.getId();
        }

        @Override
        protected List<VerifiedAnalyticalRecipeDescriptor<WorkforceFilterDto>> defineAnalyticalRecipes() {
            return List.of(
                    VerifiedAnalyticalRecipeDescriptor.<WorkforceFilterDto>builder(
                                    "programmatic-unit-distribution",
                                    "Active assignments by unit")
                            .operation(AnalyticsOperation.GROUP_BY)
                            .field("unitCode")
                            .metric(StatsMetric.COUNT, null)
                            .whereLocked(f -> f.setStatus("ACTIVE"))
                            .governanceTier(AnalyticalRecipeGovernanceTier.OPERATIONAL)
                            .order(2)
                            .build()
            );
        }
    }

    @ApiResource(value = "/api/workforce/dup", resourceKey = "workforce.dup")
    @VerifiedAnalyticalRecipe(id = "dup-id", title = "One", field = "departmentCode")
    @VerifiedAnalyticalRecipe(id = "dup-id", title = "Two", field = "departmentCode")
    static class DuplicateRecipeController extends AbstractResourceQueryController<WorkforceDto, Long, WorkforceFilterDto> {
        private final BaseResourceQueryService<WorkforceDto, Long, WorkforceFilterDto> service;
        DuplicateRecipeController(BaseResourceQueryService<WorkforceDto, Long, WorkforceFilterDto> service) {
            this.service = service;
        }
        @Override protected BaseResourceQueryService<WorkforceDto, Long, WorkforceFilterDto> getService() { return service; }
        @Override protected Long getResponseId(WorkforceDto dto) { return dto.getId(); }
    }

    @ApiResource(value = "/api/workforce/conflict", resourceKey = "workforce.conflict")
    @VerifiedAnalyticalRecipe(
            id = "locked-conflict",
            title = "Locked conflict",
            field = "departmentCode",
            lockedCriteria = { @FilterCriterion(field = "status", values = "ACTIVE") },
            adjustableParams = { @AnalyticalRecipeParameter(name = "status", dataType = "string") }
    )
    static class LockedConflictRecipeController extends AbstractResourceQueryController<WorkforceDto, Long, WorkforceFilterDto> {
        private final BaseResourceQueryService<WorkforceDto, Long, WorkforceFilterDto> service;
        LockedConflictRecipeController(BaseResourceQueryService<WorkforceDto, Long, WorkforceFilterDto> service) {
            this.service = service;
        }
        @Override protected BaseResourceQueryService<WorkforceDto, Long, WorkforceFilterDto> getService() { return service; }
        @Override protected Long getResponseId(WorkforceDto dto) { return dto.getId(); }
    }

    @ApiResource(value = "/api/workforce/unreg", resourceKey = "workforce.unreg")
    @VerifiedAnalyticalRecipe(id = "bad-field", title = "Bad field", field = "unknownStatsField")
    static class UnregisteredStatsFieldController extends AbstractResourceQueryController<WorkforceDto, Long, WorkforceFilterDto> {
        private final BaseResourceQueryService<WorkforceDto, Long, WorkforceFilterDto> service;
        UnregisteredStatsFieldController(BaseResourceQueryService<WorkforceDto, Long, WorkforceFilterDto> service) {
            this.service = service;
        }
        @Override protected BaseResourceQueryService<WorkforceDto, Long, WorkforceFilterDto> getService() { return service; }
        @Override protected Long getResponseId(WorkforceDto dto) { return dto.getId(); }
    }

    @ApiResource(value = "/api/workforce/invalid-comparison", resourceKey = "workforce.invalid.comparison")
    @VerifiedAnalyticalRecipe(
            id = "bad-comparison-period",
            title = "Bad comparison period",
            operation = AnalyticsOperation.COMPARISON,
            field = "departmentCode",
            periodField = "unitCode"
    )
    static class InvalidComparisonPeriodController extends AbstractResourceQueryController<WorkforceDto, Long, WorkforceFilterDto> {
        private final BaseResourceQueryService<WorkforceDto, Long, WorkforceFilterDto> service;
        InvalidComparisonPeriodController(BaseResourceQueryService<WorkforceDto, Long, WorkforceFilterDto> service) {
            this.service = service;
        }
        @Override protected BaseResourceQueryService<WorkforceDto, Long, WorkforceFilterDto> getService() { return service; }
        @Override protected Long getResponseId(WorkforceDto dto) { return dto.getId(); }
    }

    @ApiResource(value = "/api/workforce/windowed-groupby", resourceKey = "workforce.windowed.groupby")
    @VerifiedAnalyticalRecipe(
            id = "windowed-dept",
            title = "Windowed dept",
            operation = AnalyticsOperation.GROUP_BY,
            field = "departmentCode",
            periodField = "effectiveDate",
            adjustableParams = { @AnalyticalRecipeParameter(name = "timezone", dataType = "string") }
    )
    static class WindowedGroupByController extends AbstractResourceQueryController<WorkforceDto, Long, WorkforceFilterDto> {
        private final BaseResourceQueryService<WorkforceDto, Long, WorkforceFilterDto> service;
        WindowedGroupByController(BaseResourceQueryService<WorkforceDto, Long, WorkforceFilterDto> service) {
            this.service = service;
        }
        @Override protected BaseResourceQueryService<WorkforceDto, Long, WorkforceFilterDto> getService() { return service; }
        @Override protected Long getResponseId(WorkforceDto dto) { return dto.getId(); }
    }

    @Test
    void validatesMetricOperationAgainstMetricFieldInsteadOfTimeSeriesDimension() {
        RequestMappingHandlerMapping handlerMapping = mock(RequestMappingHandlerMapping.class);
        VerifiedAnalyticalRecipeResolver resolver = new VerifiedAnalyticalRecipeResolver(
                handlerMapping, OBJECT_MAPPER, null, List.of());

        StatsFieldRegistry timeSeriesRegistry = StatsFieldRegistry.builder()
                .temporalTimeSeriesField("effectiveDate", "effectiveDate")
                .numericHistogramMeasureField("grossAmount", "grossAmount")
                .build();
        TimeSeriesRevenueController validCtrl = new TimeSeriesRevenueController(mockStatsService(timeSeriesRegistry));
        ResolvedVerifiedAnalyticalRecipe validRecipe = resolver.resolveByController(
                TimeSeriesRevenueController.class, validCtrl).get(0);
        var compiled = resolver.compileExecution(
                validRecipe,
                WorkforceFilterDto.class,
                timeSeriesRegistry,
                new VerifiedRecipeExecutionRequest());
        assertEquals(StatsMetric.SUM, compiled.timeSeriesRequest().primaryMetric().operation());
        assertEquals("grossAmount", compiled.timeSeriesRequest().primaryMetric().field());

        StatsFieldRegistry minOnlyRegistry = StatsFieldRegistry.builder()
                .categoricalGroupByBucket("departmentCode", "departmentCode")
                .metricField("minOnlyAmount", "minOnlyAmount", Set.of(StatsMetric.MIN))
                .build();
        InvalidMetricOnMeasureController invalidCtrl =
                new InvalidMetricOnMeasureController(mockStatsService(minOnlyRegistry));
        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> resolver.resolveByController(InvalidMetricOnMeasureController.class, invalidCtrl));
        assertTrue(ex.getMessage().contains("is not allowed on metric field 'minOnlyAmount'"));
    }

    @Test
    void coercesAndValidatesDateRangeAdjustableParameterFailClosed() {
        RequestMappingHandlerMapping handlerMapping = mock(RequestMappingHandlerMapping.class);
        VerifiedAnalyticalRecipeResolver resolver = new VerifiedAnalyticalRecipeResolver(
                handlerMapping, OBJECT_MAPPER, null, List.of());

        StatsFieldRegistry timeSeriesRegistry = StatsFieldRegistry.builder()
                .temporalTimeSeriesField("effectiveDate", "effectiveDate")
                .numericHistogramMeasureField("grossAmount", "grossAmount")
                .build();
        TimeSeriesRevenueController ctrl = new TimeSeriesRevenueController(mockStatsService(timeSeriesRegistry));
        ResolvedVerifiedAnalyticalRecipe recipe = resolver.resolveByController(
                TimeSeriesRevenueController.class, ctrl).get(0);

        var compiledFromList = resolver.compileExecution(
                recipe,
                WorkforceFilterDto.class,
                timeSeriesRegistry,
                new VerifiedRecipeExecutionRequest(
                        Map.of("effectiveDateBetween", List.of("2026-01-01", "2026-06-30")), null));
        assertEquals(
                List.of(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30)),
                compiledFromList.timeSeriesRequest().filter().getEffectiveDateBetween());

        var compiledFromCsv = resolver.compileExecution(
                recipe,
                WorkforceFilterDto.class,
                timeSeriesRegistry,
                new VerifiedRecipeExecutionRequest(
                        Map.of("effectiveDateBetween", "2026-02-01,2026-02-28"), null));
        assertEquals(
                List.of(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28)),
                compiledFromCsv.timeSeriesRequest().filter().getEffectiveDateBetween());

        assertThrows(IllegalArgumentException.class, () -> resolver.compileExecution(
                recipe,
                WorkforceFilterDto.class,
                timeSeriesRegistry,
                new VerifiedRecipeExecutionRequest(
                        Map.of("effectiveDateBetween", List.of("2026-12-01", "2026-01-01")), null)));

        assertThrows(IllegalArgumentException.class, () -> resolver.compileExecution(
                recipe,
                WorkforceFilterDto.class,
                timeSeriesRegistry,
                new VerifiedRecipeExecutionRequest(
                        Map.of("effectiveDateBetween", List.of("2026-01-01")), null)));

        assertThrows(IllegalArgumentException.class, () -> resolver.compileExecution(
                recipe,
                WorkforceFilterDto.class,
                timeSeriesRegistry,
                new VerifiedRecipeExecutionRequest(
                        Map.of("effectiveDateBetween", "2026-01-01"), null)));
    }

    @ApiResource(value = "/api/workforce/ts-gross", resourceKey = "workforce.ts.gross")
    @VerifiedAnalyticalRecipe(
            id = "monthly-gross-sum",
            title = "Monthly gross amount sum",
            operation = AnalyticsOperation.TIMESERIES,
            field = "effectiveDate",
            metric = StatsMetric.SUM,
            metricField = "grossAmount",
            adjustableParams = {
                    @AnalyticalRecipeParameter(name = "effectiveDateBetween", dataType = "date-range", minValue = "2020-01-01")
            }
    )
    static class TimeSeriesRevenueController extends AbstractResourceQueryController<WorkforceDto, Long, WorkforceFilterDto> {
        private final BaseResourceQueryService<WorkforceDto, Long, WorkforceFilterDto> service;
        TimeSeriesRevenueController(BaseResourceQueryService<WorkforceDto, Long, WorkforceFilterDto> service) {
            this.service = service;
        }
        @Override protected BaseResourceQueryService<WorkforceDto, Long, WorkforceFilterDto> getService() { return service; }
        @Override protected Long getResponseId(WorkforceDto dto) { return dto.getId(); }
    }

    @ApiResource(value = "/api/workforce/invalid-metric-field", resourceKey = "workforce.invalid.metric")
    @VerifiedAnalyticalRecipe(
            id = "bad-sum-on-min-only",
            title = "Invalid sum on min-only field",
            operation = AnalyticsOperation.GROUP_BY,
            field = "departmentCode",
            metric = StatsMetric.SUM,
            metricField = "minOnlyAmount"
    )
    static class InvalidMetricOnMeasureController extends AbstractResourceQueryController<WorkforceDto, Long, WorkforceFilterDto> {
        private final BaseResourceQueryService<WorkforceDto, Long, WorkforceFilterDto> service;
        InvalidMetricOnMeasureController(BaseResourceQueryService<WorkforceDto, Long, WorkforceFilterDto> service) {
            this.service = service;
        }
        @Override protected BaseResourceQueryService<WorkforceDto, Long, WorkforceFilterDto> getService() { return service; }
        @Override protected Long getResponseId(WorkforceDto dto) { return dto.getId(); }
    }

    static class WorkforceDto {
        private Long id;
        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
    }

    static class WorkforceFilterDto implements GenericFilterDTO {
        @Filterable
        private String status;
        @Filterable
        private String departmentCode;
        @Filterable(operation = Filterable.FilterOperation.BETWEEN, relation = "effectiveDate")
        private List<LocalDate> effectiveDateBetween;

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getDepartmentCode() { return departmentCode; }
        public void setDepartmentCode(String departmentCode) { this.departmentCode = departmentCode; }
        public List<LocalDate> getEffectiveDateBetween() { return effectiveDateBetween; }
        public void setEffectiveDateBetween(List<LocalDate> effectiveDateBetween) { this.effectiveDateBetween = effectiveDateBetween; }
    }

    @Test
    @SuppressWarnings("unchecked")
    void enforcesOperationAvailabilityGovernanceAndHateoasLinkOnRecipeEndpoints() throws Exception {
        WorkforceController controller = new WorkforceController(mockStatsService());
        CapabilityService capabilityService = mock(CapabilityService.class);
        when(capabilityService.withOperationAvailabilityScope(any(), any())).thenAnswer(inv -> {
            java.util.function.Supplier<?> action = inv.getArgument(1);
            return action.get();
        });
        when(capabilityService.collectionOperationAvailability(any(), any(), any())).thenAnswer(inv -> {
            String opId = inv.getArgument(2);
            if ("statsGroupBy".equals(opId)) {
                return AvailabilityDecision.deny("ROLE_MISSING", Map.of("message", "statsGroupBy denied"));
            }
            return AvailabilityDecision.allowAll();
        });
        ReflectionTestUtils.setField(controller, "capabilityService", capabilityService);
        ReflectionTestUtils.invokeMethod(controller, "initializeBasePath");

        MockMvc blockedMvc = MockMvcBuilders.standaloneSetup(controller).build();
        blockedMvc.perform(get("/api/workforce/assignments/stats/recipes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0))
                .andExpect(jsonPath("$._links.stats-recipes.href").value("/api/workforce/assignments/stats/recipes"));

        blockedMvc.perform(post("/api/workforce/assignments/stats/recipes/active-headcount-by-department")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());

        WorkforceController openController = new WorkforceController(mockStatsService());
        CapabilityService openCapService = mock(CapabilityService.class);
        when(openCapService.withOperationAvailabilityScope(any(), any())).thenAnswer(inv -> {
            java.util.function.Supplier<?> action = inv.getArgument(1);
            return action.get();
        });
        when(openCapService.collectionOperationAvailability(any(), any(), any()))
                .thenReturn(AvailabilityDecision.allowAll());
        ReflectionTestUtils.setField(openController, "capabilityService", openCapService);
        ReflectionTestUtils.invokeMethod(openController, "initializeBasePath");

        MockMvc openMvc = MockMvcBuilders.standaloneSetup(openController).build();
        openMvc.perform(post("/api/workforce/assignments/stats/recipes/active-headcount-by-department")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parameters\":{\"limit\":5}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._links.stats-recipes.href").value("/api/workforce/assignments/stats/recipes"));

        List<org.springframework.hateoas.Link> discoveryLinks = (List<org.springframework.hateoas.Link>)
                ReflectionTestUtils.invokeMethod(openController, "buildCollectionDiscoveryLinks");
        assertNotNull(discoveryLinks);
        assertTrue(discoveryLinks.stream().anyMatch(link ->
                "stats-recipes".equals(link.getRel().value())
                        && "/api/workforce/assignments/stats/recipes".equals(link.getHref())));

        StatsFieldRegistry restrictedRegistry = StatsFieldRegistry.builder()
                .add(StatsFieldDescriptor.groupByBucket("taxId", "taxId", Set.of(StatsMetric.COUNT)))
                .add(StatsFieldDescriptor.groupByBucket("departmentCode", "departmentCode", Set.of(StatsMetric.COUNT)))
                .build();
        RestrictedGovernanceController restrictedCtrl = new RestrictedGovernanceController(
                mockRestrictedStatsService(restrictedRegistry));
        MockMvc govMvc = MockMvcBuilders.standaloneSetup(restrictedCtrl).build();
        govMvc.perform(post("/api/workforce/restricted/stats/recipes/restricted-field-recipe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
        govMvc.perform(post("/api/workforce/restricted/stats/recipes/restricted-filter-param-recipe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parameters\":{\"auditNoteFilter\":\"abc\"}}"))
                .andExpect(status().isForbidden());
    }

    @SuppressWarnings("unchecked")
    private BaseResourceQueryService<RestrictedWorkforceDto, Long, RestrictedWorkforceFilterDto> mockRestrictedStatsService(
            StatsFieldRegistry registry
    ) {
        BaseResourceQueryService<RestrictedWorkforceDto, Long, RestrictedWorkforceFilterDto> service = mock(BaseResourceQueryService.class);
        when(service.getStatsFieldRegistry()).thenReturn(registry);
        return service;
    }

    static class RestrictedWorkforceDto {
        private Long id;
        private String departmentCode;
        @DomainGovernance(preset = DomainGovernancePreset.LGPD_PERSONAL_ID, reason = "Tax identifier is LGPD PII")
        private String taxId;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getDepartmentCode() { return departmentCode; }
        public void setDepartmentCode(String departmentCode) { this.departmentCode = departmentCode; }
        public String getTaxId() { return taxId; }
        public void setTaxId(String taxId) { this.taxId = taxId; }
    }

    static class RestrictedWorkforceFilterDto implements GenericFilterDTO {
        @Filterable
        private String departmentCode;
        @Filterable
        @DomainGovernance(
                reason = "Reasoning denied on internal audit note filter",
                aiUsage = @AiUsagePolicy(visibility = AiVisibilityMode.ALLOW, reasoningUse = AiControlledUseMode.DENY)
        )
        private String auditNoteFilter;

        public String getDepartmentCode() { return departmentCode; }
        public void setDepartmentCode(String departmentCode) { this.departmentCode = departmentCode; }
        public String getAuditNoteFilter() { return auditNoteFilter; }
        public void setAuditNoteFilter(String auditNoteFilter) { this.auditNoteFilter = auditNoteFilter; }
    }

    @ApiResource(value = "/api/workforce/restricted", resourceKey = "workforce.restricted")
    @VerifiedAnalyticalRecipe(
            id = "restricted-field-recipe",
            title = "Restricted taxId recipe",
            operation = AnalyticsOperation.GROUP_BY,
            field = "taxId",
            metric = StatsMetric.COUNT
    )
    @VerifiedAnalyticalRecipe(
            id = "restricted-filter-param-recipe",
            title = "Restricted filter param recipe",
            operation = AnalyticsOperation.GROUP_BY,
            field = "departmentCode",
            metric = StatsMetric.COUNT,
            adjustableParams = {
                    @AnalyticalRecipeParameter(name = "auditNoteFilter", dataType = "string")
            }
    )
    static class RestrictedGovernanceController
            extends AbstractResourceQueryController<RestrictedWorkforceDto, Long, RestrictedWorkforceFilterDto> {
        private final BaseResourceQueryService<RestrictedWorkforceDto, Long, RestrictedWorkforceFilterDto> service;

        RestrictedGovernanceController(
                BaseResourceQueryService<RestrictedWorkforceDto, Long, RestrictedWorkforceFilterDto> service
        ) {
            this.service = service;
        }

        @Override
        protected BaseResourceQueryService<RestrictedWorkforceDto, Long, RestrictedWorkforceFilterDto> getService() {
            return service;
        }

        @Override
        protected Long getResponseId(RestrictedWorkforceDto dto) {
            return dto.getId();
        }
    }
}
