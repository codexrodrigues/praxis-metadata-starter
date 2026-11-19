package org.praxisplatform.uischema.customfields;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.Clob;

/** Optional H2 alias adapter. Production filtering uses PostgreSQL jsonb_extract_path_text/jsonb_extract_path/jsonb_exists. */
public final class H2JsonbFunctions {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private H2JsonbFunctions() {}
    public static String jsonbExtractPathText(Object jsonColumn, String key) {
        JsonNode value = extractNode(jsonColumn, key);
        if (value == null || value.isNull()) return null;
        return value.isValueNode() ? value.asText() : value.toString();
    }

    public static String jsonbExtractPath(Object jsonColumn, String key) {
        JsonNode value = extractNode(jsonColumn, key);
        return value == null || value.isNull() ? null : value.toString();
    }

    public static Boolean jsonbExists(Object jsonValue, String candidate) {
        if (jsonValue == null || candidate == null) return Boolean.FALSE;
        try {
            JsonNode node = parseNode(jsonValue);
            if (node == null || node.isNull()) return Boolean.FALSE;
            if (node.isArray()) {
                for (JsonNode elem : node) {
                    if (elem != null && !elem.isNull() && elem.isValueNode() && candidate.equals(elem.asText())) {
                        return Boolean.TRUE;
                    }
                }
                return Boolean.FALSE;
            }
            if (node.isObject()) return node.has(candidate);
            return Boolean.FALSE;
        } catch (Exception e) { throw new IllegalArgumentException("Invalid JSON custom fields column", e); }
    }

    private static JsonNode extractNode(Object jsonColumn, String key) {
        if (jsonColumn == null || key == null) return null;
        try {
            JsonNode node = parseNode(jsonColumn);
            return node == null ? null : node.get(key);
        } catch (Exception e) { throw new IllegalArgumentException("Invalid JSON custom fields column", e); }
    }

    private static JsonNode parseNode(Object raw) throws Exception {
        JsonNode node;
        if (raw instanceof JsonNode json) node = json;
        else if (raw instanceof byte[] bytes) node = MAPPER.readTree(new String(bytes, StandardCharsets.UTF_8));
        else if (raw instanceof String text) node = MAPPER.readTree(text);
        else if (raw instanceof Clob clob) node = MAPPER.readTree(clob.getSubString(1, Math.toIntExact(clob.length())));
        else node = MAPPER.valueToTree(raw);
        if (node != null && node.isTextual()) node = MAPPER.readTree(node.textValue());
        return node;
    }
}
