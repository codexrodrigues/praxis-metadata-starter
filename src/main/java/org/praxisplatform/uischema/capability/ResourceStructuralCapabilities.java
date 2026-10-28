package org.praxisplatform.uischema.capability;

import org.praxisplatform.uischema.exporting.CollectionExportCapability;
import org.praxisplatform.uischema.stats.StatsCapability;

/**
 * Descreve o suporte estrutural executavel de um resource durante o ciclo de vida do
 * {@code ApplicationContext}.
 *
 * <p>Este contrato nao representa autorizacao nem availability contextual. Ele registra apenas
 * operacoes opcionais cujos mappings podem existir na hierarquia base mesmo quando o service nao
 * possui infraestrutura executavel.</p>
 *
 * <p>{@code duplicateDraft} deriva do opt-in da porta
 * {@link org.praxisplatform.uischema.service.base.DuplicateDraftResourceCommandService}.
 * Sua projecao em capabilities exige tambem o POST publicado no OpenAPI. Um valor false
 * nao remove o mapping fixo do controller nem altera o schema do rascunho.</p>
 */
public record ResourceStructuralCapabilities(
        boolean options,
        boolean optionSources,
        boolean statsGroupBy,
        boolean statsTimeSeries,
        boolean statsDistribution,
        boolean statsComparison,
        boolean export,
        boolean duplicateDraft,
        StatsCapability stats,
        CollectionExportCapability exportCapability
) {

    public ResourceStructuralCapabilities {
        stats = stats == null ? StatsCapability.empty() : stats;
        if (!export) {
            exportCapability = null;
        }
    }

    public static ResourceStructuralCapabilities unsupported() {
        return new ResourceStructuralCapabilities(
                false, false, false, false, false, false, false, false,
                StatsCapability.empty(), null
        );
    }
}
