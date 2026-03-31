package org.praxisplatform.uischema.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.praxisplatform.uischema.controller.docs.ApiDocsController;
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
import org.praxisplatform.uischema.schema.FilteredSchemaReferenceResolver;
import org.praxisplatform.uischema.schema.SchemaReferenceResolver;
import org.praxisplatform.uischema.surface.AnnotationDrivenSurfaceDefinitionRegistry;
import org.praxisplatform.uischema.surface.DefaultSurfaceAvailabilityContextResolver;
import org.praxisplatform.uischema.surface.DefaultSurfaceAvailabilityEvaluator;
import org.praxisplatform.uischema.surface.SurfaceAvailabilityContextResolver;
import org.praxisplatform.uischema.surface.SurfaceAvailabilityEvaluator;
import org.praxisplatform.uischema.surface.SurfaceCatalogService;
import org.praxisplatform.uischema.surface.SurfaceDefinitionRegistry;
import org.praxisplatform.uischema.stats.StatsEligibility;
import org.praxisplatform.uischema.stats.StatsProperties;
import org.praxisplatform.uischema.stats.StatsSupportMode;
import org.praxisplatform.uischema.stats.service.StatsQueryExecutor;
import org.praxisplatform.uischema.stats.service.jpa.JpaStatsQueryExecutor;
import org.praxisplatform.uischema.util.OpenApiGroupResolver;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springdoc.core.models.GroupedOpenApi;

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
    @ConditionalOnMissingBean(name = "rangePayloadNormalizer")
    @Order(0)
    public FilterPayloadNormalizer rangePayloadNormalizer(
            @Value("${praxis.filter.range.allow-scalar-payload:false}") boolean allowScalarRangePayload,
            @Value("${praxis.filter.range.log-legacy-scalar-payload:true}") boolean logLegacyScalarRangePayload
    ) {
        return new RangePayloadNormalizer(allowScalarRangePayload, logLegacyScalarRangePayload);
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
    public OpenApiGroupResolver openApiGroupResolver(List<GroupedOpenApi> groupedOpenApis) {
        return new OpenApiGroupResolver(groupedOpenApis);
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
    public SurfaceAvailabilityEvaluator surfaceAvailabilityEvaluator() {
        return new DefaultSurfaceAvailabilityEvaluator();
    }

    @Bean
    @ConditionalOnMissingBean
    public SurfaceAvailabilityContextResolver surfaceAvailabilityContextResolver() {
        return new DefaultSurfaceAvailabilityContextResolver();
    }

    @Bean
    @ConditionalOnMissingBean
    public SurfaceDefinitionRegistry surfaceDefinitionRegistry(
            RequestMappingHandlerMapping requestMappingHandlerMapping,
            ApplicationContext applicationContext,
            CanonicalOperationResolver canonicalOperationResolver,
            SchemaReferenceResolver schemaReferenceResolver
    ) {
        return new AnnotationDrivenSurfaceDefinitionRegistry(
                requestMappingHandlerMapping,
                applicationContext,
                canonicalOperationResolver,
                schemaReferenceResolver
        );
    }

    @Bean
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
    @ConditionalOnMissingBean
    public SurfaceCatalogController surfaceCatalogController(SurfaceCatalogService surfaceCatalogService) {
        return new SurfaceCatalogController(surfaceCatalogService);
    }
}
