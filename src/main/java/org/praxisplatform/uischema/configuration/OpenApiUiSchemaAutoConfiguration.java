package org.praxisplatform.uischema.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.praxisplatform.uischema.capability.CapabilityService;
import org.praxisplatform.uischema.capability.CanonicalCapabilityResolver;
import org.praxisplatform.uischema.capability.DefaultCapabilityService;
import org.praxisplatform.uischema.capability.NoOpResourceStateSnapshotProvider;
import org.praxisplatform.uischema.capability.OpenApiCanonicalCapabilityResolver;
import org.praxisplatform.uischema.capability.ResourceStateSnapshotProvider;
import org.praxisplatform.uischema.analytics.UiAnalyticsAnnotationMapper;
import org.praxisplatform.uischema.analytics.UiAnalyticsOpenApiCustomizer;
import org.praxisplatform.uischema.controller.docs.ApiDocsController;
import org.praxisplatform.uischema.controller.docs.ActionCatalogController;
import org.praxisplatform.uischema.controller.docs.OpenApiDocsSupport;
import org.praxisplatform.uischema.controller.docs.SurfaceCatalogController;
import org.praxisplatform.uischema.extension.CustomOpenApiResolver;
import org.praxisplatform.uischema.filter.relativeperiod.RelativePeriodPayloadNormalizer;
import org.praxisplatform.uischema.filter.range.RangePayloadNormalizer;
import org.praxisplatform.uischema.filter.specification.GenericSpecificationsBuilder;
import org.praxisplatform.uischema.filter.web.FilterPayloadNormalizer;
import org.praxisplatform.uischema.filter.web.FilterRequestBodyAdvice;
import org.praxisplatform.uischema.openapi.CachedOpenApiDocumentService;
import org.praxisplatform.uischema.openapi.CanonicalOperationResolver;
import org.praxisplatform.uischema.openapi.OpenApiCanonicalOperationResolver;
import org.praxisplatform.uischema.openapi.OpenApiDocumentService;
import org.praxisplatform.uischema.options.OptionSourceEligibility;
import org.praxisplatform.uischema.options.OptionSourceRegistry;
import org.praxisplatform.uischema.options.service.OptionSourceQueryExecutor;
import org.praxisplatform.uischema.options.service.jpa.JpaOptionSourceQueryExecutor;
import org.praxisplatform.uischema.schema.FilteredSchemaReferenceResolver;
import org.praxisplatform.uischema.schema.SchemaReferenceResolver;
import org.praxisplatform.uischema.service.base.BaseResourceQueryService;
import org.praxisplatform.uischema.validation.AnnotationConflictMode;
import org.praxisplatform.uischema.action.ActionAvailabilityRule;
import org.praxisplatform.uischema.action.ActionAvailabilityContextResolver;
import org.praxisplatform.uischema.action.ActionAvailabilityEvaluator;
import org.praxisplatform.uischema.action.AllowedStatesActionAvailabilityRule;
import org.praxisplatform.uischema.action.ActionCatalogService;
import org.praxisplatform.uischema.action.ActionDefinitionRegistry;
import org.praxisplatform.uischema.action.AnnotationDrivenActionDefinitionRegistry;
import org.praxisplatform.uischema.action.ContextualActionAvailabilityRule;
import org.praxisplatform.uischema.action.DefaultActionAvailabilityContextResolver;
import org.praxisplatform.uischema.action.DefaultActionAvailabilityEvaluator;
import org.praxisplatform.uischema.action.RequiredAuthoritiesActionAvailabilityRule;
import org.praxisplatform.uischema.surface.AnnotationDrivenSurfaceDefinitionRegistry;
import org.praxisplatform.uischema.surface.AllowedStatesSurfaceAvailabilityRule;
import org.praxisplatform.uischema.surface.ContextualSurfaceAvailabilityRule;
import org.praxisplatform.uischema.surface.DefaultSurfaceAvailabilityContextResolver;
import org.praxisplatform.uischema.surface.DefaultSurfaceAvailabilityEvaluator;
import org.praxisplatform.uischema.surface.RequiredAuthoritiesSurfaceAvailabilityRule;
import org.praxisplatform.uischema.surface.SurfaceAvailabilityContextResolver;
import org.praxisplatform.uischema.surface.SurfaceAvailabilityEvaluator;
import org.praxisplatform.uischema.surface.SurfaceAvailabilityRule;
import org.praxisplatform.uischema.surface.SurfaceCatalogService;
import org.praxisplatform.uischema.surface.SurfaceDefinitionRegistry;
import org.praxisplatform.uischema.stats.StatsEligibility;
import org.praxisplatform.uischema.stats.StatsProperties;
import org.praxisplatform.uischema.stats.StatsSupportMode;
import org.praxisplatform.uischema.stats.service.StatsQueryExecutor;
import org.praxisplatform.uischema.stats.service.jpa.JpaStatsQueryExecutor;
import org.praxisplatform.uischema.util.OpenApiGroupResolver;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.aop.support.AopUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springdoc.core.models.GroupedOpenApi;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springdoc.core.customizers.OpenApiCustomizer;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.ZoneId;
import java.util.List;

/**
 * Auto-configuracao principal do modulo de OpenAPI e UI Schema.
 *
 * <p>
 * Esta classe registra os beans de infraestrutura usados pelo runtime metadata-driven: fetch de
 * documentos OpenAPI, resolucao canonica de operacao/schema, enriquecimento de schema e suporte
 * aos fluxos de filtro e stats.
 * </p>
 *
 * <p>
 * O objetivo aqui nao e documentar o comportamento interno de cada bean em detalhes, mas deixar
 * claro quais fronteiras o starter publica por convencao, quais beans aceitam override direto via
 * {@code @ConditionalOnMissingBean} e quais exigem substituicao mais explicita da auto-configuracao.
 * A narrativa arquitetural maior fica no documento tecnico da lane, nao nesta classe de bootstrap.
 * </p>
 */
@AutoConfiguration
public class OpenApiUiSchemaAutoConfiguration {
    /**
     * Publica o {@link RestTemplate} usado para fetch interno de documentos OpenAPI.
     */
    @Bean(name = "openApiUiSchemaRestTemplate")
    @ConditionalOnMissingBean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    /**
     * Publica o {@link ObjectMapper} padrao do starter, com suporte a Java Time.
     */
    @Bean(name = "openApiUiSchemaObjectMapper")
    @ConditionalOnMissingBean
    public ObjectMapper objectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        return objectMapper;
    }

    /**
     * Publica o {@link CustomOpenApiResolver} que enriquece schemas OpenAPI com metadados de UI.
     */
    @Bean
    public CustomOpenApiResolver modelResolver(ObjectMapper mapper) {
        return new CustomOpenApiResolver(mapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public UiAnalyticsAnnotationMapper uiAnalyticsAnnotationMapper() {
        return new UiAnalyticsAnnotationMapper();
    }

    @Bean
    @ConditionalOnBean(RequestMappingHandlerMapping.class)
    @ConditionalOnMissingBean(name = "uiAnalyticsOpenApiCustomizer")
    public GlobalOpenApiCustomizer uiAnalyticsOpenApiCustomizer(
            RequestMappingHandlerMapping requestMappingHandlerMapping,
            CanonicalOperationResolver canonicalOperationResolver,
            UiAnalyticsAnnotationMapper uiAnalyticsAnnotationMapper
    ) {
        return new UiAnalyticsOpenApiCustomizer(
                requestMappingHandlerMapping,
                canonicalOperationResolver,
                uiAnalyticsAnnotationMapper
        );
    }

    @Bean
    @ConditionalOnMissingBean(name = "rangePayloadNormalizer")
    @Order(0)
    public FilterPayloadNormalizer rangePayloadNormalizer(
            @Value("${praxis.filter.range.allow-scalar-payload:false}") boolean allowScalarRangePayload,
            @Value("${praxis.filter.range.log-scalar-payload:true}") boolean logScalarRangePayload
    ) {
        return new RangePayloadNormalizer(allowScalarRangePayload, logScalarRangePayload);
    }

    @Bean
    @ConditionalOnMissingBean(name = "relativePeriodPayloadNormalizer")
    @Order(100)
    public FilterPayloadNormalizer relativePeriodPayloadNormalizer(
            @Value("${praxis.filter.relative-period.zone-id:UTC}") String relativePeriodZoneId
    ) {
        return new RelativePeriodPayloadNormalizer(Clock.system(ZoneId.of(relativePeriodZoneId)));
    }

    @Bean
    @ConditionalOnMissingBean
    public FilterRequestBodyAdvice filterRequestBodyAdvice(
            ObjectMapper mapper,
            List<FilterPayloadNormalizer> payloadNormalizers
    ) {
        return new FilterRequestBodyAdvice(mapper, payloadNormalizers);
    }

    /**
     * Publica o builder generico de specifications usado pelos fluxos canonicos de filtro.
     */
    @Bean(name = "openApiUiSchemaSpecificationsBuilder")
    public <E> GenericSpecificationsBuilder<E> genericSpecificationsBuilder() {
        return new GenericSpecificationsBuilder<>();
    }

    @Bean
    @ConditionalOnMissingBean
    public StatsEligibility statsEligibility() {
        return new StatsEligibility();
    }

    @Bean
    @ConditionalOnMissingBean
    public StatsQueryExecutor statsQueryExecutor() {
        return new JpaStatsQueryExecutor();
    }

    @Bean
    @ConditionalOnMissingBean
    public OptionSourceEligibility optionSourceEligibility() {
        return new OptionSourceEligibility();
    }

    @Bean
    @ConditionalOnMissingBean
    public OptionSourceQueryExecutor optionSourceQueryExecutor() {
        return new JpaOptionSourceQueryExecutor();
    }

    @Bean
    @ConditionalOnMissingBean
    public OptionSourceRegistry optionSourceRegistry(
            ObjectProvider<BaseResourceQueryService<?, ?, ?>> queryServices
    ) {
        OptionSourceRegistry[] registries = queryServices.orderedStream()
                .map(this::resolveRegistryForAggregation)
                .filter(registry -> registry != null && !registry.isEmpty())
                .toArray(OptionSourceRegistry[]::new);
        return registries.length == 0 ? OptionSourceRegistry.empty() : OptionSourceRegistry.merge(registries);
    }

    private OptionSourceRegistry resolveRegistryForAggregation(BaseResourceQueryService<?, ?, ?> service) {
        if (service instanceof org.praxisplatform.uischema.service.base.AbstractBaseQueryResourceService<?, ?, ?, ?> abstractService) {
            OptionSourceRegistry declaredRegistry = abstractService.getDeclaredOptionSourceRegistry();
            if (declaredRegistry != null && !declaredRegistry.isEmpty()) {
                return declaredRegistry;
            }
            if (overridesOptionSourceRegistry(abstractService)) {
                OptionSourceRegistry overriddenRegistry = abstractService.getOptionSourceRegistry();
                if (overriddenRegistry != null && !overriddenRegistry.isEmpty()) {
                    return overriddenRegistry;
                }
            }
            return OptionSourceRegistry.empty();
        }
        return service.getOptionSourceRegistry();
    }

    private boolean overridesOptionSourceRegistry(
            org.praxisplatform.uischema.service.base.AbstractBaseQueryResourceService<?, ?, ?, ?> service
    ) {
        try {
            Class<?> userClass = AopUtils.getTargetClass(service);
            Method method = userClass.getMethod("getOptionSourceRegistry");
            return method.getDeclaringClass()
                    != org.praxisplatform.uischema.service.base.AbstractBaseQueryResourceService.class;
        } catch (NoSuchMethodException ex) {
            return false;
        }
    }

    @Bean
    @ConditionalOnMissingBean
    public StatsProperties statsProperties(
            @Value("${praxis.stats.enabled:false}") boolean enabled,
            @Value("${praxis.stats.max-buckets:20}") int maxBuckets,
            @Value("${praxis.stats.max-series-points:100}") int maxSeriesPoints,
            @Value("${praxis.stats.default-mode:DISABLED}") StatsSupportMode defaultMode
    ) {
        return new StatsProperties(enabled, maxBuckets, maxSeriesPoints, defaultMode);
    }

    /**
     * Publica o resolver de grupos OpenAPI usado pelos servicos canonicamente expostos.
     *
     * <p>
     * O bean existe para manter a estrategia oficial de mapeamento entre path e grupo SpringDoc.
     * Como este metodo nao usa {@code @ConditionalOnMissingBean}, trocar essa estrategia exige
     * substituir explicitamente a auto-configuracao ou este bean.
     * </p>
     */
    @Bean
    public OpenApiGroupResolver openApiGroupResolver(ObjectProvider<GroupedOpenApi> groupedOpenApis) {
        return new OpenApiGroupResolver(() -> groupedOpenApis.orderedStream().toList());
    }

    /**
     * Publica utilitarios compartilhados para leitura e navegacao de documentos OpenAPI.
     */
    @Bean
    @ConditionalOnMissingBean
    public OpenApiDocsSupport openApiDocsSupport() {
        return new OpenApiDocsSupport();
    }

    /**
     * Publica o servico canonico de fetch/cache de documentos OpenAPI e hash estrutural.
     */
    @Bean
    @ConditionalOnMissingBean
    public OpenApiDocumentService openApiDocumentService(
            RestTemplate restTemplate,
            ObjectMapper objectMapper,
            OpenApiDocsSupport openApiDocsSupport
    ) {
        return new CachedOpenApiDocumentService(restTemplate, objectMapper, openApiDocsSupport);
    }

    /**
     * Publica o resolvedor canonico de operacoes OpenAPI a partir de path ou handler Spring MVC.
     */
    @Bean
    @ConditionalOnMissingBean
    public CanonicalOperationResolver canonicalOperationResolver(
            OpenApiDocumentService openApiDocumentService,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            RequestMappingHandlerMapping requestMappingHandlerMapping
    ) {
        return new OpenApiCanonicalOperationResolver(openApiDocumentService, requestMappingHandlerMapping);
    }

    /**
     * Publica o gerador canonico de {@code schemaId}/{@code schemaUrl} para {@code /schemas/filtered}.
     */
    @Bean
    @ConditionalOnMissingBean
    public SchemaReferenceResolver schemaReferenceResolver() {
        return new FilteredSchemaReferenceResolver();
    }

    @Bean
    @ConditionalOnMissingBean
    public CanonicalCapabilityResolver canonicalCapabilityResolver(OpenApiDocumentService openApiDocumentService) {
        return new OpenApiCanonicalCapabilityResolver(openApiDocumentService);
    }

    @Bean
    @ConditionalOnMissingBean(name = "contextualSurfaceAvailabilityRule")
    @Order(0)
    public SurfaceAvailabilityRule contextualSurfaceAvailabilityRule() {
        return new ContextualSurfaceAvailabilityRule();
    }

    @Bean
    @ConditionalOnMissingBean(name = "requiredAuthoritiesSurfaceAvailabilityRule")
    @Order(100)
    public SurfaceAvailabilityRule requiredAuthoritiesSurfaceAvailabilityRule() {
        return new RequiredAuthoritiesSurfaceAvailabilityRule();
    }

    @Bean
    @ConditionalOnMissingBean(name = "allowedStatesSurfaceAvailabilityRule")
    @Order(200)
    public SurfaceAvailabilityRule allowedStatesSurfaceAvailabilityRule() {
        return new AllowedStatesSurfaceAvailabilityRule();
    }

    @Bean
    @ConditionalOnMissingBean
    public SurfaceAvailabilityEvaluator surfaceAvailabilityEvaluator(List<SurfaceAvailabilityRule> surfaceAvailabilityRules) {
        return new DefaultSurfaceAvailabilityEvaluator(surfaceAvailabilityRules);
    }

    @Bean
    @ConditionalOnMissingBean
    public ResourceStateSnapshotProvider resourceStateSnapshotProvider() {
        return new NoOpResourceStateSnapshotProvider();
    }

    @Bean
    @ConditionalOnMissingBean
    public SurfaceAvailabilityContextResolver surfaceAvailabilityContextResolver(
            ResourceStateSnapshotProvider resourceStateSnapshotProvider
    ) {
        return new DefaultSurfaceAvailabilityContextResolver(resourceStateSnapshotProvider);
    }

    @Bean
    @ConditionalOnBean(RequestMappingHandlerMapping.class)
    @ConditionalOnMissingBean
    public SurfaceDefinitionRegistry surfaceDefinitionRegistry(
            RequestMappingHandlerMapping requestMappingHandlerMapping,
            ApplicationContext applicationContext,
            CanonicalOperationResolver canonicalOperationResolver,
            SchemaReferenceResolver schemaReferenceResolver,
            @Value("${praxis.metadata.validation.surface-workflow-conflict:WARN}") AnnotationConflictMode conflictMode
    ) {
        return new AnnotationDrivenSurfaceDefinitionRegistry(
                requestMappingHandlerMapping,
                applicationContext,
                canonicalOperationResolver,
                schemaReferenceResolver,
                conflictMode
        );
    }

    @Bean
    @ConditionalOnBean(SurfaceDefinitionRegistry.class)
    @ConditionalOnMissingBean
    public SurfaceCatalogService surfaceCatalogService(
            SurfaceDefinitionRegistry surfaceDefinitionRegistry,
            SurfaceAvailabilityEvaluator surfaceAvailabilityEvaluator,
            SurfaceAvailabilityContextResolver surfaceAvailabilityContextResolver
    ) {
        return new SurfaceCatalogService(
                surfaceDefinitionRegistry,
                surfaceAvailabilityEvaluator,
                surfaceAvailabilityContextResolver
        );
    }

    @Bean
    @ConditionalOnMissingBean(name = "contextualActionAvailabilityRule")
    @Order(0)
    public ActionAvailabilityRule contextualActionAvailabilityRule() {
        return new ContextualActionAvailabilityRule();
    }

    @Bean
    @ConditionalOnMissingBean(name = "requiredAuthoritiesActionAvailabilityRule")
    @Order(100)
    public ActionAvailabilityRule requiredAuthoritiesActionAvailabilityRule() {
        return new RequiredAuthoritiesActionAvailabilityRule();
    }

    @Bean
    @ConditionalOnMissingBean(name = "allowedStatesActionAvailabilityRule")
    @Order(200)
    public ActionAvailabilityRule allowedStatesActionAvailabilityRule() {
        return new AllowedStatesActionAvailabilityRule();
    }

    @Bean
    @ConditionalOnMissingBean
    public ActionAvailabilityEvaluator actionAvailabilityEvaluator(List<ActionAvailabilityRule> actionAvailabilityRules) {
        return new DefaultActionAvailabilityEvaluator(actionAvailabilityRules);
    }

    @Bean
    @ConditionalOnMissingBean
    public ActionAvailabilityContextResolver actionAvailabilityContextResolver(
            ResourceStateSnapshotProvider resourceStateSnapshotProvider
    ) {
        return new DefaultActionAvailabilityContextResolver(resourceStateSnapshotProvider);
    }

    @Bean
    @ConditionalOnBean(RequestMappingHandlerMapping.class)
    @ConditionalOnMissingBean
    public ActionDefinitionRegistry actionDefinitionRegistry(
            RequestMappingHandlerMapping requestMappingHandlerMapping,
            ApplicationContext applicationContext,
            CanonicalOperationResolver canonicalOperationResolver,
            SchemaReferenceResolver schemaReferenceResolver,
            @Value("${praxis.metadata.validation.surface-workflow-conflict:WARN}") AnnotationConflictMode conflictMode,
            @Value("${praxis.metadata.validation.workflow-action-shape:WARN}") AnnotationConflictMode workflowActionShapeMode
    ) {
        return new AnnotationDrivenActionDefinitionRegistry(
                requestMappingHandlerMapping,
                applicationContext,
                canonicalOperationResolver,
                schemaReferenceResolver,
                conflictMode,
                workflowActionShapeMode
        );
    }

    @Bean
    @ConditionalOnBean(ActionDefinitionRegistry.class)
    @ConditionalOnMissingBean
    public ActionCatalogService actionCatalogService(
            ActionDefinitionRegistry actionDefinitionRegistry,
            ActionAvailabilityEvaluator actionAvailabilityEvaluator,
            ActionAvailabilityContextResolver actionAvailabilityContextResolver
    ) {
        return new ActionCatalogService(
                actionDefinitionRegistry,
                actionAvailabilityEvaluator,
                actionAvailabilityContextResolver
        );
    }

    @Bean
    @ConditionalOnBean({SurfaceCatalogService.class, ActionCatalogService.class})
    @ConditionalOnMissingBean
    public CapabilityService capabilityService(
            CanonicalCapabilityResolver canonicalCapabilityResolver,
            SurfaceCatalogService surfaceCatalogService,
            ActionCatalogService actionCatalogService,
            OpenApiDocumentService openApiDocumentService
    ) {
        return new DefaultCapabilityService(
                canonicalCapabilityResolver,
                surfaceCatalogService,
                actionCatalogService,
                openApiDocumentService
        );
    }

    /**
     * Publica o {@link ApiDocsController} com as dependencias canonicas ja resolvidas.
     *
     * <p>
     * O controller continua registrado por auto-configuracao, mas a semantica pesada de grupo,
     * documento e schema agora vive em servicos dedicados. Como este bean nao usa
     * {@code @ConditionalOnMissingBean}, substituir a superficie HTTP exige excluir ou substituir
     * explicitamente esta auto-configuracao.
     * </p>
     */
    @Bean
    public ApiDocsController apiDocsController() {
        return new ApiDocsController();
    }

    @Bean
    @ConditionalOnBean(SurfaceCatalogService.class)
    @ConditionalOnMissingBean
    public SurfaceCatalogController surfaceCatalogController(SurfaceCatalogService surfaceCatalogService) {
        return new SurfaceCatalogController(surfaceCatalogService);
    }

    @Bean
    @ConditionalOnBean(ActionCatalogService.class)
    @ConditionalOnMissingBean
    public ActionCatalogController actionCatalogController(ActionCatalogService actionCatalogService) {
        return new ActionCatalogController(actionCatalogService);
    }
}
