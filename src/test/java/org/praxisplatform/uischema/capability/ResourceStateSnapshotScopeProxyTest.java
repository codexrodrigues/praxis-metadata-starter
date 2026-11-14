package org.praxisplatform.uischema.capability;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.mapper.base.ResourceMapper;
import org.praxisplatform.uischema.repository.base.BaseCrudRepository;
import org.praxisplatform.uischema.service.base.AbstractBaseQueryResourceService;
import org.praxisplatform.uischema.service.base.BaseResourceQueryService;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.core.ResolvableType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResourceStateSnapshotScopeProxyTest {

    @AfterEach
    void cleanupContext() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void delegatingProviderShortCircuitsSeededNonEmptyAndEmptySnapshotsWithoutInvokingAopProxy() {
        @SuppressWarnings("unchecked")
        BaseCrudRepository<ItemEntity, Long> repo = Mockito.mock(BaseCrudRepository.class);
        when(repo.findById(41L)).thenReturn(Optional.of(new ItemEntity(41L, "ATIVO")));
        when(repo.findById(42L)).thenReturn(Optional.of(new ItemEntity(42L, "NO_SNAPSHOT")));

        AtomicInteger proxySnapshotCalls = new AtomicInteger();
        TargetItemService target = new TargetItemService(repo, true);
        ItemService proxy = wrapWithCounterProxy(target, proxySnapshotCalls);
        var snapshotProvider = snapshotProviderFor(proxy);

        try (var ignored = ResourceStateSnapshotScope.open()) {
            proxy.assertItemAccessible(41L);
            assertThat(proxy.findById(42L).body().id()).isEqualTo(42L);

            Optional<ResourceStateSnapshot> first = snapshotProvider.resolve("test.items", "41");
            Optional<ResourceStateSnapshot> second = snapshotProvider.resolve("test.items", 42L);

            assertThat(first).contains(ResourceStateSnapshot.of("ATIVO", Map.of("id", 41L)));
            assertThat(second).isEmpty();
            assertThat(proxySnapshotCalls).hasValue(0);
            verify(repo, times(1)).findById(41L);
            verify(repo, times(1)).findById(42L);
        }

        // Outside scope, unseeded calls still delegate through the service proxy normally.
        assertThat(snapshotProvider.resolve("test.items", 41L))
                .contains(ResourceStateSnapshot.of("ATIVO", Map.of("id", 41L)));
        assertThat(proxySnapshotCalls).hasValue(1);
    }

    @Test
    void subclassOverridingFindResourceStateSnapshotWithoutOptInIsNotBypassed() {
        @SuppressWarnings("unchecked")
        BaseCrudRepository<ItemEntity, Long> repo = Mockito.mock(BaseCrudRepository.class);
        when(repo.findById(51L)).thenReturn(Optional.of(new ItemEntity(51L, "ENTITY_STATE")));

        for (boolean proxied : new boolean[] {false, true}) {
            AtomicInteger customOverrideCalls = new AtomicInteger();
            CustomOverrideItemService target = new CustomOverrideItemService(repo, customOverrideCalls);
            BaseResourceQueryService<ItemDto, Long, ItemFilterDto> bean = proxied
                    ? wrapWithCounterProxy(target, new AtomicInteger())
                    : target;
            var snapshotProvider = snapshotProviderFor(bean);

            try (var ignored = ResourceStateSnapshotScope.open()) {
                bean.assertItemAccessible(51L);

                Optional<ResourceStateSnapshot> resolved = snapshotProvider.resolve("test.override", "51");
                assertThat(resolved).contains(ResourceStateSnapshot.of("CUSTOM_OVERRIDE", Map.of("id", 51L)));
                assertThat(customOverrideCalls).hasValue(1);
            }
        }
    }

    @Test
    void contextMutationMidScopeInvalidatesSeededAndCachedSnapshots() {
        @SuppressWarnings("unchecked")
        BaseCrudRepository<ItemEntity, Long> repo = Mockito.mock(BaseCrudRepository.class);
        when(repo.findById(61L)).thenReturn(Optional.of(new ItemEntity(61L, "FIRST")));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Tenant", "tenant-1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        "user-a", "unused", AuthorityUtils.createAuthorityList("ROLE_READER")));

        AtomicInteger proxySnapshotCalls = new AtomicInteger();
        StandardItemService target = new StandardItemService(repo);
        ItemService proxy = wrapWithCounterProxy(target, proxySnapshotCalls);
        var snapshotProvider = snapshotProviderFor(proxy);

        try (var ignored = ResourceStateSnapshotScope.open()) {
            proxy.assertItemAccessible(61L);
            assertThat(snapshotProvider.resolve("test.standard", 61L))
                    .contains(ResourceStateSnapshot.of("FIRST", Map.of("id", 61L)));
            assertThat(proxySnapshotCalls).hasValue(0);

            // Authority elevation or reduction mid-scope invalidates the prior ContextStamp.
            SecurityContextHolder.getContext().setAuthentication(
                    UsernamePasswordAuthenticationToken.authenticated(
                            "user-a", "unused", AuthorityUtils.createAuthorityList("ROLE_READER", "ROLE_ADMIN")));
            when(repo.findById(61L)).thenReturn(Optional.of(new ItemEntity(61L, "SECOND")));
            assertThat(snapshotProvider.resolve("test.standard", 61L))
                    .contains(ResourceStateSnapshot.of("SECOND", Map.of("id", 61L)));
            assertThat(proxySnapshotCalls).hasValue(1);

            // Tenant switch within the same thread/scope invalidates the prior ContextStamp too.
            request.removeHeader("X-Tenant");
            request.addHeader("X-Tenant", "tenant-2");
            when(repo.findById(61L)).thenReturn(Optional.of(new ItemEntity(61L, "THIRD")));
            assertThat(snapshotProvider.resolve("test.standard", 61L))
                    .contains(ResourceStateSnapshot.of("THIRD", Map.of("id", 61L)));
            assertThat(proxySnapshotCalls).hasValue(2);
        }
    }

    @Test
    void cglibSubclassProxyReusesSeededSnapshotWithoutDoubleFetch() {
        @SuppressWarnings("unchecked")
        BaseCrudRepository<ItemEntity, Long> repo = Mockito.mock(BaseCrudRepository.class);
        when(repo.findById(71L)).thenReturn(Optional.of(new ItemEntity(71L, "ATIVO")));

        AtomicInteger proxySnapshotCalls = new AtomicInteger();
        StandardItemService target = new StandardItemService(repo);
        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addAdvice((MethodInterceptor) invocation -> {
            if ("findResourceStateSnapshot".equals(invocation.getMethod().getName())) {
                proxySnapshotCalls.incrementAndGet();
            }
            return invocation.proceed();
        });
        BaseResourceQueryService<ItemDto, Long, ItemFilterDto> cglibProxy =
                (BaseResourceQueryService<ItemDto, Long, ItemFilterDto>) proxyFactory.getProxy();
        var snapshotProvider = snapshotProviderFor(cglibProxy);

        try (var ignored = ResourceStateSnapshotScope.open()) {
            cglibProxy.assertItemAccessible(71L);
            assertThat(snapshotProvider.resolve("test.standard", 71L))
                    .contains(ResourceStateSnapshot.of("ATIVO", Map.of("id", 71L)));
            assertThat(proxySnapshotCalls).hasValue(0);
            verify(repo, times(1)).findById(71L);
        }
    }

    private static ItemService wrapWithCounterProxy(Object target, AtomicInteger proxySnapshotCalls) {
        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.setProxyTargetClass(false);
        proxyFactory.setInterfaces(ItemService.class);
        proxyFactory.addAdvice((MethodInterceptor) invocation -> {
            if ("findResourceStateSnapshot".equals(invocation.getMethod().getName())) {
                proxySnapshotCalls.incrementAndGet();
            }
            return invocation.proceed();
        });
        return (ItemService) proxyFactory.getProxy();
    }

    private static ResourceServiceDelegatingStateSnapshotProvider snapshotProviderFor(Object bean) {
        StaticApplicationContext context = new StaticApplicationContext();
        context.getBeanFactory().registerSingleton("itemService", bean);
        @SuppressWarnings("unchecked")
        ObjectProvider<BaseResourceQueryService<?, ?, ?>> providerBean =
                (ObjectProvider<BaseResourceQueryService<?, ?, ?>>) (ObjectProvider<?>) context.getBeanProvider(
                        ResolvableType.forClass(BaseResourceQueryService.class));
        return new ResourceServiceDelegatingStateSnapshotProvider(providerBean);
    }

    private static final ResourceMapper<ItemEntity, ItemDto, Object, Object, Long> ITEM_MAPPER =
            new ResourceMapper<>() {
                @Override
                public ItemDto toResponse(ItemEntity entity) {
                    return new ItemDto(entity.id());
                }

                @Override
                public ItemEntity newEntity(Object dto) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public void applyUpdate(ItemEntity entity, Object dto) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public Long extractId(ItemEntity entity) {
                    return entity.id();
                }
            };

    interface ItemService extends BaseResourceQueryService<ItemDto, Long, ItemFilterDto> {
    }

    static class StandardItemService
            extends AbstractBaseQueryResourceService<ItemEntity, ItemDto, Long, ItemFilterDto>
            implements ItemService {
        StandardItemService() {
            super(null, ItemEntity.class);
        }

        StandardItemService(BaseCrudRepository<ItemEntity, Long> repo) {
            super(repo, ItemEntity.class);
        }

        @Override
        public String getResourceKey() {
            return "test.standard";
        }

        @Override
        protected ResourceMapper<ItemEntity, ItemDto, ?, ?, Long> getResourceMapper() {
            return ITEM_MAPPER;
        }

        @Override
        protected Optional<ResourceStateSnapshot> toResourceStateSnapshot(ItemEntity entity) {
            if ("NO_SNAPSHOT".equals(entity.state())) {
                return Optional.empty();
            }
            return Optional.of(ResourceStateSnapshot.of(entity.state(), Map.of("id", entity.id())));
        }
    }

    static class TargetItemService
            extends AbstractBaseQueryResourceService<ItemEntity, ItemDto, Long, ItemFilterDto>
            implements ItemService {
        private final boolean optIn;

        TargetItemService(BaseCrudRepository<ItemEntity, Long> repo, boolean optIn) {
            super(repo, ItemEntity.class);
            this.optIn = optIn;
        }

        @Override
        public String getResourceKey() {
            return "test.items";
        }

        @Override
        protected ResourceMapper<ItemEntity, ItemDto, ?, ?, Long> getResourceMapper() {
            return ITEM_MAPPER;
        }

        @Override
        protected boolean supportsSeededStateSnapshotReuse() {
            return optIn;
        }

        @Override
        protected Optional<ResourceStateSnapshot> toResourceStateSnapshot(ItemEntity entity) {
            if ("NO_SNAPSHOT".equals(entity.state())) {
                return Optional.empty();
            }
            return Optional.of(ResourceStateSnapshot.of(entity.state(), Map.of("id", entity.id())));
        }

        @Override
        public Optional<ResourceStateSnapshot> findResourceStateSnapshot(Long id) {
            return super.findResourceStateSnapshot(id);
        }
    }

    static class CustomOverrideItemService
            extends AbstractBaseQueryResourceService<ItemEntity, ItemDto, Long, ItemFilterDto>
            implements ItemService {
        private final AtomicInteger overrideCalls;

        CustomOverrideItemService(BaseCrudRepository<ItemEntity, Long> repo, AtomicInteger overrideCalls) {
            super(repo, ItemEntity.class);
            this.overrideCalls = overrideCalls;
        }

        @Override
        public String getResourceKey() {
            return "test.override";
        }

        @Override
        protected ResourceMapper<ItemEntity, ItemDto, ?, ?, Long> getResourceMapper() {
            return ITEM_MAPPER;
        }

        @Override
        protected Optional<ResourceStateSnapshot> toResourceStateSnapshot(ItemEntity entity) {
            return Optional.of(ResourceStateSnapshot.of(entity.state(), Map.of("id", entity.id())));
        }

        @Override
        public Optional<ResourceStateSnapshot> findResourceStateSnapshot(Long id) {
            overrideCalls.incrementAndGet();
            return Optional.of(ResourceStateSnapshot.of("CUSTOM_OVERRIDE", Map.of("id", id)));
        }
    }

    record ItemEntity(Long id, String state) {
    }

    record ItemDto(Long id) {
    }

    static final class ItemFilterDto implements GenericFilterDTO {
    }
}
