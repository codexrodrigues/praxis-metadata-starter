/**
 * Camada base de controllers REST metadata-driven do starter.
 *
 * <p>
 * Este pacote concentra as bases canonicas do modelo resource-oriented. A partir delas, um
 * controller pode herdar uma superficie publica extensa com leitura por id, listagem, filtros,
 * paginacao por cursor, options, stats, schema discovery e, quando apropriado, operacoes de
 * escrita.
 * </p>
 *
 * <p>
 * Classes principais:
 * </p>
 *
 * <ul>
 *   <li>{@link org.praxisplatform.uischema.controller.base.AbstractResourceQueryController}: superficie canonica de leitura, options, stats e schema discovery.</li>
 *   <li>{@link org.praxisplatform.uischema.controller.base.AbstractResourceController}: superficie mutante canonica com create, update e delete.</li>
 *   <li>{@link org.praxisplatform.uischema.controller.base.AbstractReadOnlyResourceController}: variante query-only para recursos somente leitura.</li>
 * </ul>
 *
 * <p>
 * O pacote assume que identidade do recurso, discovery semantico e contrato OpenAPI continuam
 * centralizados em operacoes HTTP reais anotadas de forma canonica, e nao em aliases locais ou
 * superfices paralelas.
 * </p>
 */
package org.praxisplatform.uischema.controller.base;
