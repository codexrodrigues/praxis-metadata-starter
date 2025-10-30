package org.praxisplatform.uischema.rest.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;
import org.springframework.hateoas.Links;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Envelope padrão para respostas da API, com timestamp, status e suporte a
 * links HATEOAS e lista de erros.
 *
 * <h3>Exemplo</h3>
 * <pre>{@code
 * // Sucesso
 * return RestApiResponse.success(dto, links);
 *
 * // Falha
 * return RestApiResponse.failure("Erro de validação", errors);
 * }</pre>
 *
 * @param <T> tipo do payload da resposta
 * @since 1.0.0
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RestApiResponse<T> {

    private String status;
    private String message;
    private T data;
    private Links links;
    private List<CustomProblemDetail> errors;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
    @Builder.Default
    private LocalDateTime timestamp = LocalDateTime.now();

    /**
     * Cria uma resposta de sucesso com payload e links.
     * @param data corpo da resposta
     * @param links links HATEOAS (pode ser {@code null})
     */
    public static <T> RestApiResponse<T> success(T data, Links links) {
        return RestApiResponse.<T>builder()
                .status(RestApiResponseStatus.SUCCESS)
                .message("Requisição realizada com sucesso")
                .data(data)
                .links(links)
                .timestamp(LocalDateTime.now())
                .build();
    }

    /**
     * Cria uma resposta de falha com mensagem e detalhes de erro.
     * @param message mensagem resumida de erro
     * @param errors lista de detalhes de problemas
     */
    public static <T> RestApiResponse<T> failure(String message, List<CustomProblemDetail> errors) {
        return RestApiResponse.<T>builder()
                .status(RestApiResponseStatus.FAILURE)
                .message(message)
                .errors(errors)
                .timestamp(LocalDateTime.now())
                .build();
    }

}
