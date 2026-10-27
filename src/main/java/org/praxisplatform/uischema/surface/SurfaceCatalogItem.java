package org.praxisplatform.uischema.surface;

import com.fasterxml.jackson.annotation.JsonInclude;
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
        @JsonInclude(JsonInclude.Include.NON_NULL) String icon,
        String operationId,
        String path,
        String method,
        String schemaId,
        String schemaUrl,
        SurfaceResponseCardinality responseCardinality,
        AvailabilityDecision availability,
        int order,
        List<String> tags,
        RelatedResourceSurface relatedResource
) {
    public SurfaceCatalogItem {
        icon = icon != null && !icon.isBlank() ? icon.trim() : null;
    }

    public SurfaceCatalogItem(
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
            SurfaceResponseCardinality responseCardinality,
            AvailabilityDecision availability,
            int order,
            List<String> tags,
            RelatedResourceSurface relatedResource
    ) {
        this(
                id,
                resourceKey,
                kind,
                scope,
                title,
                description,
                intent,
                null,
                operationId,
                path,
                method,
                schemaId,
                schemaUrl,
                responseCardinality,
                availability,
                order,
                tags,
                relatedResource
        );
    }

    public SurfaceCatalogItem(
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
            SurfaceResponseCardinality responseCardinality,
            AvailabilityDecision availability,
            int order,
            List<String> tags
    ) {
        this(
                id,
                resourceKey,
                kind,
                scope,
                title,
                description,
                intent,
                null,
                operationId,
                path,
                method,
                schemaId,
                schemaUrl,
                responseCardinality,
                availability,
                order,
                tags,
                null
        );
    }
}
