package org.praxisplatform.uischema.capability;

import java.util.Map;

/**
 * Resultado canonicamente serializavel de uma avaliacao de disponibilidade.
 *
 * <p>
 * O record padroniza o resultado produzido por regras e evaluators de availability, carregando
 * decisao booleana, motivo sintetico de bloqueio e metadata adicional para observabilidade ou UX.
 * </p>
 */
public record AvailabilityDecision(
        boolean allowed,
        String reason,
        Map<String, Object> metadata
) {

    /**
     * Retorna uma decisao positiva sem metadata adicional.
     */
    public static AvailabilityDecision allowAll() {
        return new AvailabilityDecision(true, null, Map.of());
    }

    /**
     * Retorna uma decisao positiva com metadata adicional.
     */
    public static AvailabilityDecision allow(Map<String, Object> metadata) {
        return new AvailabilityDecision(true, null, metadata == null ? Map.of() : Map.copyOf(metadata));
    }

    /**
     * Retorna uma decisao negativa com motivo sintetico e metadata adicional.
     */
    public static AvailabilityDecision deny(String reason, Map<String, Object> metadata) {
        return new AvailabilityDecision(false, reason, metadata == null ? Map.of() : Map.copyOf(metadata));
    }
}
