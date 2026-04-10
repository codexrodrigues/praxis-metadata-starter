package org.praxisplatform.uischema.rest.exceptionhandler;

import lombok.extern.slf4j.Slf4j;
import org.praxisplatform.uischema.rest.exceptionhandler.exception.BusinessException;
import org.praxisplatform.uischema.rest.exceptionhandler.exception.InvalidFilterPayloadException;
import org.praxisplatform.uischema.rest.response.CustomProblemDetail;
import org.praxisplatform.uischema.rest.response.RestApiResponse;
import org.praxisplatform.uischema.rest.response.RestApiResponseStatus;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.server.ResponseStatusException;
import org.springdoc.api.OpenApiResourceNotFoundException;
import org.springframework.core.annotation.AnnotatedElementUtils;

import java.net.URI;
import java.util.List;

/**
 * Tratamento global e consistente de excecoes REST.
 *
 * <p>
 * Este advice converte excecoes tecnicas e de negocio em respostas HTTP padronizadas, usando
 * {@link org.praxisplatform.uischema.rest.response.RestApiResponse} e
 * {@link org.praxisplatform.uischema.rest.response.CustomProblemDetail}. Com isso, a plataforma
 * mantem semantica uniforme para validacao, nao encontrado, conflito, erro interno e outros casos
 * relevantes ao consumo metadata-driven e operacional.
 * </p>
 *
 * @since 1.0.0
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    private static final String RESPONSE_STATUS_DEFAULT_TYPE = "https://example.com/probs/request-error";
    private static final String RESPONSE_STATUS_VALIDATION_TYPE = "https://example.com/probs/validation-error";
    private static final String RESPONSE_STATUS_SECURITY_TYPE = "https://example.com/probs/security";
    private static final String RESPONSE_STATUS_NOT_FOUND_TYPE = "https://example.com/probs/resource-not-found";
    private static final String RESPONSE_STATUS_METHOD_NOT_ALLOWED_TYPE = "https://example.com/probs/method-not-allowed";
    private static final String RESPONSE_STATUS_CONFLICT_TYPE = "https://example.com/probs/conflict";
    private static final String RESPONSE_STATUS_GONE_TYPE = "https://example.com/probs/resource-gone";
    private static final String RESPONSE_STATUS_THROTTLE_TYPE = "https://example.com/probs/rate-limit";
    private static final String RESPONSE_STATUS_UNAVAILABLE_TYPE = "https://example.com/probs/service-unavailable";
    private static final String RESPONSE_STATUS_INTERNAL_TYPE = "https://example.com/probs/internal-server-error";
    private static final String ERROR_CODE_FILTER_PAYLOAD_INVALID = "FILTER_PAYLOAD_INVALID";
    private static final String ERROR_CODE_REQUEST_PAYLOAD_INVALID = "REQUEST_PAYLOAD_INVALID";
    private static final String ERROR_CODE_INVALID_PARAMETER = "INVALID_PARAMETER";
    private static final String ERROR_CODE_METHOD_NOT_ALLOWED = "METHOD_NOT_ALLOWED";
    private static final String ERROR_CODE_DATA_ACCESS_ERROR = "DATA_ACCESS_ERROR";
    private static final String ERROR_CODE_DATA_INTEGRITY_VIOLATION = "DATA_INTEGRITY_VIOLATION";
    private static final String ERROR_CODE_INTERNAL_SERVER_ERROR = "INTERNAL_SERVER_ERROR";
    private static final String ERROR_CODE_BUSINESS_RULE_VIOLATION = "BUSINESS_RULE_VIOLATION";
    private static final String ERROR_CODE_ENTITY_NOT_FOUND = "RESOURCE_NOT_FOUND";

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<RestApiResponse<Object>> handleValidationExceptions(MethodArgumentNotValidException ex, WebRequest request) {
        List<CustomProblemDetail> customProblemDetails = ex.getBindingResult().getFieldErrors()
                .stream()
                .map(error -> {
                    CustomProblemDetail customProblemDetail = new CustomProblemDetail(error.getDefaultMessage());
                    customProblemDetail.setStatus(HttpStatus.BAD_REQUEST);
                    customProblemDetail.setTitle(error.getField());
                    customProblemDetail.setType(URI.create("https://example.com/probs/validation-error"));
                    customProblemDetail.setInstance(instanceUri(request));
                    customProblemDetail.setCategory(ErrorCategory.VALIDATION);
                    enrichErrorDetails(customProblemDetail, request, ERROR_CODE_INVALID_PARAMETER);

                    return customProblemDetail;
                })
                .toList();


        RestApiResponse<Object> response = RestApiResponse
                .builder()
                .status(RestApiResponseStatus.FAILURE)
                .message("Validation error.")
                .errors(customProblemDetails)
                .build();

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<RestApiResponse<Object>> handleBusinessException(BusinessException ex, WebRequest request) {
        CustomProblemDetail customProblemDetail = new CustomProblemDetail(ex.getMessage());
        customProblemDetail.setStatus(HttpStatus.BAD_REQUEST);
        customProblemDetail.setTitle(ex.getMessage());
        customProblemDetail.setType(URI.create("https://example.com/probs/business-logic"));
        customProblemDetail.setInstance(instanceUri(request));
        customProblemDetail.setCategory(ErrorCategory.BUSINESS_LOGIC);
        enrichErrorDetails(customProblemDetail, request, ERROR_CODE_BUSINESS_RULE_VIOLATION);

        RestApiResponse<Object> response = RestApiResponse
                .builder()
                .status(RestApiResponseStatus.FAILURE)
                .message("Business rule violation.")
                .errors(List.of(customProblemDetail))
                .build();

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }


    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<RestApiResponse<Object>> handleEntityNotFoundException(EntityNotFoundException ex, WebRequest request) {
        CustomProblemDetail customProblemDetail = new CustomProblemDetail(ex.getMessage());
        customProblemDetail.setStatus(HttpStatus.NOT_FOUND);
        customProblemDetail.setTitle("Entity Not Found");
        customProblemDetail.setType(URI.create("https://example.com/probs/resource-not-found"));
        customProblemDetail.setInstance(instanceUri(request));
        customProblemDetail.setCategory(ErrorCategory.BUSINESS_LOGIC);
        enrichErrorDetails(customProblemDetail, request, ERROR_CODE_ENTITY_NOT_FOUND);

        RestApiResponse<Object> response = RestApiResponse
                .builder()
                .status(RestApiResponseStatus.FAILURE)
                .message("Resource not found.")
                .errors(List.of(customProblemDetail))
                .build();

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<RestApiResponse<Object>> handleResponseStatusException(ResponseStatusException ex, WebRequest request) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        String reason = normalize(ex.getReason());
        return buildStatusResponse(status, reason, request, null);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<RestApiResponse<Object>> handleIllegalArgumentException(IllegalArgumentException ex, WebRequest request) {
        // Mantém 400 para invalidações explícitas em endpoints de schema.
        // Validação de payload de filtro deve usar InvalidFilterPayloadException.
        if (isSchemaRequest(request)) {
            String reason = normalize(ex.getMessage());
            String detailMessage = reason != null ? reason : "Invalid parameter.";
            return buildValidationErrorResponse(detailMessage, "Invalid parameter", request, ERROR_CODE_INVALID_PARAMETER);
        }

        log.error("[GlobalExceptionHandler] IllegalArgumentException fora do fluxo de validação explícita", ex);
        return buildInternalServerErrorResponse(request);
    }

    @ExceptionHandler(InvalidFilterPayloadException.class)
    public ResponseEntity<RestApiResponse<Object>> handleInvalidFilterPayloadException(
            InvalidFilterPayloadException ex,
            WebRequest request
    ) {
        String reason = normalize(ex.getMessage());
        String detailMessage = reason != null ? reason : "Invalid parameter.";
        return buildValidationErrorResponse(detailMessage, "Invalid parameter", request, ERROR_CODE_FILTER_PAYLOAD_INVALID);
    }

    @ExceptionHandler(InvalidDataAccessApiUsageException.class)
    public ResponseEntity<RestApiResponse<Object>> handleInvalidDataAccessApiUsageException(
            InvalidDataAccessApiUsageException ex,
            WebRequest request
    ) {
        Throwable root = rootCause(ex);
        if (root instanceof InvalidFilterPayloadException invalidFilterPayloadException) {
            String reason = normalize(invalidFilterPayloadException.getMessage());
            String detailMessage = reason != null ? reason : "Invalid filter payload.";
            return buildValidationErrorResponse(detailMessage, "Invalid payload", request, ERROR_CODE_FILTER_PAYLOAD_INVALID);
        }

        log.error("[GlobalExceptionHandler] InvalidDataAccessApiUsageException", ex);
        return buildInternalServerErrorResponse(request, ERROR_CODE_DATA_ACCESS_ERROR);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<RestApiResponse<Object>> handleDataIntegrityViolationException(
            DataIntegrityViolationException ex,
            WebRequest request
    ) {
        log.error("[GlobalExceptionHandler] DataIntegrityViolationException", ex);
        return buildStatusResponse(
                HttpStatus.CONFLICT,
                "Request conflicts with existing data constraints.",
                request,
                ERROR_CODE_DATA_INTEGRITY_VIOLATION
        );
    }

    private ResponseEntity<RestApiResponse<Object>> buildValidationErrorResponse(
            String detailMessage,
            String title,
            WebRequest request,
            String errorCode
    ) {
        CustomProblemDetail customProblemDetail = new CustomProblemDetail(detailMessage);
        customProblemDetail.setStatus(HttpStatus.BAD_REQUEST);
        customProblemDetail.setTitle(title);
        customProblemDetail.setType(URI.create(RESPONSE_STATUS_VALIDATION_TYPE));
        customProblemDetail.setInstance(instanceUri(request));
        customProblemDetail.setCategory(ErrorCategory.VALIDATION);
        enrichErrorDetails(customProblemDetail, request, errorCode);

        RestApiResponse<Object> response = RestApiResponse
                .builder()
                .status(RestApiResponseStatus.FAILURE)
                .message(detailMessage)
                .errors(List.of(customProblemDetail))
                .build();

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<RestApiResponse<Object>> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex,
            WebRequest request
    ) {
        Throwable root = rootCause(ex);
        String rootMessage = normalize(root != null ? root.getMessage() : null);
        boolean filterPayloadViolation = root instanceof InvalidFilterPayloadException;

        String detailMessage = filterPayloadViolation && rootMessage != null
                ? rootMessage
                : "Invalid JSON payload or payload incompatible with the filter contract.";
        String errorCode = filterPayloadViolation
                ? ERROR_CODE_FILTER_PAYLOAD_INVALID
                : ERROR_CODE_REQUEST_PAYLOAD_INVALID;

        CustomProblemDetail customProblemDetail = new CustomProblemDetail(detailMessage);
        customProblemDetail.setStatus(HttpStatus.BAD_REQUEST);
        customProblemDetail.setTitle("Invalid payload");
        customProblemDetail.setType(URI.create(RESPONSE_STATUS_VALIDATION_TYPE));
        customProblemDetail.setInstance(instanceUri(request));
        customProblemDetail.setCategory(ErrorCategory.VALIDATION);
        enrichErrorDetails(customProblemDetail, request, errorCode);

        RestApiResponse<Object> response = RestApiResponse
                .builder()
                .status(RestApiResponseStatus.FAILURE)
                .message(detailMessage)
                .errors(List.of(customProblemDetail))
                .build();

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<RestApiResponse<Object>> handleMissingRequestHeader(
            MissingRequestHeaderException ex,
            WebRequest request
    ) {
        String detailMessage = "Required header is missing: " + ex.getHeaderName() + ".";
        return buildValidationErrorResponse(
                detailMessage,
                "Missing required header",
                request,
                "MISSING_REQUEST_HEADER"
        );
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<RestApiResponse<Object>> handleMissingServletRequestParameter(
            MissingServletRequestParameterException ex,
            WebRequest request
    ) {
        String detailMessage = "Required parameter is missing: " + ex.getParameterName() + ".";
        return buildValidationErrorResponse(
                detailMessage,
                "Missing required parameter",
                request,
                "MISSING_REQUEST_PARAMETER"
        );
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<RestApiResponse<Object>> handleHttpRequestMethodNotSupported(
            HttpRequestMethodNotSupportedException ex,
            WebRequest request
    ) {
        String method = normalize(ex.getMethod());
        String detailMessage = method != null
                ? "Method '" + method + "' is not supported for this endpoint."
                : "HTTP method is not supported for this endpoint.";

        CustomProblemDetail customProblemDetail = new CustomProblemDetail(detailMessage);
        customProblemDetail.setStatus(HttpStatus.METHOD_NOT_ALLOWED);
        customProblemDetail.setTitle("Method not allowed");
        customProblemDetail.setType(URI.create(RESPONSE_STATUS_METHOD_NOT_ALLOWED_TYPE));
        customProblemDetail.setInstance(instanceUri(request));
        customProblemDetail.setCategory(ErrorCategory.VALIDATION);
        enrichErrorDetails(customProblemDetail, request, ERROR_CODE_METHOD_NOT_ALLOWED);

        RestApiResponse<Object> response = RestApiResponse
                .builder()
                .status(RestApiResponseStatus.FAILURE)
                .message(detailMessage)
                .errors(List.of(customProblemDetail))
                .build();

        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(response);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<RestApiResponse<Object>> handleGenericException(Exception ex, WebRequest request) {
        ResponseStatus responseStatus = AnnotatedElementUtils.findMergedAnnotation(ex.getClass(), ResponseStatus.class);
        if (responseStatus != null) {
            HttpStatus status = responseStatus.code() != HttpStatus.INTERNAL_SERVER_ERROR
                    ? responseStatus.code()
                    : responseStatus.value();
            if (status == HttpStatus.INTERNAL_SERVER_ERROR) {
                status = HttpStatus.resolve(responseStatus.value().value());
            }
            if (status != null) {
                String reason = normalize(responseStatus.reason());
                String message = reason != null ? reason : normalize(ex.getMessage());
                return buildStatusResponse(status, message, request, null);
            }
        }
        HttpStatus frameworkStatus = resolveFrameworkStatus(ex);
        if (frameworkStatus != null) {
            return buildStatusResponse(frameworkStatus, normalize(ex.getMessage()), request, null);
        }
        log.error("[GlobalExceptionHandler] Unhandled exception", ex);
        return buildInternalServerErrorResponse(request);
    }

    private ResponseEntity<RestApiResponse<Object>> buildInternalServerErrorResponse(WebRequest request) {
        return buildInternalServerErrorResponse(request, ERROR_CODE_INTERNAL_SERVER_ERROR);
    }

    private ResponseEntity<RestApiResponse<Object>> buildInternalServerErrorResponse(
            WebRequest request,
            String errorCode
    ) {
        String errorMessage = "Internal server error. Please try again or contact support.";

        CustomProblemDetail customProblemDetail = new CustomProblemDetail(errorMessage);
        customProblemDetail.setStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        customProblemDetail.setTitle("Internal server error");
        customProblemDetail.setType(URI.create(RESPONSE_STATUS_INTERNAL_TYPE));
        customProblemDetail.setInstance(instanceUri(request));
        customProblemDetail.setCategory(ErrorCategory.SYSTEM);
        enrichErrorDetails(customProblemDetail, request, errorCode);

        RestApiResponse<Object> response = RestApiResponse
                .builder()
                .status(RestApiResponseStatus.FAILURE)
                .message("Internal server error while processing the request.")
                .errors(List.of(customProblemDetail))
                .build();

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }

    private ResponseEntity<RestApiResponse<Object>> buildStatusResponse(
            HttpStatus status,
            String reason,
            WebRequest request,
            String errorCode
    ) {
        String responseMessage = reason != null ? reason : defaultResponseMessage(status);
        String detailMessage = reason != null ? reason : responseMessage;

        CustomProblemDetail customProblemDetail = new CustomProblemDetail(detailMessage);
        customProblemDetail.setStatus(status);
        customProblemDetail.setTitle(defaultProblemTitle(status));
        customProblemDetail.setType(URI.create(defaultProblemType(status)));
        customProblemDetail.setInstance(instanceUri(request));
        customProblemDetail.setCategory(resolveCategory(status));
        enrichErrorDetails(customProblemDetail, request, errorCode);

        RestApiResponse<Object> response = RestApiResponse
                .builder()
                .status(RestApiResponseStatus.FAILURE)
                .message(responseMessage)
                .errors(List.of(customProblemDetail))
                .build();

        return ResponseEntity.status(status).body(response);
    }


    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<RestApiResponse<Object>> handleNoHandlerFoundException(NoHandlerFoundException ex, WebRequest request) {
        String errorMessage = String.format("Endpoint '%s' does not exist or was not found.", ex.getRequestURL());

        CustomProblemDetail customProblemDetail = new CustomProblemDetail(errorMessage);
        customProblemDetail.setStatus(HttpStatus.NOT_FOUND);
        customProblemDetail.setTitle("Endpoint not found");
        customProblemDetail.setType(URI.create("https://example.com/probs/resource-not-found"));
        customProblemDetail.setInstance(instanceUri(request));
        customProblemDetail.setCategory(ErrorCategory.SYSTEM);
        enrichErrorDetails(customProblemDetail, request, ERROR_CODE_ENTITY_NOT_FOUND);

        RestApiResponse<Object> response = RestApiResponse
                .builder()
                .status(RestApiResponseStatus.FAILURE)
                .message("Endpoint not found")
                .errors(List.of(customProblemDetail))
                .build();

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<RestApiResponse<Object>> handleNoResourceFoundException(NoResourceFoundException ex, WebRequest request) {
        String resourcePath = ex.getResourcePath() != null ? ex.getResourcePath() : "unknown";
        String errorMessage = String.format("Endpoint '%s' does not exist or was not found.", resourcePath);

        CustomProblemDetail customProblemDetail = new CustomProblemDetail(errorMessage);
        customProblemDetail.setStatus(HttpStatus.NOT_FOUND);
        customProblemDetail.setTitle("Endpoint not found");
        customProblemDetail.setType(URI.create(RESPONSE_STATUS_NOT_FOUND_TYPE));
        customProblemDetail.setInstance(instanceUri(request));
        customProblemDetail.setCategory(ErrorCategory.SYSTEM);
        enrichErrorDetails(customProblemDetail, request, ERROR_CODE_ENTITY_NOT_FOUND);

        RestApiResponse<Object> response = RestApiResponse.failure(
                errorMessage,
                List.of(customProblemDetail)
        );

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
    }

    @ExceptionHandler(OpenApiResourceNotFoundException.class)
    public ResponseEntity<RestApiResponse<Object>> handleOpenApiResourceNotFoundException(
            OpenApiResourceNotFoundException ex,
            WebRequest request
    ) {
        String errorMessage = "OpenAPI resource was not found.";

        CustomProblemDetail customProblemDetail = new CustomProblemDetail(errorMessage);
        customProblemDetail.setStatus(HttpStatus.NOT_FOUND);
        customProblemDetail.setTitle("OpenAPI resource not found");
        customProblemDetail.setType(URI.create(RESPONSE_STATUS_NOT_FOUND_TYPE));
        customProblemDetail.setInstance(instanceUri(request));
        customProblemDetail.setCategory(ErrorCategory.SYSTEM);
        enrichErrorDetails(customProblemDetail, request, ERROR_CODE_ENTITY_NOT_FOUND);

        RestApiResponse<Object> response = RestApiResponse.failure(
                errorMessage,
                List.of(customProblemDetail)
        );

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<RestApiResponse<Object>> handleTypeMismatch(MethodArgumentTypeMismatchException ex, WebRequest request) {
        String expectedType = ex.getRequiredType() != null ? ex.getRequiredType().getSimpleName() : "unknown";
        String errorMessage = String.format("Value '%s' is invalid for parameter '%s'. Expected type: %s.",
                ex.getValue(), ex.getName(), expectedType);

        CustomProblemDetail customProblemDetail = new CustomProblemDetail(errorMessage);
        customProblemDetail.setStatus(HttpStatus.BAD_REQUEST);
        customProblemDetail.setTitle("Invalid parameter");
        customProblemDetail.setType(URI.create("https://example.com/probs/invalid-parameter"));
        customProblemDetail.setInstance(instanceUri(request));
        customProblemDetail.setCategory(ErrorCategory.VALIDATION);
        enrichErrorDetails(customProblemDetail, request, ERROR_CODE_INVALID_PARAMETER);

        RestApiResponse<Object> response = RestApiResponse
                .builder()
                .status(RestApiResponseStatus.FAILURE)
                .message("Invalid parameter.")
                .errors(List.of(customProblemDetail))
                .build();

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }


    private ErrorCategory resolveCategory(HttpStatus status) {
        if (status == null) {
            return ErrorCategory.UNKNOWN;
        }
        return switch (status) {
            case BAD_REQUEST, METHOD_NOT_ALLOWED, UNPROCESSABLE_ENTITY -> ErrorCategory.VALIDATION;
            case UNAUTHORIZED, FORBIDDEN -> ErrorCategory.SECURITY;
            case NOT_FOUND, CONFLICT, GONE -> ErrorCategory.BUSINESS_LOGIC;
            case TOO_MANY_REQUESTS, SERVICE_UNAVAILABLE, INTERNAL_SERVER_ERROR -> ErrorCategory.SYSTEM;
            default -> status.is5xxServerError() ? ErrorCategory.SYSTEM : ErrorCategory.UNKNOWN;
        };
    }

    private String defaultResponseMessage(HttpStatus status) {
        if (status == null) {
            return "Failed to process the request.";
        }
        return switch (status) {
            case BAD_REQUEST -> "Validation error.";
            case METHOD_NOT_ALLOWED -> "Method not allowed.";
            case UNAUTHORIZED -> "Authentication required.";
            case FORBIDDEN -> "Access denied.";
            case NOT_FOUND -> "Resource not found.";
            case CONFLICT -> "Request conflict.";
            case GONE -> "Resource is no longer available.";
            case UNPROCESSABLE_ENTITY -> "Invalid entity.";
            case TOO_MANY_REQUESTS -> "Too many requests.";
            case SERVICE_UNAVAILABLE -> "Service temporarily unavailable.";
            default -> status.is5xxServerError()
                    ? "Internal server error while processing the request."
                    : "Failed to process the request.";
        };
    }

    private String defaultProblemTitle(HttpStatus status) {
        if (status == null) {
            return "Request error";
        }
        return switch (status) {
            case BAD_REQUEST -> "Invalid request";
            case METHOD_NOT_ALLOWED -> "Method not allowed";
            case UNAUTHORIZED -> "Unauthenticated";
            case FORBIDDEN -> "Access denied";
            case NOT_FOUND -> "Resource not found";
            case CONFLICT -> "Conflict";
            case GONE -> "Resource is no longer available";
            case UNPROCESSABLE_ENTITY -> "Invalid entity";
            case TOO_MANY_REQUESTS -> "Too many requests";
            case SERVICE_UNAVAILABLE -> "Service unavailable";
            default -> status.is5xxServerError() ? "Internal server error" : "Request error";
        };
    }

    private String defaultProblemType(HttpStatus status) {
        if (status == null) {
            return RESPONSE_STATUS_DEFAULT_TYPE;
        }
        return switch (status) {
            case BAD_REQUEST, UNPROCESSABLE_ENTITY -> RESPONSE_STATUS_VALIDATION_TYPE;
            case METHOD_NOT_ALLOWED -> RESPONSE_STATUS_METHOD_NOT_ALLOWED_TYPE;
            case UNAUTHORIZED, FORBIDDEN -> RESPONSE_STATUS_SECURITY_TYPE;
            case NOT_FOUND -> RESPONSE_STATUS_NOT_FOUND_TYPE;
            case CONFLICT -> RESPONSE_STATUS_CONFLICT_TYPE;
            case GONE -> RESPONSE_STATUS_GONE_TYPE;
            case TOO_MANY_REQUESTS -> RESPONSE_STATUS_THROTTLE_TYPE;
            case SERVICE_UNAVAILABLE -> RESPONSE_STATUS_UNAVAILABLE_TYPE;
            case INTERNAL_SERVER_ERROR -> RESPONSE_STATUS_INTERNAL_TYPE;
            default -> RESPONSE_STATUS_DEFAULT_TYPE;
        };
    }

    private boolean isSchemaRequest(WebRequest request) {
        String uri = extractUri(request);
        return uri.contains("/schemas/filtered")
                || uri.matches(".*/schemas(?:\\?.*)?$");
    }

    private String extractUri(WebRequest request) {
        String description = request.getDescription(false);
        if (description == null || description.isBlank()) {
            return "";
        }
        String prefix = "uri=";
        int index = description.indexOf(prefix);
        if (index < 0) {
            return description;
        }
        return description.substring(index + prefix.length());
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private URI instanceUri(WebRequest request) {
        String uri = normalize(extractUri(request));
        if (uri == null) {
            uri = "/";
        }
        return URI.create(uri);
    }

    private void enrichErrorDetails(CustomProblemDetail customProblemDetail, WebRequest request, String errorCode) {
        if (customProblemDetail == null) {
            return;
        }
        String normalizedCode = normalize(errorCode);
        if (normalizedCode != null) {
            customProblemDetail.setProperty("code", normalizedCode);
        }
        String traceId = resolveTraceId(request);
        if (traceId != null) {
            customProblemDetail.setProperty("traceId", traceId);
        }
    }

    private String resolveTraceId(WebRequest request) {
        if (request instanceof ServletWebRequest servletWebRequest) {
            HttpServletRequest servletRequest = servletWebRequest.getRequest();
            if (servletRequest != null) {
                String[] headerCandidates = {"X-Request-ID", "X-Correlation-ID", "X-Trace-ID", "traceparent"};
                for (String header : headerCandidates) {
                    String value = normalize(servletRequest.getHeader(header));
                    if (value != null) {
                        return value;
                    }
                }
            }
        }

        String mdcTraceId = normalize(MDC.get("traceId"));
        if (mdcTraceId != null) {
            return mdcTraceId;
        }
        return normalize(MDC.get("X-B3-TraceId"));
    }

    private Throwable rootCause(Throwable throwable) {
        if (throwable == null) {
            return null;
        }
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private HttpStatus resolveFrameworkStatus(Throwable throwable) {
        if (throwable == null) {
            return null;
        }

        Class<?> current = throwable.getClass();
        while (current != null) {
            String name = current.getName();
            if ("org.springframework.security.access.AccessDeniedException".equals(name)
                    || "org.springframework.security.authorization.AuthorizationDeniedException".equals(name)) {
                return HttpStatus.FORBIDDEN;
            }
            if ("org.springframework.security.core.AuthenticationException".equals(name)) {
                return HttpStatus.UNAUTHORIZED;
            }
            current = current.getSuperclass();
        }
        return null;
    }

}
