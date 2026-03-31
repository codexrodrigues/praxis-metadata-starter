package org.praxisplatform.uischema.action;

import org.praxisplatform.uischema.openapi.CanonicalOperationRef;
import org.praxisplatform.uischema.schema.CanonicalSchemaRef;

import java.util.List;

/**
 * Definicao estavel de action derivada de endpoint real anotado com {@code @WorkflowAction}.
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
