package org.praxisplatform.uischema.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.SpecVersion;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.action.ActionCatalogResponse;
import org.praxisplatform.uischema.action.ActionCatalogService;
import org.praxisplatform.uischema.action.ActionDefinition;
import org.praxisplatform.uischema.action.AnnotationDrivenActionDefinitionRegistry;
import org.praxisplatform.uischema.analytics.UiAnalyticsAnnotationMapper;
import org.praxisplatform.uischema.analytics.UiAnalyticsOpenApiCustomizer;
import org.praxisplatform.uischema.annotation.AnalyticsDimensionBinding;
import org.praxisplatform.uischema.annotation.AnalyticsIntent;
import org.praxisplatform.uischema.annotation.AnalyticsMetricBinding;
import org.praxisplatform.uischema.annotation.AnalyticsOperation;
import org.praxisplatform.uischema.annotation.AnalyticsPresentationFamily;
import org.praxisplatform.uischema.annotation.AnalyticsProjection;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.annotation.BulkEditable;
import org.praxisplatform.uischema.annotation.CalculationMemory;
import org.praxisplatform.uischema.annotation.CalculationStep;
import org.praxisplatform.uischema.annotation.CalculationTrail;
import org.praxisplatform.uischema.annotation.LegalTreatmentPurpose;
import org.praxisplatform.uischema.annotation.QuickFilterTone;
import org.praxisplatform.uischema.annotation.ReactiveDetermination;
import org.praxisplatform.uischema.annotation.StepKind;
import org.praxisplatform.uischema.annotation.UiAnalytics;
import org.praxisplatform.uischema.annotation.WorkflowAction;
import org.praxisplatform.uischema.bulk.BulkEditableFields;
import org.praxisplatform.uischema.bulk.BulkMode;
import org.praxisplatform.uischema.capability.AvailabilityDecision;
import org.praxisplatform.uischema.capability.ResourceStateSnapshot;
import org.praxisplatform.uischema.configuration.OpenApiUiSchemaAutoConfiguration;
import org.praxisplatform.uischema.determination.AnnotationDrivenReactiveDeterminationDefinitionProvider;
import org.praxisplatform.uischema.determination.ReactiveDeterminationDefinition;
import org.praxisplatform.uischema.determination.ReactiveDeterminationFormMode;
import org.praxisplatform.uischema.determination.ReactiveDeterminationTriggerMode;
import org.praxisplatform.uischema.filter.annotation.Filterable;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.filter.relativeperiod.RelativePeriodPayloadNormalizer;
import org.praxisplatform.uischema.filter.specification.GenericSpecificationsBuilder;
import org.praxisplatform.uischema.mapper.base.ResourceMapper;
import org.praxisplatform.uischema.openapi.CanonicalOperationRef;
import org.praxisplatform.uischema.openapi.CanonicalOperationResolver;
import org.praxisplatform.uischema.repository.base.BaseCrudRepository;
import org.praxisplatform.uischema.rest.response.RestApiResponse;
import org.praxisplatform.uischema.schema.CalculationMemoryMetadataResolver;
import org.praxisplatform.uischema.schema.CanonicalSchemaRef;
import org.praxisplatform.uischema.schema.SchemaReferenceResolver;
import org.praxisplatform.uischema.service.base.AbstractBaseResourceService;
import org.praxisplatform.uischema.service.base.annotation.DefaultSortColumn;
import org.praxisplatform.uischema.validation.AnnotationConflictMode;
import org.praxisplatform.uischema.validation.PraxisExpressionStaticValidator;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PhaseABehavioralContractsTest {

    @Test
    @SuppressWarnings("unchecked")
    void classLevelUiAnalyticsAttachesToStatsOrFallbackFilterAndMethodOverrides() throws Exception {
        RequestMappingHandlerMapping handlerMapping = mock(RequestMappingHandlerMapping.class);
        CanonicalOperationResolver opResolver = mock(CanonicalOperationResolver.class);
        UiAnalyticsOpenApiCustomizer customizer = new UiAnalyticsOpenApiCustomizer(
                handlerMapping, opResolver, new UiAnalyticsAnnotationMapper());

        ClassAnalyticsController controller = new ClassAnalyticsController();
        Method statsMethod = ClassAnalyticsController.class.getDeclaredMethod("statsGroupBy");
        Method filterMethod = ClassAnalyticsController.class.getDeclaredMethod("filter");
        RequestMappingInfo statsMapping = RequestMappingInfo.paths("/api/reports/stats/group-by").methods(RequestMethod.POST).build();
        RequestMappingInfo filterMapping = RequestMappingInfo.paths("/api/reports/filter").methods(RequestMethod.POST).build();

        when(handlerMapping.getHandlerMethods()).thenReturn(Map.of(
                statsMapping, new HandlerMethod(controller, statsMethod),
                filterMapping, new HandlerMethod(controller, filterMethod)
        ));
        when(opResolver.resolve(any(HandlerMethod.class), any(RequestMappingInfo.class)))
                .thenAnswer(inv -> {
                    Method m = inv.getArgument(0, HandlerMethod.class).getMethod();
                    return m.getName().equals("statsGroupBy")
                            ? new CanonicalOperationRef("reports", "statsGroupBy", "/api/reports/stats/group-by", "post")
                            : new CanonicalOperationRef("reports", "filterReports", "/api/reports/filter", "post");
                });

        var statsOp = new io.swagger.v3.oas.models.Operation();
        var filterOp = new io.swagger.v3.oas.models.Operation();
        OpenAPI openAPIWithStats = new OpenAPI().paths(new Paths()
                .addPathItem("/api/reports/stats/group-by", new PathItem().post(statsOp))
                .addPathItem("/api/reports/filter", new PathItem().post(filterOp)));

        customizer.customise(openAPIWithStats);
        Map<String, Object> attachedToStats = (Map<String, Object>) statsOp.getExtensions().get("x-ui");
        assertNotNull(attachedToStats);
        List<Map<String, Object>> projections = (List<Map<String, Object>>) ((Map<String, Object>) attachedToStats.get("analytics")).get("projections");
        assertEquals("class-ranking", projections.getFirst().get("id"));
        assertNull(filterOp.getExtensions(), "when /stats/group-by exists, /filter should stay untouched");

        FilterFallbackAnalyticsController fallbackController = new FilterFallbackAnalyticsController();
        Method fallbackFilter = FilterFallbackAnalyticsController.class.getDeclaredMethod("filter");
        Method customStats = MethodPrecedenceAnalyticsController.class.getDeclaredMethod("statsGroupBy");
        RequestMappingInfo fallbackFilterMapping = RequestMappingInfo.paths("/api/fallback/filter").methods(RequestMethod.POST).build();
        RequestMappingInfo customStatsMapping = RequestMappingInfo.paths("/api/override/stats/group-by").methods(RequestMethod.POST).build();

        when(handlerMapping.getHandlerMethods()).thenReturn(Map.of(
                fallbackFilterMapping, new HandlerMethod(fallbackController, fallbackFilter),
                customStatsMapping, new HandlerMethod(new MethodPrecedenceAnalyticsController(), customStats)
        ));
        when(opResolver.resolve(any(HandlerMethod.class), any(RequestMappingInfo.class)))
                .thenAnswer(inv -> inv.getArgument(0, HandlerMethod.class).getBean() instanceof FilterFallbackAnalyticsController
                        ? new CanonicalOperationRef("fallback", "filterFallback", "/api/fallback/filter", "post")
                        : new CanonicalOperationRef("override", "statsGroupBy", "/api/override/stats/group-by", "post"));

        var fbOp = new io.swagger.v3.oas.models.Operation();
        var ovOp = new io.swagger.v3.oas.models.Operation();
        OpenAPI fallbackAndOverrideApi = new OpenAPI().paths(new Paths()
                .addPathItem("/api/fallback/filter", new PathItem().post(fbOp))
                .addPathItem("/api/override/stats/group-by", new PathItem().post(ovOp)));

        customizer.customise(fallbackAndOverrideApi);
        List<Map<String, Object>> fbProjections = (List<Map<String, Object>>) ((Map<String, Object>) ((Map<String, Object>) fbOp.getExtensions().get("x-ui")).get("analytics")).get("projections");
        assertEquals("fallback-ranking", fbProjections.getFirst().get("id"));
        List<Map<String, Object>> ovProjections = (List<Map<String, Object>>) ((Map<String, Object>) ((Map<String, Object>) ovOp.getExtensions().get("x-ui")).get("analytics")).get("projections");
        assertEquals("method-ranking", ovProjections.getFirst().get("id"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void bulkEditableAndDefaultSortColumnSupportGetterMethodsAndRecordComponents() {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode schema = mapper.createObjectNode();
        ObjectNode props = schema.putObject("properties");
        props.putObject("title").put("type", "string");
        props.putObject("priority").put("type", "integer");

        BulkEditableFields recordCompiled = BulkEditableFields.compile(
                mapper,
                mapper.constructType(BulkRecordUpdateDto.class),
                schema,
                SpecVersion.V31,
                Set.of("id")
        );
        assertEquals(Set.of("title"), recordCompiled.writableFields(BulkMode.UNIFORM_UPDATE));
        assertEquals(Set.of("priority"), recordCompiled.writableFields(BulkMode.PER_ITEM_UPDATE));

        BulkEditableFields getterCompiled = BulkEditableFields.compile(
                mapper,
                mapper.constructType(BulkGetterUpdateDto.class),
                schema,
                SpecVersion.V31,
                Set.of("id")
        );
        assertEquals(Set.of("title"), getterCompiled.writableFields(BulkMode.UNIFORM_UPDATE));

        GetterSortService getterService = new GetterSortService(mock(BaseCrudRepository.class));
        assertEquals(
                List.of(Sort.Order.desc("priority"), Sort.Order.asc("code")),
                getterService.getDefaultSort().toList()
        );

        RecordSortService recordService = new RecordSortService(mock(BaseCrudRepository.class));
        assertEquals(
                List.of(Sort.Order.asc("rankOrder")),
                recordService.getDefaultSort().toList()
        );

        FilterableIndexAdvisor advisor = new FilterableIndexAdvisor();
        FilterableIndexAdviceReport getterReport = advisor.analyze(GetterSortedEntity.class, EmptyFilterDto.class);
        assertEquals(
                List.of("priority", "code"),
                getterReport.recommendations().stream().map(FilterableIndexRecommendation::columnName).toList()
        );
        FilterableIndexAdviceReport recordReport = advisor.analyze(RecordSortProjection.class, EmptyFilterDto.class);
        assertEquals(
                List.of("rank_order"),
                recordReport.recommendations().stream().map(FilterableIndexRecommendation::columnName).toList()
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    void calculationStepSerializesAndValidatesStatically() {
        Map<String, Object> memory = CalculationMemoryMetadataResolver.resolve(CalculatedPayDto.class);
        assertNotNull(memory);
        List<Map<String, Object>> trails = (List<Map<String, Object>>) memory.get("trails");
        assertEquals(1, trails.size());
        List<Map<String, Object>> steps = (List<Map<String, Object>>) trails.getFirst().get("steps");
        assertEquals(2, steps.size());
        assertEquals("row.grossAmount", steps.get(0).get("expr"));
        assertEquals("row.grossAmount", steps.get(0).get("valueExpr"));
        assertEquals("base", steps.get(0).get("kind"));
        assertEquals("base", steps.get(0).get("role"));
        assertEquals("currency:BRL", steps.get(0).get("format"));
        assertEquals("row.taxRateLabel", steps.get(1).get("hintExpr"));
        assertEquals("deduction", steps.get(1).get("kind"));

        PraxisExpressionStaticValidator validator = new PraxisExpressionStaticValidator();
        assertTrue(validator.validateDtoClasses(List.of(CalculatedPayDto.class), true).isEmpty());

        var ex = assertThrows(
                org.praxisplatform.uischema.validation.InvalidDomainExpressionException.class,
                () -> validator.validateDtoClasses(List.of(InvalidCalculatedStepDto.class), true)
        );
        assertTrue(ex.getMessage().contains("missingParcel"));
        assertTrue(ex.getMessage().contains("missingHint"));
    }

    @Test
    void workflowActionPublishesExtendedMetadataAndReviewerRejectsNonSnakeCaseIcon() {
        RequestMappingHandlerMapping handlerMapping = mock(RequestMappingHandlerMapping.class);
        ApplicationContext applicationContext = mock(ApplicationContext.class);
        CanonicalOperationResolver opResolver = mock(CanonicalOperationResolver.class);
        SchemaReferenceResolver schemaResolver = mock(SchemaReferenceResolver.class);

        WorkflowActionController controller = new WorkflowActionController();
        Method method;
        try {
            method = WorkflowActionController.class.getDeclaredMethod("approve");
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
        RequestMappingInfo mapping = RequestMappingInfo.paths("/api/orders/{id}/actions/approve")
                .methods(RequestMethod.POST).build();
        when(handlerMapping.getHandlerMethods()).thenReturn(Map.of(mapping, new HandlerMethod(controller, method)));
        when(opResolver.resolve(any(HandlerMethod.class), any(RequestMappingInfo.class))).thenReturn(
                new CanonicalOperationRef("orders", "approveOrder", "/api/orders/{id}/actions/approve", "POST"));
        when(opResolver.resolve(anyString(), anyString())).thenReturn(
                new CanonicalOperationRef("orders", "approveOrder", "/api/orders/{id}/actions/approve", "POST"));
        when(schemaResolver.resolve(anyString(), anyString(), anyString(), anyBoolean(), any(), any(), anyString(), anyBoolean()))
                .thenReturn(new CanonicalSchemaRef("ApproveOrder", "request", "/schemas/filtered"));

        AnnotationDrivenActionDefinitionRegistry registry = new AnnotationDrivenActionDefinitionRegistry(
                handlerMapping, applicationContext, opResolver, schemaResolver,
                AnnotationConflictMode.WARN, AnnotationConflictMode.FAIL);
        List<ActionDefinition> definitions = registry.findByResourceKey("workforce.orders");
        assertEquals(1, definitions.size());
        ActionDefinition def = definitions.getFirst();
        assertEquals("check_circle", def.icon());
        assertEquals("success", def.tone());
        assertEquals(List.of("status", "approvedAt"), def.modifiedFields());
        assertEquals("Somente pedidos pendentes podem ser aprovados.", def.disallowedStateReason());

        ActionCatalogService catalogService = new ActionCatalogService(
                registry,
                (action, ctx) -> AvailabilityDecision.allow(Map.of()),
                (rk, rp, id) -> new org.praxisplatform.uischema.action.ActionAvailabilityContext(
                        rk, rp, id, null, Locale.ENGLISH, null, Set.of(), ResourceStateSnapshot.of("PENDING"))
        );
        ActionCatalogResponse catalog = catalogService.findByResourceKey("workforce.orders");
        assertEquals("check_circle", catalog.actions().getFirst().icon());
        assertEquals("success", catalog.actions().getFirst().tone());
        assertEquals(List.of("status", "approvedAt"), catalog.actions().getFirst().modifiedFields());
        assertEquals("Somente pedidos pendentes podem ser aprovados.",
                catalog.actions().getFirst().disallowedStateReason());

        SemanticMetadataReviewer reviewer = new SemanticMetadataReviewer();
        SemanticMetadataReviewReport badActionReport = reviewer.reviewResourceController(
                InvalidWorkflowIconController.class, CalculatedPayDto.class, EmptyFilterDto.class);
        assertTrue(badActionReport.issues().stream()
                .anyMatch(i -> "workflow-action-icon-not-snake-case".equals(i.code())));
    }

    @Test
    void effectiveOnDateRequiresExplicitBoundsAndConfiguredFilterTimezoneApplies() {
        GenericSpecificationsBuilder<GetterSortedEntity> builder =
                new GenericSpecificationsBuilder<>(ZoneId.of("America/Sao_Paulo"));

        BlankEffectiveFilter blankRelation = new BlankEffectiveFilter(LocalDate.of(2026, 5, 1));
        assertThrows(IllegalArgumentException.class,
                () -> builder.buildSpecification(blankRelation, PageRequest.of(0, 10)));

        ExplicitEffectiveFilter validEffective = new ExplicitEffectiveFilter(LocalDate.of(2026, 5, 1));
        assertNotNull(builder.buildSpecification(validEffective, PageRequest.of(0, 10)));

        SemanticMetadataReviewer reviewer = new SemanticMetadataReviewer();
        SemanticMetadataReviewReport report = reviewer.review(BlankEffectiveFilter.class);
        assertTrue(report.issues().stream()
                .anyMatch(i -> "effective-on-date-missing-relations".equals(i.code())));

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(OpenApiUiSchemaAutoConfiguration.class))
                .withPropertyValues("praxis.filter.time-zone=America/Sao_Paulo")
                .run(context -> {
                    GenericSpecificationsBuilder<?> configuredBuilder = context.getBean(
                            "openApiUiSchemaSpecificationsBuilder", GenericSpecificationsBuilder.class);
                    Clock builderClock = (Clock) ReflectionTestUtils.getField(configuredBuilder, "clock");
                    assertNotNull(builderClock);
                    assertEquals(ZoneId.of("America/Sao_Paulo"), builderClock.getZone());
                    RelativePeriodPayloadNormalizer normalizer = context.getBean(RelativePeriodPayloadNormalizer.class);
                    Clock clock = (Clock) ReflectionTestUtils.getField(normalizer, "clock");
                    assertEquals(ZoneId.of("America/Sao_Paulo"), clock.getZone());
                });
    }

    @Test
    void reactiveDeterminationAutoInfersInputsOutputsAndHandlerOperationIds() {
        AnnotationDrivenReactiveDeterminationDefinitionProvider provider =
                AnnotationDrivenReactiveDeterminationDefinitionProvider.fromControllerClasses(AutoInferredDetController.class);
        List<ReactiveDeterminationDefinition> definitions = List.copyOf(provider.definitions());
        assertEquals(1, definitions.size());
        ReactiveDeterminationDefinition def = definitions.getFirst();

        assertEquals("auto.calc", def.id());
        assertEquals(List.of("/postalCode", "/countryCode"), def.sourcePaths());
        assertEquals(2, def.inputs().size());
        assertEquals(2, def.outputs().size());
        assertEquals(Set.of("/city", "/stateCode"),
                def.outputs().stream().map(o -> o.fieldPath()).collect(Collectors.toSet()));
        assertEquals(List.of(ReactiveDeterminationFormMode.CREATE, ReactiveDeterminationFormMode.EDIT),
                def.scopes().stream().map(s -> s.formMode()).toList());
        assertEquals("createItem", def.scopes().get(0).schemaOperationId());
        assertEquals("updateItem", def.scopes().get(1).schemaOperationId());

        SemanticMetadataReviewer reviewer = new SemanticMetadataReviewer();
        SemanticMetadataReviewReport report = reviewer.reviewResourceController(
                AutoInferredDetController.class, DetDraftDto.class, EmptyFilterDto.class);
        assertTrue(report.issues().stream()
                .noneMatch(i -> i.code().startsWith("reactive-determination-")),
                () -> "Unexpected reactive-determination issues: " + report.issues());
    }

    @Test
    void legalTreatmentPurposeExposesGeneralLgpdAndGdprConstants() {
        assertEquals("consent", LegalTreatmentPurpose.CONSENT.wireValue());
        assertEquals("contract_execution", LegalTreatmentPurpose.CONTRACT_EXECUTION.wireValue());
        assertEquals("legitimate_interest", LegalTreatmentPurpose.LEGITIMATE_INTEREST.wireValue());
        assertEquals("vital_interest", LegalTreatmentPurpose.VITAL_INTEREST.wireValue());
        assertEquals("credit_protection", LegalTreatmentPurpose.CREDIT_PROTECTION.wireValue());
        assertEquals("judicial_defense", LegalTreatmentPurpose.JUDICIAL_DEFENSE.wireValue());
        assertEquals("fraud_prevention", LegalTreatmentPurpose.FRAUD_PREVENTION.wireValue());
        assertEquals("regulatory_compliance", LegalTreatmentPurpose.REGULATORY_COMPLIANCE.wireValue());
    }

    @UiAnalytics(projections = {
            @AnalyticsProjection(
                    id = "class-ranking",
                    intent = AnalyticsIntent.RANKING,
                    sourceOperation = AnalyticsOperation.GROUP_BY,
                    preferredFamilies = {AnalyticsPresentationFamily.CHART},
                    primaryDimension = @AnalyticsDimensionBinding(field = "department"),
                    primaryMetrics = @AnalyticsMetricBinding(field = "amount", aggregation = "sum")
            )
    })
    public static class ClassAnalyticsController {
        @PostMapping("/api/reports/stats/group-by")
        public void statsGroupBy() {}

        @PostMapping("/api/reports/filter")
        public void filter() {}
    }

    @UiAnalytics(projections = {
            @AnalyticsProjection(
                    id = "fallback-ranking",
                    intent = AnalyticsIntent.RANKING,
                    sourceOperation = AnalyticsOperation.GROUP_BY,
                    preferredFamilies = {AnalyticsPresentationFamily.CHART},
                    primaryDimension = @AnalyticsDimensionBinding(field = "department"),
                    primaryMetrics = @AnalyticsMetricBinding(field = "amount", aggregation = "sum")
            )
    })
    public static class FilterFallbackAnalyticsController {
        @PostMapping("/api/fallback/filter")
        public void filter() {}
    }

    @UiAnalytics(projections = {
            @AnalyticsProjection(
                    id = "class-default",
                    intent = AnalyticsIntent.RANKING,
                    sourceOperation = AnalyticsOperation.GROUP_BY,
                    preferredFamilies = {AnalyticsPresentationFamily.CHART},
                    primaryDimension = @AnalyticsDimensionBinding(field = "department"),
                    primaryMetrics = @AnalyticsMetricBinding(field = "amount", aggregation = "sum")
            )
    })
    public static class MethodPrecedenceAnalyticsController {
        @PostMapping("/api/override/stats/group-by")
        @UiAnalytics(projections = {
                @AnalyticsProjection(
                        id = "method-ranking",
                        intent = AnalyticsIntent.RANKING,
                        sourceOperation = AnalyticsOperation.GROUP_BY,
                        preferredFamilies = {AnalyticsPresentationFamily.CHART},
                        primaryDimension = @AnalyticsDimensionBinding(field = "department"),
                        primaryMetrics = @AnalyticsMetricBinding(field = "amount", aggregation = "sum")
                )
        })
        public void statsGroupBy() {}
    }

    public record BulkRecordUpdateDto(
            @BulkEditable(modes = {BulkMode.UNIFORM_UPDATE}) String title,
            @BulkEditable(modes = {BulkMode.PER_ITEM_UPDATE}) Integer priority
    ) {}

    public static class BulkGetterUpdateDto {
        private String title;
        @BulkEditable(modes = {BulkMode.UNIFORM_UPDATE})
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
    }

    @Entity
    @Table(name = "getter_sorted_entities", indexes = @Index(name = "idx_gs", columnList = "priority,code"))
    public static class GetterSortedEntity {
        @Id private Long id;
        @Column(name = "priority") private Integer priority;
        @Column(name = "code") private String code;
        @Column(name = "valid_from") private LocalDate validFrom;
        @Column(name = "valid_to") private LocalDate validTo;

        @DefaultSortColumn(priority = 1, ascending = false)
        public Integer getPriority() { return priority; }
        @DefaultSortColumn(priority = 2, ascending = true)
        public String getCode() { return code; }
    }

    public record RecordSortProjection(
            @DefaultSortColumn(priority = 1) Integer rankOrder
    ) {}

    public static class GetterSortService extends AbstractBaseResourceService<
            GetterSortedEntity, GetterSortedEntity, Long, EmptyFilterDto, GetterSortedEntity, GetterSortedEntity> {
        GetterSortService(BaseCrudRepository<GetterSortedEntity, Long> repo) { super(repo, GetterSortedEntity.class); }
        @Override protected ResourceMapper<GetterSortedEntity, GetterSortedEntity, GetterSortedEntity, GetterSortedEntity, Long> getResourceMapper() { return null; }
    }

    public static class RecordSortService extends AbstractBaseResourceService<
            RecordSortProjection, RecordSortProjection, Long, EmptyFilterDto, RecordSortProjection, RecordSortProjection> {
        RecordSortService(BaseCrudRepository<RecordSortProjection, Long> repo) { super(repo, RecordSortProjection.class); }
        @Override protected ResourceMapper<RecordSortProjection, RecordSortProjection, RecordSortProjection, RecordSortProjection, Long> getResourceMapper() { return null; }
    }

    public static class EmptyFilterDto implements GenericFilterDTO {}

    @CalculationMemory(
            title = "Composicao salarial",
            trails = {
                    @CalculationTrail(
                            id = "net-pay",
                            title = "Salario liquido",
                            resultExpr = "row.netAmount",
                            steps = {
                                    @CalculationStep(label = "Bruto", expr = "row.grossAmount", kind = StepKind.BASE, format = "currency:BRL"),
                                    @CalculationStep(label = "IRRF", expr = "row.taxAmount", kind = StepKind.DEDUCTION, hintExpr = "row.taxRateLabel")
                            }
                    )
            }
    )
    public static class CalculatedPayDto {
        @io.swagger.v3.oas.annotations.media.Schema(description = "Valor bruto da remuneracao mensal.")
        private BigDecimal grossAmount;
        @io.swagger.v3.oas.annotations.media.Schema(description = "Valor do desconto tributario calculado.")
        private BigDecimal taxAmount;
        @io.swagger.v3.oas.annotations.media.Schema(description = "Valor liquido apos deducoes obrigatorias.")
        private BigDecimal netAmount;
        @io.swagger.v3.oas.annotations.media.Schema(description = "Descricao da faixa de aliquota aplicada.")
        private String taxRateLabel;

        public BigDecimal getGrossAmount() { return grossAmount; }
        public BigDecimal getTaxAmount() { return taxAmount; }
        public BigDecimal getNetAmount() { return netAmount; }
        public String getTaxRateLabel() { return taxRateLabel; }
    }

    @CalculationMemory(
            title = "Invalido",
            trails = {
                    @CalculationTrail(
                            id = "bad",
                            resultExpr = "row.grossAmount",
                            steps = {
                                    @CalculationStep(label = "Missing", expr = "row.missingParcel", hintExpr = "row.missingHint")
                            }
                    )
            }
    )
    public static class InvalidCalculatedStepDto {
        private BigDecimal grossAmount;
        public BigDecimal getGrossAmount() { return grossAmount; }
    }

    @ApiResource(value = "/api/orders", resourceKey = "workforce.orders")
    @org.praxisplatform.uischema.annotation.ApiGroup("orders")
    public static class WorkflowActionController
            extends org.praxisplatform.uischema.controller.base.AbstractCollectionCommandResourceController {
        @PostMapping("/{id}/actions/approve")
        @WorkflowAction(
                id = "approve",
                title = "Aprovar pedido",
                icon = "check_circle",
                tone = QuickFilterTone.SUCCESS,
                allowedStates = {"PENDING"},
                modifiedFields = {"status", "approvedAt"},
                disallowedStateReason = "Somente pedidos pendentes podem ser aprovados."
        )
        public void approve() {}
    }

    @ApiResource(value = "/api/bad-orders", resourceKey = "workforce.bad-orders")
    public static class InvalidWorkflowIconController
            extends org.praxisplatform.uischema.controller.base.AbstractCollectionCommandResourceController {
        @PostMapping("/{id}/actions/approve")
        @WorkflowAction(id = "approve", title = "Aprovar", icon = "CheckCircle")
        public void approve() {}
    }

    public static class BlankEffectiveFilter implements GenericFilterDTO {
        @io.swagger.v3.oas.annotations.media.Schema(description = "Data alvo para filtro de vigencia aberta ou fechada.")
        @Filterable(operation = Filterable.FilterOperation.EFFECTIVE_ON_DATE, relation = "validFrom", endRelation = "")
        private LocalDate referenceDate;
        BlankEffectiveFilter(LocalDate referenceDate) { this.referenceDate = referenceDate; }
        public LocalDate getReferenceDate() { return referenceDate; }
    }

    public static class ExplicitEffectiveFilter implements GenericFilterDTO {
        @io.swagger.v3.oas.annotations.media.Schema(description = "Data alvo para filtro de vigencia com inicio e fim explicitos.")
        @Filterable(operation = Filterable.FilterOperation.EFFECTIVE_ON_DATE, relation = "validFrom", endRelation = "validTo")
        private LocalDate referenceDate;
        ExplicitEffectiveFilter(LocalDate referenceDate) { this.referenceDate = referenceDate; }
        public LocalDate getReferenceDate() { return referenceDate; }
    }

    public static class DetInputDto {
        private String postalCode;
        private String countryCode;
        public String getPostalCode() { return postalCode; }
        public void setPostalCode(String postalCode) { this.postalCode = postalCode; }
        public String getCountryCode() { return countryCode; }
        public void setCountryCode(String countryCode) { this.countryCode = countryCode; }
    }

    public static class DetOutputDto {
        private String city;
        private String stateCode;
        public String getCity() { return city; }
        public void setCity(String city) { this.city = city; }
        public String getStateCode() { return stateCode; }
        public void setStateCode(String stateCode) { this.stateCode = stateCode; }
    }

    public static class DetDraftDto {
        @io.swagger.v3.oas.annotations.media.Schema(description = "Codigo postal do endereco.")
        private String postalCode;
        @io.swagger.v3.oas.annotations.media.Schema(description = "Codigo ISO do pais.")
        private String countryCode;
        @io.swagger.v3.oas.annotations.media.Schema(description = "Municipio preenchido automaticamente.")
        private String city;
        @io.swagger.v3.oas.annotations.media.Schema(description = "Unidade federativa preenchida automaticamente.")
        private String stateCode;
        public String getPostalCode() { return postalCode; }
        public String getCountryCode() { return countryCode; }
        public String getCity() { return city; }
        public String getStateCode() { return stateCode; }
    }

    @RestController
    @RequestMapping("/api/workforce/items")
    public static class AutoInferredDetController {
        @PostMapping
        @Operation(operationId = "createItem")
        public ResponseEntity<RestApiResponse<DetDraftDto>> create(@RequestBody DetDraftDto body) { return null; }

        @PutMapping("/{id}")
        @Operation(operationId = "updateItem")
        public ResponseEntity<RestApiResponse<DetDraftDto>> update(@RequestBody DetDraftDto body) { return null; }

        @PostMapping("/determinations/address")
        @Operation(operationId = "determineItemAddress")
        @ReactiveDetermination(
                id = "auto.calc",
                triggerMode = ReactiveDeterminationTriggerMode.ON_CHANGE
        )
        public ResponseEntity<RestApiResponse<DetOutputDto>> determineAddress(@RequestBody DetInputDto body) {
            return null;
        }
    }
}
