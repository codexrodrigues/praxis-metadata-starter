package org.praxisplatform.uischema.action;

import org.praxisplatform.uischema.capability.AvailabilityDecision;

/**
 * Regra composicional de availability de workflow action.
 */
public interface ActionAvailabilityRule {

    AvailabilityDecision evaluate(ActionDefinition definition, ActionAvailabilityContext context);
}
