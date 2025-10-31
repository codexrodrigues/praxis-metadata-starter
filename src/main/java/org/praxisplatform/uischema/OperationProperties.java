package org.praxisplatform.uischema;

/**
 * Propriedades canônicas da extensão {@code x-ui} em nível de operação (endpoint).
 * <p>
 * As chaves aqui definidas são usadas por páginas e clientes para apontar schema
 * de resposta, colunas/títulos padrão e campos de filtro sugeridos, mantendo a
 * consistência com o vocabulário de {@link org.praxisplatform.uischema.FieldConfigProperties}.
 * </p>
 *
 * @since 1.0.0
 * @see org.praxisplatform.uischema.FieldConfigProperties
 * @see org.praxisplatform.uischema.controller.docs.ApiDocsController
 */
public interface OperationProperties {
    String RESPONSE_SCHEMA = "responseSchema";
    String DISPLAY_FIELDS = "displayFields";
    String DISPLAY_COLUMNS = "displayColumns";
    String FILTER_FIELDS = "filterFields";
    String RELATED_ENTITIES_ENDPOINTS = "relatedEntitiesEndpoints";
}
