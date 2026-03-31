package org.praxisplatform.uischema.rest.exceptionhandler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.rest.exceptionhandler.exception.InvalidFilterPayloadException;
import org.praxisplatform.uischema.rest.response.RestApiResponse;
import org.praxisplatform.uischema.surface.SurfaceCatalogNotFoundException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpMethod;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.server.ResponseStatusException;
import org.springdoc.api.OpenApiResourceNotFoundException;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void shouldPreserveStatusAndReasonForResponseStatusException() {
        WebRequest request = webRequest("/api/praxis/config/ai/patch/stream/start");
        ResponseStatusException exception = new ResponseStatusException(HttpStatus.BAD_REQUEST, "Prompt vazio.");

        var response = handler.handleResponseStatusException(exception, request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        RestApiResponse<Object> body = response.getBody();
        assertNotNull(body);
        assertEquals("failure", body.getStatus());
        assertEquals("Prompt vazio.", body.getMessage());
        assertNotNull(body.getErrors());
        assertEquals(1, body.getErrors().size());
        assertEquals(HttpStatus.BAD_REQUEST.value(), body.getErrors().get(0).getStatus());
        assertEquals(ErrorCategory.VALIDATION, body.getErrors().get(0).getCategory());
        assertEquals("Prompt vazio.", body.getErrors().get(0).getMessage());
        assertEquals("/api/praxis/config/ai/patch/stream/start", body.getErrors().get(0).getInstance().toString());
    }

    @Test
    void shouldMapForbiddenToSecurityCategory() {
        WebRequest request = webRequest("/api/praxis/config/ai/patch/stream/123");
        ResponseStatusException exception = new ResponseStatusException(HttpStatus.FORBIDDEN, "Stream access denied.");

        var response = handler.handleResponseStatusException(exception, request);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        RestApiResponse<Object> body = response.getBody();
        assertNotNull(body);
        assertEquals("Stream access denied.", body.getMessage());
        assertNotNull(body.getErrors());
        assertEquals(ErrorCategory.SECURITY, body.getErrors().get(0).getCategory());
    }

    @Test
    void shouldReturnEnglishTopLevelMessageForValidationException() {
        WebRequest request = webRequest("/simple/filter");
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "payload");
        bindingResult.addError(new FieldError("payload", "name", "Name is required."));
        MethodArgumentNotValidException exception = new MethodArgumentNotValidException(null, bindingResult);

        var response = handler.handleValidationExceptions(exception, request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        RestApiResponse<Object> body = response.getBody();
        assertNotNull(body);
        assertEquals("Validation error.", body.getMessage());
        assertNotNull(body.getErrors());
        assertEquals("Name is required.", body.getErrors().get(0).getMessage());
    }

    @Test
    void shouldReturnEnglishTopLevelMessageForBusinessException() {
        WebRequest request = webRequest("/simple/filter");

        var response = handler.handleBusinessException(
                new org.praxisplatform.uischema.rest.exceptionhandler.exception.BusinessException("Rule violated."),
                request
        );

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        RestApiResponse<Object> body = response.getBody();
        assertNotNull(body);
        assertEquals("Business rule violation.", body.getMessage());
        assertNotNull(body.getErrors());
        assertEquals("Rule violated.", body.getErrors().get(0).getMessage());
    }

    @Test
    void shouldReturnEnglishTopLevelMessageForEntityNotFound() {
        WebRequest request = webRequest("/simple/filter");

        var response = handler.handleEntityNotFoundException(
                new jakarta.persistence.EntityNotFoundException("Entity 1 was not found."),
                request
        );

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        RestApiResponse<Object> body = response.getBody();
        assertNotNull(body);
        assertEquals("Resource not found.", body.getMessage());
        assertNotNull(body.getErrors());
        assertEquals("Entity 1 was not found.", body.getErrors().get(0).getMessage());
    }

    @Test
    void shouldKeepGenericExceptionAsInternalServerError() {
        WebRequest request = webRequest("/api/praxis/config/ai/patch/stream/start");

        var response = handler.handleGenericException(new IllegalStateException("boom"), request);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        RestApiResponse<Object> body = response.getBody();
        assertNotNull(body);
        assertEquals("failure", body.getStatus());
        assertEquals("Internal server error while processing the request.", body.getMessage());
        assertNotNull(body.getErrors());
        assertEquals(ErrorCategory.SYSTEM, body.getErrors().get(0).getCategory());
        assertEquals("INTERNAL_SERVER_ERROR", body.getErrors().get(0).getProperties().get("code"));
    }

    @Test
    void shouldRespectResponseStatusAnnotationInsideGenericHandler() {
        WebRequest request = webRequest("/schemas/surfaces?resource=unknown.resource");

        var response = handler.handleGenericException(
                SurfaceCatalogNotFoundException.unknownResourceKey("unknown.resource"),
                request
        );

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        RestApiResponse<Object> body = response.getBody();
        assertNotNull(body);
        assertEquals("failure", body.getStatus());
        assertEquals("Unknown surface resource key: unknown.resource", body.getMessage());
        assertNotNull(body.getErrors());
        assertEquals(ErrorCategory.BUSINESS_LOGIC, body.getErrors().get(0).getCategory());
        assertEquals("/schemas/surfaces?resource=unknown.resource", body.getErrors().get(0).getInstance().toString());
    }

    @Test
    void shouldMapIllegalArgumentExceptionToBadRequest() {
        WebRequest request = webRequest("/simple/filter");

        var response = handler.handleInvalidFilterPayloadException(
                new InvalidFilterPayloadException("BETWEEN requires at least one bound."),
                request
        );

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        RestApiResponse<Object> body = response.getBody();
        assertNotNull(body);
        assertEquals("failure", body.getStatus());
        assertEquals("BETWEEN requires at least one bound.", body.getMessage());
        assertNotNull(body.getErrors());
        assertEquals(ErrorCategory.VALIDATION, body.getErrors().get(0).getCategory());
        assertEquals("FILTER_PAYLOAD_INVALID", body.getErrors().get(0).getProperties().get("code"));
        assertEquals("/simple/filter", body.getErrors().get(0).getInstance().toString());
    }

    @Test
    void shouldMapGenericIllegalArgumentExceptionToInternalServerError() {
        WebRequest request = webRequest("/simple/filter");

        var response = handler.handleIllegalArgumentException(
                new IllegalArgumentException("Erro de programação"),
                request
        );

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        RestApiResponse<Object> body = response.getBody();
        assertNotNull(body);
        assertEquals("failure", body.getStatus());
        assertEquals("Internal server error while processing the request.", body.getMessage());
        assertNotNull(body.getErrors());
        assertEquals(ErrorCategory.SYSTEM, body.getErrors().get(0).getCategory());
        assertEquals("INTERNAL_SERVER_ERROR", body.getErrors().get(0).getProperties().get("code"));
    }

    @Test
    void shouldMapSchemaIllegalArgumentExceptionWithPrefixedPathToBadRequest() {
        WebRequest request = webRequest("/api/human-resources/funcionarios/schemas");

        var response = handler.handleIllegalArgumentException(
                new IllegalArgumentException("schema inválido"),
                request
        );

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        RestApiResponse<Object> body = response.getBody();
        assertNotNull(body);
        assertEquals("failure", body.getStatus());
        assertEquals("schema inválido", body.getMessage());
        assertNotNull(body.getErrors());
        assertEquals(ErrorCategory.VALIDATION, body.getErrors().get(0).getCategory());
        assertEquals("INVALID_PARAMETER", body.getErrors().get(0).getProperties().get("code"));
    }

    @Test
    void shouldMapHttpMessageNotReadableToBadRequest() {
        WebRequest request = webRequest("/simple/filter");

        var response = handler.handleHttpMessageNotReadable(
                new HttpMessageNotReadableException("bad payload"),
                request
        );

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        RestApiResponse<Object> body = response.getBody();
        assertNotNull(body);
        assertEquals("failure", body.getStatus());
        assertEquals("Invalid JSON payload or payload incompatible with the filter contract.", body.getMessage());
        assertNotNull(body.getErrors());
        assertEquals(ErrorCategory.VALIDATION, body.getErrors().get(0).getCategory());
        assertEquals("REQUEST_PAYLOAD_INVALID", body.getErrors().get(0).getProperties().get("code"));
    }

    @Test
    void shouldMapMissingRequestHeaderToBadRequest() {
        WebRequest request = webRequest("/api/praxis/config/ui");

        var response = handler.handleMissingRequestHeader(
                new MissingRequestHeaderException("X-Tenant-ID", null),
                request
        );

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        RestApiResponse<Object> body = response.getBody();
        assertNotNull(body);
        assertEquals("failure", body.getStatus());
        assertEquals("Required header is missing: X-Tenant-ID.", body.getMessage());
        assertNotNull(body.getErrors());
        assertEquals(ErrorCategory.VALIDATION, body.getErrors().get(0).getCategory());
        assertEquals("MISSING_REQUEST_HEADER", body.getErrors().get(0).getProperties().get("code"));
        assertEquals("/api/praxis/config/ui", body.getErrors().get(0).getInstance().toString());
    }

    @Test
    void shouldMapMissingRequestParameterToBadRequest() {
        WebRequest request = webRequest("/api/praxis/config/ui");

        var response = handler.handleMissingServletRequestParameter(
                new MissingServletRequestParameterException("componentId", "String"),
                request
        );

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        RestApiResponse<Object> body = response.getBody();
        assertNotNull(body);
        assertEquals("failure", body.getStatus());
        assertEquals("Required parameter is missing: componentId.", body.getMessage());
        assertNotNull(body.getErrors());
        assertEquals(ErrorCategory.VALIDATION, body.getErrors().get(0).getCategory());
        assertEquals("MISSING_REQUEST_PARAMETER", body.getErrors().get(0).getProperties().get("code"));
        assertEquals("/api/praxis/config/ui", body.getErrors().get(0).getInstance().toString());
    }

    @Test
    void shouldMapHttpRequestMethodNotSupportedToMethodNotAllowed() {
        WebRequest request = webRequest("/payroll-view/1");

        var response = handler.handleHttpRequestMethodNotSupported(
                new HttpRequestMethodNotSupportedException("PUT"),
                request
        );

        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, response.getStatusCode());
        RestApiResponse<Object> body = response.getBody();
        assertNotNull(body);
        assertEquals("failure", body.getStatus());
        assertEquals("Method 'PUT' is not supported for this endpoint.", body.getMessage());
        assertNotNull(body.getErrors());
        assertEquals(ErrorCategory.VALIDATION, body.getErrors().get(0).getCategory());
        assertEquals("METHOD_NOT_ALLOWED", body.getErrors().get(0).getProperties().get("code"));
        assertEquals("/payroll-view/1", body.getErrors().get(0).getInstance().toString());
    }

    @Test
    void shouldMapOpenApiResourceNotFoundToNotFound() {
        WebRequest request = webRequest("/v3/api-docs/does-not-exist");

        var response = handler.handleOpenApiResourceNotFoundException(
                new OpenApiResourceNotFoundException("does-not-exist"),
                request
        );

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        RestApiResponse<Object> body = response.getBody();
        assertNotNull(body);
        assertEquals("failure", body.getStatus());
        assertEquals("OpenAPI resource was not found.", body.getMessage());
        assertNotNull(body.getErrors());
        assertEquals(ErrorCategory.SYSTEM, body.getErrors().get(0).getCategory());
        assertEquals("RESOURCE_NOT_FOUND", body.getErrors().get(0).getProperties().get("code"));
        assertEquals("/v3/api-docs/does-not-exist", body.getErrors().get(0).getInstance().toString());
    }

    @Test
    void shouldExposeFilterPayloadRootCauseInHttpMessageNotReadable() {
        WebRequest request = webRequest("/simple/filter");

        HttpMessageNotReadableException ex = new HttpMessageNotReadableException(
                "bad payload",
                new InvalidFilterPayloadException("BETWEEN requires at least one bound.")
        );

        var response = handler.handleHttpMessageNotReadable(ex, request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        RestApiResponse<Object> body = response.getBody();
        assertNotNull(body);
        assertEquals("failure", body.getStatus());
        assertEquals("BETWEEN requires at least one bound.", body.getMessage());
        assertNotNull(body.getErrors());
        assertEquals(ErrorCategory.VALIDATION, body.getErrors().get(0).getCategory());
        assertEquals(
                "FILTER_PAYLOAD_INVALID",
                body.getErrors().get(0).getProperties().get("code")
        );
        assertEquals("/simple/filter", body.getErrors().get(0).getInstance().toString());
    }

    @Test
    void shouldKeepGenericIllegalArgumentInHttpMessageNotReadableAsRequestPayloadInvalid() {
        WebRequest request = webRequest("/simple/filter");

        HttpMessageNotReadableException ex = new HttpMessageNotReadableException(
                "bad payload",
                new IllegalArgumentException("invalid enum value")
        );

        var response = handler.handleHttpMessageNotReadable(ex, request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        RestApiResponse<Object> body = response.getBody();
        assertNotNull(body);
        assertEquals("failure", body.getStatus());
        assertEquals("Invalid JSON payload or payload incompatible with the filter contract.", body.getMessage());
        assertNotNull(body.getErrors());
        assertEquals(ErrorCategory.VALIDATION, body.getErrors().get(0).getCategory());
        assertEquals("REQUEST_PAYLOAD_INVALID", body.getErrors().get(0).getProperties().get("code"));
    }

    @Test
    void shouldMapInvalidDataAccessUsageWithFilterPayloadCauseToBadRequest() {
        WebRequest request = webRequest("/simple/filter");
        InvalidDataAccessApiUsageException exception = new InvalidDataAccessApiUsageException(
                "wrapped",
                new InvalidFilterPayloadException("BETWEEN requires at least one bound.")
        );

        var response = handler.handleInvalidDataAccessApiUsageException(exception, request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        RestApiResponse<Object> body = response.getBody();
        assertNotNull(body);
        assertEquals("failure", body.getStatus());
        assertEquals("BETWEEN requires at least one bound.", body.getMessage());
        assertNotNull(body.getErrors());
        assertEquals(ErrorCategory.VALIDATION, body.getErrors().get(0).getCategory());
        assertEquals("FILTER_PAYLOAD_INVALID", body.getErrors().get(0).getProperties().get("code"));
    }

    @Test
    void shouldMapInvalidDataAccessUsageWithoutFilterCauseToInternalServerError() {
        WebRequest request = webRequest("/simple/filter");
        InvalidDataAccessApiUsageException exception = new InvalidDataAccessApiUsageException("wrapped");

        var response = handler.handleInvalidDataAccessApiUsageException(exception, request);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        RestApiResponse<Object> body = response.getBody();
        assertNotNull(body);
        assertEquals("failure", body.getStatus());
        assertEquals("Internal server error while processing the request.", body.getMessage());
        assertNotNull(body.getErrors());
        assertEquals(ErrorCategory.SYSTEM, body.getErrors().get(0).getCategory());
        assertEquals("DATA_ACCESS_ERROR", body.getErrors().get(0).getProperties().get("code"));
    }

    @Test
    void shouldPropagateRequestTraceIdWhenPresent() {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("GET", "/simple/filter");
        servletRequest.addHeader("X-Request-ID", "req-123");
        WebRequest request = new ServletWebRequest(servletRequest);

        var response = handler.handleInvalidFilterPayloadException(
                new InvalidFilterPayloadException("BETWEEN requires at least one bound."),
                request
        );

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        RestApiResponse<Object> body = response.getBody();
        assertNotNull(body);
        assertNotNull(body.getErrors());
        assertEquals("req-123", body.getErrors().get(0).getProperties().get("traceId"));
    }

    @Test
    void shouldMapNoResourceFoundToNotFound() {
        WebRequest request = webRequest("/payroll-view");

        var response = handler.handleNoResourceFoundException(
                new NoResourceFoundException(HttpMethod.POST, "/payroll-view"),
                request
        );

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        RestApiResponse<Object> body = response.getBody();
        assertNotNull(body);
        assertEquals("failure", body.getStatus());
        assertEquals("Endpoint '/payroll-view' does not exist or was not found.", body.getMessage());
        assertNotNull(body.getErrors());
        assertEquals(ErrorCategory.SYSTEM, body.getErrors().get(0).getCategory());
        assertEquals("RESOURCE_NOT_FOUND", body.getErrors().get(0).getProperties().get("code"));
        assertEquals("/payroll-view", body.getErrors().get(0).getInstance().toString());
    }

    private WebRequest webRequest(String uri) {
        return new ServletWebRequest(new MockHttpServletRequest("GET", uri));
    }
}
