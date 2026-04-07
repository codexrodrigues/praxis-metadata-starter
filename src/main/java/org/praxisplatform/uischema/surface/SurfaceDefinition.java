package org.praxisplatform.uischema.surface;

import org.praxisplatform.uischema.openapi.CanonicalOperationRef;
import org.praxisplatform.uischema.schema.CanonicalSchemaRef;

import java.util.List;

/**
 * Definicao estavel de uma surface descoberta a partir de operacoes reais do recurso.
 *
 * <p>
 * Este record representa a camada interna canonica usada pelo registry e pelo catalogo de
 * surfaces. Ele conecta identidade semantica, operacao OpenAPI canonica e referencia de schema
 * sem carregar payload inline ou contrato paralelo.
 * </p>
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
