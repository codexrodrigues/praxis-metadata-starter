package org.praxisplatform.uischema.capability;

import org.praxisplatform.uischema.action.ActionCatalogItem;
import org.praxisplatform.uischema.surface.SurfaceCatalogItem;

import java.util.List;
import java.util.Map;

/**
 * Snapshot unificado das capacidades canonicamente disponiveis para uma colecao ou instancia.
 *
 * <p>
 * O campo {@code group} representa o grupo OpenAPI canonico resolvido a partir de
 * {@code resourcePath}. No estado atual da Fase 6, isso normalmente corresponde ao grupo
 * individual do recurso, e nao ao agrupamento documental agregado de {@code @ApiGroup}.
 * </p>
 *
 * <p>
 * O snapshot agrega tres camadas complementares do baseline da plataforma:
 * operacoes canonicas, surfaces disponiveis e actions disponiveis. Ele nao substitui
 * {@code /schemas/filtered} como fonte estrutural do schema nem redefine payloads das operacoes.
 * </p>
 */
public record CapabilitySnapshot(
        String resourceKey,
        String resourcePath,
        String group,
        Object resourceId,
        Map<String, Boolean> canonicalOperations,
        Map<String, CapabilityOperation> operations,
        List<SurfaceCatalogItem> surfaces,
        List<ActionCatalogItem> actions
) {

    public CapabilitySnapshot {
        canonicalOperations = canonicalOperations == null ? Map.of() : Map.copyOf(canonicalOperations);
        operations = operations == null ? Map.of() : Map.copyOf(operations);
        surfaces = surfaces == null ? List.of() : List.copyOf(surfaces);
        actions = actions == null ? List.of() : List.copyOf(actions);
    }
}
