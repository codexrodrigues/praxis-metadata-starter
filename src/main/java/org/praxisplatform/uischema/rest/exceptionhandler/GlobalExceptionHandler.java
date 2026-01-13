package org.praxisplatform.uischema.rest.exceptionhandler;

import lombok.extern.slf4j.Slf4j;
import org.praxisplatform.uischema.rest.exceptionhandler.exception.BusinessException;
import org.praxisplatform.uischema.rest.response.CustomProblemDetail;
import org.praxisplatform.uischema.rest.response.RestApiResponse;
import org.praxisplatform.uischema.rest.response.RestApiResponseStatus;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.net.URI;
import java.util.List;

/**
 * Tratamento global e consistente de exceções REST.
 * <p>
 * Converte exceções comuns em {@link org.springframework.http.ResponseEntity}
 * com {@link org.praxisplatform.uischema.rest.response.CustomProblemDetail},
 * padronizando status, mensagem e categoria ({@link ErrorCategory}).
 * </p>
 *
 * @since 1.0.0
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<RestApiResponse<Object>> handleValidationExceptions(MethodArgumentNotValidException ex, WebRequest request) {
        List<CustomProblemDetail> customProblemDetails = ex.getBindingResult().getFieldErrors()
                .stream()
                .map(error -> {
                    CustomProblemDetail customProblemDetail = new CustomProblemDetail(error.getDefaultMessage());
                    customProblemDetail.setStatus(HttpStatus.BAD_REQUEST);
                    customProblemDetail.setTitle(error.getField());
                    customProblemDetail.setType(URI.create("https://example.com/probs/validation-error"));
                    customProblemDetail.setInstance(URI.create(request.getDescription(false)));
                    customProblemDetail.setCategory(ErrorCategory.VALIDATION);

                    return customProblemDetail;
                })
                .toList();


        RestApiResponse<Object> response = RestApiResponse
                .builder()
                .status(RestApiResponseStatus.FAILURE)
                .message("Erro de validação")
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
        customProblemDetail.setInstance(URI.create(request.getDescription(false)));
        customProblemDetail.setCategory(ErrorCategory.BUSINESS_LOGIC);

        RestApiResponse<Object> response = RestApiResponse
                .builder()
                .status(RestApiResponseStatus.FAILURE)
                .message("Erro de regra de negócio")
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
        customProblemDetail.setInstance(URI.create(request.getDescription(false)));
        customProblemDetail.setCategory(ErrorCategory.BUSINESS_LOGIC);

        RestApiResponse<Object> response = RestApiResponse
                .builder()
                .status(RestApiResponseStatus.FAILURE)
                .message("Recurso não encontrado")
                .errors(List.of(customProblemDetail))
                .build();

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<RestApiResponse<Object>> handleGenericException(Exception ex, WebRequest request) {
        log.error("[GlobalExceptionHandler] Unhandled exception", ex);

        String errorMessage = "Erro interno. Por favor, tente novamente ou entre em contato com o suporte.";

        CustomProblemDetail customProblemDetail = new CustomProblemDetail(errorMessage);
        customProblemDetail.setStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        customProblemDetail.setTitle("Erro interno no servidor");
        customProblemDetail.setType(URI.create("https://example.com/probs/internal-server-error"));
        customProblemDetail.setInstance(URI.create(request.getDescription(false)));
        customProblemDetail.setCategory(ErrorCategory.SYSTEM);

        RestApiResponse<Object> response = RestApiResponse
                .builder()
                .status(RestApiResponseStatus.FAILURE)
                .message("Erro interno ao processar a requisição")
                .errors(List.of(customProblemDetail))
                .build();

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }


    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<RestApiResponse<Object>> handleNoHandlerFoundException(NoHandlerFoundException ex, WebRequest request) {
        String errorMessage = String.format("O endpoint '%s' não existe ou não foi encontrado.", ex.getRequestURL());

        CustomProblemDetail customProblemDetail = new CustomProblemDetail(errorMessage);
        customProblemDetail.setStatus(HttpStatus.NOT_FOUND);
        customProblemDetail.setTitle("Endpoint não encontrado");
        customProblemDetail.setType(URI.create("https://example.com/probs/resource-not-found"));
        customProblemDetail.setInstance(URI.create(request.getDescription(false)));
        customProblemDetail.setCategory(ErrorCategory.SYSTEM);

        RestApiResponse<Object> response = RestApiResponse
                .builder()
                .status(RestApiResponseStatus.FAILURE)
                .message("Endpoint não encontrado")
                .errors(List.of(customProblemDetail))
                .build();

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<RestApiResponse<Object>> handleTypeMismatch(MethodArgumentTypeMismatchException ex, WebRequest request) {
        String errorMessage = String.format("O valor '%s' não é válido para o parâmetro '%s'. Esperado tipo: %s.",
                ex.getValue(), ex.getName(), ex.getRequiredType().getSimpleName());

        CustomProblemDetail customProblemDetail = new CustomProblemDetail(errorMessage);
        customProblemDetail.setStatus(HttpStatus.BAD_REQUEST);
        customProblemDetail.setTitle("Parâmetro inválido");
        customProblemDetail.setType(URI.create("https://example.com/probs/invalid-parameter"));
        customProblemDetail.setInstance(URI.create(request.getDescription(false)));
        customProblemDetail.setCategory(ErrorCategory.VALIDATION);

        RestApiResponse<Object> response = RestApiResponse
                .builder()
                .status(RestApiResponseStatus.FAILURE)
                .message("Erro de parâmetro inválido")
                .errors(List.of(customProblemDetail))
                .build();

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }


}
