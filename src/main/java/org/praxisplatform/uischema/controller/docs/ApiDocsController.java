package org.praxisplatform.uischema.controller.docs;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.praxisplatform.uischema.FieldConfigProperties;
import org.praxisplatform.uischema.FieldControlType;
import org.praxisplatform.uischema.capability.CanonicalCapabilityResolver;
import org.praxisplatform.uischema.determination.ReactiveDeterminationMetadataCompiler;
import org.praxisplatform.uischema.openapi.CanonicalOperationRef;
import org.praxisplatform.uischema.openapi.CanonicalOperationResolver;
import org.praxisplatform.uischema.openapi.OpenApiDocumentService;
import org.praxisplatform.uischema.options.OptionSourceDescriptor;
import org.praxisplatform.uischema.options.OptionSourceRegistry;
import org.praxisplatform.uischema.schema.CanonicalSchemaRef;
import org.praxisplatform.uischema.schema.FilteredSchemaProjection;
import org.praxisplatform.uischema.schema.ApiResourceIdentityResolver;
import org.praxisplatform.uischema.schema.SchemaReferenceResolver;
import org.praxisplatform.uischema.util.OpenApiUiUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriUtils;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.Map.Entry;

/**
 * Expoe o endpoint canonico {@code /schemas/filtered}.
 *
 * <p>
 * Esta e a superficie estrutural central do starter para consumo metadata-driven. A partir de
 * {@code path + operation + schemaType}, o controller resolve a operacao canonica, delega a
 * leitura do documento OpenAPI ao servico apropriado e devolve apenas o fragmento estrutural
 * relevante, enriquecido com metadados {@code x-ui}.
 * </p>
 *
 * <p>
 * A classe nao concentra mais logica de grupo, cache de documento ou hashing estrutural. Essas
 * responsabilidades pertencem a {@link CanonicalOperationResolver},
 * {@link OpenApiDocumentService} e {@link SchemaReferenceResolver}. O papel deste controller agora
 * e orquestrar a resolucao canonica, selecionar o schema de request/response e aplicar os
 * enriquecimentos finais visiveis para consumidores do contrato.
 * </p>
 *
 * <p>
 * Em termos de plataforma, esta e a superficie estrutural de verdade. Catalogos documentais,
 * surfaces, actions e capabilities podem referenciar schemas, mas nao devem substituir o payload
 * produzido por este endpoint como fonte canonica do shape filtrado.
 * </p>
 */
@RestController
@RequestMapping("/schemas/filtered")
public class ApiDocsController {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiDocsController.class);

    // ------------------------------------------------------------------------
    // Base Path do OpenAPI
    // ------------------------------------------------------------------------
    // Constantes para chaves do JSON
    private static final String PATHS = "paths";
    private static final String COMPONENTS = "components";
    private static final String SCHEMAS = "schemas";
    private static final String X_UI = "x-ui";
    private static final String PROPERTIES = "properties";
    private static final String REF = "$ref";
    private static final String ITEMS = "items";
    private static final String OPERATION_EXAMPLES = "operationExamples";
    private static final String REACTIVE_DETERMINATIONS = "reactiveDeterminations";
    private static final String LEGACY_FORM_EFFECTS = "formEffects";
    private static final Set<String> DOCUMENTATION_X_UI_KEYS = Set.of(OPERATION_EXAMPLES);
    private static final Set<String> OPTION_SOURCE_PUBLIC_KEYS = Set.of(
            "key",
            "type",
            "resourcePath",
            "filterField",
            "propertyPath",
            "labelPropertyPath",
            "valuePropertyPath",
            "dependsOn",
            "entityKey",
            "codePropertyPath",
            "descriptionPropertyPaths",
            "statusPropertyPath",
            "disabledPropertyPath",
            "disabledReasonPropertyPath",
            "searchPropertyPaths",
            "dependencyFilterMap",
            "selectionPolicy",
            "capabilities",
            "detail",
            "create",
            "display",
            "filtering",
            "excludeSelfField",
            "searchMode",
            "pageSize",
            "includeIds",
            "cachePolicy",
            "filterEndpoint",
            "byIdsEndpoint",
            "selectedReloadPolicy",
            "invalidSortPolicy"
    );
    private static final Map<String, Set<String>> OPTION_SOURCE_PUBLIC_NESTED_KEYS = Map.of(
            "selectionPolicy", Set.of(
                    "selectablePropertyPath",
                    "statusPropertyPath",
                    "allowedStatuses",
                    "blockedStatuses",
                    "allowRetainInvalidExistingValue",
                    "disabledReasonTemplate",
                    "validationMessageTemplate"
            ),
            "capabilities", Set.of(
                    "filter",
                    "byIds",
                    "detail",
                    "create",
                    "edit",
                    "navigateToDetail",
                    "multiSelect",
                    "recent",
                    "favorites",
                    "auditSnapshot"
            ),
            "detail", Set.of(
                    "hrefTemplate",
                    "routeTemplate",
                    "openDetailMode",
                    "kind",
                    "surfaceId",
                    "presentation",
                    "preferredWidget",
                    "mode"
            ),
            "create", Set.of(
                    "hrefTemplate",
                    "routeTemplate",
                    "openMode"
            ),
            "display", Set.of(
                    "preset",
                    "usage",
                    "density",
                    "selectedLayout",
                    "resultLayout",
                    "primaryPropertyPath",
                    "fields",
                    "secondaryPropertyPaths",
                    "badgePropertyPaths",
                    "avatarPropertyPath",
                    "showAvatar",
                    "showCode",
                    "showDescription",
                    "showStatus",
                    "showBadges",
                    "showDisabledReason",
                    "showResultCount",
                    "statusLabelMap",
                    "statusToneMap",
                    "badgeKeys",
                    "maxVisibleBadges",
                    "detailActionLabel",
                    "changeActionLabel",
                    "copyCodeActionLabel",
                    "copyIdActionLabel",
                    "createActionLabel",
                    "clearActionLabel",
                    "actions"
            ),
            "filtering", Set.of(
                    "availableFilters",
                    "defaultFilters",
                    "sortOptions",
                    "defaultSort",
                    "quickFilterFields",
                    "searchPlaceholder",
                    "searchStrategies"
            )
    );

    @Autowired(required = false)
    private ApiResourceIdentityResolver apiResourceIdentityResolver;
    private static final Map<String, Set<String>> OPTION_SOURCE_PUBLIC_DEEP_KEYS = Map.of(
            "display.actions", Set.of(
                    "showDetail",
                    "showChange",
                    "showCopyCode",
                    "showCopyId",
                    "showCreate",
                    "showClear"
            ),
            "display.fields", Set.of(
                    "key",
                    "propertyPath",
                    "label",
                    "icon",
                    "presentation",
                    "tone",
                    "format"
            ),
            "filtering.availableFilters", Set.of(
                    "field",
                    "label",
                    "type",
                    "operators",
                    "defaultOperator",
                    "optionsSource",
                    "required",
                    "hidden"
            ),
            "filtering.sortOptions", Set.of(
                    "key",
                    "field",
                    "direction",
                    "label"
            ),
            "filtering.searchStrategies", Set.of(
                    "key", "kind", "minSearchChars", "inputFormat"
            )
    );
    private static final Set<String> OPTION_SOURCE_PRIVATE_KEYS = Set.of(
            "attributes",
            "bindParameters",
            "context",
            "function",
            "hostContext",
            "package",
            "providerConfig",
            "rawEndpoint",
            "sql",
            "tenant",
            "user"
    );

    // Constantes para valores padrao
    private static final String DEFAULT_OPERATION = "get";

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OpenApiDocsSupport openApiDocsSupport;

    @Autowired
    private OpenApiDocumentService openApiDocumentService;

    @Autowired
    private CanonicalOperationResolver canonicalOperationResolver;

    @Autowired
    private SchemaReferenceResolver schemaReferenceResolver;

    @Autowired
    private CanonicalCapabilityResolver canonicalCapabilityResolver;

    @Autowired
    private ObjectProvider<OptionSourceRegistry> optionSourceRegistryProvider;

    @Autowired(required = false)
    private ReactiveDeterminationMetadataCompiler reactiveDeterminationMetadataCompiler;

    /**
     * Resolve e devolve o fragmento estrutural de schema para uma operacao OpenAPI concreta.
     *
     * <p>
     * O fluxo canonico desta operacao e: resolver {@code group + path + method}, carregar o
     * documento OpenAPI do grupo, localizar o schema de {@code request} ou {@code response},
     * aplicar enriquecimentos {@code x-ui} e emitir o payload com {@code schemaId}/{@code schemaUrl}
     * consistentes com a variante estrutural solicitada.
     * </p>
     *
     * <p>
     * Nesta lane, variacoes estruturais relevantes para o resultado incluem
     * {@code includeInternalSchemas}, {@code idField} e {@code readOnly}. Os parametros
     * {@code tenant} e {@code locale} continuam no boundary canonico, mas permanecem neutros para a
     * estrutura retornada.
     * </p>
     *
     * <p>
     * A operacao tambem respeita validacao por ETag via {@code If-None-Match}, emitindo a mesma
     * identidade estrutural observada por consumidores runtime e documentais.
     * </p>
     */
    @GetMapping
    public org.springframework.http.ResponseEntity<Map<String, Object>> getFilteredSchema(
            @RequestParam(required = false) String path,
            @RequestParam(required = false) String resourcePath,
            @RequestParam(required = false, defaultValue = DEFAULT_OPERATION) String operation,
            @RequestParam(required = false, defaultValue = "false") boolean includeInternalSchemas,
            @RequestParam(required = false, defaultValue = "response") String schemaType,
            @RequestParam(required = false) String idField,
            @RequestParam(required = false) Boolean readOnly,
            @org.springframework.web.bind.annotation.RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch,
            @org.springframework.web.bind.annotation.RequestHeader(value = "X-Tenant", required = false) String tenant,
            java.util.Locale locale) {

        String effectivePath = StringUtils.hasText(path) ? path : resourcePath;
        if (!StringUtils.hasText(effectivePath)) {
            throw new IllegalArgumentException("Parameter 'path' or 'resourcePath' is required.");
        }

        return openApiDocumentService.withSchemaCacheReadLock(() -> getFilteredSchemaWithStableCache(
                effectivePath, operation, includeInternalSchemas, schemaType, idField, readOnly, ifNoneMatch, tenant, locale));
    }

    private org.springframework.http.ResponseEntity<Map<String, Object>> getFilteredSchemaWithStableCache(
            String path, String operation, boolean includeInternalSchemas, String schemaType,
            String idField, Boolean readOnly, String ifNoneMatch, String tenant, java.util.Locale locale) {

        if (!"response".equalsIgnoreCase(schemaType) && !"request".equalsIgnoreCase(schemaType)) {
            throw new IllegalArgumentException("Parameter 'schemaType' must be 'response' or 'request'.");
        }

        String decodedPath = UriUtils.decode(path, StandardCharsets.UTF_8).trim();
        if (!decodedPath.startsWith("/")) {
            decodedPath = "/" + decodedPath;
        }

        // 1. Resolver grupo automaticamente baseado no path
        CanonicalOperationRef operationRef = canonicalOperationResolver.resolve(decodedPath, operation);
        String normalizedOperation = operationRef.method().toLowerCase(Locale.ROOT);
        String groupName = operationRef.group();
        LOGGER.info("Path '{}' -> grupo resolvido: '{}'", decodedPath, groupName);

        // 2. Obter documento especifico do cache
        JsonNode rootNode = openApiDocumentService.getDocumentForGroup(groupName);

        if (rootNode == null) {
            throw new IllegalStateException("Failed to retrieve the OpenAPI document for group: " + groupName);
        }

        String canonicalPath = openApiDocumentService.resolveDocumentPath(
                rootNode.path(PATHS),
                decodedPath,
                normalizedOperation
        );

        // Procura o caminho especificado no JSON
        JsonNode pathsNode = rootNode.path(PATHS).path(canonicalPath).path(normalizedOperation);

        if (pathsNode.isMissingNode()) {
            String alternatePath = decodedPath.startsWith("/api/")
                    ? decodedPath.substring(4)
                    : "/api" + decodedPath;

            CanonicalOperationRef alternateOperationRef = canonicalOperationResolver.resolve(alternatePath, operation);
            String alternateGroup = alternateOperationRef.group();
            JsonNode alternateRootNode = (alternateGroup != null && !alternateGroup.equals(groupName))
                    ? openApiDocumentService.getDocumentForGroup(alternateGroup)
                    : rootNode;

            if (alternateRootNode != null) {
                String alternateCanonicalPath = openApiDocumentService.resolveDocumentPath(
                        alternateRootNode.path(PATHS),
                        alternatePath,
                        normalizedOperation
                );
                JsonNode alternatePathsNode = alternateRootNode.path(PATHS).path(alternateCanonicalPath).path(normalizedOperation);
                if (!alternatePathsNode.isMissingNode()) {
                    rootNode = alternateRootNode;
                    canonicalPath = alternateCanonicalPath;
                    pathsNode = alternatePathsNode;
                }
            }
        }

        if (pathsNode.isMissingNode()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "The specified path or operation was not found in the documentation."
            );
        }

        LOGGER.info("Path and operation node retrieved successfully");

        FilteredSchemaProjection projection = new FilteredSchemaProjection(objectMapper);
        FilteredSchemaProjection.Selection selected = projection.select(rootNode, canonicalPath, normalizedOperation, schemaType);
        String schemaName = selected.schemaName();
        JsonNode schemasNode = selected.schema();
        JsonNode allSchemas = rootNode.path(COMPONENTS).path(SCHEMAS);

        LOGGER.info("Schema node retrieved successfully");

        JsonNode schemaNodeForResponse = schemasNode.isObject() ? schemasNode.deepCopy() : schemasNode;
        if ("request".equalsIgnoreCase(schemaType) && schemaNodeForResponse.isObject()) {
            materializeStatsFilterSchema(
                    (ObjectNode) schemaNodeForResponse,
                    rootNode,
                    canonicalPath
            );
        }
        // Se includeInternalSchemas for verdadeiro, substitui schemas internos em uma copia
        // profunda para nao contaminar o documento OpenAPI compartilhado em cache.
        if (includeInternalSchemas && schemaNodeForResponse.isObject()) {
            replaceInternalSchemas((ObjectNode) schemaNodeForResponse, allSchemas);
        }

        // Converte o esquema para um Map
        Map<String, Object> schemaMap = objectMapper.convertValue(schemaNodeForResponse, new TypeReference<Map<String, Object>>() { });
        normalizeEmptyObjectProperties(schemaMap);

        String basePath = deriveBasePathFrom(canonicalPath);

        // Copia os valores de xUiNode para o "x-ui" do objeto retornado
        JsonNode xUiNode = pathsNode.path(X_UI);
        Map<String, Object> xUiMap = objectMapper.convertValue(xUiNode, new TypeReference<Map<String, Object>>() { });
        if (xUiMap == null) {
            xUiMap = new java.util.HashMap<>();
        }
        // A operacao OpenAPI nao e uma superficie autoravel para callbacks, raw paths ou uma
        // segunda copia do contrato. Apenas o compiler canonico pode publicar determinacoes.
        xUiMap.remove(LEGACY_FORM_EFFECTS);
        xUiMap.remove(REACTIVE_DETERMINATIONS);
        Map<String, Object> operationExamples = resolveOperationExamples(pathsNode, xUiMap, schemaType);
        if (!operationExamples.isEmpty()) {
            xUiMap.put(OPERATION_EXAMPLES, operationExamples);
        }

        // Anotar x-ui.resource.idField para o frontend
        String resolvedIdField = resolveIdField(idField, schemaMap, rootNode, basePath, schemaType);
        Map<String, Boolean> caps = computeCapabilities(rootNode, basePath);
        boolean computedReadOnly = FilteredSchemaProjection.readOnly(readOnly, caps);
        @SuppressWarnings("unchecked")
        Map<String, Object> resourceMeta = (Map<String, Object>) xUiMap.get("resource");
        if (resourceMeta == null) {
            resourceMeta = new java.util.LinkedHashMap<>();
            xUiMap.put("resource", resourceMeta);
        }
        if (resolvedIdField != null && !resolvedIdField.isBlank()) {
            resourceMeta.put("idField", resolvedIdField);

            boolean valid = hasSchemaProperty(schemaMap, resolvedIdField);
            resourceMeta.put("idFieldValid", valid);
            if (!valid) {
                resourceMeta.put("idFieldMessage", "idField not found in schema properties");
                logMissingIdField(schemaType, resolvedIdField, schemaName);
            }

            resourceMeta.put("readOnly", computedReadOnly);
            resourceMeta.put("capabilities", caps);
        }

        // Record identity describes materialized resources. Request schemas model
        // filters and commands, and must not publish an identity their DTO cannot satisfy.
        if ("response".equalsIgnoreCase(schemaType) && apiResourceIdentityResolver != null) {
            Optional<Map<String, Object>> resolvedIdentity = apiResourceIdentityResolver.resolve(basePath);
            if (resolvedIdentity.isPresent()) {
                Map<String, Object> identity = resolvedIdentity.get();
                Map<String, Object> publishedIdentity = new java.util.LinkedHashMap<>(identity);
                java.util.List<String> invalidFields = identity.values().stream()
                        .flatMap(value -> value instanceof java.util.Collection<?> collection
                                ? collection.stream()
                                : java.util.stream.Stream.of(value))
                        .map(String::valueOf)
                        .filter(field -> !hasSchemaProperty(schemaMap, field))
                        .distinct()
                        .toList();
                publishedIdentity.put("valid", invalidFields.isEmpty());
                if (!invalidFields.isEmpty()) {
                    publishedIdentity.put("invalidFields", invalidFields);
                    publishedIdentity.put("message", "Resource identity references fields not found in schema properties");
                }
                resourceMeta.put("identity", publishedIdentity);
            }
        }

        enrichPropertyOptionSources(schemaMap, basePath);
        enrichArrayItemSchemasInline(schemaMap, allSchemas);
        enrichReferencedComponentSchemas(schemaMap, allSchemas);
        propagateArrayEnumOptionsRecursive(schemaMap, allSchemas);
        if (reactiveDeterminationMetadataCompiler != null) {
            String schemaOperationId = pathsNode.path("operationId").asText(null);
            CanonicalOperationRef exactSchemaOperation = new CanonicalOperationRef(
                    operationRef.group(),
                    schemaOperationId,
                    canonicalPath,
                    operationRef.method()
            );
            List<Map<String, Object>> determinations = reactiveDeterminationMetadataCompiler.compile(
                    exactSchemaOperation,
                    schemaType,
                    rootNode,
                    schemaNodeForResponse
            );
            if (!determinations.isEmpty()) {
                xUiMap.put(REACTIVE_DETERMINATIONS, determinations);
            }
        }
        schemaMap.put(X_UI, xUiMap);

        // 4) Canonicalize and hash the final payload (com cache por schemaId)
        CanonicalSchemaRef schemaRef = schemaReferenceResolver.resolve(
                canonicalPath,
                normalizedOperation,
                schemaType,
                includeInternalSchemas,
                tenant,
                locale,
                resolvedIdField,
                computedReadOnly
        );

        String schemaHash = openApiDocumentService.getOrComputeSchemaHash(
                schemaRef.schemaId(),
                () -> objectMapper.valueToTree(buildStructuralSchemaPayload(schemaMap))
        );
        String eTag = "\"" + schemaHash + "\""; // strong ETag

        // 5) Conditional request handling (If-None-Match)
        if (org.praxisplatform.uischema.http.IfNoneMatchUtils.matches(ifNoneMatch, eTag)) {
            return org.springframework.http.ResponseEntity
                    .status(org.springframework.http.HttpStatus.NOT_MODIFIED)
                    .eTag(eTag)
                    .header("X-Schema-Hash", schemaHash)
                    .header("Access-Control-Expose-Headers", "ETag,X-Schema-Hash")
                    .cacheControl(org.springframework.http.CacheControl.maxAge(0, java.util.concurrent.TimeUnit.SECONDS).cachePublic().mustRevalidate())
                    .varyBy("Accept-Encoding")
                    .build();
        }

        return org.springframework.http.ResponseEntity
                .ok()
                .eTag(eTag)
                .header("X-Schema-Hash", schemaHash)
                .header("Access-Control-Expose-Headers", "ETag,X-Schema-Hash")
                .cacheControl(org.springframework.http.CacheControl.maxAge(0, java.util.concurrent.TimeUnit.SECONDS).cachePublic().mustRevalidate())
                .varyBy("Accept-Encoding")
                .body(schemaMap);
    }

    // Convenience overload preserving 9-parameter signature (without resourcePath)
    public org.springframework.http.ResponseEntity<Map<String, Object>> getFilteredSchema(
            String path,
            String operation,
            boolean includeInternalSchemas,
            String schemaType,
            String idField,
            Boolean readOnly,
            String ifNoneMatch,
            String tenant,
            java.util.Locale locale) {
        return getFilteredSchema(path, null, operation, includeInternalSchemas, schemaType, idField, readOnly, ifNoneMatch, tenant, locale);
    }

    // Convenience overload used by unit tests and callers without idField param.
    public org.springframework.http.ResponseEntity<Map<String, Object>> getFilteredSchema(
            String path,
            String operation,
            boolean includeInternalSchemas,
            String schemaType,
            String ifNoneMatch,
            String tenant,
            java.util.Locale locale) {
        return getFilteredSchema(path, null, operation, includeInternalSchemas, schemaType, null, null, ifNoneMatch, tenant, locale);
    }

    /**
     * Resolve o {@code idField} final a ser exposto em {@code x-ui.resource.idField}.
     *
     * <p>
     * A prioridade atual e: parametro explicito da requisicao; identificador canonico do recurso
     * quando presente no schema materializado (ou em schemas de request); identidade propria da
     * resposta derivada via {@code id} ou {@code *Id}; e, por fim, fallback conservador.
     * </p>
     */
    private String resolveIdField(String requestedIdField,
                                  Map<String, Object> schemaMap,
                                  JsonNode rootNode,
                                  String basePath,
                                  String schemaType) {
        return new FilteredSchemaProjection(objectMapper).resolveIdField(requestedIdField, schemaMap, rootNode, basePath, schemaType);
    }

    /**
     * Deriva o path base do recurso a partir de um path de operacao.
     *
     * <p>
     * Remove sufixos conhecidos de CRUD, filtro, options e stats para permitir calculo de
     * capacidades e metadados em {@code x-ui.resource}.
     * </p>
     */
    private String deriveBasePathFrom(String fullPath) {
        return new FilteredSchemaProjection(objectMapper).deriveBasePathFrom(fullPath);
    }

    /**
     * Materializa no request de stats o schema concreto de filtro do mesmo recurso.
     *
     * <p>O Springdoc preserva o envelope de {@code GroupByStatsRequest<FD>} e
     * {@code TimeSeriesStatsRequest<FD>}, mas pode apagar o tipo parametrico {@code FD}, publicando
     * {@code filter} apenas como {@code object}. O endpoint irmao {@code /filter} continua contendo
     * o {@code FilterDTO} concreto. Esta etapa reutiliza essa evidencia OpenAPI canonica para que
     * {@code /schemas/filtered} nao perca a conformidade de campos nas operacoes analiticas.</p>
     */
    private void materializeStatsFilterSchema(
            ObjectNode statsRequestSchema,
            JsonNode rootNode,
            String canonicalPath
    ) {
        if (!isStatsOperationPath(canonicalPath)) {
            return;
        }

        JsonNode propertiesNode = statsRequestSchema.path(PROPERTIES);
        JsonNode currentFilterSchema = propertiesNode.path("filter");
        JsonNode allSchemas = rootNode.path(COMPONENTS).path(SCHEMAS);
        if (!(propertiesNode instanceof ObjectNode properties)
                || currentFilterSchema.isMissingNode()
                || hasDeclaredSchemaProperties(currentFilterSchema, allSchemas)) {
            return;
        }

        String filterPath = deriveBasePathFrom(canonicalPath) + "/filter";
        JsonNode filterOperation = rootNode.path(PATHS).path(filterPath).path("post");
        if (filterOperation.isMissingNode()) {
            return;
        }

        JsonNode filterRequestSchema = openApiDocsSupport.selectPreferredContentNode(
                filterOperation.path("requestBody").path("content")
        ).path("schema");
        JsonNode concreteFilterSchema = resolveRequestSchemaNode(
                filterRequestSchema,
                allSchemas
        );
        if (concreteFilterSchema == null || !concreteFilterSchema.isObject()) {
            return;
        }

        properties.set("filter", concreteFilterSchema.deepCopy());
        LOGGER.info(
                "Schema concreto de filtro materializado em request de stats '{}' a partir de '{}'",
                canonicalPath,
                filterPath
        );
    }

    private boolean isStatsOperationPath(String path) {
        return path != null && (
                path.endsWith("/stats/group-by")
                        || path.endsWith("/stats/timeseries")
                        || path.endsWith("/stats/distribution")
                        || path.endsWith("/stats/comparison")
        );
    }

    private JsonNode resolveRequestSchemaNode(JsonNode requestSchema, JsonNode allSchemas) {
        if (requestSchema == null || requestSchema.isMissingNode()) {
            return null;
        }
        if (requestSchema.has(REF)) {
            JsonNode referenced = allSchemas.path(extractSchemaNameFromRef(requestSchema.path(REF).asText()));
            return referenced.isMissingNode() ? null : referenced;
        }
        JsonNode extracted = tryExtractFilterSchemaFromInline(requestSchema, allSchemas);
        return extracted != null ? extracted : requestSchema;
    }

    private boolean hasDeclaredSchemaProperties(JsonNode schema, JsonNode allSchemas) {
        JsonNode candidate = schema;
        if (schema.has(REF)) {
            candidate = allSchemas.path(extractSchemaNameFromRef(schema.path(REF).asText()));
        }
        return candidate != null
                && !candidate.isMissingNode()
                && candidate.path(PROPERTIES).fieldNames().hasNext();
    }

    /**
     * Calcula capacidades canonicas do recurso a partir da presenca de operacoes no OpenAPI.
     *
     * <p>
     * O resultado alimenta {@code x-ui.resource.capabilities} e resume se o recurso expoe
     * operacoes como create, update, delete, filter, options e stats.
     *
     * <p>
     * {@code update} considera tanto {@code PUT/PATCH /{id}} quanto operacoes item-level de
     * manutencao parcial orientadas a recurso, por exemplo {@code PATCH /{id}/profile}.
     * </p>
     */
    private Map<String, Boolean> computeCapabilities(JsonNode rootNode, String basePath) {
        return canonicalCapabilityResolver.resolve(rootNode, basePath);
    }

    @SuppressWarnings("unchecked")
    private void enrichPropertyOptionSources(Map<String, Object> schemaMap, String basePath) {
        OptionSourceRegistry optionSourceRegistry = optionSourceRegistryProvider != null
                ? optionSourceRegistryProvider.getIfAvailable()
                : null;
        Object rawProperties = schemaMap.get(PROPERTIES);
        if (!(rawProperties instanceof Map<?, ?> properties)) {
            return;
        }
        for (Map.Entry<?, ?> entry : properties.entrySet()) {
            if (!(entry.getKey() instanceof String fieldName) || !(entry.getValue() instanceof Map<?, ?> rawFieldSchema)) {
                continue;
            }
            Map<String, Object> fieldSchema = (Map<String, Object>) rawFieldSchema;
            OptionSourceDescriptor descriptor = optionSourceRegistry == null || basePath == null || basePath.isBlank()
                    ? null
                    : resolveFieldOptionSource(optionSourceRegistry, basePath, fieldName, fieldSchema);
            Map<String, Object> fieldXUi = existingNestedMap(fieldSchema, X_UI);
            Map<String, Object> optionSourceMeta = fieldXUi == null ? null : existingNestedMap(fieldXUi, "optionSource");
            if (descriptor == null && optionSourceMeta == null) {
                continue;
            }
            fieldXUi = ensureNestedMap(fieldSchema, X_UI);
            optionSourceMeta = ensureNestedMap(fieldXUi, "optionSource");
            Map<String, Object> mergedOptionSourceMeta = descriptor == null
                    ? new LinkedHashMap<>()
                    : new LinkedHashMap<>(descriptor.toMetadataMap());
            mergeMissingPublicOptionSourceMetadata(mergedOptionSourceMeta, optionSourceMeta);
            Map<String, Object> sanitized = sanitizeOptionSourceMetadata(mergedOptionSourceMeta);
            if (sanitized.isEmpty()) {
                fieldXUi.remove("optionSource");
                continue;
            }
            optionSourceMeta.clear();
            optionSourceMeta.putAll(sanitized);
        }
    }

    @SuppressWarnings("unchecked")
    private void mergeMissingPublicOptionSourceMetadata(
            Map<String, Object> target,
            Map<String, Object> candidate
    ) {
        if (candidate == null || candidate.isEmpty()) {
            return;
        }
        candidate.forEach((key, value) -> {
            if (!OPTION_SOURCE_PUBLIC_KEYS.contains(key)) {
                return;
            }
            Set<String> nestedAllowedKeys = OPTION_SOURCE_PUBLIC_NESTED_KEYS.get(key);
            if (nestedAllowedKeys != null && value instanceof Map<?, ?> nestedCandidate) {
                Map<String, Object> sanitizedNested = sanitizeNestedOptionSourceMetadata(nestedCandidate, nestedAllowedKeys);
                Object existing = target.get(key);
                if (existing instanceof Map<?, ?> existingMap) {
                    Map<String, Object> mergedNested = new LinkedHashMap<>((Map<String, Object>) existingMap);
                    sanitizedNested.forEach(mergedNested::putIfAbsent);
                    target.put(key, mergedNested);
                } else if (!sanitizedNested.isEmpty()) {
                    target.putIfAbsent(key, sanitizedNested);
                }
                return;
            }
            target.putIfAbsent(key, value);
        });
    }

    private Map<String, Object> sanitizeOptionSourceMetadata(Map<String, Object> candidate) {
        Map<String, Object> sanitized = new LinkedHashMap<>();
        if (candidate == null || candidate.isEmpty()) {
            return sanitized;
        }
        candidate.forEach((key, value) -> {
            if (OPTION_SOURCE_PUBLIC_KEYS.contains(key) && !OPTION_SOURCE_PRIVATE_KEYS.contains(key)) {
                sanitized.put(key, sanitizeOptionSourceValue(key, value, OPTION_SOURCE_PUBLIC_NESTED_KEYS.get(key)));
            }
        });
        sanitized.values().removeIf(Objects::isNull);
        return sanitized;
    }

    private Map<String, Object> sanitizeNestedOptionSourceMetadata(
            Map<?, ?> candidate,
            Set<String> allowedKeys
    ) {
        Map<String, Object> sanitized = new LinkedHashMap<>();
        candidate.forEach((rawKey, value) -> {
            if (rawKey instanceof String key && allowedKeys.contains(key)) {
                sanitized.put(key, sanitizeOptionSourceValue(key, value, null));
            }
        });
        sanitized.values().removeIf(Objects::isNull);
        return sanitized;
    }

    private Object sanitizeOptionSourceValue(String path, Object value, Set<String> allowedMapKeys) {
        if (value instanceof Map<?, ?> mapValue) {
            Set<String> effectiveAllowedKeys = allowedMapKeys != null ? allowedMapKeys : OPTION_SOURCE_PUBLIC_DEEP_KEYS.get(path);
            if (effectiveAllowedKeys == null) {
                Map<String, Object> sanitized = new LinkedHashMap<>();
                mapValue.forEach((rawKey, nestedValue) -> {
                    if (rawKey instanceof String key && !OPTION_SOURCE_PRIVATE_KEYS.contains(key)) {
                        sanitized.put(key, sanitizeOptionSourceValue(path + "." + key, nestedValue, null));
                    }
                });
                sanitized.values().removeIf(Objects::isNull);
                return sanitized;
            }
            Map<String, Object> sanitized = new LinkedHashMap<>();
            mapValue.forEach((rawKey, nestedValue) -> {
                if (rawKey instanceof String key
                        && effectiveAllowedKeys.contains(key)
                        && !OPTION_SOURCE_PRIVATE_KEYS.contains(key)) {
                    sanitized.put(key, sanitizeOptionSourceValue(path + "." + key, nestedValue, null));
                }
            });
            sanitized.values().removeIf(Objects::isNull);
            return sanitized;
        }
        if (value instanceof List<?> listValue) {
            List<Object> sanitized = listValue.stream()
                    .map(item -> sanitizeOptionSourceValue(path, item, OPTION_SOURCE_PUBLIC_DEEP_KEYS.get(path)))
                    .filter(Objects::nonNull)
                    .toList();
            return sanitized;
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private OptionSourceDescriptor resolveFieldOptionSource(
            OptionSourceRegistry optionSourceRegistry,
            String basePath,
            String fieldName,
            Map<String, Object> fieldSchema
    ) {
        Object rawXUi = fieldSchema.get(X_UI);
        if (rawXUi instanceof Map<?, ?> xUi) {
            Object endpoint = ((Map<String, Object>) xUi).get(FieldConfigProperties.ENDPOINT.getValue());
            OptionSourceEndpointRef endpointRef = parseOptionSourceEndpoint(endpoint);
            if (endpointRef != null) {
                OptionSourceDescriptor descriptor = optionSourceRegistry
                        .resolveByResourcePathAndKey(endpointRef.resourcePath(), endpointRef.sourceKey())
                        .orElse(null);
                if (descriptor != null) {
                    return descriptor;
                }
            }
        }
        return optionSourceRegistry
                .resolveByResourcePathAndField(basePath, fieldName)
                .orElse(null);
    }

    private OptionSourceEndpointRef parseOptionSourceEndpoint(Object endpoint) {
        if (!(endpoint instanceof String rawEndpoint) || rawEndpoint.isBlank()) {
            return null;
        }
        String path = endpointPath(rawEndpoint);
        int marker = path.indexOf("/option-sources/");
        if (marker <= 0) {
            return null;
        }
        String resourcePath = normalizePath(path.substring(0, marker));
        String suffix = path.substring(marker + "/option-sources/".length());
        int nextSlash = suffix.indexOf('/');
        if (nextSlash <= 0) {
            return null;
        }
        String sourceKey = suffix.substring(0, nextSlash);
        String operationPath = suffix.substring(nextSlash);
        if (!operationPath.startsWith("/options/")) {
            return null;
        }
        return new OptionSourceEndpointRef(resourcePath, sourceKey);
    }

    private String endpointPath(String rawEndpoint) {
        String candidate = rawEndpoint.trim();
        int queryIndex = candidate.indexOf('?');
        if (queryIndex >= 0) {
            candidate = candidate.substring(0, queryIndex);
        }
        int hashIndex = candidate.indexOf('#');
        if (hashIndex >= 0) {
            candidate = candidate.substring(0, hashIndex);
        }
        try {
            URI uri = URI.create(candidate);
            if (uri.getPath() != null && !uri.getPath().isBlank()) {
                candidate = uri.getPath();
            }
        } catch (IllegalArgumentException ignored) {
            // Keep the literal path when the endpoint is not an absolute URI.
        }
        return normalizePath(candidate);
    }

    private String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        String normalized = path.trim();
        while (normalized.contains("//")) {
            normalized = normalized.replace("//", "/");
        }
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        if (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private record OptionSourceEndpointRef(String resourcePath, String sourceKey) {
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> ensureNestedMap(Map<String, Object> parent, String key) {
        Object existing = parent.get(key);
        if (existing instanceof Map<?, ?> existingMap) {
            return (Map<String, Object>) existingMap;
        }
        Map<String, Object> created = new LinkedHashMap<>();
        parent.put(key, created);
        return created;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> existingNestedMap(Map<String, Object> parent, String key) {
        Object existing = parent.get(key);
        return existing instanceof Map<?, ?> existingMap ? (Map<String, Object>) existingMap : null;
    }

    /**
     * Retorna {@code true} quando o schema ja contem a propriedade informada.
     */
    @SuppressWarnings("unchecked")
    private boolean hasSchemaProperty(Map<String, Object> schemaMap, String prop) {
        return new FilteredSchemaProjection(objectMapper).hasSchemaProperty(schemaMap, prop);
    }

    private void normalizeEmptyObjectProperties(Map<String, Object> schemaMap) {
        if (schemaMap == null || schemaMap.containsKey(PROPERTIES)) {
            return;
        }
        Object type = schemaMap.get("type");
        if ("object".equals(type)) {
            schemaMap.put(PROPERTIES, new LinkedHashMap<>());
        }
    }

    private void logMissingIdField(String schemaType, String resolvedIdField, String schemaName) {
        if ("request".equalsIgnoreCase(schemaType)) {
            LOGGER.debug(
                    "x-ui.resource.idField='{}' derivado do recurso canonico nao esta presente no request schema '{}'",
                    resolvedIdField,
                    schemaName
            );
            return;
        }
        if (isStatsAggregateResponseSchema(schemaName)) {
            LOGGER.debug(
                    "x-ui.resource.idField='{}' derivado do recurso canonico nao esta presente no schema agregado de stats '{}'",
                    resolvedIdField,
                    schemaName
            );
            return;
        }
        LOGGER.warn(
                "x-ui.resource.idField='{}' nao encontrado nas propriedades do schema '{}'",
                resolvedIdField,
                schemaName
        );
    }

    private boolean isStatsAggregateResponseSchema(String schemaName) {
        return schemaName != null && schemaName.endsWith("StatsResponse");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveOperationExamples(JsonNode operationNode, Map<String, Object> xUiMap, String schemaType) {
        Map<String, Object> derivedExamples = extractOperationExamples(operationNode, schemaType);
        Object explicitExamplesObj = xUiMap.get(OPERATION_EXAMPLES);
        if (!(explicitExamplesObj instanceof Map<?, ?> explicitExamplesMap)) {
            return derivedExamples;
        }

        Map<String, Object> explicitExamples = filterOperationExamplesBySchemaType((Map<String, Object>) explicitExamplesMap, schemaType);
        if (explicitExamples.isEmpty()) {
            return derivedExamples;
        }

        Map<String, Object> merged = new LinkedHashMap<>(derivedExamples);
        explicitExamples.forEach((side, value) -> {
            if (value instanceof Map<?, ?> explicitCollection) {
                Map<String, Object> mergedCollection = new LinkedHashMap<>();
                Object derivedCollectionObj = derivedExamples.get(side);
                if (derivedCollectionObj instanceof Map<?, ?> derivedCollection) {
                    mergedCollection.putAll((Map<String, Object>) derivedCollection);
                }
                mergedCollection.putAll((Map<String, Object>) explicitCollection);
                merged.put(side, mergedCollection);
            }
        });
        return merged;
    }

    private Map<String, Object> filterOperationExamplesBySchemaType(Map<String, Object> operationExamples, String schemaType) {
        Map<String, Object> filtered = new LinkedHashMap<>();
        if ("request".equalsIgnoreCase(schemaType) && operationExamples.containsKey("request")) {
            filtered.put("request", operationExamples.get("request"));
        }
        if ("response".equalsIgnoreCase(schemaType) && operationExamples.containsKey("response")) {
            filtered.put("response", operationExamples.get("response"));
        }
        return filtered;
    }

    @SuppressWarnings("unchecked")
    private void enrichArrayItemSchemasInline(Map<String, Object> schemaMap, JsonNode allSchemas) {
        if (schemaMap == null || allSchemas == null || allSchemas.isMissingNode() || !allSchemas.isObject()) {
            return;
        }

        enrichArrayItemSchemasInlineRecursive(schemaMap, allSchemas);
    }

    @SuppressWarnings("unchecked")
    private void enrichArrayItemSchemasInlineRecursive(Object schemaNode, JsonNode allSchemas) {
        enrichArrayItemSchemasInlineRecursive(schemaNode, allSchemas, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    @SuppressWarnings("unchecked")
    private void enrichArrayItemSchemasInlineRecursive(Object schemaNode, JsonNode allSchemas, Set<Object> visited) {
        if (schemaNode == null || !visited.add(schemaNode)) {
            return;
        }
        if (schemaNode instanceof Map<?, ?> rawSchemaNode) {
            Map<String, Object> typedSchemaNode = (Map<String, Object>) rawSchemaNode;
            enrichArrayItemSchemaInline(typedSchemaNode, allSchemas);
            typedSchemaNode.values().forEach(value -> enrichArrayItemSchemasInlineRecursive(value, allSchemas, visited));
            return;
        }

        if (schemaNode instanceof Collection<?> values) {
            values.forEach(value -> enrichArrayItemSchemasInlineRecursive(value, allSchemas, visited));
        }
    }

    @SuppressWarnings("unchecked")
    private void enrichArrayItemSchemaInline(Map<String, Object> propertySchema, JsonNode allSchemas) {
        Object rawXUi = propertySchema.get(X_UI);
        if (!(rawXUi instanceof Map<?, ?> xUi)) {
            return;
        }
        Object rawArray = xUi.get("array");
        if (!(rawArray instanceof Map<?, ?> array)) {
            return;
        }

        Map<String, Object> arrayConfig = (Map<String, Object>) array;
        if (arrayConfig.get("itemSchema") instanceof Map<?, ?>) {
            return;
        }

        Object rawRef = arrayConfig.get("itemSchemaRef");
        String ref = rawRef instanceof String refText ? refText : null;
        if (ref == null || ref.isBlank()) {
            ref = refFromItems(propertySchema);
        }
        if (ref == null || ref.isBlank()) {
            return;
        }

        Map<String, Object> itemSchema = buildInlineItemSchema(ref, allSchemas);
        if (!itemSchema.isEmpty()) {
            arrayConfig.put("itemSchema", itemSchema);
        }
    }

    @SuppressWarnings("unchecked")
    private String refFromItems(Map<String, Object> propertySchema) {
        Object rawItems = propertySchema.get(ITEMS);
        if (!(rawItems instanceof Map<?, ?> items)) {
            return null;
        }
        Object rawRef = ((Map<String, Object>) items).get(REF);
        return rawRef instanceof String ref ? ref : null;
    }

    private Map<String, Object> buildInlineItemSchema(String ref, JsonNode allSchemas) {
        String schemaName = extractSchemaNameFromRef(ref);
        if (schemaName == null || schemaName.isBlank()) {
            return Collections.emptyMap();
        }

        JsonNode componentSchema = allSchemas.path(schemaName);
        JsonNode properties = componentSchema.path(PROPERTIES);
        if (properties.isMissingNode() || !properties.isObject()) {
            return Collections.emptyMap();
        }

        Set<String> requiredFields = requiredFields(componentSchema.path("required"));
        List<Map<String, Object>> fields = new ArrayList<>();
        Iterator<Entry<String, JsonNode>> iterator = properties.fields();
        while (iterator.hasNext()) {
            Entry<String, JsonNode> property = iterator.next();
            Map<String, Object> field = objectMapper.convertValue(
                    property.getValue(),
                    new TypeReference<Map<String, Object>>() { }
            );
            field.putIfAbsent("name", property.getKey());
            if (requiredFields.contains(property.getKey())) {
                markInlineFieldRequired(field);
            }
            fields.add(field);
        }

        Map<String, Object> itemSchema = new LinkedHashMap<>();
        itemSchema.put("type", componentSchema.path("type").asText("object"));
        itemSchema.put("fields", fields);
        return itemSchema;
    }

    private Set<String> requiredFields(JsonNode requiredNode) {
        if (requiredNode == null || !requiredNode.isArray()) {
            return Collections.emptySet();
        }

        Set<String> required = new LinkedHashSet<>();
        requiredNode.forEach(item -> {
            if (item.isTextual() && StringUtils.hasText(item.asText())) {
                required.add(item.asText());
            }
        });
        return required;
    }

    @SuppressWarnings("unchecked")
    private void markInlineFieldRequired(Map<String, Object> field) {
        field.put("required", true);
        Object rawXUi = field.get(X_UI);
        Map<String, Object> xUi;
        if (rawXUi instanceof Map<?, ?> existingXUi) {
            xUi = (Map<String, Object>) existingXUi;
        } else {
            xUi = new LinkedHashMap<>();
            field.put(X_UI, xUi);
        }
        xUi.put("required", true);
    }

    private void enrichReferencedComponentSchemas(Map<String, Object> schemaMap, JsonNode allSchemas) {
        if (schemaMap == null || allSchemas == null || allSchemas.isMissingNode() || !allSchemas.isObject()) {
            return;
        }

        JsonNode schemaTree = objectMapper.valueToTree(schemaMap);
        Map<String, Object> referencedSchemas = new LinkedHashMap<>();
        collectReferencedComponentSchemas(schemaTree, allSchemas, referencedSchemas, new LinkedHashSet<>());
        if (referencedSchemas.isEmpty()) {
            return;
        }

        Map<String, Object> components = new LinkedHashMap<>();
        components.put(SCHEMAS, referencedSchemas);
        schemaMap.put(COMPONENTS, components);
    }

    private void collectReferencedComponentSchemas(JsonNode node,
                                                   JsonNode allSchemas,
                                                   Map<String, Object> referencedSchemas,
                                                   Set<String> visitedSchemaNames) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }

        if (node.isObject()) {
            String ref = refText(node.path(REF));
            if (ref == null) {
                ref = refText(node.path("itemSchemaRef"));
            }
            if (ref != null) {
                addReferencedComponentSchema(ref, allSchemas, referencedSchemas, visitedSchemaNames);
            }

            Iterator<Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Entry<String, JsonNode> field = fields.next();
                collectReferencedComponentSchemas(field.getValue(), allSchemas, referencedSchemas, visitedSchemaNames);
            }
            return;
        }

        if (node.isArray()) {
            for (JsonNode item : node) {
                collectReferencedComponentSchemas(item, allSchemas, referencedSchemas, visitedSchemaNames);
            }
        }
    }

    private String refText(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        String ref = node.asText();
        return ref != null && ref.startsWith("#/components/schemas/") ? ref : null;
    }

    private void addReferencedComponentSchema(String ref,
                                              JsonNode allSchemas,
                                              Map<String, Object> referencedSchemas,
                                              Set<String> visitedSchemaNames) {
        String schemaName = extractSchemaNameFromRef(ref);
        if (schemaName == null || schemaName.isBlank() || !visitedSchemaNames.add(schemaName)) {
            return;
        }

        JsonNode componentSchema = allSchemas.path(schemaName);
        if (componentSchema.isMissingNode() || !componentSchema.isObject()) {
            return;
        }

        referencedSchemas.put(schemaName, objectMapper.convertValue(componentSchema, new TypeReference<Map<String, Object>>() { }));
        collectReferencedComponentSchemas(componentSchema, allSchemas, referencedSchemas, visitedSchemaNames);
    }

    private Map<String, Object> extractOperationExamples(JsonNode operationNode, String schemaType) {
        Map<String, Object> result = new LinkedHashMap<>();

        if ("request".equalsIgnoreCase(schemaType)) {
            Map<String, Object> requestExamples = extractExamplesFromContentNode(
                    openApiDocsSupport.selectPreferredContentNode(operationNode.path("requestBody").path("content"))
            );
            if (!requestExamples.isEmpty()) {
                result.put("request", requestExamples);
            }
        }

        if ("response".equalsIgnoreCase(schemaType)) {
            Map<String, Object> responseExamples = extractExamplesFromContentNode(
                    openApiDocsSupport.selectPreferredContentNode(operationNode.path("responses").path("200").path("content"))
            );
            if (!responseExamples.isEmpty()) {
                result.put("response", responseExamples);
            }
        }

        return result;
    }

    private Map<String, Object> extractExamplesFromContentNode(JsonNode contentNode) {
        Map<String, Object> examples = new LinkedHashMap<>();
        if (contentNode == null || contentNode.isMissingNode()) {
            return examples;
        }

        JsonNode examplesNode = contentNode.path("examples");
        if (!examplesNode.isMissingNode() && examplesNode.isObject()) {
            Iterator<Entry<String, JsonNode>> fields = examplesNode.fields();
            while (fields.hasNext()) {
                Entry<String, JsonNode> entry = fields.next();
                Map<String, Object> exampleMeta = new LinkedHashMap<>();
                JsonNode exampleNode = entry.getValue();
                if (exampleNode.has("summary")) {
                    exampleMeta.put("summary", exampleNode.path("summary").asText());
                }
                if (exampleNode.has("description")) {
                    exampleMeta.put("description", exampleNode.path("description").asText());
                }
                if (exampleNode.has("value")) {
                    exampleMeta.put("value", objectMapper.convertValue(exampleNode.path("value"), Object.class));
                }
                if (exampleNode.has("externalValue")) {
                    exampleMeta.put("externalValue", exampleNode.path("externalValue").asText());
                }
                if (!exampleMeta.isEmpty()) {
                    examples.put(entry.getKey(), exampleMeta);
                }
            }
        }

        JsonNode singleExampleNode = contentNode.path("example");
        if (!singleExampleNode.isMissingNode()) {
            Map<String, Object> defaultExample = new LinkedHashMap<>();
            defaultExample.put("value", objectMapper.convertValue(singleExampleNode, Object.class));
            examples.putIfAbsent("default", defaultExample);
        }

        return examples;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildStructuralSchemaPayload(Map<String, Object> schemaMap) {
        Map<String, Object> structuralPayload = new LinkedHashMap<>(schemaMap);
        Object xUiObj = structuralPayload.get(X_UI);
        if (xUiObj instanceof Map<?, ?> xUiMap) {
            Map<String, Object> xUiCopy = new LinkedHashMap<>((Map<String, Object>) xUiMap);
            DOCUMENTATION_X_UI_KEYS.forEach(xUiCopy::remove);
            structuralPayload.put(X_UI, xUiCopy);
        }
        return structuralPayload;
    }

    /**
     * Tenta isolar o {@code FilterDTO} real a partir de um request inline que o encapsula.
     *
     * <p>
     * A heuristica prioriza a propriedade {@code filterDTO}. Na falta dela, procura a primeira
     * referencia cujo schema termine com {@code FilterDTO}. Se nada disso existir, o inline
     * original e preservado.
     * </p>
     */
    private JsonNode tryExtractFilterSchemaFromInline(JsonNode inlineSchema, JsonNode allSchemas) {
        return new FilteredSchemaProjection(objectMapper).tryExtractFilterSchemaFromInline(inlineSchema, allSchemas);
    }

    /**
     * Expande referencias internas {@code $ref} dentro do schema quando solicitado pelo cliente.
     *
     * <p>
     * Essa expansao atua apenas sobre referencias internas do mesmo documento OpenAPI e preserva a
     * semantica do schema filtrado para consumidores que preferem payload estrutural inline.
     * </p>
     */
    private void replaceInternalSchemas(ObjectNode schemaNode, JsonNode allSchemas) {
        // 0) Top-level $ref
        JsonNode topRef = schemaNode.path(REF);
        if (!topRef.isMissingNode()) {
            String refSchemaName = extractSchemaNameFromRef(topRef.asText());
            JsonNode refSchemaNode = allSchemas.path(refSchemaName);
            if (!refSchemaNode.isMissingNode() && refSchemaNode.isObject()) {
                LOGGER.info("Replacing top-level $ref with full schema {}", refSchemaName);
                schemaNode.removeAll();
                schemaNode.setAll(((ObjectNode) refSchemaNode).deepCopy());
            }
        }

        // Generic scan over all object fields to catch nested custom keys (e.g., 'schema')
        Iterator<Entry<String, JsonNode>> genericFields = schemaNode.fields();
        while (genericFields.hasNext()) {
            Entry<String, JsonNode> entry = genericFields.next();
            String key = entry.getKey();
            JsonNode val = entry.getValue();
            if (val != null && val.isObject()) {
                ObjectNode obj = (ObjectNode) val;
                JsonNode innerRef = obj.path(REF);
                if (!innerRef.isMissingNode()) {
                    String refName = extractSchemaNameFromRef(innerRef.asText());
                    JsonNode refSchemaNode = allSchemas.path(refName);
                    if (!refSchemaNode.isMissingNode() && refSchemaNode.isObject()) {
                        obj.removeAll();
                        obj.setAll(((ObjectNode) refSchemaNode).deepCopy());
                    }
                }
                // Recurse further
                replaceInternalSchemas(obj, allSchemas);
            }
        }

        // 1) Properties
        if (schemaNode.has(PROPERTIES)) {
            Iterator<Entry<String, JsonNode>> fields = schemaNode.path(PROPERTIES).fields();
            while (fields.hasNext()) {
                Entry<String, JsonNode> field = fields.next();
                JsonNode fieldValue = field.getValue();
                JsonNode refNode = fieldValue.path(REF);
                if (!refNode.isMissingNode()) {
                    String ref = refNode.asText();
                    String refSchemaName = ref.substring(ref.lastIndexOf('/') + 1);
                    JsonNode refSchemaNode = allSchemas.path(refSchemaName);
                    if (!refSchemaNode.isMissingNode() && refSchemaNode.isObject()) {
                        LOGGER.info("Replacing $ref {} with full schema {}", ref, refSchemaName);
                        ((ObjectNode) fieldValue).remove(REF);
                        ((ObjectNode) fieldValue).setAll(((ObjectNode) refSchemaNode).deepCopy());
                        replaceInternalSchemas((ObjectNode) fieldValue, allSchemas);
                    } else {
                        LOGGER.warn("Schema {} not found or not object", refSchemaName);
                    }
                } else if (fieldValue.isObject()) {
                    replaceInternalSchemas((ObjectNode) fieldValue, allSchemas);
                }
            }
        }

        // 2) Items
        if (schemaNode.has(ITEMS) && schemaNode.path(ITEMS).isObject()) {
            replaceInternalSchemas((ObjectNode) schemaNode.path(ITEMS), allSchemas);
        }

        // 3) Compositions: allOf/oneOf/anyOf
        processCompositionArray(schemaNode, allSchemas, "allOf");
        processCompositionArray(schemaNode, allSchemas, "oneOf");
        processCompositionArray(schemaNode, allSchemas, "anyOf");

        // 4) additionalProperties
        if (schemaNode.has("additionalProperties") && schemaNode.path("additionalProperties").isObject()) {
            replaceInternalSchemas((ObjectNode) schemaNode.path("additionalProperties"), allSchemas);
        }
    }

    private void processCompositionArray(ObjectNode schemaNode, JsonNode allSchemas, String keyword) {
        JsonNode comp = schemaNode.path(keyword);
        if (comp != null && comp.isArray()) {
            for (JsonNode element : comp) {
                if (element.isObject()) {
                    ObjectNode obj = (ObjectNode) element;
                    JsonNode ref = obj.path(REF);
                    if (!ref.isMissingNode()) {
                        String refName = extractSchemaNameFromRef(ref.asText());
                        JsonNode refSchemaNode = allSchemas.path(refName);
                        if (!refSchemaNode.isMissingNode() && refSchemaNode.isObject()) {
                            obj.removeAll();
                            obj.setAll(((ObjectNode) refSchemaNode).deepCopy());
                        }
                    }
                    replaceInternalSchemas(obj, allSchemas);
                }
            }
        }
    }

    // processControlTypes method is now removed as its logic is integrated into processSpecialFields
    // and OpenApiUiUtils.determineSmartControlTypeByFieldName

    /**
     * Localiza o schema do corpo de requisicao para a operacao informada.
     */
    protected String findRequestSchema(JsonNode pathsNode) {
        JsonNode schemaNode = pathsNode
                .path("requestBody")
                .path("content")
                .path("application/json")
                .path("schema");

        if (!schemaNode.isMissingNode() && schemaNode.has(REF)) {
            return extractSchemaNameFromRef(schemaNode.path(REF).asText());
        }
        return null;
    }

    /**
     * Extrai apenas o nome do schema a partir de um {@code $ref}.
     */
    private String extractSchemaNameFromRef(String ref) {
        return new FilteredSchemaProjection(objectMapper).extractSchemaNameFromRef(ref);
    }

    // ------------------------------------------------------------------------
    // Metodos de resolucao automatica de grupos e cache
    // ------------------------------------------------------------------------

    /**
     * Delega a leitura do documento OpenAPI ao servico canonico de documentos.
     *
     * <p>
     * O controller nao mantem mais cache local nem fallback proprio. Toda a politica de fetch,
     * cache e erro estrutural pertence a {@link OpenApiDocumentService}.
     * </p>
     */
    private JsonNode getDocumentForGroup(String groupName) {
        return openApiDocumentService.getDocumentForGroup(groupName);
    }

    /**
     * Limpa os caches estruturais compartilhados de documento OpenAPI e hash de schema.
     */
    public void clearDocumentCache() {
        openApiDocumentService.clearCaches();
    }

    @SuppressWarnings("unchecked")
    private void propagateArrayEnumOptionsRecursive(Object schemaNode, JsonNode allSchemas) {
        propagateArrayEnumOptionsRecursive(schemaNode, allSchemas, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    @SuppressWarnings("unchecked")
    private void propagateArrayEnumOptionsRecursive(Object schemaNode, JsonNode allSchemas, Set<Object> visited) {
        if (schemaNode == null || !visited.add(schemaNode)) {
            return;
        }
        if (schemaNode instanceof Map<?, ?> rawSchemaNode) {
            Map<String, Object> propSchema = (Map<String, Object>) rawSchemaNode;

            String type = (String) propSchema.get("type");
            if ("array".equals(type)) {
                Object rawItems = propSchema.get(ITEMS);
                if (rawItems instanceof Map<?, ?> itemsSchemaMap) {
                    Map<String, Object> itemsSchema = (Map<String, Object>) itemsSchemaMap;
                    List<Object> enumValues = null;

                    Object itemsEnum = itemsSchema.get("enum");
                    if (itemsEnum instanceof List<?> list && !list.isEmpty()) {
                        enumValues = (List<Object>) list;
                    }

                    if (enumValues == null && itemsSchema.containsKey(REF)) {
                        String ref = (String) itemsSchema.get(REF);
                        String refName = extractSchemaNameFromRef(ref);
                        if (refName != null && allSchemas != null && !allSchemas.isMissingNode()) {
                            JsonNode refSchema = allSchemas.path(refName);
                            if (refSchema != null && !refSchema.isMissingNode()) {
                                JsonNode enumNode = refSchema.path("enum");
                                if (enumNode != null && enumNode.isArray() && enumNode.size() > 0) {
                                    enumValues = new ArrayList<>();
                                    for (JsonNode val : enumNode) {
                                        if (val.isNull()) continue;
                                        if (val.isTextual()) {
                                            enumValues.add(val.asText());
                                        } else {
                                            enumValues.add(val.toString());
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (enumValues != null && !enumValues.isEmpty()) {
                        enumValues = enumValues.stream().filter(value -> value != null
                                && !(value instanceof JsonNode node && node.isNull())).toList();
                        Map<String, Object> parentXui = ensureNestedMap(propSchema, X_UI);
                        if (!parentXui.containsKey("options")) {
                            OpenApiUiUtils.populateUiOptionsFromEnum(parentXui, enumValues, this.objectMapper);
                        }
                        applyArrayEnumUiContract(parentXui, enumValues.size());
                    }
                }
            }

            new ArrayList<>(propSchema.values()).forEach(value -> propagateArrayEnumOptionsRecursive(value, allSchemas, visited));
            return;
        }

        if (schemaNode instanceof Collection<?> values) {
            new ArrayList<>(values).forEach(value -> propagateArrayEnumOptionsRecursive(value, allSchemas, visited));
        }
    }

    private void applyArrayEnumUiContract(Map<String, Object> xUi, int optionCount) {
        Object controlType = xUi.get(FieldConfigProperties.CONTROL_TYPE.getValue());
        if (controlType == null || FieldControlType.ARRAY.getValue().equals(controlType)) {
            xUi.put(
                    FieldConfigProperties.CONTROL_TYPE.getValue(),
                    OpenApiUiUtils.determineArrayEnumControlBySize(optionCount)
            );
        }
        xUi.remove("array");
    }

}
