package org.praxisplatform.uischema.surface;

import java.util.List;

/**
 * Registry canonicamente responsavel por descobrir surfaces publicadas.
 */
public interface SurfaceDefinitionRegistry {

    List<SurfaceDefinition> findByResourceKey(String resourceKey);

    List<SurfaceDefinition> findByGroup(String group);
}
