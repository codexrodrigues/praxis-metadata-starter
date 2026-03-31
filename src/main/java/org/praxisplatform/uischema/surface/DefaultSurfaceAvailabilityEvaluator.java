package org.praxisplatform.uischema.surface;

import org.praxisplatform.uischema.capability.AvailabilityDecision;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Evaluator baseline da Fase 4 baseado em composicao de regras.
 *
 * <p>
 * A implementacao continua simples por default, mas agora usa regras compostas para permitir
 * evolucao por RBAC e estado do recurso sem transformar o evaluator em um ponto monolitico.
 * </p>
 */
public class DefaultSurfaceAvailabilityEvaluator implements SurfaceAvailabilityEvaluator {

    private final List<SurfaceAvailabilityRule> rules;

    public DefaultSurfaceAvailabilityEvaluator() {
        this(List.of(
                new ContextualSurfaceAvailabilityRule(),
                new RequiredAuthoritiesSurfaceAvailabilityRule(),
                new AllowedStatesSurfaceAvailabilityRule()
        ));
    }

    public DefaultSurfaceAvailabilityEvaluator(List<SurfaceAvailabilityRule> rules) {
        this.rules = List.copyOf(rules);
    }

    @Override
    public AvailabilityDecision evaluate(SurfaceDefinition definition, SurfaceAvailabilityContext context) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        for (SurfaceAvailabilityRule rule : rules) {
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
