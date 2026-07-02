package org.praxisplatform.uischema.service.base;

/**
 * Boundary canonico de escrita para resources metadata-driven.
 *
 * <p>
 * A camada de comando separa explicitamente criacao e atualizacao, permitindo DTOs distintos para
 * cada intencao sem reintroduzir um DTO monolitico de CRUD.
 * </p>
 */
public interface BaseResourceCommandService<ResponseDTO, ID, CreateDTO, UpdateDTO>
        extends BaseCreateUpdateResourceCommandService<ResponseDTO, ID, CreateDTO, UpdateDTO> {

    void deleteById(ID id);

    void deleteAllById(java.util.Collection<ID> ids);
}
