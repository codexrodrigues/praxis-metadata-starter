package org.praxisplatform.uischema.action;

/**
 * Resolve o contexto compartilhado de availability de actions para o catalogo atual.
 *
 * <p>
 * Esta fronteira encapsula a coleta de sinais contextuais do runtime e evita que o catalogo de
 * actions ou as regras de availability acessem diretamente detalhes HTTP ou de seguranca.
 * </p>
 */
public interface ActionAvailabilityContextResolver {

    /**
     * Resolve um contexto compartilhavel por todas as actions do mesmo recurso e request.
     */
    ActionAvailabilityContext resolve(String resourceKey, String resourcePath, Object resourceId);
}
