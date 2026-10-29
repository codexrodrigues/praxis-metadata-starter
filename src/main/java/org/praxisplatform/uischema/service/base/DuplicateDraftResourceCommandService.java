package org.praxisplatform.uischema.service.base;

/**
 * Porta canonica opcional para resources que preparam um rascunho de duplicacao sem persistir dados.
 *
 * <p>
 * O resultado de {@code duplicate-draft} deve ser um DTO editavel compativel com o
 * payload de criacao. A persistencia continua acontecendo no {@code POST /resource} subsequente.
 * </p>
 */
public interface DuplicateDraftResourceCommandService<ID, DraftDTO> {

    /**
     * Informa se a operacao opcional de rascunho duplicado existe para este recurso.
     */
    default boolean supportsDuplicateDraft() {
        return true;
    }

    /**
     * Prepara um rascunho editavel a partir do item informado. Jamais deve persistir dados,
     * alterar a origem ou executar efeitos de dominio de uma criacao.
     *
     * <p>A implementacao aplica a politica explicita do recurso para limpar identificadores,
     * campos unicos, credenciais, aprovacoes e estado de ciclo de vida. O rascunho pode exigir
     * preenchimento e revisao antes de satisfazer as validacoes da criacao; estas devem ser
     * reavaliadas no POST final. Clonagem transacional pertence a um comando de dominio separado.</p>
     *
     * @param sourceId identificador do item de origem, sujeito a leitura e escopo autorizados
     * @return preenchimento editavel para o contrato de criacao, nunca um novo registro persistido
     */
    DraftDTO duplicateDraft(ID sourceId);
}
