package org.praxisplatform.uischema.action;

import org.praxisplatform.uischema.capability.AvailabilityDecision;

/**
 * Avalia se uma action esta disponivel no contexto atual.
 *
 * <p>
 * Esta fronteira aplica regras contextuais sobre uma action previamente descoberta e validada.
 * Ela nao cria nem remove a action do registro canonico; apenas calcula sua disponibilidade
 * runtime ou documental.
 * </p>
 */
public interface ActionAvailabilityEvaluator {

    /**
     * Avalia uma action descoberta no contexto informado.
     */
    AvailabilityDecision evaluate(ActionDefinition definition, ActionAvailabilityContext context);
}
