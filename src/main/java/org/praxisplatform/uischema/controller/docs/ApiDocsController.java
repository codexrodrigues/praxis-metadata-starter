package org.praxisplatform.uischema.controller.docs;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.praxisplatform.uischema.FieldConfigProperties;
import org.praxisplatform.uischema.capability.CanonicalCapabilityResolver;
import org.praxisplatform.uischema.openapi.CanonicalOperationRef;
import org.praxisplatform.uischema.openapi.CanonicalOperationResolver;
import org.praxisplatform.uischema.openapi.OpenApiDocumentService;
import org.praxisplatform.uischema.options.OptionSourceDescriptor;
import org.praxisplatform.uischema.options.OptionSourceRegistry;
import org.praxisplatform.uischema.schema.CanonicalSchemaRef;
import org.praxisplatform.uischema.schema.SchemaReferenceResolver;
import org.praxisplatform.uischema.util.OpenApiUiUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriUtils;

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
    private static final String RESPONSE_SCHEMA = "responseSchema";
    private static final String PROPERTIES = "properties";
    private static final String REF = "$ref";
    private static final String ITEMS = "items";
    private static final String OPERATION_EXAMPLES = "operationExamples";
    private static final Set<String> DOCUMENTATION_X_UI_KEYS = Set.of(OPERATION_EXAMPLES);

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

    @Autowired(required = false)
    private OptionSourceRegistry optionSourceRegistry;

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
            @RequestParam String path,
            @RequestParam(required = false, defaultValue = DEFAULT_OPERATION) String operation,
            @RequestParam(required = false, defaultValue = "false") boolean includeInternalSchemas,
            @RequestParam(required = false, defaultValue = "response") String schemaType,
            @RequestParam(required = false) String idField,
            @RequestParam(required = false) Boolean readOnly,
            @org.springframework.web.bind.annotation.RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch,
            @org.springframework.web.bind.annotation.RequestHeader(value = "X-Tenant", required = false) String tenant,
            java.util.Locale locale) {

        if (!"response".equalsIgnoreCase(schemaType) && !"request".equalsIgnoreCase(schemaType)) {
            throw new IllegalArgumentException("Parameter 'schemaType' must be 'response' or 'request'.");
        }

        String decodedPath = UriUtils.decode(path, StandardCharsets.UTF_8);

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

        String canonicalPath = resolveDocumentPath(rootNode.path(PATHS), decodedPath);

        // Procura o caminho especificado no JSON
        JsonNode pathsNode = rootNode.path(PATHS).path(canonicalPath).path(normalizedOperation);

        if (pathsNode.isMissingNode()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "The specified path or operation was not found in the documentation."
            );
        }

        LOGGER.info("Path and operation node retrieved successfully");

        // Escolhe o schema conforme o schemaType indicado
        String schemaName = null;
        JsonNode directSchemaNode = null;
        if ("request".equalsIgnoreCase(schemaType)) {
            // Tenta localizar schema do corpo de requisicao
            JsonNode bodySchema = openApiDocsSupport.selectPreferredContentNode(
                    pathsNode.path("requestBody").path("content")
            ).path("schema");

            if (!bodySchema.isMissingNode()) {
                if (bodySchema.has(REF)) {
                    schemaName = extractSchemaNameFromRef(bodySchema.path(REF).asText());
                    LOGGER.info("Request schema encontrado por $ref: {}", schemaName);
                } else {
                    // Schema inline (sem $ref): usar diretamente (posteriormente tentaremos extrair o FilterDTO real)
                    directSchemaNode = bodySchema;
                    LOGGER.info("Request schema inline detectado (sem $ref). Usando schema inline.");
                }
            }
        } else {
            schemaName = findResponseSchema(pathsNode, rootNode, operationRef.method(), canonicalPath);
        }

        if ((schemaName == null || schemaName.isEmpty()) && (directSchemaNode == null)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "The requested schema was not found or is not defined for the specified path and operation."
            );
        }

        LOGGER.info("Schema found: {}", schemaName != null ? schemaName : "<inline>");

        // Recupera o no do schema: de components/schemas quando ha nome; ou o no inline quando aplicavel
        JsonNode schemasNode;
        JsonNode allSchemas = rootNode.path(COMPONENTS).path(SCHEMAS);
        if (directSchemaNode != null) {
            // Heuristica: quando o corpo e um objeto com propriedades (ex.: filterDTO, pageable), tentar extrair o schema do filtro
            JsonNode extracted = tryExtractFilterSchemaFromInline(directSchemaNode, allSchemas);
            schemasNode = extracted != null ? extracted : directSchemaNode;
        } else {
            schemasNode = allSchemas.path(schemaName);
            if (schemasNode.isMissingNode()) {
                throw new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "The specified component schema was not found in the documentation."
                );
            }
        }

        LOGGER.info("Schema node retrieved successfully");

        JsonNode schemaNodeForResponse = schemasNode;
        // Se includeInternalSchemas for verdadeiro, substitui schemas internos em uma copia
        // profunda para nao contaminar o documento OpenAPI compartilhado em cache.
        if (includeInternalSchemas && schemasNode.isObject()) {
            schemaNodeForResponse = schemasNode.deepCopy();
            replaceInternalSchemas((ObjectNode) schemaNodeForResponse, allSchemas);
        }

        // Converte o esquema para um Map
        Map<String, Object> schemaMap = objectMapper.convertValue(schemaNodeForResponse, new TypeReference<Map<String, Object>>() { });

        String basePath = deriveBasePathFrom(canonicalPath);

        // Copia os valores de xUiNode para o "x-ui" do objeto retornado
        JsonNode xUiNode = pathsNode.path(X_UI);
        Map<String, Object> xUiMap = objectMapper.convertValue(xUiNode, new TypeReference<Map<String, Object>>() { });
        if (xUiMap == null) {
            xUiMap = new java.util.HashMap<>();
        }
        Map<String, Object> operationExamples = resolveOperationExamples(pathsNode, xUiMap, schemaType);
        if (!operationExamples.isEmpty()) {
            xUiMap.put(OPERATION_EXAMPLES, operationExamples);
        }

        // Anotar x-ui.resource.idField para o frontend
        String resolvedIdField = resolveIdField(idField, schemaMap, rootNode, basePath, schemaType);
        Map<String, Boolean> caps = computeCapabilities(rootNode, basePath);
        boolean computedReadOnly = (readOnly != null) ? readOnly.booleanValue() :
                !(Boolean.TRUE.equals(caps.getOrDefault("create", false))
                        || Boolean.TRUE.equals(caps.getOrDefault("update", false))
                        || Boolean.TRUE.equals(caps.getOrDefault("delete", false)));
        if (resolvedIdField != null && !resolvedIdField.isBlank()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> resourceMeta = (Map<String, Object>) xUiMap.get("resource");
            if (resourceMeta == null) {
                resourceMeta = new java.util.HashMap<>();
                xUiMap.put("resource", resourceMeta);
            }
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

        enrichPropertyOptionSources(schemaMap, basePath);
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

    // Backwards-compatible overload used by unit tests and callers without idField param
    public org.springframework.http.ResponseEntity<Map<String, Object>> getFilteredSchema(
            String path,
            String operation,
            boolean includeInternalSchemas,
            String schemaType,
            String ifNoneMatch,
            String tenant,
            java.util.Locale locale) {
        return getFilteredSchema(path, operation, includeInternalSchemas, schemaType, null, null, ifNoneMatch, tenant, locale);
    }

    /**
     * Resolve o {@code idField} final a ser exposto em {@code x-ui.resource.idField}.
     *
     * <p>
     * A prioridade atual e: parametro explicito da requisicao, propriedade chamada {@code id} no
     * schema resolvido e, por fim, fallback para {@code id}.
     * </p>
     */
    private String resolveIdField(String requestedIdField,
                                  Map<String, Object> schemaMap,
                                  JsonNode rootNode,
                                  String basePath,
                                  String schemaType) {
        try {
            if (requestedIdField != null && !requestedIdField.isBlank()) {
                return requestedIdField;
            }
            String canonicalIdField = resolveCanonicalIdFieldFromResourceResponse(rootNode, basePath);
            if (canonicalIdField != null && !canonicalIdField.isBlank()) {
                return canonicalIdField;
            }
            if (hasSchemaProperty(schemaMap, "id")) {
                return "id";
            }
            if ("response".equalsIgnoreCase(schemaType)) {
                @SuppressWarnings("unchecked")
                Map<String, Object> props = (Map<String, Object>) schemaMap.get("properties");
                if (props != null) {
                    for (String key : props.keySet()) {
                        if (key != null && key.endsWith("Id")) {
                            return key;
                        }
                    }
                }
            }
            // Conservative fallback
            return "id";
        } catch (Exception e) {
            LOGGER.debug("Falha ao resolver idField: {}", e.getMessage());
            return "id";
        }
    }

    private String resolveCanonicalIdFieldFromResourceResponse(JsonNode rootNode, String basePath) {
        if (rootNode == null || basePath == null || basePath.isBlank()) {
            return null;
        }

        JsonNode resourceSchema = findResourceResponseSchema(rootNode, basePath + "/{id}", "get");
        if (resourceSchema == null || resourceSchema.isMissingNode()) {
            resourceSchema = findResourceResponseSchema(rootNode, basePath + "/all", "get");
        }
        if (resourceSchema == null || resourceSchema.isMissingNode()) {
            resourceSchema = findResourceResponseSchema(rootNode, basePath, "post");
        }
        if (resourceSchema == null || resourceSchema.isMissingNode()) {
            resourceSchema = findResourceResponseSchema(rootNode, basePath + "/filter", "post");
        }
        if (resourceSchema == null || resourceSchema.isMissingNode()) {
            return null;
        }

        Map<String, Object> resourceSchemaMap = objectMapper.convertValue(
                resourceSchema,
                new TypeReference<Map<String, Object>>() { });
        if (hasSchemaProperty(resourceSchemaMap, "id")) {
            return "id";
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) resourceSchemaMap.get(PROPERTIES);
        if (props != null) {
            for (String key : props.keySet()) {
                if (key != null && key.endsWith("Id")) {
                    return key;
                }
            }
        }
        return null;
    }

    private JsonNode findResourceResponseSchema(JsonNode rootNode, String path, String operation) {
        if (rootNode == null || path == null || path.isBlank()) {
            return null;
        }

        JsonNode operationNode = rootNode.path(PATHS).path(path).path(operation);
        if (operationNode == null || operationNode.isMissingNode()) {
            return null;
        }

        String schemaName = findResponseSchema(operationNode, rootNode, operation, path);
        if (!StringUtils.hasText(schemaName)) {
            return null;
        }

        JsonNode schemaNode = rootNode.path(COMPONENTS).path(SCHEMAS).path(schemaName);
        return (schemaNode == null || schemaNode.isMissingNode()) ? null : schemaNode;
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
        if (fullPath == null || fullPath.isBlank()) return fullPath;
        String p = fullPath;
        // normaliza barras
        p = p.replaceAll("/+", "/");
        if (p.endsWith("/") && p.length() > 1) p = p.substring(0, p.length() - 1);

        String[] suffixes = new String[]{
                "/stats/distribution",
                "/stats/timeseries",
                "/stats/group-by",
                "/options/by-ids",
                "/options/filter",
                "/filter/cursor",
                "/by-ids",
                "/schemas/filtered",
                "/schemas",
                "/filter",
                "/locate",
                "/batch",
                "/{id}",
                "/all"
        };
        for (String s : suffixes) {
            if (p.endsWith(s)) {
                return p.substring(0, p.length() - s.length());
            }
        }

        int variableSegmentIndex = p.indexOf("/{");
        if (variableSegmentIndex > 0) {
            return p.substring(0, variableSegmentIndex);
        }

        return p; // ja e base
    }

    private String resolveDocumentPath(JsonNode pathsNode, String requestedPath) {
        if (pathsNode == null || pathsNode.isMissingNode()) {
            return requestedPath;
        }

        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        candidates.add(requestedPath);

        String normalized = normalizeOpenApiPath(requestedPath);
        candidates.add(normalized);
        if ("/".equals(normalized)) {
            candidates.add("/");
        } else {
            candidates.add(normalized + "/");
        }

        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank() && !pathsNode.path(candidate).isMissingNode()) {
                return candidate;
            }
        }

        return normalized;
    }

    private String normalizeOpenApiPath(String path) {
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
        if (optionSourceRegistry == null || basePath == null || basePath.isBlank()) {
            return;
        }
        Object rawProperties = schemaMap.get(PROPERTIES);
        if (!(rawProperties instanceof Map<?, ?> properties)) {
            return;
        }
        for (Map.Entry<?, ?> entry : properties.entrySet()) {
            if (!(entry.getKey() instanceof String fieldName) || !(entry.getValue() instanceof Map<?, ?> rawFieldSchema)) {
                continue;
            }
            OptionSourceDescriptor descriptor = optionSourceRegistry
                    .resolveByResourcePathAndField(basePath, fieldName)
                    .orElse(null);
            if (descriptor == null) {
                continue;
            }
            Map<String, Object> fieldSchema = (Map<String, Object>) rawFieldSchema;
            Map<String, Object> fieldXUi = ensureNestedMap(fieldSchema, X_UI);
            Map<String, Object> optionSourceMeta = ensureNestedMap(fieldXUi, "optionSource");
            descriptor.toMetadataMap().forEach(optionSourceMeta::putIfAbsent);
        }
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

    /**
     * Retorna {@code true} quando o schema ja contem a propriedade informada.
     */
    @SuppressWarnings("unchecked")
    private boolean hasSchemaProperty(Map<String, Object> schemaMap, String prop) {
        if (schemaMap == null || prop == null) return false;
        Object propsObj = schemaMap.get("properties");
        if (!(propsObj instanceof Map)) return false;
        return ((Map<String, Object>) propsObj).containsKey(prop);
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
        LOGGER.warn(
                "x-ui.resource.idField='{}' nao encontrado nas propriedades do schema '{}'",
                resolvedIdField,
                schemaName
        );
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
        if (inlineSchema == null || inlineSchema.isMissingNode()) return null;
        JsonNode props = inlineSchema.path(PROPERTIES);
        if (props.isMissingNode() || !props.fieldNames().hasNext()) return null;

        // 1) Preferencia por propriedade explicitamente chamada 'filterDTO'
        JsonNode filterDtoNode = props.path("filterDTO");
        if (!filterDtoNode.isMissingNode()) {
            JsonNode refNode = filterDtoNode.path(REF);
            if (!refNode.isMissingNode()) {
                String refName = extractSchemaNameFromRef(refNode.asText());
                JsonNode resolved = allSchemas.path(refName);
                if (!resolved.isMissingNode()) {
                    LOGGER.info("Extraido FilterDTO via propriedade 'filterDTO': {}", refName);
                    return resolved;
                }
            }
        }

        // 2) Caso nao exista 'filterDTO', procurar qualquer propriedade com $ref que termine com 'FilterDTO'
        Iterator<Entry<String, JsonNode>> it = props.fields();
        while (it.hasNext()) {
            Entry<String, JsonNode> entry = it.next();
            JsonNode val = entry.getValue();
            JsonNode ref = val.path(REF);
            if (!ref.isMissingNode()) {
                String refName = extractSchemaNameFromRef(ref.asText());
                if (refName != null && refName.endsWith("FilterDTO")) {
                    JsonNode resolved = allSchemas.path(refName);
                    if (!resolved.isMissingNode()) {
                    LOGGER.info("Extraido FilterDTO via heuristica de sufixo: {}", refName);
                        return resolved;
                    }
                }
            }
        }

        // 3) Nao foi possivel extrair um FilterDTO especifico
        return null;
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
     * Localiza o schema de resposta usando as heuristicas canonicas atuais.
     *
     * <p>
     * A busca prioriza {@code x-ui.responseSchema}, depois o schema da resposta HTTP e, por fim,
     * heuristicas para wrappers como {@code RestApiResponse}.
     * </p>
     */
    private String findResponseSchema(JsonNode pathsNode, JsonNode rootNode, String operation, String decodedPath) {
        // 1. Primeiro tenta encontrar no no x-ui (abordagem atual)
        JsonNode xUiNode = pathsNode.path(X_UI);
        if (!xUiNode.isMissingNode() && !xUiNode.path(RESPONSE_SCHEMA).isMissingNode()) {
            String responseSchema = xUiNode.path(RESPONSE_SCHEMA).asText();
            LOGGER.info("Response schema encontrado em x-ui: {}", responseSchema);
            return responseSchema;
        }

        // 2. Tenta extrair do schema de resposta 200 OK
        JsonNode responses = pathsNode.path("responses");
        JsonNode okResponse = openApiDocsSupport.selectPreferredContentNode(
                responses.path("200").path("content")
        ).path("schema");
        if (okResponse.isMissingNode()) {
            okResponse = openApiDocsSupport.selectPreferredContentNode(
                    responses.path("201").path("content")
            ).path("schema");
        }

        if (!okResponse.isMissingNode() && okResponse.has("$ref")) {
            String schemaRef = okResponse.path("$ref").asText();
            String wrapperSchemaName = extractSchemaNameFromRef(schemaRef);
            LOGGER.info("Schema wrapper encontrado: {}", wrapperSchemaName);

            // Agora temos o nome do schema wrapper, vamos localizar o tipo real dentro do wrapper
            JsonNode wrapperSchema = rootNode.path(COMPONENTS).path(SCHEMAS).path(wrapperSchemaName);

            if (!wrapperSchema.isMissingNode()) {
            // Verificar se e RestApiResponseTestDTO ou RestApiResponseListTestDTO
                if (wrapperSchemaName.startsWith("RestApiResponse")) {
                // Encontrar o tipo generico dentro do RestApiResponse
                    String realTypeName = extractRealTypeFromRestApiResponse(
                            wrapperSchema,
                            wrapperSchemaName,
                            rootNode.path(COMPONENTS).path(SCHEMAS)
                    );
                    if (realTypeName != null) {
                    LOGGER.info("Tipo real extraido de {}: {}", wrapperSchemaName, realTypeName);
                        return realTypeName;
                    }
                } else {
                    // Quando a resposta referencia diretamente um DTO sem wrapper
                    return wrapperSchemaName;
                }
            }
        }

        // 3. Tenta inferir pelo nome do endpoint
        String[] pathParts = decodedPath.split("/");
        if (pathParts.length > 0) {
            String lastSegment = pathParts[pathParts.length - 1];
            // Se o ultimo segmento do path for "list", podemos inferir que o retorno e uma lista
            // de algum tipo, provavelmente relacionado ao penultimo segmento
            if ("list".equals(lastSegment) && pathParts.length > 1) {
                String entityName = pathParts[pathParts.length - 2];
                String capitalizedName = entityName.substring(0, 1).toUpperCase() + entityName.substring(1);
                if (capitalizedName.endsWith("s")) {
                    capitalizedName = capitalizedName.substring(0, capitalizedName.length() - 1);
                }
                String potentialTypeName = capitalizedName + "DTO";

                // Verifica se o schema inferido existe
                if (!rootNode.path(COMPONENTS).path(SCHEMAS).path(potentialTypeName).isMissingNode()) {
                    LOGGER.info("Schema inferido pela URL: {}", potentialTypeName);
                    return potentialTypeName;
                }
            }
        }

        LOGGER.warn("Nao foi possivel encontrar um responseSchema para {}", decodedPath);
        return null;
    }

    /**
     * Extrai o tipo de dominio encapsulado por wrappers como {@code RestApiResponse}.
     */
    private String extractRealTypeFromRestApiResponse(JsonNode wrapperSchema, String wrapperSchemaName, JsonNode allSchemas) {
        String structuralType = resolveDomainSchemaName(
                wrapperSchema.path("properties").path("data"),
                allSchemas,
                new LinkedHashSet<>()
        );
        if (StringUtils.hasText(structuralType)) {
            return structuralType;
        }

        // Analise do nome para casos comuns como "RestApiResponseTestDTO" ou "RestApiResponseListTestDTO"
        if (wrapperSchemaName.startsWith("RestApiResponse")) {
            String remaining = wrapperSchemaName.substring("RestApiResponse".length());

            // Verifica se e uma lista (RestApiResponseListXXX)
            if (remaining.startsWith("List")) {
                String typeName = remaining.substring("List".length());
                return typeName; // Retorna o tipo contido na lista (ex: "TestDTO")
            } else {
                return remaining; // Retorna o tipo direto (ex: "TestDTO")
            }
        }

        // Se a analise pelo nome nao funcionar, tenta analisar a estrutura do schema
        // Especificamente, buscamos a propriedade "data" do RestApiResponse
        JsonNode dataSchema = wrapperSchema.path("properties").path("data");

        // Verifica se data e um array
        if (dataSchema.has("type") && "array".equals(dataSchema.path("type").asText()) && dataSchema.has("items") && dataSchema.path("items").has("$ref")) {
            // E um array, extrai o tipo dos items
            return extractSchemaNameFromRef(dataSchema.path("items").path("$ref").asText());
        }
        // Se data tem referencia direta
        else if (dataSchema.has("$ref")) {
            return extractSchemaNameFromRef(dataSchema.path("$ref").asText());
        }

        // Segunda tentativa: olhar propriedades do schema wrapper
        JsonNode properties = wrapperSchema.path("properties");
        if (!properties.isMissingNode()) {
            JsonNode dataProperty = properties.path("data");

            // Verifica se data e um objeto ou array
            if (!dataProperty.isMissingNode()) {
                // Se data e um array
                if (dataProperty.has("type") && "array".equals(dataProperty.path("type").asText())) {
                    // Verifica se o array tem referencia para o tipo dos itens
                    if (dataProperty.has("items") && dataProperty.path("items").has("$ref")) {
                        String itemRef = dataProperty.path("items").path("$ref").asText();
                        return extractSchemaNameFromRef(itemRef);
                    }
                }
                // Se data tem referencia direta
                else if (dataProperty.has("$ref")) {
                    return extractSchemaNameFromRef(dataProperty.path("$ref").asText());
                }
            }
        }

        // Nao conseguiu extrair o tipo
        return null;
    }

    private String resolveDomainSchemaName(JsonNode schemaNode, JsonNode allSchemas, Set<String> visited) {
        if (schemaNode == null || schemaNode.isMissingNode() || schemaNode.isNull()) {
            return null;
        }

        if (schemaNode.has(REF)) {
            String schemaName = extractSchemaNameFromRef(schemaNode.path(REF).asText());
            if (!StringUtils.hasText(schemaName)) {
                return null;
            }
            if (!visited.add(schemaName)) {
                return unwrapWrapperSchemaName(schemaName);
            }

            JsonNode referencedSchema = allSchemas == null ? null : allSchemas.path(schemaName);
            String nestedType = resolveDomainSchemaName(referencedSchema, allSchemas, visited);
            if (StringUtils.hasText(nestedType) && !isLinkInfrastructureSchema(nestedType)) {
                return nestedType;
            }
            return unwrapWrapperSchemaName(schemaName);
        }

        if (schemaNode.has("items")) {
            String nestedType = resolveDomainSchemaName(schemaNode.path("items"), allSchemas, visited);
            if (StringUtils.hasText(nestedType)) {
                return nestedType;
            }
        }

        JsonNode contentNode = schemaNode.path("properties").path("content");
        if (!contentNode.isMissingNode()) {
            String nestedType = resolveDomainSchemaName(contentNode, allSchemas, visited);
            if (StringUtils.hasText(nestedType)) {
                return nestedType;
            }
        }

        JsonNode dataNode = schemaNode.path("properties").path("data");
        if (!dataNode.isMissingNode()) {
            String nestedType = resolveDomainSchemaName(dataNode, allSchemas, visited);
            if (StringUtils.hasText(nestedType)) {
                return nestedType;
            }
        }

        JsonNode allOf = schemaNode.path("allOf");
        if (allOf.isArray()) {
            for (JsonNode candidate : allOf) {
                String nestedType = resolveDomainSchemaName(candidate, allSchemas, visited);
                if (StringUtils.hasText(nestedType) && !isLinkInfrastructureSchema(nestedType)) {
                    return nestedType;
                }
            }
        }

        return null;
    }

    private String unwrapWrapperSchemaName(String schemaName) {
        if (!StringUtils.hasText(schemaName)) {
            return null;
        }
        if (schemaName.startsWith("RestApiResource")) {
            return schemaName.substring("RestApiResource".length());
        }
        if (schemaName.startsWith("EntityModel")) {
            return schemaName.substring("EntityModel".length());
        }
        return schemaName;
    }

    private boolean isLinkInfrastructureSchema(String schemaName) {
        return "RestApiLinks".equals(schemaName) || "RestApiLinkObject".equals(schemaName);
    }

    /**
     * Extrai apenas o nome do schema a partir de um {@code $ref}.
     */
    private String extractSchemaNameFromRef(String ref) {
        return ref.substring(ref.lastIndexOf('/') + 1);
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

}
