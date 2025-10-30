/**
 * Repositórios base para operações CRUD e consultas dinâmicas via
 * {@link org.springframework.data.jpa.domain.Specification}.
 *
 * <p>
 * Interfaces como {@link org.praxisplatform.uischema.repository.base.BaseRepository}
 * e {@link org.praxisplatform.uischema.repository.base.BaseReadOnlyRepository}
 * são consumidas pelos serviços em {@code service.base}, permitindo que o
 * starter resolva filtros declarados com {@code @Filterable} e DTOs do pacote
 * {@code filter}.
 * </p>
 *
 * @since 1.0.0
 */
package org.praxisplatform.uischema.repository.base;

