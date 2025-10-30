/**
 * Implementações de {@link org.springframework.data.jpa.domain.Specification}
 * e builders que convertem DTOs anotados em consultas dinâmicas.
 *
 * <p>
 * Componentes como {@link org.praxisplatform.uischema.filter.specification.DynamicSpecificationBuilder}
 * interpretam {@link org.praxisplatform.uischema.filter.dto.FilterDefinitionDTO}
 * e os metadados declarados via {@code @Filterable}. Os resultados são
 * utilizados pelos serviços do pacote {@code service.base}.
 * </p>
 *
 * @since 1.0.0
 */
package org.praxisplatform.uischema.filter.specification;

