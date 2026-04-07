package org.praxisplatform.uischema.controller.docs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.praxisplatform.uischema.openapi.CanonicalOperationResolver;
import org.praxisplatform.uischema.openapi.OpenApiDocumentService;
import org.praxisplatform.uischema.schema.SchemaReferenceResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Exporta um catalogo enxuto de dominios e endpoints do OpenAPI.
 *
 * <p>
 * Esta superficie foi desenhada para consumo operacional e semantico por clientes que nao precisam
 * do documento OpenAPI completo, como pipelines de RAG, indexadores documentais e discovery
 * incremental. O controller resume operacoes, tags, parametros, exemplos e links para os schemas
 * filtrados correspondentes.
 * </p>
 *
 * <p>
 * A resolucao de grupo, documento OpenAPI e schema links canonicos e delegada a
 * {@link OpenApiDocumentService}, {@link CanonicalOperationResolver} e
 * {@link SchemaReferenceResolver}.
 * </p>
 *
 * <p>
 * O catalogo existe como superficie derivada para consulta, indexacao e navegacao operacional.
 * Ele nao substitui o documento OpenAPI completo nem o endpoint estrutural
 * {@code /schemas/filtered}; apenas resume e conecta essas fontes canonicas.
 * </p>
 */
@RestController
@RequestMapping("/schemas/catalog")
public class DomainCatalogController {

    private static final Logger LOGGER = LoggerFactory.getLogger(DomainCatalogController.class);
    private static final Set<String> HTTP_METHODS = Set.of(
            "GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"
    );

    @Value("${praxis.catalog.exclude-paths:/api/praxis/config/ui}")
    private String excludedPathsRaw;

    private Set<String> excludedPaths = Set.of();

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

    @PostConstruct
    void initExcludedPaths() {
        if (!StringUtils.hasText(excludedPathsRaw)) {
            excludedPaths = Set.of();
            return;
        }
        excludedPaths = Arrays.stream(excludedPathsRaw.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .map(this::normalizePath)
                .collect(Collectors.toSet());
    }

    /**
     * Gera um catalogo resumido das operacoes publicadas em um grupo OpenAPI.
     *
     * <p>
     * Cada item do catalogo inclui path, metodo HTTP, resumo, descricao, {@code operationId},
     * parametros, exemplos e {@code schemaLinks} canonicos de request/response quando existirem.
     * Esses links passam pelo {@link SchemaReferenceResolver} e ficam alinhados ao mesmo contrato
     * estrutural usado por {@code /schemas/filtered}.
     * </p>
     *
     * <p>
     * Quando {@code group} nao e informado, o controller pode derivar o grupo a partir de
     * {@code path}. Se nenhum dos dois estiver presente, a resposta cai no grupo default
     * {@code application}, coerente com a organizacao documental atual do starter.
     * </p>
     */
    @GetMapping
    public ResponseEntity<CatalogResponse> getCatalog(@RequestParam(name = "group", required = false) String group,
                                                      @RequestParam(name = "path", required = false) String pathFilter,
                                                      @RequestParam(name = "operation", required = false) String operationFilter) {
        String groupToUse = resolveGroup(group, pathFilter);
        JsonNode doc = fetchOpenApiDocument(groupToUse);

        JsonNode pathsNode = doc.path("paths");
        JsonNode components = doc.path("components").path("schemas");
        List<EndpointSummary> endpoints = new ArrayList<>();

        if (!pathsNode.isObject()) {
            LOGGER.warn("OpenAPI document has no paths object; returning empty catalog");
            return ResponseEntity.ok(new CatalogResponse(groupToUse, endpoints));
        }

        Iterator<Map.Entry<String, JsonNode>> pathIt = pathsNode.fields();
        while (pathIt.hasNext()) {
            Map.Entry<String, JsonNode> pathEntry = pathIt.next();
            String path = pathEntry.getKey();
            JsonNode methodsNode = pathEntry.getValue();

            String normalizedPath = normalizePath(path);
            if (excludedPaths.contains(normalizedPath)) {
                LOGGER.debug("Skipping excluded path from catalog: {}", path);
                continue;
            }
            if (StringUtils.hasText(pathFilter) && !normalizedPath.equals(normalizePath(pathFilter))) {
                continue;
            }

            if (methodsNode == null || !methodsNode.isObject()) {
                continue;
            }

            Iterator<Map.Entry<String, JsonNode>> methodIt = methodsNode.fields();
            while (methodIt.hasNext()) {
                Map.Entry<String, JsonNode> methodEntry = methodIt.next();
                String rawMethod = methodEntry.getKey();
                String method = rawMethod == null ? "" : rawMethod.toUpperCase(Locale.ROOT);
                JsonNode op = methodEntry.getValue();

                if (!HTTP_METHODS.contains(method)) {
                    continue;
                }
                if (StringUtils.hasText(operationFilter)
                        && !method.equalsIgnoreCase(operationFilter)) {
                    continue;
                }

                if (op == null || !op.isObject()) {
                    LOGGER.debug("Skipping non-object operation: {} {}", method, path);
                    continue;
                }

                try {
                    SchemaRef request = extractRequestSchema(op, components);
                    SchemaRef response = extractResponseSchema(op, components);
                    String rawSummary = op.path("summary").asText(null);
                    String rawDescription = op.path("description").asText(null);
                    String resourceLabel = inferResourceLabel(path);
                    String summary = firstNonBlank(rawSummary, rawDescription, buildFallbackSummary(method, path, resourceLabel));
                    String description = StringUtils.hasText(rawDescription)
                            ? rawDescription
                            : buildFallbackDescription(summary, path, resourceLabel);

                    EndpointSummary endpointSummary = new EndpointSummary(
                            path,
                            method,
                            toStringList(op.path("tags")),
                            summary,
                            description,
                            op.path("operationId").asText(null),
                            request,
                            response,
                            extractParameters(op),
                            extractOperationExamples(op),
                            buildSchemaLinks(path, rawMethod, request, response)
                    );

                    endpoints.add(endpointSummary);
                } catch (Exception ex) {
                    LOGGER.warn("Skipping operation {} {} due to error: {}", method, path, ex.getMessage());
                }
            }
        }

        CatalogResponse response = new CatalogResponse(groupToUse, endpoints);

        return ResponseEntity.ok(response);
    }

    private String resolveGroup(String group, String pathFilter) {
        if (StringUtils.hasText(group)) {
            return group;
        }
        if (StringUtils.hasText(pathFilter)) {
            return openApiDocumentService.resolveGroupFromPath(pathFilter);
        }
        return "application";
    }

    private JsonNode fetchOpenApiDocument(String group) {
        LOGGER.info("Fetching OpenAPI doc for group {}", group);
        return openApiDocumentService.getDocumentForGroup(group);
    }

    private List<String> toStringList(JsonNode node) {
        List<String> result = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(n -> result.add(n.asText()));
        }
        return result;
    }

    private Map<String, Map<String, Object>> extractOperationExamples(JsonNode operationNode) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();

        Map<String, Object> requestExamples = extractExamplesFromContentNode(
                openApiDocsSupport.selectPreferredContentNode(operationNode.path("requestBody").path("content"))
        );
        if (!requestExamples.isEmpty()) {
            result.put("request", requestExamples);
        }

        JsonNode responses = operationNode.path("responses");
        JsonNode responseContent = responses.path("200").path("content");
        if (responseContent.isMissingNode()) {
            responseContent = responses.path("201").path("content");
        }
        Map<String, Object> responseExamples = extractExamplesFromContentNode(
                openApiDocsSupport.selectPreferredContentNode(responseContent)
        );
        if (!responseExamples.isEmpty()) {
            result.put("response", responseExamples);
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
            Iterator<Map.Entry<String, JsonNode>> fields = examplesNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                JsonNode exampleNode = entry.getValue();
                Map<String, Object> exampleMeta = new LinkedHashMap<>();
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

    private SchemaLinks buildSchemaLinks(String path, String operation, SchemaRef requestSchema, SchemaRef responseSchema) {
        var operationRef = canonicalOperationResolver.resolve(path, operation);
        String requestLink = requestSchema != null
                ? schemaReferenceResolver.requestSchema(operationRef).url()
                : null;
        String responseLink = responseSchema != null
                ? schemaReferenceResolver.responseSchema(operationRef).url()
                : null;
        return new SchemaLinks(
                requestLink,
                responseLink
        );
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private String inferResourceLabel(String path) {
        if (!StringUtils.hasText(path)) {
            return "recurso";
        }
        String trimmed = normalizePath(path);
        String[] parts = trimmed.split("/");
        for (int i = parts.length - 1; i >= 0; i--) {
            String part = parts[i];
            if (!StringUtils.hasText(part)) {
                continue;
            }
            if (part.startsWith("{") && part.endsWith("}")) {
                continue;
            }
            String cleaned = part.replace('-', ' ').replace('_', ' ').trim();
            if (!StringUtils.hasText(cleaned)) {
                continue;
            }
            return cleaned.substring(0, 1).toUpperCase(Locale.ROOT) + cleaned.substring(1);
        }
        return "recurso";
    }

    private String normalizePath(String path) {
        if (!StringUtils.hasText(path)) {
            return "";
        }
        String trimmed = path.trim();
        if (trimmed.length() > 1 && trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private String buildFallbackSummary(String method, String path, String resourceLabel) {
        String action = resolveActionVerb(method, path);
        return action + " " + resourceLabel;
    }

    private String buildFallbackDescription(String summary, String path, String resourceLabel) {
        if (StringUtils.hasText(summary)) {
            return "Endpoint para " + summary.toLowerCase(Locale.ROOT);
        }
        return "Endpoint do recurso " + resourceLabel.toLowerCase(Locale.ROOT);
    }

    private String resolveActionVerb(String method, String path) {
        String normalizedMethod = method != null ? method.toUpperCase(Locale.ROOT) : "GET";
        String normalizedPath = path != null ? path.toLowerCase(Locale.ROOT) : "";
        if (normalizedPath.endsWith("/options/filter")) {
            return "Listar opcoes de";
        }
        if (normalizedPath.endsWith("/filter")) {
            return "Filtrar";
        }
        boolean isItemPath = normalizedPath.contains("/{");
        return switch (normalizedMethod) {
            case "POST" -> isItemPath ? "Criar" : "Criar";
            case "PUT", "PATCH" -> "Atualizar";
            case "DELETE" -> "Remover";
            default -> isItemPath ? "Detalhar" : "Listar";
        };
    }

    private SchemaRef extractRequestSchema(JsonNode op, JsonNode components) {
        JsonNode schema = openApiDocsSupport.selectPreferredContentNode(
                op.path("requestBody").path("content")
        ).path("schema");
        if (schema.isMissingNode()) return null;
        if (schema.has("$ref")) {
            String name = extractRefName(schema.path("$ref").asText());
            return new SchemaRef(name, null, openApiDocsSupport.inferMediaType(op.path("requestBody").path("content")), extractFieldsFromRef(name, components), extractRelationsFromRef(name, components));
        }
        return new SchemaRef(null, schema, openApiDocsSupport.inferMediaType(op.path("requestBody").path("content")), extractFields(schema, components), extractRelations(schema, components));
    }

    private SchemaRef extractResponseSchema(JsonNode op, JsonNode components) {
        JsonNode responses = op.path("responses");
        if (responses.isMissingNode()) return null;
        String[] preferred = {"200", "201", "default"};
        for (String code : preferred) {
            JsonNode r = responses.path(code);
            if (!r.isMissingNode()) {
                JsonNode content = r.path("content");
                JsonNode schema = openApiDocsSupport.selectPreferredContentNode(content).path("schema");
                if (schema.isMissingNode()) continue;
                if (schema.has("$ref")) {
                    String name = extractRefName(schema.path("$ref").asText());
                    return new SchemaRef(name, null, openApiDocsSupport.inferMediaType(content), extractFieldsFromRef(name, components), extractRelationsFromRef(name, components));
                }
                return new SchemaRef(null, schema, openApiDocsSupport.inferMediaType(content), extractFields(schema, components), extractRelations(schema, components));
            }
        }
        Iterator<Map.Entry<String, JsonNode>> it = responses.fields();
        while (it.hasNext()) {
            JsonNode r = it.next().getValue();
            JsonNode content = r.path("content");
            JsonNode schema = openApiDocsSupport.selectPreferredContentNode(content).path("schema");
            if (schema.isMissingNode()) continue;
            if (schema.has("$ref")) {
                String name = extractRefName(schema.path("$ref").asText());
                return new SchemaRef(name, null, openApiDocsSupport.inferMediaType(content), extractFieldsFromRef(name, components), extractRelationsFromRef(name, components));
            }
            return new SchemaRef(null, schema, openApiDocsSupport.inferMediaType(content), extractFields(schema, components), extractRelations(schema, components));
        }
        return null;
    }

    private List<FieldSummary> extractFieldsFromRef(String name, JsonNode components) {
        if (name == null) return List.of();
        JsonNode schema = components.path(name);
        if (schema.isMissingNode()) return List.of();
        return extractFields(schema, components);
    }

    private List<RelationSummary> extractRelationsFromRef(String name, JsonNode components) {
        if (name == null) return List.of();
        JsonNode schema = components.path(name);
        if (schema.isMissingNode()) return List.of();
        return extractRelations(schema, components);
    }

    private List<FieldSummary> extractFields(JsonNode schema, JsonNode components) {
        List<FieldSummary> fields = new ArrayList<>();
        if (schema == null || schema.isMissingNode()) {
            return fields;
        }
        if ("array".equals(schema.path("type").asText()) && schema.has("items")) {
            return extractFields(schema.path("items"), components);
        }
        if (!schema.has("properties")) {
            return fields;
        }
        JsonNode props = schema.path("properties");
        JsonNode required = schema.path("required");
        Iterator<Map.Entry<String, JsonNode>> it = props.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> entry = it.next();
            String name = entry.getKey();
            JsonNode prop = entry.getValue();
            boolean isRequired = required.isArray() && required.toString().contains("\"" + name + "\"");
            fields.add(new FieldSummary(
                    name,
                    prop.path("type").asText(null),
                    prop.path("format").asText(null),
                    isRequired,
                    prop.path("description").asText(null),
                    extractEnum(prop),
                    extractNumberConstraint(prop, "minimum"),
                    extractNumberConstraint(prop, "maximum"),
                    extractStringConstraint(prop, "minLength"),
                    extractStringConstraint(prop, "maxLength"),
                    prop.path("pattern").asText(null)
            ));
        }
        return fields;
    }

    private List<RelationSummary> extractRelations(JsonNode schema, JsonNode components) {
        List<RelationSummary> relations = new ArrayList<>();
        if (schema == null || schema.isMissingNode()) {
            return relations;
        }
        if ("array".equals(schema.path("type").asText()) && schema.has("items")) {
            return extractRelations(schema.path("items"), components);
        }
        if (!schema.has("properties")) {
            return relations;
        }
        JsonNode props = schema.path("properties");
        Iterator<Map.Entry<String, JsonNode>> it = props.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> entry = it.next();
            String name = entry.getKey();
            JsonNode prop = entry.getValue();
            RelationSummary rel = buildRelation(name, prop, components);
            if (rel != null) {
                relations.add(rel);
            }
        }
        return relations;
    }

    private RelationSummary buildRelation(String fieldName, JsonNode prop, JsonNode components) {
        if (prop == null) return null;
        String type = prop.path("type").asText(null);
        boolean isArray = "array".equals(type);
        JsonNode targetNode = isArray ? prop.path("items") : prop;

        // Caso 0: x-ui.resource com schemaId/targetSchema
        JsonNode xui = prop.path("x-ui").path("resource");
        if (xui != null && !xui.isMissingNode()) {
            String target = cleanSchemaName(xui.path("schemaId").asText(null));
            if (target == null) {
                target = cleanSchemaName(xui.path("targetSchema").asText(null));
            }
            if (target != null) {
                return new RelationSummary(fieldName, target, isArray ? "one-to-many" : "many-to-one");
            }
        }

        // Caso 1: referencia explicita
        if (targetNode != null && targetNode.has("$ref")) {
            String target = extractRefName(targetNode.path("$ref").asText());
            return new RelationSummary(fieldName, target, isArray ? "one-to-many" : "many-to-one");
        }

        // Caso 2: heuristica *_id ou *_ref com tipo string/integer -> assume FK
        if (fieldName != null && (fieldName.endsWith("_id") || fieldName.endsWith("Id") || fieldName.endsWith("_ref") || fieldName.endsWith("Ref"))) {
            String target = guessTargetFromField(fieldName);
            String cardinality = isArray ? "one-to-many" : "many-to-one";
            return new RelationSummary(fieldName, target, cardinality);
        }

        return null;
    }

    private String guessTargetFromField(String fieldName) {
        String cleaned = fieldName;
        if (cleaned.endsWith("_id")) cleaned = cleaned.substring(0, cleaned.length() - 3);
        if (cleaned.endsWith("_ref")) cleaned = cleaned.substring(0, cleaned.length() - 4);
        if (cleaned.endsWith("Id")) cleaned = cleaned.substring(0, cleaned.length() - 2);
        if (cleaned.endsWith("Ref")) cleaned = cleaned.substring(0, cleaned.length() - 3);
        // normalize camel/snake to schema-like name
        if (cleaned.contains("_")) {
            String[] parts = cleaned.split("_");
            StringBuilder sb = new StringBuilder();
            for (String p : parts) {
                if (p.isEmpty()) continue;
                sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
            }
            cleaned = sb.toString();
        } else if (!cleaned.isEmpty()) {
            cleaned = Character.toUpperCase(cleaned.charAt(0)) + cleaned.substring(1);
        }
        return cleaned;
    }

    private String cleanSchemaName(String raw) {
        if (raw == null || raw.isBlank()) return null;
        // se for path ou id composto, pegar ultima parte apos "/" ou "."
        String cleaned = raw;
        if (cleaned.contains("/")) {
            cleaned = cleaned.substring(cleaned.lastIndexOf('/') + 1);
        }
        if (cleaned.contains(".")) {
            cleaned = cleaned.substring(cleaned.lastIndexOf('.') + 1);
        }
        if (!cleaned.isEmpty()) {
            cleaned = Character.toUpperCase(cleaned.charAt(0)) + cleaned.substring(1);
        }
        return cleaned;
    }

    private List<String> extractEnum(JsonNode prop) {
        if (prop == null || !prop.has("enum")) return List.of();
        List<String> values = new ArrayList<>();
        prop.path("enum").forEach(v -> values.add(v.asText()));
        return values;
    }

    private Integer extractNumberConstraint(JsonNode prop, String key) {
        if (prop == null || !prop.has(key)) return null;
        try {
            return prop.path(key).isNumber() ? prop.path(key).asInt() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private Integer extractStringConstraint(JsonNode prop, String key) {
        if (prop == null || !prop.has(key)) return null;
        try {
            return prop.path(key).isNumber() ? prop.path(key).asInt() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private List<ParameterSummary> extractParameters(JsonNode op) {
        List<ParameterSummary> params = new ArrayList<>();
        JsonNode array = op.path("parameters");
        if (array.isArray()) {
            for (JsonNode p : array) {
                params.add(new ParameterSummary(
                        p.path("name").asText(null),
                        p.path("in").asText(null),
                        p.path("required").asBoolean(false),
                        p.path("schema").path("type").asText(null)
                ));
            }
        }
        return params;
    }

    private String extractRefName(String ref) {
        if (ref == null) return null;
        int idx = ref.lastIndexOf('/');
        return idx >= 0 ? ref.substring(idx + 1) : ref;
    }

    public static class CatalogResponse {
        private final String group;
        private final List<EndpointSummary> endpoints;

        public CatalogResponse(String group, List<EndpointSummary> endpoints) {
            this.group = group;
            this.endpoints = endpoints;
        }

        public String getGroup() {
            return group;
        }

        public List<EndpointSummary> getEndpoints() {
            return endpoints;
        }
    }

    public static class EndpointSummary {
        private final String path;
        private final String method;
        private final List<String> tags;
        private final String summary;
        private final String description;
        private final String operationId;
        private final SchemaRef requestSchema;
        private final SchemaRef responseSchema;
        private final List<ParameterSummary> parameters;
        private final Map<String, Map<String, Object>> operationExamples;
        private final SchemaLinks schemaLinks;

        public EndpointSummary(String path, String method, List<String> tags, String summary, String description,
                               String operationId, SchemaRef requestSchema, SchemaRef responseSchema,
                               List<ParameterSummary> parameters,
                               Map<String, Map<String, Object>> operationExamples,
                               SchemaLinks schemaLinks) {
            this.path = path;
            this.method = method;
            this.tags = tags;
            this.summary = summary;
            this.description = description;
            this.operationId = operationId;
            this.requestSchema = requestSchema;
            this.responseSchema = responseSchema;
            this.parameters = parameters;
            this.operationExamples = operationExamples;
            this.schemaLinks = schemaLinks;
        }

        public String getPath() {
            return path;
        }

        public String getMethod() {
            return method;
        }

        public List<String> getTags() {
            return tags;
        }

        public String getSummary() {
            return summary;
        }

        public String getDescription() {
            return description;
        }

        public String getOperationId() {
            return operationId;
        }

        public SchemaRef getRequestSchema() {
            return requestSchema;
        }

        public SchemaRef getResponseSchema() {
            return responseSchema;
        }

        public List<ParameterSummary> getParameters() {
            return parameters;
        }

        public Map<String, Map<String, Object>> getOperationExamples() {
            return operationExamples;
        }

        public SchemaLinks getSchemaLinks() {
            return schemaLinks;
        }
    }

    public static class SchemaLinks {
        private final String request;
        private final String response;

        public SchemaLinks(String request, String response) {
            this.request = request;
            this.response = response;
        }

        public String getRequest() {
            return request;
        }

        public String getResponse() {
            return response;
        }
    }

    public static class SchemaRef {
        private final String name;
        private final JsonNode inlineSchema;
        private final String mediaType;
        private final List<FieldSummary> fields;
        private final List<RelationSummary> relations;

        public SchemaRef(String name, JsonNode inlineSchema, String mediaType, List<FieldSummary> fields,
                         List<RelationSummary> relations) {
            this.name = name;
            this.inlineSchema = inlineSchema;
            this.mediaType = mediaType;
            this.fields = fields;
            this.relations = relations;
        }

        public String getName() {
            return name;
        }

        public JsonNode getInlineSchema() {
            return inlineSchema;
        }

        public String getMediaType() {
            return mediaType;
        }

        public List<FieldSummary> getFields() {
            return fields;
        }

        public List<RelationSummary> getRelations() {
            return relations;
        }
    }

    public static class ParameterSummary {
        private final String name;
        private final String in;
        private final boolean required;
        private final String type;

        public ParameterSummary(String name, String in, boolean required, String type) {
            this.name = name;
            this.in = in;
            this.required = required;
            this.type = type;
        }

        public String getName() {
            return name;
        }

        public String getIn() {
            return in;
        }

        public boolean isRequired() {
            return required;
        }

        public String getType() {
            return type;
        }
    }

    public static class FieldSummary {
        private final String name;
        private final String type;
        private final String format;
        private final boolean required;
        private final String description;
        private final List<String> enumValues;
        private final Integer minimum;
        private final Integer maximum;
        private final Integer minLength;
        private final Integer maxLength;
        private final String pattern;

        public FieldSummary(String name, String type, String format, boolean required, String description,
                            List<String> enumValues, Integer minimum, Integer maximum,
                            Integer minLength, Integer maxLength, String pattern) {
            this.name = name;
            this.type = type;
            this.format = format;
            this.required = required;
            this.description = description;
            this.enumValues = enumValues;
            this.minimum = minimum;
            this.maximum = maximum;
            this.minLength = minLength;
            this.maxLength = maxLength;
            this.pattern = pattern;
        }

        public String getName() {
            return name;
        }

        public String getType() {
            return type;
        }

        public String getFormat() {
            return format;
        }

        public boolean isRequired() {
            return required;
        }

        public String getDescription() {
            return description;
        }

        public List<String> getEnumValues() {
            return enumValues;
        }

        public Integer getMinimum() {
            return minimum;
        }

        public Integer getMaximum() {
            return maximum;
        }

        public Integer getMinLength() {
            return minLength;
        }

        public Integer getMaxLength() {
            return maxLength;
        }

        public String getPattern() {
            return pattern;
        }
    }

    public static class RelationSummary {
        private final String field;
        private final String targetSchema;
        private final String cardinality;

        public RelationSummary(String field, String targetSchema, String cardinality) {
            this.field = field;
            this.targetSchema = targetSchema;
            this.cardinality = cardinality;
        }

        public String getField() {
            return field;
        }

        public String getTargetSchema() {
            return targetSchema;
        }

        public String getCardinality() {
            return cardinality;
        }
    }
}
