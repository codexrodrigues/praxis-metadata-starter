package org.praxisplatform.uischema.action;

import java.util.List;

/**
 * Registry de workflow actions descobertas a partir de controllers reais.
 */
public interface ActionDefinitionRegistry {

    List<ActionDefinition> findByResourceKey(String resourceKey);

    List<ActionDefinition> findByGroup(String group);
}
