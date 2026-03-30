package org.praxisplatform.uischema.id;

/**
 * Constroi um {@code schemaId} deterministico para cache, indexacao e comparacao estrutural.
 *
 * <p>
 * O ID produzido aqui deve refletir apenas dimensoes que alteram o payload estrutural efetivo do
 * schema filtrado. Na lane atual, isso inclui path, metodo, tipo de schema,
 * {@code includeInternalSchemas}, {@code idField} e {@code readOnly}. Parametros neutros para a
 * estrutura, como {@code tenant} e {@code locale}, nao devem entrar neste calculo ate que passem a
 * influenciar o payload retornado.
 * </p>
 */
public final class SchemaIdBuilder {

    private SchemaIdBuilder() {}

    /**
     * Gera o ID estrutural canonico para a variante de schema informada.
     */
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
