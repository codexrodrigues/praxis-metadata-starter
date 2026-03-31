package org.praxisplatform.uischema.action;

import org.praxisplatform.uischema.capability.AvailabilityDecision;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Regra baseline que publica metadados contextuais minimos e exige contexto concreto para
 * actions {@code ITEM}.
 */
public class ContextualActionAvailabilityRule implements ActionAvailabilityRule {

    @Override
    public AvailabilityDecision evaluate(ActionDefinition definition, ActionAvailabilityContext context) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("contextual", context.resourceId() != null);
        metadata.put("tenantPresent", StringUtils.hasText(context.tenant()));
        metadata.put("principalPresent", context.principal() != null);
        metadata.put("authorityCount", context.authorities() != null ? context.authorities().size() : 0);
        metadata.put("scope", definition.scope().name());

        if (definition.scope() == ActionScope.ITEM && context.resourceId() == null) {
            return AvailabilityDecision.deny("resource-context-required", metadata);
        }

        return AvailabilityDecision.allow(metadata);
    }
}
