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
 * <p><strong>Tour visual:</strong> guia conceitual dos endpoints e recursos em
 * <a href="doc-files/endpoints-overview.html">endpoints-overview.html</a>.</p>
 *
 * <p><strong>Veja exemplos:</strong>
 * <ul>
 *   <li><a href="../../../../../doc-files/exemplos-expondo-controller.html#expondo-controller-com-apiresource-heading">Expondo controller com {@code @ApiResource}</a></li>
 *   <li><a href="../../../../../doc-files/exemplos-consumindo-contrato.html#consumindo-o-contrato-heading">Consumindo o contrato (/schemas/filtered)</a></li>
 * </ul>
 * </p>
 */
package org.praxisplatform.uischema.controller.base;
