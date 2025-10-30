package org.praxisplatform.uischema.mapper.base;

/**
 * Contrato mínimo para mapeamento entre entidade e DTO.
 * Implementações costumam ser geradas com MapStruct.
 *
 * @param <E> tipo da entidade
 * @param <D> tipo do DTO
 * @since 1.0.0
 */
public interface BaseMapper<E, D> {

    /**
     * Converte uma entidade em DTO.
     * @param entity entidade de origem
     * @return DTO resultante
     */
    D toDto(E entity);

    /**
     * Converte um DTO em entidade.
     * @param dto DTO de origem
     * @return entidade resultante
     */
    E toEntity(D dto);
}
