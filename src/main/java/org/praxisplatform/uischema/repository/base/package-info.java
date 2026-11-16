/**
 * Repositórios base para operações de persistência de recursos e consultas dinâmicas via
 * {@link org.springframework.data.jpa.domain.Specification}.
 *
 * <p>
 * Interfaces como {@link org.praxisplatform.uischema.repository.base.BaseResourceRepository}
 * e {@link org.praxisplatform.uischema.repository.base.BaseCrudRepository}, junto aos contratos de servico em {@code service.base},
 * são consumidas pelos serviços em {@code service.base}, permitindo que o
 * starter resolva filtros declarados com {@code @Filterable} e DTOs do pacote
 * {@code filter}.
 * </p>
 *
 * @since 1.0.0
 */
package org.praxisplatform.uischema.repository.base;

