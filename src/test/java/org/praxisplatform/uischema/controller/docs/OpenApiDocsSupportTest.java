package org.praxisplatform.uischema.controller.docs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.openapi.CachedOpenApiDocumentService;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpStatus.NOT_FOUND;

class OpenApiDocsSupportTest {

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

        server.expect(once(), requestTo("http://localhost:8091/v3/api-docs/stats"))
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
    void strictGroupDocumentReplacesPreviouslyCachedBaseFallbackForAllReaders() {
        server.expect(once(), requestTo("http://localhost/v3/api-docs/stats"))
                .andRespond(withStatus(NOT_FOUND));
        server.expect(once(), requestTo("http://localhost/v3/api-docs"))
                .andRespond(withSuccess("{\"paths\":{\"/base-only\":{}}}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("http://localhost/v3/api-docs/stats"))
                .andRespond(withSuccess("{\"paths\":{\"/exact-group\":{}}}", MediaType.APPLICATION_JSON));

        CachedOpenApiDocumentService documents = new CachedOpenApiDocumentService(
                restTemplate, new ObjectMapper(), support);
        ReflectionTestUtils.setField(documents, "openApiBasePath", "/v3/api-docs");

        JsonNode legacyRead = documents.getDocumentForGroup("stats");
        assertEquals(true, legacyRead.path("paths").has("/base-only"));
        JsonNode strictRead = documents.getDocumentForGroupStrict("stats");
        assertEquals(true, strictRead.path("paths").has("/exact-group"));
        JsonNode subsequentSchemaReader = documents.getDocumentForGroup("stats");
        assertEquals(true, subsequentSchemaReader.path("paths").has("/exact-group"));
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
