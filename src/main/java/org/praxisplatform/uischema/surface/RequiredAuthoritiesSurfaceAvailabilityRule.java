package org.praxisplatform.uischema.surface;

import org.praxisplatform.uischema.capability.AvailabilityDecision;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Regra declarativa de availability por authorities/roles.
 */
public class RequiredAuthoritiesSurfaceAvailabilityRule implements SurfaceAvailabilityRule {

    @Override
    public AvailabilityDecision evaluate(SurfaceDefinition definition, SurfaceAvailabilityContext context) {
        List<String> requiredAuthorities = definition.requiredAuthorities();
        if (requiredAuthorities == null || requiredAuthorities.isEmpty()) {
            return AvailabilityDecision.allowAll();
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("requiredAuthorities", requiredAuthorities);

        List<String> missingAuthorities = requiredAuthorities.stream()
                .filter(required -> context.authorities() == null || !context.authorities().contains(required))
                .toList();
        if (!missingAuthorities.isEmpty()) {
            metadata.put("missingAuthorities", missingAuthorities);
            return AvailabilityDecision.deny("missing-authority", metadata);
        }

        return AvailabilityDecision.allow(metadata);
    }
}
