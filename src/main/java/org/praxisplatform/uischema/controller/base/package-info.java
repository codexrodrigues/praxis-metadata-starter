/**
 * Camada base de controllers REST metadata-driven da plataforma.
 *
 * <p>
 * Este pacote concentra as bases canônicas de recurso do starter. A partir delas, um controller
 * pode herdar uma superficie publica extensa com CRUD, filtros, paginacao por cursor, options,
 * option-sources, stats e acesso ao schema metadata-driven do recurso.
 * </p>
 *
 * <p>
 * <strong>Classes principais:</strong>
 * <ul>
 *   <li>{@link org.praxisplatform.uischema.controller.base.AbstractResourceQueryController} — superficie canonica de leitura, options, stats e schema discovery.</li>
 *   <li>{@link org.praxisplatform.uischema.controller.base.AbstractResourceController} — superficie mutante canonica com create, update e delete.</li>
 *   <li>{@link org.praxisplatform.uischema.controller.base.AbstractReadOnlyResourceController} — variante query-only para recursos somente leitura.</li>
 *   <li>{@link org.praxisplatform.uischema.controller.base.AbstractCrudController} — legado em migracao; nao deve receber novas semanticas.</li>
 * </ul>
 * </p>
 *
 * <p><strong>Tour visual:</strong> <a href="doc-files/endpoints-overview.html">endpoints-overview.html</a>.</p>
 */
package org.praxisplatform.uischema.controller.base;
