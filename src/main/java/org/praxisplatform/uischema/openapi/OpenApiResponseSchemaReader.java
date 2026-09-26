package org.praxisplatform.uischema.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.models.SpecVersion;
import org.praxisplatform.uischema.hash.SchemaCanonicalizer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Strict subset for response schemas consumed by backend descriptor composition. */
final class OpenApiResponseSchemaReader {
    private static final Set<String> METHODS = Set.of("get", "put", "post", "delete", "options", "head", "patch", "trace");
    private static final String RESPONSES_PREFIX = "#/components/responses/";

    private OpenApiResponseSchemaReader() { }

    static CanonicalResponseSchema read(OpenApiDocumentService documents, CanonicalOperationRef operation) {
        OpenApiRequestSchemaReader.validateOperationReference(operation);
        return read(documents, CanonicalOpenApiGroupSnapshot.capture(documents, operation.group()), operation);
    }

    static CanonicalResponseSchema read(OpenApiDocumentService documents, CanonicalOpenApiGroupSnapshot snapshot,
            CanonicalOperationRef operation) {
        OpenApiRequestSchemaReader.validateOperationReference(operation);
        if (documents == null) throw new IllegalArgumentException("OpenAPI document service is required");
        if (snapshot == null || !snapshot.group().equals(operation.group())) {
            throw OpenApiRequestSchemaReader.invalid("Operation group does not match the captured OpenAPI document");
        }

        String method = operation.method().toLowerCase(Locale.ROOT);
        JsonNode document = snapshot.document();
        SpecVersion version = OpenApiRequestSchemaReader.version(document.path("openapi"));
        OpenApiRequestSchemaReader.checkDialect(document.get("jsonSchemaDialect"), version);
        String path = documents.resolveDocumentPath(document.path("paths"), operation.path(), method);
        JsonNode pathItem = document.path("paths").path(path);
        if (pathItem.has("$ref")) throw OpenApiRequestSchemaReader.invalid("Referenced path items are not supported for strict response binding");
        JsonNode operationNode = pathItem.path(method);
        if (!operationNode.isObject() || !operation.operationId().equals(operationNode.path("operationId").textValue())) {
            throw OpenApiRequestSchemaReader.invalid("Operation is not present with its explicit ID in the canonical document");
        }
        requireUniqueOperationId(document, operation.operationId());

        JsonNode responses = operationNode.path("responses");
        if (!responses.isObject() || responses.isEmpty()) {
            throw OpenApiRequestSchemaReader.invalid("Canonical operation must declare explicit successful responses");
        }

        OpenApiRequestSchemaReader schemaReader = new OpenApiRequestSchemaReader(document, version);
        SchemaCanonicalizer canonicalizer = new SchemaCanonicalizer();
        List<CanonicalResponseSchema.Variant> variants = new ArrayList<>();
        JsonNode commonSchema = null;
        var responseKeys = responses.fieldNames();
        while (responseKeys.hasNext()) {
            String key = responseKeys.next();
            if ("default".equalsIgnoreCase(key) || "2XX".equalsIgnoreCase(key)) {
                throw OpenApiRequestSchemaReader.invalid("Default and 2XX range responses cannot prove concrete successful response contracts");
            }
            if (key.matches("[1-5][xX][xX]")) continue;
            if (!key.matches("[1-5][0-9]{2}")) {
                throw OpenApiRequestSchemaReader.invalid("Unsupported OpenAPI response status key");
            }
            int status = Integer.parseInt(key);
            if (status < 200 || status >= 300) continue;

            JsonNode response = resolveResponse(responses.path(key), schemaReader, new HashSet<>(), 0);
            JsonNode content = response.path("content");
            String mediaType = OpenApiContentSupport.requireJsonMediaType(content);
            JsonNode resolvedSchema = schemaReader.resolveSchema(content.path(mediaType).path("schema"));
            JsonNode canonicalSchema = canonicalizer.canonicalize(resolvedSchema);
            if (commonSchema == null) commonSchema = canonicalSchema;
            else if (!commonSchema.equals(canonicalSchema)) {
                throw OpenApiRequestSchemaReader.invalid("Successful response schemas are not canonically equivalent");
            }
            variants.add(new CanonicalResponseSchema.Variant(status, mediaType));
        }
        if (variants.isEmpty() || commonSchema == null) {
            throw OpenApiRequestSchemaReader.invalid("Canonical operation must declare at least one explicit 2xx JSON response schema");
        }
        variants.sort(Comparator.comparingInt(CanonicalResponseSchema.Variant::status)
                .thenComparing(CanonicalResponseSchema.Variant::mediaType));
        return new CanonicalResponseSchema(operation, version, commonSchema, variants);
    }

    private static JsonNode resolveResponse(JsonNode response, OpenApiRequestSchemaReader schemaReader,
            Set<String> visited, int depth) {
        schemaReader.budget(depth);
        if (!response.isObject()) throw OpenApiRequestSchemaReader.invalid("Successful response object is missing or invalid");
        if (!response.has("$ref")) return response;
        String ref = schemaReader.reference(response, RESPONSES_PREFIX);
        if (!visited.add(ref)) throw OpenApiRequestSchemaReader.invalid("Cyclic response reference");
        JsonNode resolved = resolveResponse(schemaReader.target(ref), schemaReader, visited, depth + 1);
        visited.remove(ref);
        return resolved;
    }

    private static void requireUniqueOperationId(JsonNode document, String operationId) {
        int matches = 0;
        JsonNode paths = document.path("paths");
        var pathNames = paths.fieldNames();
        while (pathNames.hasNext()) {
            JsonNode pathItem = paths.path(pathNames.next());
            for (String method : METHODS) {
                if (operationId.equals(pathItem.path(method).path("operationId").textValue())) matches++;
            }
        }
        if (matches != 1) throw OpenApiRequestSchemaReader.invalid("Operation ID is ambiguous in the canonical document");
    }
}
