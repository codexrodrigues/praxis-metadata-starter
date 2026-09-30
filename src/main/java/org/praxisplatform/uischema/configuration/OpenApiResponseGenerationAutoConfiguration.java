package org.praxisplatform.uischema.configuration;

import java.util.List;
import org.praxisplatform.uischema.openapi.GenerationScopedGenericResponseService;
import org.springdoc.core.configuration.SpringDocConfiguration;
import org.springdoc.core.parsers.ReturnTypeParser;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.core.service.GenericResponseService;
import org.springdoc.core.service.OperationService;
import org.springdoc.core.utils.PropertyResolverUtils;
import org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

/** Installs the servlet response builder before Springdoc's default, respecting host overrides. */
@AutoConfiguration(after = SpringDocConfiguration.class, before = SpringDocWebMvcConfiguration.class)
@ConditionalOnClass({GenericResponseService.class, SpringDocWebMvcConfiguration.class})
@ConditionalOnBean(SpringDocConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "springdoc.api-docs.enabled", matchIfMissing = true)
public class OpenApiResponseGenerationAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(GenericResponseService.class)
    public GenerationScopedGenericResponseService generationScopedGenericResponseService(
            OperationService operationService, List<ReturnTypeParser> returnTypeParsers,
            SpringDocConfigProperties properties, PropertyResolverUtils propertyResolver) {
        // The concrete return type also makes the same bean discoverable as a global customizer.
        return new GenerationScopedGenericResponseService(operationService, returnTypeParsers, properties, propertyResolver);
    }
}
