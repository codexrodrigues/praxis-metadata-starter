/**
 * Endpoints e componentes que publicam documentacao OpenAPI enriquecida e discovery documental.
 *
 * <p>
 * Este pacote abriga as superficies documentais canonicas do starter, como
 * {@link org.praxisplatform.uischema.controller.docs.ApiDocsController},
 * {@link org.praxisplatform.uischema.controller.docs.DomainCatalogController},
 * {@link org.praxisplatform.uischema.controller.docs.SurfaceCatalogController} e
 * {@link org.praxisplatform.uischema.controller.docs.ActionCatalogController}.
 * </p>
 *
 * <p>
 * A regra central aqui e separar claramente:
 * </p>
 *
 * <ul>
 *   <li>{@code /schemas/filtered} como superficie estrutural canonica;</li>
 *   <li>catalogos e discovery como superficies derivadas para navegacao e indexacao;</li>
 *   <li>OpenAPI completo como fonte documental ampla, mas nao diretamente otimizada para consumo runtime.</li>
 * </ul>
 *
 * @since 1.0.0
 */
package org.praxisplatform.uischema.controller.docs;
