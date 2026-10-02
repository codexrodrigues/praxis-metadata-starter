package org.praxisplatform.uischema.openapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.controller.docs.OpenApiDocsSupport;
import org.springframework.mock.web.MockFilterConfig;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real loopback HTTP/owned transport/filter fixture. This is not Boot/IAM or PostgreSQL authority proof. */
class OpenApiProducerProvenanceHttpTest {
    private static final String BASE = "/v3/api-docs";
    private static final String OAS = " {\"openapi\":\"3.1.0\",\"info\":{\"title\":\"Pilot\",\"version\":\"1\"},\"paths\":{}}\n";
    private static final String CONFIG = "{\"urls\":[{\"name\":\"a\",\"url\":\"/v3/api-docs/a\"},{\"name\":\"b\",\"url\":\"/v3/api-docs/b\"}]}";

    @Test void everyCapturedRouteTraversesTheFilterAndReplayCannotCallTheProducerAgain() throws Exception {
        try (var fixture = new Fixture()) {
            assertThat(fixture.get(BASE, null).statusCode()).isEqualTo(503);
            var candidate = fixture.prepare();
            assertThat(fixture.producerCalls).hasValue(4);
            assertThat(fixture.paths).containsExactly(BASE, BASE + "/a", BASE + "/b", BASE + "/swagger-config");
            fixture.documents.installBulkLifecyclePublicationGuard((generation, digest) -> {
                assertThat(generation).isEqualTo(1L); assertThat(digest).isEqualTo(candidate.digest());
            });
            fixture.inRequest(() -> { fixture.documents.installBulkOpenApiPublication(candidate, 1); return null; });
            for (String path : candidate.responsePaths()) {
                var response = fixture.get(path, null);
                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(response.body()).isEqualTo(candidate.response(path).bytes());
                assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
            }
            var head = fixture.http.send(HttpRequest.newBuilder(URI.create(fixture.url(BASE))).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofByteArray());
            assertThat(head.statusCode()).isEqualTo(200); assertThat(head.body()).isEmpty();
            assertThat(fixture.get(BASE, fixture.tokens.getFirst()).statusCode()).isEqualTo(503);
            assertThat(fixture.get(BASE + ".yaml", null).statusCode()).isEqualTo(503);
            assertThat(fixture.get(BASE + ".yaml/a", null).statusCode()).isEqualTo(503);
            assertThat(fixture.producerCalls).hasValue(4);
        }
    }

    @Test void validJsonWithoutNonceConsumptionCannotProduceAnyCandidateEvenAfterPartialCapture() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.bypassPath = BASE + "/b";
            assertThatThrownBy(fixture::prepare).isInstanceOf(IllegalStateException.class).hasMessageContaining("consum");
            assertThat(fixture.paths).containsExactly(BASE, BASE + "/a");
            assertThat(fixture.get(BASE, null).statusCode()).isEqualTo(503);
            assertThat(fixture.get(BASE + "/a", fixture.tokens.get(1)).statusCode()).isEqualTo(503);
            fixture.bypassPath = null;
            assertThat(fixture.prepare().responsePaths()).hasSize(4);
            assertThat(fixture.producerCalls).hasValue(6);
        }
    }

    @Test void producerErrorAfterConsumptionDoesNotPublishAndNextPreparationHasFreshPermissions() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.errorPath = BASE + "/a";
            assertThatThrownBy(fixture::prepare).isInstanceOf(IllegalStateException.class);
            assertThat(fixture.paths).containsExactly(BASE, BASE + "/a");
            assertThat(fixture.get(BASE, null).statusCode()).isEqualTo(503);
            for (String token : fixture.tokens) assertThat(fixture.get(BASE, token).statusCode()).isEqualTo(503);
            fixture.errorPath = null;
            assertThat(fixture.prepare().responsePaths()).hasSize(4);
            assertThat(fixture.producerCalls).hasValue(6);
            assertThat(fixture.get(BASE, null).statusCode()).isEqualTo(503);
        }
    }

    private static final class Fixture implements AutoCloseable {
        final HttpServer server;
        final OpenApiInternalRestTemplate internal = new OpenApiInternalRestTemplate(Duration.ofSeconds(1), Duration.ofSeconds(2));
        final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
        final AtomicInteger producerCalls = new AtomicInteger();
        final CopyOnWriteArrayList<String> paths = new CopyOnWriteArrayList<>();
        final CopyOnWriteArrayList<String> tokens = new CopyOnWriteArrayList<>();
        final MockServletContext context;
        final CachedOpenApiDocumentService documents;
        final GovernedOpenApiPublicationFilter filter;
        volatile String bypassPath;
        volatile String errorPath;
        Fixture() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            var registration = mock(jakarta.servlet.FilterRegistration.class);
            when(registration.getClassName()).thenReturn(GovernedOpenApiPublicationFilter.class.getName());
            when(registration.getUrlPatternMappings()).thenReturn(java.util.List.of("/*"));
            when(registration.getServletNameMappings()).thenReturn(java.util.List.of());
            context = new MockServletContext() {
                @Override public jakarta.servlet.FilterRegistration getFilterRegistration(String name) { return registration; }
            };
            documents = new CachedOpenApiDocumentService(internal, new ObjectMapper(), new OpenApiDocsSupport(), true, Duration.ofSeconds(5));
            ReflectionTestUtils.setField(documents, "openApiBasePath", BASE);
            filter = new GovernedOpenApiPublicationFilter(documents);
            filter.init(new MockFilterConfig(context, "bulkPublication"));
            server.createContext("/", exchange -> {
                var request = new MockHttpServletRequest(context, exchange.getRequestMethod(), exchange.getRequestURI().getRawPath());
                request.setServletPath(exchange.getRequestURI().getRawPath());
                request.setQueryString(exchange.getRequestURI().getRawQuery());
                exchange.getRequestHeaders().forEach((name, values) -> values.forEach(value -> request.addHeader(name, value)));
                var response = new MockHttpServletResponse();
                String path = exchange.getRequestURI().getRawPath();
                try {
                    if (path.equals(bypassPath)) writeProducer(response, path);
                    else filter.doFilter(request, response, (input, output) -> {
                        producerCalls.incrementAndGet(); paths.add(path);
                        tokens.add(request.getHeader(OpenApiInternalRestTemplate.PRODUCER_CAPTURE_HEADER));
                        if (path.equals(errorPath)) throw new jakarta.servlet.ServletException("producer fixture failure");
                        writeProducer(response, path);
                    });
                } catch (Exception failure) { response.reset(); response.setStatus(500); }
                for (String name : response.getHeaderNames()) exchange.getResponseHeaders().put(name, response.getHeaders(name).stream().toList());
                byte[] bytes = response.getContentAsByteArray();
                exchange.sendResponseHeaders(response.getStatus(), "HEAD".equals(exchange.getRequestMethod()) || bytes.length == 0 ? -1 : bytes.length);
                try (var output = exchange.getResponseBody()) { if (!"HEAD".equals(exchange.getRequestMethod())) output.write(bytes); }
            });
            server.start();
        }
        private static void writeProducer(jakarta.servlet.http.HttpServletResponse response, String path) throws java.io.IOException {
            response.setStatus(200); response.setContentType("application/json");
            response.getOutputStream().write((path.endsWith("/swagger-config") ? CONFIG : OAS).getBytes(StandardCharsets.UTF_8));
        }
        String url(String path) { return "http://127.0.0.1:" + server.getAddress().getPort() + path; }
        HttpResponse<byte[]> get(String path, String token) throws Exception {
            var request = HttpRequest.newBuilder(URI.create(url(path))).timeout(Duration.ofSeconds(3));
            if (token != null) request.header(OpenApiInternalRestTemplate.PRODUCER_CAPTURE_HEADER, token);
            return http.send(request.GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        }
        OpenApiPublicationCandidate prepare() { return inRequest(() -> documents.prepareBulkOpenApiPublication(Set.of("a", "b"))); }
        <T> T inRequest(java.util.function.Supplier<T> action) {
            var request = new MockHttpServletRequest(context, "GET", "/fixture/preparation");
            request.setLocalAddr("127.0.0.1"); request.setLocalPort(server.getAddress().getPort()); request.setServerPort(server.getAddress().getPort());
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
            try { return action.get(); } finally { RequestContextHolder.resetRequestAttributes(); }
        }
        @Override public void close() { server.stop(0); internal.close(); http.shutdownNow(); }
    }
}
