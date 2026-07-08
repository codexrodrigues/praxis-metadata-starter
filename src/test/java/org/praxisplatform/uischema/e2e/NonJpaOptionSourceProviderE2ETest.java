package org.praxisplatform.uischema.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.dto.OptionDTO;
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
    void providerBackedOptionSourceAcceptsDeclaredDependencyOutsideResourceFilterDto() throws Exception {
        ExternalCatalogOptionSourceProvider.resetCounters();

        ResponseEntity<String> response = postJson(
                "/employees/option-sources/externalDependencyLookup/options/filter?page=0&size=10",
                """
                {
                  "filter": {
                    "tipoEvento": "SUBST FG"
                  }
                }
                """
        );

        assertEquals(200, response.getStatusCode().value());
        JsonNode body = body(response);
        assertEquals(2, body.path("content").size());
        assertEquals(1, ExternalCatalogOptionSourceProvider.filterCalls());
        Map<?, ?> payload = assertInstanceOf(Map.class, ExternalCatalogOptionSourceProvider.lastFilterPayload());
        assertEquals("SUBST FG", payload.get("tipoEvento"));
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

    private static final class ExternalCatalogOptionSourceProvider implements OptionSourceProvider, Ordered {
        private static final AtomicInteger SUPPORT_CALLS = new AtomicInteger();
        private static final AtomicInteger FILTER_CALLS = new AtomicInteger();
        private static final AtomicInteger BY_IDS_CALLS = new AtomicInteger();
        private static final AtomicReference<Object> LAST_FILTER_PAYLOAD = new AtomicReference<>();
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
            return new PageImpl<>(OPTIONS, request.pageable(), OPTIONS.size());
        }

        @Override
        public List<OptionDTO<Object>> byIds(OptionSourceExecutionRequest<?> request) {
            BY_IDS_CALLS.incrementAndGet();
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
    }
}
