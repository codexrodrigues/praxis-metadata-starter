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
 *   <li>{@link org.praxisplatform.uischema.annotation.DomainGovernance} declara classificacao semantica e politicas de IA para campos;</li>
 *   <li>{@link org.praxisplatform.uischema.annotation.DomainGovernanceKind},
 *       {@link org.praxisplatform.uischema.annotation.DomainClassification},
 *       {@link org.praxisplatform.uischema.annotation.DomainDataCategory},
 *       {@link org.praxisplatform.uischema.annotation.AiVisibilityMode},
 *       {@link org.praxisplatform.uischema.annotation.AiTrainingUseMode} e
 *       {@link org.praxisplatform.uischema.annotation.AiControlledUseMode} fixam os tokens canonicos de governanca;</li>
 *   <li>{@link org.praxisplatform.uischema.annotation.UiSurface} publica discovery semantico de experiencias de UI;</li>
 *   <li>{@link org.praxisplatform.uischema.annotation.WorkflowAction} publica comandos explicitos de negocio;</li>
 *   <li>{@link org.praxisplatform.uischema.annotation.FormLayout},
 *       {@link org.praxisplatform.uischema.annotation.FormLayoutSection},
 *       {@link org.praxisplatform.uischema.annotation.FormLayoutStep},
 *       {@link org.praxisplatform.uischema.annotation.FormLayoutMode},
 *       {@link org.praxisplatform.uischema.annotation.FormLayoutTabsAppearance},
 *       {@link org.praxisplatform.uischema.annotation.FormLayoutStepperReadMode} e
 *       {@link org.praxisplatform.uischema.annotation.UiBoolean} configuram opcionalmente a organizacao
 *       estrutural de secoes ({@code x-ui.formLayout}) em DTOs de comando/detalhe ou endpoints;</li>
 *   <li>{@link org.praxisplatform.uischema.annotation.CalculationMemory} e
 *       {@link org.praxisplatform.uischema.annotation.ReactiveDetermination} descrevem memorias de calculo
 *       auditaveis e determinacoes reativas declarativas de dominio;</li>
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
