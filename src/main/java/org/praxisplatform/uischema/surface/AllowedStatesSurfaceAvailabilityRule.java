package org.praxisplatform.uischema.surface;

import org.praxisplatform.uischema.capability.AvailabilityDecision;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Regra declarativa de availability por estado do recurso.
 *
 * <p>
 * A regra compara o estado atual resolvido no contexto com a lista de estados permitidos na
 * definicao da surface. Para surfaces collection-level, a ausencia de {@code resourceId} nao
 * bloqueia discovery.
 * </p>
 */
public class AllowedStatesSurfaceAvailabilityRule implements SurfaceAvailabilityRule {

    @Override
    public AvailabilityDecision evaluate(SurfaceDefinition definition, SurfaceAvailabilityContext context) {
        List<String> allowedStates = definition.allowedStates();
        if (allowedStates == null || allowedStates.isEmpty()) {
            return AvailabilityDecision.allowAll();
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("allowedStates", allowedStates);

        if (context.resourceId() == null) {
            return AvailabilityDecision.allow(metadata);
        }

        if (context.resourceState() == null || !StringUtils.hasText(context.resourceState().state())) {
            return AvailabilityDecision.deny("resource-state-unavailable", metadata);
        }

        String currentState = context.resourceState().state();
        metadata.put("resourceState", currentState);
        if (!allowedStates.contains(currentState)) {
            return AvailabilityDecision.deny("resource-state-blocked", metadata);
        }

        return AvailabilityDecision.allow(metadata);
    }
}
