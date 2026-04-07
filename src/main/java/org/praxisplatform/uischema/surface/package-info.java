/**
 * Discovery semantico de surfaces e avaliacao contextual de disponibilidade.
 *
 * <p>
 * Este pacote cobre tres camadas complementares:
 * </p>
 *
 * <ul>
 *   <li>definicoes e registries que descobrem surfaces a partir de operacoes reais;</li>
 *   <li>contexto e regras de availability para decidir disponibilidade por request e recurso;</li>
 *   <li>catalogos serializaveis derivados usados por consumidores runtime e documentais.</li>
 * </ul>
 *
 * <p>
 * A regra central e que surface representa discovery semantico de UI e nunca substitui o contrato
 * HTTP nem o schema canonico resolvido por {@code /schemas/filtered}.
 * </p>
 */
package org.praxisplatform.uischema.surface;
