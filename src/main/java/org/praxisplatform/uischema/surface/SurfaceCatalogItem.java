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
        RelatedResourceSurface relatedResource,
        @JsonInclude(JsonInclude.Include.NON_NULL) String legalPurpose,
        @JsonInclude(JsonInclude.Include.NON_NULL) String legalBasisRef
) {
    public SurfaceCatalogItem {
        icon = icon != null && !icon.isBlank() ? icon.trim() : null;
        legalPurpose = legalPurpose != null && !legalPurpose.isBlank() ? legalPurpose.trim() : null;
        legalBasisRef = legalBasisRef != null && !legalBasisRef.isBlank() ? legalBasisRef.trim() : null;
    }

    public SurfaceCatalogItem(
            String id,
            String resourceKey,
            SurfaceKind kind,
            SurfaceScope scope,
            String title,
            String description,
            String intent,
            String icon,
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
                icon,
                operationId,
                path,
                method,
                schemaId,
                schemaUrl,
                responseCardinality,
                availability,
                order,
                tags,
                relatedResource,
                null,
                null
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
                relatedResource,
                null,
                null
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
                null,
                null,
                null
        );
    }
}
