package org.praxisplatform.uischema.id;

/**
 * Builds a deterministic schemaId string for caching/indexing on backend and frontend.
 */
public final class SchemaIdBuilder {

    private SchemaIdBuilder() {}

    public static String build(String decodedPath,
                               String operation,
                               String schemaType,
                               boolean includeInternalSchemas,
                               String idField,
                               Boolean readOnly) {
        StringBuilder sb = new StringBuilder();
        sb.append(normalizePath(decodedPath))
          .append('|').append(operation)
          .append('|').append(schemaType)
          .append("|internal:").append(includeInternalSchemas);
        if (idField != null && !idField.isBlank()) {
            sb.append("|idField:").append(idField);
        }
        if (readOnly != null) {
            sb.append("|readOnly:").append(readOnly);
        }
        return sb.toString();
    }

    private static String normalizePath(String p) {
        if (p == null) return "";
        // Preserve case; remove duplicate trailing slashes
        String out = p.replaceAll("/+", "/");
        if (out.endsWith("/") && out.length() > 1) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }
}
