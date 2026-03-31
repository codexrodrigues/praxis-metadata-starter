package org.praxisplatform.uischema.mapper.base;

/**
 * Contrato canonico de mapeamento para o novo core resource-oriented.
 *
 * <p>
 * Diferente de {@link BaseMapper}, esta interface separa explicitamente os fluxos de leitura,
 * criacao e atualizacao. O objetivo e impedir que um unico DTO central continue carregando ao
 * mesmo tempo semantica de response, create e update.
 * </p>
 *
 * @param <E> tipo da entidade
 * @param <R> tipo do DTO de resposta
 * @param <C> tipo do DTO de criacao
 * @param <U> tipo do DTO de atualizacao
 * @param <ID> tipo do identificador do recurso
 */
public interface ResourceMapper<E, R, C, U, ID> {

    /**
     * Converte uma entidade no DTO de resposta canonico.
     */
    R toResponse(E entity);

    /**
     * Cria uma nova entidade a partir do DTO de criacao.
     */
    E newEntity(C dto);

    /**
     * Aplica um payload de atualizacao sobre a entidade ja existente.
     */
    void applyUpdate(E entity, U dto);

    /**
     * Extrai o identificador persistido da entidade.
     */
    ID extractId(E entity);
}
