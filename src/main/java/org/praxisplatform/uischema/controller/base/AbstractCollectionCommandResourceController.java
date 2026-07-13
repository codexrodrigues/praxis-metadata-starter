package org.praxisplatform.uischema.controller.base;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.annotation.PostConstruct;
import org.praxisplatform.uischema.action.ActionCatalogResponse;
import org.praxisplatform.uischema.action.ActionCatalogService;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.capability.AvailabilityDecision;
import org.praxisplatform.uischema.capability.CapabilityService;
import org.praxisplatform.uischema.capability.CapabilitySnapshot;
import org.praxisplatform.uischema.capability.ResourceOperationAvailabilityContext;
import org.praxisplatform.uischema.command.GovernedResourceCommandExecutor;
import org.praxisplatform.uischema.command.ResourceCommandEvidenceSanitizer;
import org.praxisplatform.uischema.command.ResourceCommandExecutionProvider;
import org.praxisplatform.uischema.command.ResourceCommandExecutionRequest;
import org.praxisplatform.uischema.command.ResourceCommandHttpResponseAdapter;
import org.praxisplatform.uischema.command.ResourceCommandResponsePolicy;
import org.praxisplatform.uischema.command.ResourceCommandScope;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.env.Environment;
import org.springframework.hateoas.Link;
import org.springframework.hateoas.Links;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

/**
 * Base canonica para recursos que publicam somente comandos no escopo da colecao.
 *
 * <p>Use esta base quando o recurso representa uma intencao ou avaliacao de negocio que ainda
 * nao possui colecao consultavel nem ciclo de persistencia. Ela publica discovery contextual em
 * {@code GET /actions} e {@code GET /capabilities} e executa comandos pelo mesmo boundary
 * governado usado pelos controllers resource-oriented, sem inventar endpoints de query, filtros
 * ou um armazenamento ficticio.</p>
 *
 * <p>A classe deliberadamente nao oferece comandos por item. Quando o dominio passar a possuir
 * identidade persistida e leitura por registro, o controller deve migrar para
 * {@link AbstractResourceController} ou outra base resource-oriented apropriada.</p>
 */
public abstract class AbstractCollectionCommandResourceController {

    private static final String SCHEMAS_FILTERED_PATH = "/schemas/filtered";
    private static final String MISSING_BASE_PATH = "/__unconfigured-resource__";

    @Value("${server.servlet.contextPath:}")
    private String contextPath;

    @Autowired(required = false)
    private Environment environment;

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
        if (!StringUtils.hasText(detectedBasePath)) {
            detectedBasePath = MISSING_BASE_PATH;
        }
    }

    /**
     * Retorna o catalogo de actions collection-level declarado pelo recurso.
     */
    @GetMapping("/actions")
    @Operation(
            summary = "Descobrir workflow actions contextuais da colecao",
            description = "Lista os comandos de negocio collection-level publicados pelo recurso."
    )
    public ResponseEntity<ActionCatalogResponse> getCollectionActions() {
        if (actionCatalogService == null) {
            throw new IllegalStateException("ActionCatalogService is not configured for contextual discovery.");
        }
        return ResponseEntity.ok(actionCatalogService.findCollectionActions(getResourceKey()));
    }

    /**
     * Retorna o snapshot agregado de capabilities da colecao, sem declarar operacoes CRUD.
     */
    @GetMapping("/capabilities")
    @Operation(
            summary = "Descobrir capabilities unificadas da colecao",
            description = "Agrega disponibilidade e actions do recurso sem pressupor uma superficie de consulta ou persistencia."
    )
    public ResponseEntity<CapabilitySnapshot> getCollectionCapabilities() {
        if (capabilityService == null) {
            throw new IllegalStateException("CapabilityService is not configured for contextual discovery.");
        }
        return ResponseEntity.ok(capabilityService.collectionCapabilities(getResourceKey(), getBasePath()));
    }

    /**
     * Executa um comando collection-level pelo boundary governado da plataforma.
     */
    protected ResponseEntity<?> executeCollectionCommand(
            String commandId,
            Object payload,
            ResourceCommandResponsePolicy responsePolicy,
            ResourceCommandExecutionProvider provider
    ) {
        return executeCollectionCommand(commandId, payload, responsePolicy, provider, Map.of());
    }

    /**
     * Executa um comando collection-level incluindo somente metadata publica e sanitizavel.
     */
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
        GovernedResourceCommandExecutor executor = new GovernedResourceCommandExecutor(
                provider,
                this::evaluateAvailability,
                ResourceCommandEvidenceSanitizer.defaults()
        );
        return new ResourceCommandHttpResponseAdapter().toResponse(
                executor.execute(request),
                hateoasOrNull(commandLinks(commandId))
        );
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
                "Collection command discovery requires @ApiResource(resourceKey=...) on " + getClass().getName()
        );
    }

    private AvailabilityDecision evaluateAvailability(ResourceOperationAvailabilityContext context) {
        if (context == null || capabilityService == null || !StringUtils.hasText(context.resourceKey())) {
            return AvailabilityDecision.allowAll();
        }
        return capabilityService.collectionOperationAvailability(
                context.resourceKey(),
                context.resourcePath(),
                context.operationId()
        );
    }

    private Links commandLinks(String commandId) {
        String operationPath = normalizePath(getBasePath() + "/actions/" + commandId);
        return Links.of(
                Link.of(resourcePath("actions"), "actions"),
                Link.of(resourcePath("capabilities"), "capabilities"),
                schemaLink(operationPath, "request"),
                schemaLink(operationPath, "response")
        );
    }

    private Link schemaLink(String operationPath, String schemaType) {
        String href = UriComponentsBuilder.fromPath(normalizedContextPath() + SCHEMAS_FILTERED_PATH)
                .queryParam("path", operationPath)
                .queryParam("operation", "post")
                .queryParam("schemaType", schemaType)
                .build()
                .toUriString();
        return Link.of(href, "schema");
    }

    private String resourcePath(String segment) {
        return normalizePath(normalizedContextPath() + getBasePath() + "/" + segment);
    }

    private String normalizedContextPath() {
        return StringUtils.hasText(contextPath) ? normalizePath(contextPath) : "";
    }

    private Links hateoasOrNull(Links links) {
        String configured = environment == null
                ? "true"
                : environment.getProperty("praxis.hateoas.enabled", "true");
        return Boolean.parseBoolean(configured) ? links : null;
    }

    private String extractBasePath(String[] values, String[] paths) {
        if (values != null && values.length > 0 && StringUtils.hasText(values[0])) {
            return normalizePath(values[0]);
        }
        if (paths != null && paths.length > 0 && StringUtils.hasText(paths[0])) {
            return normalizePath(paths[0]);
        }
        return null;
    }

    private String normalizePath(String path) {
        if (!StringUtils.hasText(path)) {
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
}
