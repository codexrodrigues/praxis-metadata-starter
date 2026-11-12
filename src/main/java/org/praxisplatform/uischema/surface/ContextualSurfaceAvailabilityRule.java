package org.praxisplatform.uischema.surface;

import org.praxisplatform.uischema.capability.AvailabilityDecision;
import org.praxisplatform.uischema.capability.DefaultResourceAvailabilityService;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Regra baseline que publica metadados contextuais minimos, exige contexto concreto para
 * surfaces `ITEM` e propaga nativamente metadados legais e de disponibilidade do
 * {@code ResourceStateSnapshot} ({@code surface.<id>.allowed}, {@code surface.<id>.reason},
 * {@code surface.<id>.legalBasis}, {@code surface.<id>.legalPurpose}, {@code surface.<id>.customTitle}).
 *
 * <p>
 * Essa regra garante o comportamento minimo esperado do baseline: surfaces item-level sem
 * instancia concreta permanecem apenas documentais e nao aparecem como disponiveis em runtime.
 * </p>
 */
public class ContextualSurfaceAvailabilityRule implements SurfaceAvailabilityRule {

    private final DefaultResourceAvailabilityService resourceAvailabilityService;

    public ContextualSurfaceAvailabilityRule() {
        this(DefaultResourceAvailabilityService.INSTANCE);
    }

    public ContextualSurfaceAvailabilityRule(DefaultResourceAvailabilityService resourceAvailabilityService) {
        this.resourceAvailabilityService = resourceAvailabilityService != null
                ? resourceAvailabilityService
                : DefaultResourceAvailabilityService.INSTANCE;
    }

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
        if (!hasRequiredAuthorities(definition, context)) {
            return AvailabilityDecision.allow(metadata);
        }

        return resourceAvailabilityService.evaluateSurfaceSnapshot(
                definition,
                context.resourceState(),
                metadata
        );
    }

    private boolean hasRequiredAuthorities(SurfaceDefinition definition, SurfaceAvailabilityContext context) {
        if (definition.requiredAuthorities() == null || definition.requiredAuthorities().isEmpty()) {
            return true;
        }
        if (context.authorities() == null || context.authorities().isEmpty()) {
            return false;
        }
        return context.authorities().containsAll(definition.requiredAuthorities());
    }
}
