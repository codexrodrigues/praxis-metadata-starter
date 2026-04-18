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
        Map<String, String> dependencyFilterMap,
        OptionSourcePolicy policy,
        EntityLookupDescriptor entityLookup
) {
    public OptionSourceDescriptor(
            String key,
            OptionSourceType type,
            String resourcePath,
            String filterField,
            String propertyPath,
            String labelPropertyPath,
            String valuePropertyPath,
            List<String> dependsOn,
            OptionSourcePolicy policy,
            EntityLookupDescriptor entityLookup
    ) {
        this(
                key,
                type,
                resourcePath,
                filterField,
                propertyPath,
                labelPropertyPath,
                valuePropertyPath,
                dependsOn,
                null,
                policy,
                entityLookup
        );
    }

    public OptionSourceDescriptor(
            String key,
            OptionSourceType type,
            String resourcePath,
            String filterField,
            String propertyPath,
            String labelPropertyPath,
            String valuePropertyPath,
            List<String> dependsOn,
            Map<String, String> dependencyFilterMap,
            OptionSourcePolicy policy
    ) {
        this(
                key,
                type,
                resourcePath,
                filterField,
                propertyPath,
                labelPropertyPath,
                valuePropertyPath,
                dependsOn,
                dependencyFilterMap,
                policy,
                null
        );
    }

    public OptionSourceDescriptor(
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
        this(
                key,
                type,
                resourcePath,
                filterField,
                propertyPath,
                labelPropertyPath,
                valuePropertyPath,
                dependsOn,
                null,
                policy,
                null
        );
    }

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
        dependsOn = normalizeList(dependsOn);
        dependencyFilterMap = normalizeMap(dependencyFilterMap);
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
        if (!dependencyFilterMap.isEmpty()) {
            metadata.put("dependencyFilterMap", dependencyFilterMap);
        }
        if (propertyPath != null) {
            metadata.put("propertyPath", propertyPath);
        }
        if (labelPropertyPath != null) {
            metadata.put("labelPropertyPath", labelPropertyPath);
        }
        if (valuePropertyPath != null) {
            metadata.put("valuePropertyPath", valuePropertyPath);
        }
        metadata.put("excludeSelfField", policy.excludeSelfField());
        metadata.put("searchMode", policy.searchMode());
        metadata.put("pageSize", policy.defaultPageSize());
        metadata.put("includeIds", policy.allowIncludeIds());
        metadata.put("cachePolicy", policy.cacheable() ? "request-scope" : "none");
        if (entityLookup != null) {
            metadata.putAll(entityLookup.toMetadataMap());
            if (!dependencyFilterMap.isEmpty()) {
                metadata.put("dependencyFilterMap", dependencyFilterMap);
            }
        }
        return metadata;
    }

    /**
     * Normaliza strings opcionais, convertendo valores vazios em {@code null}.
     */
    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static List<String> normalizeList(List<String> value) {
        if (value == null || value.isEmpty()) {
            return List.of();
        }
        return value.stream()
                .filter(item -> item != null && !item.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    private static Map<String, String> normalizeMap(Map<String, String> value) {
        if (value == null || value.isEmpty()) {
            return Map.of();
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        value.forEach((key, mappedValue) -> {
            String normalizedKey = normalize(key);
            String normalizedValue = normalize(mappedValue);
            if (normalizedKey != null && normalizedValue != null) {
                normalized.put(normalizedKey, normalizedValue);
            }
        });
        return normalized.isEmpty() ? Map.of() : Map.copyOf(normalized);
    }
}
