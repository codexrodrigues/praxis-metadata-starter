package org.praxisplatform.uischema.capability;

import java.util.Map;

/**
 * Snapshot canonico do estado de um recurso para avaliacao de availability.
 *
 * <p>
 * O snapshot e carregado uma vez por request contextual e compartilhado entre todas as avaliacoes
 * daquele catalogo, evitando custo N+1 por surface ou action.
 * </p>
 */
public record ResourceStateSnapshot(
        String state,
        Map<String, Object> metadata
) {

    public ResourceStateSnapshot {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static ResourceStateSnapshot of(String state) {
        return new ResourceStateSnapshot(state, Map.of());
    }

    public static ResourceStateSnapshot of(String state, Map<String, Object> metadata) {
        return new ResourceStateSnapshot(state, metadata);
    }
}
