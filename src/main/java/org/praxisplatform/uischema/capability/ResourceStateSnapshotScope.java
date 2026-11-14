package org.praxisplatform.uischema.capability;

import java.security.Principal;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.praxisplatform.uischema.surface.DefaultSurfaceAvailabilityContextResolver;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Escopo thread-local curto para deduplicar leituras de {@link ResourceStateSnapshotProvider}
 * durante guardas, links e materializacao contextual de capabilities, surfaces e actions.
 *
 * <p>
 * Todas as entradas cacheadas ou pré-carregadas carregam um {@link ContextStamp} capturado no
 * momento da gravacao (identidade da requisicao HTTP, headers neutros de tenant/ambiente, nome do
 * principal e authorities concedidas) para impedir reuso indevido caso o contexto efetivo mude
 * dentro da mesma thread.
 * </p>
 */
public final class ResourceStateSnapshotScope implements AutoCloseable {

    private static final String HEADER_TENANT = "X-Tenant";
    private static final String HEADER_PRAXIS_ENVIRONMENT = "X-Praxis-Environment";
    private static final String HEADER_ENV = "X-Env";
    private static final ThreadLocal<ScopeState> CURRENT = new ThreadLocal<>();

    private final ScopeState previous;
    private final ScopeState active;
    private boolean closed;

    private ResourceStateSnapshotScope(ScopeState previous, ScopeState active) {
        this.previous = previous;
        this.active = active;
    }

    public static ResourceStateSnapshotScope open() {
        ScopeState existing = CURRENT.get();
        if (existing != null) {
            return new ResourceStateSnapshotScope(existing, existing);
        }
        ScopeState created = new ScopeState();
        CURRENT.set(created);
        return new ResourceStateSnapshotScope(null, created);
    }

    public static boolean isOpen() {
        return CURRENT.get() != null;
    }

    public static void seedServiceSnapshot(
            Object serviceInstance,
            Object resourceId,
            Optional<ResourceStateSnapshot> snapshot
    ) {
        ScopeState scope = CURRENT.get();
        if (scope == null || serviceInstance == null || resourceId == null) {
            return;
        }
        scope.serviceSnapshots.put(
                new ServiceCacheKey(serviceInstance, resourceId),
                new SeededEntry(ContextStamp.captureCurrent(), normalize(snapshot))
        );
    }

    /**
     * Seeds a context-pinned snapshot indexed by {@code (resourceKey, resourceId)} for providers
     * when the owning service explicitly confirms that {@code findResourceStateSnapshot(id)} is
     * semantically equivalent to {@code toResourceStateSnapshot(entity)}.
     */
    public static void seedReusableResourceSnapshot(
            String resourceKey,
            Object resourceId,
            Optional<ResourceStateSnapshot> snapshot
    ) {
        ScopeState scope = CURRENT.get();
        if (scope == null || !StringUtils.hasText(resourceKey) || resourceId == null) {
            return;
        }
        scope.reusableResourceSnapshots.put(
                new ResourceCacheKey(resourceKey, resourceId),
                new SeededEntry(ContextStamp.captureCurrent(), normalize(snapshot))
        );
    }

    public static void seedResourceSnapshot(
            String resourceKey,
            Object resourceId,
            Optional<ResourceStateSnapshot> snapshot
    ) {
        seedReusableResourceSnapshot(resourceKey, resourceId, snapshot);
    }

    public static Optional<Optional<ResourceStateSnapshot>> findSeededServiceSnapshot(
            Object serviceInstance,
            Object resourceId
    ) {
        ScopeState scope = CURRENT.get();
        if (scope == null || serviceInstance == null || resourceId == null) {
            return Optional.empty();
        }
        SeededEntry entry = scope.serviceSnapshots.get(new ServiceCacheKey(serviceInstance, resourceId));
        if (entry == null || !Objects.equals(entry.contextStamp(), ContextStamp.captureCurrent())) {
            return Optional.empty();
        }
        return Optional.of(entry.snapshot());
    }

    public static Optional<Optional<ResourceStateSnapshot>> findReusableResourceSnapshot(
            String resourceKey,
            Object resourceId
    ) {
        ScopeState scope = CURRENT.get();
        if (scope == null || !StringUtils.hasText(resourceKey) || resourceId == null) {
            return Optional.empty();
        }
        SeededEntry entry = scope.reusableResourceSnapshots.get(new ResourceCacheKey(resourceKey, resourceId));
        if (entry == null || !Objects.equals(entry.contextStamp(), ContextStamp.captureCurrent())) {
            return Optional.empty();
        }
        return Optional.of(entry.snapshot());
    }

    public static Optional<Optional<ResourceStateSnapshot>> findSeededResourceSnapshot(
            String resourceKey,
            Object resourceId
    ) {
        return findReusableResourceSnapshot(resourceKey, resourceId);
    }

    public static Optional<ResourceStateSnapshot> resolve(
            ResourceStateSnapshotProvider provider,
            String resourceKey,
            Object resourceId
    ) {
        if (provider == null) {
            return Optional.empty();
        }
        ScopeState scope = CURRENT.get();
        if (scope == null) {
            return normalize(provider.resolve(resourceKey, resourceId));
        }
        CacheKey key = new CacheKey(provider, resourceKey, resourceId, ContextStamp.captureCurrent());
        if (scope.cache.containsKey(key)) {
            return scope.cache.get(key);
        }
        Optional<ResourceStateSnapshot> resolved = normalize(provider.resolve(resourceKey, resourceId));
        scope.cache.put(key, resolved);
        return resolved;
    }

    private static Optional<ResourceStateSnapshot> normalize(Optional<ResourceStateSnapshot> candidate) {
        return candidate == null ? Optional.empty() : candidate;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (CURRENT.get() != active) {
            return;
        }
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }

    private static final class ScopeState {
        private final Map<CacheKey, Optional<ResourceStateSnapshot>> cache = new HashMap<>();
        private final Map<ServiceCacheKey, SeededEntry> serviceSnapshots = new HashMap<>();
        private final Map<ResourceCacheKey, SeededEntry> reusableResourceSnapshots = new HashMap<>();
    }

    private record SeededEntry(ContextStamp contextStamp, Optional<ResourceStateSnapshot> snapshot) {
    }

    private record ContextStamp(
            Integer requestIdentity,
            String tenantHeader,
            String environmentHeader,
            String principalName,
            Set<String> authorities
    ) {
        private static ContextStamp captureCurrent() {
            Integer requestIdentity = null;
            String tenant = null;
            String environment = null;
            if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes sra) {
                var request = sra.getRequest();
                requestIdentity = System.identityHashCode(request);
                tenant = normalizeHeader(request.getHeader(HEADER_TENANT));
                environment = normalizeHeader(request.getHeader(HEADER_PRAXIS_ENVIRONMENT));
                if (environment == null) {
                    environment = normalizeHeader(request.getHeader(HEADER_ENV));
                }
            }
            Principal principal = DefaultSurfaceAvailabilityContextResolver.currentPrincipal();
            String principalName = principal != null ? normalizeHeader(principal.getName()) : null;
            Set<String> authorities = DefaultSurfaceAvailabilityContextResolver.currentPrincipalAuthorities();
            return new ContextStamp(requestIdentity, tenant, environment, principalName, authorities);
        }

        private static String normalizeHeader(String raw) {
            return StringUtils.hasText(raw) ? raw.trim() : null;
        }
    }

    private record ResourceCacheKey(String resourceKey, Object resourceId) {
    }

    private record ServiceCacheKey(Object serviceInstance, Object resourceId) {
        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof ServiceCacheKey other)) {
                return false;
            }
            return this.serviceInstance == other.serviceInstance
                    && Objects.equals(this.resourceId, other.resourceId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(System.identityHashCode(serviceInstance), resourceId);
        }
    }

    private record CacheKey(
            ResourceStateSnapshotProvider provider,
            String resourceKey,
            Object resourceId,
            ContextStamp contextStamp
    ) {
        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof CacheKey other)) {
                return false;
            }
            return this.provider == other.provider
                    && Objects.equals(this.resourceKey, other.resourceKey)
                    && Objects.equals(this.resourceId, other.resourceId)
                    && Objects.equals(this.contextStamp, other.contextStamp);
        }

        @Override
        public int hashCode() {
            return Objects.hash(System.identityHashCode(provider), resourceKey, resourceId, contextStamp);
        }
    }
}
