/**
 * Resolucao canonica de operacoes e acesso a documentos OpenAPI agrupados.
 *
 * <p>
 * Este pacote centraliza:
 * </p>
 *
 * <ul>
 *   <li>a identificacao canonica de operacoes documentadas;</li>
 *   <li>a obtencao e o cache de documentos OpenAPI por grupo;</li>
 *   <li>o suporte compartilhado para schemas filtrados, catalogos e capabilities.</li>
 * </ul>
 *
 * <p>
 * A regra central e evitar heuristicas duplicadas de grupo, path, metodo e {@code operationId}
 * espalhadas pelos consumidores do starter.
 * </p>
 */
package org.praxisplatform.uischema.openapi;
