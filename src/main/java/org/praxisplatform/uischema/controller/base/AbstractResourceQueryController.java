package org.praxisplatform.uischema.controller.base;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.annotation.PostConstruct;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.capability.AvailabilityDecision;
import org.praxisplatform.uischema.capability.CapabilityService;
import org.praxisplatform.uischema.concurrency.ResourceVersionEtagService;
import org.praxisplatform.uischema.concurrency.ResourceVersionPreconditions;
import org.praxisplatform.uischema.capability.CapabilitySnapshot;
import org.praxisplatform.uischema.capability.ResourceStructuralCapabilities;
import org.praxisplatform.uischema.capability.ResourceOperationAvailabilityContext;
import org.praxisplatform.uischema.command.GovernedResourceCommandExecutor;
import org.praxisplatform.uischema.command.ResourceCommandEvidenceSanitizer;
import org.praxisplatform.uischema.command.ResourceCommandExecutionProvider;
import org.praxisplatform.uischema.command.ResourceCommandExecutionRequest;
import org.praxisplatform.uischema.command.ResourceCommandHttpResponseAdapter;
import org.praxisplatform.uischema.command.ResourceCommandResponsePolicy;
import org.praxisplatform.uischema.command.ResourceCommandScope;
import org.praxisplatform.uischema.dto.CursorPage;
import org.praxisplatform.uischema.dto.LocateResponse;
import org.praxisplatform.uischema.dto.OptionDTO;
import org.praxisplatform.uischema.exporting.CollectionExportRequest;
import org.praxisplatform.uischema.exporting.CollectionExportResult;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.options.EntityLookupDescriptor;
import org.praxisplatform.uischema.options.LookupFilterRequest;
import org.praxisplatform.uischema.options.OptionSourceByIdsRequest;
import org.praxisplatform.uischema.options.OptionSourceDescriptor;
import org.praxisplatform.uischema.options.OptionSourceExecutionMode;
import org.praxisplatform.uischema.options.OptionSourceFilterRequest;
import org.praxisplatform.uischema.options.UnknownOptionSourceException;
import org.praxisplatform.uischema.rest.response.RestApiResource;
import org.praxisplatform.uischema.rest.response.RestApiResponse;
import org.praxisplatform.uischema.rest.response.RestApiResponseDistributionStatsResponse;
import org.praxisplatform.uischema.rest.response.RestApiResponseGroupByStatsResponse;
import org.praxisplatform.uischema.rest.response.RestApiResponseTimeSeriesStatsResponse;
import org.praxisplatform.uischema.rest.response.RestApiResponseComparisonStatsResponse;
import org.praxisplatform.uischema.service.base.BaseResourceQueryService;
import org.praxisplatform.uischema.stats.dto.DistributionStatsRequest;
import org.praxisplatform.uischema.stats.dto.DistributionStatsResponse;
import org.praxisplatform.uischema.stats.dto.ComparisonStatsRequest;
import org.praxisplatform.uischema.stats.dto.ComparisonStatsResponse;
import org.praxisplatform.uischema.stats.dto.GroupByStatsRequest;
import org.praxisplatform.uischema.stats.dto.GroupByStatsResponse;
import org.praxisplatform.uischema.stats.dto.TimeSeriesStatsRequest;
import org.praxisplatform.uischema.stats.dto.TimeSeriesStatsResponse;
import org.praxisplatform.uischema.action.ActionDefinitionRegistry;
import org.praxisplatform.uischema.action.ActionScope;
import org.praxisplatform.uischema.action.ActionCatalogResponse;
import org.praxisplatform.uischema.action.ActionCatalogService;
import org.praxisplatform.uischema.surface.SurfaceCatalogResponse;
import org.praxisplatform.uischema.surface.SurfaceCatalogService;
import org.praxisplatform.uischema.util.PageableBuilder;
import org.praxisplatform.uischema.util.SortBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.env.Environment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.Link;
import org.springframework.hateoas.Links;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.lang.reflect.Field;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Base canonica de leitura do novo core resource-oriented.
 *
 * <p>
 * Esta classe concentra a superficie HTTP de query, options, option-sources, stats e schema
 * discovery. A partir da Fase 4/5, ela tambem publica discovery contextual em
 * `GET /surfaces`, `GET /{id}/surfaces`, `GET /actions` e `GET /{id}/actions` a partir dos
 * catalogos canonicos de surfaces/actions. Recursos mutantes devem subir para
 * {@link AbstractResourceController}; recursos somente leitura devem herdar diretamente desta base.
 * </p>
 *
 * <p>
 * Ela tambem concentra a deteccao do base path do recurso, a composicao de links HATEOAS e a
 * exposicao do schema canonicalmente resolvido pelo starter. Por isso, qualquer extensao desta
 * base deve preservar a regra de que discovery e links sempre apontam para operacoes HTTP reais,
 * e nao para aliases ou contratos paralelos.
 * </p>
 */
public abstract class AbstractResourceQueryController<ResponseDTO, ID, FD extends GenericFilterDTO> {

    private static final Logger logger = LoggerFactory.getLogger(AbstractResourceQueryController.class);
    private static final String SCHEMAS_PATH = "/schemas";
    private static final String SCHEMAS_FILTERED_PATH = "/schemas/filtered";
    private static final String HDR = "X-Data-Version";
    private static final String MISSING_BASE_PATH = "/__unconfigured-resource__";
    private static final String GROUP_BY_STATS_REQUEST_EXAMPLE = """
            {
              "filter": {},
              "field": "status",
              "metrics": [
                { "metric": "count" }
              ],
              "includeNullBucket": false,
              "limit": 10
            }
            """;
    private static final String GROUP_BY_STATS_RESPONSE_EXAMPLE = """
            {
              "success": true,
              "data": {
                "field": "status",
                "buckets": [
                  { "key": "ATIVO", "label": "ATIVO", "count": 42, "metrics": { "count": 42 } },
                  { "key": "INATIVO", "label": "INATIVO", "count": 8, "metrics": { "count": 8 } }
                ],
                "totalBuckets": 2,
                "truncated": false
              }
            }
            """;
    private static final String TIME_SERIES_STATS_REQUEST_EXAMPLE = """
            {
              "filter": {},
              "field": "createdAt",
              "granularity": "day",
              "metrics": [
                { "metric": "sum", "field": "amount" }
              ],
              "from": "2026-01-01T00:00:00Z",
              "to": "2026-01-31T23:59:59Z",
              "fillGaps": true
            }
            """;
    private static final String TIME_SERIES_STATS_RESPONSE_EXAMPLE = """
            {
              "success": true,
              "data": {
                "field": "createdAt",
                "granularity": "day",
                "points": [
                  { "key": "2026-01-01", "label": "2026-01-01", "count": 3, "metrics": { "sum_amount": 1250.00 } },
                  { "key": "2026-01-02", "label": "2026-01-02", "count": 0, "metrics": { "sum_amount": 0.00 } }
                ]
              }
            }
            """;
    private static final String DISTRIBUTION_STATS_REQUEST_EXAMPLE = """
            {
              "filter": {},
              "field": "amount",
              "mode": "histogram",
              "interval": 1000,
              "metrics": [
                { "metric": "count" }
              ]
            }
            """;
    private static final String DISTRIBUTION_STATS_RESPONSE_EXAMPLE = """
            {
              "success": true,
              "data": {
                "field": "amount",
                "mode": "histogram",
                "buckets": [
                  { "key": "0", "label": "0-1000", "count": 5, "metrics": { "count": 5 } },
                  { "key": "1000", "label": "1000-2000", "count": 7, "metrics": { "count": 7 } }
                ]
              }
            }
            """;

    @Value("${springdoc.api-docs.path:/v3/api-docs}")
    private String openApiBasePath;

    @Value("${server.servlet.contextPath:}")
    private String contextPath;

    @Value("${praxis.query.by-ids.max:200}")
    private int byIdsMax;

    @Value("${praxis.pagination.max-size:200}")
    private int paginationMaxSize;

    @Autowired
    private Environment environment;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired(required = false)
    private SurfaceCatalogService surfaceCatalogService;

    @Autowired(required = false)
    private ActionCatalogService actionCatalogService;

    @Autowired(required = false)
    private ActionDefinitionRegistry actionDefinitionRegistry;

    @Autowired(required = false)
    private CapabilityService capabilityService;

    @Autowired(required = false)
    private ResourceVersionEtagService resourceVersionEtagService;

    private String detectedBasePath;

    @PostConstruct
    protected void initializeBasePath() {
        if (detectedBasePath != null) {
            return;
        }

        ApiResource apiResource = AnnotationUtils.findAnnotation(getClass(), ApiResource.class);
        if (apiResource != null) {
            detectedBasePath = extractBasePath(apiResource.value(), apiResource.path());
        }

        if (detectedBasePath == null) {
            RequestMapping requestMapping = AnnotationUtils.findAnnotation(getClass(), RequestMapping.class);
            if (requestMapping != null) {
                detectedBasePath = extractBasePath(requestMapping.value(), requestMapping.path());
            }
        }

        if (detectedBasePath == null) {
            detectedBasePath = MISSING_BASE_PATH;
            logger.warn(
                    "Controller {} nao declarou @ApiResource nem @RequestMapping; usando placeholder de base path.",
                    getClass().getName()
            );
        }
    }

    protected abstract BaseResourceQueryService<ResponseDTO, ID, FD> getService();

    /** Ponte Java usada pelo registry canônico; não publica um endpoint adicional. */
    public final ResourceStructuralCapabilities getStructuralCapabilities() {
        ResourceStructuralCapabilities capabilities = getService().getStructuralCapabilities();
        return capabilities == null ? ResourceStructuralCapabilities.unsupported() : capabilities;
    }

    protected abstract ID getResponseId(ResponseDTO dto);

    @SuppressWarnings("unchecked")
    protected Class<? extends AbstractResourceQueryController<ResponseDTO, ID, FD>> getControllerClass() {
        return (Class<? extends AbstractResourceQueryController<ResponseDTO, ID, FD>>) getClass();
    }

    protected boolean isReadOnlyResource() {
        return false;
    }

    protected String getIdFieldName() {
        return getService().getIdFieldName();
    }

    protected String getBasePath() {
        return detectedBasePath;
    }

    protected String getResourceKey() {
        String resourceKey = getResourceKeyOrNull();
        if (StringUtils.hasText(resourceKey)) {
            return resourceKey;
        }
        throw new IllegalStateException(
                "Contextual surface discovery requires @ApiResource(resourceKey=...) on " + getClass().getName()
        );
    }

    protected String getResourceKeyOrNull() {
        ApiResource apiResource = AnnotationUtils.findAnnotation(getClass(), ApiResource.class);
        if (apiResource != null && StringUtils.hasText(apiResource.resourceKey())) {
            return apiResource.resourceKey().trim();
        }
        return null;
    }

    protected EntityModel<ResponseDTO> toEntityModel(ResponseDTO dto) {
        if (!isHateoasEnabled()) {
            return EntityModel.of(dto);
        }

        ID id = getResponseId(dto);
        List<Link> links = new ArrayList<>();
        links.add(linkToSelf(id));
        links.addAll(buildEntityActionLinks(id));
        links.addAll(buildItemDiscoveryLinks(id));
        return EntityModel.of(dto, links);
    }

    protected RestApiResource<ResponseDTO> toResourceModel(ResponseDTO dto) {
        if (!isHateoasEnabled()) {
            return RestApiResource.of(dto);
        }

        ID id = getResponseId(dto);
        List<Link> links = new ArrayList<>();
        links.add(linkToSelf(id));
        links.addAll(buildEntityActionLinks(id));
        links.addAll(buildItemDiscoveryLinks(id));
        return RestApiResource.of(dto, links);
    }

    protected List<Link> buildItemActionLinks(ID id) {
        return List.of();
    }

    protected List<Link> buildEntityActionLinks(ID id) {
        return List.of();
    }

    protected List<Link> buildCollectionActionLinks() {
        return supportsCollectionExport() && isCollectionOperationAvailable("export")
                ? List.of(linkToExport())
                : List.of();
    }

    protected List<Link> buildItemDiscoveryLinks(ID id) {
        List<Link> links = new ArrayList<>();
        Link surfacesLink = linkToItemSurfacesIfAvailable(id);
        if (surfacesLink != null) {
            links.add(surfacesLink);
        }
        Link actionsLink = linkToItemActionsIfAvailable(id);
        if (actionsLink != null) {
            links.add(actionsLink);
        }
        Link capabilitiesLink = linkToItemCapabilitiesIfAvailable(id);
        if (capabilitiesLink != null) {
            links.add(capabilitiesLink);
        }
        return links;
    }

    protected List<Link> buildCollectionDiscoveryLinks() {
        List<Link> links = new ArrayList<>();
        Link surfacesLink = linkToCollectionSurfacesIfAvailable();
        if (surfacesLink != null) {
            links.add(surfacesLink);
        }
        Link actionsLink = linkToCollectionActionsIfAvailable();
        if (actionsLink != null) {
            links.add(actionsLink);
        }
        Link capabilitiesLink = linkToCollectionCapabilitiesIfAvailable();
        if (capabilitiesLink != null) {
            links.add(capabilitiesLink);
        }
        return links;
    }

    @PostMapping("/filter")
    @Operation(summary = "Filtrar registros", description = "Aplica filtros aos registros com paginacao.")
    public ResponseEntity<RestApiResponse<Page<EntityModel<ResponseDTO>>>> filter(
            @RequestBody FD filterDTO,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "includeIds", required = false) List<ID> includeIds,
            @RequestParam MultiValueMap<String, String> queryParams
    ) {
        if (size > paginationMaxSize) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Maximum page size exceeded: " + paginationMaxSize);
        }

        List<String> sort = queryParams.get("sort");
        Pageable pageable = buildPageable(page, size, sort, getService().getDefaultSort());
        Page<ResponseDTO> result = getService().filter(filterDTO, pageable, includeIds);
        if (!isHateoasEnabled()) {
            return successEnvelope(ResponseEntity.ok(), result, null);
        }
        Page<RestApiResource<ResponseDTO>> entityModels = result.map(this::toResourceModel);

        List<Link> links = new ArrayList<>();
        addCollectionOperationLink(links, "all", linkToAll());
        links.add(linkToUiSchema("/filter", "post", "request"));
        links.add(linkToUiSchema("/filter", "post", "response"));
        links.addAll(buildCollectionActionLinks());
        links.addAll(buildCollectionDiscoveryLinks());

        return successEnvelope(ResponseEntity.ok(), entityModels, Links.of(links));
    }

    @PostMapping("/filter/cursor")
    @Operation(summary = "Filtrar com paginacao por cursor")
    public ResponseEntity<RestApiResponse<CursorPage<EntityModel<ResponseDTO>>>> filterByCursor(
            @RequestBody FD filterDTO,
            @RequestParam(name = "after", required = false) String after,
            @RequestParam(name = "before", required = false) String before,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam MultiValueMap<String, String> queryParams
    ) {
        if (size > paginationMaxSize) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Maximum page size exceeded: " + paginationMaxSize);
        }

        List<String> sort = queryParams.get("sort");
        Sort sortObj = buildSort(sort, getService().getDefaultSort());

        CursorPage<ResponseDTO> result;
        try {
            result = getService().filterByCursor(filterDTO, sortObj, after, before, size);
        } catch (UnsupportedOperationException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "Not implemented.");
        }
        if (!isHateoasEnabled()) {
            return successEnvelope(ResponseEntity.ok(), result, null);
        }

        CursorPage<RestApiResource<ResponseDTO>> mapped = new CursorPage<>(
                result.content().stream().map(this::toResourceModel).toList(),
                result.next(),
                result.prev(),
                result.size()
        );

        List<Link> links = new ArrayList<>();
        addCollectionOperationLink(links, "all", linkToAll());
        links.add(linkToUiSchema("/filter/cursor", "post", "request"));
        links.add(linkToUiSchema("/filter/cursor", "post", "response"));
        links.addAll(buildCollectionActionLinks());
        links.addAll(buildCollectionDiscoveryLinks());

        return successEnvelope(ResponseEntity.ok(), mapped, Links.of(links));
    }

    @PostMapping("/locate")
    @Operation(summary = "Localizar posicao de registro")
    public ResponseEntity<LocateResponse> locate(
            @RequestBody FD filterDTO,
            @RequestParam("id") ID id,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam MultiValueMap<String, String> queryParams
    ) {
        if (size > paginationMaxSize) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Maximum page size exceeded: " + paginationMaxSize);
        }

        List<String> sort = queryParams.get("sort");
        Sort sortObj = buildSort(sort, getService().getDefaultSort());
        OptionalLong position = getService().locate(filterDTO, sortObj, id);
        if (position.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "Not implemented.");
        }

        long index = position.getAsLong();
        long page = size > 0 ? index / size : 0;
        return withVersion(ResponseEntity.ok(), new LocateResponse(index, page));
    }

    @PostMapping("/stats/group-by")
    @Operation(
            summary = "Group-by stats sobre o conjunto filtrado",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @Content(
                            examples = @ExampleObject(
                                    name = "groupByCount",
                                    summary = "Agrupamento por status com COUNT",
                                    value = GROUP_BY_STATS_REQUEST_EXAMPLE
                            )
                    )
            ),
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Group-by calculado com sucesso",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @io.swagger.v3.oas.annotations.media.Schema(
                                            implementation = RestApiResponseGroupByStatsResponse.class
                                    ),
                                    examples = @ExampleObject(
                                            name = "groupByCountResponse",
                                            summary = "Buckets agregados por status",
                                            value = GROUP_BY_STATS_RESPONSE_EXAMPLE
                                    )
                            )
                    ),
                    @ApiResponse(responseCode = "400", description = "Request de stats invalido"),
                    @ApiResponse(responseCode = "501", description = "Stats nao suportado pelo recurso")
            }
    )
    public ResponseEntity<RestApiResponse<GroupByStatsResponse>> groupByStats(
            @RequestBody GroupByStatsRequest<FD> request
    ) {
        try {
            GroupByStatsResponse result = getService().groupByStats(request);
            List<Link> links = new ArrayList<>();
            addCollectionOperationLink(links, "filter", linkToFilter());
            links.add(linkToUiSchema("/stats/group-by", "post", "request"));
            links.add(linkToUiSchema("/stats/group-by", "post", "response"));
            return withVersion(ResponseEntity.ok(), RestApiResponse.success(result, hateoasOrNull(Links.of(links))));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (UnsupportedOperationException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "Not implemented.");
        }
    }

    @PostMapping("/stats/comparison")
    @Operation(summary = "Comparacao de stats entre dois periodos governados")
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Comparacao calculada com sucesso",
                    content = @Content(schema = @Schema(implementation = RestApiResponseComparisonStatsResponse.class))
            ),
            @ApiResponse(responseCode = "400", description = "Request de comparacao invalido"),
            @ApiResponse(responseCode = "501", description = "Comparacao nao suportada pelo recurso")
    })
    public ResponseEntity<RestApiResponse<ComparisonStatsResponse>> comparisonStats(
            @RequestBody ComparisonStatsRequest<FD> request
    ) {
        try {
            ComparisonStatsResponse result = getService().comparisonStats(request);
            List<Link> links = new ArrayList<>();
            addCollectionOperationLink(links, "filter", linkToFilter());
            links.add(linkToUiSchema("/stats/comparison", "post", "request"));
            links.add(linkToUiSchema("/stats/comparison", "post", "response"));
            return withVersion(ResponseEntity.ok(), RestApiResponse.success(result, hateoasOrNull(Links.of(links))));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (UnsupportedOperationException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "Not implemented.");
        }
    }

    @PostMapping("/stats/timeseries")
    @Operation(
            summary = "Time-series stats sobre o conjunto filtrado",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @Content(
                            examples = @ExampleObject(
                                    name = "timeSeriesSum",
                                    summary = "Serie diaria com SUM e fillGaps",
                                    value = TIME_SERIES_STATS_REQUEST_EXAMPLE
                            )
                    )
            ),
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Serie temporal calculada com sucesso",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @io.swagger.v3.oas.annotations.media.Schema(
                                            implementation = RestApiResponseTimeSeriesStatsResponse.class
                                    ),
                                    examples = @ExampleObject(
                                            name = "timeSeriesSumResponse",
                                            summary = "Pontos de serie temporal com datas ISO",
                                            value = TIME_SERIES_STATS_RESPONSE_EXAMPLE
                                    )
                            )
                    ),
                    @ApiResponse(responseCode = "400", description = "Request de stats invalido"),
                    @ApiResponse(responseCode = "501", description = "Stats nao suportado pelo recurso")
            }
    )
    public ResponseEntity<RestApiResponse<TimeSeriesStatsResponse>> timeSeriesStats(
            @RequestBody TimeSeriesStatsRequest<FD> request
    ) {
        try {
            TimeSeriesStatsResponse result = getService().timeSeriesStats(request);
            List<Link> links = new ArrayList<>();
            addCollectionOperationLink(links, "filter", linkToFilter());
            links.add(linkToUiSchema("/stats/timeseries", "post", "request"));
            links.add(linkToUiSchema("/stats/timeseries", "post", "response"));
            return withVersion(ResponseEntity.ok(), RestApiResponse.success(result, hateoasOrNull(Links.of(links))));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (UnsupportedOperationException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "Not implemented.");
        }
    }

    @PostMapping("/stats/distribution")
    @Operation(
            summary = "Distribution stats sobre o conjunto filtrado",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @Content(
                            examples = @ExampleObject(
                                    name = "distributionHistogram",
                                    summary = "Distribuicao por histograma com COUNT",
                                    value = DISTRIBUTION_STATS_REQUEST_EXAMPLE
                            )
                    )
            ),
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Distribuicao calculada com sucesso",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @io.swagger.v3.oas.annotations.media.Schema(
                                            implementation = RestApiResponseDistributionStatsResponse.class
                                    ),
                                    examples = @ExampleObject(
                                            name = "distributionHistogramResponse",
                                            summary = "Buckets de histograma canonicos",
                                            value = DISTRIBUTION_STATS_RESPONSE_EXAMPLE
                                    )
                            )
                    ),
                    @ApiResponse(responseCode = "400", description = "Request de stats invalido"),
                    @ApiResponse(responseCode = "501", description = "Stats nao suportado pelo recurso")
            }
    )
    public ResponseEntity<RestApiResponse<DistributionStatsResponse>> distributionStats(
            @RequestBody DistributionStatsRequest<FD> request
    ) {
        try {
            DistributionStatsResponse result = getService().distributionStats(request);
            List<Link> links = new ArrayList<>();
            addCollectionOperationLink(links, "all", linkToAll());
            links.add(linkToUiSchema("/stats/distribution", "post", "request"));
            links.add(linkToUiSchema("/stats/distribution", "post", "response"));
            return withVersion(ResponseEntity.ok(), RestApiResponse.success(result, hateoasOrNull(Links.of(links))));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (UnsupportedOperationException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "Not implemented.");
        }
    }

    @PostMapping("/export")
    @Operation(summary = "Exportar colecao", description = "Exporta dados da colecao preservando escopo, selecao, filtros, ordenacao e campos.")
    public ResponseEntity<?> exportCollection(@RequestBody CollectionExportRequest<FD> request) {
        try {
            CollectionExportResult result = getService().exportCollection(request);
            if (result == null) {
                throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "Not implemented.");
            }

            if (result.deferredStatus()) {
                return withVersion(
                        ResponseEntity.accepted().contentType(MediaType.APPLICATION_JSON),
                        exportResultBody(result)
                );
            }

            if (StringUtils.hasText(result.downloadUrl()) && result.content().length == 0) {
                return withVersion(
                        ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON),
                        exportResultBody(result)
                );
            }

            ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                    .contentType(resolveExportContentType(result))
                    .header(
                            org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                            ContentDisposition.attachment()
                                    .filename(resolveExportFileName(result, request))
                                    .build()
                                    .toString()
                    );
            if (result.rowCount() != null) {
                builder.header("X-Export-Row-Count", result.rowCount().toString());
            }
            appendExportMetadataHeaders(builder, result);
            return withVersion(builder, result.content());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (UnsupportedOperationException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "Not implemented.", ex);
        }
    }

    @GetMapping("/all")
    @Operation(summary = "Listar itens")
    public ResponseEntity<RestApiResponse<List<EntityModel<ResponseDTO>>>> getAll() {
        List<ResponseDTO> dtos = getService().findAll();
        if (!isHateoasEnabled()) {
            return successEnvelope(ResponseEntity.ok(), dtos, null);
        }

        List<RestApiResource<ResponseDTO>> entityModels = dtos.stream()
                .map(this::toResourceModel)
                .toList();

        List<Link> links = new ArrayList<>();
        addCollectionOperationLink(links, "filter", linkToFilter());
        addCollectionOperationLink(links, "cursor", linkToFilterCursor());
        links.add(linkToUiSchema("/all", "get", "response"));
        links.addAll(buildCollectionActionLinks());
        links.addAll(buildCollectionDiscoveryLinks());

        return successEnvelope(ResponseEntity.ok(), entityModels, Links.of(links));
    }

    @GetMapping("/by-ids")
    @Operation(summary = "Buscar registros por IDs")
    public ResponseEntity<List<ResponseDTO>> getByIds(@RequestParam(name = "ids", required = false) List<ID> ids) {
        if (ids == null || ids.isEmpty()) {
            return withVersion(ResponseEntity.ok(), List.of());
        }
        if (ids.size() > byIdsMax) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Maximum number of IDs exceeded: " + byIdsMax);
        }
        return withVersion(ResponseEntity.ok(), getService().findAllById(ids));
    }

    @PostMapping("/options/filter")
    @Operation(summary = "Listar opcoes filtradas")
    public ResponseEntity<Page<OptionDTO<ID>>> filterOptions(
            @RequestBody FD filterDTO,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam MultiValueMap<String, String> queryParams
    ) {
        if (size > paginationMaxSize) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Maximum page size exceeded: " + paginationMaxSize);
        }

        List<String> sort = queryParams.get("sort");
        Pageable pageable = buildPageable(page, size, sort, getService().getDefaultSort());
        return withVersion(ResponseEntity.ok(), getService().filterOptions(filterDTO, pageable));
    }

    @PostMapping("/option-sources/{sourceKey}/options/filter")
    @Operation(summary = "Listar opcoes filtradas por fonte derivada")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            required = false,
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = OptionSourceFilterRequest.class)
            )
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Opcoes retornadas sem envelope."),
            @ApiResponse(responseCode = "404", description = "Option-source inexistente."),
            @ApiResponse(responseCode = "422", description = "Payload, policy ou filtros invalidos."),
            @ApiResponse(responseCode = "501", description = "Capability ou provider nao implementado.")
    })
    public ResponseEntity<Page<OptionDTO<Object>>> filterOptionSourceOptions(
            @PathVariable String sourceKey,
            @RequestBody(required = false) JsonNode request,
            @RequestParam(name = "search", required = false) String search,
            @RequestParam(name = "searchStrategy", required = false) String searchStrategy,
            @RequestParam(name = "includeIds", required = false) List<String> includeIds,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam MultiValueMap<String, String> queryParams
    ) {
        if (size > paginationMaxSize) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Maximum page size exceeded: " + paginationMaxSize);
        }

        try {
            List<String> sort = queryParams.get("sort");
            OptionSourceDescriptor descriptor = getService().resolveOptionSource(sourceKey);
            OptionSourceFilterEnvelope<FD> envelope = parseOptionSourceFilterRequest(
                    request,
                    descriptor,
                    search,
                    searchStrategy,
                    includeIds,
                    sort
            );
            Pageable pageable = buildPageable(page, size, sort, Sort.unsorted());
            return withOptionSourceVersion(
                    ResponseEntity.ok(),
                    sourceKey,
                    getService().filterOptionSourceOptions(
                            sourceKey,
                            envelope.request(),
                            pageable,
                            envelope.providerFilterPayload()
                    )
            );
        } catch (UnknownOptionSourceException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), ex);
        } catch (UnsupportedOperationException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, ex.getMessage(), ex);
        }
    }

    private OptionSourceFilterEnvelope<FD> parseOptionSourceFilterRequest(
            JsonNode request,
            OptionSourceDescriptor descriptor,
            String search,
            String searchStrategy,
            List<String> includeIds,
            List<String> sort
    ) {
        JsonNode body = request == null || request.isNull() ? objectMapper.createObjectNode() : request;
        if (!body.isObject()) {
            throw new IllegalArgumentException("Option source filter request must be a JSON object.");
        }

        boolean envelopeBody = hasOptionSourceEnvelopeShape(body);
        JsonNode filterNode = envelopeBody ? body.get("filter") : body;
        OptionSourceFilterParts<FD> filterParts = parseOptionSourceFilterParts(filterNode, descriptor);
        List<LookupFilterRequest> filters = parseLookupFilters(body.get("filters"));
        String bodySearch = textOrNull(body.get("search"));
        String bodySearchStrategy = textOrNull(body.get("searchStrategy"));
        String bodySort = textOrNull(body.get("sort"));
        Collection<Object> bodyIncludeIds = parseIncludeIds(body.get("includeIds"));

        String effectiveSearch = StringUtils.hasText(bodySearch) ? bodySearch : search;
        String effectiveSearchStrategy = StringUtils.hasText(bodySearchStrategy) ? bodySearchStrategy : searchStrategy;
        String effectiveSort = StringUtils.hasText(bodySort)
                ? bodySort
                : firstLegacySortKey(sort);
        Collection<Object> effectiveIncludeIds = !bodyIncludeIds.isEmpty()
                ? bodyIncludeIds
                : (includeIds == null ? List.of() : List.copyOf(includeIds));

        OptionSourceFilterRequest<FD> effectiveRequest = new OptionSourceFilterRequest<>(
                filterParts.filter(),
                filters,
                effectiveSearch,
                effectiveSearchStrategy,
                effectiveSort,
                effectiveIncludeIds
        );
        return new OptionSourceFilterEnvelope<>(effectiveRequest, filterParts.providerFilterPayload());
    }

    private boolean hasOptionSourceEnvelopeShape(JsonNode body) {
        if (body.has("filter")) {
            return true;
        }
        Set<String> bodyFields = fieldNames(body);
        Set<String> envelopeFields = Set.of("filters", "search", "searchStrategy", "sort", "includeIds");
        boolean hasEnvelopeField = bodyFields.stream().anyMatch(envelopeFields::contains);
        if (!hasEnvelopeField) {
            return false;
        }
        Set<String> filterFields = filterDtoFieldNames();
        return !filterFields.containsAll(bodyFields);
    }

    private OptionSourceFilterParts<FD> parseOptionSourceFilterParts(
            JsonNode filterNode,
            OptionSourceDescriptor descriptor
    ) {
        if (filterNode == null || filterNode.isNull()) {
            return new OptionSourceFilterParts<>(null, null);
        }
        if (!filterNode.isObject()) {
            throw new IllegalArgumentException("Option source filter must be a JSON object.");
        }

        Set<String> dependencyKeys = optionSourceDependencyKeys(descriptor);
        ObjectNode resourceFilterNode = objectMapper.createObjectNode();
        Map<String, Object> dependencyPayload = new LinkedHashMap<>();
        filterNode.fields().forEachRemaining(entry -> {
            String field = entry.getKey();
            JsonNode value = entry.getValue();
            if (dependencyKeys.contains(field)) {
                dependencyPayload.put(field, objectMapper.convertValue(value, Object.class));
            } else {
                resourceFilterNode.set(field, value);
            }
        });

        FD filter = resourceFilterNode.isEmpty()
                ? null
                : objectMapper.convertValue(resourceFilterNode, resolveFilterDtoClass());
        Object providerPayload = dependencyPayload.isEmpty()
                ? null
                : Collections.unmodifiableMap(new LinkedHashMap<>(dependencyPayload));
        return new OptionSourceFilterParts<>(filter, providerPayload);
    }

    private Set<String> optionSourceDependencyKeys(OptionSourceDescriptor descriptor) {
        Set<String> keys = new LinkedHashSet<>();
        if (descriptor == null || descriptor.executionMode() == OptionSourceExecutionMode.JPA) {
            return keys;
        }
        keys.addAll(descriptor.dependsOn());
        keys.addAll(descriptor.dependencyFilterMap().keySet());
        EntityLookupDescriptor lookup = descriptor.entityLookup();
        if (lookup != null) {
            keys.addAll(lookup.dependencyFilterMap().keySet());
        }
        keys.removeIf(key -> key == null || key.isBlank());
        return keys;
    }

    private List<LookupFilterRequest> parseLookupFilters(JsonNode filtersNode) {
        if (filtersNode == null || filtersNode.isNull()) {
            return List.of();
        }
        if (!filtersNode.isArray()) {
            throw new IllegalArgumentException("Option source structured filters must be a JSON array.");
        }
        return objectMapper.convertValue(filtersNode, new TypeReference<>() {});
    }

    private Collection<Object> parseIncludeIds(JsonNode includeIdsNode) {
        if (includeIdsNode == null || includeIdsNode.isNull()) {
            return List.of();
        }
        if (!includeIdsNode.isArray()) {
            throw new IllegalArgumentException("Option source includeIds must be a JSON array.");
        }
        return objectMapper.convertValue(includeIdsNode, new TypeReference<>() {});
    }

    private Collection<Object> parseIds(JsonNode idsNode) {
        if (idsNode == null || idsNode.isNull()) {
            return List.of();
        }
        if (!idsNode.isArray()) {
            throw new IllegalArgumentException("Option source ids must be a JSON array.");
        }
        return objectMapper.convertValue(idsNode, new TypeReference<>() {});
    }

    private String textOrNull(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return node.asText(null);
    }

    @SuppressWarnings("unchecked")
    private Class<FD> resolveFilterDtoClass() {
        Class<?> filterType = ResolvableType.forClass(getClass())
                .as(AbstractResourceQueryController.class)
                .getGeneric(2)
                .resolve();
        if (filterType == null || !GenericFilterDTO.class.isAssignableFrom(filterType)) {
            throw new IllegalStateException("Unable to resolve resource FilterDTO type.");
        }
        return (Class<FD>) filterType;
    }

    private Set<String> fieldNames(JsonNode node) {
        Set<String> names = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private Set<String> filterDtoFieldNames() {
        List<Field> fields = new ArrayList<>();
        Class<?> current = resolveFilterDtoClass();
        while (current != null && current != Object.class) {
            fields.addAll(List.of(current.getDeclaredFields()));
            current = current.getSuperclass();
        }
        return fields.stream().map(Field::getName).collect(Collectors.toUnmodifiableSet());
    }

    private record OptionSourceFilterEnvelope<FD extends GenericFilterDTO>(
            OptionSourceFilterRequest<FD> request,
            Object providerFilterPayload
    ) {
    }

    private record OptionSourceByIdsEnvelope<FD extends GenericFilterDTO>(
            OptionSourceByIdsRequest<FD> request,
            Object providerFilterPayload
    ) {
    }

    private record OptionSourceFilterParts<FD extends GenericFilterDTO>(
            FD filter,
            Object providerFilterPayload
    ) {
    }

    private String firstLegacySortKey(List<String> sort) {
        if (sort == null || sort.isEmpty()) {
            return null;
        }
        return sort.stream()
                .filter(StringUtils::hasText)
                .map(entry -> entry.split(",", 2)[0].trim())
                .filter(StringUtils::hasText)
                .findFirst()
                .orElse(null);
    }

    private Pageable buildPageable(int page, int size, List<String> sort, Sort fallback) {
        try {
            return PageableBuilder.from(page, size, sort, fallback);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), ex);
        }
    }

    private Sort buildSort(List<String> sort, Sort fallback) {
        try {
            return SortBuilder.from(sort, fallback);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), ex);
        }
    }

    @GetMapping("/options/by-ids")
    @Operation(summary = "Buscar opcoes por IDs")
    public ResponseEntity<List<OptionDTO<ID>>> getOptionsByIds(
            @RequestParam(name = "ids", required = false) List<ID> ids
    ) {
        if (ids == null || ids.isEmpty()) {
            return withVersion(ResponseEntity.ok(), List.of());
        }
        if (ids.size() > byIdsMax) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Maximum number of IDs exceeded: " + byIdsMax);
        }
        return withVersion(ResponseEntity.ok(), getService().byIdsOptions(ids));
    }

    @GetMapping("/option-sources/{sourceKey}/options/by-ids")
    @Operation(summary = "Buscar opcoes derivadas por IDs")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Opcoes retornadas sem envelope."),
            @ApiResponse(responseCode = "404", description = "Option-source inexistente."),
            @ApiResponse(responseCode = "422", description = "Payload ou IDs invalidos."),
            @ApiResponse(responseCode = "501", description = "Capability ou provider nao implementado.")
    })
    public ResponseEntity<List<OptionDTO<Object>>> getOptionSourceOptionsByIds(
            @PathVariable String sourceKey,
            @RequestParam(name = "ids", required = false) List<String> ids
    ) {
        try {
            if (ids != null && ids.size() > byIdsMax) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Maximum number of IDs exceeded: " + byIdsMax);
            }
            return withOptionSourceVersion(
                    ResponseEntity.ok(),
                    sourceKey,
                    getService().byIdsOptionSourceOptions(sourceKey, ids == null ? List.of() : List.copyOf(ids))
            );
        } catch (UnknownOptionSourceException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), ex);
        } catch (UnsupportedOperationException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, ex.getMessage(), ex);
        }
    }

    @PostMapping("/option-sources/{sourceKey}/options/by-ids")
    @Operation(summary = "Buscar opcoes derivadas por IDs com contexto")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            required = false,
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = OptionSourceByIdsRequest.class)
            )
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Opcoes retornadas sem envelope."),
            @ApiResponse(responseCode = "404", description = "Option-source inexistente."),
            @ApiResponse(responseCode = "422", description = "Payload ou IDs invalidos."),
            @ApiResponse(responseCode = "501", description = "Capability ou provider nao implementado.")
    })
    public ResponseEntity<List<OptionDTO<Object>>> postOptionSourceOptionsByIds(
            @PathVariable String sourceKey,
            @RequestBody(required = false) JsonNode request
    ) {
        try {
            OptionSourceDescriptor descriptor = getService().resolveOptionSource(sourceKey);
            OptionSourceByIdsEnvelope<FD> envelope = parseOptionSourceByIdsRequest(request, descriptor);
            Collection<Object> ids = envelope.request().ids();
            if (ids.size() > byIdsMax) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Maximum number of IDs exceeded: " + byIdsMax);
            }
            return withOptionSourceVersion(
                    ResponseEntity.ok(),
                    sourceKey,
                    getService().byIdsOptionSourceOptions(
                            sourceKey,
                            envelope.request(),
                            envelope.providerFilterPayload()
                    )
            );
        } catch (UnknownOptionSourceException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), ex);
        } catch (UnsupportedOperationException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, ex.getMessage(), ex);
        }
    }

    private OptionSourceByIdsEnvelope<FD> parseOptionSourceByIdsRequest(
            JsonNode request,
            OptionSourceDescriptor descriptor
    ) {
        JsonNode body = request == null || request.isNull() ? objectMapper.createObjectNode() : request;
        if (!body.isObject()) {
            throw new IllegalArgumentException("Option source by-ids request must be a JSON object.");
        }
        OptionSourceFilterParts<FD> filterParts = parseOptionSourceFilterParts(body.get("filter"), descriptor);
        List<LookupFilterRequest> filters = parseLookupFilters(body.get("filters"));
        OptionSourceByIdsRequest<FD> effectiveRequest = new OptionSourceByIdsRequest<>(
                filterParts.filter(),
                filters,
                parseIds(body.get("ids"))
        );
        return new OptionSourceByIdsEnvelope<>(effectiveRequest, filterParts.providerFilterPayload());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Abrir item")
    public ResponseEntity<RestApiResponse<ResponseDTO>> getById(@PathVariable ID id) {
        ResponseDTO dto = getService().findById(id);

        List<Link> linkList = new ArrayList<>();
        linkList.add(linkToSelf(id));
        linkList.add(linkToAll());
        linkList.add(linkToFilter());
        linkList.add(linkToFilterCursor());
        linkList.addAll(buildItemActionLinks(id));
        linkList.addAll(buildItemDiscoveryLinks(id));
        linkList.add(linkToUiSchema("/{id}", "get", "response"));

        return withResourceVersion(
                ResponseEntity.ok(),
                id,
                RestApiResponse.success(dto, hateoasOrNull(Links.of(linkList)))
        );
    }

    @GetMapping("/{id}/surfaces")
    @Operation(summary = "Descobrir surfaces contextuais do registro")
    public ResponseEntity<SurfaceCatalogResponse> getItemSurfaces(@PathVariable ID id) {
        if (surfaceCatalogService == null) {
            throw new IllegalStateException("SurfaceCatalogService is not configured for contextual discovery.");
        }
        getService().findById(id);
        return withVersion(ResponseEntity.ok(), surfaceCatalogService.findItemSurfaces(getResourceKey(), id));
    }

    @GetMapping("/{id}/actions")
    @Operation(summary = "Descobrir workflow actions contextuais do registro")
    public ResponseEntity<ActionCatalogResponse> getItemActions(@PathVariable ID id) {
        if (actionCatalogService == null) {
            throw new IllegalStateException("ActionCatalogService is not configured for contextual discovery.");
        }
        getService().findById(id);
        return withVersion(ResponseEntity.ok(), actionCatalogService.findItemActions(getResourceKey(), id));
    }

    @GetMapping("/actions")
    @Operation(summary = "Descobrir workflow actions contextuais da colecao")
    public ResponseEntity<ActionCatalogResponse> getCollectionActions() {
        if (actionCatalogService == null) {
            throw new IllegalStateException("ActionCatalogService is not configured for contextual discovery.");
        }
        return withVersion(ResponseEntity.ok(), actionCatalogService.findCollectionActions(getResourceKey()));
    }

    @GetMapping("/capabilities")
    @Operation(summary = "Descobrir capabilities unificadas da colecao")
    public ResponseEntity<CapabilitySnapshot> getCollectionCapabilities() {
        if (capabilityService == null) {
            throw new IllegalStateException("CapabilityService is not configured for contextual discovery.");
        }
        CapabilitySnapshot snapshot = capabilityService.collectionCapabilities(getResourceKey(), getBasePath());
        return withVersion(
                ResponseEntity.ok(),
                snapshot
        );
    }

    @GetMapping("/{id}/capabilities")
    @Operation(summary = "Descobrir capabilities unificadas do registro")
    public ResponseEntity<CapabilitySnapshot> getItemCapabilities(@PathVariable ID id) {
        if (capabilityService == null) {
            throw new IllegalStateException("CapabilityService is not configured for contextual discovery.");
        }
        getService().findById(id);
        return withVersion(
                ResponseEntity.ok(),
                capabilityService.itemCapabilities(getResourceKey(), getBasePath(), id)
        );
    }

    @GetMapping(SCHEMAS_PATH)
    @Operation(summary = "Redirecionar para o schema filtrado do recurso")
    public ResponseEntity<Void> getSchema() {
        Link metadataLink = linkToUiSchema("/all", "get", "response");
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(metadataLink.getHref()))
                .build();
    }

    protected Link linkToSelf(ID id) {
        return Link.of(resourcePath(id)).withSelfRel();
    }

    protected Link linkToAll() {
        return Link.of(resourcePath("all"), "all");
    }

    protected Link linkToFilter() {
        return Link.of(resourcePath("filter"), "filter");
    }

    protected Link linkToFilterCursor() {
        return Link.of(resourcePath("filter", "cursor"), "filter-cursor");
    }

    protected Link linkToExport() {
        return Link.of(resourcePath("export"), "export");
    }

    protected void addCollectionOperationLink(List<Link> links, String operationId, Link link) {
        if (isCollectionOperationAvailable(operationId)) {
            links.add(link);
        }
    }

    protected boolean isCollectionOperationAvailable(String operationId) {
        String resourceKey = getResourceKeyOrNull();
        if (!StringUtils.hasText(resourceKey) || capabilityService == null) {
            return true;
        }
        AvailabilityDecision decision = capabilityService.collectionOperationAvailability(resourceKey, getBasePath(), operationId);
        return decision == null || decision.allowed();
    }

    protected boolean isItemOperationAvailable(String operationId, ID id) {
        String resourceKey = getResourceKeyOrNull();
        if (!StringUtils.hasText(resourceKey) || capabilityService == null) {
            return true;
        }
        AvailabilityDecision decision = capabilityService.itemOperationAvailability(resourceKey, getBasePath(), operationId, id);
        return decision == null || decision.allowed();
    }

    protected void assertCollectionOperationAvailable(String operationId) {
        String resourceKey = getResourceKeyOrNull();
        if (!StringUtils.hasText(resourceKey) || capabilityService == null) {
            return;
        }
        AvailabilityDecision decision = capabilityService.collectionOperationAvailability(resourceKey, getBasePath(), operationId);
        if (decision != null && !decision.allowed()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, decision.reason());
        }
    }

    protected void assertItemOperationAvailable(String operationId, ID id) {
        String resourceKey = getResourceKeyOrNull();
        if (!StringUtils.hasText(resourceKey) || capabilityService == null) {
            return;
        }
        AvailabilityDecision decision = capabilityService.itemOperationAvailability(resourceKey, getBasePath(), operationId, id);
        if (decision != null && !decision.allowed()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, decision.reason());
        }
    }

    protected ResponseEntity<?> executeCollectionCommand(
            String commandId,
            Object payload,
            ResourceCommandResponsePolicy responsePolicy,
            ResourceCommandExecutionProvider provider
    ) {
        return executeCollectionCommand(commandId, payload, responsePolicy, provider, Map.of());
    }

    protected ResponseEntity<?> executeCollectionCommand(
            String commandId,
            Object payload,
            ResourceCommandResponsePolicy responsePolicy,
            ResourceCommandExecutionProvider provider,
            Map<String, Object> publicMetadata
    ) {
        ResourceCommandExecutionRequest request = new ResourceCommandExecutionRequest(
                getResourceKey(),
                getBasePath(),
                commandId,
                ResourceCommandScope.COLLECTION,
                null,
                payload,
                responsePolicy,
                publicMetadata
        );
        return executeGovernedCommand(request, provider, collectionCommandLinks(commandId));
    }

    protected ResponseEntity<?> executeItemCommand(
            String commandId,
            ID id,
            Object payload,
            ResourceCommandResponsePolicy responsePolicy,
            ResourceCommandExecutionProvider provider
    ) {
        return executeItemCommand(commandId, id, payload, responsePolicy, provider, Map.of());
    }

    protected ResponseEntity<?> executeItemCommand(
            String commandId,
            ID id,
            Object payload,
            ResourceCommandResponsePolicy responsePolicy,
            ResourceCommandExecutionProvider provider,
            Map<String, Object> publicMetadata
    ) {
        ResourceCommandExecutionRequest request = new ResourceCommandExecutionRequest(
                getResourceKey(),
                getBasePath(),
                commandId,
                ResourceCommandScope.ITEM,
                id,
                payload,
                responsePolicy,
                publicMetadata
        );
        return executeGovernedCommand(request, provider, itemCommandLinks(commandId, id));
    }

    private ResponseEntity<?> executeGovernedCommand(
            ResourceCommandExecutionRequest request,
            ResourceCommandExecutionProvider provider,
            Links successLinks
    ) {
        GovernedResourceCommandExecutor executor = new GovernedResourceCommandExecutor(
                provider,
                this::evaluateResourceCommandAvailability,
                ResourceCommandEvidenceSanitizer.defaults()
        );
        return withCommandDatasetVersion(new ResourceCommandHttpResponseAdapter().toResponse(
                executor.execute(request),
                hateoasOrNull(successLinks)
        ));
    }

    private AvailabilityDecision evaluateResourceCommandAvailability(ResourceOperationAvailabilityContext context) {
        if (context == null || capabilityService == null || !StringUtils.hasText(context.resourceKey())) {
            return AvailabilityDecision.allowAll();
        }
        if (ResourceCommandScope.ITEM.name().equals(context.scope())) {
            return capabilityService.itemOperationAvailability(
                    context.resourceKey(),
                    context.resourcePath(),
                    context.operationId(),
                    context.resourceId()
            );
        }
        return capabilityService.collectionOperationAvailability(
                context.resourceKey(),
                context.resourcePath(),
                context.operationId()
        );
    }

    private ResponseEntity<?> withCommandDatasetVersion(ResponseEntity<?> response) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(response.getStatusCode());
        builder.headers(response.getHeaders());
        getService().getDatasetVersion().ifPresent(v -> builder.header(HDR, v));
        return response.getBody() == null ? builder.build() : builder.body(response.getBody());
    }

    private Links collectionCommandLinks(String commandId) {
        String operationPath = "/actions/" + commandId;
        return Links.of(
                linkToAll(),
                linkToFilter(),
                linkToFilterCursor(),
                linkToUiSchema(operationPath, "post", "request"),
                linkToUiSchema(operationPath, "post", "response")
        );
    }

    private Links itemCommandLinks(String commandId, ID id) {
        String operationPath = "/{id}/actions/" + commandId;
        return Links.of(
                linkToSelf(id),
                linkToAll(),
                linkToFilter(),
                linkToFilterCursor(),
                linkToUiSchema(operationPath, "post", "request"),
                linkToUiSchema(operationPath, "post", "response")
        );
    }

    protected boolean supportsCollectionExport() {
        return getService().supportsCollectionExport();
    }

    protected Link linkToCollectionSurfacesIfAvailable() {
        String resourceKey = getResourceKeyOrNull();
        if (!StringUtils.hasText(resourceKey) || surfaceCatalogService == null) {
            return null;
        }
        String href = UriComponentsBuilder.fromPath(StringUtils.hasText(contextPath) ? contextPath : "")
                .path("/schemas/surfaces")
                .queryParam("resource", resourceKey)
                .build()
                .toUriString();
        return Link.of(href, "surfaces");
    }

    protected Link linkToItemSurfacesIfAvailable(ID id) {
        if (!StringUtils.hasText(getResourceKeyOrNull()) || surfaceCatalogService == null) {
            return null;
        }
        return Link.of(resourceDiscoveryPath(id, "surfaces"), "surfaces");
    }

    protected Link linkToCollectionActionsIfAvailable() {
        if (!hasWorkflowActions(ActionScope.COLLECTION) || actionCatalogService == null) {
            return null;
        }
        return Link.of(resourceDiscoveryPath("actions"), "actions");
    }

    protected Link linkToItemActionsIfAvailable(ID id) {
        if (!hasWorkflowActions(ActionScope.ITEM) || actionCatalogService == null) {
            return null;
        }
        return Link.of(resourceDiscoveryPath(id, "actions"), "actions");
    }

    protected Link linkToCollectionCapabilitiesIfAvailable() {
        if (!StringUtils.hasText(getResourceKeyOrNull()) || capabilityService == null) {
            return null;
        }
        return Link.of(resourceDiscoveryPath("capabilities"), "capabilities");
    }

    protected Link linkToItemCapabilitiesIfAvailable(ID id) {
        if (!StringUtils.hasText(getResourceKeyOrNull()) || capabilityService == null) {
            return null;
        }
        return Link.of(resourceDiscoveryPath(id, "capabilities"), "capabilities");
    }

    private String resourceDiscoveryPath(String discoverySegment) {
        return resourcePath(discoverySegment);
    }

    private String resourceDiscoveryPath(ID id, String discoverySegment) {
        return resourcePath(id, discoverySegment);
    }

    protected String resourcePath(Object... segments) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath(StringUtils.hasText(contextPath) ? contextPath : "")
                .path(getBasePath());
        if (segments != null) {
            for (Object segment : segments) {
                if (segment != null) {
                    builder.pathSegment(String.valueOf(segment));
                }
            }
        }
        return builder.build().toUriString();
    }

    protected Link linkToDocs() {
        String docsUrl = String.format("%s%s", openApiBasePath, getBasePath());
        return Link.of(docsUrl, "docs");
    }

    private MediaType resolveExportContentType(CollectionExportResult result) {
        if (StringUtils.hasText(result.contentType())) {
            return MediaType.parseMediaType(result.contentType());
        }
        return MediaType.APPLICATION_OCTET_STREAM;
    }

    private String resolveExportFileName(CollectionExportResult result, CollectionExportRequest<FD> request) {
        if (StringUtils.hasText(result.fileName())) {
            return result.fileName().trim();
        }
        if (request != null && StringUtils.hasText(request.fileName())) {
            return request.fileName().trim();
        }
        return "export";
    }

    private Map<String, Object> exportResultBody(CollectionExportResult result) {
        return Map.of(
                "status", result.status().value(),
                "format", result.format().value(),
                "scope", result.scope().value(),
                "fileName", result.fileName() == null ? "" : result.fileName(),
                "downloadUrl", result.downloadUrl() == null ? "" : result.downloadUrl(),
                "jobId", result.jobId() == null ? "" : result.jobId(),
                "rowCount", result.rowCount() == null ? 0 : result.rowCount(),
                "warnings", result.warnings(),
                "metadata", result.metadata()
        );
    }

    private void appendExportMetadataHeaders(ResponseEntity.BodyBuilder builder, CollectionExportResult result) {
        Map<String, Object> metadata = result.metadata();
        addExportHeader(builder, "X-Export-Truncated", metadata.get("truncated"));
        addExportHeader(builder, "X-Export-Max-Rows", metadata.get("maxRows"));
        addExportHeader(builder, "X-Export-Candidate-Row-Count", metadata.get("candidateRows"));
        if (!result.warnings().isEmpty()) {
            builder.header("X-Export-Warnings", String.join(" | ", result.warnings()));
        }
    }

    private void addExportHeader(ResponseEntity.BodyBuilder builder, String headerName, Object value) {
        if (value != null) {
            builder.header(headerName, value.toString());
        }
    }

    protected Link linkToUiSchema(String methodPath, String operation, String schemaType) {
        if (methodPath == null || methodPath.trim().isEmpty()) {
            throw new IllegalArgumentException("Parameter 'methodPath' must not be null or blank.");
        }
        if (operation == null || operation.trim().isEmpty()) {
            throw new IllegalArgumentException("Parameter 'operation' must not be null or blank.");
        }

        String resolvedSchemaType = (schemaType == null || schemaType.trim().isEmpty())
                ? "response"
                : schemaType.toLowerCase();

        String fullPath = resolveSchemaMethodPath(methodPath);
        try {
            String docsPath = UriComponentsBuilder.fromPath(contextPath + SCHEMAS_FILTERED_PATH)
                    .queryParam("path", fullPath)
                    .queryParam("operation", operation.toLowerCase())
                    .queryParam("schemaType", resolvedSchemaType)
                    .queryParam("idField", getIdFieldName())
                    .queryParam("readOnly", Boolean.toString(isReadOnlyResource()))
                    .build()
                    .toUriString();
            return Link.of(docsPath, "schema");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build the filtered schema link.", e);
        }
    }

    protected <T> ResponseEntity<T> withVersion(ResponseEntity.BodyBuilder builder, T body) {
        getService().getDatasetVersion().ifPresent(v -> builder.header(HDR, v));
        return builder.body(body);
    }

    /**
     * Adds a record ETag only for resources that explicitly expose a persisted item version.
     * This is intentionally distinct from {@code X-Data-Version}, which describes collection data.
     */
    protected <T> ResponseEntity<T> withResourceVersion(ResponseEntity.BodyBuilder builder, ID id, T body) {
        if (resourceVersionEtagService != null) {
            getService().getResourceVersion(id).ifPresent(version -> builder.eTag(
                    resourceVersionEtagService.create(getResourceKey(), id, version)
            ));
        }
        return withVersion(builder, body);
    }

    /**
     * Enforces the canonical record-version precondition for a business action.
     * Controllers call this after resolving the item and before changing domain state.
     */
    protected void requireMatchingResourceVersion(ID id, String ifMatch) {
        if (resourceVersionEtagService == null) {
            throw new IllegalStateException(
                    "Resource version ETag support is not configured. Set praxis.resource-version.etag.secret."
            );
        }
        OptionalLong version = getService().getResourceVersion(id);
        if (version.isEmpty()) {
            throw new IllegalStateException(
                    "Resource " + getResourceKey() + " does not expose a persisted record version."
            );
        }
        ResourceVersionPreconditions.requireMatch(resourceVersionEtagService, ifMatch, getResourceKey(), id, version.getAsLong());
    }

    protected <T> ResponseEntity<T> withOptionSourceVersion(
            ResponseEntity.BodyBuilder builder,
            String sourceKey,
            T body
    ) {
        getService().getOptionSourceDatasetVersion(sourceKey).ifPresent(v -> builder.header(HDR, v));
        return builder.body(body);
    }

    protected Links hateoasOrNull(Links links) {
        return isHateoasEnabled() ? links : null;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    protected <T> ResponseEntity<RestApiResponse<T>> successEnvelope(
            ResponseEntity.BodyBuilder builder,
            Object data,
            Links links
    ) {
        return (ResponseEntity) withVersion(builder, RestApiResponse.success(data, hateoasOrNull(links)));
    }

    private boolean isHateoasEnabled() {
        String configured = environment != null
                ? environment.getProperty("praxis.hateoas.enabled", "true")
                : "true";
        return Boolean.parseBoolean(configured);
    }

    private boolean hasWorkflowActions(ActionScope scope) {
        String resourceKey = getResourceKeyOrNull();
        if (!StringUtils.hasText(resourceKey) || actionDefinitionRegistry == null) {
            return false;
        }
        return actionDefinitionRegistry.findByResourceKey(resourceKey).stream()
                .anyMatch(definition -> definition.scope() == scope);
    }

    private String resolveSchemaMethodPath(String methodPath) {
        String normalizedBasePath = normalizePath(getBasePath());
        String trimmedMethodPath = methodPath.trim();
        if ("/".equals(trimmedMethodPath)) {
            return normalizedBasePath;
        }

        String combined = trimmedMethodPath.startsWith("/")
                ? normalizedBasePath + trimmedMethodPath
                : normalizedBasePath + "/" + trimmedMethodPath;
        return normalizePath(combined);
    }

    private String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }

        String normalized = path.trim().replaceAll("/+", "/");
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        if (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String extractBasePath(String[] values, String[] paths) {
        if (values != null && values.length > 0) {
            return values[0];
        }
        if (paths != null && paths.length > 0) {
            return paths[0];
        }
        return null;
    }
}
