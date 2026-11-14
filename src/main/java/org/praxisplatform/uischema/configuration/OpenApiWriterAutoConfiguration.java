package org.praxisplatform.uischema.configuration;

import org.praxisplatform.uischema.openapi.OrderedOpenApiJsonResponse;
import org.springdoc.webmvc.api.OpenApiResource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Import;

@AutoConfiguration
@ConditionalOnClass(OpenApiResource.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "springdoc.api-docs.enabled", matchIfMissing = true)
@ConditionalOnProperty(name = "springdoc.writer-with-order-by-keys", havingValue = "true")
@Import(OrderedOpenApiJsonResponse.class)
public class OpenApiWriterAutoConfiguration {
}