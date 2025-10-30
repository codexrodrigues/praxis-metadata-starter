/**
 * Auto-configurações Spring Boot que conectam anotações, resolvers e
 * endpoints de documentação.
 *
 * <p>
 * Classes como {@link org.praxisplatform.uischema.configuration.OpenApiUiSchemaAutoConfiguration}
 * registram {@link org.praxisplatform.uischema.extension.CustomOpenApiResolver},
 * {@link org.praxisplatform.uischema.controller.docs.ApiDocsController} e
 * integradores adicionais com SpringDoc. Consulte a "Visão Arquitetural" para
 * entender a ordem de inicialização e dependências geradas.
 * </p>
 *
 * @since 1.0.0
 */
package org.praxisplatform.uischema.configuration;

