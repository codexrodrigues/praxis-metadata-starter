package org.praxisplatform.uischema.surface;

import java.util.List;

/**
 * Registry canonicamente responsavel por descobrir surfaces publicadas.
 *
 * <p>
 * Esta fronteira separa a fase de descoberta estrutural das surfaces da fase de avaliacao
 * contextual de disponibilidade. O registry descobre o que existe; o catalogo e o evaluator
 * decidem o que fica disponivel em cada contexto.
 * </p>
 */
public interface SurfaceDefinitionRegistry {

    /**
     * Retorna as surfaces descobertas para um recurso canonico.
     */
    List<SurfaceDefinition> findByResourceKey(String resourceKey);

    /**
     * Retorna as surfaces descobertas para um grupo OpenAPI.
     */
    List<SurfaceDefinition> findByGroup(String group);
}
