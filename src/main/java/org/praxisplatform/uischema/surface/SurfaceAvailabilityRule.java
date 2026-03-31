package org.praxisplatform.uischema.surface;

import org.praxisplatform.uischema.capability.AvailabilityDecision;

/**
 * Regra composicional de availability de surface.
 */
public interface SurfaceAvailabilityRule {

    AvailabilityDecision evaluate(SurfaceDefinition definition, SurfaceAvailabilityContext context);
}
