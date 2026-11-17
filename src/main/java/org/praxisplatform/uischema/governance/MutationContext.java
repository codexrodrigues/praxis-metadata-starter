package org.praxisplatform.uischema.governance;

import org.praxisplatform.uischema.capability.ResourceStateSnapshot;

import java.util.List;

/**
 * Contexto estruturado entregue a {@link ResourceGovernanceInterceptor} em hooks de mutacao.
 *
 * @param kind natureza da operacao de escrita
 * @param partialIntent {@code true} quando executada via intent parcial ({@code applyVersionedPatch})
 * @param modifiedFields campos declarados ou fornecidos para mutacao parcial, quando aplicavel
 * @param dto DTO de entrada (CreateDTO, UpdateDTO, PartialUpdateInput ou comando), quando houver
 * @param stateSnapshot snapshot de estado do recurso antes da mutacao, quando disponivel
 */
public record MutationContext(
        MutationKind kind,
        boolean partialIntent,
        List<String> modifiedFields,
        Object dto,
        ResourceStateSnapshot stateSnapshot
) {
    public MutationContext {
        kind = kind == null ? MutationKind.UPDATE : kind;
        modifiedFields = modifiedFields == null ? List.of() : List.copyOf(modifiedFields);
    }

    public static MutationContext of(MutationKind kind, Object dto, ResourceStateSnapshot stateSnapshot) {
        return new MutationContext(kind, kind == MutationKind.PARTIAL_INTENT || kind == MutationKind.PATCH,
                List.of(), dto, stateSnapshot);
    }

    public static MutationContext partial(
            List<String> modifiedFields,
            Object dto,
            ResourceStateSnapshot stateSnapshot
    ) {
        return new MutationContext(MutationKind.PARTIAL_INTENT, true, modifiedFields, dto, stateSnapshot);
    }
}
