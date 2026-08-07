package org.praxisplatform.uischema.service.base;

import org.praxisplatform.uischema.concurrency.ResourceVersionUpdatePrecondition;

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

    /** Whether this resource requires a strong item-version precondition for ordinary updates. */
    default boolean requiresResourceVersionPrecondition() {
        return false;
    }

    /**
     * Executes a version-aware update. Implementations must verify the precondition against the
     * version read inside the same transaction/lock that applies the mutation.
     */
    default ResponseDTO update(
            ID id,
            UpdateDTO dto,
            ResourceVersionUpdatePrecondition precondition
    ) {
        if (requiresResourceVersionPrecondition()) {
            throw new IllegalStateException(
                    "Versioned resource command service must implement transactional precondition validation."
            );
        }
        return update(id, dto);
    }
}
