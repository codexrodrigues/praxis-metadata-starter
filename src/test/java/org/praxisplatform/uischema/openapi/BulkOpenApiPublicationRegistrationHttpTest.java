package org.praxisplatform.uischema.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.configuration.BulkOperationLifecycleAutoConfiguration;
import org.praxisplatform.uischema.controller.docs.OpenApiDocsSupport;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.DelegatingFilterProxyRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.boot.web.server.ErrorPage;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.server.ConfigurableServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Real Boot/Tomcat registration, Spring Security chain, HTTP and lease consumption.
 * HTTP Basic identities/policy are fixture IAM, not host JWT propagation or PostgreSQL authority.
 * The fixture delegates to the official registration factory without composing a bulk lifecycle.
 */
class BulkOpenApiPublicationRegistrationHttpTest {
    private static final String BASE = "/v3/api-docs";
    private static final String CONTEXT = "/bulk-registration-fixture";
    private static final String CREDENTIAL = "Basic " + java.util.Base64.getEncoder().encodeToString(
            "registration-fixture:fixture-password".getBytes(StandardCharsets.UTF_8));
    private static final byte[] JSON = " {\"openapi\":\"3.1.0\",\"info\":{\"title\":\"São Paulo\",\"version\":\"1\"},\"paths\":{}}\n"
            .getBytes(StandardCharsets.UTF_8);
    private static final byte[] CONFIG = ("{\"urls\":[{\"name\":\"operations\",\"url\":\"" + CONTEXT + BASE + "/operations\"}]}")
            .getBytes(StandardCharsets.UTF_8);

    @Test
    void realSpringSecurityPrecedesConsumptionAndRegistersEveryDispatchWithDefaultOrder() throws Exception {
        try (var fixture = new Fixture(Map.of())) {
            assertThat(fixture.security.getFilter().getOrder()).isEqualTo(SecurityProperties.DEFAULT_FILTER_ORDER);
            fixture.assertRegistration();
            fixture.assertIamBeforeConsumption();
            for (String path : List.of(BASE + "/operations", BASE + "/swagger-config")) {
                try (var lease = fixture.access.issue(CONTEXT, path, TimeUnit.SECONDS.toNanos(5))) {
                    var response = fixture.get(path, lease.token(), true);
                    assertThat(response.statusCode()).isEqualTo(200);
                    assertThat(response.body()).isEqualTo(path.endsWith("swagger-config") ? CONFIG : JSON);
                    lease.requireConsumed();
                }
            }
            assertThat(fixture.calls.producer).hasValue(3);
        }
    }

    @Test
    void effectiveBootProxyOrderOverridesThePropertiesOrderWithoutConsumingBeforeSecurity() throws Exception {
        try (var fixture = new Fixture(Map.of("spring.security.filter.order", "47", "fixture.security.proxy-order", "113"))) {
            assertThat(fixture.security.getFilter().getOrder()).isEqualTo(47);
            assertThat(fixture.securityRegistration.getOrder()).isEqualTo(113);
            fixture.assertRegistration();
            fixture.assertIamBeforeConsumption();
        }
    }

    @Test
    void realForwardAsyncAndErrorDispatchesCannotReachAnUnpublishedProducer() throws Exception {
        try (var fixture = new Fixture(Map.of())) {
            for (String route : List.of("/fixture/forward", "/fixture/async", "/fixture/error")) {
                try (var lease = fixture.access.issue(CONTEXT, BASE, TimeUnit.SECONDS.toNanos(5))) {
                    assertUnavailable(fixture.get(route, lease.token(), true));
                    assertThatThrownBy(lease::requireConsumed).isInstanceOf(IllegalStateException.class);
                }
            }
            assertThat(fixture.calls.producer).hasValue(0);
        }
    }

    @Test
    void configuredSecurityOrderIsHonoredAndColdInvalidNonceAndYamlNeverReachTheProducer() throws Exception {
        try (var fixture = new Fixture(Map.of("spring.security.filter.order", "47"))) {
            assertThat(fixture.security.getFilter().getOrder()).isEqualTo(47);
            fixture.assertRegistration();
            fixture.assertIamBeforeConsumption();
            int producerCalls = fixture.calls.producer.get();
            for (String path : List.of(BASE, BASE + "/operations", BASE + "/swagger-config",
                    BASE + ".yaml", BASE + ".yaml/operations", BASE + ".yml")) {
                assertUnavailable(fixture.get(path, null, true));
            }
            assertUnavailable(fixture.get(BASE, "invalid", true));
            assertUnavailable(fixture.send(BASE, null, true, "HEAD"));
            assertUnavailable(fixture.get(BASE + "?group=operations", null, true));
            assertThat(fixture.calls.producer).hasValue(producerCalls);
            assertThat(fixture.calls.iamDenied).hasValue(1);
        }
    }

    @Test
    void registrationFactoryDefaultsWithoutPropertiesAndFailsClosedForCustomServiceOrOrderOverflow() {
        var configuration = new BulkOperationLifecycleAutoConfiguration();
        var beans = new DefaultListableBeanFactory();
        var documents = mock(CachedOpenApiDocumentService.class);
        when(documents.bulkOpenApiBasePath()).thenReturn(BASE);
        var registration = configuration.governedOpenApiPublicationFilterRegistration(documents,
                beans.getBeanProvider(SecurityProperties.class), beans.getBeanProvider(DelegatingFilterProxyRegistrationBean.class), beans);
        assertThat(registration.getOrder()).isEqualTo(SecurityProperties.DEFAULT_FILTER_ORDER + 1);
        assertThat(registration.getUrlPatterns()).containsExactly("/*");
        assertThat(registration.isAsyncSupported()).isTrue();
        assertThatThrownBy(() -> configuration.governedOpenApiPublicationFilterRegistration(
                mock(OpenApiDocumentService.class), beans.getBeanProvider(SecurityProperties.class),
                beans.getBeanProvider(DelegatingFilterProxyRegistrationBean.class), beans))
                .isInstanceOf(IllegalStateException.class);
        var security = new SecurityProperties(); security.getFilter().setOrder(Integer.MAX_VALUE);
        beans.registerSingleton("configuredSecurity", security);
        assertThatThrownBy(() -> configuration.governedOpenApiPublicationFilterRegistration(documents,
                beans.getBeanProvider(SecurityProperties.class), beans.getBeanProvider(DelegatingFilterProxyRegistrationBean.class), beans))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void missingDisabledOrPartiallyMappedCanonicalSecurityRegistrationFailsClosed() {
        var factory = new BulkOperationLifecycleAutoConfiguration(); var beans = new DefaultListableBeanFactory();
        var documents = mock(CachedOpenApiDocumentService.class); when(documents.bulkOpenApiBasePath()).thenReturn(BASE);
        beans.registerSingleton("springSecurityFilterChain", mock(Filter.class));
        assertThatThrownBy(() -> factory.governedOpenApiPublicationFilterRegistration(documents,
                beans.getBeanProvider(SecurityProperties.class), beans.getBeanProvider(DelegatingFilterProxyRegistrationBean.class), beans))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("topology");
        var proxy = mock(DelegatingFilterProxyRegistrationBean.class);
        when(proxy.getFilterName()).thenReturn("springSecurityFilterChain");
        when(proxy.getUrlPatterns()).thenReturn(List.of("/*")); when(proxy.getServletNames()).thenReturn(List.of());
        when(proxy.getServletRegistrationBeans()).thenReturn(List.of());
        when(proxy.determineDispatcherTypes()).thenReturn(EnumSet.allOf(DispatcherType.class));
        beans.registerSingleton("securityFilterChainRegistration", proxy);
        assertThatThrownBy(() -> factory.governedOpenApiPublicationFilterRegistration(documents,
                beans.getBeanProvider(SecurityProperties.class), beans.getBeanProvider(DelegatingFilterProxyRegistrationBean.class), beans))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("coverage");
        when(proxy.isEnabled()).thenReturn(true); when(proxy.getUrlPatterns()).thenReturn(List.of("/unrelated/*"));
        assertThatThrownBy(() -> factory.governedOpenApiPublicationFilterRegistration(documents,
                beans.getBeanProvider(SecurityProperties.class), beans.getBeanProvider(DelegatingFilterProxyRegistrationBean.class), beans))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("coverage");
        when(proxy.getUrlPatterns()).thenReturn(List.of("/*"));
        when(proxy.determineDispatcherTypes()).thenReturn(EnumSet.of(DispatcherType.ERROR));
        assertThatThrownBy(() -> factory.governedOpenApiPublicationFilterRegistration(documents,
                beans.getBeanProvider(SecurityProperties.class), beans.getBeanProvider(DelegatingFilterProxyRegistrationBean.class), beans))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("coverage");
        when(proxy.determineDispatcherTypes()).thenReturn(EnumSet.allOf(DispatcherType.class));
        when(proxy.getFilterName()).thenReturn("otherProxy");
        assertThatThrownBy(() -> factory.governedOpenApiPublicationFilterRegistration(documents,
                beans.getBeanProvider(SecurityProperties.class), beans.getBeanProvider(DelegatingFilterProxyRegistrationBean.class), beans))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("coverage");
        when(proxy.getFilterName()).thenReturn("springSecurityFilterChain"); when(proxy.getOrder()).thenReturn(Integer.MAX_VALUE);
        assertThatThrownBy(() -> factory.governedOpenApiPublicationFilterRegistration(documents,
                beans.getBeanProvider(SecurityProperties.class), beans.getBeanProvider(DelegatingFilterProxyRegistrationBean.class), beans))
                .isInstanceOf(ArithmeticException.class);
        beans.destroySingleton("springSecurityFilterChain");
        assertThatThrownBy(() -> factory.governedOpenApiPublicationFilterRegistration(documents,
                beans.getBeanProvider(SecurityProperties.class), beans.getBeanProvider(DelegatingFilterProxyRegistrationBean.class), beans))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("topology");
    }

    private static void assertUnavailable(HttpResponse<byte[]> response) {
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).isEmpty();
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
    }

    private static final class Fixture implements AutoCloseable {
        final ServletWebServerApplicationContext context;
        final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
        final Calls calls;
        final SecurityProperties security;
        final DelegatingFilterProxyRegistrationBean securityRegistration;
        final OpenApiProducerCaptureAccess access;
        final FilterRegistrationBean<?> registration;

        Fixture(Map<String, Object> properties) {
            var application = new SpringApplication(FixtureApplication.class);
            application.setWebApplicationType(WebApplicationType.SERVLET);
            application.setRegisterShutdownHook(false); application.setLogStartupInfo(false);
            var defaults = new java.util.HashMap<String, Object>(properties);
            defaults.put("server.port", "0"); defaults.put("server.servlet.context-path", CONTEXT);
            defaults.put("springdoc.api-docs.path", BASE);
            defaults.put("spring.main.banner-mode", "off");
            application.setDefaultProperties(defaults);
            context = (ServletWebServerApplicationContext) application.run();
            calls = context.getBean(Calls.class); security = context.getBean(SecurityProperties.class);
            securityRegistration = context.getBean("securityFilterChainRegistration", DelegatingFilterProxyRegistrationBean.class);
            registration = context.getBean("publicationRegistration", FilterRegistrationBean.class);
            // Test-only access to the existing owner, no second nonce registry or production accessor.
            access = (OpenApiProducerCaptureAccess) ReflectionTestUtils.getField(
                    context.getBean(CachedOpenApiDocumentService.class), "producerCaptureAccess");
            assertThat(access).isNotNull();
        }

        void assertRegistration() {
            assertThat(context.getBean(CachedOpenApiDocumentService.class).bulkOpenApiBasePath()).isEqualTo(BASE);
            assertThat(context.getWebServer().getClass().getName()).contains("Tomcat");
            assertThat(registration.getFilter()).isInstanceOf(GovernedOpenApiPublicationFilter.class);
            assertThat(context.getBean("springSecurityFilterChain")).isInstanceOf(FilterChainProxy.class);
            assertThat(((FilterChainProxy) context.getBean("springSecurityFilterChain")).getFilterChains()).hasSize(1);
            assertThat(registration.getOrder()).isEqualTo(Math.addExact(securityRegistration.getOrder(), 1));
            assertThat(registration.getUrlPatterns()).containsExactly("/*");
            assertThat(registration.isAsyncSupported()).isTrue();
            assertThat(registration.determineDispatcherTypes()).containsExactlyInAnyOrderElementsOf(EnumSet.allOf(DispatcherType.class));
            var actual = context.getServletContext().getFilterRegistration("praxisGovernedOpenApiPublicationFilter");
            assertThat(actual).isNotNull();
            assertThat(actual.getClassName()).isEqualTo(GovernedOpenApiPublicationFilter.class.getName());
            assertThat(actual.getUrlPatternMappings()).containsExactly("/*");
            assertThat(actual.getServletNameMappings()).isEmpty();
            assertThat(ReflectionTestUtils.getField(context.getBean(CachedOpenApiDocumentService.class),
                    "bulkOpenApiServingContext")).isEqualTo(CONTEXT);
        }

        void assertIamBeforeConsumption() throws Exception {
            try (var lease = access.issue(CONTEXT, BASE, TimeUnit.SECONDS.toNanos(5))) {
                assertThat(get(BASE, lease.token(), false).statusCode()).isEqualTo(401);
                assertThatThrownBy(lease::requireConsumed).isInstanceOf(IllegalStateException.class);
                assertThat(calls.producer).hasValue(0);
                var allowed = get(BASE, lease.token(), true);
                assertThat(allowed.statusCode()).isEqualTo(200); assertThat(allowed.body()).isEqualTo(JSON);
                lease.requireConsumed();
                assertThat(calls.producer).hasValue(1);
                assertUnavailable(get(BASE, lease.token(), true));
                lease.requireConsumed();
                assertThat(calls.producer).hasValue(1);
            }
        }

        HttpResponse<byte[]> get(String path, String token, boolean authorized) throws Exception {
            return send(path, token, authorized, "GET");
        }
        HttpResponse<byte[]> send(String path, String token, boolean authorized, String method) throws Exception {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + context.getWebServer().getPort() + CONTEXT + path))
                    .timeout(Duration.ofSeconds(3));
            if (authorized) request.header("Authorization", CREDENTIAL);
            if (token != null) request.header(OpenApiProducerCaptureAccess.HEADER, token);
            return http.send(request.method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofByteArray());
        }
        @Override public void close() {
            try { context.close(); } finally { http.shutdownNow(); }
        }
    }

    static final class Calls {
        final AtomicInteger producer = new AtomicInteger();
        final AtomicInteger iamDenied = new AtomicInteger();
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({PropertyPlaceholderAutoConfiguration.class, ServletWebServerFactoryAutoConfiguration.class,
            SecurityAutoConfiguration.class, SecurityFilterAutoConfiguration.class})
    @EnableConfigurationProperties(SecurityProperties.class)
    static class FixtureApplication {
        @Bean Calls calls() { return new Calls(); }
        @Bean(destroyMethod = "close") OpenApiInternalRestTemplate internalClient() {
            return new OpenApiInternalRestTemplate(Duration.ofSeconds(1), Duration.ofSeconds(2));
        }
        @Bean CachedOpenApiDocumentService documents(OpenApiInternalRestTemplate client) {
            return new CachedOpenApiDocumentService(client, new ObjectMapper(), new OpenApiDocsSupport(), true, Duration.ofSeconds(3));
        }
        @Bean FilterRegistrationBean<GovernedOpenApiPublicationFilter> publicationRegistration(
                CachedOpenApiDocumentService documents, ObjectProvider<SecurityProperties> security,
                @Qualifier("securityFilterChainRegistration") ObjectProvider<DelegatingFilterProxyRegistrationBean> registrations,
                BeanFactory beans) {
            return new BulkOperationLifecycleAutoConfiguration().governedOpenApiPublicationFilterRegistration(documents, security, registrations, beans);
        }
        @Bean SecurityFilterChain fixtureSecurity(HttpSecurity http, Calls calls) throws Exception {
            http.authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated());
            http.httpBasic(basic -> basic.authenticationEntryPoint((request, response, denied) -> {
                calls.iamDenied.incrementAndGet(); response.setStatus(401); response.setContentLength(0);
            }));
            http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
            http.requestCache(cache -> cache.disable()); http.csrf(csrf -> csrf.disable());
            return http.build();
        }
        @Bean UserDetailsService fixtureUsers() {
            return new InMemoryUserDetailsManager(User.withUsername("registration-fixture")
                    .password("{noop}fixture-password").roles("DOCUMENT_CAPTURE").build());
        }
        @Bean static BeanPostProcessor effectiveSecurityRegistrationOrder(Environment environment) {
            return new BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String name) {
                    if (bean instanceof DelegatingFilterProxyRegistrationBean registration && name.equals("securityFilterChainRegistration")) {
                        Integer order = environment.getProperty("fixture.security.proxy-order", Integer.class);
                        if (order != null) registration.setOrder(order);
                    }
                    return bean;
                }
            };
        }
        @Bean ServletRegistrationBean<HttpServlet> producer(Calls calls) {
            var servlet = new HttpServlet() {
                @Override protected void doGet(HttpServletRequest request, HttpServletResponse response)
                        throws IOException, jakarta.servlet.ServletException {
                    if (request.getServletPath().equals("/fixture/forward")) {
                        request.getRequestDispatcher(BASE).forward(request, response); return;
                    }
                    if (request.getServletPath().equals("/fixture/async")) {
                        request.startAsync().dispatch(BASE); return;
                    }
                    if (request.getServletPath().equals("/fixture/error")) {
                        response.sendError(500); return;
                    }
                    calls.producer.incrementAndGet(); response.setContentType("application/json");
                    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                    response.getOutputStream().write(request.getServletPath().endsWith("/swagger-config") ? CONFIG : JSON);
                }
            };
            var registration = new ServletRegistrationBean<HttpServlet>(servlet, "/");
            registration.setName("unpublishedProducerFixture"); registration.setLoadOnStartup(1);
            registration.setAsyncSupported(true);
            return registration;
        }
        @Bean WebServerFactoryCustomizer<ConfigurableServletWebServerFactory> errorDispatchToOpenApi() {
            return factory -> factory.addErrorPages(new ErrorPage(HttpStatus.INTERNAL_SERVER_ERROR, BASE));
        }
    }
}
