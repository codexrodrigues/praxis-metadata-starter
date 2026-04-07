package org.praxisplatform.uischema.surface;

import org.praxisplatform.uischema.capability.AvailabilityDecision;

import java.util.List;

/**
 * Representacao serializavel de uma surface semantica pronta para clientes documentais e runtimes UI.
 *
 * <p>
 * Cada item aponta para uma operacao HTTP real do recurso, preservando metadados semanticos como
 * {@code kind}, {@code scope}, {@code intent} e availability contextual. O item nao carrega um
 * schema inline; ele referencia o schema canonico por {@code schemaId} e {@code schemaUrl}.
 * </p>
 */
public record SurfaceCatalogItem(
        String id,
        String resourceKey,
        SurfaceKind kind,
        SurfaceScope scope,
        String title,
        String description,
        String intent,
        String operationId,
        String path,
        String method,
        String schemaId,
        String schemaUrl,
        AvailabilityDecision availability,
        int order,
        List<String> tags
) {
}
