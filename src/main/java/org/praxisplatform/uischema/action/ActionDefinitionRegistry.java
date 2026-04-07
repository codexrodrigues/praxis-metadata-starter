package org.praxisplatform.uischema.action;

import java.util.List;

/**
 * Registry de workflow actions descobertas a partir de controllers reais.
 *
 * <p>
 * Assim como no lado de surfaces, o registry descobre actions publicadas pelo recurso, enquanto
 * a fase de availability decide se elas aparecem como disponiveis no contexto atual.
 * </p>
 */
public interface ActionDefinitionRegistry {

    /**
     * Retorna as actions descobertas para um recurso canonico.
     */
    List<ActionDefinition> findByResourceKey(String resourceKey);

    /**
     * Retorna as actions descobertas para um grupo OpenAPI.
     */
    List<ActionDefinition> findByGroup(String group);
}
