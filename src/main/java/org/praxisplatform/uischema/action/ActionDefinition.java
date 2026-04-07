package org.praxisplatform.uischema.action;

import org.praxisplatform.uischema.openapi.CanonicalOperationRef;
import org.praxisplatform.uischema.schema.CanonicalSchemaRef;

import java.util.List;

/**
 * Definicao estavel de action derivada de endpoint real anotado com {@code @WorkflowAction}.
 *
 * <p>
 * Este record representa a camada interna canonica usada pelo registry e pelo catalogo de
 * actions. Ele preserva a ligacao entre semantica de comando, operacao HTTP real e schemas
 * canonicos de request e response.
 * </p>
 */
public record ActionDefinition(
        String id,
        String resourceKey,
        String resourcePath,
        String group,
        ActionScope scope,
        String title,
        String description,
        CanonicalOperationRef operation,
        CanonicalSchemaRef requestSchema,
        CanonicalSchemaRef responseSchema,
        int order,
        String successMessage,
        List<String> requiredAuthorities,
        List<String> allowedStates,
        List<String> tags
) {
}
