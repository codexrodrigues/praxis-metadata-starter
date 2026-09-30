package org.praxisplatform.uischema.openapi;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.praxisplatform.uischema.controller.docs.OpenApiDocsSupport;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.DefaultResponseErrorHandler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Timeout(8)
class OpenApiInternalRestTemplateTest {
    @Test
    void configuredResponseTimeoutIncludesSlowHeadersAndAllowsTheNextRequest() throws Exception {
        assertSlowResponseIsBounded(false, false);
    }

    @Test
    void aggregateRemainingBudgetIncludesSlowBodyAfterHeaders() throws Exception {
        assertSlowResponseIsBounded(true, true);
    }

    private void assertSlowResponseIsBounded(boolean sendHeaders, boolean useAggregate) throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var handlers = Executors.newFixedThreadPool(2);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(handlers);
        server.createContext("/slow", exchange -> {
            try {
                if (sendHeaders) {
                    exchange.sendResponseHeaders(200, 0);
                    exchange.getResponseBody().write('{');
                    exchange.getResponseBody().flush();
                }
                entered.countDown();
                release.await(5, TimeUnit.SECONDS);
                if (!sendHeaders) exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write('}');
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (IOException cancelledClient) {
                // Client cancellation is best effort; the server is released separately below.
            } finally { exchange.close(); }
        });
        server.createContext("/fast", exchange -> {
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        String origin = "http://127.0.0.1:" + server.getAddress().getPort();
        try (var client = new OpenApiInternalRestTemplate(Duration.ofSeconds(1),
                useAggregate ? Duration.ofSeconds(5) : Duration.ofMillis(500))) {
            long start = System.nanoTime();
            assertThrows(RuntimeException.class, () -> {
                if (useAggregate) client.withDeadline(new OpenApiInternalRestTemplate.Deadline(Duration.ofMillis(500)),
                        () -> client.getForObject(origin + "/slow", String.class));
                else client.getForObject(origin + "/slow", String.class);
            });
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
            assertThat(release.getCount()).isEqualTo(1);
            release.countDown();
            assertThat(client.getForObject(origin + "/fast", String.class)).isEqualTo("ok");
        } finally {
            release.countDown();
            server.stop(0);
            handlers.shutdownNow();
            assertThat(handlers.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void ownedFactoryKeepsInterceptorsAndErrorHandlerForRealHttp() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var intercepted = new AtomicInteger();
        var errors = new AtomicInteger();
        server.createContext("/docs", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("X-Test-Interceptor")).isEqualTo("present");
            byte[] body = "custom-error-body".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(409, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try (var client = new OpenApiInternalRestTemplate(Duration.ofSeconds(1), Duration.ofSeconds(2))) {
            client.setInterceptors(List.of((request, body, execution) -> {
                intercepted.incrementAndGet(); request.getHeaders().set("X-Test-Interceptor", "present");
                return execution.execute(request, body);
            }));
            client.setErrorHandler(new DefaultResponseErrorHandler() {
                @Override public boolean hasError(org.springframework.http.client.ClientHttpResponse response) {
                    errors.incrementAndGet(); return false;
                }
            });
            assertThat(client.hasOwnedRequestFactory()).isTrue();
            assertThat(client.withDeadline(new OpenApiInternalRestTemplate.Deadline(Duration.ofSeconds(3)),
                    () -> client.getForObject("http://127.0.0.1:" + server.getAddress().getPort() + "/docs", String.class)))
                    .isEqualTo("custom-error-body");
            assertThat(intercepted).hasValue(1);
            assertThat(errors).hasValue(1);
        } finally { server.stop(0); }
    }

    @Test
    void freshSourceRedirectIsRejectedWithoutFetchingItsTarget() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var redirectedFetches = new AtomicInteger();
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().set("Location", "/other-node");
            exchange.sendResponseHeaders(307, -1);
            exchange.close();
        });
        server.createContext("/other-node", exchange -> {
            redirectedFetches.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close();
        });
        server.start();
        try (var client = new OpenApiInternalRestTemplate(Duration.ofSeconds(1), Duration.ofSeconds(2))) {
            assertThrows(org.springframework.web.client.RestClientException.class, () ->
                    client.withDeadline(new OpenApiInternalRestTemplate.Deadline(Duration.ofSeconds(3)),
                            () -> client.getForObject("http://127.0.0.1:" + server.getAddress().getPort() + "/redirect", String.class)));
            assertThat(redirectedFetches).hasValue(0);
        } finally { server.stop(0); }
    }

    @Test
    void replacingFactoryFailsClosedIncludingSwapAwayAndBackDuringPreparation() {
        try (var client = new OpenApiInternalRestTemplate(Duration.ofSeconds(1), Duration.ofSeconds(2))) {
            var original = client.getRequestFactory();
            var source = new OpenApiDocsSupport() {
                @Override public com.fasterxml.jackson.databind.JsonNode fetchOpenApiDocument(
                        org.springframework.web.client.RestTemplate template, String base, String group, org.slf4j.Logger logger) {
                    return new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
                }
                @Override public com.fasterxml.jackson.databind.JsonNode fetchFreshOpenApiGroupDocument(
                        org.springframework.web.client.RestTemplate template, String base, String group, org.slf4j.Logger logger) {
                    client.setRequestFactory(new SimpleClientHttpRequestFactory());
                    client.setRequestFactory(original);
                    return new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
                }
            };
            var service = new CachedOpenApiDocumentService(client, new com.fasterxml.jackson.databind.ObjectMapper(), source, true);
            var callback = new AtomicInteger();
            assertThrows(IllegalStateException.class,
                    () -> service.withFreshBulkLifecycleDocuments(Set.of("inventory"), callback::incrementAndGet));
            assertThat(callback).hasValue(0);
            assertThat(client.hasOwnedRequestFactory()).isTrue();
            client.setRequestFactory(new SimpleClientHttpRequestFactory());
            assertThat(service.supportsFreshBulkLifecycleComposition()).isFalse();
            assertThat(service.getDocumentForGroup("inventory")).isNotNull();
        }
    }

    @Test
    void timeoutsRejectZeroNegativeAndUnrepresentableValues() {
        for (Duration invalid : List.of(Duration.ZERO, Duration.ofMillis(-1), Duration.ofNanos(1), Duration.ofDays(100))) {
            assertThrows(IllegalArgumentException.class, () -> new OpenApiInternalRestTemplate(invalid, Duration.ofSeconds(1)));
            assertThrows(IllegalArgumentException.class, () -> new OpenApiInternalRestTemplate(Duration.ofSeconds(1), invalid));
        }
    }
}
