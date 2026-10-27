package org.praxisplatform.uischema.fact;

import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.annotation.DomainClassification;
import org.praxisplatform.uischema.annotation.DomainDataCategory;
import org.praxisplatform.uischema.annotation.DomainGovernance;
import org.praxisplatform.uischema.annotation.DomainGovernanceKind;
import org.springframework.aop.framework.autoproxy.DefaultAdvisorAutoProxyCreator;
import org.springframework.aop.support.AopUtils;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.NameMatchMethodPointcut;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnnotationResourceFactCatalogProviderTest {
    private static final String RESOURCE = "hr.employees";

    @Test
    void discoversImplementationAnnotationsBehindASpringJdkProxyWithoutInitializingLazyBeans() {
        try (var context = new AnnotationConfigApplicationContext(ProxiedFactsConfiguration.class)) {
            assertThat(AopUtils.isJdkDynamicProxy(context.getBean("facts"))).isTrue();
            assertThat(context.getType("facts", false)).isNotEqualTo(FactsService.class);
            var provider = new AnnotationResourceFactCatalogProvider(context.getBeanFactory());

            assertThat(provider.findFacts(RESOURCE, null)).extracting(ResourceFactDescriptor::key)
                    .containsExactlyInAnyOrder("tenure", "lazy_tenure");
            assertThat(provider.findFacts(RESOURCE, null)).hasSize(2);
            assertThat(context.getBeanFactory().containsSingleton("lazyFacts")).isFalse();
        }
    }

    @Test
    void recoversOriginalConcreteDefinitionWhenPublishedBeanTypeIsAJdkProxy() {
        var factory = new DefaultListableBeanFactory();
        var proxyFactory = new org.springframework.aop.framework.ProxyFactory(new FactsService());
        proxyFactory.setInterfaces(FactsApi.class);
        Object proxy = proxyFactory.getProxy();
        var definition = new RootBeanDefinition(FactsService.class);
        definition.setInstanceSupplier(() -> proxy);
        factory.registerBeanDefinition("facts", definition);
        factory.getBean("facts");

        assertThat(new AnnotationResourceFactCatalogProvider(factory).findFacts(RESOURCE, null))
                .singleElement().satisfies(fact -> {
                    assertThat(fact.key()).isEqualTo("tenure");
                    assertThat(fact.governance()).containsEntry("confidence", 0.4d)
                            .containsEntry("reason", "Historical evidence requires review");
                });
    }

    @Test
    void discoversLazyFactoryMethodDeclarationsWithoutCallingTheFactory() {
        try (var context = new AnnotationConfigApplicationContext(LazyFactoryConfiguration.class)) {
            assertThat(new AnnotationResourceFactCatalogProvider(context.getBeanFactory()).findFacts(RESOURCE, null))
                    .extracting(ResourceFactDescriptor::key).containsExactlyInAnyOrder("factory_fact", "lazy_tenure");
            assertThat(context.getBeanFactory().containsSingleton("declaredFact")).isFalse();
            assertThat(context.getBeanFactory().containsSingleton("concreteFacts")).isFalse();
        }
    }

    @Test
    void publishesOnlyTheResolvedFactoryOverload() throws Exception {
        var factory = new DefaultListableBeanFactory();
        var owner = new RootBeanDefinition(OverloadedFactory.class);
        owner.setLazyInit(true);
        factory.registerBeanDefinition("factory", owner);
        var product = new RootBeanDefinition();
        product.setFactoryBeanName("factory");
        product.setFactoryMethodName("create");
        product.setResolvedFactoryMethod(OverloadedFactory.class.getDeclaredMethod("create"));
        product.setTargetType(FactsApi.class);
        product.setLazyInit(true);
        factory.registerBeanDefinition("product", product);

        assertThat(new AnnotationResourceFactCatalogProvider(factory).findFacts(RESOURCE, null))
                .singleElement().satisfies(fact -> assertThat(fact.key()).isEqualTo("selected"));
        assertThat(factory.containsSingleton("factory")).isFalse();
        assertThat(factory.containsSingleton("product")).isFalse();
    }

    @Test
    void resolvesFactoryOwnerProducedByAnotherLazyBeanMethod() throws Exception {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(NestedFactoryConfiguration.class);
            var product = new RootBeanDefinition();
            product.setFactoryBeanName("factOwner");
            product.setFactoryMethodName("create");
            product.setResolvedFactoryMethod(OverloadedFactory.class.getDeclaredMethod("create"));
            product.setTargetType(FactsApi.class);
            product.setLazyInit(true);
            context.registerBeanDefinition("product", product);
            context.refresh();

            assertThat(new AnnotationResourceFactCatalogProvider(context.getBeanFactory()).findFacts(RESOURCE, null))
                    .singleElement().satisfies(fact -> assertThat(fact.key()).isEqualTo("selected"));
            assertThat(context.getBeanFactory().containsSingleton("factOwner")).isFalse();
            assertThat(context.getBeanFactory().containsSingleton("product")).isFalse();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class NestedFactoryConfiguration {
        @Bean @Lazy OverloadedFactory factOwner() {
            throw new AssertionError("Catalog must not create the factory owner");
        }
    }

    @Test
    void discoversFactsFromFactoryOwnerRegisteredOnlyAsSingleton() {
        var factory = new DefaultListableBeanFactory();
        factory.registerSingleton("owner", new SingletonFactoryOwner());
        var product = new RootBeanDefinition();
        product.setFactoryBeanName("owner");
        product.setFactoryMethodName("build");
        product.setTargetType(SingletonFactoryProduct.class);
        factory.registerBeanDefinition("product", product);

        assertThat(factory.containsBeanDefinition("owner")).isFalse();
        assertThat(new AnnotationResourceFactCatalogProvider(factory).findFacts(RESOURCE, null))
                .singleElement().satisfies(fact -> assertThat(fact.key()).isEqualTo("singleton_product"));
        assertThat(factory.containsSingleton("product")).isFalse();
    }

    @Test
    void unrelatedSingletonFactoryOwnerDoesNotBreakFactDiscovery() {
        var factory = new DefaultListableBeanFactory();
        factory.registerSingleton("owner", new UnrelatedSingletonFactoryOwner());
        var product = new RootBeanDefinition();
        product.setFactoryBeanName("owner");
        product.setFactoryMethodName("build");
        product.setTargetType(String.class);
        factory.registerBeanDefinition("unrelatedProduct", product);
        factory.registerBeanDefinition("facts", new RootBeanDefinition(FactsService.class));

        assertThat(factory.containsBeanDefinition("owner")).isFalse();
        assertThat(new AnnotationResourceFactCatalogProvider(factory).findFacts(RESOURCE, null))
                .singleElement().satisfies(fact -> assertThat(fact.key()).isEqualTo("tenure"));
        assertThat(factory.containsSingleton("unrelatedProduct")).isFalse();
        assertThat(factory.containsSingleton("facts")).isFalse();
    }

    static class SingletonFactoryOwner {
        SingletonFactoryProduct build() { throw new AssertionError("Catalog must not invoke singleton factory owners"); }
    }

    @ResourceFact(key = "singleton_product", resourceKey = RESOURCE)
    static class SingletonFactoryProduct {}

    static class UnrelatedSingletonFactoryOwner {
        String build() { throw new AssertionError("Catalog must not invoke unrelated singleton factory owners"); }
    }

    @Test
    void rejectsAmbiguousUnresolvedFactoryOverloadsWithoutPublishingTheirDeclarations() {
        var factory = new DefaultListableBeanFactory();
        factory.registerBeanDefinition("factory", new RootBeanDefinition(OverloadedFactory.class));
        var product = new RootBeanDefinition();
        product.setFactoryBeanName("factory");
        product.setFactoryMethodName("create");
        product.setLazyInit(true);
        factory.registerBeanDefinition("product", product);

        assertThatThrownBy(() -> new AnnotationResourceFactCatalogProvider(factory).findFacts(RESOURCE, null))
                .isInstanceOf(IllegalStateException.class).hasMessage("Ambiguous fact factory method: create");
        assertThat(factory.containsSingleton("factory")).isFalse();
        assertThat(factory.containsSingleton("product")).isFalse();
    }

    static class OverloadedFactory {
        OverloadedFactory() { throw new AssertionError("Catalog must not instantiate the lazy factory owner"); }
        @ResourceFact(key = "selected", resourceKey = RESOURCE)
        FactsApi create() { throw new AssertionError("Catalog must not invoke a selected factory"); }
        @ResourceFact(key = "unselected", resourceKey = RESOURCE)
        FactsApi create(String context) { throw new AssertionError("Catalog must not invoke any factory overload"); }
    }

    interface FactsApi { int tenure(); }

    static class FactsService implements FactsApi {
        @Override
        @ResourceFact(key = "tenure", resourceKey = RESOURCE)
        @DomainGovernance(kind = DomainGovernanceKind.PRIVACY, classification = DomainClassification.RESTRICTED,
                dataCategory = DomainDataCategory.PERSONAL, confidence = 0.4d,
                reason = "Historical evidence requires review")
        public int tenure() { throw new AssertionError("Catalog must not evaluate a fact"); }
    }

    static class LazyFacts {
        LazyFacts() { throw new AssertionError("Catalog must not instantiate lazy domain beans"); }
        @ResourceFact(key = "lazy_tenure", resourceKey = RESOURCE)
        public int tenure() { throw new AssertionError("Catalog must not evaluate a fact"); }
    }

    @Configuration(proxyBeanMethods = false)
    static class ProxiedFactsConfiguration {
        @Bean static DefaultAdvisorAutoProxyCreator autoProxyCreator() {
            return new DefaultAdvisorAutoProxyCreator();
        }
        @Bean DefaultPointcutAdvisor factAdvisor() {
            var pointcut = new NameMatchMethodPointcut();
            pointcut.setMappedName("tenure");
            return new DefaultPointcutAdvisor(pointcut,
                    (org.aopalliance.intercept.MethodInterceptor) invocation -> {
                        throw new AssertionError("Catalog must not invoke proxied facts");
                    });
        }
        @Bean FactsApi facts() { return new FactsService(); }
        @Bean @Lazy LazyFacts lazyFacts() { throw new AssertionError("Catalog must not invoke lazy factory methods"); }
    }

    @Configuration(proxyBeanMethods = false)
    static class LazyFactoryConfiguration {
        @Bean @Lazy
        @ResourceFact(key = "factory_fact", resourceKey = RESOURCE)
        FactsApi declaredFact() { throw new AssertionError("Catalog must not execute factory methods"); }
        @Bean @Lazy LazyFacts concreteFacts() { throw new AssertionError("Catalog must not execute factory methods"); }
    }
}
