package org.praxisplatform.uischema;

/**
 * Chaves canonicas de {@code x-ui} em nivel de operacao HTTP.
 *
 * <p>
 * Essas propriedades descrevem metadados ligados ao endpoint como um todo, e nao apenas a campos
 * individuais. Elas ajudam consumidores a descobrir schema de resposta, colunas de exibicao,
 * campos de filtro sugeridos e endpoints correlatos a partir do OpenAPI enriquecido.
 * </p>
 *
 * @since 1.0.0
 * @see org.praxisplatform.uischema.controller.docs.ApiDocsController
 */
public interface OperationProperties {
    String RESPONSE_SCHEMA = "responseSchema";
    String DISPLAY_FIELDS = "displayFields";
    String DISPLAY_COLUMNS = "displayColumns";
    String FILTER_FIELDS = "filterFields";
    String RELATED_ENTITIES_ENDPOINTS = "relatedEntitiesEndpoints";
}
