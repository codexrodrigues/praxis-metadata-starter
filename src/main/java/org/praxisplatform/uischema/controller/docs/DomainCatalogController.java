package org.praxisplatform.uischema.controller.docs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.praxisplatform.uischema.util.OpenApiGroupResolver;
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
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Endpoint que exporta um catálogo enxuto de domínios/endpoints do OpenAPI,
 * pensado para RAG. Inclui campos principais do schema de request/response.
 */
@RestController
@RequestMapping("/schemas/catalog")
public class DomainCatalogController {

    private static final Logger LOGGER = LoggerFactory.getLogger(DomainCatalogController.class);

    @Value("${springdoc.api-docs.path:/v3/api-docs}")
    private String openApiBasePath;

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired(required = false)
    private OpenApiGroupResolver openApiGroupResolver;

    @GetMapping
    public ResponseEntity<CatalogResponse> getCatalog(@RequestParam(name = "group", required = false) String group) {
        String groupToUse = resolveGroup(group);
        JsonNode doc = fetchOpenApiDocument(groupToUse);

        JsonNode pathsNode = doc.path("paths");
        JsonNode components = doc.path("components").path("schemas");
        List<EndpointSummary> endpoints = new ArrayList<>();

        Iterator<Map.Entry<String, JsonNode>> pathIt = pathsNode.fields();
        while (pathIt.hasNext()) {
            Map.Entry<String, JsonNode> pathEntry = pathIt.next();
            String path = pathEntry.getKey();
            JsonNode methodsNode = pathEntry.getValue();

            Iterator<Map.Entry<String, JsonNode>> methodIt = methodsNode.fields();
            while (methodIt.hasNext()) {
                Map.Entry<String, JsonNode> methodEntry = methodIt.next();
                String method = methodEntry.getKey();
                JsonNode op = methodEntry.getValue();

                SchemaRef request = extractRequestSchema(op, components);
                SchemaRef response = extractResponseSchema(op, components);

                EndpointSummary summary = new EndpointSummary(
                        path,
                        method.toUpperCase(),
                        toStringList(op.path("tags")),
                        op.path("summary").asText(null),
                        op.path("description").asText(null),
                        op.path("operationId").asText(null),
                        request,
                        response,
                        extractParameters(op)
                );

                endpoints.add(summary);
            }
        }

        CatalogResponse response = new CatalogResponse(groupToUse, endpoints);

        return ResponseEntity.ok(response);
    }

    private String resolveGroup(String group) {
        if (StringUtils.hasText(group)) {
            return group;
        }
        // Tenta resolver dinamicamente com OpenApiGroupResolver, caindo para null (usa /v3/api-docs)
        if (openApiGroupResolver != null) {
            String resolved = openApiGroupResolver.resolveGroup("/api");
            if (StringUtils.hasText(resolved)) {
                return resolved;
            }
        }
        return null;
    }

    private JsonNode fetchOpenApiDocument(String group) {
        String base = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path(openApiBasePath)
                .build()
                .toUriString();

        // Constrói URL de grupo se informado; caso contrário usa a raiz (/v3/api-docs)
        String url = StringUtils.hasText(group)
                ? base + "/" + UriUtils.encodePathSegment(group, StandardCharsets.UTF_8)
                : base;

        LOGGER.info("Fetching OpenAPI doc from {}", url);
        try {
            JsonNode root = restTemplate.getForObject(url, JsonNode.class);
            if (root != null) {
                return root;
            }
        } catch (Exception ex) {
            LOGGER.warn("Failed to fetch OpenAPI doc from {} (group={}), falling back to base {}. Error: {}",
                    url, group, base, ex.getMessage());
        }

        // Fallback: tentar o /v3/api-docs raiz
        JsonNode fallback = restTemplate.getForObject(base, JsonNode.class);
        if (fallback == null) {
            throw new IllegalStateException("OpenAPI document is null for group " + group + " and fallback " + base);
        }
        return fallback;
    }

    private List<String> toStringList(JsonNode node) {
        List<String> result = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(n -> result.add(n.asText()));
        }
        return result;
    }

    private SchemaRef extractRequestSchema(JsonNode op, JsonNode components) {
        JsonNode schema = op.path("requestBody")
                .path("content")
                .path("application/json")
                .path("schema");
        if (schema.isMissingNode()) return null;
        if (schema.has("$ref")) {
            String name = extractRefName(schema.path("$ref").asText());
            return new SchemaRef(name, null, "application/json", extractFieldsFromRef(name, components), extractRelationsFromRef(name, components));
        }
        return new SchemaRef(null, schema, "application/json", extractFields(schema, components), extractRelations(schema, components));
    }

    private SchemaRef extractResponseSchema(JsonNode op, JsonNode components) {
        JsonNode responses = op.path("responses");
        if (responses.isMissingNode()) return null;
        String[] preferred = {"200", "201", "default"};
        for (String code : preferred) {
            JsonNode r = responses.path(code);
            if (!r.isMissingNode()) {
                JsonNode schema = r.path("content").path("application/json").path("schema");
                if (schema.isMissingNode()) continue;
                if (schema.has("$ref")) {
                    String name = extractRefName(schema.path("$ref").asText());
                    return new SchemaRef(name, null, "application/json", extractFieldsFromRef(name, components), extractRelationsFromRef(name, components));
                }
                return new SchemaRef(null, schema, "application/json", extractFields(schema, components), extractRelations(schema, components));
            }
        }
        Iterator<Map.Entry<String, JsonNode>> it = responses.fields();
        while (it.hasNext()) {
            JsonNode r = it.next().getValue();
            JsonNode schema = r.path("content").path("application/json").path("schema");
            if (schema.isMissingNode()) continue;
            if (schema.has("$ref")) {
                String name = extractRefName(schema.path("$ref").asText());
                return new SchemaRef(name, null, "application/json", extractFieldsFromRef(name, components), extractRelationsFromRef(name, components));
            }
            return new SchemaRef(null, schema, "application/json", extractFields(schema, components), extractRelations(schema, components));
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

        // Caso 1: referência explícita
        if (targetNode != null && targetNode.has("$ref")) {
            String target = extractRefName(targetNode.path("$ref").asText());
            return new RelationSummary(fieldName, target, isArray ? "one-to-many" : "many-to-one");
        }

        // Caso 2: heurística *_id ou *_ref com tipo string/integer → assume FK
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
        // se for path ou id composto, pegar última parte após "/" ou "."
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

        public EndpointSummary(String path, String method, List<String> tags, String summary, String description,
                               String operationId, SchemaRef requestSchema, SchemaRef responseSchema,
                               List<ParameterSummary> parameters) {
            this.path = path;
            this.method = method;
            this.tags = tags;
            this.summary = summary;
            this.description = description;
            this.operationId = operationId;
            this.requestSchema = requestSchema;
            this.responseSchema = responseSchema;
            this.parameters = parameters;
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
