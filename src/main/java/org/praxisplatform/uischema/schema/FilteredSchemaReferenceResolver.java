package org.praxisplatform.uischema.schema;

import org.praxisplatform.uischema.id.SchemaIdBuilder;
import org.praxisplatform.uischema.openapi.CanonicalOperationRef;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriUtils;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Implementacao canonica para gerar referencias a {@code /schemas/filtered}.
 *
 * <p>
 * Esta classe e a implementacao padrao usada pelo starter para manter
 * {@code schemaId}/{@code schemaUrl} sincronizados com a variante estrutural solicitada.
 * Variacoes atualmente refletidas nas duas saidas incluem {@code includeInternalSchemas},
 * {@code idField} e {@code readOnly}.
 * </p>
 *
 * <p>
 * Os parametros {@code tenant} e {@code locale} seguem neutros para o payload estrutural nesta
 * lane. Eles sao aceitos para preservar a fronteira canonica e devem permanecer fora do ID/URL ate
 * que alguma evolucao futura os torne estruturalmente relevantes.
 * </p>
 */
@Component
public class FilteredSchemaReferenceResolver implements SchemaReferenceResolver {

    @Override
    public CanonicalSchemaRef resolve(CanonicalOperationRef operationRef, String schemaType) {
        if (operationRef == null) {
            throw new IllegalArgumentException("operationRef must not be null");
        }
        return resolve(operationRef.path(), operationRef.method(), schemaType, false, null, null);
    }

    @Override
    public CanonicalSchemaRef resolve(
            String path,
            String method,
            String schemaType,
            boolean includeInternalSchemas,
            String tenant,
            Locale locale,
            String idField,
            Boolean readOnly
    ) {
        String normalizedPath = normalizePath(path);
        String normalizedMethod = normalizeMethod(method);
        String normalizedSchemaType = normalizeSchemaType(schemaType);
        StringBuilder urlBuilder = new StringBuilder("/schemas/filtered?path=")
                .append(URLEncoder.encode(normalizedPath, StandardCharsets.UTF_8).replace("+", "%20"))
                .append("&operation=")
                .append(UriUtils.encodeQueryParam(normalizedMethod, StandardCharsets.UTF_8))
                .append("&schemaType=")
                .append(UriUtils.encodeQueryParam(normalizedSchemaType, StandardCharsets.UTF_8));
        if (includeInternalSchemas) {
            urlBuilder.append("&includeInternalSchemas=true");
        }
        if (StringUtils.hasText(idField)) {
            urlBuilder.append("&idField=")
                    .append(UriUtils.encodeQueryParam(idField, StandardCharsets.UTF_8));
        }
        if (readOnly != null) {
            urlBuilder.append("&readOnly=")
                    .append(UriUtils.encodeQueryParam(String.valueOf(readOnly), StandardCharsets.UTF_8));
        }
        String url = urlBuilder.toString();
        String schemaId = SchemaIdBuilder.build(
                normalizedPath,
                normalizedMethod,
                normalizedSchemaType,
                includeInternalSchemas,
                idField,
                readOnly
        );
        return new CanonicalSchemaRef(schemaId, normalizedSchemaType, url);
    }

    private String normalizePath(String path) {
        if (!StringUtils.hasText(path)) {
            return "/";
        }
        String normalized = path.trim().replaceAll("/+", "/");
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        if (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String normalizeMethod(String method) {
        if (!StringUtils.hasText(method)) {
            return "get";
        }
        return method.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeSchemaType(String schemaType) {
        if (!StringUtils.hasText(schemaType)) {
            return "response";
        }
        return schemaType.trim().toLowerCase(Locale.ROOT);
    }
}
