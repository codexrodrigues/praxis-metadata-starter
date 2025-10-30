/**
 * Serviços base que orquestram repositórios, Specifications e convenções corporativas.
 *
 * <p>
 * <strong>Destaques:</strong>
 * <ul>
 *   <li>Ordenação padrão declarativa via {@link org.praxisplatform.uischema.service.base.annotation.DefaultSortColumn}.</li>
 *   <li>Projeções id/label com {@code OptionMapper} + {@link org.praxisplatform.uischema.annotation.OptionLabel}.</li>
 *   <li>Cabeçalho {@code X-Data-Version} opcional para políticas de cache HTTP.</li>
 * </ul>
 * </p>
 */
package org.praxisplatform.uischema.service.base;

