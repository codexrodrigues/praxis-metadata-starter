package org.praxisplatform.uischema.service.base;

import java.util.Collection;

/**
 * Boundary canonico de escrita para resources metadata-driven.
 *
 * <p>
 * A camada de comando separa explicitamente criacao e atualizacao, permitindo DTOs distintos para
 * cada intencao sem reintroduzir um DTO monolitico de CRUD.
 * </p>
 */
public interface BaseResourceCommandService<ResponseDTO, ID, CreateDTO, UpdateDTO> {

    /**
     * Resultado de criacao que devolve simultaneamente o ID persistido e o DTO de resposta.
     */
    record SavedResult<ID, R>(ID id, R body) {}

    SavedResult<ID, ResponseDTO> create(CreateDTO dto);

    ResponseDTO update(ID id, UpdateDTO dto);

    void deleteById(ID id);

    void deleteAllById(Collection<ID> ids);
}
