/**
 * Servicos base que orquestram repositorios, Specifications e convencoes do core
 * resource-oriented.
 *
 * <p>
 * Este pacote separa explicitamente query e command services para evitar que recursos
 * read-only herdem semantica mutante desnecessaria. Ele tambem centraliza convencoes de
 * filtragem, ordenacao padrao, projecoes id/label e o cabecalho opcional
 * {@code X-Data-Version}.
 * </p>
 *
 * <p>
 * O fluxo esperado e:
 * </p>
 *
 * <ul>
 *   <li>{@link org.praxisplatform.uischema.service.base.AbstractBaseQueryResourceService} para leitura e discovery;</li>
 *   <li>{@link org.praxisplatform.uischema.service.base.AbstractBaseResourceService} para recursos mutaveis;</li>
 *   <li>{@link org.praxisplatform.uischema.service.base.AbstractReadOnlyResourceService} para recursos estritamente query-only.</li>
 * </ul>
 */
package org.praxisplatform.uischema.service.base;
