/**
 * Serviços base que centralizam regras CRUD, paginação, opções e suporte a
 * Specifications.
 *
 * <p>
 * Fornece implementações como {@link org.praxisplatform.uischema.service.base.BaseCrudService}
 * e {@link org.praxisplatform.uischema.service.base.AbstractReadOnlyService},
 * pensadas para trabalhar com os controllers de {@code controller.base} e os
 * repositórios em {@code repository.base}. Essa camada também fornece
 * integra com {@link org.praxisplatform.uischema.filter.dto.GenericFilterDTO} e o
 * {@link org.praxisplatform.uischema.filter.specification.GenericSpecificationsBuilder}.
 * </p>
 *
 * @since 1.0.0
 */
package org.praxisplatform.uischema.service.base;

