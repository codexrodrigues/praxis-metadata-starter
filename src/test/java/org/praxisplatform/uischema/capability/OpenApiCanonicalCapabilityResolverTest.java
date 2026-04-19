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
                    "/employees/batch": {
                      "delete": {}
                    },
                    "/employees/{id}/profile": {
                      "patch": {}
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
        assertEquals(Boolean.TRUE, capabilities.get("options"));
        assertEquals(Boolean.TRUE, capabilities.get("optionSources"));
        assertEquals(Boolean.TRUE, capabilities.get("byId"));
        assertEquals(Boolean.TRUE, capabilities.get("all"));
        assertEquals(Boolean.TRUE, capabilities.get("filter"));
        assertEquals(Boolean.TRUE, capabilities.get("cursor"));
        assertEquals(Boolean.FALSE, capabilities.get("export"));
        assertEquals(Boolean.TRUE, capabilities.get("statsGroupBy"));
        assertEquals(Boolean.TRUE, capabilities.get("statsTimeSeries"));
        assertEquals(Boolean.TRUE, capabilities.get("statsDistribution"));

        Map<String, CapabilityOperation> operations = resolver.resolveCrudOperations(document, "/employees/");
        assertTrue(operations.get("create").supported());
        assertEquals("POST", operations.get("create").preferredMethod());
        assertTrue(operations.get("view").supported());
        assertEquals("GET", operations.get("view").preferredMethod());
        assertTrue(operations.get("edit").supported());
        assertEquals("PATCH", operations.get("edit").preferredMethod());
        assertTrue(operations.get("delete").supported());
        assertEquals("DELETE", operations.get("delete").preferredMethod());
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
        assertFalse(capabilities.get("options"));
        assertFalse(capabilities.get("optionSources"));
        assertTrue(capabilities.get("byId"));
        assertTrue(capabilities.get("all"));
        assertTrue(capabilities.get("filter"));
        assertFalse(capabilities.get("cursor"));
        assertFalse(capabilities.get("export"));
        assertFalse(capabilities.get("statsGroupBy"));
        assertFalse(capabilities.get("statsTimeSeries"));
        assertFalse(capabilities.get("statsDistribution"));

        Map<String, CapabilityOperation> operations = resolver.resolveCrudOperations(document, "/payroll-view");
        assertFalse(operations.get("create").supported());
        assertTrue(operations.get("view").supported());
        assertFalse(operations.get("edit").supported());
        assertFalse(operations.get("delete").supported());
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
