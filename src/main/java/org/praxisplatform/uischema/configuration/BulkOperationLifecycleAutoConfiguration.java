package org.praxisplatform.uischema.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;
import java.util.EnumSet;
import java.util.Set;
import org.praxisplatform.uischema.action.ActionDefinitionRegistry;
import org.praxisplatform.uischema.bulk.BulkControlPlaneInfrastructure;
import org.praxisplatform.uischema.bulk.BulkExecutionInfrastructure;
import org.praxisplatform.uischema.bulk.BulkOperationDescriptorProvider;
import org.praxisplatform.uischema.bulk.BulkOperationLifecycle;
import org.praxisplatform.uischema.bulk.BulkResourceOperationBindings;
import org.praxisplatform.uischema.openapi.CanonicalOperationResolver;
import org.praxisplatform.uischema.openapi.CachedOpenApiDocumentService;
import org.praxisplatform.uischema.openapi.GovernedOpenApiPublicationFilter;
import org.praxisplatform.uischema.openapi.OpenApiDocumentService;
import org.praxisplatform.uischema.schema.SchemaReferenceResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.DelegatingFilterProxyRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** Conditional wiring for the explicitly opted-in governed bulk lifecycle. */
@AutoConfiguration
@AutoConfigureAfter(value = OpenApiUiSchemaAutoConfiguration.class,
        name = "org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration")
@ConditionalOnBean({BulkControlPlaneInfrastructure.class, BulkExecutionInfrastructure.class,
        RequestMappingHandlerMapping.class, CanonicalOperationResolver.class, OpenApiDocumentService.class,
        ActionDefinitionRegistry.class, SchemaReferenceResolver.class, BulkResourceOperationBindings.class})
public class BulkOperationLifecycleAutoConfiguration {

    /**
     * Follows the effective canonical Boot security registration. Arbitrary host target overrides
     * require host validation; the proxy's protected target is not introspected here. Without
     * a security chain/registration, the properties order is only ordering, never an IAM attestation.
     * This installation prerequisite never publishes a tuple or grants READY.
     */
    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    public FilterRegistrationBean<GovernedOpenApiPublicationFilter> governedOpenApiPublicationFilterRegistration(
            OpenApiDocumentService documents, ObjectProvider<SecurityProperties> securityProperties,
            @Qualifier("securityFilterChainRegistration") ObjectProvider<DelegatingFilterProxyRegistrationBean> securityRegistrations,
            BeanFactory beans) {
        if (!(documents instanceof CachedOpenApiDocumentService cached))
            throw new IllegalStateException("Governed bulk serving requires the concrete immutable document service");
        var security = securityRegistrations.getIfAvailable();
        boolean hasSecurityChain = beans.containsBean("springSecurityFilterChain");
        if (hasSecurityChain != (security != null))
            throw new IllegalStateException("Governed serving requires the canonical Boot security registration topology");
        int securityOrder;
        if (security != null) {
            if (!security.isEnabled() || !"springSecurityFilterChain".equals(security.getFilterName())
                    || !security.getServletNames().isEmpty() || !security.getServletRegistrationBeans().isEmpty()
                    || (!security.getUrlPatterns().isEmpty() && !Set.copyOf(security.getUrlPatterns()).equals(Set.of("/*")))
                    || !security.determineDispatcherTypes().contains(DispatcherType.REQUEST))
                throw new IllegalStateException("Governed serving requires complete enabled Boot security request coverage");
            securityOrder = security.getOrder();
        } else securityOrder = securityProperties.getIfAvailable(SecurityProperties::new).getFilter().getOrder();
        var registration = new FilterRegistrationBean<>(new GovernedOpenApiPublicationFilter(cached));
        registration.setName("praxisGovernedOpenApiPublicationFilter");
        registration.addUrlPatterns("/*");
        registration.setDispatcherTypes(EnumSet.allOf(DispatcherType.class));
        registration.setAsyncSupported(true);
        registration.setOrder(Math.addExact(securityOrder, 1));
        return registration;
    }

    @Bean
    @ConditionalOnMissingBean(BulkOperationLifecycle.class)
    public BulkOperationLifecycle bulkOperationLifecycle(BulkControlPlaneInfrastructure controlPlane,
            BulkExecutionInfrastructure runtime, BulkResourceOperationBindings bindings,
            CanonicalOperationResolver operations,
            OpenApiDocumentService documents, ActionDefinitionRegistry actions,
            ObjectMapper objectMapper, SchemaReferenceResolver schemaReferences,
            ObjectProvider<BulkOperationDescriptorProvider> providers,
            org.praxisplatform.uischema.capability.CanonicalCapabilityResolver capabilities) {
        return new BulkOperationLifecycle(bindings, operations, documents, actions,
                objectMapper, schemaReferences, runtime, controlPlane,
                providers.orderedStream().toList(), capabilities);
    }
}
