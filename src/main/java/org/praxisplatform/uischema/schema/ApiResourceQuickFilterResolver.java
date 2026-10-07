package org.praxisplatform.uischema.schema;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.annotation.QuickFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.*;

/**
 * Resolve e normaliza os filtros rapidos canonicos declarados por {@link ApiResource#quickFilters()}.
 */
@Component
public class ApiResourceQuickFilterResolver {

    private final RequestMappingHandlerMapping handlerMapping;
    private final ObjectMapper objectMapper;

    public ApiResourceQuickFilterResolver(
            @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping handlerMapping,
            @Autowired(required = false) ObjectMapper objectMapper
    ) {
        this.handlerMapping = handlerMapping;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    /**
     * Resolve a lista de filtros rapidos para um dado path de recurso.
     *
     * @param resourcePath path operacional do recurso (ex: "/api/heroes")
     * @return lista de mapas representando os filtros rapidos ou lista vazia
     */
    public List<Map<String, Object>> resolve(String resourcePath) {
        if (resourcePath == null || resourcePath.isBlank() || handlerMapping == null) {
            return Collections.emptyList();
        }
        String expectedPath = normalize(resourcePath);
        return handlerMapping.getHandlerMethods().values().stream()
                .map(HandlerMethod::getBeanType)
                .distinct()
                .map(type -> AnnotationUtils.findAnnotation(type, ApiResource.class))
                .filter(annotation -> annotation != null && matches(annotation, expectedPath))
                .map(ApiResource::quickFilters)
                .filter(qfs -> qfs != null && qfs.length > 0)
                .findFirst()
                .map(this::convertQuickFilters)
                .orElse(Collections.emptyList());
    }

    private List<Map<String, Object>> convertQuickFilters(QuickFilter[] quickFilters) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (QuickFilter qf : quickFilters) {
            if (qf == null || qf.id().isBlank()) {
                continue;
            }
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", qf.id().trim());
            map.put("label", qf.label() != null ? qf.label().trim() : qf.id().trim());
            map.put("filter", parseFilterExpression(qf.filter()));
            if (qf.icon() != null && !qf.icon().isBlank()) {
                map.put("icon", qf.icon().trim());
            }
            result.add(map);
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Normaliza a expressao de filtro para um objeto estruturado Map ou fallback em String.
     * Suporta formato JSON: "{\"ativo\":true}"
     * Suporta formato query string: "ativo=true&departamentoId=10"
     */
    public Object parseFilterExpression(String raw) {
        if (raw == null || raw.isBlank()) {
            return Collections.emptyMap();
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            try {
                return objectMapper.readValue(trimmed, new TypeReference<Map<String, Object>>() {});
            } catch (Exception ignored) {
                // fallback to key-value or raw
            }
        }
        if (trimmed.contains("=")) {
            Map<String, Object> map = new LinkedHashMap<>();
            String[] pairs = trimmed.split("&");
            for (String pair : pairs) {
                int eqIdx = pair.indexOf('=');
                if (eqIdx > 0) {
                    String key = pair.substring(0, eqIdx).trim();
                    String val = pair.substring(eqIdx + 1).trim();
                    map.put(key, parseScalarValue(val));
                }
            }
            if (!map.isEmpty()) {
                return map;
            }
        }
        return trimmed;
    }

    private Object parseScalarValue(String val) {
        if ("true".equalsIgnoreCase(val)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(val)) {
            return Boolean.FALSE;
        }
        if (val.matches("^-?\\d+$")) {
            try {
                long num = Long.parseLong(val);
                if (num >= Integer.MIN_VALUE && num <= Integer.MAX_VALUE) {
                    return (int) num;
                }
                return num;
            } catch (NumberFormatException ignored) {}
        }
        if (val.matches("^-?\\d+\\.\\d+$")) {
            try {
                return Double.parseDouble(val);
            } catch (NumberFormatException ignored) {}
        }
        return val;
    }

    private static boolean matches(ApiResource resource, String expectedPath) {
        return Arrays.stream(resource.value()).anyMatch(path -> normalize(path).equals(expectedPath))
                || Arrays.stream(resource.path()).anyMatch(path -> normalize(path).equals(expectedPath));
    }

    private static String normalize(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        String normalized = path.trim().replaceAll("/+$", "");
        return normalized.isBlank() ? "/" : normalized;
    }
}
