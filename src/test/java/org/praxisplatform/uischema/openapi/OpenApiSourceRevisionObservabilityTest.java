package org.praxisplatform.uischema.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.controller.docs.OpenApiDocsSupport;
import org.slf4j.Logger;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Counterexample to treating an unchanged local cache/transport/MVC fingerprint as a source
 * revision. Exercises the real SpringDoc customizer contract and Metadata cache/freshness gate;
 * source generation is in process, so this is not an HTTP or durable READY proof.
 */
class OpenApiSourceRevisionObservabilityTest {
    @Test
    void dynamicCustomizerChangesAnotherGroupsOperationIdWithoutChangingLocalRevisionSignals() throws Exception {
        var externalOperationId = new AtomicReference<>("other.query");
        GlobalOpenApiCustomizer customizer = document -> {
            if (document.getPaths().containsKey("/other"))
                document.getPaths().get("/other").getGet().setOperationId(externalOperationId.get());
        };
        var source = new CustomizerSource(customizer);
        var mappings = new RequestMappingHandlerMapping();
        var endpoint = new Endpoint();
        mappings.registerMapping(RequestMappingInfo.paths("/other").methods(RequestMethod.GET).build(),
                endpoint, Endpoint.class.getMethod("read"));
        var initialMappings = java.util.Map.copyOf(mappings.getHandlerMethods());
        try (var transport = new OpenApiInternalRestTemplate(Duration.ofSeconds(1), Duration.ofSeconds(10))) {
            var service = new CachedOpenApiDocumentService(transport, io.swagger.v3.core.util.Json.mapper(),
                    source, true, Duration.ofSeconds(5));
            ReflectionTestUtils.setField(service, "openApiBasePath", "/v3/api-docs");
            var groups = Set.of("bulk", "other");
            service.withFreshBulkLifecycleDocuments(groups, () -> null);
            Object initialEpoch = ReflectionTestUtils.getField(service, "cacheInvalidationEpoch");
            long initialTransportRevision = transport.transportRevision();
            var guards = new AtomicInteger();
            service.installBulkLifecycleInvalidationGuard(() -> {
                assertThat(service.getDocumentForGroupStrict("other").at("/paths/~1other/get/operationId").asText())
                        .isEqualTo("other.query");
                guards.incrementAndGet();
            });

            // A supported arbitrary customizer input changes, without calling any cache API.
            // The new operationId collides with an operation in a different published group.
            externalOperationId.set("bulk.confirm");
            assertThat(mappings.getHandlerMethods()).isEqualTo(initialMappings);
            assertThat(ReflectionTestUtils.getField(service, "cacheInvalidationEpoch")).isEqualTo(initialEpoch);
            assertThat(transport.transportRevision()).isEqualTo(initialTransportRevision);
            assertThat(guards).hasValue(0);
            assertThat(service.getDocumentForGroupStrict("other").at("/paths/~1other/get/operationId").asText())
                    .isEqualTo("other.query");
            assertThat(source.generate("other").at("/paths/~1other/get/operationId").asText())
                    .isEqualTo("bulk.confirm");

            var admissions = new AtomicInteger();
            assertThrows(IllegalStateException.class,
                    () -> service.withFreshBulkLifecycleDocuments(groups, admissions::incrementAndGet));
            assertThat(guards).hasValue(1);
            assertThat(admissions).hasValue(0);
            assertThat((java.util.Map<?, ?>) ReflectionTestUtils.getField(service, "documentCache")).isEmpty();
        }
    }

    public static class Endpoint {
        public String read() { return "value"; }
    }

    private static final class CustomizerSource extends OpenApiDocsSupport {
        private final GlobalOpenApiCustomizer customizer;
        private CustomizerSource(GlobalOpenApiCustomizer customizer) { this.customizer = customizer; }
        private JsonNode generate(String group) {
            var document = new OpenAPI().paths(new Paths().addPathItem("/" + group,
                    new PathItem().get(new Operation().operationId("bulk".equals(group) ? "bulk.confirm" : "other.query"))));
            customizer.customise(document);
            return io.swagger.v3.core.util.Json.mapper().valueToTree(document);
        }
        @Override public JsonNode fetchOpenApiDocument(RestTemplate client, String base, String group, Logger logger) {
            return generate(group);
        }
        @Override public JsonNode fetchFreshOpenApiGroupDocument(RestTemplate client, String base, String group, Logger logger) {
            return generate(group);
        }
    }
}
