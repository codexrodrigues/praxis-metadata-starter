package org.praxisplatform.uischema.schema;

import org.praxisplatform.uischema.openapi.CanonicalOperationRef;

import java.util.Locale;

/**
 * Resolve referencias canonicas para {@code /schemas/filtered}.
 */
public interface SchemaReferenceResolver {

    CanonicalSchemaRef resolve(CanonicalOperationRef operationRef, String schemaType);

    default CanonicalSchemaRef requestSchema(CanonicalOperationRef operationRef) {
        return resolve(operationRef, "request");
    }

    default CanonicalSchemaRef responseSchema(CanonicalOperationRef operationRef) {
        return resolve(operationRef, "response");
    }

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
