package org.praxisplatform.uischema.service.base;

import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.capability.*;
import org.praxisplatform.uischema.configuration.OpenApiUiSchemaAutoConfiguration;
import org.praxisplatform.uischema.governance.ResourceGovernanceInterceptor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ResourceServiceStateSnapshotProviderTest {
    @Test void itemReadRefreshesAnExistingSnapshotBeforeProjectingTheSameEntityForItsSurface() {
        var repository = mock(CoreResourceEvolutionIntegrationTest.ItemRepository.class);
        var item = new CoreResourceEvolutionIntegrationTest.Item(1, 1, "Allowed");
        item.revision = 7L;
        when(repository.findById(1L)).thenReturn(Optional.of(item));
        var service = new CoreResourceEvolutionIntegrationTest.ItemService(repository);
        service.setResourceGovernanceInterceptors(java.util.List.of(new ResourceGovernanceInterceptor() {
            @Override
            public <E, R> R decorateResponse(BaseResourceQueryService<?, ?, ?> owner, E entity, R dto, String surface) {
                assertSame(item, entity);
                assertEquals("hero-profile", surface);
                assertEquals("OPEN", ResourceStateSnapshotScope.findSeededServiceSnapshot(owner, 1L)
                        .orElseThrow().orElseThrow().state());
                return dto;
            }
        }));

        try (var ignored = ResourceStateSnapshotScope.open()) {
            ResourceStateSnapshotScope.seedServiceSnapshot(service, 1L, Optional.of(ResourceStateSnapshot.of("STALE")));
            ResourceStateSnapshotScope.seedReusableResourceSnapshot("items", 1L, Optional.of(ResourceStateSnapshot.of("STALE")));
            var result = service.findByIdForSurface(1L, "hero-profile");
            assertEquals("Allowed", result.body().name);
            assertTrue(result.body().enriched);
            assertEquals(7L, result.requirePersistedVersion());
            assertEquals("OPEN", ResourceStateSnapshotScope.findReusableResourceSnapshot("items", 1L)
                    .orElseThrow().orElseThrow().state());
            verify(repository).findById(1L);
        }
    }

    @Test void itemAccessPreservesTenantChecksAndReusesSnapshotsOnlyWithinTheScope() {
        var repository = mock(CoreResourceEvolutionIntegrationTest.ItemRepository.class);
        when(repository.findById(1L)).thenReturn(Optional.of(new CoreResourceEvolutionIntegrationTest.Item(1, 1, "Allowed")));
        when(repository.findById(2L)).thenReturn(Optional.of(new CoreResourceEvolutionIntegrationTest.Item(2, 2, "Denied")));
        var service = new CoreResourceEvolutionIntegrationTest.ItemService(repository);
        try (var ignored = ResourceStateSnapshotScope.open()) {
            service.assertItemAccessible(1L);
            assertEquals("OPEN", service.findResourceStateSnapshot(1L).orElseThrow().state());
            assertEquals("OPEN", service.findResourceStateSnapshot(1L).orElseThrow().state());
            assertTrue(service.batches.isEmpty(), "Discovery must not materialize response enrichment");
            var denied = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                    () -> service.assertItemAccessible(2L));
            assertEquals(403, denied.getStatusCode().value());
            assertThrows(jakarta.persistence.EntityNotFoundException.class, () -> service.assertItemAccessible(99L));
            verify(repository).findById(1L);
        }
        service.findResourceStateSnapshot(1L).orElseThrow();
        verify(repository, times(2)).findById(1L);
    }

    @Test void itemAccessDoesNotBypassHostFindByIdOverridesIncludingInterfaceProxies() {
        var repository = mock(CoreResourceEvolutionIntegrationTest.ItemRepository.class);
        var service = new CoreResourceEvolutionIntegrationTest.ItemService(repository) {
            @Override public org.praxisplatform.uischema.concurrency.ResourceRepresentationResult<CoreResourceEvolutionIntegrationTest.Response> findById(Long id) {
                throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN);
            }
        };
        var proxyFactory = new org.springframework.aop.framework.ProxyFactory(service);
        proxyFactory.setInterfaces(BaseResourceQueryService.class);
        @SuppressWarnings("unchecked")
        BaseResourceQueryService<CoreResourceEvolutionIntegrationTest.Response, Long, CoreResourceEvolutionIntegrationTest.Filter> proxy =
                (BaseResourceQueryService<CoreResourceEvolutionIntegrationTest.Response, Long, CoreResourceEvolutionIntegrationTest.Filter>) proxyFactory.getProxy();
        for (var target : java.util.List.of(service, proxy)) {
            var denied = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                    () -> target.assertItemAccessible(1L));
            assertEquals(403, denied.getStatusCode().value());
        }
        verifyNoInteractions(repository);
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OpenApiUiSchemaAutoConfiguration.class))
            .withBean(jakarta.persistence.EntityManagerFactory.class, () -> mock(jakarta.persistence.EntityManagerFactory.class));

    @Test void defaultBeanLazilyDelegatesByResourceKeyConvertsIdAndPreservesAuthorization() {
        var repository = mock(CoreResourceEvolutionIntegrationTest.ItemRepository.class);
        when(repository.findById(1L)).thenReturn(Optional.of(new CoreResourceEvolutionIntegrationTest.Item(1, 1, "Allowed")));
        when(repository.findById(2L)).thenReturn(Optional.of(new CoreResourceEvolutionIntegrationTest.Item(2, 2, "Denied")));
        runner.withBean("itemService", CoreResourceEvolutionIntegrationTest.ItemService.class,
                        () -> new CoreResourceEvolutionIntegrationTest.ItemService(repository))
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    var provider = context.getBean(ResourceStateSnapshotProvider.class);
                    assertInstanceOf(ResourceServiceDelegatingStateSnapshotProvider.class, provider);
                    assertEquals("OPEN", provider.resolve("items", "1").orElseThrow().state());
                    assertTrue(provider.resolve("unknown", 1).isEmpty());
                    assertTrue(provider.resolve("items", "2").isEmpty());
                    assertTrue(provider.resolve("items", "99").isEmpty());
                    var denied = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                            () -> context.getBean(CoreResourceEvolutionIntegrationTest.ItemService.class).assertItemAccessible(2L));
                    assertEquals(403, denied.getStatusCode().value());
                    try (var ignored = ResourceStateSnapshotScope.open()) {
                        ResourceStateSnapshotScope.resolve(provider, "items", 1L);
                        ResourceStateSnapshotScope.resolve(provider, "items", 1L);
                    }
                    verify(repository, times(2)).findById(1L);
                });
    }

    @Test void hostOverrideRemainsTheSingleProviderAndEmptyContextNeedsNoService() {
        ResourceStateSnapshotProvider custom = (key, id) -> Optional.of(ResourceStateSnapshot.of("CUSTOM"));
        runner.withBean(ResourceStateSnapshotProvider.class, () -> custom).run(context -> {
            assertEquals(1, context.getBeansOfType(ResourceStateSnapshotProvider.class).size());
            assertSame(custom, context.getBean(ResourceStateSnapshotProvider.class));
        });
        runner.run(context -> assertTrue(context.getBean(ResourceStateSnapshotProvider.class).resolve("absent", 1).isEmpty()));
    }

    @Test void interfaceBasedSpringProxyKeepsTheCanonicalServiceBridge() {
        var repository = mock(CoreResourceEvolutionIntegrationTest.ItemRepository.class);
        when(repository.findById(1L)).thenReturn(Optional.of(new CoreResourceEvolutionIntegrationTest.Item(1, 1, "Allowed")));
        var proxyFactory = new org.springframework.aop.framework.ProxyFactory(new CoreResourceEvolutionIntegrationTest.ItemService(repository));
        proxyFactory.setInterfaces(BaseResourceQueryService.class);
        BaseResourceQueryService<?, ?, ?> proxy = (BaseResourceQueryService<?, ?, ?>) proxyFactory.getProxy();
        runner.withBean("proxiedService", BaseResourceQueryService.class, () -> proxy).run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals("OPEN", context.getBean(ResourceStateSnapshotProvider.class).resolve("items", "1").orElseThrow().state());
        });
    }

    @Test void duplicateServiceKeysFailExplicitly() {
        var repository = mock(CoreResourceEvolutionIntegrationTest.ItemRepository.class);
        runner.withBean("one", CoreResourceEvolutionIntegrationTest.ItemService.class,
                        () -> new CoreResourceEvolutionIntegrationTest.ItemService(repository))
                .withBean("two", CoreResourceEvolutionIntegrationTest.ItemService.class,
                        () -> new CoreResourceEvolutionIntegrationTest.ItemService(repository))
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertThrows(IllegalStateException.class, () -> context.getBean(ResourceStateSnapshotProvider.class).resolve("items", 1L));
                });
    }
}
