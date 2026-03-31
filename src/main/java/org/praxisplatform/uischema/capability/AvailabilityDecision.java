package org.praxisplatform.uischema.capability;

import java.util.Map;

/**
 * Resultado canonicamente serializavel de uma avaliacao de disponibilidade.
 */
public record AvailabilityDecision(
        boolean allowed,
        String reason,
        Map<String, Object> metadata
) {

    public static AvailabilityDecision allowAll() {
        return new AvailabilityDecision(true, null, Map.of());
    }

    public static AvailabilityDecision allow(Map<String, Object> metadata) {
        return new AvailabilityDecision(true, null, metadata == null ? Map.of() : Map.copyOf(metadata));
    }

    public static AvailabilityDecision deny(String reason, Map<String, Object> metadata) {
        return new AvailabilityDecision(false, reason, metadata == null ? Map.of() : Map.copyOf(metadata));
    }
}
