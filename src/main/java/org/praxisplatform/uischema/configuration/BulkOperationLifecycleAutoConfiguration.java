package org.praxisplatform.uischema.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;
import java.util.EnumSet;
import java.util.Set;
import org.praxisplatform.uischema.action.ActionDefinitionRegistry;
import org.praxisplatform.uischema.bulk.AnnotationDrivenBulkDescriptorDiscovery;
import org.praxisplatform.uischema.bulk.BulkControlPlaneInfrastructure;
import org.praxisplatform.uischema.bulk.BulkExecutionInfrastructure;
import org.praxisplatform.uischema.bulk.BulkLifecycleAuditContext;
import org.praxisplatform.uischema.bulk.BulkOperationControlIdentity;
import org.praxisplatform.uischema.bulk.BulkOperationDescriptorProvider;
import org.praxisplatform.uischema.bulk.BulkOperationLifecycle;
import org.praxisplatform.uischema.bulk.BulkPublicationAdminController;
import org.praxisplatform.uischema.bulk.BulkPublicationConflictException;
import org.praxisplatform.uischema.bulk.BulkPublicationJobIntent;
import org.praxisplatform.uischema.bulk.BulkPublicationJobService;
import org.praxisplatform.uischema.bulk.BulkResourceOperationBindings;
import org.praxisplatform.uischema.openapi.CanonicalOperationResolver;
import org.praxisplatform.uischema.openapi.CachedOpenApiDocumentService;
import org.praxisplatform.uischema.openapi.GovernedOpenApiPublicationFilter;
import org.praxisplatform.uischema.openapi.OpenApiDocumentService;
import org.praxisplatform.uischema.schema.SchemaReferenceResolver;
import org.praxisplatform.uischema.service.base.AbstractBaseResourceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.DelegatingFilterProxyRegistrationBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** Conditional wiring for the explicitly opted-in governed bulk lifecycle. */
@AutoConfiguration
@AutoConfigureAfter(value = OpenApiUiSchemaAutoConfiguration.class,
        name = "org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration")
@ConditionalOnProperty(name = "praxis.bulk.lifecycle.enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnBean({BulkControlPlaneInfrastructure.class, BulkExecutionInfrastructure.class,
        RequestMappingHandlerMapping.class, CanonicalOperationResolver.class, OpenApiDocumentService.class,
        ActionDefinitionRegistry.class, SchemaReferenceResolver.class, BulkResourceOperationBindings.class})
public class BulkOperationLifecycleAutoConfiguration {
    private static final Logger log = LoggerFactory.getLogger(BulkOperationLifecycleAutoConfiguration.class);

    @Autowired(required = false)
    private ObjectProvider<AbstractBaseResourceService<?, ?, ?, ?, ?, ?>> resourceServicesProvider;

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
        List<AbstractBaseResourceService<?, ?, ?, ?, ?, ?>> services = resourceServicesProvider == null
                ? List.of()
                : resourceServicesProvider.orderedStream().toList();
        List<BulkOperationDescriptorProvider> effectiveProviders =
                AnnotationDrivenBulkDescriptorDiscovery.mergeWithDiscoveredProviders(
                        bindings,
                        runtime,
                        services,
                        providers.orderedStream().toList()
                );
        return new BulkOperationLifecycle(bindings, operations, documents, actions,
                objectMapper, schemaReferences, runtime, controlPlane,
                effectiveProviders, capabilities);
    }

    @Bean
    @ConditionalOnMissingBean(BulkPublicationJobService.class)
    public BulkPublicationJobService bulkPublicationJobService(BulkOperationLifecycle lifecycle) {
        return new BulkPublicationJobService(lifecycle);
    }

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnProperty(name = "praxis.bulk.lifecycle.admin-endpoints.enabled", havingValue = "true")
    @ConditionalOnMissingBean(BulkPublicationAdminController.class)
    public BulkPublicationAdminController bulkPublicationAdminController(BulkPublicationJobService jobService) {
        return new BulkPublicationAdminController(jobService);
    }

    /**
     * Opt-in startup initializer ({@code praxis.bulk.lifecycle.auto-ready-on-startup=true}) that
     * publishes or reconciles every configured bulk confirmation operation onto {@code READY}.
     */
    @Bean
    @ConditionalOnProperty(name = "praxis.bulk.lifecycle.auto-ready-on-startup", havingValue = "true")
    public ApplicationListener<ApplicationReadyEvent> praxisBulkLifecycleAutoReadyInitializer(
            BulkOperationLifecycle lifecycle,
            ApplicationContext applicationContext
    ) {
        AtomicBoolean executed = new AtomicBoolean(false);
        return event -> {
            if (event.getApplicationContext() != applicationContext || !executed.compareAndSet(false, true)) {
                return;
            }
            for (BulkOperationControlIdentity identity : lifecycle.configuredIdentities()) {
                lifecycle.ensureReadyOnStartup(identity);
            }
        };
    }

    /**
     * Opt-in asynchronous startup convergence listener ({@code praxis.bulk.lifecycle.startup-convergence})
     * supporting {@code NONE}, {@code RECONCILE_ALL}, or {@code CONVERGE_ALL}. Submits a single
     * asynchronous namespace-wide job on {@link ApplicationReadyEvent} so startup readiness probes are
     * not blocked by full OpenAPI composition.
     */
    @Bean
    @Lazy(false)
    @ConditionalOnProperty(name = "praxis.bulk.lifecycle.startup-convergence")
    public ApplicationListener<ApplicationReadyEvent> praxisBulkLifecycleStartupConvergenceInitializer(
            BulkPublicationJobService jobService,
            ApplicationContext applicationContext,
            @Value("${praxis.bulk.lifecycle.startup-convergence:NONE}") String mode
    ) {
        String normalized = mode == null ? "NONE" : mode.trim().replace('-', '_').toUpperCase(Locale.ROOT);
        BulkPublicationJobIntent intent = switch (normalized) {
            case "", "NONE" -> null;
            case "RECONCILE_ALL" -> BulkPublicationJobIntent.RECONCILE_ALL;
            case "CONVERGE_ALL" -> BulkPublicationJobIntent.CONVERGE_ALL;
            default -> throw new IllegalArgumentException(
                    "Invalid praxis.bulk.lifecycle.startup-convergence '" + mode
                            + "'; expected NONE, RECONCILE_ALL, or CONVERGE_ALL");
        };
        AtomicBoolean executed = new AtomicBoolean(false);
        return event -> {
            if (intent == null
                    || event.getApplicationContext() != applicationContext
                    || !executed.compareAndSet(false, true)) {
                return;
            }
            try {
                jobService.submit(intent, BulkLifecycleAuditContext.system());
            } catch (BulkPublicationConflictException conflict) {
                log.warn("Skipped startup bulk convergence ({}): {}", intent, conflict.getMessage());
            }
        };
    }
}
