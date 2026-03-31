package org.praxisplatform.uischema.controller.base;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.annotation.PostConstruct;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.capability.CapabilityService;
import org.praxisplatform.uischema.capability.CapabilitySnapshot;
import org.praxisplatform.uischema.dto.CursorPage;
import org.praxisplatform.uischema.dto.LocateResponse;
import org.praxisplatform.uischema.dto.OptionDTO;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.rest.response.RestApiResponse;
import org.praxisplatform.uischema.service.base.BaseResourceQueryService;
import org.praxisplatform.uischema.stats.dto.DistributionStatsRequest;
import org.praxisplatform.uischema.stats.dto.DistributionStatsResponse;
import org.praxisplatform.uischema.stats.dto.GroupByStatsRequest;
import org.praxisplatform.uischema.stats.dto.GroupByStatsResponse;
import org.praxisplatform.uischema.stats.dto.TimeSeriesStatsRequest;
import org.praxisplatform.uischema.stats.dto.TimeSeriesStatsResponse;
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
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.env.Environment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.Link;
import org.springframework.hateoas.Links;
import org.springframework.hateoas.server.mvc.WebMvcLinkBuilder;
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

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.OptionalLong;

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
 */
public abstract class AbstractResourceQueryController<ResponseDTO, ID, FD extends GenericFilterDTO> {

    private static final Logger logger = LoggerFactory.getLogger(AbstractResourceQueryController.class);
    private static final String SCHEMAS_PATH = "/schemas";
    private static final String SCHEMAS_FILTERED_PATH = "/schemas/filtered";
    private static final String HDR = "X-Data-Version";
    private static final String MISSING_BASE_PATH = "/__unconfigured-resource__";

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

    @Autowired(required = false)
    private SurfaceCatalogService surfaceCatalogService;

    @Autowired(required = false)
    private ActionCatalogService actionCatalogService;

    @Autowired(required = false)
    private CapabilityService capabilityService;

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
        ApiResource apiResource = AnnotationUtils.findAnnotation(getClass(), ApiResource.class);
        if (apiResource != null && StringUtils.hasText(apiResource.resourceKey())) {
            return apiResource.resourceKey().trim();
        }
        throw new IllegalStateException(
                "Contextual surface discovery requires @ApiResource(resourceKey=...) on " + getClass().getName()
        );
    }

    protected EntityModel<ResponseDTO> toEntityModel(ResponseDTO dto) {
        if (!isHateoasEnabled()) {
            return EntityModel.of(dto);
        }

        ID id = getResponseId(dto);
        List<Link> links = new ArrayList<>();
        links.add(linkToSelf(id));
        links.addAll(buildEntityActionLinks(id));
        return EntityModel.of(dto, links);
    }

    protected List<Link> buildItemActionLinks(ID id) {
        return List.of();
    }

    protected List<Link> buildEntityActionLinks(ID id) {
        return List.of();
    }

    protected List<Link> buildCollectionActionLinks() {
        return List.of();
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
        Pageable pageable = PageableBuilder.from(page, size, sort, getService().getDefaultSort());
        Page<ResponseDTO> result = getService().filter(filterDTO, pageable, includeIds);
        Page<EntityModel<ResponseDTO>> entityModels = result.map(this::toEntityModel);

        List<Link> links = new ArrayList<>();
        links.add(linkToAll());
        links.add(linkToUiSchema("/filter", "post", "request"));
        links.add(linkToUiSchema("/filter", "post", "response"));
        links.addAll(buildCollectionActionLinks());

        return withVersion(ResponseEntity.ok(), RestApiResponse.success(entityModels, hateoasOrNull(Links.of(links))));
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
        Sort sortObj = SortBuilder.from(sort, getService().getDefaultSort());

        CursorPage<ResponseDTO> result;
        try {
            result = getService().filterByCursor(filterDTO, sortObj, after, before, size);
        } catch (UnsupportedOperationException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "Not implemented.");
        }

        CursorPage<EntityModel<ResponseDTO>> mapped = new CursorPage<>(
                result.content().stream().map(this::toEntityModel).toList(),
                result.next(),
                result.prev(),
                result.size()
        );

        List<Link> links = new ArrayList<>();
        links.add(linkToAll());
        links.add(linkToUiSchema("/filter/cursor", "post", "request"));
        links.add(linkToUiSchema("/filter/cursor", "post", "response"));
        links.addAll(buildCollectionActionLinks());

        return withVersion(ResponseEntity.ok(), RestApiResponse.success(mapped, hateoasOrNull(Links.of(links))));
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
        Sort sortObj = SortBuilder.from(sort, getService().getDefaultSort());
        OptionalLong position = getService().locate(filterDTO, sortObj, id);
        if (position.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "Not implemented.");
        }

        long index = position.getAsLong();
        long page = size > 0 ? index / size : 0;
        return withVersion(ResponseEntity.ok(), new LocateResponse(index, page));
    }

    @PostMapping("/stats/group-by")
    @Operation(summary = "Group-by stats sobre o conjunto filtrado")
    public ResponseEntity<RestApiResponse<GroupByStatsResponse>> groupByStats(
            @RequestBody GroupByStatsRequest<FD> request
    ) {
        try {
            GroupByStatsResponse result = getService().groupByStats(request);
            Links links = Links.of(
                    linkToFilter(),
                    linkToUiSchema("/stats/group-by", "post", "request"),
                    linkToUiSchema("/stats/group-by", "post", "response")
            );
            return withVersion(ResponseEntity.ok(), RestApiResponse.success(result, hateoasOrNull(links)));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (UnsupportedOperationException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "Not implemented.");
        }
    }

    @PostMapping("/stats/timeseries")
    @Operation(summary = "Time-series stats sobre o conjunto filtrado")
    public ResponseEntity<RestApiResponse<TimeSeriesStatsResponse>> timeSeriesStats(
            @RequestBody TimeSeriesStatsRequest<FD> request
    ) {
        try {
            TimeSeriesStatsResponse result = getService().timeSeriesStats(request);
            Links links = Links.of(
                    linkToFilter(),
                    linkToUiSchema("/stats/timeseries", "post", "request"),
                    linkToUiSchema("/stats/timeseries", "post", "response")
            );
            return withVersion(ResponseEntity.ok(), RestApiResponse.success(result, hateoasOrNull(links)));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (UnsupportedOperationException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "Not implemented.");
        }
    }

    @PostMapping("/stats/distribution")
    @Operation(summary = "Distribution stats sobre o conjunto filtrado")
    public ResponseEntity<RestApiResponse<DistributionStatsResponse>> distributionStats(
            @RequestBody DistributionStatsRequest<FD> request
    ) {
        try {
            DistributionStatsResponse result = getService().distributionStats(request);
            Links links = Links.of(
                    linkToAll(),
                    linkToUiSchema("/stats/distribution", "post", "request"),
                    linkToUiSchema("/stats/distribution", "post", "response")
            );
            return withVersion(ResponseEntity.ok(), RestApiResponse.success(result, hateoasOrNull(links)));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (UnsupportedOperationException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "Not implemented.");
        }
    }

    @GetMapping("/all")
    @Operation(summary = "Listar todos os registros")
    public ResponseEntity<RestApiResponse<List<EntityModel<ResponseDTO>>>> getAll() {
        List<EntityModel<ResponseDTO>> entityModels = getService().findAll().stream()
                .map(this::toEntityModel)
                .toList();

        List<Link> links = new ArrayList<>();
        links.add(linkToFilter());
        links.add(linkToFilterCursor());
        links.add(linkToUiSchema("/all", "get", "response"));
        links.addAll(buildCollectionActionLinks());

        return withVersion(ResponseEntity.ok(), RestApiResponse.success(entityModels, hateoasOrNull(Links.of(links))));
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
        Pageable pageable = PageableBuilder.from(page, size, sort, getService().getDefaultSort());
        return withVersion(ResponseEntity.ok(), getService().filterOptions(filterDTO, pageable));
    }

    @PostMapping("/option-sources/{sourceKey}/options/filter")
    @Operation(summary = "Listar opcoes filtradas por fonte derivada")
    public ResponseEntity<Page<OptionDTO<Object>>> filterOptionSourceOptions(
            @PathVariable String sourceKey,
            @RequestBody FD filterDTO,
            @RequestParam(name = "search", required = false) String search,
            @RequestParam(name = "includeIds", required = false) List<String> includeIds,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam MultiValueMap<String, String> queryParams
    ) {
        if (size > paginationMaxSize) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Maximum page size exceeded: " + paginationMaxSize);
        }

        List<String> sort = queryParams.get("sort");
        Pageable pageable = PageableBuilder.from(page, size, sort, getService().getDefaultSort());
        try {
            return withVersion(
                    ResponseEntity.ok(),
                    getService().filterOptionSourceOptions(
                            sourceKey,
                            filterDTO,
                            search,
                            pageable,
                            includeIds == null ? List.of() : List.copyOf(includeIds)
                    )
            );
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        } catch (UnsupportedOperationException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, ex.getMessage(), ex);
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
    public ResponseEntity<List<OptionDTO<Object>>> getOptionSourceOptionsByIds(
            @PathVariable String sourceKey,
            @RequestParam(name = "ids", required = false) List<String> ids
    ) {
        if (ids == null || ids.isEmpty()) {
            return withVersion(ResponseEntity.ok(), List.of());
        }
        if (ids.size() > byIdsMax) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Maximum number of IDs exceeded: " + byIdsMax);
        }
        try {
            return withVersion(ResponseEntity.ok(), getService().byIdsOptionSourceOptions(sourceKey, List.copyOf(ids)));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        } catch (UnsupportedOperationException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, ex.getMessage(), ex);
        }
    }

    @GetMapping("/{id}")
    @Operation(summary = "Buscar registro por ID")
    public ResponseEntity<RestApiResponse<ResponseDTO>> getById(@PathVariable ID id) {
        ResponseDTO dto = getService().findById(id);

        List<Link> linkList = new ArrayList<>();
        linkList.add(linkToSelf(id));
        linkList.add(linkToAll());
        linkList.add(linkToFilter());
        linkList.add(linkToFilterCursor());
        linkList.addAll(buildItemActionLinks(id));
        linkList.add(linkToUiSchema("/{id}", "get", "response"));

        return withVersion(
                ResponseEntity.ok(),
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
        return withVersion(ResponseEntity.ok(), capabilityService.collectionCapabilities(getResourceKey(), getBasePath()));
    }

    @GetMapping("/{id}/capabilities")
    @Operation(summary = "Descobrir capabilities unificadas do registro")
    public ResponseEntity<CapabilitySnapshot> getItemCapabilities(@PathVariable ID id) {
        if (capabilityService == null) {
            throw new IllegalStateException("CapabilityService is not configured for contextual discovery.");
        }
        getService().findById(id);
        return withVersion(ResponseEntity.ok(), capabilityService.itemCapabilities(getResourceKey(), getBasePath(), id));
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
        return WebMvcLinkBuilder.linkTo(
                WebMvcLinkBuilder.methodOn(getControllerClass()).getById(id)
        ).withSelfRel();
    }

    protected Link linkToAll() {
        return WebMvcLinkBuilder.linkTo(
                WebMvcLinkBuilder.methodOn(getControllerClass()).getAll()
        ).withRel("all");
    }

    protected Link linkToFilter() {
        return WebMvcLinkBuilder.linkTo(
                WebMvcLinkBuilder.methodOn(getControllerClass()).filter(null, 0, 0, null, null)
        ).withRel("filter");
    }

    protected Link linkToFilterCursor() {
        return WebMvcLinkBuilder.linkTo(
                WebMvcLinkBuilder.methodOn(getControllerClass()).filterByCursor(null, null, null, 0, null)
        ).withRel("filter-cursor");
    }

    protected Link linkToDocs() {
        String docsUrl = String.format("%s%s", openApiBasePath, getBasePath());
        return Link.of(docsUrl, "docs");
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

    protected Links hateoasOrNull(Links links) {
        return isHateoasEnabled() ? links : null;
    }

    private boolean isHateoasEnabled() {
        String configured = environment != null
                ? environment.getProperty("praxis.hateoas.enabled", "true")
                : "true";
        return Boolean.parseBoolean(configured);
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
