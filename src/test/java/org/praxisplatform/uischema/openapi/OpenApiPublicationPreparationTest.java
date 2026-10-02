package org.praxisplatform.uischema.openapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.controller.docs.OpenApiDocsSupport;
import org.slf4j.Logger;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenApiPublicationPreparationTest {
    private static final String BASE = "/v3/api-docs";
    private static final String OAS = " {\"openapi\":\"3.1.0\",\"info\":{\"title\":\"Pilot\",\"version\":\"1\"},\"x-count\":7,\"paths\":{}}\n";
    private static final String CONFIG = "{\"urls\":[{\"name\":\"a\",\"url\":\"/v3/api-docs/a\"},{\"name\":\"other\",\"url\":\"/v3/api-docs/other\"}]}";

    @Test
    void capturesExactlyRootConfigAndAllGroupsWithoutPublishingOrSeedingTheOrdinaryCache() {
        var observed = new ArrayList<String>();
        var source = new LocalSource() {
            @Override
            public OpenApiDocumentCapture captureProducerResponse(RestTemplate client, String path,
                    Logger logger, ObjectMapper parser) {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                observed.add(path);
                return capture(path.endsWith("/swagger-config") ? CONFIG : OAS, parser);
            }
            @Override
            public com.fasterxml.jackson.databind.JsonNode fetchOpenApiDocument(RestTemplate client,
                    String base, String group, Logger logger) {
                throw new IllegalStateException("ordinary cache was not seeded");
            }
        };
        var mapper = new ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);
        try (var client = client()) {
            var service = service(client, mapper, source);
            var candidate = service.prepareBulkOpenApiPublication(Set.of("a", "other"));
            assertThat(observed).containsExactly(BASE, BASE + "/a", BASE + "/other", BASE + "/swagger-config");
            assertThat(candidate.responsePaths()).containsExactlyInAnyOrderElementsOf(observed);
            assertThat(candidate.groupDocument("a").path("x-count").isBigInteger()).isTrue();
            assertThat(candidate.digest()).matches("sha256:[0-9a-f]{64}");
            assertThat(candidate.response(BASE).bytes()).isEqualTo(OAS.getBytes(StandardCharsets.UTF_8));
            assertThatThrownBy(() -> service.getDocumentForGroup("a"))
                    .isInstanceOf(IllegalStateException.class).hasStackTraceContaining("ordinary cache was not seeded");
        }
    }

    @Test
    void digestBindsRootConfigOtherGroupsAndWireBytesRegardlessOfMapInsertionOrder() {
        var owner = new Object();
        var captures = responses();
        var first = new OpenApiPublicationCandidate(owner, 3, 7, BASE, "", Set.of("a", "other"), captures);
        var reverse = new LinkedHashMap<String, OpenApiDocumentCapture>();
        captures.entrySet().stream().sorted(Map.Entry.<String, OpenApiDocumentCapture>comparingByKey().reversed())
                .forEach(entry -> reverse.put(entry.getKey(), entry.getValue()));
        assertThat(new OpenApiPublicationCandidate(owner, 3, 7, BASE, "", Set.of("other", "a"), reverse).digest())
                .isEqualTo(first.digest());
        for (String path : captures.keySet()) {
            var changed = new LinkedHashMap<>(captures);
            changed.put(path, capture(" " + new String(captures.get(path).bytes(), StandardCharsets.UTF_8), new ObjectMapper()));
            assertThat(new OpenApiPublicationCandidate(owner, 3, 7, BASE, "", Set.of("a", "other"), changed).digest())
                    .isNotEqualTo(first.digest());
        }
        var changedWhitespace = new LinkedHashMap<>(captures);
        changedWhitespace.put(BASE, capture(" " + OAS, new ObjectMapper()));
        assertThat(new OpenApiPublicationCandidate(owner, 3, 7, BASE, "", Set.of("a", "other"), changedWhitespace).digest())
                .isNotEqualTo(first.digest());
        assertThat(first.belongsTo(owner, 3, 7)).isTrue();
        assertThat(first.belongsTo(new Object(), 3, 7)).isFalse();
        assertThat(first.belongsTo(owner, 4, 7)).isFalse();
        assertThat(first.belongsTo(owner, 3, 8)).isFalse();
        assertThatThrownBy(() -> first.responsePaths().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> first.groupDocument("missing")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void partialOrAmbiguousRouteSetsCannotBecomeCandidates() {
        for (String reserved : Set.of("swagger-config", "a.yaml", " ", "a\n", ".", "..", "a/b", "a\\b", "a;b", "a%2fb", "a?b", "a#b", "a b", "a\tb"))
            assertThatThrownBy(() -> new OpenApiPublicationCandidate(this, 0, 0, BASE, "",
                    Set.of(reserved), responses())).isInstanceOf(IllegalArgumentException.class);
        var missing = responses(); missing.remove(BASE + "/other");
        assertThatThrownBy(() -> new OpenApiPublicationCandidate(this, 0, 0, BASE, "",
                Set.of("a", "other"), missing)).isInstanceOf(IllegalArgumentException.class);
        var extra = responses(); extra.put(BASE + "/hidden", capture("{}", new ObjectMapper()));
        assertThatThrownBy(() -> new OpenApiPublicationCandidate(this, 0, 0, BASE, "",
                Set.of("a", "other"), extra)).isInstanceOf(IllegalArgumentException.class);
        for (String invalid : Set.of("relative", "/v3/api-docs/", "/v3/../api-docs", "/v3/api-docs?x=1", "/v3/%2fapi-docs", "/v3;/api-docs", "/v3//api-docs", "/v3/./api-docs", "/./v3/api-docs", "/v3 api-docs"))
            assertThatThrownBy(() -> new OpenApiPublicationCandidate(this, 0, 0, invalid, "",
                    Set.of("a", "other"), responses())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void successfulHttpJsonErrorsAndMalformedProducerDocumentsCannotBecomeCandidates() {
        for (String path : responses().keySet()) {
            var invalid = responses();
            invalid.put(path, capture("{\"error\":\"unavailable\"}", new ObjectMapper()));
            assertThatThrownBy(() -> new OpenApiPublicationCandidate(this, 0, 0, BASE, "",
                    Set.of("a", "other"), invalid)).isInstanceOf(IllegalArgumentException.class);
        }
        for (String json : new String[]{"{\"openapi\":\"2.0\"}",
                OAS.replace("\"paths\":{}", "\"paths\":[]"),
                OAS.replace("\"title\":\"Pilot\"", "\"title\":7"),
                OAS.replace("\"version\":\"1\"", "\"version\":\" \"")}) {
            var invalid = responses(); invalid.put(BASE, capture(json, new ObjectMapper()));
            assertThatThrownBy(() -> new OpenApiPublicationCandidate(this, 0, 0, BASE, "",
                    Set.of("a", "other"), invalid)).isInstanceOf(IllegalArgumentException.class);
        }
        for (String json : new String[]{"{}", "{\"urls\":[]}", "{\"urls\":{}}",
                "{\"urls\":[{\"name\":\"a\",\"url\":7}]}",
                CONFIG.replace("\"name\":\"other\"", "\"name\":\"a\"")}) {
            var invalid = responses(); invalid.put(BASE + "/swagger-config", capture(json, new ObjectMapper()));
            assertThatThrownBy(() -> new OpenApiPublicationCandidate(this, 0, 0, BASE, "",
                    Set.of("a", "other"), invalid)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void invalidationDuringSourceReadDiscardsThePreparedResponseSet() {
        var holder = new CachedOpenApiDocumentService[1];
        var source = new LocalSource() {
            @Override
            public OpenApiDocumentCapture captureProducerResponse(RestTemplate client, String path,
                    Logger logger, ObjectMapper parser) {
                if (path.equals(BASE)) holder[0].clearCaches();
                return capture(path.endsWith("/swagger-config") ? CONFIG : OAS, parser);
            }
        };
        try (var client = client()) {
            holder[0] = service(client, new ObjectMapper(), source);
            assertThatThrownBy(() -> holder[0].prepareBulkOpenApiPublication(Set.of("a")))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("invalidated during preparation");
        }
    }

    @Test
    void preparationRejectsAnExistingTransactionOrCacheReadLockBeforeCallingTheSource() {
        var source = new LocalSource() {
            @Override
            public OpenApiDocumentCapture captureProducerResponse(RestTemplate client, String path,
                    Logger logger, ObjectMapper parser) { throw new AssertionError("source must not be called"); }
        };
        try (var client = client()) {
            var service = service(client, new ObjectMapper(), source);
            assertThatThrownBy(() -> service.withSchemaCacheReadLock(() -> service.prepareBulkOpenApiPublication(Set.of("a"))))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("outside transactions");
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try {
                assertThatThrownBy(() -> service.prepareBulkOpenApiPublication(Set.of("a")))
                        .isInstanceOf(IllegalStateException.class).hasMessageContaining("outside transactions");
            } finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
        }
    }

    @Test
    void configMustAdvertiseExactlyCapturedRoutesAndServletContextWithoutAliases() {
        var contextual = responses();
        contextual.put(BASE + "/swagger-config", capture(CONFIG.replace("/v3/", "/host/v3/"), new ObjectMapper()));
        var candidate = new OpenApiPublicationCandidate(this, 0, 0, BASE, "/host",
                Set.of("a", "other"), contextual);
        assertThat(candidate.contextPath()).isEqualTo("/host");
        for (String json : new String[]{
                CONFIG.replace("/v3/api-docs/a", "https://elsewhere.example/v3/api-docs/a"),
                CONFIG.replace("/v3/api-docs/a", "/v3/api-docs/a?x=1"),
                CONFIG.replace("/v3/api-docs/a", "/v3/api-docs/%61"),
                CONFIG.replace("/v3/api-docs/other", "/v3/api-docs/a"),
                CONFIG.replace("/v3/api-docs/other", "/v3/api-docs/hidden"),
                CONFIG.replace("/v3/", "/wrong/v3/"),
                CONFIG.replace("{\"urls\"", "{\"configUrl\":\"https://elsewhere.example/config\",\"urls\""),
                CONFIG.replace("{\"urls\"", "{\"url\":\"/dynamic-document\",\"urls\"")}) {
            var invalid = responses();
            invalid.put(BASE + "/swagger-config", capture(json, new ObjectMapper()));
            assertThatThrownBy(() -> new OpenApiPublicationCandidate(this, 0, 0, BASE, "",
                    Set.of("a", "other"), invalid)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new OpenApiPublicationCandidate(this, 0, 0, BASE, "/host",
                Set.of("a", "other"), responses())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OpenApiPublicationCandidate(this, 0, 0, BASE, "",
                Set.of("a"), responses())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void preparedCompositionReadsCanonicalRequestSchemaWithoutFetchingOrSeedingPublicCaches() {
        String schemaDoc = OAS.replace("\"paths\":{}", "\"paths\":{\"/items\":{\"post\":{\"operationId\":\"change\","
                + "\"requestBody\":{\"content\":{\"application/json\":{\"schema\":{\"type\":\"object\","
                + "\"properties\":{\"code\":{\"type\":\"string\"}}}}}}}}}");
        var fetches = new java.util.concurrent.atomic.AtomicInteger();
        var source = new LocalSource() {
            @Override public OpenApiDocumentCapture captureProducerResponse(RestTemplate c, String path,
                    Logger logger, ObjectMapper parser) {
                fetches.incrementAndGet();
                return capture(path.endsWith("/swagger-config") ? CONFIG : schemaDoc, parser);
            }
            @Override public com.fasterxml.jackson.databind.JsonNode fetchOpenApiDocument(RestTemplate c,
                    String base, String group, Logger logger) { throw new IllegalStateException("no public document"); }
        };
        try (var client = client()) {
            var service = service(client, new ObjectMapper(), source);
            var candidate = service.prepareBulkOpenApiPublication(Set.of("a", "other"));
            var result = service.withPreparedBulkOpenApiPublication(candidate, () -> {
                var schema = service.requireRequestSchema(new CanonicalOperationRef("a", "change", "/items", "POST"));
                assertThat(schema.schema().path("properties").path("code").path("type").asText()).isEqualTo("string");
                ((com.fasterxml.jackson.databind.node.ObjectNode) service.getDocumentForGroup("a")).put("tampered", true);
                assertThat(service.getDocumentForGroupStrict("a").has("tampered")).isFalse();
                assertThatThrownBy(() -> service.getDocumentForGroup("missing")).isInstanceOf(IllegalStateException.class);
                return schema;
            });
            assertThat(result.operation().operationId()).isEqualTo("change");
            assertThat(fetches).hasValue(4);
            assertThatThrownBy(() -> service.getDocumentForGroup("a")).hasStackTraceContaining("no public document");
        }
    }

    @Test
    void preparedSchemaHashesAreIsolatedAndCleanedAfterBothSuccessAndFailure() {
        try (var client = client()) {
            var service = service(client, new ObjectMapper(), capturedSource());
            var candidate = service.prepareBulkOpenApiPublication(Set.of("a", "other"));
            String oldHash = service.getOrComputeSchemaHash("existing", () -> capture("{\"value\":1}", new ObjectMapper()).document());
            String candidateHash = service.withPreparedBulkOpenApiPublication(candidate,
                    () -> {
                        assertThatThrownBy(() -> service.getOrComputeSchemaHash(null, () -> candidate.groupDocument("a")))
                                .isInstanceOf(NullPointerException.class);
                        return service.getOrComputeSchemaHash("existing", () -> capture("{\"value\":2}", new ObjectMapper()).document());
                    });
            assertThat(candidateHash).isNotEqualTo(oldHash);
            assertThat(service.getOrComputeSchemaHash("existing", () -> { throw new AssertionError(); })).isEqualTo(oldHash);
            assertThatThrownBy(() -> service.withPreparedBulkOpenApiPublication(candidate, () -> {
                service.getOrComputeSchemaHash("new", () -> capture("{\"value\":2}", new ObjectMapper()).document());
                throw new IllegalArgumentException("compiler rejected");
            })).isInstanceOf(IllegalArgumentException.class).hasMessage("compiler rejected");
            assertThat(service.getOrComputeSchemaHash("new", () -> capture("{\"value\":1}", new ObjectMapper()).document()))
                    .isEqualTo(oldHash);
            assertThat(service.withPreparedBulkOpenApiPublication(candidate, () -> "again")).isEqualTo("again");
        }
    }

    @Test
    void foreignInvalidatedAndChangedTransportCandidatesDoNotInvokeComposition() {
        try (var client = client(); var otherClient = client()) {
            var service = service(client, new ObjectMapper(), capturedSource());
            var other = service(otherClient, new ObjectMapper(), capturedSource());
            var candidate = service.prepareBulkOpenApiPublication(Set.of("a", "other"));
            java.util.function.Supplier<String> forbidden = () -> { throw new AssertionError("must not compose"); };
            assertThatThrownBy(() -> other.withPreparedBulkOpenApiPublication(candidate, forbidden))
                    .isInstanceOf(IllegalStateException.class);
            service.clearCaches();
            assertThatThrownBy(() -> service.withPreparedBulkOpenApiPublication(candidate, forbidden))
                    .isInstanceOf(IllegalStateException.class);
            var next = service.prepareBulkOpenApiPublication(Set.of("a", "other"));
            var factory = client.getRequestFactory();
            client.setRequestFactory(factory);
            assertThatThrownBy(() -> service.withPreparedBulkOpenApiPublication(next, forbidden))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void preparedCompositionRejectsNestingAndTransactionOrLockEntryAndCleansAfterTransportDrift() {
        try (var client = client()) {
            var service = service(client, new ObjectMapper(), capturedSource());
            var candidate = service.prepareBulkOpenApiPublication(Set.of("a", "other"));
            assertThatThrownBy(() -> service.withSchemaCacheReadLock(
                    () -> service.withPreparedBulkOpenApiPublication(candidate, () -> "no")))
                    .isInstanceOf(IllegalStateException.class);
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try {
                assertThatThrownBy(() -> service.withPreparedBulkOpenApiPublication(candidate, () -> "no"))
                        .isInstanceOf(IllegalStateException.class);
            } finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
            service.withPreparedBulkOpenApiPublication(candidate, () -> {
                assertThatThrownBy(() -> service.withPreparedBulkOpenApiPublication(candidate, () -> "nested"))
                        .isInstanceOf(IllegalStateException.class);
                assertThatThrownBy(service::clearCaches).isInstanceOf(IllegalStateException.class);
                return null;
            });
            assertThatThrownBy(() -> service.withPreparedBulkOpenApiPublication(candidate, () -> {
                client.setRequestFactory(client.getRequestFactory());
                return "must not escape";
            })).isInstanceOf(IllegalStateException.class);
            var next = service.prepareBulkOpenApiPublication(Set.of("a", "other"));
            assertThat(service.withPreparedBulkOpenApiPublication(next, () -> "clean")).isEqualTo("clean");
        }
    }

    private static LocalSource capturedSource() {
        return new LocalSource() {
            @Override public OpenApiDocumentCapture captureProducerResponse(RestTemplate c, String path,
                    Logger logger, ObjectMapper parser) {
                return capture(path.endsWith("/swagger-config") ? CONFIG : OAS, parser);
            }
        };
    }

    private static class LocalSource extends OpenApiDocsSupport {
        private CachedOpenApiDocumentService fixtureOwner;
        @Override public String localPublicationContextPath() { return ""; }
        @Override public final OpenApiDocumentCapture fetchFreshOpenApiResponseCapture(RestTemplate client,
                String path, Logger logger, ObjectMapper parser) {
            // Unit fixture explicitly simulates the direct Servlet producer admission; not HTTP/IAM proof.
            var context = new org.springframework.mock.web.MockServletContext();
            context.setContextPath(localPublicationContextPath());
            var request = new org.springframework.mock.web.MockHttpServletRequest(context, "GET", localPublicationContextPath() + path);
            request.setContextPath(localPublicationContextPath());
            request.addHeader(OpenApiInternalRestTemplate.PRODUCER_CAPTURE_HEADER,
                    ((OpenApiInternalRestTemplate) client).producerCaptureToken(path));
            assertThat(fixtureOwner.consumeBulkOpenApiProducer(request)).isTrue();
            return captureProducerResponse(client, path, logger, parser);
        }
        public OpenApiDocumentCapture captureProducerResponse(RestTemplate client, String path, Logger logger, ObjectMapper parser) {
            throw new UnsupportedOperationException("Fixture response must be specified");
        }
    }

    private static Map<String, OpenApiDocumentCapture> responses() {
        var result = new LinkedHashMap<String, OpenApiDocumentCapture>();
        for (String path : new String[]{BASE, BASE + "/a", BASE + "/other", BASE + "/swagger-config"})
            result.put(path, capture(path.endsWith("/swagger-config") ? CONFIG : OAS, new ObjectMapper()));
        return result;
    }
    private static OpenApiDocumentCapture capture(String json, ObjectMapper mapper) {
        return OpenApiDocumentCapture.parse(json.getBytes(StandardCharsets.UTF_8), mapper);
    }
    private static OpenApiInternalRestTemplate client() {
        return new OpenApiInternalRestTemplate(Duration.ofSeconds(1), Duration.ofSeconds(2));
    }
    private static CachedOpenApiDocumentService service(OpenApiInternalRestTemplate client,
            ObjectMapper mapper, OpenApiDocsSupport source) {
        var result = new CachedOpenApiDocumentService(client, mapper, source, true, Duration.ofSeconds(3));
        ReflectionTestUtils.setField(result, "openApiBasePath", BASE);
        ((LocalSource) source).fixtureOwner = result;
        return result;
    }
}
