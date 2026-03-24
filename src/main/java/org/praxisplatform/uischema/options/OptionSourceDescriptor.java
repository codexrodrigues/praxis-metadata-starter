package org.praxisplatform.uischema.options;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Descricao canonica de uma option-source metadata-driven.
 *
 * <p>
 * O descritor informa como uma fonte derivada de opcoes deve ser exposta e consumida pela
 * plataforma: qual recurso a ancora, qual chave a identifica, quais dependencias de filtro ela
 * possui e quais politicas operacionais devem ser aplicadas.
 * </p>
 */
public record OptionSourceDescriptor(
        String key,
        OptionSourceType type,
        String resourcePath,
        String filterField,
        String propertyPath,
        String labelPropertyPath,
        String valuePropertyPath,
        List<String> dependsOn,
        OptionSourcePolicy policy
) {
    /**
     * Valida e normaliza o descritor no momento da criacao.
     */
    public OptionSourceDescriptor {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Option source key is required.");
        }
        if (type == null) {
            throw new IllegalArgumentException("Option source type is required.");
        }
        if (resourcePath == null || resourcePath.isBlank()) {
            throw new IllegalArgumentException("Option source resourcePath is required.");
        }
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        policy = policy == null ? OptionSourcePolicy.defaults() : policy;
        filterField = normalize(filterField);
        propertyPath = normalize(propertyPath);
        labelPropertyPath = normalize(labelPropertyPath);
        valuePropertyPath = normalize(valuePropertyPath);
    }

    /**
     * Retorna o campo efetivo de filtro associado a esta fonte.
     *
     * @return {@code filterField} quando informado; caso contrario, a propria {@code key}
     */
    public String effectiveFilterField() {
        return filterField != null ? filterField : key;
    }

    /**
     * Converte o descritor para um mapa de metadados apropriado para exposicao documental.
     *
     * @return mapa serializavel com os metadados essenciais da fonte
     */
    public Map<String, Object> toMetadataMap() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("key", key);
        metadata.put("type", type.name());
        metadata.put("resourcePath", resourcePath);
        if (filterField != null) {
            metadata.put("filterField", filterField);
        }
        if (!dependsOn.isEmpty()) {
            metadata.put("dependsOn", dependsOn);
        }
        metadata.put("excludeSelfField", policy.excludeSelfField());
        metadata.put("searchMode", policy.searchMode());
        metadata.put("pageSize", policy.defaultPageSize());
        metadata.put("includeIds", policy.allowIncludeIds());
        metadata.put("cachePolicy", policy.cacheable() ? "request-scope" : "none");
        return metadata;
    }

    /**
     * Normaliza strings opcionais, convertendo valores vazios em {@code null}.
     */
    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
