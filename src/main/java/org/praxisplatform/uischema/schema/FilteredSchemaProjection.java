package org.praxisplatform.uischema.schema;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import org.praxisplatform.uischema.openapi.OpenApiContentSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

/**
 * Shared implementation of the filtered endpoint's schema selection and structural dimensions.
 * Operates exclusively on the supplied OpenAPI document; never fetches, caches, authorizes or
 * routes user intent. Existing documentary wrapper/filter fallbacks are preserved here.
 */
public final class FilteredSchemaProjection {
    private static final Logger LOGGER = LoggerFactory.getLogger(FilteredSchemaProjection.class);
    private static final String PATHS = "paths", COMPONENTS = "components", SCHEMAS = "schemas";
    private static final String REF = "$ref", X_UI = "x-ui", RESPONSE_SCHEMA = "responseSchema", PROPERTIES = "properties";
    private final ObjectMapper objectMapper;

    public FilteredSchemaProjection(ObjectMapper objectMapper) {
        this.objectMapper = java.util.Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /** Selects exactly the component or inline request that /schemas/filtered will materialize. */
    public Selection select(JsonNode rootNode, String path, String method, String schemaType) {
        JsonNode operation = rootNode.path(PATHS).path(path).path(method.toLowerCase(java.util.Locale.ROOT));
        if (operation.isMissingNode()) throw missing("The specified path or operation was not found in the documentation.");
        String name = null;
        JsonNode direct = null;
        if ("request".equalsIgnoreCase(schemaType)) {
            JsonNode body = OpenApiContentSupport.preferredContent(operation.path("requestBody").path("content")).path("schema");
            if (!body.isMissingNode()) {
                if (body.has(REF)) name = extractSchemaNameFromRef(body.path(REF).asText());
                else direct = body;
            }
        } else if ("response".equalsIgnoreCase(schemaType)) {
            name = findResponseSchema(operation, rootNode, method, path);
        } else throw new IllegalArgumentException("Parameter 'schemaType' must be 'response' or 'request'.");
        if ((name == null || name.isEmpty()) && direct == null)
            throw missing("The requested schema was not found or is not defined for the specified path and operation.");
        JsonNode allSchemas = rootNode.path(COMPONENTS).path(SCHEMAS);
        JsonNode schema;
        if (direct != null) {
            JsonNode extracted = tryExtractFilterSchemaFromInline(direct, allSchemas);
            schema = extracted != null ? extracted : direct;
        } else {
            schema = allSchemas.path(name);
            if (schema.isMissingNode()) throw missing("The specified component schema was not found in the documentation.");
        }
        return new Selection(name, schema.deepCopy());
    }

    /** Resolves a concrete UI reference and captures its selection evidence from this document. */
    public Resolved resolve(JsonNode document, org.praxisplatform.uischema.openapi.CanonicalOperationRef operation,
            String schemaType, SchemaReferenceResolver references,
            org.praxisplatform.uischema.capability.CanonicalCapabilityResolver capabilities,
            String explicitIdField, Boolean explicitReadOnly) {
        JsonNode operationNode = document.path(PATHS).path(operation.path())
                .path(operation.method().toLowerCase(java.util.Locale.ROOT));
        if ("response".equalsIgnoreCase(schemaType) && findDeclaredResponseSchema(operationNode, document) == null)
            throw missing("Bulk UI references require x-ui.responseSchema or a selectable 200/201 response");
        Selection selected = select(document, operation.path(), operation.method(), schemaType);
        if (!selected.schema().isObject()) throw missing("Filtered bulk schemas must be materializable objects");
        String basePath = deriveBasePathFrom(operation.path());
        Map<String, Object> schema = objectMapper.convertValue(selected.schema(), new TypeReference<>() { });
        Map<String, Boolean> caps = capabilities.resolve(document, basePath);
        String idField = resolveIdField(explicitIdField, schema, document, basePath, schemaType);
        boolean readOnly = readOnly(explicitReadOnly, caps);
        CanonicalSchemaRef reference = references.resolve(operation.path(), operation.method(), schemaType,
                false, null, null, idField, readOnly);
        var evidence = objectMapper.createObjectNode();
        evidence.put("schemaName", selected.schemaName());
        evidence.set("schema", selected.schema());
        evidence.set("capabilities", objectMapper.valueToTree(caps));
        // Operation x-ui may select a component different from the raw transport schema.
        JsonNode operationUi = operationNode.get(X_UI);
        if (operationUi != null) evidence.set("operationUi", operationUi.deepCopy());
        return new Resolved(reference, evidence);
    }

    /** Immutable implementation evidence, separate from the raw HTTP schema contract. */
    public record Resolved(CanonicalSchemaRef reference, JsonNode evidence) {
        public Resolved { evidence = evidence.deepCopy(); }
        @Override public JsonNode evidence() { return evidence.deepCopy(); }
    }

    /** The endpoint and snapshot composition use this exact read-only default. */
    public static boolean readOnly(Boolean explicit, Map<String, Boolean> capabilities) {
        return explicit != null ? explicit : !(Boolean.TRUE.equals(capabilities.get("create"))
                || Boolean.TRUE.equals(capabilities.get("update")) || Boolean.TRUE.equals(capabilities.get("delete")));
    }

    /** Implementation value; not a new HTTP schema contract. */
    public record Selection(String schemaName, JsonNode schema) {
        public Selection { schema = schema.deepCopy(); }
        @Override public JsonNode schema() { return schema.deepCopy(); }
    }

    private static ResponseStatusException missing(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }

    public String resolveIdField(String requestedIdField,
                                  Map<String, Object> schemaMap,
                                  JsonNode rootNode,
                                  String basePath,
                                  String schemaType) {
        try {
            if (requestedIdField != null && !requestedIdField.isBlank()) {
                return requestedIdField;
            }
            String canonicalIdField = resolveCanonicalIdFieldFromResourceResponse(rootNode, basePath);
            boolean responseSchema = "response".equalsIgnoreCase(schemaType);
            if (canonicalIdField != null
                    && !canonicalIdField.isBlank()
                    && (!responseSchema || hasSchemaProperty(schemaMap, canonicalIdField))) {
                return canonicalIdField;
            }
            if (hasSchemaProperty(schemaMap, "id")) {
                return "id";
            }
            if (responseSchema) {
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
            if (canonicalIdField != null && !canonicalIdField.isBlank()) {
                return canonicalIdField;
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
        String requiredIdentifier = resolveSingleRequiredIdentifierField(resourceSchemaMap);
        if (requiredIdentifier != null) {
            return requiredIdentifier;
        }
        return null;
    }

    private String resolveSingleRequiredIdentifierField(Map<String, Object> schemaMap) {
        Object requiredValue = schemaMap.get("required");
        if (!(requiredValue instanceof List<?> requiredFields) || requiredFields.size() != 1) {
            return null;
        }

        Object candidateValue = requiredFields.get(0);
        if (!(candidateValue instanceof String candidate) || candidate.isBlank()) {
            return null;
        }
        if (!hasSchemaProperty(schemaMap, candidate)) {
            return null;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schemaMap.get(PROPERTIES);
        if (props == null || !isScalarIdentifierProperty(props.get(candidate))) {
            return null;
        }
        return candidate;
    }

    private boolean isScalarIdentifierProperty(Object propertyValue) {
        if (!(propertyValue instanceof Map<?, ?> property)) {
            return false;
        }
        Object typeValue = property.get("type");
        if (!(typeValue instanceof String type)) {
            return false;
        }
        return "integer".equals(type) || "number".equals(type) || "string".equals(type);
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

    public String deriveBasePathFrom(String fullPath) {
        if (fullPath == null || fullPath.isBlank()) return fullPath;
        String p = fullPath;
        // normaliza barras
        p = p.replaceAll("/+", "/");
        if (p.endsWith("/") && p.length() > 1) p = p.substring(0, p.length() - 1);

        String[] suffixes = new String[]{
                "/stats/distribution",
                "/stats/timeseries",
                "/stats/comparison",
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

    private String findDeclaredResponseSchema(JsonNode pathsNode, JsonNode rootNode) {
        // 1. Primeiro tenta encontrar no no x-ui (abordagem atual)
        JsonNode xUiNode = pathsNode.path(X_UI);
        if (!xUiNode.isMissingNode() && !xUiNode.path(RESPONSE_SCHEMA).isMissingNode()) {
            String responseSchema = xUiNode.path(RESPONSE_SCHEMA).asText();
            LOGGER.info("Response schema encontrado em x-ui: {}", responseSchema);
            return responseSchema;
        }

        // 2. Tenta extrair do schema de resposta 200 OK
        JsonNode responses = pathsNode.path("responses");
        JsonNode okResponse = OpenApiContentSupport.preferredContent(
                responses.path("200").path("content")
        ).path("schema");
        if (okResponse.isMissingNode()) {
            okResponse = OpenApiContentSupport.preferredContent(
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

        return null;
    }

    public String findResponseSchema(JsonNode pathsNode, JsonNode rootNode, String operation, String decodedPath) {
        String declared = findDeclaredResponseSchema(pathsNode, rootNode);
        if (declared != null) return declared;

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

    public String extractSchemaNameFromRef(String ref) {
        return ref.substring(ref.lastIndexOf('/') + 1);
    }

    public boolean hasSchemaProperty(Map<String, Object> schemaMap, String prop) {
        if (schemaMap == null || prop == null) return false;
        Object propsObj = schemaMap.get("properties");
        if (!(propsObj instanceof Map)) return false;
        return ((Map<String, Object>) propsObj).containsKey(prop);
    }

    public JsonNode tryExtractFilterSchemaFromInline(JsonNode inlineSchema, JsonNode allSchemas) {
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

}
