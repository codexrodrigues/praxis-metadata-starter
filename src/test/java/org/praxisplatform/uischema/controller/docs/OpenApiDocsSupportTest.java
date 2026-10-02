package org.praxisplatform.uischema.controller.docs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.hash.SchemaCanonicalizer;
import org.praxisplatform.uischema.hash.SchemaHashUtil;
import org.praxisplatform.uischema.openapi.CachedOpenApiDocumentService;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpStatus.NOT_FOUND;

class OpenApiDocsSupportTest {

    @Test
    void freshCaptureRejectsUtf16BytesEvenWhenJsonHasNoCharsetParameter() {
        var client = new RestTemplate();
        var sourceServer = MockRestServiceServer.createServer(client);
        var source = new OpenApiDocsSupport();
        ReflectionTestUtils.setField(source, "openApiInternalBaseUrl", "http://localhost");
        byte[] oas = "{\"openapi\":\"3.1.0\",\"info\":{\"title\":\"Pilot\",\"version\":\"1\"},\"paths\":{}}"
                .getBytes(java.nio.charset.StandardCharsets.UTF_16LE);
        sourceServer.expect(once(), requestTo("http://localhost/v3/api-docs/stats"))
                .andRespond(withSuccess(oas, MediaType.APPLICATION_JSON));
        assertThrows(IllegalStateException.class, () -> source.fetchFreshOpenApiResponseCapture(
                client, "/v3/api-docs/stats", LoggerFactory.getLogger(OpenApiDocsSupportTest.class), new ObjectMapper()));
        sourceServer.verify();
    }

    @Test
    void freshCaptureRejectsUnexpectedStatusesEvenWithValidOpenApiJson() {
        String oas = "{\"openapi\":\"3.1.0\",\"info\":{\"title\":\"Pilot\",\"version\":\"1\"},\"paths\":{}}";
        for (int status : new int[]{201, 202, 204, 206, 301, 302, 307, 308, 304, 404, 500}) {
            var client = new RestTemplate();
            var sourceServer = MockRestServiceServer.createServer(client);
            var source = new OpenApiDocsSupport();
            ReflectionTestUtils.setField(source, "openApiInternalBaseUrl", "http://localhost");
            sourceServer.expect(once(), requestTo("http://localhost/v3/api-docs/stats"))
                    .andRespond(withStatus(org.springframework.http.HttpStatusCode.valueOf(status))
                            .contentType(MediaType.APPLICATION_JSON).body(oas));
            assertThrows(IllegalStateException.class, () -> source.fetchFreshOpenApiResponseCapture(
                    client, "/v3/api-docs/stats", LoggerFactory.getLogger(OpenApiDocsSupportTest.class), new ObjectMapper()));
            sourceServer.verify();
        }
    }

    @Test
    void freshCaptureRejectsMissingOrIncorrectMediaEvenWithValidOpenApiJson() {
        String oas = "{\"openapi\":\"3.1.0\",\"info\":{\"title\":\"Pilot\",\"version\":\"1\"},\"paths\":{}}";
        for (String media : new String[]{null, "text/plain", "text/html", "application/octet-stream",
                "application/*", "application/problem+json", "application/json;charset=UTF-16"}) {
            var client = new RestTemplate();
            var sourceServer = MockRestServiceServer.createServer(client);
            var source = new OpenApiDocsSupport();
            ReflectionTestUtils.setField(source, "openApiInternalBaseUrl", "http://localhost");
            var response = withSuccess().body(oas);
            if (media != null) {
                var headers = new HttpHeaders();
                headers.add(HttpHeaders.CONTENT_TYPE, media);
                response.headers(headers);
            }
            sourceServer.expect(once(), requestTo("http://localhost/v3/api-docs/stats"))
                    .andRespond(response);
            assertThrows(IllegalStateException.class, () -> source.fetchFreshOpenApiResponseCapture(
                    client, "/v3/api-docs/stats", LoggerFactory.getLogger(OpenApiDocsSupportTest.class), new ObjectMapper()));
            sourceServer.verify();
        }
    }

    @Test
    void publicationContextRequiresActualLocalServletContextWithoutRemoteOriginFallback() {
        var source = new OpenApiDocsSupport();
        RequestContextHolder.resetRequestAttributes();
        assertThrows(IllegalStateException.class, source::localPublicationContextPath);
        var context = new org.springframework.mock.web.MockServletContext();
        context.setContextPath("/host");
        var request = new MockHttpServletRequest(context);
        request.setContextPath("/host");
        request.addHeader("X-Forwarded-Prefix", "/untrusted");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            assertEquals("/host", source.localPublicationContextPath());
            var forwarded = new jakarta.servlet.http.HttpServletRequestWrapper(request) {
                @Override public String getContextPath() { return "/untrusted/host"; }
            };
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(forwarded));
            assertThrows(IllegalStateException.class, source::localPublicationContextPath);
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
            ReflectionTestUtils.setField(source, "openApiInternalBaseUrl", "https://elsewhere.example");
            assertThrows(IllegalStateException.class, source::localPublicationContextPath);
        } finally { RequestContextHolder.resetRequestAttributes(); }
    }

    @Test
    void freshGroupCapturePreservesResponseBytesAndUsesExactRevalidatedRoute() {
        var client = new RestTemplate();
        var sourceServer = MockRestServiceServer.createServer(client);
        var source = new OpenApiDocsSupport();
        ReflectionTestUtils.setField(source, "openApiInternalBaseUrl", "http://localhost");
        String response = " { \"openapi\":\"3.1.0\", \"info\":{\"title\":\"Pilot\",\"version\":\"1\"}, \"x-count\" : 7, \"paths\" : { \"/stats\" : {} } }\n";
        sourceServer.expect(once(), requestTo("http://localhost/v3/api-docs/stats"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.CACHE_CONTROL, "no-cache, no-store"))
                .andRespond(withSuccess(response, MediaType.parseMediaType("application/json;charset=UTF-8")));
        var capture = source.fetchFreshOpenApiGroupCapture(client, "/v3/api-docs", "stats",
                LoggerFactory.getLogger(OpenApiDocsSupportTest.class),
                new ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_INTEGER_FOR_INTS));
        assertEquals(response, new String(capture.bytes(), java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(true, capture.document().path("paths").has("/stats"));
        assertEquals(true, capture.document().path("x-count").isBigInteger());
        sourceServer.verify();
    }

    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private OpenApiDocsSupport support;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.createServer(restTemplate);
        support = new OpenApiDocsSupport();
        ReflectionTestUtils.setField(support, "openApiInternalBaseUrl", "http://localhost");
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void fetchOpenApiDocumentUsesLocalBackendPortWhenForwardedHostOmitsPort() {
        ReflectionTestUtils.setField(support, "openApiInternalBaseUrl", "");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/employees/capabilities");
        request.setScheme("http");
        request.setServerName("127.0.0.1");
        request.setServerPort(80);
        request.setLocalName("localhost");
        request.setLocalPort(8091);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        server.expect(once(), requestTo("http://127.0.0.1:8091/v3/api-docs/stats"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"paths\":{\"/stats/group-by\":{}}}", MediaType.APPLICATION_JSON));

        JsonNode result = support.fetchOpenApiDocument(
                restTemplate,
                "/v3/api-docs",
                "stats",
                LoggerFactory.getLogger(OpenApiDocsSupportTest.class)
        );

        assertEquals(true, result.path("paths").has("/stats/group-by"));
        server.verify();
    }

    @Test
    void fetchOpenApiDocumentFallsBackOnlyWhenGroupDocumentIsMissing() {
        server.expect(once(), requestTo("http://localhost/v3/api-docs/stats"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(NOT_FOUND));
        server.expect(once(), requestTo("http://localhost/v3/api-docs"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"paths\":{\"/stats/group-by\":{}}}", MediaType.APPLICATION_JSON));

        JsonNode result = support.fetchOpenApiDocument(
                restTemplate,
                "/v3/api-docs",
                "stats",
                LoggerFactory.getLogger(OpenApiDocsSupportTest.class)
        );

        assertEquals(true, result.path("paths").has("/stats/group-by"));
        server.verify();
    }

    @Test
    void fetchOpenApiGroupDocumentFailsClosedWhenOnlyTheBaseDocumentExists() {
        server.expect(once(), requestTo("http://localhost/v3/api-docs/stats"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(NOT_FOUND));

        assertThrows(IllegalStateException.class, () -> support.fetchOpenApiGroupDocument(
                restTemplate,
                "/v3/api-docs",
                "stats",
                LoggerFactory.getLogger(OpenApiDocsSupportTest.class)
        ));
        server.verify();
    }

    @Test
    void strictGroupReadRejectsCachedFallbackUntilGuardedRefresh() {
        var guardCalls = new AtomicInteger();
        server.expect(once(), requestTo("http://localhost/v3/api-docs/stats"))
                .andRespond(withStatus(NOT_FOUND));
        server.expect(once(), requestTo("http://localhost/v3/api-docs"))
                .andRespond(withSuccess("{\"paths\":{\"/base-only\":{}}}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("http://localhost/v3/api-docs/stats"))
                .andRespond(withSuccess("{\"paths\":{\"/exact-group\":{}}}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("http://localhost/v3/api-docs/stats"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.CACHE_CONTROL, "no-cache, no-store"))
                .andExpect(request -> assertEquals(1, guardCalls.get()))
                .andRespond(withSuccess("{\"paths\":{\"/exact-group\":{}}}", MediaType.APPLICATION_JSON));

        CachedOpenApiDocumentService documents = new CachedOpenApiDocumentService(
                restTemplate, new ObjectMapper(), support);
        ReflectionTestUtils.setField(documents, "openApiBasePath", "/v3/api-docs");

        JsonNode legacyRead = documents.getDocumentForGroup("stats");
        assertEquals(true, legacyRead.path("paths").has("/base-only"));
        String oldHash = documents.getOrComputeSchemaHash("stats.schema", () -> legacyRead.path("paths"));
        documents.installBulkLifecycleInvalidationGuard(() -> {
            assertEquals(legacyRead, documents.getDocumentForGroup("stats"));
            assertEquals(oldHash, documents.getOrComputeSchemaHash("stats.schema", () -> {
                throw new AssertionError("Guard must run before the old schema hash is invalidated");
            }));
            guardCalls.incrementAndGet();
        });

        IllegalStateException rejection = assertThrows(IllegalStateException.class,
                () -> documents.getDocumentForGroupStrict("stats"));
        assertEquals(true, rejection.getCause().getMessage().contains("guarded refresh"));
        assertEquals(0, guardCalls.get());
        assertEquals(legacyRead, documents.getDocumentForGroup("stats"));
        assertEquals(oldHash, documents.getOrComputeSchemaHash("stats.schema", () -> {
            throw new AssertionError("Rejected strict promotion must preserve the cached schema hash");
        }));

        JsonNode refreshed = documents.refreshDocumentForGroupStrict("stats");
        assertEquals(1, guardCalls.get());
        assertEquals(true, refreshed.path("paths").has("/exact-group"));
        assertEquals(false, refreshed.path("paths").has("/base-only"));
        assertEquals(refreshed, documents.getDocumentForGroup("stats"));
        assertEquals(refreshed, documents.getDocumentForGroupStrict("stats"));
        var hashComputations = new AtomicInteger();
        String newHash = documents.getOrComputeSchemaHash("stats.schema", () -> {
            hashComputations.incrementAndGet();
            return refreshed.path("paths");
        });
        assertEquals(1, hashComputations.get());
        assertNotEquals(oldHash, newHash);
        assertEquals(SchemaHashUtil.sha256Hex(new SchemaCanonicalizer().canonicalize(refreshed.path("paths"))),
                newHash);
        server.verify();
    }

    @Test
    void lifecycleRefreshBypassesTheNodeCacheAndRequestsHttpCacheRevalidation() {
        server.expect(once(), requestTo("http://localhost/v3/api-docs/stats"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"openapi\":\"3.1.0\",\"info\":{\"version\":\"old\"},\"paths\":{}}",
                        MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("http://localhost/v3/api-docs/stats"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.CACHE_CONTROL, "no-cache, no-store"))
                .andRespond(withSuccess("{\"openapi\":\"3.1.0\",\"info\":{\"version\":\"fresh\"},\"paths\":{}}",
                        MediaType.APPLICATION_JSON));
        CachedOpenApiDocumentService documents = new CachedOpenApiDocumentService(
                restTemplate, new ObjectMapper(), support);
        ReflectionTestUtils.setField(documents, "openApiBasePath", "/v3/api-docs");

        assertEquals("old", documents.getDocumentForGroupStrict("stats").path("info").path("version").asText());
        assertEquals("fresh", documents.refreshDocumentForGroupStrict("stats").path("info").path("version").asText());
        assertEquals("fresh", documents.getDocumentForGroupStrict("stats").path("info").path("version").asText());
        server.verify();
    }

    @Test
    void fetchOpenApiDocumentDoesNotHideServerErrorsBehindGlobalFallback() {
        server.expect(once(), requestTo("http://localhost/v3/api-docs/stats"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withServerError());

        assertThrows(IllegalStateException.class, () -> support.fetchOpenApiDocument(
                restTemplate,
                "/v3/api-docs",
                "stats",
                LoggerFactory.getLogger(OpenApiDocsSupportTest.class)
        ));
        server.verify();
    }
}
