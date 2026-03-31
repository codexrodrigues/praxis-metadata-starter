package org.praxisplatform.uischema.surface;

import org.praxisplatform.uischema.capability.AvailabilityDecision;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Implementacao baseline da Fase 4: availability contextual minima, mas honesta.
 *
 * <p>
 * Regras atuais:
 * </p>
 *
 * <ul>
 *   <li>surfaces `ITEM` sem `resourceId` concreto ficam indisponiveis no catalogo global;</li>
 *   <li>surfaces contextuais carregam metadata minima sobre contextualidade, principal e tenant;</li>
 *   <li>nenhuma regra de negocio por estado/tenant e aplicada nesta fase.</li>
 * </ul>
 */
public class DefaultSurfaceAvailabilityEvaluator implements SurfaceAvailabilityEvaluator {

    @Override
    public AvailabilityDecision evaluate(SurfaceDefinition definition, SurfaceAvailabilityContext context) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("contextual", context.resourceId() != null);
        metadata.put("tenantPresent", context.tenant() != null && !context.tenant().isBlank());
        metadata.put("principalPresent", context.principal() != null);
        metadata.put("scope", definition.scope().name());

        if (definition.scope() == SurfaceScope.ITEM && context.resourceId() == null) {
            return AvailabilityDecision.deny("resource-context-required", metadata);
        }

        return AvailabilityDecision.allow(metadata);
    }
}
