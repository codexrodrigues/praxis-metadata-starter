package org.praxisplatform.uischema.determination;

import com.fasterxml.jackson.databind.JsonNode;
import org.praxisplatform.uischema.openapi.CanonicalOperationRef;
import org.praxisplatform.uischema.openapi.CanonicalOperationResolver;
import org.praxisplatform.uischema.openapi.OpenApiDocumentService;
import org.praxisplatform.uischema.schema.CanonicalSchemaRef;
import org.praxisplatform.uischema.schema.SchemaReferenceResolver;
import org.springframework.util.StringUtils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Compila declaracoes de provider em metadata fechada, resolvida e validada contra o OpenAPI.
 *
 * <p>A compilacao so publica bindings em request schemas de operationIds explicitamente
 * declarados. O href e os schema URLs da capability sao derivados das fontes canonicas; nenhum
 * provider fornece URL executavel.</p>
 */
public class ReactiveDeterminationMetadataCompiler {

    private static final Pattern STABLE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final int MAX_BINDINGS = 64;

    private final ReactiveDeterminationDefinitionRegistry registry;
    private final CanonicalOperationResolver operationResolver;
    private final SchemaReferenceResolver schemaReferenceResolver;
    private final OpenApiDocumentService openApiDocumentService;

    public ReactiveDeterminationMetadataCompiler(
            ReactiveDeterminationDefinitionRegistry registry,
            CanonicalOperationResolver operationResolver,
            SchemaReferenceResolver schemaReferenceResolver,
            OpenApiDocumentService openApiDocumentService
    ) {
        this.registry = registry;
        this.operationResolver = operationResolver;
        this.schemaReferenceResolver = schemaReferenceResolver;
        this.openApiDocumentService = openApiDocumentService;
    }

    /**
     * Compila os bindings aplicaveis a uma variante exata de schema filtrado.
     *
     * @param schemaOperation operacao cujo request schema esta sendo materializado
     * @param schemaType variante request/response solicitada
     * @param schemaDocument documento OpenAPI que contem o form schema
     * @param formSchema schema raiz do formulario antes dos enriquecimentos finais
     */
    public List<Map<String, Object>> compile(
            CanonicalOperationRef schemaOperation,
            String schemaType,
            JsonNode schemaDocument,
            JsonNode formSchema
    ) {
        if (!"request".equalsIgnoreCase(schemaType)
                || schemaOperation == null
                || !StringUtils.hasText(schemaOperation.operationId())) {
            return List.of();
        }

        List<ReactiveDeterminationDefinition> definitions = registry.findBySchemaOperationId(
                schemaOperation.operationId()
        );
        if (definitions.isEmpty()) {
            return List.of();
        }

        validateGraph(definitions, schemaOperation.operationId());
        List<Map<String, Object>> compiled = new ArrayList<>();
        for (ReactiveDeterminationDefinition definition : definitions) {
            ReactiveDeterminationScope scope = definition.scopes().stream()
                    .filter(candidate -> schemaOperation.operationId().equals(candidate.schemaOperationId()))
                    .findFirst()
                    .orElseThrow();
            ResolvedOperation determinationOperation = validateDefinition(
                    definition,
                    scope,
                    schemaOperation,
                    schemaDocument,
                    formSchema
            );
            compiled.add(toMetadata(definition, scope, determinationOperation.operation()));
        }
        compiled.sort(Comparator.comparing(item -> String.valueOf(item.get("id"))));
        return List.copyOf(compiled);
    }

    private ResolvedOperation validateDefinition(
            ReactiveDeterminationDefinition definition,
            ReactiveDeterminationScope scope,
            CanonicalOperationRef schemaOperation,
            JsonNode schemaDocument,
            JsonNode formSchema
    ) {
        String id = requireStableId(definition.id(), "determination id");
        validateFormMode(id, scope.formMode(), schemaOperation.method());
        if (definition.triggerMode() == null) {
            fail(id, "triggerMode is required");
        }
        requireStableId(definition.operationId(), "operationId");
        validateProvenance(id, definition.provenance());

        List<String> sourcePaths = requireList(id, "sourcePaths", definition.sourcePaths());
        List<ReactiveDeterminationInputBinding> inputs = requireList(id, "inputs", definition.inputs());
        List<ReactiveDeterminationOutputBinding> outputs = requireList(id, "outputs", definition.outputs());
        if ((long) inputs.size() + outputs.size() > MAX_BINDINGS) {
            fail(id, "at most " + MAX_BINDINGS + " input and output bindings are allowed in total");
        }

        Set<String> inputFields = new LinkedHashSet<>();
        Set<String> requestPaths = new LinkedHashSet<>();
        for (ReactiveDeterminationInputBinding input : inputs) {
            if (input == null) {
                fail(id, "input binding must not be null");
            }
            String fieldPath = validatePointer(id, "input.fieldPath", input.fieldPath());
            String requestPath = validatePointer(id, "input.requestPath", input.requestPath());
            requireNonOverlappingPath(id, inputFields, fieldPath, "input fieldPath");
            requireNonOverlappingPath(id, requestPaths, requestPath, "input requestPath");
            requireSchemaPath(id, "input field", formSchema, schemaDocument, fieldPath);
        }
        Set<String> uniqueSources = new LinkedHashSet<>();
        for (String sourcePath : sourcePaths) {
            String source = validatePointer(id, "sourcePath", sourcePath);
            if (!uniqueSources.add(source)) {
                fail(id, "duplicate sourcePath '" + source + "'");
            }
            if (!inputFields.contains(source)) {
                fail(id, "sourcePath '" + source + "' must also be declared as an input fieldPath");
            }
        }

        ResolvedOperation determinationOperation = resolveDeterminationOperation(id, definition.operationId());
        JsonNode requestSchema = requestSchema(determinationOperation.operationNode(), determinationOperation.document());
        JsonNode responseSchema = responseSchema(determinationOperation.operationNode(), determinationOperation.document());
        if (requestSchema == null) {
            fail(id, "determination operation must publish a JSON request schema");
        }
        if (responseSchema == null) {
            fail(id, "determination operation must publish a JSON success response schema");
        }
        for (ReactiveDeterminationInputBinding input : inputs) {
            requireSchemaPath(id, "request", requestSchema, determinationOperation.document(), input.requestPath());
        }

        Set<String> responsePaths = new LinkedHashSet<>();
        Set<String> targetFields = new LinkedHashSet<>();
        for (ReactiveDeterminationOutputBinding output : outputs) {
            if (output == null) {
                fail(id, "output binding must not be null");
            }
            String responsePath = validatePointer(id, "output.responsePath", output.responsePath());
            String fieldPath = validatePointer(id, "output.fieldPath", output.fieldPath());
            requireNonOverlappingPath(id, responsePaths, responsePath, "output responsePath");
            requireNonOverlappingPath(id, targetFields, fieldPath, "output fieldPath");
            requireSchemaPath(id, "response", responseSchema, determinationOperation.document(), responsePath);
            requireSchemaPath(id, "output field", formSchema, schemaDocument, fieldPath);
        }
        for (String inputField : inputFields) {
            for (String targetField : targetFields) {
                if (pathsOverlap(inputField, targetField)) {
                    fail(id, "input and output fieldPath values must not overlap");
                }
            }
        }
        return determinationOperation;
    }

    private Map<String, Object> toMetadata(
            ReactiveDeterminationDefinition definition,
            ReactiveDeterminationScope scope,
            CanonicalOperationRef operation
    ) {
        CanonicalSchemaRef requestSchema = schemaReferenceResolver.requestSchema(operation);
        CanonicalSchemaRef responseSchema = schemaReferenceResolver.responseSchema(operation);

        Map<String, Object> trigger = new LinkedHashMap<>();
        trigger.put("mode", definition.triggerMode().metadataValue());
        trigger.put("sourcePaths", List.copyOf(definition.sourcePaths()));

        Map<String, Object> publishedScope = new LinkedHashMap<>();
        publishedScope.put("schemaOperationId", scope.schemaOperationId());
        publishedScope.put("formMode", scope.formMode().metadataValue());

        Map<String, Object> capability = new LinkedHashMap<>();
        capability.put("operationId", operation.operationId());
        capability.put("method", operation.method());
        capability.put("href", operation.path());
        capability.put("requestSchemaUrl", requestSchema.url());
        capability.put("responseSchemaUrl", responseSchema.url());

        List<Map<String, Object>> inputs = definition.inputs().stream()
                .map(binding -> Map.<String, Object>of(
                        "fieldPath", binding.fieldPath(),
                        "requestPath", binding.requestPath()
                ))
                .toList();
        List<Map<String, Object>> outputs = definition.outputs().stream()
                .map(binding -> Map.<String, Object>of(
                        "responsePath", binding.responsePath(),
                        "fieldPath", binding.fieldPath()
                ))
                .toList();

        Map<String, Object> provenance = new LinkedHashMap<>();
        provenance.put("kind", definition.provenance().kind().metadataValue());
        provenance.put("source", definition.provenance().source());
        if (StringUtils.hasText(definition.provenance().version())) {
            provenance.put("version", definition.provenance().version());
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("id", definition.id());
        metadata.put("trigger", trigger);
        metadata.put("scope", publishedScope);
        metadata.put("capability", capability);
        metadata.put("inputs", inputs);
        metadata.put("outputs", outputs);
        metadata.put("provenance", provenance);
        return metadata;
    }

    private ResolvedOperation resolveDeterminationOperation(String id, String operationId) {
        Optional<CanonicalOperationRef> resolved = operationResolver.resolveByOperationId(operationId);
        if (resolved.isEmpty()) {
            fail(id, "operationId '" + operationId + "' cannot be resolved canonically");
        }
        CanonicalOperationRef operation = resolved.orElseThrow();
        if (!"POST".equalsIgnoreCase(operation.method())) {
            fail(id, "determination operation must use POST");
        }
        if (!isSafeResolvedPath(operation.path())) {
            fail(id, "resolved operation path is not a safe same-origin relative path");
        }
        JsonNode document = openApiDocumentService.getDocumentForGroup(operation.group());
        if (document == null || document.isMissingNode()) {
            fail(id, "OpenAPI document for operation group is unavailable");
        }
        String documentPath = openApiDocumentService.resolveDocumentPath(
                document.path("paths"),
                operation.path(),
                operation.method()
        );
        JsonNode operationNode = document.path("paths")
                .path(documentPath)
                .path(operation.method().toLowerCase(Locale.ROOT));
        if (operationNode.isMissingNode()
                || !operationId.equals(operationNode.path("operationId").asText())) {
            fail(id, "resolved operation is not present in the canonical OpenAPI document");
        }
        return new ResolvedOperation(operation, document, operationNode);
    }

    private JsonNode requestSchema(JsonNode operation, JsonNode document) {
        JsonNode schema = contentSchema(operation.path("requestBody").path("content"));
        return resolveRef(schema, document);
    }

    private JsonNode responseSchema(JsonNode operation, JsonNode document) {
        JsonNode responses = operation.path("responses");
        List<String> statuses = new ArrayList<>();
        responses.fieldNames().forEachRemaining(statuses::add);
        statuses.sort(Comparator.comparingInt(this::responseStatusPriority).thenComparing(String::compareTo));
        for (String status : statuses) {
            if (!(status.startsWith("2") || "default".equals(status))) {
                continue;
            }
            JsonNode schema = contentSchema(responses.path(status).path("content"));
            JsonNode resolved = resolveRef(schema, document);
            if (resolved != null && !resolved.isMissingNode()) {
                return resolved;
            }
        }
        return null;
    }

    private int responseStatusPriority(String status) {
        return switch (status) {
            case "200" -> 0;
            case "201" -> 1;
            case "202" -> 2;
            case "204" -> 3;
            case "default" -> 9;
            default -> 5;
        };
    }

    private JsonNode contentSchema(JsonNode content) {
        if (content == null || content.isMissingNode() || !content.isObject()) {
            return null;
        }
        JsonNode json = content.path("application/json").path("schema");
        if (!json.isMissingNode()) {
            return json;
        }
        var fields = content.fields();
        while (fields.hasNext()) {
            JsonNode schema = fields.next().getValue().path("schema");
            if (!schema.isMissingNode()) {
                return schema;
            }
        }
        return null;
    }

    private JsonNode resolveRef(JsonNode schema, JsonNode document) {
        if (schema == null || schema.isMissingNode()) {
            return null;
        }
        JsonNode current = schema;
        Set<String> visited = new HashSet<>();
        while (current != null && current.has("$ref")) {
            String ref = current.path("$ref").asText();
            if (!ref.startsWith("#/components/schemas/") || !visited.add(ref)) {
                return null;
            }
            current = document.path("components").path("schemas").path(ref.substring(ref.lastIndexOf('/') + 1));
            if (current.isMissingNode()) {
                return null;
            }
        }
        return current;
    }

    private void requireSchemaPath(
            String id,
            String label,
            JsonNode schema,
            JsonNode document,
            String pointer
    ) {
        List<String> segments = pointerSegments(pointer);
        JsonNode current = resolveRef(schema, document);
        for (String segment : segments) {
            current = propertySchema(current, document, segment);
            if (current == null || current.isMissingNode()) {
                fail(id, label + " path '" + pointer + "' does not exist in its canonical schema");
            }
        }
    }

    private JsonNode propertySchema(JsonNode schema, JsonNode document, String property) {
        JsonNode current = resolveRef(schema, document);
        if (current == null) {
            return null;
        }
        JsonNode direct = current.path("properties").path(property);
        if (!direct.isMissingNode()) {
            return resolveRef(direct, document);
        }
        for (String composition : List.of("allOf", "oneOf", "anyOf")) {
            JsonNode branches = current.path(composition);
            if (!branches.isArray()) {
                continue;
            }
            for (JsonNode branch : branches) {
                JsonNode candidate = propertySchema(branch, document, property);
                if (candidate != null && !candidate.isMissingNode()) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private void validateGraph(
            List<ReactiveDeterminationDefinition> definitions,
            String schemaOperationId
    ) {
        Map<String, String> writerByTarget = new LinkedHashMap<>();
        for (ReactiveDeterminationDefinition definition : definitions) {
            if (definition.outputs() == null) {
                continue;
            }
            for (ReactiveDeterminationOutputBinding output : definition.outputs()) {
                if (output == null || output.fieldPath() == null) {
                    continue;
                }
                for (Map.Entry<String, String> existing : writerByTarget.entrySet()) {
                    if (pathsOverlap(existing.getKey(), output.fieldPath())) {
                        throw new IllegalArgumentException(
                                "Reactive determinations '" + existing.getValue() + "' and '" + definition.id()
                                        + "' write overlapping targets for schema operation '" + schemaOperationId + "'."
                        );
                    }
                }
                writerByTarget.put(output.fieldPath(), definition.id());
            }
        }

        Map<String, Set<String>> edges = new LinkedHashMap<>();
        for (ReactiveDeterminationDefinition definition : definitions) {
            edges.put(definition.id(), new LinkedHashSet<>());
        }
        for (ReactiveDeterminationDefinition writer : definitions) {
            if (writer.outputs() == null) {
                continue;
            }
            for (ReactiveDeterminationDefinition reader : definitions) {
                if (reader.sourcePaths() == null) {
                    continue;
                }
                boolean dependency = writer.outputs().stream()
                        .filter(output -> output != null)
                        .anyMatch(output -> reader.sourcePaths().stream()
                                .anyMatch(source -> source != null && pathsOverlap(output.fieldPath(), source)));
                if (dependency) {
                    edges.get(writer.id()).add(reader.id());
                }
            }
        }
        detectCycle(edges, schemaOperationId);
    }

    private void detectCycle(Map<String, Set<String>> edges, String schemaOperationId) {
        Set<String> visited = new HashSet<>();
        Set<String> active = new HashSet<>();
        Deque<String> stack = new ArrayDeque<>();
        for (String node : edges.keySet()) {
            if (visit(node, edges, visited, active, stack)) {
                throw new IllegalArgumentException(
                        "Reactive determination cycle detected for schema operation '" + schemaOperationId
                                + "': " + String.join(" -> ", stack)
                );
            }
        }
    }

    private boolean visit(
            String node,
            Map<String, Set<String>> edges,
            Set<String> visited,
            Set<String> active,
            Deque<String> stack
    ) {
        if (active.contains(node)) {
            stack.addLast(node);
            return true;
        }
        if (!visited.add(node)) {
            return false;
        }
        active.add(node);
        stack.addLast(node);
        for (String target : edges.getOrDefault(node, Set.of())) {
            if (visit(target, edges, visited, active, stack)) {
                return true;
            }
        }
        active.remove(node);
        stack.removeLast();
        return false;
    }

    private boolean pathsOverlap(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        List<String> leftSegments = pointerSegments(left);
        List<String> rightSegments = pointerSegments(right);
        int common = Math.min(leftSegments.size(), rightSegments.size());
        return leftSegments.subList(0, common).equals(rightSegments.subList(0, common));
    }

    private void requireNonOverlappingPath(
            String id,
            Set<String> existingPaths,
            String candidate,
            String label
    ) {
        for (String existing : existingPaths) {
            if (pathsOverlap(existing, candidate)) {
                fail(id, label + " values must not overlap: '" + existing + "' and '" + candidate + "'");
            }
        }
        existingPaths.add(candidate);
    }

    private String validatePointer(String id, String label, String pointer) {
        if (!StringUtils.hasText(pointer) || !pointer.startsWith("/") || pointer.startsWith("//")) {
            fail(id, label + " must be a non-root JSON Pointer");
        }
        pointerSegments(pointer);
        return pointer;
    }

    private List<String> pointerSegments(String pointer) {
        if (pointer == null || pointer.length() < 2 || !pointer.startsWith("/")) {
            return List.of();
        }
        String[] raw = pointer.substring(1).split("/", -1);
        List<String> segments = new ArrayList<>(raw.length);
        for (String segment : raw) {
            if (segment.isEmpty() || segment.matches(".*~(?:[^01]|$).*$")) {
                throw new IllegalArgumentException("Invalid JSON Pointer: " + pointer);
            }
            String decoded = segment.replace("~1", "/").replace("~0", "~");
            if (decoded.isBlank() || "*".equals(decoded)) {
                throw new IllegalArgumentException("Array wildcards and blank JSON Pointer segments are not supported: " + pointer);
            }
            segments.add(decoded);
        }
        return List.copyOf(segments);
    }

    private boolean isSafeResolvedPath(String path) {
        return StringUtils.hasText(path)
                && path.startsWith("/")
                && !path.startsWith("//")
                && !path.contains("\\")
                && !path.contains("://")
                && !path.contains("{")
                && !path.contains("}");
    }

    private void validateFormMode(String id, ReactiveDeterminationFormMode mode, String method) {
        if (mode == null) {
            fail(id, "scope formMode is required");
        }
        String normalized = method == null ? "" : method.toUpperCase(Locale.ROOT);
        if (mode == ReactiveDeterminationFormMode.CREATE && !"POST".equals(normalized)) {
            fail(id, "CREATE scope must reference a POST schema operation");
        }
        if (mode == ReactiveDeterminationFormMode.EDIT
                && !("PUT".equals(normalized) || "PATCH".equals(normalized))) {
            fail(id, "EDIT scope must reference a PUT or PATCH schema operation");
        }
    }

    private void validateProvenance(String id, ReactiveDeterminationProvenance provenance) {
        if (provenance == null || provenance.kind() == null) {
            fail(id, "structural provenance kind is required");
        }
        requireStableId(provenance.source(), "provenance source");
        if (StringUtils.hasText(provenance.version()) && provenance.version().length() > 64) {
            fail(id, "provenance version must have at most 64 characters");
        }
    }

    private String requireStableId(String value, String label) {
        if (!StringUtils.hasText(value) || !STABLE_ID.matcher(value).matches()) {
            throw new IllegalArgumentException(label + " must match " + STABLE_ID.pattern());
        }
        return value;
    }

    private <T> List<T> requireList(String id, String label, List<T> value) {
        if (value == null || value.isEmpty()) {
            fail(id, label + " must not be empty");
        }
        return value;
    }

    private void fail(String id, String message) {
        throw new IllegalArgumentException("Reactive determination '" + id + "': " + message + ".");
    }

    private record ResolvedOperation(
            CanonicalOperationRef operation,
            JsonNode document,
            JsonNode operationNode
    ) {
    }
}
