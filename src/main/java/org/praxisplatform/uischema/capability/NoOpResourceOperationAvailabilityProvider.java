package org.praxisplatform.uischema.capability;

/**
 * Provider permissivo usado quando o host nao declarou disponibilidade dinamica.
 */
public class NoOpResourceOperationAvailabilityProvider implements ResourceOperationAvailabilityProvider {

    @Override
    public AvailabilityDecision evaluate(ResourceOperationAvailabilityContext context) {
        return AvailabilityDecision.allowAll();
    }
}
