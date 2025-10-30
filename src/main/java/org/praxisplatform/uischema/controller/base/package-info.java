/**
 * Controllers genéricos que expõem operações CRUD, paginação, filtros e
 * endpoints de opções reutilizáveis.
 *
 * <p>
 * Essa camada fornece classes como {@link org.praxisplatform.uischema.controller.base.AbstractCrudController}
 * e {@link org.praxisplatform.uischema.controller.base.BaseFilterController},
 * utilizadas diretamente pelas aplicações. Os controllers são pensados para
 * trabalhar em conjunto com os serviços de {@code service.base} e com as
 * anotações {@code @ApiResource} e {@code @Filterable}.
 * </p>
 *
 * @since 1.0.0
 */
package org.praxisplatform.uischema.controller.base;

