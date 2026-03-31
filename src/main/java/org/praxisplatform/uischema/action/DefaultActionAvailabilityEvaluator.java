package org.praxisplatform.uischema.action;

import org.praxisplatform.uischema.capability.AvailabilityDecision;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Evaluator baseline da Fase 5 para workflow actions.
 *
 * <p>
 * O comportamento inicial permanece simples e sem N+1: actions {@code ITEM} fora de contexto
 * concreto sao discovery-only e recebem deny explicito; quando houver contexto, o evaluator
 * aplica hints declarativos de authorities e estados sem hardcode de seguranca corporativa.
 * </p>
 */
public class DefaultActionAvailabilityEvaluator implements ActionAvailabilityEvaluator {

    private final List<ActionAvailabilityRule> rules;

    public DefaultActionAvailabilityEvaluator() {
        this(List.of(
                new ContextualActionAvailabilityRule(),
                new RequiredAuthoritiesActionAvailabilityRule(),
                new AllowedStatesActionAvailabilityRule()
        ));
    }

    public DefaultActionAvailabilityEvaluator(List<ActionAvailabilityRule> rules) {
        this.rules = List.copyOf(rules);
    }

    @Override
    public AvailabilityDecision evaluate(ActionDefinition definition, ActionAvailabilityContext context) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        for (ActionAvailabilityRule rule : rules) {
            AvailabilityDecision decision = rule.evaluate(definition, context);
            if (decision.metadata() != null && !decision.metadata().isEmpty()) {
                metadata.putAll(decision.metadata());
            }
            if (!decision.allowed() && decision.reason() != null) {
                return AvailabilityDecision.deny(decision.reason(), metadata);
            }
        }
        return AvailabilityDecision.allow(metadata);
    }
}
