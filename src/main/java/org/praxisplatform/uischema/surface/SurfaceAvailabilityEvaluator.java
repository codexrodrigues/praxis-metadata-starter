package org.praxisplatform.uischema.surface;

import org.praxisplatform.uischema.capability.AvailabilityDecision;

/**
 * Avalia disponibilidade contextual de surfaces.
 *
 * <p>
 * Esta fronteira aplica regras de availability sobre uma definicao semantica ja descoberta.
 * Ela nao decide se a surface existe no recurso; decide apenas se ela deve aparecer como
 * disponivel no contexto atual.
 * </p>
 */
public interface SurfaceAvailabilityEvaluator {

    /**
     * Avalia uma surface descoberta no contexto informado.
     */
    AvailabilityDecision evaluate(SurfaceDefinition definition, SurfaceAvailabilityContext context);
}
