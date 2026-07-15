package org.praxisplatform.uischema.capability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.openapi.OpenApiDocumentService;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenApiCanonicalCapabilityResolverTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void resolvesPublishedCanonicalOperationsIncludingItemLevelPatchIntents() throws Exception {
        JsonNode document = objectMapper.readTree("""
                {
                  "paths": {
                    "/employees": {
                      "post": {}
                    },
                    "/employees/{id}": {
                      "get": {},
                      "delete": {}
                    },
                    "/employees/all": {
                      "get": {}
                    },
                    "/employees/filter": {
                      "post": {}
                    },
                    "/employees/filter/cursor": {
                      "post": {}
                    },
                    "/employees/export": {
                      "post": {}
                    },
                    "/employees/options/filter": {
                      "post": {}
                    },
                    "/employees/options/by-ids": {
                      "get": {}
                    },
                    "/employees/option-sources/{sourceKey}/options/filter": {
                      "post": {}
                    },
                    "/employees/option-sources/{sourceKey}/options/by-ids": {
                      "get": {}
                    },
                    "/employees/stats/group-by": {
                      "post": {}
                    },
                    "/employees/stats/timeseries": {
                      "post": {}
                    },
                    "/employees/stats/distribution": {
                      "post": {}
                    },
                    "/employees/stats/comparison": {
                      "post": {}
                    },
                    "/employees/batch": {
                      "delete": {}
                    },
                    "/employees/{id}/profile": {
                      "patch": {}
                    },
                    "/employees/{id}/duplicate-draft": {
                      "post": {}
                    }
                  }
                }
                """);

        CanonicalCapabilityResolver resolver = new OpenApiCanonicalCapabilityResolver(
                new StaticOpenApiDocumentService("human-resources", document)
        );

        Map<String, Boolean> capabilities = resolver.resolve("/employees/");

        assertEquals(Boolean.TRUE, capabilities.get("create"));
        assertEquals(Boolean.TRUE, capabilities.get("update"));
        assertEquals(Boolean.TRUE, capabilities.get("delete"));
        assertEquals(Boolean.TRUE, capabilities.get("duplicate-draft"));
        assertEquals(Boolean.TRUE, capabilities.get("options"));
        assertEquals(Boolean.TRUE, capabilities.get("optionSources"));
        assertEquals(Boolean.TRUE, capabilities.get("byId"));
        assertEquals(Boolean.TRUE, capabilities.get("all"));
        assertEquals(Boolean.TRUE, capabilities.get("filter"));
        assertEquals(Boolean.FALSE, capabilities.get("filterExpression"));
        assertEquals(Boolean.TRUE, capabilities.get("cursor"));
        assertEquals(Boolean.FALSE, capabilities.get("export"));
        assertEquals(Boolean.TRUE, capabilities.get("statsGroupBy"));
        assertEquals(Boolean.TRUE, capabilities.get("statsTimeSeries"));
        assertEquals(Boolean.TRUE, capabilities.get("statsDistribution"));
        assertEquals(Boolean.TRUE, capabilities.get("statsComparison"));

        Map<String, CapabilityOperation> operations = resolver.resolveCrudOperations(document, "/employees/");
        assertTrue(operations.get("create").supported());
        assertEquals("POST", operations.get("create").preferredMethod());
        assertTrue(operations.get("view").supported());
        assertEquals("GET", operations.get("view").preferredMethod());
        assertTrue(operations.get("edit").supported());
        assertEquals("PATCH", operations.get("edit").preferredMethod());
        assertTrue(operations.get("delete").supported());
        assertEquals("DELETE", operations.get("delete").preferredMethod());
        assertTrue(operations.get("duplicate-draft").supported());
        assertEquals("POST", operations.get("duplicate-draft").preferredMethod());

        Map<String, CapabilityOperation> allOperations = resolver.resolveOperations(document, "/employees/");
        assertOperation(allOperations, "byId", true, "ITEM", "GET", "self");
        assertOperation(allOperations, "update", true, "ITEM", "PATCH", "update");
        assertOperation(allOperations, "all", true, "COLLECTION", "GET", "all");
        assertOperation(allOperations, "filter", true, "COLLECTION", "POST", "filter");
        assertOperation(allOperations, "cursor", true, "COLLECTION", "POST", "filter-cursor");
        assertOperation(allOperations, "options", true, "COLLECTION", "POST", "options");
        assertOperation(allOperations, "optionSources", true, "COLLECTION", "POST", "option-sources");
        assertOperation(allOperations, "export", false, "COLLECTION", null, "export");
        assertOperation(allOperations, "statsGroupBy", true, "COLLECTION", "POST", "stats-group-by");
        assertOperation(allOperations, "statsTimeSeries", true, "COLLECTION", "POST", "stats-timeseries");
        assertOperation(allOperations, "statsDistribution", true, "COLLECTION", "POST", "stats-distribution");
        assertOperation(allOperations, "statsComparison", true, "COLLECTION", "POST", "stats-comparison");
    }

    @Test
    void resolvesReadOnlyCanonicalOperationsForQueryOnlyResource() throws Exception {
        JsonNode document = objectMapper.readTree("""
                {
                  "paths": {
                    "/payroll-view/{id}": {
                      "get": {}
                    },
                    "/payroll-view/all": {
                      "get": {}
                    },
                    "/payroll-view/filter": {
                      "post": {}
                    }
                  }
                }
                """);

        CanonicalCapabilityResolver resolver = new OpenApiCanonicalCapabilityResolver(
                new StaticOpenApiDocumentService("human-resources", document)
        );

        Map<String, Boolean> capabilities = resolver.resolve("/payroll-view");

        assertFalse(capabilities.get("create"));
        assertFalse(capabilities.get("update"));
        assertFalse(capabilities.get("delete"));
        assertFalse(capabilities.get("duplicate-draft"));
        assertFalse(capabilities.get("options"));
        assertFalse(capabilities.get("optionSources"));
        assertTrue(capabilities.get("byId"));
        assertTrue(capabilities.get("all"));
        assertTrue(capabilities.get("filter"));
        assertFalse(capabilities.get("filterExpression"));
        assertFalse(capabilities.get("cursor"));
        assertFalse(capabilities.get("export"));
        assertFalse(capabilities.get("statsGroupBy"));
        assertFalse(capabilities.get("statsTimeSeries"));
        assertFalse(capabilities.get("statsDistribution"));
        assertFalse(capabilities.get("statsComparison"));

        Map<String, CapabilityOperation> operations = resolver.resolveCrudOperations(document, "/payroll-view");
        assertFalse(operations.get("create").supported());
        assertTrue(operations.get("view").supported());
        assertFalse(operations.get("edit").supported());
        assertFalse(operations.get("delete").supported());
        assertFalse(operations.get("duplicate-draft").supported());

        Map<String, CapabilityOperation> allOperations = resolver.resolveOperations(document, "/payroll-view");
        assertOperation(allOperations, "byId", true, "ITEM", "GET", "self");
        assertOperation(allOperations, "all", true, "COLLECTION", "GET", "all");
        assertOperation(allOperations, "filter", true, "COLLECTION", "POST", "filter");
        assertOperation(allOperations, "cursor", false, "COLLECTION", null, "filter-cursor");
    }

    @Test
    void ignoresWorkflowLikePatchPathsWhenResolvingCanonicalUpdateCapability() throws Exception {
        JsonNode document = objectMapper.readTree("""
                {
                  "paths": {
                    "/employees/{id}": {
                      "get": {}
                    },
                    "/employees/{id}/actions/approve": {
                      "patch": {}
                    },
                    "/employees/{id}:approve": {
                      "patch": {}
                    }
                  }
                }
                """);

        CanonicalCapabilityResolver resolver = new OpenApiCanonicalCapabilityResolver(
                new StaticOpenApiDocumentService("human-resources", document)
        );

        Map<String, Boolean> capabilities = resolver.resolve("/employees");

        assertFalse(capabilities.get("update"));
        assertTrue(capabilities.get("byId"));

        Map<String, CapabilityOperation> operations = resolver.resolveCrudOperations(document, "/employees");
        assertFalse(operations.get("edit").supported());
        assertNull(operations.get("edit").preferredMethod());
    }

    @Test
    void doesNotPublishItemDeleteOperationWhenOnlyBatchDeleteExists() throws Exception {
        JsonNode document = objectMapper.readTree("""
                {
                  "paths": {
                    "/employees/batch": {
                      "delete": {}
                    },
                    "/employees/filter": {
                      "post": {}
                    }
                  }
                }
                """);

        CanonicalCapabilityResolver resolver = new OpenApiCanonicalCapabilityResolver(
                new StaticOpenApiDocumentService("human-resources", document)
        );

        Map<String, Boolean> capabilities = resolver.resolve("/employees");
        assertTrue(capabilities.get("delete"));

        Map<String, CapabilityOperation> operations = resolver.resolveCrudOperations(document, "/employees");
        assertFalse(operations.get("delete").supported());
        assertEquals("ITEM", operations.get("delete").scope());
        assertNull(operations.get("delete").preferredMethod());
    }

    @Test
    void resolvesUnitDeleteWithoutBatchDeleteAsItemDeleteCapability() throws Exception {
        JsonNode document = objectMapper.readTree("""
                {
                  "paths": {
                    "/employees": {
                      "post": {}
                    },
                    "/employees/{id}": {
                      "get": {},
                      "put": {},
                      "delete": {}
                    },
                    "/employees/filter": {
                      "post": {}
                    }
                  }
                }
                """);

        CanonicalCapabilityResolver resolver = new OpenApiCanonicalCapabilityResolver(
                new StaticOpenApiDocumentService("human-resources", document)
        );

        Map<String, Boolean> capabilities = resolver.resolve("/employees");
        assertTrue(capabilities.get("create"));
        assertTrue(capabilities.get("update"));
        assertTrue(capabilities.get("delete"));

        Map<String, CapabilityOperation> operations = resolver.resolveCrudOperations(document, "/employees");
        assertTrue(operations.get("delete").supported());
        assertEquals("ITEM", operations.get("delete").scope());
        assertEquals("DELETE", operations.get("delete").preferredMethod());
    }

    @Test
    void resolvesContextualRelatedCollectionOperationsWithCustomItemVariable() throws Exception {
        JsonNode document = objectMapper.readTree("""
                {
                  "paths": {
                    "/codigos-frequencia/{id}/documentos-legais": {
                      "get": {},
                      "post": {}
                    },
                    "/codigos-frequencia/{id}/documentos-legais/{documentoId}": {
                      "put": {},
                      "delete": {}
                    }
                  }
                }
                """);

        CanonicalCapabilityResolver resolver = new OpenApiCanonicalCapabilityResolver(
                new StaticOpenApiDocumentService("administracao-pessoal", document)
        );

        String childResourcePath = "/codigos-frequencia/{id}/documentos-legais";
        Map<String, Boolean> capabilities = resolver.resolve(childResourcePath);

        assertTrue(capabilities.get("all"));
        assertTrue(capabilities.get("create"));
        assertTrue(capabilities.get("update"));
        assertTrue(capabilities.get("delete"));

        Map<String, CapabilityOperation> operations = resolver.resolveCrudOperations(document, childResourcePath);
        assertTrue(operations.get("create").supported());
        assertEquals("POST", operations.get("create").preferredMethod());
        assertTrue(operations.get("edit").supported());
        assertEquals("PUT", operations.get("edit").preferredMethod());
        assertTrue(operations.get("delete").supported());
        assertEquals("DELETE", operations.get("delete").preferredMethod());
    }

    @Test
    void doesNotPromoteRelatedResourceDeleteToParentDeleteCapability() throws Exception {
        JsonNode document = objectMapper.readTree("""
                { "paths": {
                  "/codigos-frequencia/{id}": { "get": {}, "put": {} },
                  "/codigos-frequencia/{id}/documentos-legais/{documentoId}": { "put": {}, "delete": {} }
                } }
                """);
        CanonicalCapabilityResolver resolver = new OpenApiCanonicalCapabilityResolver(
                new StaticOpenApiDocumentService("administracao-pessoal", document));
        assertFalse(resolver.resolve("/codigos-frequencia").get("delete"));
        CapabilityOperation delete = resolver.resolveCrudOperations(document, "/codigos-frequencia").get("delete");
        assertFalse(delete.supported());
        assertNull(delete.preferredMethod());
    }

    private void assertOperation(
            Map<String, CapabilityOperation> operations,
            String id,
            boolean supported,
            String scope,
            String preferredMethod,
            String preferredRel
    ) {
        CapabilityOperation operation = operations.get(id);
        assertEquals(supported, operation.supported());
        assertEquals(scope, operation.scope());
        assertEquals(preferredMethod, operation.preferredMethod());
        assertEquals(preferredRel, operation.preferredRel());
        assertTrue(operation.availability().allowed());
    }

    private record StaticOpenApiDocumentService(String group, JsonNode document) implements OpenApiDocumentService {

        @Override
        public String resolveGroupFromPath(String path) {
            return group;
        }

        @Override
        public JsonNode getDocumentForGroup(String groupName) {
            return document;
        }

        @Override
        public String getOrComputeSchemaHash(String schemaId, java.util.function.Supplier<JsonNode> payloadSupplier) {
            return "unused";
        }

        @Override
        public void clearCaches() {
        }
    }
}
