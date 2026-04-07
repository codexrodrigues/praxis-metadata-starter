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
 *
 * <p>
 * Esta interface protege a regra central de que uma variante estrutural deve sempre ter uma
 * referencia biunivoca: a mesma identidade precisa aparecer no {@code schemaId} e na
 * {@code schemaUrl}. Discovery semantico e payload estrutural nao podem divergir aqui.
 * </p>
 */
public interface SchemaReferenceResolver {

    /**
     * Resolve a referencia canonica de schema a partir de uma operacao previamente resolvida.
     */
    CanonicalSchemaRef resolve(CanonicalOperationRef operationRef, String schemaType);

    /**
     * Atalho para resolver o schema de request de uma operacao canonica.
     */
    default CanonicalSchemaRef requestSchema(CanonicalOperationRef operationRef) {
        return resolve(operationRef, "request");
    }

    /**
     * Atalho para resolver o schema de response de uma operacao canonica.
     */
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

    /**
     * Variante reduzida que preserva apenas as dimensoes canonicas atualmente estruturais.
     */
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

    /**
     * Variante minima para resolver uma referencia canonica sem dimensoes adicionais.
     */
    default CanonicalSchemaRef resolve(String path, String method, String schemaType) {
        return resolve(path, method, schemaType, false, null, null);
    }

    /**
     * Atalho para resolver o schema de request de {@code path + method}.
     */
    default CanonicalSchemaRef requestSchema(String path, String method) {
        return resolve(path, method, "request");
    }

    /**
     * Atalho para resolver o schema de response de {@code path + method}.
     */
    default CanonicalSchemaRef responseSchema(String path, String method) {
        return resolve(path, method, "response");
    }
}
