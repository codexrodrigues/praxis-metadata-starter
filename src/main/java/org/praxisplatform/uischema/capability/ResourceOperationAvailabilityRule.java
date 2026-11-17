package org.praxisplatform.uischema.capability;

import java.util.Objects;

/**
 * Regra composicional para disponibilidade contextual de operacoes canonicas de recurso.
 *
 * <p>Permite que regras do host e de starters externos (como autorizacao externa) sejam combinadas
 * via {@link CompositeResourceOperationAvailabilityEvaluator} respeitando {@code @Order}/{@code Ordered}
 * sem conflitos de bean {@code @Primary}.</p>
 */
@FunctionalInterface
public interface ResourceOperationAvailabilityRule {

    AvailabilityDecision evaluate(ResourceOperationAvailabilityContext context);

    static ResourceOperationAvailabilityRule fromProvider(ResourceOperationAvailabilityProvider provider) {
        Objects.requireNonNull(provider, "provider must not be null");
        return provider::evaluate;
    }
}
