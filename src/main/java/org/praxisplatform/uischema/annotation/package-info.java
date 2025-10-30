/**
 * Conjunto de anotações que descrevem recursos REST, agrupamentos OpenAPI e
 * metadados de interface {@code x-ui}.
 *
 * <p>
 * O trio {@link org.praxisplatform.uischema.annotation.ApiResource},
 * {@link org.praxisplatform.uischema.annotation.ApiGroup} e
 * {@link org.praxisplatform.uischema.extension.annotation.UISchema}
 * conecta controllers, DTOs e documentação gerada. Essas anotações são
 * interpretadas pelo pacote {@code configuration} e pelo
 * {@link org.praxisplatform.uischema.extension.CustomOpenApiResolver}.
 * </p>
 *
 * <p><strong>Veja exemplos:</strong>
 * <ul>
 *   <li><a href="../../../../doc-files/exemplos-expondo-controller.html#expondo-controller-com-apiresource-heading">Expondo controller com {@code @ApiResource}</a></li>
 *   <li><a href="../../../../doc-files/exemplos-modelando-dto.html#modelando-dto-com-uischema-heading">Modelando DTO com {@code @UISchema}</a></li>
 * </ul>
 * </p>
 *
 * @since 1.0.0
 */
package org.praxisplatform.uischema.annotation;

