package org.praxisplatform.uischema.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.praxisplatform.uischema.bulk.*;
import org.praxisplatform.uischema.extension.CustomOpenApiResolver;
import org.praxisplatform.uischema.filter.web.FilterPayloadNormalizer;
import org.praxisplatform.uischema.openapi.CanonicalOperationResolver;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import java.util.List;

/** Protocol composition only: no automatic bulk database migration, publication or worker startup. */
@AutoConfiguration(after = OpenApiUiSchemaAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class PraxisBulkWebAutoConfiguration {
    @Bean @ConditionalOnMissingBean
    public PraxisBulkProtocolHttpMessageConverter praxisBulkProtocolHttpMessageConverter(ObjectMapper mapper,
            ObjectProvider<FilterPayloadNormalizer> normalizers) {
        return new PraxisBulkProtocolHttpMessageConverter(mapper, normalizers.orderedStream().toList());
    }

    @Bean
    public WebMvcConfigurer praxisBulkProtocolWebMvcConfigurer(ObjectProvider<PraxisBulkProtocolHttpMessageConverter> converter) {
        return new WebMvcConfigurer() {
            @Override public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
                var bulk = converter.getObject();
                converters.remove(bulk); converters.addFirst(bulk);
            }
        };
    }

    @Bean @ConditionalOnMissingBean
    public BulkOperationIdentityDiscovery bulkOperationIdentityDiscovery(BulkResourceOperationBindings bindings,
            CanonicalOperationResolver operations) {
        return new BulkOperationIdentityDiscovery(bindings, operations);
    }

    @Bean @ConditionalOnMissingBean @ConditionalOnBean(BulkExecutionInfrastructure.class)
    public BulkOperationStatusLookup bulkOperationStatusLookup(BulkExecutionInfrastructure infrastructure,
            BulkOperationIdentityDiscovery discovery) {
        return new BulkOperationStatusLookup(infrastructure, discovery);
    }

    @Bean @Order(Ordered.LOWEST_PRECEDENCE)
    public GlobalOpenApiCustomizer bulkEvaluationSchemaCustomizer(BulkResourceOperationBindings bindings,
            CustomOpenApiResolver resolver) {
        return document -> resolver.synthesizeBulkEvaluationSchemas(document, bindings);
    }
}
