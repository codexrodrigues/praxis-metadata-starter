package org.praxisplatform.uischema.stats;

import java.util.List;

/**
 * Discovery publico das dimensoes estatisticas declaradas por um recurso.
 *
 * <p>
 * Este contrato e uma projecao do {@link StatsFieldRegistry}; ele nao redefine a regra de
 * elegibilidade. Consumidores podem usar este inventario para escolher charts antes de executar
 * consultas em {@code /stats/group-by}, {@code /stats/timeseries} ou {@code /stats/distribution}.
 * </p>
 *
 * @param fields campos estatisticos elegiveis no recurso
 */
public record StatsCapability(
        List<StatsFieldCapability> fields
) {

    public StatsCapability {
        fields = fields == null ? List.of() : List.copyOf(fields);
    }

    public static StatsCapability empty() {
        return new StatsCapability(List.of());
    }

    public static StatsCapability from(StatsFieldRegistry registry) {
        if (registry == null || registry.isEmpty()) {
            return empty();
        }
        return new StatsCapability(
                registry.descriptors().stream()
                        .map(StatsFieldCapability::from)
                        .toList()
        );
    }
}
