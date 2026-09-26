package org.praxisplatform.uischema.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.models.SpecVersion;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.controller.docs.OpenApiDocsSupport;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CanonicalRequestSchemaTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final CanonicalOperationRef UPDATE = new CanonicalOperationRef(
            "inventory", "update-item", "/api/items/{id}", "PUT"
    );

    @Test
    void resolvesExactOperationAndReturnsAnIndependentOas30Snapshot() {
        ObjectNode document = basicDocument("3.0.3");
        CachedOpenApiDocumentService service = cached(document);

        CanonicalRequestSchema schema = service.requireRequestSchema(UPDATE);

        assertEquals(UPDATE, schema.operation());
        assertEquals("application/json", schema.mediaType());
        assertEquals(SpecVersion.V30, schema.specVersion());
        assertEquals("string", schema.schema().path("properties").path("title").path("type").asText());

        ((ObjectNode) schema.schema()).put("mutated", true);
        CanonicalRequestSchema again = service.requireRequestSchema(UPDATE);
        assertFalse(again.schema().has("mutated"));
    }

    @Test
    void strictRequestSchemaUsesTheExactGroupAndNeverSubstitutesTheBaseDocument() {
        ObjectNode base = basicDocument("3.0.3");
        ObjectNode exact = basicDocument("3.0.3");
        requestSchema(exact).putObject("properties").putObject("exactOnly").put("type", "string");

        java.util.concurrent.atomic.AtomicInteger legacyReads = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger strictReads = new java.util.concurrent.atomic.AtomicInteger();
        OpenApiDocumentService documents = new OpenApiDocumentService() {
            @Override
            public String resolveGroupFromPath(String path) { return "inventory"; }

            @Override
            public JsonNode getDocumentForGroup(String groupName) {
                legacyReads.incrementAndGet();
                return base;
            }

            @Override
            public JsonNode getDocumentForGroupStrict(String groupName) {
                strictReads.incrementAndGet();
                return exact;
            }

            @Override
            public String getOrComputeSchemaHash(String schemaId, java.util.function.Supplier<JsonNode> supplier) {
                return "hash";
            }

            @Override
            public void clearCaches() { }
        };

        CanonicalRequestSchema schema = documents.requireRequestSchema(UPDATE);

        assertEquals(0, legacyReads.get());
        assertEquals(1, strictReads.get());
        assertTrue(schema.schema().path("properties").has("exactOnly"));
    }

    @Test
    void acceptsOas31OnlyWithADeclaredSupportedDialectAndReturnsItsDialectVersion() {
        ObjectNode baseDialect = basicDocument("3.1.1");
        baseDialect.put("jsonSchemaDialect", "https://spec.openapis.org/oas/3.1/dialect/base");
        assertEquals(SpecVersion.V31, cached(baseDialect).requireRequestSchema(UPDATE).specVersion());

        ObjectNode draft2020 = basicDocument("3.1.0");
        draft2020.put("jsonSchemaDialect", "https://json-schema.org/draft/2020-12/schema");
        assertEquals(SpecVersion.V31, cached(draft2020).requireRequestSchema(UPDATE).specVersion());
    }

    @Test
    void rejectsUnsupportedOrUndeclaredOpenApiAndJsonSchemaDialects() {
        List<ObjectNode> invalid = List.of(
                basicDocument("3.2.0"),
                basicDocument("2.0.0"),
                documentWithoutOpenApiVersion(),
                withRootDialect(basicDocument("3.1.0"), "https://example.test/custom-dialect"),
                withSchemaDialect(basicDocument("3.1.0"), "https://example.test/custom-schema")
        );

        invalid.forEach(document -> assertThrows(IllegalStateException.class,
                () -> cached(document).requireRequestSchema(UPDATE)));
    }

    @Test
    void selectsApplicationJsonBeforeOtherRepresentationsAndAcceptsOneConcreteJsonSuffix() {
        ObjectNode applicationJson = basicDocument("3.0.3");
        ObjectNode content = requestContent(applicationJson);
        content.putObject("application/xml").putObject("schema").put("type", "string");
        assertEquals("application/json", cached(applicationJson).requireRequestSchema(UPDATE).mediaType());

        ObjectNode suffixJson = basicDocument("3.0.3");
        ObjectNode suffixContent = requestContent(suffixJson);
        suffixContent.remove("application/json");
        suffixContent.putObject("application/vnd.praxis+json").putObject("schema").put("type", "object");
        assertEquals("application/vnd.praxis+json", cached(suffixJson).requireRequestSchema(UPDATE).mediaType());
    }

    @Test
    void rejectsAmbiguousWildcardAndNonJsonRequestMediaTypes() {
        ObjectNode multipleJson = basicDocument("3.0.3");
        requestContent(multipleJson).putObject("application/vnd.praxis+json")
                .putObject("schema").put("type", "object");

        ObjectNode ambiguous = basicDocument("3.0.3");
        ObjectNode ambiguousContent = requestContent(ambiguous);
        ambiguousContent.remove("application/json");
        ambiguousContent.putObject("application/a+json").putObject("schema").put("type", "object");
        ambiguousContent.putObject("application/b+json").putObject("schema").put("type", "object");

        ObjectNode wildcard = basicDocument("3.0.3");
        ObjectNode wildcardContent = requestContent(wildcard);
        wildcardContent.remove("application/json");
        wildcardContent.putObject("*/*").putObject("schema").put("type", "object");

        ObjectNode xml = basicDocument("3.0.3");
        ObjectNode xmlContent = requestContent(xml);
        xmlContent.remove("application/json");
        xmlContent.putObject("application/xml").putObject("schema").put("type", "object");

        for (ObjectNode document : List.of(multipleJson, ambiguous, wildcard, xml)) {
            assertThrows(IllegalStateException.class, () -> cached(document).requireRequestSchema(UPDATE));
        }
    }

    @Test
    void followsAComponentRequestBodyAndJsonPointerEscapedComponentSchemaReference() {
        ObjectNode document = basicDocument("3.0.3");
        ObjectNode operation = operation(document, "/api/items/{id}", "put");
        operation.remove("requestBody");
        operation.putObject("requestBody").put("$ref", "#/components/requestBodies/Update~1body~0v1");
        ObjectNode components = child(document, "components");
        child(child(components, "requestBodies"), "Update/body~v1")
                .putObject("content").putObject("application/json")
                .putObject("schema").put("$ref", "#/components/schemas/Update~1item~0v1");
        child(child(components, "schemas"), "Update/item~v1")
                .put("type", "object").putObject("properties").putObject("displayName").put("type", "string");

        CanonicalRequestSchema schema = cached(document).requireRequestSchema(UPDATE);

        assertEquals("string", schema.schema().path("properties").path("displayName").path("type").asText());
    }

    @Test
    void rejectsInvalidOperationReferencesAndDuplicateDocumentOperationIds() {
        ObjectNode missingOperationId = basicDocument("3.0.3");
        assertThrows(IllegalStateException.class, () -> cached(missingOperationId).requireRequestSchema(
                new CanonicalOperationRef("inventory", "other", "/api/items/{id}", "PUT")
        ));

        ObjectNode duplicate = basicDocument("3.0.3");
        ObjectNode duplicateOperation = operation(duplicate, "/api/items/{otherId}", "put");
        duplicateOperation.put("operationId", "update-item");
        duplicateOperation.putObject("requestBody").putObject("content").putObject("application/json")
                .putObject("schema").put("type", "object");
        assertThrows(IllegalStateException.class, () -> cached(duplicate).requireRequestSchema(UPDATE));

        ObjectNode missingPath = basicDocument("3.0.3");
        assertThrows(IllegalStateException.class, () -> cached(missingPath).requireRequestSchema(
                new CanonicalOperationRef("inventory", "update-item", "/api/unknown/{id}", "PUT")
        ));

        ObjectNode mismatchedMethod = basicDocument("3.0.3");
        assertThrows(IllegalStateException.class, () -> cached(mismatchedMethod).requireRequestSchema(
                new CanonicalOperationRef("inventory", "update-item", "/api/items/{id}", "PATCH")
        ));
    }

    @Test
    void rejectsExternalMissingCyclicAndSiblingReferences() {
        ObjectNode external = basicDocument("3.0.3");
        replaceSchemaWithReference(external, "https://example.test/Update");

        ObjectNode missing = basicDocument("3.0.3");
        replaceSchemaWithReference(missing, "#/components/schemas/Missing");

        ObjectNode cyclic = basicDocument("3.0.3");
        replaceSchemaWithReference(cyclic, "#/components/schemas/A");
        child(child(cyclic, "components"), "schemas").putObject("A")
                .put("$ref", "#/components/schemas/B");
        child(child(cyclic, "components"), "schemas").putObject("B")
                .put("$ref", "#/components/schemas/A");

        ObjectNode sibling = basicDocument("3.0.3");
        requestSchema(sibling).put("$ref", "#/components/schemas/Update").put("description", "not a pure reference");
        child(child(sibling, "components"), "schemas").putObject("Update").put("type", "object");

        for (ObjectNode document : List.of(external, missing, cyclic, sibling)) {
            assertThrows(IllegalStateException.class, () -> cached(document).requireRequestSchema(UPDATE));
        }
    }

    @Test
    void rejectsUnsupportedCompositionsAndDynamicSchemaKeywordsBeforePublishingAConcreteSchema() {
        List<String> forbidden = List.of(
                "allOf", "anyOf", "oneOf", "not", "if", "then", "else", "dependentSchemas",
                "$dynamicRef", "$recursiveRef", "$id"
        );

        for (String keyword : forbidden) {
            ObjectNode document = basicDocument("3.1.0");
            if (keyword.endsWith("Ref") || keyword.equals("$id")) {
                requestSchema(document).put(keyword, "value");
            } else {
                requestSchema(document).put(keyword, true);
            }
            assertThrows(IllegalStateException.class, () -> cached(document).requireRequestSchema(UPDATE), keyword);
        }

        ObjectNode referencedComposition = basicDocument("3.1.0");
        replaceSchemaWithReference(referencedComposition, "#/components/schemas/Composed");
        child(child(referencedComposition, "components"), "schemas").putObject("Composed")
                .putArray("allOf").addObject().put("type", "object");
        assertThrows(IllegalStateException.class, () -> cached(referencedComposition).requireRequestSchema(UPDATE));
    }

    @Test
    void resolvesReferencesOnlyInStructuralSchemaKeywordsAndLeavesLiteralMetadataUntouched() {
        ObjectNode document = basicDocument("3.1.0");
        ObjectNode components = child(child(document, "components"), "schemas");
        child(components, "Shared").put("type", "string").put("minLength", 3);
        ObjectNode schema = requestSchema(document);
        child(child(schema, "properties"), "title").removeAll().put("$ref", "#/components/schemas/Shared");
        schema.putObject("items").put("$ref", "#/components/schemas/Shared");
        schema.putObject("additionalProperties").put("$ref", "#/components/schemas/Shared");
        schema.putObject("$defs").putObject("local").put("$ref", "#/components/schemas/Shared");
        schema.putObject("patternProperties").putObject("^x-").put("$ref", "#/components/schemas/Shared");
        schema.putObject("propertyNames").put("$ref", "#/components/schemas/Shared");
        schema.putObject("contains").put("$ref", "#/components/schemas/Shared");
        schema.putObject("contentSchema").put("$ref", "#/components/schemas/Shared");
        schema.putArray("prefixItems").addObject().put("$ref", "#/components/schemas/Shared");
        schema.putObject("default").put("$ref", "literal-default");
        schema.putArray("examples").addObject().put("$ref", "literal-example");
        schema.putObject("x-ui").putObject("exampleBinding").put("$ref", "literal-x-ui");

        JsonNode resolved = cached(document).requireRequestSchema(UPDATE).schema();

        assertFalse(resolved.path("properties").path("title").has("$ref"));
        assertEquals(3, resolved.path("properties").path("title").path("minLength").asInt());
        assertEquals(3, resolved.path("items").path("minLength").asInt());
        assertEquals(3, resolved.path("additionalProperties").path("minLength").asInt());
        assertEquals(3, resolved.path("$defs").path("local").path("minLength").asInt());
        assertEquals(3, resolved.path("patternProperties").path("^x-").path("minLength").asInt());
        assertEquals(3, resolved.path("propertyNames").path("minLength").asInt());
        assertEquals(3, resolved.path("contains").path("minLength").asInt());
        assertEquals(3, resolved.path("contentSchema").path("minLength").asInt());
        child(components, "Shared").putArray("allOf").addObject().put("type", "string");
        ObjectNode contentOnly = basicDocument("3.1.0");
        contentOnly.set("components", document.path("components"));
        requestSchema(contentOnly).putObject("contentSchema").put("$ref", "#/components/schemas/Shared");
        assertThrows(IllegalStateException.class, () -> cached(contentOnly).requireRequestSchema(UPDATE));
        assertEquals(3, resolved.path("prefixItems").get(0).path("minLength").asInt());
        assertEquals("literal-default", resolved.path("default").path("$ref").asText());
        assertEquals("literal-example", resolved.path("examples").get(0).path("$ref").asText());
        assertEquals("literal-x-ui", resolved.path("x-ui").path("exampleBinding").path("$ref").asText());
    }

    @Test
    void rejectsSchemasThatExceedTheStructuralResolutionBudget() {
        ObjectNode tooDeep = basicDocument("3.1.0");
        ObjectNode cursor = requestSchema(tooDeep);
        for (int level = 0; level < 65; level++) {
            cursor = cursor.putObject("properties").putObject("level" + level);
        }
        assertThrows(IllegalStateException.class, () -> cached(tooDeep).requireRequestSchema(UPDATE));

        ObjectNode tooWide = basicDocument("3.1.0");
        ObjectNode properties = child(requestSchema(tooWide), "properties");
        for (int index = 0; index <= 10_000; index++) {
            properties.putObject("field" + index).put("type", "string");
        }
        assertThrows(IllegalStateException.class, () -> cached(tooWide).requireRequestSchema(UPDATE));
    }

    @Test
    void rejectsInvalidArgumentsAndDefaultSpiValidatesASubstituteDocumentSource() {
        CachedOpenApiDocumentService service = cached(basicDocument("3.0.3"));
        assertThrows(IllegalArgumentException.class, () -> service.requireRequestSchema(null));
        assertThrows(IllegalArgumentException.class, () -> service.requireRequestSchema(
                new CanonicalOperationRef(" ", "", "", " ")
        ));

        ObjectNode source = basicDocument("3.0.3");
        OpenApiDocumentService substitute = new OpenApiDocumentService() {
            @Override
            public String resolveGroupFromPath(String path) {
                return "inventory";
            }

            @Override
            public JsonNode getDocumentForGroup(String groupName) {
                return source;
            }

            @Override
            public JsonNode getDocumentForGroupStrict(String groupName) {
                return source;
            }

            @Override
            public String getOrComputeSchemaHash(String schemaId, java.util.function.Supplier<JsonNode> supplier) {
                return "hash";
            }

            @Override
            public void clearCaches() {
            }
        };
        assertEquals(SpecVersion.V30, substitute.requireRequestSchema(UPDATE).specVersion());
        assertEquals("string", substitute.requireRequestSchema(UPDATE).schema().path("properties").path("title").path("type").asText());
        source.remove("openapi");
        assertThrows(IllegalStateException.class, () -> substitute.requireRequestSchema(UPDATE));
    }

    @Test
    void usesTheCachedDocumentWithoutMutatingItOrRefetchingIt() {
        ObjectNode document = basicDocument("3.0.3");
        OpenApiDocsSupport support = mock(OpenApiDocsSupport.class);
        when(support.fetchOpenApiGroupDocument(any(RestTemplate.class), isNull(), anyString(), any())).thenReturn(document);
        CachedOpenApiDocumentService service = new CachedOpenApiDocumentService(new RestTemplate(), JSON, support);

        CanonicalRequestSchema first = service.requireRequestSchema(UPDATE);
        CanonicalRequestSchema second = service.requireRequestSchema(UPDATE);

        assertNotNull(first.schema());
        assertNotNull(second.schema());
        assertFalse(document.path("paths").path("/api/items/{id}").path("put").path("requestBody")
                .path("content").path("application/json").path("schema").has("$ref"));
        verify(support, times(1)).fetchOpenApiGroupDocument(any(RestTemplate.class), isNull(), anyString(), any());
        verify(support, org.mockito.Mockito.never()).fetchOpenApiDocument(any(RestTemplate.class), isNull(), anyString(), any());
    }

    private static CachedOpenApiDocumentService cached(JsonNode document) {
        OpenApiDocsSupport support = mock(OpenApiDocsSupport.class);
        when(support.fetchOpenApiGroupDocument(any(RestTemplate.class), isNull(), anyString(), any())).thenReturn(document);
        return new CachedOpenApiDocumentService(new RestTemplate(), JSON, support);
    }

    private static ObjectNode basicDocument(String openApiVersion) {
        ObjectNode root = JSON.createObjectNode();
        root.put("openapi", openApiVersion);
        ObjectNode operation = operation(root, "/api/items/{id}", "put");
        operation.put("operationId", "update-item");
        operation.putObject("requestBody").putObject("content").putObject("application/json")
                .putObject("schema").put("type", "object").putObject("properties")
                .putObject("title").put("type", "string");
        return root;
    }

    private static ObjectNode documentWithoutOpenApiVersion() {
        ObjectNode root = basicDocument("3.0.3");
        root.remove("openapi");
        return root;
    }

    private static ObjectNode withRootDialect(ObjectNode document, String dialect) {
        document.put("jsonSchemaDialect", dialect);
        return document;
    }

    private static ObjectNode withSchemaDialect(ObjectNode document, String dialect) {
        requestSchema(document).put("$schema", dialect);
        return document;
    }

    private static ObjectNode operation(ObjectNode document, String path, String method) {
        return child(child(child(document, "paths"), path), method);
    }

    private static ObjectNode requestContent(ObjectNode document) {
        return child(child(operation(document, "/api/items/{id}", "put"), "requestBody"), "content");
    }

    private static ObjectNode requestSchema(ObjectNode document) {
        return child(child(requestContent(document), "application/json"), "schema");
    }

    private static void replaceSchemaWithReference(ObjectNode document, String reference) {
        ObjectNode schema = requestSchema(document);
        schema.removeAll();
        schema.put("$ref", reference);
    }

    private static ObjectNode child(ObjectNode parent, String literalName) {
        JsonNode existing = parent.get(literalName);
        if (existing == null) {
            return parent.putObject(literalName);
        }
        if (existing instanceof ObjectNode object) {
            return object;
        }
        throw new IllegalStateException("Fixture key must be an object: " + literalName);
    }
}
