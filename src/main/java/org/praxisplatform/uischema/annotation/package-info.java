/**
 * Anotacoes publicas que descrevem recursos REST, discovery semantico e metadados
 * {@code x-ui} do starter.
 *
 * <p>
 * Este pacote concentra a camada declarativa do baseline canonico
 * {@code resource + surfaces + actions + capabilities}. Em termos praticos:
 * </p>
 *
 * <ul>
 *   <li>{@link org.praxisplatform.uischema.annotation.ApiResource} fixa identidade e path do recurso;</li>
 *   <li>{@link org.praxisplatform.uischema.annotation.ApiGroup} organiza a documentacao OpenAPI publica;</li>
 *   <li>{@link org.praxisplatform.uischema.annotation.UiSurface} publica discovery semantico de experiencias de UI;</li>
 *   <li>{@link org.praxisplatform.uischema.annotation.WorkflowAction} publica comandos explicitos de negocio;</li>
 *   <li>{@link org.praxisplatform.uischema.annotation.ResourceCapabilities} resume capacidades habilitadas do recurso.</li>
 * </ul>
 *
 * <p>
 * Essas anotacoes sao interpretadas pela auto-configuracao do starter, pela resolucao canonica
 * de OpenAPI e pelos catalogos semanticos de surfaces, actions e capabilities. Elas nao
 * substituem operacoes HTTP reais nem redefinem sozinhas payloads ou schemas.
 * </p>
 *
 * @since 1.0.0
 */
package org.praxisplatform.uischema.annotation;
