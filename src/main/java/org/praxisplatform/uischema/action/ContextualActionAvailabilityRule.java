package org.praxisplatform.uischema.action;

import org.praxisplatform.uischema.capability.AvailabilityDecision;
import org.praxisplatform.uischema.capability.DefaultResourceAvailabilityService;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Regra baseline que publica metadados contextuais minimos, exige contexto concreto para
 * actions {@code ITEM} e propaga nativamente metadados legais e de disponibilidade do
 * {@code ResourceStateSnapshot} ({@code action.<id>.allowed}, {@code action.<id>.reason},
 * {@code action.<id>.legalBasis}, {@code action.<id>.legalPurpose}, {@code action.<id>.customTitle}).
 *
 * <p>
 * Isso impede que comandos item-level sejam anunciados como disponiveis sem uma instancia
 * concreta do recurso, mantendo a semantica de workflow coerente com o contexto real.
 * </p>
 */
public class ContextualActionAvailabilityRule implements ActionAvailabilityRule {

    private final DefaultResourceAvailabilityService resourceAvailabilityService;

    public ContextualActionAvailabilityRule() {
        this(DefaultResourceAvailabilityService.INSTANCE);
    }

    public ContextualActionAvailabilityRule(DefaultResourceAvailabilityService resourceAvailabilityService) {
        this.resourceAvailabilityService = resourceAvailabilityService != null
                ? resourceAvailabilityService
                : DefaultResourceAvailabilityService.INSTANCE;
    }

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
        if (!hasRequiredAuthorities(definition, context)) {
            return AvailabilityDecision.allow(metadata);
        }

        return resourceAvailabilityService.evaluateActionSnapshot(
                definition,
                context.resourceState(),
                metadata
        );
    }

    private boolean hasRequiredAuthorities(ActionDefinition definition, ActionAvailabilityContext context) {
        if (definition.requiredAuthorities() == null || definition.requiredAuthorities().isEmpty()) {
            return true;
        }
        if (context.authorities() == null || context.authorities().isEmpty()) {
            return false;
        }
        return context.authorities().containsAll(definition.requiredAuthorities());
    }
}
