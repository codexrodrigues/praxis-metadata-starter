package org.praxisplatform.uischema.rest.exceptionhandler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.rest.exceptionhandler.exception.InvalidFilterPayloadException;
import org.praxisplatform.uischema.rest.response.RestApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.server.ResponseStatusException;

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
    void shouldKeepGenericExceptionAsInternalServerError() {
        WebRequest request = webRequest("/api/praxis/config/ai/patch/stream/start");

        var response = handler.handleGenericException(new IllegalStateException("boom"), request);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        RestApiResponse<Object> body = response.getBody();
        assertNotNull(body);
        assertEquals("failure", body.getStatus());
        assertEquals("Erro interno ao processar a requisição", body.getMessage());
        assertNotNull(body.getErrors());
        assertEquals(ErrorCategory.SYSTEM, body.getErrors().get(0).getCategory());
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
        assertEquals("Erro interno ao processar a requisição", body.getMessage());
        assertNotNull(body.getErrors());
        assertEquals(ErrorCategory.SYSTEM, body.getErrors().get(0).getCategory());
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
        assertEquals("Payload JSON inválido ou incompatível com o contrato do filtro.", body.getMessage());
        assertNotNull(body.getErrors());
        assertEquals(ErrorCategory.VALIDATION, body.getErrors().get(0).getCategory());
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
    }

    private WebRequest webRequest(String uri) {
        return new ServletWebRequest(new MockHttpServletRequest("GET", uri));
    }
}
