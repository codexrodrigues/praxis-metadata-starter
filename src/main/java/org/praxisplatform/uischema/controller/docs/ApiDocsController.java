package org.praxisplatform.uischema.controller.docs;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.praxisplatform.uischema.FieldConfigProperties;
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
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.Map.Entry;

/**
 * Controlador REST que expÃµe o endpoint canÃ´nico {@code /schemas/filtered}.
 *
 * <p>
 * Esta e uma das superficies centrais do modelo metadata-driven do starter. Em vez de obrigar
 * cada consumidor a interpretar o documento OpenAPI completo, o controller resolve o grupo correto,
 * localiza a operacao desejada e devolve apenas o fragmento de schema relevante, enriquecido com
 * metadados {@code x-ui} e informacoes auxiliares para frontend, playgrounds e integradores.
 * </p>
 *
 * <h3>Responsabilidades principais</h3>
 * <ul>
 *   <li>Resolver automaticamente o grupo OpenAPI adequado via
 *   {@link org.praxisplatform.uischema.util.OpenApiGroupResolver}.</li>
 *   <li>Aplicar cache em memoria e suporte a {@code ETag}/{@code If-None-Match}.</li>
 *   <li>Selecionar schema de {@code request} ou {@code response} da operacao desejada.</li>
 *   <li>Enriquecer a resposta com {@code x-ui.resource}, capacidades, exemplos e metadados de option-sources.</li>
 * </ul>
 *
 * <h3>Uso tipico</h3>
 * <pre>{@code
 * GET /schemas/filtered?path=/api/human-resources/funcionarios/all&operation=get&schemaType=response
 * GET /schemas/filtered?path=/api/human-resources/funcionarios/filter&operation=post&schemaType=request
 * }</pre>
 *
 * <p>
 * O resultado e um fragmento OpenAPI filtrado, adequado para formularios, grids, consumo por IA,
 * validadores documentais e outras superficies derivadas que nao devem reconstruir manualmente a
 * semantica do contrato a partir do OpenAPI bruto.
 * </p>
 *
 * @see org.praxisplatform.uischema.util.OpenApiGroupResolver
 * @see org.praxisplatform.uischema.extension.CustomOpenApiResolver
 * @since 1.0.0
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

    // Constantes para valores padrÃ£o
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

    @Autowired(required = false)
    private OptionSourceRegistry optionSourceRegistry;

    /**
     * Recupera e filtra o schema OpenAPI de uma operacao especifica.
     *
     * <p>
     * O fluxo parte do {@code path} do recurso, resolve o grupo OpenAPI correspondente e carrega
     * o documento fonte. Em seguida, seleciona a operacao HTTP desejada e retorna o schema de
     * {@code response} ou {@code request}, opcionalmente expandindo referencias internas e
     * enriquecendo o payload com metadados auxiliares para consumidores metadata-driven.
     * </p>
     *
     * <h3>Parametros mais relevantes</h3>
     * <ul>
     *   <li>{@code path}: endpoint real do recurso cuja operacao servira de ancora canonica.</li>
     *   <li>{@code operation}: verbo HTTP em minusculo, como {@code get} ou {@code post}.</li>
     *   <li>{@code schemaType}: escolhe entre schema do corpo de {@code request} ou de {@code response}.</li>
     *   <li>{@code includeInternalSchemas}: quando {@code true}, expande refs internas para facilitar consumo direto.</li>
     * </ul>
     *
     * <p>
     * Alem do schema em si, a resposta pode incluir enriquecimentos como {@code x-ui.resource.idField},
     * flags de capacidade CRUD, exemplos de operacao e metadados de option-sources quando aplicavel.
     * </p>
     *
     * @param path caminho OpenAPI do recurso, por exemplo {@code /api/human-resources/funcionarios/all}
     * @param operation operacao HTTP; padrao {@code get}
     * @param includeInternalSchemas define se referencias internas devem ser expandidas recursivamente
     * @param schemaType define se o schema alvo e de {@code response} ou {@code request}
     * @param idField permite sobrescrever o campo de identificacao informado ao frontend
     * @param readOnly permite sinalizar explicitamente o estado de somente leitura quando necessario
     * @param ifNoneMatch ETag previamente recebida pelo cliente para validacao condicional
     * @param tenant cabecalho opcional de tenant para ambientes multitenant
     * @param locale locale da requisicao para resolucoes sensiveis a idioma
     * @return resposta HTTP contendo o schema filtrado e enriquecido
     * @throws IllegalStateException quando nao for possivel recuperar o documento OpenAPI do grupo resolvido
     * @throws IllegalArgumentException quando {@code path}, {@code operation} ou o schema solicitado nao existirem
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

        // 1. Resolver grupo automaticamente baseado no path
        CanonicalOperationRef operationRef = canonicalOperationResolver.resolve(path, operation);
        String normalizedOperation = operationRef.method().toLowerCase(Locale.ROOT);
        String groupName = operationRef.group();
        LOGGER.info("Path '{}' â†’ Grupo resolvido: '{}'", path, groupName);
        
        // 2. Obter documento especÃ­fico do cache
        JsonNode rootNode = openApiDocumentService.getDocumentForGroup(groupName);
        
        if (rootNode == null) {
            throw new IllegalStateException("Failed to retrieve the OpenAPI document for group: " + groupName);
        }

        // Decodifica o path para tratar caracteres especiais especiais (por exemplo, '%2F')
        String decodedPath = UriUtils.decode(path, StandardCharsets.UTF_8);

        // Procura o caminho especificado no JSON
        JsonNode pathsNode = rootNode.path(PATHS).path(decodedPath).path(normalizedOperation);

        if (pathsNode.isMissingNode()) {
            throw new IllegalArgumentException("The specified path or operation was not found in the documentation.");
        }

        LOGGER.info("Path and operation node retrieved successfully");

        // Escolhe o schema conforme o schemaType indicado
        String schemaName = null;
        JsonNode directSchemaNode = null;
        if ("request".equalsIgnoreCase(schemaType)) {
            // Tenta localizar schema do corpo de requisiÃ§Ã£o
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
            schemaName = findResponseSchema(pathsNode, rootNode, operationRef.method(), decodedPath);
        }

        if ((schemaName == null || schemaName.isEmpty()) && (directSchemaNode == null)) {
            throw new IllegalArgumentException("The requested schema was not found or is not defined for the specified path and operation.");
        }

        LOGGER.info("Schema found: {}", schemaName != null ? schemaName : "<inline>");

        // Recupera o nÃ³ do schema: de components/schemas quando hÃ¡ nome; ou o nÃ³ inline quando aplicÃ¡vel
        JsonNode schemasNode;
        JsonNode allSchemas = rootNode.path(COMPONENTS).path(SCHEMAS);
        if (directSchemaNode != null) {
            // HeurÃ­stica: quando o corpo Ã© um objeto com propriedades (ex.: filterDTO, pageable), tentar extrair o schema do filtro
            JsonNode extracted = tryExtractFilterSchemaFromInline(directSchemaNode, allSchemas);
            schemasNode = extracted != null ? extracted : directSchemaNode;
        } else {
            schemasNode = allSchemas.path(schemaName);
            if (schemasNode.isMissingNode()) {
                throw new IllegalArgumentException("The specified component schema was not found in the documentation.");
            }
        }

        LOGGER.info("Schema node retrieved successfully");

        // Se includeInternalSchemas for verdadeiro, substitui schemas internos
        if (includeInternalSchemas && schemasNode.isObject()) {
            replaceInternalSchemas((ObjectNode) schemasNode, allSchemas);
        }

        // Converte o esquema para um Map
        Map<String, Object> schemaMap = objectMapper.convertValue(schemasNode, new TypeReference<Map<String, Object>>() { });

        String basePath = deriveBasePathFrom(path);

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
                LOGGER.warn("x-ui.resource.idField='{}' nÃ£o encontrado nas propriedades do schema '{}'", resolvedIdField, schemaName);
            }

            resourceMeta.put("readOnly", computedReadOnly);
            resourceMeta.put("capabilities", caps);
        }

        enrichPropertyOptionSources(schemaMap, basePath);
        schemaMap.put(X_UI, xUiMap);

        // 4) Canonicalize and hash the final payload (com cache por schemaId)
        CanonicalSchemaRef schemaRef = schemaReferenceResolver.resolve(
                decodedPath,
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
     * Resolve the idField to annotate into x-ui.resource.idField.
     * Priority:
     * - Request param 'idField' (from HATEOAS link builder on controllers)
     * - Property named 'id' in the schema
     * - Fallback: 'id'
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
     * Deriva o basePath a partir de um caminho completo de mÃ©todo (ex.: /api/foo/all â†’ /api/foo).
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
        return p; // jÃ¡ Ã© base
    }

    /**
     * Calcula capacidades do recurso verificando a presenÃ§a de operaÃ§Ãµes nos paths do documento OpenAPI.
     */
    private Map<String, Boolean> computeCapabilities(JsonNode rootNode, String basePath) {
        Map<String, Boolean> caps = new java.util.HashMap<>();
        java.util.function.BiFunction<String, String, Boolean> hasOp = (path, op) -> {
            JsonNode node = rootNode.path(PATHS).path(path);
            return node != null && !node.isMissingNode() && node.has(op);
        };

        String p = basePath;
        caps.put("create", hasOp.apply(p, "post"));
        caps.put("update", hasOp.apply(p + "/{id}", "put"));
        caps.put("delete", hasOp.apply(p + "/{id}", "delete") || hasOp.apply(p + "/batch", "delete"));
        caps.put("options", hasOp.apply(p + "/options/filter", "post") || hasOp.apply(p + "/options/by-ids", "get"));
        caps.put("optionSources", hasOp.apply(p + "/option-sources/{sourceKey}/options/filter", "post")
                || hasOp.apply(p + "/option-sources/{sourceKey}/options/by-ids", "get"));
        caps.put("byId", hasOp.apply(p + "/{id}", "get"));
        caps.put("all", hasOp.apply(p + "/all", "get"));
        caps.put("filter", hasOp.apply(p + "/filter", "post"));
        caps.put("cursor", hasOp.apply(p + "/filter/cursor", "post"));
        caps.put("statsGroupBy", hasOp.apply(p + "/stats/group-by", "post"));
        caps.put("statsTimeSeries", hasOp.apply(p + "/stats/timeseries", "post"));
        caps.put("statsDistribution", hasOp.apply(p + "/stats/distribution", "post"));
        return caps;
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
     * Returns true if schemaMap.properties contains the given property name.
     */
    @SuppressWarnings("unchecked")
    private boolean hasSchemaProperty(Map<String, Object> schemaMap, String prop) {
        if (schemaMap == null || prop == null) return false;
        Object propsObj = schemaMap.get("properties");
        if (!(propsObj instanceof Map)) return false;
        return ((Map<String, Object>) propsObj).containsKey(prop);
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
     * Tenta extrair o schema do FilterDTO a partir de um schema inline de request que encapsula propriedades como
     * { filterDTO: { $ref: ... }, pageable: { ... } }.
     *
     * EstratÃ©gia:
     * - Se houver propriedade chamada 'filterDTO' com $ref, resolve esse schema.
     * - Caso contrÃ¡rio, procura a primeira propriedade com $ref cujo nome do schema termine com 'FilterDTO'.
     * - Em Ãºltimo caso, retorna null para manter o inline original.
     */
    private JsonNode tryExtractFilterSchemaFromInline(JsonNode inlineSchema, JsonNode allSchemas) {
        if (inlineSchema == null || inlineSchema.isMissingNode()) return null;
        JsonNode props = inlineSchema.path(PROPERTIES);
        if (props.isMissingNode() || !props.fieldNames().hasNext()) return null;

        // 1) PreferÃªncia por propriedade explicitamente chamada 'filterDTO'
        JsonNode filterDtoNode = props.path("filterDTO");
        if (!filterDtoNode.isMissingNode()) {
            JsonNode refNode = filterDtoNode.path(REF);
            if (!refNode.isMissingNode()) {
                String refName = extractSchemaNameFromRef(refNode.asText());
                JsonNode resolved = allSchemas.path(refName);
                if (!resolved.isMissingNode()) {
                    LOGGER.info("ExtraÃ­do FilterDTO via propriedade 'filterDTO': {}", refName);
                    return resolved;
                }
            }
        }

        // 2) Caso nÃ£o exista 'filterDTO', procurar qualquer propriedade com $ref que termine com 'FilterDTO'
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
                        LOGGER.info("ExtraÃ­do FilterDTO via heurÃ­stica de sufixo: {}", refName);
                        return resolved;
                    }
                }
            }
        }

        // 3) NÃ£o foi possÃ­vel extrair um FilterDTO especÃ­fico
        return null;
    }

    /**
     * Substitui referÃªncias internas (<code>$ref</code>) em um schema JSON por suas propriedades reais,
     * de forma recursiva, caso seja necessÃ¡rio.
     *
     * @param schemaNode NÃ³ (schema) em que serÃ£o buscadas as referÃªncias para substituiÃ§Ã£o.
     * @param allSchemas NÃ³ contendo todos os schemas para referÃªncia, geralmente em <code>components -> schemas</code>.
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
     * Localiza o schema do corpo de requisiÃ§Ã£o para a operaÃ§Ã£o informada.
     * <p>
     * Caminho esperado no JSON: {@code requestBody -> content -> application/json -> schema -> $ref}
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
     * Localiza o responseSchema na documentaÃ§Ã£o OpenAPI, tentando vÃ¡rias estratÃ©gias
     */
    private String findResponseSchema(JsonNode pathsNode, JsonNode rootNode, String operation, String decodedPath) {
        // 1. Primeiro tenta encontrar no nÃ³ x-ui (abordagem atual)
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
                // Verificar se Ã© RestApiResponseTestDTO ou RestApiResponseListTestDTO
                if (wrapperSchemaName.startsWith("RestApiResponse")) {
                    // Encontrar o tipo genÃ©rico dentro do RestApiResponse
                    String realTypeName = extractRealTypeFromRestApiResponse(wrapperSchema, wrapperSchemaName);
                    if (realTypeName != null) {
                        LOGGER.info("Tipo real extraÃ­do de {}: {}", wrapperSchemaName, realTypeName);
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
            // Se o Ãºltimo segmento do path for "list", podemos inferir que o retorno Ã© uma lista
            // de algum tipo, provavelmente relacionado ao penÃºltimo segmento
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

        LOGGER.warn("NÃ£o foi possÃ­vel encontrar um responseSchema para {}", decodedPath);
        return null;
    }

    /**
     * Extrai o tipo real contido dentro de um RestApiResponse ou coleÃ§Ã£o
     */
    private String extractRealTypeFromRestApiResponse(JsonNode wrapperSchema, String wrapperSchemaName) {
        // AnÃ¡lise do nome para casos comuns como "RestApiResponseTestDTO" ou "RestApiResponseListTestDTO"
        if (wrapperSchemaName.startsWith("RestApiResponse")) {
            String remaining = wrapperSchemaName.substring("RestApiResponse".length());

            // Verifica se Ã© uma lista (RestApiResponseListXXX)
            if (remaining.startsWith("List")) {
                String typeName = remaining.substring("List".length());
                return typeName; // Retorna o tipo contido na lista (ex: "TestDTO")
            } else {
                return remaining; // Retorna o tipo direto (ex: "TestDTO")
            }
        }

        // Se a anÃ¡lise pelo nome nÃ£o funcionar, tenta analisar a estrutura do schema
        // Especificamente, buscamos a propriedade "data" do RestApiResponse
        JsonNode dataSchema = wrapperSchema.path("properties").path("data").path("schema");

        // Verifica se data Ã© um array
        if (dataSchema.has("type") && "array".equals(dataSchema.path("type").asText()) && dataSchema.has("items") && dataSchema.path("items").has("$ref")) {
            // Ã‰ um array, extrai o tipo dos items
            return extractSchemaNameFromRef(dataSchema.path("items").path("$ref").asText());
        }
        // Se data tem referÃªncia direta
        else if (dataSchema.has("$ref")) {
            return extractSchemaNameFromRef(dataSchema.path("$ref").asText());
        }

        // Segunda tentativa: olhar propriedades do schema wrapper
        JsonNode properties = wrapperSchema.path("properties");
        if (!properties.isMissingNode()) {
            JsonNode dataProperty = properties.path("data");

            // Verifica se data Ã© um objeto ou array
            if (!dataProperty.isMissingNode()) {
                // Se data Ã© um array
                if (dataProperty.has("type") && "array".equals(dataProperty.path("type").asText())) {
                    // Verifica se o array tem referÃªncia para o tipo dos itens
                    if (dataProperty.has("items") && dataProperty.path("items").has("$ref")) {
                        String itemRef = dataProperty.path("items").path("$ref").asText();
                        return extractSchemaNameFromRef(itemRef);
                    }
                }
                // Se data tem referÃªncia direta
                else if (dataProperty.has("$ref")) {
                    return extractSchemaNameFromRef(dataProperty.path("$ref").asText());
                }
            }
        }

        // NÃ£o conseguiu extrair o tipo
        return null;
    }

    /**
     * Extrai o nome do schema de uma referÃªncia ($ref)
     */
    private String extractSchemaNameFromRef(String ref) {
        return ref.substring(ref.lastIndexOf('/') + 1);
    }

    // ------------------------------------------------------------------------
    // MÃ©todos de resoluÃ§Ã£o automÃ¡tica de grupos e cache
    // ------------------------------------------------------------------------
    
    /**
     * <h3>ðŸŽ¯ MÃ©todo Chave - ResoluÃ§Ã£o AutomÃ¡tica via EstratÃ©gia Dupla</h3>
     * <p>Este Ã© o coraÃ§Ã£o da funcionalidade de resoluÃ§Ã£o automÃ¡tica. Elimina a necessidade 
     * do parÃ¢metro 'document' manual, detectando automaticamente qual grupo OpenAPI usar 
     * baseado na estratÃ©gia dupla implementada pelo DynamicSwaggerConfig.</p>
     * 
     * <h4>ðŸ” EstratÃ©gias de ResoluÃ§Ã£o (ordem de prioridade):</h4>
     * <ol>
     *   <li><strong>ðŸ¤– OpenApiGroupResolver:</strong> Usa algoritmo "best match" com grupos da estratÃ©gia dupla</li>
     *   <li><strong>ðŸ“ DerivaÃ§Ã£o do Path:</strong> Extrai padrÃ£o para gerar nome do grupo individual</li>
     *   <li><strong>ðŸŽ¯ Primeiro Segmento:</strong> Usa primeiro segmento significativo se disponÃ­vel</li>
     *   <li><strong>ðŸ›¡ï¸ Fallback:</strong> "application" como Ãºltimo recurso</li>
     * </ol>
     * 
     * <h4>ðŸ“Š Exemplos de ResoluÃ§Ã£o com EstratÃ©gia Dupla:</h4>
     * <pre>
     * // âœ… Grupos Individuais Ultra-EspecÃ­ficos (CRUDs)
     * "/api/human-resources/eventos-folha/all"     â†’ "api-human-resources-eventos-folha" (~3KB)
     * "/api/human-resources/funcionarios/123"     â†’ "api-human-resources-funcionarios" (~3KB)  
     * "/api/human-resources/departamentos/filter" â†’ "api-human-resources-departamentos" (~3KB)
     * 
     * // ðŸ·ï¸ Grupos Agregados por Contexto (@ApiGroup)
     * "/api/human-resources/bulk/funcionarios"    â†’ "recursos-humanos-bulk" (~50KB)
     * "/api/custom/reports/summary"               â†’ "relatorios" (~30KB)
     * 
     * // ðŸ›¡ï¸ Fallbacks
     * "/funcionarios"                             â†’ "funcionarios" (derivaÃ§Ã£o)
     * ""                                          â†’ "application" (Ãºltimo recurso)
     * </pre>
     * 
     * <h4>ðŸ”— IntegraÃ§Ã£o Perfeita com DynamicSwaggerConfig:</h4>
     * <p>Os nomes resolvidos aqui correspondem exatamente aos grupos registrados 
     * pela estratÃ©gia dupla do DynamicSwaggerConfig:</p>
     * <ul>
     *   <li><strong>Grupos Individuais:</strong> Nomes baseados em paths completos de AbstractCrudController</li>
     *   <li><strong>Grupos Agregados:</strong> Nomes customizados via @ApiGroup de qualquer controller</li>
     * </ul>
     * 
     * <h4>ðŸš€ Performance Resultante:</h4>
     * <ul>
     *   <li><strong>MÃ¡xima otimizaÃ§Ã£o:</strong> Sempre resolve para o grupo mais especÃ­fico disponÃ­vel</li>
     *   <li><strong>Cache eficiente:</strong> Documentos pequenos sÃ£o cacheados mais rapidamente</li>
     *   <li><strong>Flexibilidade total:</strong> Funciona com ambas as estratÃ©gias automaticamente</li>
     * </ul>
     * 
     * @param path o path da requisiÃ§Ã£o (ex: "/api/human-resources/funcionarios/all")
     * @return o nome do grupo resolvido para buscar documento OpenAPI ultra-otimizado
     */
    /**
     * <h3>ðŸ—„ï¸ Cache Inteligente de Documentos OpenAPI</h3>
     * <p>Implementa cache otimizado com estratÃ©gia de fallback para garantir alta performance
     * e disponibilidade dos documentos OpenAPI.</p>
     * 
     * <h4>ðŸŽ¯ EstratÃ©gia de Busca (ordem de prioridade):</h4>
     * <ol>
     *   <li><strong>ðŸ’¾ Cache Hit:</strong> Retorna documento cacheado instantaneamente</li>
     *   <li><strong>ðŸŽ¯ Grupo EspecÃ­fico:</strong> Busca /v3/api-docs/{grupo} (~14KB)</li>
     *   <li><strong>ðŸ›¡ï¸ Fallback Completo:</strong> Busca /v3/api-docs (~500KB) se grupo falhar</li>
     * </ol>
     * 
     * <h4>ðŸ“Š OtimizaÃ§Ã£o de Performance:</h4>
     * <ul>
     *   <li><strong>97% menor:</strong> Documento especÃ­fico vs completo</li>
     *   <li><strong>Cache persistente:</strong> ConcurrentHashMap thread-safe</li>
     *   <li><strong>ComputaÃ§Ã£o lazy:</strong> computeIfAbsent() para threading otimizada</li>
     * </ul>
     * 
     * <h4>ðŸ”„ Exemplo de ExecuÃ§Ã£o:</h4>
     * <pre>
     * 1Âª chamada: groupName="api-human-resources-eventos-folha"
     *   â†’ Cache miss â†’ Busca /v3/api-docs/api-human-resources-eventos-folha 
     *   â†’ Sucesso: 14KB cacheado
     * 
     * 2Âª chamada: mesmo groupName
     *   â†’ Cache hit â†’ Retorna 14KB instantaneamente
     * 
     * 3Âª chamada: groupName="grupo-inexistente"  
     *   â†’ Cache miss â†’ Tentativa /v3/api-docs/grupo-inexistente
     *   â†’ Falha â†’ Fallback /v3/api-docs â†’ 500KB cacheado
     * </pre>
     * 
     * @param groupName o nome do grupo para buscar o documento (ex: "api-human-resources-funcionarios")
     * @return o documento JSON do OpenAPI especÃ­fico do grupo
     * @throws IllegalStateException se nÃ£o conseguir obter nenhum documento (cenÃ¡rio extremo)
     */
    private JsonNode getDocumentForGroup(String groupName) {
        return openApiDocumentService.getDocumentForGroup(groupName);
    }

    /**
     * <h3>Limpeza Manual do Cache</h3>
     * <p>Metodo utilitario para limpar os caches estruturais de documentos e hashes.</p>
     */
    public void clearDocumentCache() {
        openApiDocumentService.clearCaches();
    }

}

