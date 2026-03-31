package org.praxisplatform.uischema.surface;

import org.praxisplatform.uischema.openapi.CanonicalOperationRef;
import org.praxisplatform.uischema.schema.CanonicalSchemaRef;

import java.util.List;

/**
 * Definicao estavel de uma surface descoberta a partir de operacoes reais do recurso.
 */
public record SurfaceDefinition(
        String id,
        String resourceKey,
        String resourcePath,
        String group,
        SurfaceKind kind,
        SurfaceScope scope,
        String title,
        String description,
        String intent,
        String schemaType,
        CanonicalOperationRef operation,
        CanonicalSchemaRef schema,
        int order,
        List<String> requiredAuthorities,
        List<String> allowedStates,
        List<String> tags
) {
}
