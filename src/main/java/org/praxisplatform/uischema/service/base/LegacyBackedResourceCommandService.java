package org.praxisplatform.uischema.service.base;

/**
 * Porta canonica de comando para resources mutaveis delegados a backend legado.
 *
 * <p>
 * A semantica publica continua resource-oriented. O host decide se a execucao real usa stored
 * procedure, command handler, adaptador mainframe, servico externo ou outro mecanismo privado.
 * </p>
 */
public interface LegacyBackedResourceCommandService<ResponseDTO, ID, CreateDTO, UpdateDTO>
        extends BaseResourceCommandService<ResponseDTO, ID, CreateDTO, UpdateDTO> {

    /**
     * Informa se a operacao opcional de rascunho duplicado existe para este recurso.
     */
    default boolean supportsDuplicateDraft() {
        return false;
    }

    /**
     * Cria um rascunho duplicado a partir do item informado.
     */
    default SavedResult<ID, ResponseDTO> duplicateDraft(ID sourceId) {
        throw new UnsupportedOperationException("duplicate-draft is not supported by this resource.");
    }
}
