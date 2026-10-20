package org.praxisplatform.uischema.capability;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Escopo thread-local curto para deduplicar leituras de {@link ResourceStateSnapshotProvider}
 * durante a materializacao agregada de {@code itemCapabilities}.
 *
 * <p>
 * Quando {@link DefaultCapabilityService} compoe surfaces, actions e operacoes de um item no
 * mesmo ciclo, {@code DefaultSurfaceAvailabilityContextResolver},
 * {@code DefaultActionAvailabilityContextResolver} e o proprio {@code DefaultCapabilityService}
 * consultam o mesmo {@link ResourceStateSnapshotProvider} para o mesmo par
 * {@code (resourceKey, resourceId)}. Este escopo garante no maximo uma resolucao por instancia
 * de provider e chave de item dentro do ciclo fechado.
 * </p>
 */
public final class ResourceStateSnapshotScope implements AutoCloseable {

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
        CacheKey key = new CacheKey(provider, resourceKey, resourceId);
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
    }

    private record CacheKey(ResourceStateSnapshotProvider provider, String resourceKey, Object resourceId) {
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
                    && Objects.equals(this.resourceId, other.resourceId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(System.identityHashCode(provider), resourceKey, resourceId);
        }
    }
}
