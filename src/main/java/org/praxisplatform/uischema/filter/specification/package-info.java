/**
 * Implementacoes de {@link org.springframework.data.jpa.domain.Specification}
 * e builders que convertem DTOs anotados em consultas dinamicas.
 *
 * <p>
 * O {@link org.praxisplatform.uischema.filter.specification.GenericSpecificationsBuilder}
 * interpreta {@link org.praxisplatform.uischema.filter.dto.GenericFilterDTO} em conjunto
 * com as anotacoes {@link org.praxisplatform.uischema.filter.annotation.Filterable}
 * e monta predicados JPA (Criteria) de forma segura e reutilizavel.
 * Os resultados sao utilizados pelos servicos em {@code service.base}.
 * </p>
 *
 * <p>
 * <strong>Leitura complementar:</strong>
 * Veja a "Visao Geral de Filtros" com exemplos e boas praticas em
 * <a href="doc-files/filters-overview.html">filters-overview.html</a>.
 * </p>
 *
 * @since 1.0.0
 */
package org.praxisplatform.uischema.filter.specification;

