package org.praxisplatform.uischema.service.base;

/**
 * Boundary canonico de escrita parcial para resources metadata-driven.
 *
 * <p>
 * Use esta porta quando o recurso publica criacao e atualizacao como operacoes canonicas do
 * proprio resource, mas nao publica exclusao. Recursos CRUD completos continuam usando
 * {@link BaseResourceCommandService}.
 * </p>
 */
public interface BaseCreateUpdateResourceCommandService<ResponseDTO, ID, CreateDTO, UpdateDTO> {

    /**
     * Resultado de criacao que devolve simultaneamente o ID persistido e o DTO de resposta.
     */
    record SavedResult<ID, R>(ID id, R body) {}

    SavedResult<ID, ResponseDTO> create(CreateDTO dto);

    ResponseDTO update(ID id, UpdateDTO dto);
}
