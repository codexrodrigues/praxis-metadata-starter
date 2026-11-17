package org.praxisplatform.uischema.multitenancy;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.command.JdbcResourceCommandReceiptStore;
import org.praxisplatform.uischema.command.ResourceCommandIdempotencyStore;
import org.praxisplatform.uischema.configuration.DynamicSwaggerConfig;
import org.praxisplatform.uischema.configuration.OpenApiUiSchemaAutoConfiguration;
import org.praxisplatform.uischema.configuration.PraxisMetadataAutoConfiguration;
import org.praxisplatform.uischema.customfields.ResourceTenantContextResolver;
import org.praxisplatform.uischema.id.CompositeIdJacksonModule;
import org.praxisplatform.uischema.security.PraxisMetadataRequestMatchers;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.PathContainer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpRequestResponseHolder;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class MultitenancySupportAndAutoConfigurationTest {

    @Test
    void multiOrganizationAccessScopeResolverAppliesAllFourStrategiesAndFailsClosed() {
        DataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:multi_org_scope_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1",
                "sa",
                ""
        );
        LocalContainerEntityManagerFactoryBean emfBean = new LocalContainerEntityManagerFactoryBean();
        emfBean.setDataSource(dataSource);
        emfBean.setPackagesToScan(ScopedUnit.class.getPackageName());
        emfBean.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        emfBean.setJpaPropertyMap(Map.of(
                "hibernate.hbm2ddl.auto", "create-drop",
                "hibernate.archive.autodetection", "none"
        ));
        emfBean.afterPropertiesSet();
        EntityManagerFactory emf = emfBean.getObject();
        try {
            EntityManager em = SharedEntityManagerCreator.createSharedEntityManager(emf);
            JpaTransactionManager txManager = new JpaTransactionManager(emf);
            var txTemplate = new org.springframework.transaction.support.TransactionTemplate(txManager);
            ScopedUnitRepository unitRepo = new JpaRepositoryFactory(em).getRepository(ScopedUnitRepository.class);
            ScopedChildRepository childRepo = new JpaRepositoryFactory(em).getRepository(ScopedChildRepository.class);

            txTemplate.executeWithoutResult(status -> {
                unitRepo.save(new ScopedUnit(1L, 10L, List.of(10L, 30L)));
                unitRepo.save(new ScopedUnit(2L, 20L, List.of(20L)));
                unitRepo.save(new ScopedUnit(3L, null, List.of(10L, 20L)));
                childRepo.save(new ScopedChild(100L, 1L, "U1"));
                childRepo.save(new ScopedChild(200L, 2L, "U2"));
            });

            MultiOrganizationAccessScopeResolver resolver = new MultiOrganizationAccessScopeResolver();

            assertThat(unitRepo.findAll(resolver.<ScopedUnit>scalarField("orgId", List.of(10L)).specification()))
                    .extracting(u -> u.id).containsExactly(1L);
            assertThat(unitRepo.findAll(resolver.<ScopedUnit>scalarField("orgId", List.of()).specification()))
                    .isEmpty();

            assertThat(unitRepo.findAll(resolver.<ScopedUnit>nullableOrIn("orgId", List.of(10L)).specification()))
                    .extracting(u -> u.id).containsExactlyInAnyOrder(1L, 3L);
            assertThat(unitRepo.findAll(resolver.<ScopedUnit>nullableOrIn("orgId", List.of(), true).specification()))
                    .extracting(u -> u.id).containsExactly(3L);
            assertThat(unitRepo.findAll(resolver.<ScopedUnit>nullableOrIn("orgId", List.of(), false).specification()))
                    .isEmpty();

            assertThat(unitRepo.findAll(resolver.<ScopedUnit>elementCollection("allowedOrgs", List.of(30L)).specification()))
                    .extracting(u -> u.id).containsExactly(1L);
            assertThat(unitRepo.findAll(resolver.<ScopedUnit>elementCollection("allowedOrgs", List.of()).specification()))
                    .isEmpty();

            assertThat(childRepo.findAll(resolver.<ScopedChild, ScopedUnit>parentSubquery(
                            "unitId", ScopedUnit.class, "id", "orgId", List.of(10L))
                    .specification()))
                    .extracting(c -> c.id).containsExactly(100L);
            assertThat(childRepo.findAll(resolver.<ScopedChild, ScopedUnit>parentSubquery(
                            "unitId", ScopedUnit.class, "id", "orgId", List.of())
                    .specification()))
                    .isEmpty();
        } finally {
            emfBean.destroy();
        }
    }

    @Test
    void tenantRealmContextRoutingDataSourceAndValidationInterceptorWorkEndToEnd() {
        assertThatThrownBy(() -> TenantRealmContext.set("bad realm/!"))
                .isInstanceOf(IllegalArgumentException.class);

        ResourceTenantContextResolver tenantResolver = () -> Optional.of("tenant-fallback");
        TenantRealmRoutingDataSource routingDataSource = new TenantRealmRoutingDataSource(tenantResolver);
        routingDataSource.setDefaultLookupKey("default-ds");
        assertThat(routingDataSource.determineCurrentLookupKey()).isEqualTo("tenant-fallback");

        try (var scope = TenantRealmContext.open("realm-alpha")) {
            assertThat(routingDataSource.determineCurrentLookupKey()).isEqualTo("realm-alpha");
        }
        assertThat(TenantRealmContext.get()).isEmpty();

        TenantRequestValidationInterceptor interceptor = new TenantRequestValidationInterceptor(tenantResolver);
        MockHttpServletRequest validReq = new MockHttpServletRequest();
        validReq.addHeader("X-Tenant-Realm", "realm-beta");
        MockHttpServletResponse res = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(validReq, res, new Object())).isTrue();
        assertThat(TenantRealmContext.get()).contains("realm-beta");
        interceptor.afterCompletion(validReq, res, new Object(), null);
        assertThat(TenantRealmContext.get()).isEmpty();

        MockHttpServletRequest invalidReq = new MockHttpServletRequest();
        invalidReq.addHeader("X-Tenant-Realm", "invalid realm space");
        assertThatThrownBy(() -> interceptor.preHandle(invalidReq, res, new Object()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @SuppressWarnings("deprecation")
    void realmScopedSecurityAndCsrfRepositoriesIsolateSessionsAndCookiesPerRealm() {
        MockHttpSession sharedSession = new MockHttpSession();
        RealmScopedSecurityContextRepository securityRepo = new RealmScopedSecurityContextRepository();

        MockHttpServletRequest reqAlpha = new MockHttpServletRequest();
        reqAlpha.addHeader("X-Tenant-Realm", "realm-alpha");
        reqAlpha.setSession(sharedSession);
        MockHttpServletResponse respAlpha = new MockHttpServletResponse();

        SecurityContext alphaCtx = new SecurityContextImpl(new TestingAuthenticationToken("alice", "pw"));
        securityRepo.saveContext(alphaCtx, reqAlpha, respAlpha);

        MockHttpServletRequest reqBeta = new MockHttpServletRequest();
        reqBeta.addHeader("X-Tenant-Realm", "realm-beta");
        reqBeta.setSession(sharedSession);
        MockHttpServletResponse respBeta = new MockHttpServletResponse();

        assertThat(securityRepo.containsContext(reqAlpha)).isTrue();
        assertThat(securityRepo.containsContext(reqBeta)).isFalse();
        assertThat(securityRepo.loadContext(new HttpRequestResponseHolder(reqBeta, respBeta)).getAuthentication()).isNull();
        assertThat(securityRepo.loadContext(new HttpRequestResponseHolder(reqAlpha, respAlpha)).getAuthentication().getName())
                .isEqualTo("alice");

        RealmScopedCsrfTokenRepository csrfSessionRepo = new RealmScopedCsrfTokenRepository(false);
        var tokenAlpha = csrfSessionRepo.generateToken(reqAlpha);
        csrfSessionRepo.saveToken(tokenAlpha, reqAlpha, respAlpha);
        assertThat(csrfSessionRepo.loadToken(reqAlpha)).isNotNull();
        assertThat(csrfSessionRepo.loadToken(reqBeta)).isNull();

        RealmScopedCsrfTokenRepository csrfCookieRepo = new RealmScopedCsrfTokenRepository(true);
        csrfCookieRepo.saveToken(csrfCookieRepo.generateToken(reqAlpha), reqAlpha, respAlpha);
        assertThat(respAlpha.getCookie("XSRF-TOKEN-realm-alpha")).isNotNull();
    }

    @Test
    void requestMatchersEmitPathPatternParserSafeReadOnlyPostPatterns() {
        String[] patterns = PraxisMetadataRequestMatchers.readOnlyPostPatterns();
        PathPatternParser parser = PathPatternParser.defaultInstance;
        List<PathPattern> parsed = Arrays.stream(patterns)
                .map(parser::parse)
                .toList();

        assertThat(parsed).isNotEmpty();
        assertThat(matchesAny(parsed, "/api/employees/filter")).isTrue();
        assertThat(matchesAny(parsed, "/api/workforce/employees/filter/cursor")).isTrue();
        assertThat(matchesAny(parsed, "/api/v1/workforce/employees/stats/group-by")).isTrue();
        assertThat(matchesAny(parsed, "/api/workforce/employees/option-sources/departments/options/filter")).isTrue();
        assertThat(matchesAny(parsed, "/api/workforce/employees/option-sources/departments/options/by-ids")).isTrue();

        assertThat(matchesAny(parsed, "/api/workforce/employees")).isFalse();
        assertThat(matchesAny(parsed, "/api/workforce/employees/bulk/update")).isFalse();
        assertThat(matchesAny(parsed, "/api/workforce/employees/actions/approve")).isFalse();
    }

    @Test
    void autoConfigurationWiresJacksonCustomizerJdbcReceiptsTenantInterceptorAndDynamicSwaggerPostProcessor() {
        ApplicationContextRunner withBootJackson = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class, OpenApiUiSchemaAutoConfiguration.class));
        withBootJackson.run(ctx -> {
            assertThat(ctx.getBeansOfType(ObjectMapper.class)).hasSize(1);
            assertThat(ctx).doesNotHaveBean("openApiUiSchemaObjectMapper");
            ObjectMapper mapper = ctx.getBean(ObjectMapper.class);
            assertThat(mapper.getRegisteredModuleIds().stream().map(Object::toString))
                    .anyMatch(id -> id.contains(CompositeIdJacksonModule.class.getSimpleName())
                            || id.contains("CompositeId"));
        });

        DataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:receipts_cfg_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1",
                "sa",
                ""
        );
        WebApplicationContextRunner webRunner = new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class, OpenApiUiSchemaAutoConfiguration.class))
                .withBean(ResourceTenantContextResolver.class, () -> () -> Optional.of("tenant-a"))
                .withBean(JdbcTemplate.class, () -> new JdbcTemplate(ds))
                .withBean(DataSourceTransactionManager.class, () -> new DataSourceTransactionManager(ds));

        webRunner.run(ctx -> {
            assertThat(ctx).doesNotHaveBean(ResourceCommandIdempotencyStore.class);
            assertThat(ctx).hasSingleBean(TenantRequestValidationInterceptor.class);
            WebMvcConfigurer configurer = ctx.getBean("praxisTenantValidationWebMvcConfigurer", WebMvcConfigurer.class);
            InterceptorRegistry registry = mock(InterceptorRegistry.class);
            configurer.addInterceptors(registry);
            verify(registry).addInterceptor(ctx.getBean(TenantRequestValidationInterceptor.class));
            assertThat(ctx).doesNotHaveBean(RealmScopedSecurityContextRepository.class);
            assertThat(ctx).doesNotHaveBean(RealmScopedCsrfTokenRepository.class);
        });

        webRunner.withPropertyValues(
                        "praxis.command.receipts.jdbc.enabled=true",
                        "praxis.multitenancy.realm-scoped-security.enabled=true",
                        "praxis.multitenancy.realm-scoped-csrf.enabled=true",
                        "praxis.multitenancy.realm-scoped-csrf.cookie-based=true"
                )
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(JdbcResourceCommandReceiptStore.class);
                    assertThat(ctx).hasSingleBean(RealmScopedSecurityContextRepository.class);
                    assertThat(ctx).hasSingleBean(RealmScopedCsrfTokenRepository.class);
                });

        DynamicSwaggerConfig mockSwagger = mock(DynamicSwaggerConfig.class);
        BeanPostProcessor postProcessor = PraxisMetadataAutoConfiguration.eagerDynamicSwaggerConfigPostProcessor(
                new org.springframework.beans.factory.ObjectProvider<>() {
                    @Override public DynamicSwaggerConfig getObject(Object... args) { return mockSwagger; }
                    @Override public DynamicSwaggerConfig getIfAvailable() { return mockSwagger; }
                    @Override public DynamicSwaggerConfig getIfUnique() { return mockSwagger; }
                    @Override public DynamicSwaggerConfig getObject() { return mockSwagger; }
                }
        );
        Object target = new Object();
        assertThat(postProcessor.postProcessAfterInitialization(target, "requestMappingHandlerMapping"))
                .isSameAs(target);
    }

    private static boolean matchesAny(List<PathPattern> patterns, String path) {
        PathContainer container = PathContainer.parsePath(path);
        return patterns.stream().anyMatch(pattern -> pattern.matches(container));
    }

    @Entity
    @Table(name = "scoped_units")
    public static class ScopedUnit {
        @Id
        public Long id;
        public Long orgId;
        @ElementCollection
        public List<Long> allowedOrgs = new ArrayList<>();

        public ScopedUnit() {
        }

        public ScopedUnit(Long id, Long orgId, List<Long> allowedOrgs) {
            this.id = id;
            this.orgId = orgId;
            this.allowedOrgs = new ArrayList<>(allowedOrgs);
        }
    }

    @Entity
    @Table(name = "scoped_children")
    public static class ScopedChild {
        @Id
        public Long id;
        public Long unitId;
        public String label;

        public ScopedChild() {
        }

        public ScopedChild(Long id, Long unitId, String label) {
            this.id = id;
            this.unitId = unitId;
            this.label = label;
        }
    }

    interface ScopedUnitRepository extends JpaRepository<ScopedUnit, Long>, JpaSpecificationExecutor<ScopedUnit> {
    }

    interface ScopedChildRepository extends JpaRepository<ScopedChild, Long>, JpaSpecificationExecutor<ScopedChild> {
    }
}
