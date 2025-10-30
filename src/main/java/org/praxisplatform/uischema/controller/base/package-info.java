/**
 * Camada base de controllers com foco em entrega rápida e consistente.
 *
 * <p>
 * <strong>Por que usar:</strong> até 13 endpoints por recurso (CRUD completo, filtros, paginação por cursor,
 * options id/label e schemas), documentação viva (OpenAPI por grupo + cache + ETag) e HATEOAS opcional.
 * </p>
 *
 * <p>
 * <strong>Classes principais:</strong>
 * <ul>
 *   <li>{@link org.praxisplatform.uischema.controller.base.AbstractCrudController} — superfície completa de endpoints.</li>
 *   <li>{@link org.praxisplatform.uischema.controller.base.AbstractReadOnlyController} — modo read‑only (views/@Immutable) com 405 para escrita.</li>
 * </ul>
 * </p>
 *
 * <p><strong>Tour visual:</strong> veja um panorama dos endpoints e recursos em
 * <a href="doc-files/controllers-overview.html">controllers-overview.html</a>.</p>
 */
package org.praxisplatform.uischema.controller.base;
