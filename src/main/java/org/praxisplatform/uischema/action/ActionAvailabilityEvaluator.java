package org.praxisplatform.uischema.action;

import org.praxisplatform.uischema.capability.AvailabilityDecision;

/**
 * Avalia se uma action esta disponivel no contexto atual.
 */
public interface ActionAvailabilityEvaluator {

    AvailabilityDecision evaluate(ActionDefinition definition, ActionAvailabilityContext context);
}
