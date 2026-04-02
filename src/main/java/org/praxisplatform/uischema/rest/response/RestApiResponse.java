package org.praxisplatform.uischema.rest.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;
import org.springframework.hateoas.Links;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Envelope padrao de resposta da API Praxis.
 *
 * <p>
 * Este tipo unifica a forma de expor sucesso, falha, links HATEOAS e erros estruturados na
 * superficie HTTP da plataforma. Controllers base e endpoints documentais o usam como contrato
 * padrao para evitar formatos heterogeneos entre modulos e recursos.
 * </p>
 *
 * <p>
 * Em respostas bem-sucedidas, o campo {@code data} carrega o payload principal e o campo
 * {@code _links} expõe affordances HATEOAS quando habilitadas. Em respostas de falha, o envelope
 * pode trazer mensagem resumida e uma lista de {@link CustomProblemDetail}.
 * </p>
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
    @JsonProperty("_links")
    private RestApiLinks links;
    private List<CustomProblemDetail> errors;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
    @Builder.Default
    private LocalDateTime timestamp = LocalDateTime.now();

    /**
     * Cria um envelope de sucesso.
     *
     * @param data corpo da resposta
     * @param links links HATEOAS, quando aplicavel
     * @return envelope de sucesso preenchido
     */
    public static <T> RestApiResponse<T> success(T data, Links links) {
        return RestApiResponse.<T>builder()
                .status(RestApiResponseStatus.SUCCESS)
                .message("Request processed successfully.")
                .data(data)
                .links(RestApiLinks.from(links))
                .timestamp(LocalDateTime.now())
                .build();
    }

    /**
     * Cria um envelope de falha com mensagem e detalhes estruturados.
     *
     * @param message mensagem resumida de erro
     * @param errors lista de detalhes de problema
     * @return envelope de falha preenchido
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
