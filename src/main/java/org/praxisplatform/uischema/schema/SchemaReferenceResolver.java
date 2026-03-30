package org.praxisplatform.uischema.schema;

import org.praxisplatform.uischema.openapi.CanonicalOperationRef;

import java.util.Locale;

/**
 * Resolve referencias canonicas para {@code /schemas/filtered}.
 *
 * <p>
 * O contrato desta interface garante que {@code schemaId} e {@code schemaUrl} representem a mesma
 * variante estrutural do payload filtrado. Se uma dimensao altera a estrutura devolvida, ela deve
 * aparecer nos dois lados da referencia canonica.
 * </p>
 *
 * <p>
 * Na lane atual, {@code tenant} e {@code locale} permanecem neutros para a estrutura filtrada.
 * Eles continuam presentes no boundary para preservar a fronteira canonica; se uma lane futura
 * fizer qualquer um deles alterar o payload estrutural, a implementacao precisara promover essa
 * variacao tanto em {@code schemaId} quanto em {@code schemaUrl}.
 * </p>
 */
public interface SchemaReferenceResolver {

    /**
     * Resolve a referencia canonica de schema a partir de uma operacao previamente resolvida.
     */
    CanonicalSchemaRef resolve(CanonicalOperationRef operationRef, String schemaType);

    default CanonicalSchemaRef requestSchema(CanonicalOperationRef operationRef) {
        return resolve(operationRef, "request");
    }

    default CanonicalSchemaRef responseSchema(CanonicalOperationRef operationRef) {
        return resolve(operationRef, "response");
    }

    /**
     * Resolve a referencia canonica completa para o schema filtrado.
     *
     * <p>
     * As variacoes estruturais reconhecidas hoje sao {@code includeInternalSchemas},
     * {@code idField} e {@code readOnly}. Implementacoes nao devem tratar parametros adicionais
     * como estruturais sem refletir isso nos dois artefatos de saida: URL e ID.
     * </p>
     */
    CanonicalSchemaRef resolve(
            String path,
            String method,
            String schemaType,
            boolean includeInternalSchemas,
            String tenant,
            Locale locale,
            String idField,
            Boolean readOnly
    );

    default CanonicalSchemaRef resolve(
            String path,
            String method,
            String schemaType,
            boolean includeInternalSchemas,
            String tenant,
            Locale locale
    ) {
        return resolve(path, method, schemaType, includeInternalSchemas, tenant, locale, null, null);
    }

    default CanonicalSchemaRef resolve(String path, String method, String schemaType) {
        return resolve(path, method, schemaType, false, null, null);
    }

    default CanonicalSchemaRef requestSchema(String path, String method) {
        return resolve(path, method, "request");
    }

    default CanonicalSchemaRef responseSchema(String path, String method) {
        return resolve(path, method, "response");
    }
}
