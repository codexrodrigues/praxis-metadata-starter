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
 *   <li>{@link org.praxisplatform.uischema.controller.base.AbstractCrudController} — superficie completa do recurso.</li>
 *   <li>{@link org.praxisplatform.uischema.controller.base.AbstractReadOnlyController} — variante somente leitura com {@code 405} para escrita.</li>
 * </ul>
 * </p>
 *
 * <p><strong>Tour visual:</strong> <a href="doc-files/endpoints-overview.html">endpoints-overview.html</a>.</p>
 */
package org.praxisplatform.uischema.controller.base;
