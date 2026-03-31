package org.praxisplatform.uischema.surface;

import org.praxisplatform.uischema.capability.AvailabilityDecision;

/**
 * Avalia disponibilidade contextual de surfaces.
 */
public interface SurfaceAvailabilityEvaluator {

    AvailabilityDecision evaluate(SurfaceDefinition definition, SurfaceAvailabilityContext context);
}
