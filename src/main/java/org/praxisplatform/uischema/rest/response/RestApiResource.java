package org.praxisplatform.uischema.rest.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import org.springframework.hateoas.Link;
import org.springframework.hateoas.Links;

import java.util.Collection;

/**
 * Recurso canonico serializado dentro de colecoes/filter/cursor no envelope Praxis.
 *
 * <p>
 * O objetivo deste tipo e estabilizar o JSON publico dos itens de colecao em
 * `{ ...dtoFields, _links: { rel: { href } } }`, sem acoplar o contrato HTTP ao
 * formato padrao do Spring HATEOAS fora do modo HAL.
 * </p>
 *
 * @param <T> DTO encapsulado
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class RestApiResource<T> {

    private final T content;
    private final RestApiLinks links;

    private RestApiResource(T content, RestApiLinks links) {
        this.content = content;
        this.links = links;
    }

    public static <T> RestApiResource<T> of(T content, Links links) {
        return new RestApiResource<>(content, RestApiLinks.from(links));
    }

    public static <T> RestApiResource<T> of(T content, Collection<Link> links) {
        return new RestApiResource<>(content, RestApiLinks.from(links));
    }

    public static <T> RestApiResource<T> of(T content) {
        return new RestApiResource<>(content, null);
    }

    @JsonUnwrapped
    public T getContent() {
        return content;
    }

    @JsonProperty("_links")
    public RestApiLinks getLinks() {
        return links;
    }
}
