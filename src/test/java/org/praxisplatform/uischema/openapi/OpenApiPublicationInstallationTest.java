package org.praxisplatform.uischema.openapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.controller.docs.OpenApiDocsSupport;
import org.slf4j.Logger;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestTemplate;
import static org.assertj.core.api.Assertions.*;

class OpenApiPublicationInstallationTest {
    private static final String BASE = "/v3/api-docs";
    private static final String OAS = " {\"openapi\":\"3.1.0\",\"info\":{\"title\":\"Pilot\",\"version\":\"1\"},\"paths\":{}}\n";
    private static final String CONFIG = "{\"urls\":[{\"name\":\"a\",\"url\":\"/v3/api-docs/a\"}]}";

    @Test void installationRequiresServingAndCommittedExactAuthorityAndReadsNeverFetch() {
        AtomicInteger fetches = new AtomicInteger();
        try (var client = client()) {
            var docs = docs(client, fetches);
            var candidate = docs.prepareBulkOpenApiPublication(Set.of("a"));
            assertThat(fetches).hasValue(3);
            assertThatThrownBy(() -> docs.publishedBulkOpenApiResponse("", BASE)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> docs.installBulkOpenApiPublication(candidate, 1)).isInstanceOf(IllegalStateException.class);
            activate(docs);
            AtomicLong committed = new AtomicLong();
            docs.installBulkLifecyclePublicationGuard((generation, digest) -> {
                if (generation != committed.get() || !candidate.digest().equals(digest))
                    throw new IllegalStateException("durable tuple differs");
            });
            assertThatThrownBy(() -> docs.installBulkOpenApiPublication(candidate, 1)).hasMessageContaining("tuple differs");
            assertThatThrownBy(() -> docs.publishedBulkOpenApiResponse("", BASE)).isInstanceOf(IllegalStateException.class);
            committed.set(1);
            docs.installBulkOpenApiPublication(candidate, 1);
            assertThat(docs.publishedBulkOpenApiResponse("", BASE).bytes()).isEqualTo(OAS.getBytes(StandardCharsets.UTF_8));
            assertThat(docs.publishedBulkOpenApiResponse("", BASE + "/swagger-config").bytes()).isEqualTo(CONFIG.getBytes(StandardCharsets.UTF_8));
            assertThatThrownBy(() -> docs.publishedBulkOpenApiResponse("/wrong", BASE)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> docs.publishedBulkOpenApiResponse("", BASE + "/missing")).isInstanceOf(IllegalArgumentException.class);
            committed.set(2);
            assertThatThrownBy(() -> docs.publishedBulkOpenApiResponse("", BASE)).hasMessageContaining("tuple differs");
            assertThat(fetches).hasValue(3);
        }
    }

    @Test void coldProcessEpochTransportAndFailedInvalidationCannotServeAnOldPhoto() {
        try (var client = client()) {
            var docs = docs(client, new AtomicInteger());
            var candidate = docs.prepareBulkOpenApiPublication(Set.of("a"));
            activate(docs);
            docs.installBulkLifecyclePublicationGuard((g, d) -> {});
            var cold = docs(client, new AtomicInteger());
            activate(cold);
            cold.installBulkLifecyclePublicationGuard((g, d) -> {});
            assertThatThrownBy(() -> cold.publishedBulkOpenApiResponse("", BASE)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> cold.installBulkOpenApiPublication(candidate, 1)).hasMessageContaining("foreign");
            docs.installBulkOpenApiPublication(candidate, 1);
            docs.installBulkLifecycleInvalidationGuard(() -> { throw new IllegalStateException("suspend failed"); });
            assertThatThrownBy(docs::clearCaches).hasMessageContaining("suspend failed");
            assertThatThrownBy(() -> docs.publishedBulkOpenApiResponse("", BASE)).hasMessageContaining("fence changed");
            var next = docs.prepareBulkOpenApiPublication(Set.of("a"));
            docs.installBulkOpenApiPublication(next, 2);
            client.setRequestFactory(client.getRequestFactory());
            assertThatThrownBy(() -> docs.publishedBulkOpenApiResponse("", BASE)).hasMessageContaining("fence changed");
        }
    }

    @Test void producerLeaseIsExactSingleUseAndRetiredAfterCaptureFailure() {
        var access = new OpenApiProducerCaptureAccess();
        try (var client = client()) {
            var budget = new OpenApiInternalRestTemplate.Deadline(Duration.ofSeconds(2));
            String[] token = new String[1];
            client.withProducerCapture(access, "", BASE, budget, () -> {
                token[0] = client.producerCaptureToken(BASE);
                assertThatThrownBy(() -> client.producerCaptureToken(BASE + "/a")).isInstanceOf(IllegalStateException.class);
                var request = request(BASE, token[0]);
                assertThat(access.consume(request)).isTrue();
                assertThat(access.consume(request)).isFalse();
                return null;
            });
            assertThat(client.producerCaptureToken(BASE)).isNull();
            assertThatThrownBy(() -> client.withProducerCapture(access, "", BASE, budget, () -> {
                token[0] = client.producerCaptureToken(BASE);
                throw new IllegalStateException("capture failed");
            })).hasMessage("capture failed");
            assertThat(client.producerCaptureToken(BASE)).isNull();
            assertThat(access.consume(request(BASE, token[0]))).isFalse();
        }
    }

    @Test void installationRejectsTransactionsAndAuthorityChangingTheSource() {
        try (var client = client()) {
            var docs = docs(client, new AtomicInteger());
            var candidate = docs.prepareBulkOpenApiPublication(Set.of("a"));
            activate(docs);
            docs.installBulkLifecyclePublicationGuard((g, d) -> client.setRequestFactory(client.getRequestFactory()));
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try { assertThatThrownBy(() -> docs.installBulkOpenApiPublication(candidate, 1)).hasMessageContaining("outside transactions"); }
            finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
            assertThatThrownBy(() -> docs.installBulkOpenApiPublication(candidate, 1)).hasMessageContaining("fence changed");
            assertThatThrownBy(() -> docs.publishedBulkOpenApiResponse("", BASE))
                    .isInstanceOf(GovernedOpenApiPublicationUnavailableException.class)
                    .hasCauseInstanceOf(IllegalStateException.class)
                    .hasRootCauseMessage("No governed OpenAPI publication is installed locally");
        }
    }

    @Test void servingUsesTheVerifiedServletContextWithoutMvcRequestThreadLocals() {
        var requestAvailable = new java.util.concurrent.atomic.AtomicBoolean(true);
        try (var client = client()) {
            var fixtureOwner = new CachedOpenApiDocumentService[1];
            var source = new OpenApiDocsSupport() {
                @Override public String localPublicationContextPath() {
                    if (!requestAvailable.get()) throw new IllegalStateException("no MVC request thread local");
                    return "";
                }
                @Override public OpenApiDocumentCapture fetchFreshOpenApiResponseCapture(RestTemplate c, String path, Logger l, ObjectMapper m) {
                    consumeFixture(fixtureOwner[0], c, path);
                    return OpenApiDocumentCapture.parse((path.endsWith("swagger-config") ? CONFIG : OAS).getBytes(StandardCharsets.UTF_8), m);
                }
            };
            var docs = new CachedOpenApiDocumentService(client, new ObjectMapper(), source, true, Duration.ofSeconds(3));
            ReflectionTestUtils.setField(docs, "openApiBasePath", BASE);
            fixtureOwner[0] = docs;
            var candidate = docs.prepareBulkOpenApiPublication(Set.of("a"));
            activate(docs);
            docs.installBulkLifecyclePublicationGuard((g, d) -> {});
            docs.installBulkOpenApiPublication(candidate, 1);
            requestAvailable.set(false);
            assertThat(docs.publishedBulkOpenApiResponse("", BASE).bytes()).isEqualTo(OAS.getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test void exactCaptureHelperForwardsOnlyItsScopedNonceAndOrdinaryCallsHaveNone() throws Exception {
        var access = new OpenApiProducerCaptureAccess();
        var observed = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(BASE, exchange -> {
            String token = exchange.getRequestHeaders().getFirst(OpenApiInternalRestTemplate.PRODUCER_CAPTURE_HEADER);
            observed.add(token == null ? "absent" : token);
            if (token != null) assertThat(access.consume(request(BASE, token))).isTrue();
            byte[] bytes = OAS.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var body = exchange.getResponseBody()) { body.write(bytes); }
        });
        server.start();
        try (var client = client()) {
            var source = new OpenApiDocsSupport();
            // Ephemeral transport fixture, as in OpenApiInternalRestTemplateTest; no host port/bridge.
            ReflectionTestUtils.setField(source, "openApiInternalBaseUrl", "http://127.0.0.1:" + server.getAddress().getPort());
            client.withProducerCapture(access, "", BASE, new OpenApiInternalRestTemplate.Deadline(Duration.ofSeconds(2)), () -> {
                source.fetchFreshOpenApiResponseCapture(client, BASE, org.slf4j.LoggerFactory.getLogger(getClass()), new ObjectMapper());
                return null;
            });
            source.fetchFreshOpenApiResponseCapture(client, BASE, org.slf4j.LoggerFactory.getLogger(getClass()), new ObjectMapper());
            assertThat(observed).hasSize(2);
            assertThat(observed.getFirst()).hasSize(43);
            assertThat(observed.getLast()).isEqualTo("absent");
            assertThat(access.consume(request(BASE, observed.getFirst()))).isFalse();
        } finally { server.stop(0); }
    }

    @Test void interceptorOrInitializerReplacementAndAbaCannotAuthorizeAnOldCandidate() {
        try (var client = client()) {
            var docs = docs(client, new AtomicInteger());
            var candidate = docs.prepareBulkOpenApiPublication(Set.of("a"));
            client.setInterceptors(java.util.List.of((request, body, execution) -> execution.execute(request, body)));
            assertThat(docs.supportsFreshBulkLifecycleComposition()).isFalse();
            assertThatThrownBy(() -> docs.prepareBulkOpenApiPublication(Set.of("a"))).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> client.getInterceptors().clear()).isInstanceOf(UnsupportedOperationException.class);
            client.setInterceptors(java.util.List.of());
            assertThat(docs.supportsFreshBulkLifecycleComposition()).isTrue();
            assertThatThrownBy(() -> docs.withPreparedBulkOpenApiPublication(candidate, () -> null)).hasMessageContaining("fence changed");
            var next = docs.prepareBulkOpenApiPublication(Set.of("a"));
            client.setClientHttpRequestInitializers(java.util.List.of(request -> {}));
            assertThat(docs.supportsFreshBulkLifecycleComposition()).isFalse();
            assertThatThrownBy(() -> client.getClientHttpRequestInitializers().clear()).isInstanceOf(UnsupportedOperationException.class);
            client.setClientHttpRequestInitializers(java.util.List.of());
            assertThatThrownBy(() -> docs.withPreparedBulkOpenApiPublication(next, () -> null)).hasMessageContaining("fence changed");
        }
    }

    @Test void interceptorRaceBeforeRequestAdmissionCannotInvokeTheCaptureCallback() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var access = new OpenApiProducerCaptureAccess(() -> {
            entered.countDown();
            try { if (!release.await(3, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("lease clock timed out"); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
            return System.nanoTime();
        });
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        var callback = new AtomicInteger();
        try (var client = client()) {
            var future = executor.submit(() -> client.withProducerCapture(access, "", BASE,
                    new OpenApiInternalRestTemplate.Deadline(Duration.ofSeconds(3)), callback::incrementAndGet));
            assertThat(entered.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            client.setInterceptors(java.util.List.of((request, body, execution) -> execution.execute(request, body)));
            release.countDown();
            assertThatThrownBy(() -> future.get(3, java.util.concurrent.TimeUnit.SECONDS))
                    .isInstanceOf(java.util.concurrent.ExecutionException.class).hasCauseInstanceOf(IllegalStateException.class);
            assertThat(callback).hasValue(0);
        } finally {
            release.countDown(); executor.shutdownNow();
            assertThat(executor.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test void oneServiceCannotAttestTwoServletContextsOrInstallAnotherContext() {
        try (var client = client()) {
            var docs = docs(client, new AtomicInteger());
            var candidate = docs.prepareBulkOpenApiPublication(Set.of("a"));
            docs.markBulkOpenApiServingInstalled("/other");
            docs.installBulkLifecyclePublicationGuard((g, d) -> {});
            assertThatThrownBy(() -> docs.markBulkOpenApiServingInstalled(""))
                    .hasMessageContaining("another Servlet context");
            assertThatThrownBy(() -> docs.installBulkOpenApiPublication(candidate, 1))
                    .hasMessageContaining("registered Servlet context");
            assertThatThrownBy(() -> docs.publishedBulkOpenApiResponse("", BASE)).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test void publishedReadCopiesOnlyRequestedGroupAndKeepsDefensiveIsolation() {
        var mapper = new ObjectMapper();
        var config = mapper.createObjectNode();
        var urls = config.putArray("urls");
        var groups = new java.util.TreeSet<String>();
        for (int i = 0; i < 53; i++) {
            String group = "group" + i;
            groups.add(group); urls.addObject().put("name", group).put("url", BASE + "/" + group);
        }
        var fetches = new AtomicInteger();
        try (var client = client()) {
            var docs = docs(client, fetches, config.toString());
            var candidate = docs.prepareBulkOpenApiPublication(groups);
            assertThat(candidate.groups()).hasSize(53);
            var counted = org.mockito.Mockito.mock(OpenApiPublicationCandidate.class,
                    org.mockito.Mockito.withSettings().mockMaker(org.mockito.MockMakers.INLINE)
                            .spiedInstance(candidate).defaultAnswer(org.mockito.Mockito.CALLS_REAL_METHODS));
            activate(docs);
            docs.installBulkLifecyclePublicationGuard((g, d) -> {}); // simulated durable tuple; PG tests attest it
            docs.installBulkOpenApiPublication(counted, 1);
            org.mockito.Mockito.clearInvocations(counted);
            var first = docs.getDocumentForGroupStrict("group0");
            ((com.fasterxml.jackson.databind.node.ObjectNode)first.get("info")).put("title", "tampered");
            assertThat(docs.getDocumentForGroupStrict("group0").at("/info/title").asText()).isEqualTo("Pilot");
            org.mockito.Mockito.verify(counted, org.mockito.Mockito.times(2)).groupDocument("group0");
            org.mockito.Mockito.verify(counted, org.mockito.Mockito.times(2))
                    .groupDocument(org.mockito.ArgumentMatchers.anyString());
            assertThat(fetches).hasValue(55); // preparation only: root, config and 53 groups
        }
    }

    private static void activate(CachedOpenApiDocumentService docs) {
        var context = org.mockito.Mockito.mock(jakarta.servlet.ServletContext.class);
        var registration = org.mockito.Mockito.mock(jakarta.servlet.FilterRegistration.class);
        org.mockito.Mockito.when(context.getFilterRegistration("bulkPublication")).thenReturn(registration);
        org.mockito.Mockito.when(context.getContextPath()).thenReturn("");
        org.mockito.Mockito.when(registration.getClassName()).thenReturn(GovernedOpenApiPublicationFilter.class.getName());
        org.mockito.Mockito.when(registration.getUrlPatternMappings()).thenReturn(java.util.List.of("/*"));
        org.mockito.Mockito.when(registration.getServletNameMappings()).thenReturn(java.util.List.of());
        try { new GovernedOpenApiPublicationFilter(docs).init(new org.springframework.mock.web.MockFilterConfig(context, "bulkPublication")); }
        catch (jakarta.servlet.ServletException failure) { throw new AssertionError(failure); }
    }

    private static MockHttpServletRequest request(String path, String token) {
        var request = new MockHttpServletRequest("GET", path);
        request.addHeader(OpenApiInternalRestTemplate.PRODUCER_CAPTURE_HEADER, token);
        return request;
    }
    private static OpenApiInternalRestTemplate client() {
        return new OpenApiInternalRestTemplate(Duration.ofSeconds(1), Duration.ofSeconds(2));
    }
    private static void consumeFixture(CachedOpenApiDocumentService owner, RestTemplate client, String path) {
        // Unit fixture admission only; the HTTP filter/provenance test supplies actual routing.
        assertThat(owner.consumeBulkOpenApiProducer(request(path,
                ((OpenApiInternalRestTemplate) client).producerCaptureToken(path)))).isTrue();
    }
    private static CachedOpenApiDocumentService docs(OpenApiInternalRestTemplate client, AtomicInteger fetches) {
        return docs(client, fetches, CONFIG);
    }
    private static CachedOpenApiDocumentService docs(OpenApiInternalRestTemplate client, AtomicInteger fetches, String config) {
        var fixtureOwner = new CachedOpenApiDocumentService[1];
        var source = new OpenApiDocsSupport() {
            @Override public String localPublicationContextPath() { return ""; }
            @Override public OpenApiDocumentCapture fetchFreshOpenApiResponseCapture(RestTemplate c, String path, Logger l, ObjectMapper m) {
                fetches.incrementAndGet();
                consumeFixture(fixtureOwner[0], c, path);
                assertThat(((OpenApiInternalRestTemplate)c).producerCaptureToken(path)).isNotNull();
                return OpenApiDocumentCapture.parse((path.endsWith("swagger-config") ? config : OAS).getBytes(StandardCharsets.UTF_8), m);
            }
        };
        var docs = new CachedOpenApiDocumentService(client, new ObjectMapper(), source, true, Duration.ofSeconds(3));
        ReflectionTestUtils.setField(docs, "openApiBasePath", BASE);
        fixtureOwner[0] = docs;
        return docs;
    }
}
