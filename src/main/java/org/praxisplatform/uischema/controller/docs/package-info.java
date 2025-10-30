/**
 * Endpoints responsáveis por publicar documentação OpenAPI enriquecida com
 * metadados {@code x-ui}.
 *
 * <p>
 * O {@link org.praxisplatform.uischema.controller.docs.ApiDocsController}
 * resolve automaticamente o grupo correto via
 * {@link org.praxisplatform.uischema.util.OpenApiGroupResolver}, aplica cache
 * com ETag e filtra schemas conforme descrito na "Visão Arquitetural" do
 * projeto. Esses controladores são habilitados pela auto-configuração do
 * pacote {@code configuration}.
 * </p>
 *
 * @since 1.0.0
 */
package org.praxisplatform.uischema.controller.docs;
