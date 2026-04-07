package org.praxisplatform.uischema.rest.response;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.hateoas.Link;
import org.springframework.hateoas.Links;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Colecao canonica de links HATEOAS serializada como objeto por rel.
 *
 * <p>
 * A superficie HTTP publica do starter usa {@code _links} em formato semelhante a HAL:
 * cada rel vira uma propriedade estavel apontando para um link unico ou uma lista
 * de links quando houver multiplas ocorrencias para o mesmo rel.
 * </p>
 *
 * <p>
 * O objetivo e manter o contrato JSON publico desacoplado do shape interno do Spring HATEOAS,
 * preservando a semantica de rels canonicos como {@code self}, {@code update},
 * {@code delete}, {@code schema}, {@code surfaces} e {@code actions}.
 * </p>
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public final class RestApiLinks {

    private final Map<String, Object> values;

    private RestApiLinks(Map<String, Object> values) {
        this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    /**
     * Converte a colecao de links do Spring HATEOAS para o formato canonico serializavel.
     */
    public static RestApiLinks from(Links links) {
        if (links == null || links.isEmpty()) {
            return null;
        }
        return from(links.toList());
    }

    /**
     * Converte uma colecao arbitraria de links para o formato canonico serializavel.
     */
    public static RestApiLinks from(Collection<Link> links) {
        if (links == null || links.isEmpty()) {
            return null;
        }

        Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        for (Link link : links) {
            if (link == null) {
                continue;
            }
            grouped.computeIfAbsent(link.getRel().value(), ignored -> new ArrayList<>())
                    .add(serialize(link));
        }

        if (grouped.isEmpty()) {
            return null;
        }

        Map<String, Object> canonical = new LinkedHashMap<>();
        grouped.forEach((rel, entries) -> canonical.put(rel, entries.size() == 1 ? entries.get(0) : List.copyOf(entries)));
        return new RestApiLinks(canonical);
    }

    /**
     * Expoe os links agrupados por rel como propriedades JSON do objeto {@code _links}.
     */
    @JsonAnyGetter
    public Map<String, Object> values() {
        return values;
    }

    @JsonIgnore
    public boolean isEmpty() {
        return values.isEmpty();
    }

    @JsonIgnore
    public Map<String, Object> asMap() {
        return values;
    }

    private static Map<String, Object> serialize(Link link) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("href", link.getHref());
        if (link.isTemplated()) {
            value.put("templated", true);
        }
        if (link.getType() != null) {
            value.put("type", link.getType());
        }
        if (link.getDeprecation() != null) {
            value.put("deprecation", link.getDeprecation());
        }
        if (link.getProfile() != null) {
            value.put("profile", link.getProfile().toString());
        }
        if (link.getName() != null) {
            value.put("name", link.getName());
        }
        if (link.getTitle() != null) {
            value.put("title", link.getTitle());
        }
        if (link.getHreflang() != null) {
            value.put("hreflang", link.getHreflang());
        }
        return value;
    }
}
