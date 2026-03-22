package org.praxisplatform.uischema.controller.docs;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

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
