package org.praxisplatform.uischema.customfields;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.Clob;

/** Optional H2 alias adapter. Production filtering uses PostgreSQL jsonb_extract_path_text. */
public final class H2JsonbFunctions {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private H2JsonbFunctions() {}
    public static String jsonbExtractPathText(Object jsonColumn, String key) {
        if (jsonColumn == null || key == null) return null;
        try {
            JsonNode node;
            if (jsonColumn instanceof JsonNode json) node = json;
            else if (jsonColumn instanceof byte[] bytes) node = MAPPER.readTree(new String(bytes, StandardCharsets.UTF_8));
            else if (jsonColumn instanceof String text) node = MAPPER.readTree(text);
            else if (jsonColumn instanceof Clob clob) node = MAPPER.readTree(clob.getSubString(1, Math.toIntExact(clob.length())));
            else node = MAPPER.valueToTree(jsonColumn);
            if (node.isTextual()) node = MAPPER.readTree(node.textValue());
            JsonNode value = node.get(key);
            if (value == null || value.isNull()) return null;
            return value.isValueNode() ? value.asText() : value.toString();
        } catch (Exception e) { throw new IllegalArgumentException("Invalid JSON custom fields column", e); }
    }
}
