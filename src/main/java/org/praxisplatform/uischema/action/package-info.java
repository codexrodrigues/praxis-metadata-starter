/**
 * Discovery semantico de workflow actions e avaliacao contextual de disponibilidade.
 *
 * <p>
 * Este pacote organiza:
 * </p>
 *
 * <ul>
 *   <li>definicoes e registries que descobrem actions a partir de endpoints reais anotados com {@code @WorkflowAction};</li>
 *   <li>contexto, regras e evaluators de availability;</li>
 *   <li>catalogos serializaveis consumidos por runtime e discovery documental.</li>
 * </ul>
 *
 * <p>
 * A regra central e que action representa um comando explicito de negocio. Ela referencia uma
 * operacao HTTP real e seus schemas canonicos, sem criar dispatcher generico ou payload paralelo.
 * </p>
 */
package org.praxisplatform.uischema.action;
