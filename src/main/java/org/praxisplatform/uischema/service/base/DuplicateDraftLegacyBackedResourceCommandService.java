package org.praxisplatform.uischema.service.base;

/**
 * Porta canonica opcional para resources que preparam um rascunho de duplicacao sem persistir dados.
 *
 * <p>
 * O resultado de {@code duplicate-draft} deve ser um DTO editavel, normalmente compatível com o
 * payload de criacao. A persistencia continua acontecendo no {@code POST /resource} subsequente.
 * </p>
 */
public interface DuplicateDraftLegacyBackedResourceCommandService<ID, DraftDTO> {

    /**
     * Informa se a operacao opcional de rascunho duplicado existe para este recurso.
     */
    default boolean supportsDuplicateDraft() {
        return true;
    }

    /**
     * Prepara um rascunho editavel a partir do item informado, sem persistir dados.
     */
    DraftDTO duplicateDraft(ID sourceId);
}
