package org.praxisplatform.uischema.surface;

import org.praxisplatform.uischema.capability.AvailabilityDecision;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Regra baseline que publica metadados contextuais minimos e exige contexto concreto para
 * surfaces `ITEM`.
 */
public class ContextualSurfaceAvailabilityRule implements SurfaceAvailabilityRule {

    @Override
    public AvailabilityDecision evaluate(SurfaceDefinition definition, SurfaceAvailabilityContext context) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("contextual", context.resourceId() != null);
        metadata.put("tenantPresent", StringUtils.hasText(context.tenant()));
        metadata.put("principalPresent", context.principal() != null);
        metadata.put("authorityCount", context.authorities() != null ? context.authorities().size() : 0);
        metadata.put("scope", definition.scope().name());

        if (definition.scope() == SurfaceScope.ITEM && context.resourceId() == null) {
            return AvailabilityDecision.deny("resource-context-required", metadata);
        }

        return AvailabilityDecision.allow(metadata);
    }
}
