package org.praxisplatform.uischema.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.dto.OptionDTO;
import org.praxisplatform.uischema.options.LookupFilterRequest;
import org.praxisplatform.uischema.options.OptionSourceDescriptor;
import org.praxisplatform.uischema.options.service.OptionSourceExecutionContext;
import org.praxisplatform.uischema.options.service.OptionSourceExecutionRequest;
import org.praxisplatform.uischema.options.service.OptionSourceOperation;
import org.praxisplatform.uischema.options.service.OptionSourceProvider;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Import(NonJpaOptionSourceProviderE2ETest.NonJpaOptionSourceProviderTestConfiguration.class)
class NonJpaOptionSourceProviderE2ETest extends AbstractE2eH2Test {

    @Test
    void customNonJpaProviderExecutesFilterAndByIdsThroughCanonicalEndpoints() throws Exception {
        ExternalCatalogOptionSourceProvider.resetCounters();

        ResponseEntity<String> filterResponse = postJson(
                "/employees/option-sources/externalDepartmentLookup/options/filter?search=external&page=0&size=10",
                "{}"
        );

        assertEquals(200, filterResponse.getStatusCode().value());
        JsonNode filterBody = body(filterResponse);
        assertEquals(2, filterBody.path("content").size());
        assertEquals("EXT-1", filterBody.path("content").get(0).path("id").asText());
        assertEquals("External Human Resources", filterBody.path("content").get(0).path("label").asText());
        assertEquals("department-catalog", filterBody.path("content").get(0).path("extra").path("catalogCode").asText());

        ResponseEntity<String> byIdsResponse = get(
                "/employees/option-sources/externalDepartmentLookup/options/by-ids?ids=EXT-2&ids=EXT-1"
        );

        assertEquals(200, byIdsResponse.getStatusCode().value());
        JsonNode byIdsBody = body(byIdsResponse);
        assertEquals("EXT-2", byIdsBody.get(0).path("id").asText());
        assertEquals("External Operations", byIdsBody.get(0).path("label").asText());
        assertEquals("EXT-1", byIdsBody.get(1).path("id").asText());
        assertEquals("External Human Resources", byIdsBody.get(1).path("label").asText());
    }

    @Test
    void providerBackedOptionSourcePublishesStableFilterRequestSchema() throws Exception {
        ResponseEntity<String> openApiResponse = get("/v3/api-docs/employees");

        assertEquals(200, openApiResponse.getStatusCode().value());
        JsonNode openApi = body(openApiResponse);
        String openApiText = openApi.toString();

        assertTrue(openApi.path("paths").has("/employees/option-sources/{sourceKey}/options/filter"));
        assertTrue(openApi.path("paths").has("/employees/option-sources/{sourceKey}/options/by-ids"));
        assertTrue(openApiText.contains("OptionSourceFilterRequest"), openApiText);
        assertTrue(openApiText.contains("OptionSourceByIdsRequest"), openApiText);

        ResponseEntity<String> requestSchemaResponse = get(
                "/schemas/filtered?path=/employees/option-sources/%7BsourceKey%7D/options/filter"
                        + "&operation=post&schemaType=request"
        );

        assertEquals(200, requestSchemaResponse.getStatusCode().value());
        JsonNode requestSchema = body(requestSchemaResponse);
        assertTrue(requestSchema.path("properties").has("filter"), requestSchema.toPrettyString());
    }

    @Test
    void registryWideProviderBackedOptionSourceIsDiscoverableByCanonicalResourceKey() throws Exception {
        ResponseEntity<String> response = get("/schemas/domain?resource=human-resources.employees");

        assertEquals(200, response.getStatusCode().value());
        JsonNode domain = body(response);
        JsonNode optionSource = findOptionSourceNode(domain, "externalRequiredDependencyLookup");

        assertEquals(
                "human-resources.employees.policy.external-required-dependency-lookup.selection",
                optionSource.path("nodeKey").asText()
        );
        JsonNode metadata = optionSource.path("metadata");
        assertEquals("LIGHT_LOOKUP", metadata.path("type").asText());
        assertEquals("/employees", metadata.path("resourcePath").asText());
        assertEquals("tipoEvento", metadata.path("dependsOn").get(0).asText());
        assertEquals("tipoEvento", metadata.path("dependencyFilterMap").path("tipoEvento").asText());
        assertEquals(
                "/employees/option-sources/externalRequiredDependencyLookup/options/filter",
                metadata.path("filterEndpoint").asText()
        );
        assertEquals(
                "/employees/option-sources/externalRequiredDependencyLookup/options/by-ids",
                metadata.path("byIdsEndpoint").asText()
        );
        JsonNode filter = metadata.path("filtering").path("availableFilters").get(0);
        assertEquals("tipoEvento", filter.path("field").asText());
        assertEquals("equals", filter.path("defaultOperator").asText());
        assertTrue(filter.path("required").asBoolean());
        assertEquals("required", metadata.path("selectedReloadPolicy").asText());
        assertEquals("reject", metadata.path("invalidSortPolicy").asText());
        assertTrue(metadata.path("executionMode").isMissingNode());
        assertTrue(metadata.path("provider").isMissingNode());
        assertTrue(metadata.path("providerConfig").isMissingNode());
        assertTrue(metadata.path("sql").isMissingNode());

        ResponseEntity<String> schemaResponse = get(
                "/schemas/filtered?path=/employees/filter&operation=post&schemaType=request"
        );
        assertEquals(200, schemaResponse.getStatusCode().value());
        assertFalse(
                body(schemaResponse).path("properties").has("tipoSubstituicao"),
                "Registry-wide option sources must not create synthetic structural fields"
        );
    }

    @Test
    void providerBackedOptionSourceAcceptsDeclaredDependencyOutsideResourceFilterDto() throws Exception {
        ExternalCatalogOptionSourceProvider.resetCounters();

        ResponseEntity<String> response = postJson(
                "/employees/option-sources/externalDependencyLookup/options/filter?page=0&size=10",
                """
                {
                  "filter": {
                    "tipoEvento": "SUBST FG"
                  },
                  "filters": [
                    { "field": "tipoEvento", "operator": "equals", "value": "SUBST FG" }
                  ]
                }
                """
        );

        assertEquals(200, response.getStatusCode().value());
        JsonNode body = body(response);
        assertEquals(2, body.path("content").size());
        assertEquals(1, ExternalCatalogOptionSourceProvider.filterCalls());
        Map<?, ?> payload = assertInstanceOf(Map.class, ExternalCatalogOptionSourceProvider.lastFilterPayload());
        assertEquals("SUBST FG", payload.get("tipoEvento"));
        List<LookupFilterRequest> filters = ExternalCatalogOptionSourceProvider.lastFilterRequest();
        assertEquals(1, filters.size());
        assertEquals("tipoEvento", filters.get(0).field());
        assertEquals("equals", filters.get(0).operator());
        assertEquals("SUBST FG", filters.get(0).value());
    }

    @Test
    void providerBackedOptionSourceByIdsAcceptsDeclaredDependencyOutsideResourceFilterDto() throws Exception {
        ExternalCatalogOptionSourceProvider.resetCounters();

        ResponseEntity<String> response = postJson(
                "/employees/option-sources/externalDependencyLookup/options/by-ids",
                """
                {
                  "filter": {
                    "tipoEvento": "SUBST FG"
                  },
                  "filters": [
                    { "field": "tipoEvento", "operator": "equals", "value": "SUBST FG" }
                  ],
                  "ids": ["EXT-2", "EXT-1"]
                }
                """
        );

        assertEquals(200, response.getStatusCode().value());
        JsonNode body = body(response);
        assertEquals("EXT-2", body.get(0).path("id").asText());
        assertEquals("EXT-1", body.get(1).path("id").asText());
        assertEquals(1, ExternalCatalogOptionSourceProvider.byIdsCalls());
        Map<?, ?> payload = assertInstanceOf(Map.class, ExternalCatalogOptionSourceProvider.lastByIdsPayload());
        assertEquals("SUBST FG", payload.get("tipoEvento"));
        List<LookupFilterRequest> filters = ExternalCatalogOptionSourceProvider.lastByIdsFilterRequest();
        assertEquals(1, filters.size());
        assertEquals("tipoEvento", filters.get(0).field());
        assertEquals("equals", filters.get(0).operator());
        assertEquals("SUBST FG", filters.get(0).value());
    }

    @Test
    void providerBackedStructuredFilterRejectsUndeclaredFieldBeforeCustomProviderResolution() {
        ExternalCatalogOptionSourceProvider.resetCounters();

        ResponseEntity<String> response = postJson(
                "/employees/option-sources/externalDependencyLookup/options/filter?page=0&size=10",
                """
                {
                  "filters": [
                    { "field": "classeEvento", "operator": "equals", "value": "SUBST FG" }
                  ]
                }
                """
        );

        assertEquals(422, response.getStatusCode().value());
        assertTrue(response.getBody().contains("Unsupported entity lookup filter field: classeEvento"));
        assertEquals(0, ExternalCatalogOptionSourceProvider.supportCalls());
        assertEquals(0, ExternalCatalogOptionSourceProvider.filterCalls());
    }

    @Test
    void providerBackedStructuredFilterRejectsUndeclaredOperatorBeforeCustomProviderResolution() {
        ExternalCatalogOptionSourceProvider.resetCounters();

        ResponseEntity<String> response = postJson(
                "/employees/option-sources/externalDependencyLookup/options/filter?page=0&size=10",
                """
                {
                  "filters": [
                    { "field": "tipoEvento", "operator": "startsWith", "value": "SUBST" }
                  ]
                }
                """
        );

        assertEquals(422, response.getStatusCode().value());
        assertTrue(response.getBody().contains("Unsupported entity lookup filter operator 'startsWith'"));
        assertEquals(0, ExternalCatalogOptionSourceProvider.supportCalls());
        assertEquals(0, ExternalCatalogOptionSourceProvider.filterCalls());
    }

    @Test
    void providerBackedOptionSourceAcceptsNullDeclaredDependency() throws Exception {
        ExternalCatalogOptionSourceProvider.resetCounters();

        ResponseEntity<String> response = postJson(
                "/employees/option-sources/externalDependencyLookup/options/filter?page=0&size=10",
                """
                {
                  "filter": {
                    "tipoEvento": null
                  }
                }
                """
        );

        assertEquals(200, response.getStatusCode().value());
        Map<?, ?> payload = assertInstanceOf(Map.class, ExternalCatalogOptionSourceProvider.lastFilterPayload());
        assertTrue(payload.containsKey("tipoEvento"));
        assertEquals(null, payload.get("tipoEvento"));
    }

    @Test
    void providerBackedOptionSourcePreservesLegacyFilterFieldNamedSearch() throws Exception {
        ExternalCatalogOptionSourceProvider.resetCounters();

        ResponseEntity<String> response = postJson(
                "/employees/option-sources/externalDepartmentLookup/options/filter?page=0&size=10",
                """
                {
                  "search": "Alice"
                }
                """
        );

        assertEquals(200, response.getStatusCode().value());
        assertEquals(1, ExternalCatalogOptionSourceProvider.filterCalls());
        Object payload = ExternalCatalogOptionSourceProvider.lastFilterPayload();
        assertEquals("EmployeeFilterDTO", payload.getClass().getSimpleName());
    }

    @Test
    void invalidPayloadIsRejectedBeforeCustomProviderResolution() {
        ExternalCatalogOptionSourceProvider.resetCounters();

        ResponseEntity<String> response = postJson(
                "/employees/option-sources/externalDepartmentLookup/options/filter?page=0&size=10",
                """
                {
                  "sort": "unsupported"
                }
                """
        );

        assertEquals(422, response.getStatusCode().value());
        assertTrue(response.getBody().contains("Structured sorting is not supported"));
        assertEquals(0, ExternalCatalogOptionSourceProvider.supportCalls());
        assertEquals(0, ExternalCatalogOptionSourceProvider.filterCalls());
    }

    @Test
    void querySortIsRejectedBeforeCustomProviderResolution() {
        ExternalCatalogOptionSourceProvider.resetCounters();

        ResponseEntity<String> response = postJson(
                "/employees/option-sources/externalDepartmentLookup/options/filter?sort=unsupported,asc&page=0&size=10",
                "{}"
        );

        assertEquals(422, response.getStatusCode().value());
        assertTrue(response.getBody().contains("Structured sorting is not supported"));
        assertEquals(0, ExternalCatalogOptionSourceProvider.supportCalls());
        assertEquals(0, ExternalCatalogOptionSourceProvider.filterCalls());
    }

    @Test
    void querySortWithUnsupportedDirectionIsRejectedBeforeCustomProviderResolution() {
        ExternalCatalogOptionSourceProvider.resetCounters();

        ResponseEntity<String> response = postJson(
                "/employees/option-sources/externalDepartmentLookup/options/filter?sort=label,sideways&page=0&size=10",
                "{}"
        );

        assertEquals(422, response.getStatusCode().value());
        assertTrue(response.getBody().contains("Unsupported sort direction"));
        assertEquals(0, ExternalCatalogOptionSourceProvider.supportCalls());
        assertEquals(0, ExternalCatalogOptionSourceProvider.filterCalls());
    }

    @Test
    void shortSearchIsRejectedBeforeCustomProviderResolution() {
        ExternalCatalogOptionSourceProvider.resetCounters();

        ResponseEntity<String> response = postJson(
                "/employees/option-sources/externalDepartmentLookup/options/filter?search=ex&page=0&size=10",
                "{}"
        );

        assertEquals(422, response.getStatusCode().value());
        assertTrue(response.getBody().contains("Search term must have at least 3 characters"));
        assertEquals(0, ExternalCatalogOptionSourceProvider.supportCalls());
        assertEquals(0, ExternalCatalogOptionSourceProvider.filterCalls());
    }

    @Test
    void pageSizeOverPolicyLimitIsRejectedBeforeCustomProviderResolution() {
        ExternalCatalogOptionSourceProvider.resetCounters();

        ResponseEntity<String> response = postJson(
                "/employees/option-sources/externalDepartmentLookup/options/filter?search=external&page=0&size=21",
                "{}"
        );

        assertEquals(422, response.getStatusCode().value());
        assertTrue(response.getBody().contains("Maximum option source page size exceeded"));
        assertEquals(0, ExternalCatalogOptionSourceProvider.supportCalls());
        assertEquals(0, ExternalCatalogOptionSourceProvider.filterCalls());
    }

    @Test
    void missingByIdsCapabilityReturnsNotImplementedBeforeCustomProviderResolution() throws Exception {
        ExternalCatalogOptionSourceProvider.resetCounters();

        ResponseEntity<String> filterResponse = postJson(
                "/employees/option-sources/externalFilterOnlyLookup/options/filter?page=0&size=10",
                "{}"
        );

        assertEquals(200, filterResponse.getStatusCode().value());
        JsonNode filterBody = body(filterResponse);
        assertEquals(2, filterBody.path("content").size());
        assertEquals(1, ExternalCatalogOptionSourceProvider.filterCalls());

        ExternalCatalogOptionSourceProvider.resetCounters();
        ResponseEntity<String> byIdsResponse = get(
                "/employees/option-sources/externalFilterOnlyLookup/options/by-ids?ids=EXT-1"
        );

        assertEquals(501, byIdsResponse.getStatusCode().value());
        assertTrue(byIdsResponse.getBody().contains("Option source capability not supported"));
        assertEquals(0, ExternalCatalogOptionSourceProvider.supportCalls());
        assertEquals(0, ExternalCatalogOptionSourceProvider.byIdsCalls());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class NonJpaOptionSourceProviderTestConfiguration {

        @Bean
        OptionSourceProvider externalCatalogOptionSourceProvider() {
            return new ExternalCatalogOptionSourceProvider();
        }
    }

    private JsonNode findOptionSourceNode(JsonNode domain, String sourceKey) {
        for (JsonNode node : domain.path("nodes")) {
            if (sourceKey.equals(node.path("metadata").path("key").asText())) {
                return node;
            }
        }
        throw new AssertionError("Option source not found in semantic domain catalog: " + sourceKey);
    }

    private static final class ExternalCatalogOptionSourceProvider implements OptionSourceProvider, Ordered {
        private static final AtomicInteger SUPPORT_CALLS = new AtomicInteger();
        private static final AtomicInteger FILTER_CALLS = new AtomicInteger();
        private static final AtomicInteger BY_IDS_CALLS = new AtomicInteger();
        private static final AtomicReference<Object> LAST_FILTER_PAYLOAD = new AtomicReference<>();
        private static final AtomicReference<Object> LAST_BY_IDS_PAYLOAD = new AtomicReference<>();
        private static final AtomicReference<List<LookupFilterRequest>> LAST_FILTER_REQUEST = new AtomicReference<>(List.of());
        private static final AtomicReference<List<LookupFilterRequest>> LAST_BY_IDS_FILTER_REQUEST = new AtomicReference<>(List.of());
        private static final List<OptionDTO<Object>> OPTIONS = List.of(
                option("EXT-1", "External Human Resources"),
                option("EXT-2", "External Operations")
        );
        private static final Map<String, OptionDTO<Object>> OPTIONS_BY_ID = Map.of(
                "EXT-1", OPTIONS.get(0),
                "EXT-2", OPTIONS.get(1)
        );

        @Override
        public boolean supports(
                OptionSourceDescriptor descriptor,
                OptionSourceExecutionContext context,
                OptionSourceOperation operation
        ) {
            SUPPORT_CALLS.incrementAndGet();
            return descriptor != null
                    && ("externalDepartmentLookup".equals(descriptor.key())
                    || "externalDependencyLookup".equals(descriptor.key())
                    || "externalFilterOnlyLookup".equals(descriptor.key()))
                    && operation == context.operation();
        }

        @Override
        public Page<OptionDTO<Object>> filter(OptionSourceExecutionRequest<?> request) {
            FILTER_CALLS.incrementAndGet();
            LAST_FILTER_PAYLOAD.set(request.filterPayload());
            LAST_FILTER_REQUEST.set(request.filters());
            return new PageImpl<>(OPTIONS, request.pageable(), OPTIONS.size());
        }

        @Override
        public List<OptionDTO<Object>> byIds(OptionSourceExecutionRequest<?> request) {
            BY_IDS_CALLS.incrementAndGet();
            LAST_BY_IDS_PAYLOAD.set(request.filterPayload());
            LAST_BY_IDS_FILTER_REQUEST.set(request.filters());
            return request.ids().stream()
                    .map(String::valueOf)
                    .map(OPTIONS_BY_ID::get)
                    .filter(option -> option != null)
                    .toList();
        }

        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE;
        }

        private static OptionDTO<Object> option(String id, String label) {
            return new OptionDTO<>(id, label, Map.of("catalogCode", "department-catalog"));
        }

        private static void resetCounters() {
            SUPPORT_CALLS.set(0);
            FILTER_CALLS.set(0);
            BY_IDS_CALLS.set(0);
            LAST_FILTER_PAYLOAD.set(null);
            LAST_BY_IDS_PAYLOAD.set(null);
            LAST_FILTER_REQUEST.set(List.of());
            LAST_BY_IDS_FILTER_REQUEST.set(List.of());
        }

        private static int supportCalls() {
            return SUPPORT_CALLS.get();
        }

        private static int filterCalls() {
            return FILTER_CALLS.get();
        }

        private static int byIdsCalls() {
            return BY_IDS_CALLS.get();
        }

        private static Object lastFilterPayload() {
            return LAST_FILTER_PAYLOAD.get();
        }

        private static Object lastByIdsPayload() {
            return LAST_BY_IDS_PAYLOAD.get();
        }

        private static List<LookupFilterRequest> lastFilterRequest() {
            return LAST_FILTER_REQUEST.get();
        }

        private static List<LookupFilterRequest> lastByIdsFilterRequest() {
            return LAST_BY_IDS_FILTER_REQUEST.get();
        }
    }
}
