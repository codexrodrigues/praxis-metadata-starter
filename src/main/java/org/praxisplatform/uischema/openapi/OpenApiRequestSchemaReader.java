package org.praxisplatform.uischema.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.models.SpecVersion;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Strict structural subset for backend compilation; not a general JSON Schema validator. */
final class OpenApiRequestSchemaReader {
    private static final Set<String> METHODS = Set.of("get", "put", "post", "delete", "options", "head", "patch", "trace");
    private static final Set<String> SCHEMA_MAPS = Set.of("properties", "patternProperties", "$defs", "definitions");
    private static final Set<String> SCHEMA_VALUES = Set.of("items", "additionalProperties", "propertyNames", "contains", "unevaluatedProperties", "unevaluatedItems", "additionalItems", "contentSchema");
    private static final List<String> UNSUPPORTED = List.of("allOf", "oneOf", "anyOf", "not", "if", "then", "else",
            "dependentSchemas", "dependencies", "$dynamicRef", "$recursiveRef", "$id", "$anchor", "$dynamicAnchor", "$recursiveAnchor");
    private static final Set<String> DIALECTS = Set.of("https://spec.openapis.org/oas/3.1/dialect/base", "https://json-schema.org/draft/2020-12/schema");
    private static final int MAX_DEPTH = 64;
    private static final int MAX_NODES = 10_000;

    private final JsonNode document;
    private final SpecVersion version;
    private int nodes;

    private OpenApiRequestSchemaReader(JsonNode document, SpecVersion version) {
        this.document = document;
        this.version = version;
    }

    static CanonicalRequestSchema read(OpenApiDocumentService documents, CanonicalOperationRef operation) {
        if (operation == null || blank(operation.group()) || blank(operation.path()) || blank(operation.method()) || blank(operation.operationId())) {
            throw new IllegalArgumentException("Explicit canonical operation group, ID, path and method are required");
        }
        String method = operation.method().toLowerCase(Locale.ROOT);
        if (!METHODS.contains(method)) throw new IllegalArgumentException("Unsupported operation method");
        JsonNode document = documents.getDocumentForGroup(operation.group());
        if (document == null || !document.isObject()) throw invalid("OpenAPI document is unavailable");
        SpecVersion version = version(document.path("openapi"));
        checkDialect(document.get("jsonSchemaDialect"), version);
        String path = documents.resolveDocumentPath(document.path("paths"), operation.path(), method);
        JsonNode pathItem = document.path("paths").path(path);
        if (pathItem.has("$ref")) throw invalid("Referenced path items are not supported for strict request binding");
        JsonNode operationNode = pathItem.path(method);
        if (!operationNode.isObject() || !operation.operationId().equals(operationNode.path("operationId").textValue())) {
            throw invalid("Operation is not present with its explicit ID in the canonical document");
        }
        int matches = 0;
        for (JsonNode item : document.path("paths")) {
            for (String verb : METHODS) {
                if (operation.operationId().equals(item.path(verb).path("operationId").textValue())) matches++;
            }
        }
        if (matches != 1) throw invalid("Operation ID is ambiguous in the canonical document");
        OpenApiRequestSchemaReader reader = new OpenApiRequestSchemaReader(document, version);
        JsonNode requestBody = reader.resolveRequestBody(operationNode.path("requestBody"), new HashSet<>(), 0);
        String mediaType = OpenApiContentSupport.requireJsonMediaType(requestBody.path("content"));
        JsonNode schema = reader.schema(requestBody.path("content").path(mediaType).path("schema"), new HashSet<>(), 0);
        return new CanonicalRequestSchema(operation, mediaType, version, schema);
    }

    private JsonNode resolveRequestBody(JsonNode body, Set<String> visited, int depth) {
        budget(depth);
        if (!body.isObject()) throw invalid("Request body is missing or invalid");
        if (!body.has("$ref")) return body;
        String ref = reference(body, "#/components/requestBodies/");
        if (!visited.add(ref)) throw invalid("Cyclic request body reference");
        return resolveRequestBody(target(ref), visited, depth + 1);
    }

    private JsonNode schema(JsonNode source, Set<String> visited, int depth) {
        budget(depth);
        if (source.isBoolean() && version == SpecVersion.V31) return source;
        if (!source.isObject()) throw invalid("Request schema is missing or unsupported");
        checkDialect(source.get("$schema"), version);
        for (String keyword : UNSUPPORTED) {
            if (source.has(keyword)) throw invalid("Unresolved or unsupported schema composition: " + keyword);
        }
        if (source.has("$ref")) {
            String ref = reference(source, "#/components/schemas/");
            if (!visited.add(ref)) throw invalid("Cyclic schema reference");
            JsonNode resolved = schema(target(ref), visited, depth + 1);
            visited.remove(ref);
            return resolved;
        }
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        var fields = source.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            String key = field.getKey();
            JsonNode value = field.getValue();
            if (SCHEMA_MAPS.contains(key)) {
                if (!value.isObject()) throw invalid("Schema property map is invalid");
                ObjectNode properties = result.putObject(key);
                var children = value.fields();
                while (children.hasNext()) {
                    var child = children.next();
                    properties.set(child.getKey(), schema(child.getValue(), visited, depth + 1));
                }
            } else if (SCHEMA_VALUES.contains(key)) {
                // OpenAPI 3.0 explicitly allows boolean additionalProperties.
                result.set(key, value.isBoolean() && "additionalProperties".equals(key)
                        ? value : schema(value, visited, depth + 1));
            } else if ("prefixItems".equals(key)) {
                if (version != SpecVersion.V31 || !value.isArray()) throw invalid("Tuple schema is unsupported");
                ArrayNode items = result.putArray(key);
                for (JsonNode item : value) items.add(schema(item, visited, depth + 1));
            } else {
                // Defaults, examples and x-ui are data: a literal $ref there is not a schema reference.
                result.set(key, copyData(value, depth + 1));
            }
        }
        return result;
    }

    private JsonNode copyData(JsonNode value, int depth) {
        budget(depth);
        if (value.isObject()) {
            ObjectNode copy = JsonNodeFactory.instance.objectNode();
            var fields = value.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                copy.set(field.getKey(), copyData(field.getValue(), depth + 1));
            }
            return copy;
        }
        if (value.isArray()) {
            ArrayNode copy = JsonNodeFactory.instance.arrayNode();
            for (JsonNode item : value) copy.add(copyData(item, depth + 1));
            return copy;
        }
        if (value.isPojo() || value.isMissingNode()) throw invalid("Non-JSON schema value");
        return value.deepCopy();
    }

    private String reference(JsonNode source, String prefix) {
        JsonNode node = source.path("$ref");
        if (!node.isTextual() || source.size() != 1) throw invalid("Reference siblings are not supported");
        String ref = node.textValue();
        if (!ref.startsWith(prefix) || ref.length() == prefix.length()) throw invalid("Only local component references are supported");
        String name = ref.substring(prefix.length());
        if (name.contains("/") || name.contains("%") || name.contains("#")) throw invalid("Unsupported component reference encoding");
        for (int i = 0; i < name.length(); i++) {
            if (name.charAt(i) == '~' && (++i >= name.length() || (name.charAt(i) != '0' && name.charAt(i) != '1'))) {
                throw invalid("Invalid JSON Pointer escape in component reference");
            }
        }
        return ref;
    }

    private JsonNode target(String ref) {
        JsonNode target = document.at(ref.substring(1));
        if (target.isMissingNode() || target.isNull()) throw invalid("Component reference is missing");
        return target;
    }

    private void budget(int depth) {
        if (depth > MAX_DEPTH || ++nodes > MAX_NODES) throw invalid("Request schema resolution exceeds structural limits");
    }

    private static SpecVersion version(JsonNode version) {
        if (version.isTextual()) {
            if (version.textValue().matches("3\\.0\\.[0-9]+")) return SpecVersion.V30;
            if (version.textValue().matches("3\\.1\\.[0-9]+")) return SpecVersion.V31;
        }
        throw invalid("Explicit OpenAPI 3.0.x or 3.1.x version is required");
    }

    private static void checkDialect(JsonNode dialect, SpecVersion version) {
        if (dialect != null && (version != SpecVersion.V31 || !dialect.isTextual() || !DIALECTS.contains(dialect.textValue()))) {
            throw invalid("Unsupported JSON Schema dialect");
        }
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static IllegalStateException invalid(String message) { return new IllegalStateException(message); }
}
