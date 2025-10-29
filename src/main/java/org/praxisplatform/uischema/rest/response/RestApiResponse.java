package org.praxisplatform.uischema.rest.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;
import org.springframework.hateoas.Links;

import java.time.LocalDateTime;
import java.util.List;

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

    public static <T> RestApiResponse<T> success(T data, Links links) {
        return RestApiResponse.<T>builder()
                .status(RestApiResponseStatus.SUCCESS)
                .message("Requisição realizada com sucesso")
                .data(data)
                .links(links)
                .timestamp(LocalDateTime.now())
                .build();
    }

    // Adicione algo assim:
    public static <T> RestApiResponse<T> failure(String message, List<CustomProblemDetail> errors) {
        return RestApiResponse.<T>builder()
                .status(RestApiResponseStatus.FAILURE) // ou "ERROR"
                .message(message)
                .errors(errors)
                .timestamp(LocalDateTime.now())
                .build();
    }

}
