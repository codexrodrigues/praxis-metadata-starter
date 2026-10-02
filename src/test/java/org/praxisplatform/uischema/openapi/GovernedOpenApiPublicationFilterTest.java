package org.praxisplatform.uischema.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterRegistration;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockFilterConfig;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;

/** Unit Servlet fixtures only: real HTTP/IAM filter ordering and PostgreSQL remain separate gates. */
class GovernedOpenApiPublicationFilterTest {
    private static final String BASE = "/v3/api-docs";
    private static final byte[] JSON = " {\"info\":{\"title\":\"São Paulo\"},\"paths\":{}}\n".getBytes(StandardCharsets.UTF_8);
    private CachedOpenApiDocumentService documents;
    private GovernedOpenApiPublicationFilter filter;

    @BeforeEach
    void setup() throws Exception {
        documents = mock(CachedOpenApiDocumentService.class);
        when(documents.bulkOpenApiBasePath()).thenReturn(BASE);
        filter = new GovernedOpenApiPublicationFilter(documents);
        filter.init(registration("", List.of("/*"), GovernedOpenApiPublicationFilter.class.getName()));
        clearInvocations(documents); // Init marking is legitimate; preserve the stubs.
    }

    @Test
    void onlyInitWithACompleteContainerRegistrationMarksServingInstalled() throws Exception {
        var source = mock(CachedOpenApiDocumentService.class);
        when(source.bulkOpenApiBasePath()).thenReturn(BASE);
        var instance = new GovernedOpenApiPublicationFilter(source);
        verify(source, never()).markBulkOpenApiServingInstalled(any());
        instance.init(registration("/host", List.of("/*"), instance.getClass().getName()));
        verify(source).markBulkOpenApiServingInstalled("/host");
    }

    @Test
    void missingPartialOrWrongClassRegistrationCannotAttestInstallation() {
        var context = mock(ServletContext.class);
        when(context.getContextPath()).thenReturn("");
        assertThatThrownBy(() -> filter.init(new MockFilterConfig(context, "missing")))
                .isInstanceOf(ServletException.class);
        assertThatThrownBy(() -> filter.init(registration("", List.of(BASE + "/*"), filter.getClass().getName())))
                .isInstanceOf(ServletException.class);
        assertThatThrownBy(() -> filter.init(registration("", List.of("/*"), "other.Filter")))
                .isInstanceOf(ServletException.class);
        verifyNoInteractions(documents);
    }

    @Test
    void rootConfigAndGroupsServeTheExactCapturedUtf8BytesWithoutCallingSpringDoc() throws Exception {
        for (String path : List.of(BASE, BASE + "/swagger-config", BASE + "/operations", BASE + "/a.yml")) {
            when(documents.publishedBulkOpenApiResponse("", path)).thenReturn(capture());
            var response = new MockHttpServletResponse(); var calls = new AtomicInteger();
            filter.doFilter(request("", path, "GET"), response, (input, output) -> calls.incrementAndGet());
            assertThat(calls.get()).isZero(); assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.getContentAsByteArray()).isEqualTo(JSON);
            assertThat(response.getContentType()).isEqualTo("application/json;charset=UTF-8");
            assertThat(response.getHeader("Content-Length")).isEqualTo(Integer.toString(JSON.length));
            assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
            verify(documents).publishedBulkOpenApiResponse("", path);
        }
        verify(documents, never()).consumeBulkOpenApiProducer(any());
    }

    @Test
    void headHasGetHeadersAndContentLengthWithNoBodyInTheExactContainerContext() throws Exception {
        when(documents.publishedBulkOpenApiResponse("/host", BASE)).thenReturn(capture());
        var response = new MockHttpServletResponse(); var calls = new AtomicInteger();
        filter.doFilter(request("/host", BASE, "HEAD"), response, (input, output) -> calls.incrementAndGet());
        assertThat(calls.get()).isZero(); assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsByteArray()).isEmpty();
        assertThat(response.getHeader("Content-Length")).isEqualTo(Integer.toString(JSON.length));
        assertThat(response.getContentType()).isEqualTo("application/json;charset=UTF-8");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    }

    @Test
    void allRootExtensionsAndEncodedNormalizedMatrixAliasesAreDeniedWithoutAnyLookup() throws Exception {
        for (String alias : List.of(BASE + ".yaml", BASE + ".yaml/operations", BASE + ".yml", BASE + ".json",
                BASE + ".xml", BASE + "/%61", "/v3%2fapi-docs", "/v3%252fapi-docs",
                "/v3//api-docs", "/v3/./api-docs", "/other/../v3/api-docs", "/v3/api-docs;x=1",
                "/v3;matrix=x/api-docs", "/v3\\api-docs", BASE + "/", BASE + "/a/..",
                BASE + "/%zz", "/v3%2fapi-docs/%zz", "/v3%252fapi-docs/%zz")) {
            denied(request("", alias, "GET"));
        }
        verifyNoInteractions(documents);
    }

    @Test
    void queryNonReadMethodsAndEveryRedispatchAreDeniedBeforeLookup() throws Exception {
        List<Consumer<MockHttpServletRequest>> invalid = List.of(
                request -> request.setQueryString(""), request -> request.setQueryString("locale=pt"),
                request -> request.setMethod("POST"), request -> request.setMethod("PUT"),
                request -> request.setMethod("OPTIONS"), request -> request.setDispatcherType(DispatcherType.FORWARD),
                request -> request.setDispatcherType(DispatcherType.INCLUDE), request -> request.setDispatcherType(DispatcherType.ASYNC),
                request -> request.setDispatcherType(DispatcherType.ERROR),
                request -> request.setAttribute("jakarta.servlet.forward.request_uri", BASE),
                request -> request.setAttribute("jakarta.servlet.error.request_uri", BASE));
        for (var mutation : invalid) {
            var request = request("", BASE, "GET"); mutation.accept(request); denied(request);
        }
        verifyNoInteractions(documents);
    }

    @Test
    void contextAndServletRoutingMismatchCannotOpenAProducerOrPublicRead() throws Exception {
        var contextMismatch = request("/host", BASE, "GET"); contextMismatch.setContextPath("/other");
        denied(contextMismatch);
        var routingMismatch = request("", BASE, "GET"); routingMismatch.setServletPath("/other");
        denied(routingMismatch);
        var hiddenSource = request("", "/other", "GET"); hiddenSource.setServletPath(BASE);
        denied(hiddenSource);
        verifyNoInteractions(documents);
    }

    @Test
    void validProducerConsumptionAllowsOnlyOneDirectGetToContinueTheExistingChain() throws Exception {
        var request = request("", BASE + "/operations", "GET");
        request.addHeader(OpenApiProducerCaptureAccess.HEADER, "issued-private-token");
        when(documents.consumeBulkOpenApiProducer(request)).thenReturn(true, false);
        var calls = new AtomicInteger(); var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (input, output) -> calls.incrementAndGet());
        assertThat(calls.get()).isEqualTo(1);
        var replayResponse = new MockHttpServletResponse();
        filter.doFilter(request, replayResponse, (input, output) -> calls.incrementAndGet());
        assertThat(calls.get()).isEqualTo(1); assertUnavailable(replayResponse);
        verify(documents, never()).publishedBulkOpenApiResponse(any(), any());
    }

    @Test
    void producerHeadersOnHeadOrDuplicateValuesAreDeniedWithoutConsumingOrFallingBack() throws Exception {
        var head = request("", BASE, "HEAD"); head.addHeader(OpenApiProducerCaptureAccess.HEADER, "token");
        denied(head);
        var duplicate = request("", BASE, "GET");
        duplicate.addHeader(OpenApiProducerCaptureAccess.HEADER, "first");
        duplicate.addHeader(OpenApiProducerCaptureAccess.HEADER, "second"); denied(duplicate);
        verifyNoInteractions(documents);
    }

    @Test
    void invalidTokenAndProducerFailureNeverFallBackToPublishedOrRawSpringDoc() throws Exception {
        var request = request("", BASE, "GET"); request.addHeader(OpenApiProducerCaptureAccess.HEADER, "invalid");
        when(documents.consumeBulkOpenApiProducer(request)).thenReturn(false);
        denied(request);
        when(documents.consumeBulkOpenApiProducer(request)).thenThrow(new IllegalStateException("private producer facts"));
        denied(request);
        verify(documents, never()).publishedBulkOpenApiResponse(any(), any());
    }

    @Test
    void unavailableOrMissingPublicationNeverFallsBackAndDoesNotExposeExceptionText() throws Exception {
        when(documents.publishedBulkOpenApiResponse("", BASE)).thenReturn(null);
        denied(request("", BASE, "GET"));
        when(documents.publishedBulkOpenApiResponse("", BASE)).thenThrow(new IllegalStateException("private schema facts"));
        denied(request("", BASE, "GET"));
        verify(documents, never()).consumeBulkOpenApiProducer(any());
    }

    @Test
    void unrelatedRoutesIncludingMalformedEscapesRemainOutsidePublicationPolicy() throws Exception {
        for (String path : List.of("/api/employees", "/actuator/health", "/swagger-ui/index.html", "/v3/api-document", "/",
                "/api/items/%zz", "/api/items/%", "/api/%2fitems/%zz")) {
            var request = request("/host", path, "GET"); var response = new MockHttpServletResponse();
            var calls = new AtomicInteger();
            filter.doFilter(request, response, (input, output) -> calls.incrementAndGet());
            assertThat(calls.get()).isEqualTo(1);
        }
        verifyNoInteractions(documents);
    }

    private void denied(MockHttpServletRequest request) throws Exception {
        var response = new MockHttpServletResponse(); var calls = new AtomicInteger();
        filter.doFilter(request, response, (input, output) -> calls.incrementAndGet());
        assertThat(calls.get()).isZero(); assertUnavailable(response);
    }
    private static void assertUnavailable(MockHttpServletResponse response) {
        assertThat(response.getStatus()).isEqualTo(503); assertThat(response.getContentAsByteArray()).isEmpty();
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getHeader("Content-Length")).isEqualTo("0");
    }
    private static OpenApiDocumentCapture capture() { return OpenApiDocumentCapture.parse(JSON, new ObjectMapper()); }
    private static MockFilterConfig registration(String contextPath, List<String> mappings, String className) {
        var context = mock(ServletContext.class); var registration = mock(FilterRegistration.class);
        when(context.getContextPath()).thenReturn(contextPath);
        when(context.getFilterRegistration("bulkPublication")).thenReturn(registration);
        when(registration.getClassName()).thenReturn(className);
        when(registration.getUrlPatternMappings()).thenReturn(mappings);
        when(registration.getServletNameMappings()).thenReturn(List.of());
        return new MockFilterConfig(context, "bulkPublication");
    }
    private static MockHttpServletRequest request(String context, String path, String method) {
        var servlet = new MockServletContext(); servlet.setContextPath(context);
        var request = new MockHttpServletRequest(servlet);
        request.setContextPath(context); request.setRequestURI(context + path); request.setServletPath(path);
        request.setMethod(method); request.setDispatcherType(DispatcherType.REQUEST);
        return request;
    }
}
